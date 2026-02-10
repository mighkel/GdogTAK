package com.gdogtak.ble

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.gdogtak.AppPreferences
import com.gdogtak.GeoUtils
import com.gdogtak.MainActivity
import com.gdogtak.R
import com.gdogtak.cot.CotGenerator
import com.gdogtak.tak.AtakBroadcaster
import kotlinx.coroutines.*
import java.util.*

/**
 * Background service that:
 * 1. Scans for and connects to Garmin Alpha handheld via BLE
 * 2. Sends init sequence to enable standalone streaming (no Garmin Explore needed!)
 * 3. Subscribes to position notifications
 * 4. Parses dog collar positions
 * 5. Broadcasts to ATAK via UDP multicast
 */
class BleTrackingService : Service() {
    
    companion object {
        private const val TAG = "BleTrackingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gdogtak_tracking"
        
        // Service actions
        const val ACTION_START = "com.gdogtak.START_TRACKING"
        const val ACTION_STOP = "com.gdogtak.STOP_TRACKING"
        
        // Broadcast actions for UI updates
        const val BROADCAST_STATUS = "com.gdogtak.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DOG_COUNT = "dog_count"
        const val EXTRA_LAST_LAT = "last_lat"
        const val EXTRA_LAST_LON = "last_lon"

        // Device list broadcast
        const val BROADCAST_DEVICES = "com.gdogtak.DEVICE_LIST_UPDATE"
        const val EXTRA_DEVICE_COUNT = "device_count"
        
        // Init sequence timing
        private const val INIT_STEP_DELAY_MS = 150L
        // CRITICAL: Increased delay - some Android BLE stacks lose notification registrations
        // if the next setCharacteristicNotification is called too quickly
        private const val CCCD_WRITE_DELAY_MS = 250L
    }
    
    // Service state
    enum class Status {
        IDLE, SCANNING, CONNECTING, CONNECTED, INITIALIZING, TRACKING, ERROR
    }
    
    private var currentStatus = Status.IDLE
    private var statusMessage = "Ready"
    
    // Bluetooth components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    
    // Handler for sequenced BLE operations
    private val bleHandler = Handler(Looper.getMainLooper())
    
    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // ATAK broadcaster
    private lateinit var atakBroadcaster: AtakBroadcaster
    
    // Dog configuration - loaded from SharedPreferences
    private lateinit var dogConfig: CotGenerator.DogConfig

    // Tracked device positions (handheld + collars + contacts)
    private var handheldPosition: GarminProtocol.DogPosition? = null
    private var contactPosition: GarminProtocol.DogPosition? = null

    // Dynamic collar device IDs from 229-byte device registry (command 07_16)
    private var registeredCollars: List<GarminProtocol.CollarRegistryEntry> = emptyList()
    private var registryParsed = false

    // Tracking stats
    private var positionCount = 0
    private var lastPosition: GarminProtocol.DogPosition? = null
    private var notificationCount = 0
    private var lastCollarDataTime = 0L  // Track time since last collar position
    
    // Init sequence state
    private var initSequenceStarted = false
    private var cccdWriteQueue: MutableList<BluetoothGattDescriptor> = mutableListOf()
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic2: BluetoothGattCharacteristic? = null  // Second write char for dual init
    private var controlInitChar: BluetoothGattCharacteristic? = null  // Control char for "02 00" app connect signal
    
    // Dynamically assigned data channel from Alpha
    private var assignedChannel: Byte = 0x0f  // Default, will be updated from response
    // Channel prefix for init commands - MUST use the device-assigned prefix!
    // Btsnoop analysis shows each session gets a unique channel (e.g., 0x42, 0x47)
    // and ALL subsequent commands must use that assigned prefix.
    private var channelPrefix: Byte = 0x00  // Will be set from device response

    // Device ID for init sequence (generated once per app install)
    private var deviceId: ByteArray? = null

    // Periodic polling state - keeps collar relay alive after init
    private var pollingRunnable: Runnable? = null
    private var pollingTickCount = 0
    private var collarSlotIndex = 0  // Cycles through 0x80-0x9E for re-registration
    private var relaySeqHi: Byte = 0x80.toByte()  // Rolling sequence for 02_35 commands
    private var relaySeqLo: Byte = 0xBF.toByte()  // Rolling counter for 02_35 commands

    // Fragmentation state for Garmin multi-packet protocol
    // CRITICAL: The fragment base is NOT always 0xC0! It's session-specific and MUST be
    // learned from the device's first notification. Garmin Explore mirrors the device's
    // fragment base for all outgoing writes. Using wrong base = device rejects all commands.
    // Example: device sends A0 → phone must use A0/A1/A2/A3, NOT C0/C1/C2/C3!
    private var fragmentBase: Int = 0xC0  // Learned from device notification (fallback 0xC0)
    private var fragmentBaseLearned = false
    private var fragmentGroup: Int = 0  // Rotates 0-3 for first byte low nibble
    private var fragmentSeq: Int = 0x40  // Sequence number, starts at 0x40, wraps

    // Negotiated MTU - default 23, request 232 for single-write commands
    private var negotiatedMtu: Int = 23
    
    // Binder for activity communication
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): BleTrackingService = this@BleTrackingService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        // Load dog config from SharedPreferences
        val prefs = AppPreferences(this)
        dogConfig = CotGenerator.DogConfig(
            uid = prefs.dogUid,
            callsign = prefs.dogCallsign,
            team = prefs.teamName
        )
        Log.i(TAG, "Dog config: callsign=${dogConfig.callsign}, uid=${dogConfig.uid}, team=${dogConfig.team}")

        // Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        // Initialize ATAK broadcaster
        atakBroadcaster = AtakBroadcaster(this)

        // Generate device ID for init sequence
        deviceId = generateDeviceId()

        // Create notification channel
        createNotificationChannel()
    }
    
    /**
     * Generate an 8-byte device ID for the Garmin init sequence.
     * TESTING: Using known-working ID from btsnoop capture.
     */
    private fun generateDeviceId(): ByteArray {
        // Known working device ID from btsnoop capture (Garmin Explore session)
        // This ID successfully triggered position streaming on the Alpha 300i
        val knownWorkingId = byteArrayOf(
            0x8d.toByte(), 0x3d.toByte(), 0xb0.toByte(), 0xe5.toByte(),
            0x92.toByte(), 0x59.toByte(), 0x03.toByte(), 0x3d.toByte()
        )
        Log.i(TAG, "Using known-working device ID from btsnoop")
        return knownWorkingId
        
        /* Original dynamic generation - uncomment once streaming works
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val hash = androidId.hashCode().toLong()
        val time = System.currentTimeMillis()
        val combined = hash xor time
        
        return byteArrayOf(
            ((combined shr 56) and 0xFF).toByte(),
            ((combined shr 48) and 0xFF).toByte(),
            ((combined shr 40) and 0xFF).toByte(),
            ((combined shr 32) and 0xFF).toByte(),
            ((combined shr 24) and 0xFF).toByte(),
            ((combined shr 16) and 0xFF).toByte(),
            ((combined shr 8) and 0xFF).toByte(),
            (combined and 0xFF).toByte()
        )
        */
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        serviceScope.cancel()
        bleHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Service destroyed")
    }
    
    /**
     * Start the tracking process
     */
    private fun startTracking() {
        Log.i(TAG, "Starting tracking")
        
        // Reset state
        initSequenceStarted = false
        positionCount = 0
        notificationCount = 0
        lastCollarDataTime = 0L
        registryParsed = false
        registeredCollars = emptyList()
        fragmentBaseLearned = false
        fragmentBase = 0xC0
        negotiatedMtu = 23
        
        // Start as foreground service
        startForegroundService()
        
        // Initialize broadcaster
        serviceScope.launch {
            val initialized = atakBroadcaster.initialize()
            if (!initialized) {
                updateStatus(Status.ERROR, "Failed to initialize network")
                return@launch
            }
            
            // Start BLE scan
            startBleScan()
        }
    }
    
    /**
     * Stop tracking and clean up
     */
    private fun stopTracking() {
        Log.i(TAG, "Stopping tracking")

        stopPeriodicPolling()
        stopBleScan()
        disconnectGatt()
        atakBroadcaster.shutdown()
        bleHandler.removeCallbacksAndMessages(null)
        
        updateStatus(Status.IDLE, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * Try to connect to a bonded Alpha device first, then fall back to BLE scan.
     * Bonded devices often don't advertise, so scanning alone won't find them.
     */
    private fun startBleScan() {
        if (isScanning) return

        if (!hasBluetoothPermissions()) {
            updateStatus(Status.ERROR, "Missing Bluetooth permissions")
            return
        }

        // First, check if we already have a bonded Alpha device
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            Log.i(TAG, "Checking ${bondedDevices.size} bonded devices for Alpha...")
            for (device in bondedDevices) {
                val name = try { device.name } catch (e: SecurityException) { null }
                Log.d(TAG, "  Bonded: ${name ?: "unknown"} (${device.address})")
                if (name != null && name.contains("Alpha", ignoreCase = true)) {
                    Log.i(TAG, "Found bonded Alpha: $name (${device.address}) - connecting directly")
                    updateStatus(Status.CONNECTING, "Connecting to bonded $name...")
                    connectToDevice(device)
                    return
                }
            }
            Log.i(TAG, "No bonded Alpha found, falling back to BLE scan")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot check bonded devices: ${e.message}")
        }

        // Fall back to BLE scan
        try {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true
            updateStatus(Status.SCANNING, "Scanning for Alpha...")

            Log.i(TAG, "BLE scan started (name-based filtering)")
        } catch (e: SecurityException) {
            updateStatus(Status.ERROR, "Bluetooth permission denied")
            Log.e(TAG, "Security exception starting scan", e)
        }
    }
    
    /**
     * Stop BLE scan
     */
    private fun stopBleScan() {
        if (!isScanning) return
        
        try {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
            Log.i(TAG, "BLE scan stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan", e)
        }
    }
    
    /**
     * BLE scan callback - filters by device name containing "Alpha"
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            var deviceName: String? = null
            
            try {
                deviceName = device.name
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot get device name: ${e.message}")
            }
            
            // Filter for Garmin Alpha devices by name
            if (deviceName != null && deviceName.contains("Alpha", ignoreCase = true)) {
                Log.i(TAG, "Found Alpha device: $deviceName (${device.address})")
                
                // Stop scanning and connect
                stopBleScan()
                connectToDevice(device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            updateStatus(Status.ERROR, "Scan failed: $errorCode")
        }
    }
    
    /**
     * Connect to a discovered Garmin Alpha
     */
    private fun connectToDevice(device: BluetoothDevice) {
        updateStatus(Status.CONNECTING, "Connecting to ${device.address}...")
        
        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            updateStatus(Status.ERROR, "Connection permission denied")
            Log.e(TAG, "Security exception connecting", e)
        }
    }
    
    /**
     * GATT callback for connection events
     */
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server, requesting MTU 232")
                    updateStatus(Status.CONNECTED, "Connected, negotiating MTU...")
                    try {
                        // Request large MTU BEFORE service discovery
                        // Garmin Explore uses MTU=232 for single-write commands
                        if (!gatt.requestMtu(232)) {
                            Log.w(TAG, "MTU request failed, proceeding with service discovery")
                            gatt.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception requesting MTU", e)
                        try { gatt.discoverServices() } catch (_: SecurityException) {}
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    stopPeriodicPolling()
                    updateStatus(Status.IDLE, "Disconnected")
                    // Try to reconnect
                    serviceScope.launch {
                        delay(2000)
                        startBleScan()
                    }
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            Log.i(TAG, ">>> MTU negotiated: $mtu (status=$status)")
            updateStatus(Status.CONNECTED, "Connected (MTU=$mtu), discovering services...")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception discovering services", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            
            Log.i(TAG, "Services discovered")
            
            // DEBUG: List ALL services found on device
            Log.i(TAG, "=== ALL SERVICES ON DEVICE ===")
            for (svc in gatt.services) {
                Log.i(TAG, "Service: ${svc.uuid}")
                Log.i(TAG, "  Type: ${if (svc.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "PRIMARY" else "SECONDARY"}")
                Log.i(TAG, "  Characteristics: ${svc.characteristics.size}")
                for (char in svc.characteristics) {
                    val propsStr = buildString {
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                    }
                    Log.i(TAG, "    ${char.uuid} inst=${char.instanceId} props=$propsStr")
                }
            }
            
            // Find the Garmin service
            val service = gatt.getService(UUID.fromString(GarminProtocol.SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Garmin service not found: ${GarminProtocol.SERVICE_UUID}")
                Log.e(TAG, ">>> The Alpha may need to be paired/bonded first!")
                Log.e(TAG, ">>> Try: Settings > Bluetooth > Pair with Alpha")
                updateStatus(Status.ERROR, "Garmin service not found - try pairing first")
                return
            }
            
            Log.i(TAG, ">>> Found Garmin service: ${service.uuid}")
            
            // DEBUG: List ALL characteristics in the service to see handles
            Log.i(TAG, "=== ALL CHARACTERISTICS IN GARMIN SERVICE ===")
            for (char in service.characteristics) {
                val propsStr = buildString {
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                Log.i(TAG, "  Char: ${char.uuid.toString().takeLast(8)} instance=${char.instanceId} props=$propsStr (${char.properties})")
            }
            
            // CRITICAL: Alpha writes "02 00" to handle 0x000D FIRST
            // Btsnoop analysis reveals this is the CCCD for "Service Changed" (00002a05)
            // This enables indications on Service Changed - standard BLE best practice
            // We need to find it across ALL services, not just Garmin
            val serviceChangedUuid = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            var serviceChangedCccd: BluetoothGattDescriptor? = null

            for (svc in gatt.services) {
                val serviceChangedChar = svc.characteristics.find { it.uuid == serviceChangedUuid }
                if (serviceChangedChar != null) {
                    serviceChangedCccd = serviceChangedChar.getDescriptor(cccdUuid)
                    if (serviceChangedCccd != null) {
                        Log.i(TAG, ">>> Found Service Changed CCCD in service ${svc.uuid.toString().takeLast(8)} instance=${serviceChangedChar.instanceId}")
                        break
                    }
                }
            }

            if (serviceChangedCccd != null) {
                // Store for later - we'll enable indications first
                controlInitChar = null  // Not used anymore
                Log.i(TAG, ">>> Will enable Service Changed indications (handle 0x000D equivalent)")
            } else {
                Log.w(TAG, "Service Changed characteristic not found - Alpha handshake may fail")
            }

            // Find write characteristics - get FIRST instance (lowest instanceId)
            // Alpha app uses specific instances that receive collar data
            val writeUuid1 = UUID.fromString(GarminProtocol.WRITE_CHAR_UUID)
            val writeUuid2 = UUID.fromString(GarminProtocol.WRITE_CHAR_UUID_2)

            writeCharacteristic = service.characteristics
                .filter { it.uuid == writeUuid1 }
                .minByOrNull { it.instanceId }
            writeCharacteristic2 = service.characteristics
                .filter { it.uuid == writeUuid2 }
                .minByOrNull { it.instanceId }

            if (writeCharacteristic == null) {
                Log.w(TAG, "Write characteristic 1 not found, init sequence may fail")
            } else {
                Log.i(TAG, "Write characteristic 1 found: ${GarminProtocol.WRITE_CHAR_UUID} instance=${writeCharacteristic?.instanceId}")
            }
            if (writeCharacteristic2 == null) {
                Log.w(TAG, "Write characteristic 2 not found")
            } else {
                Log.i(TAG, "Write characteristic 2 found: ${GarminProtocol.WRITE_CHAR_UUID_2} instance=${writeCharacteristic2?.instanceId}")
            }

            // Build list of notification characteristics to subscribe to
            // CRITICAL FIX: Subscribe to ALL notify channels (6a4e2810-2814)!
            // Btsnoop analysis shows coordinates arrive on handle 0x0012 (channel 1) and 0x0021 (channel 4)
            // NOT on channel 5 as previously assumed. We were missing all the data!
            cccdWriteQueue.clear()
            // cccdUuid already defined above for Service Changed

            // Subscribe to ALL notify characteristics - don't miss any data channels!
            Log.i(TAG, ">>> Subscribing to ALL ${GarminProtocol.NOTIFY_CHAR_UUIDS.size} notify channels")
            for (charUuid in GarminProtocol.NOTIFY_CHAR_UUIDS) {
                val uuid = UUID.fromString(charUuid)
                val characteristic = service.characteristics
                    .filter { it.uuid == uuid }
                    .minByOrNull { it.instanceId }

                if (characteristic != null) {
                    val descriptor = characteristic.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        cccdWriteQueue.add(descriptor)
                        // Log FULL UUID to identify active channels
                        Log.i(TAG, ">>> Queued subscription: ${charUuid} instance=${characteristic.instanceId}")
                    }
                } else {
                    Log.w(TAG, "Notify characteristic not found: $charUuid")
                }
            }

            // CRITICAL: Enable Service Changed indications FIRST (write 02 00 to handle 0x000D)
            // This is what Alpha app does before anything else
            if (serviceChangedCccd != null) {
                updateStatus(Status.INITIALIZING, "Enabling Service Changed indications...")
                enableServiceChangedIndications(gatt, serviceChangedCccd)
            } else if (cccdWriteQueue.isNotEmpty()) {
                // No Service Changed found, proceed with CCCD subscriptions
                updateStatus(Status.INITIALIZING, "Subscribing to ${cccdWriteQueue.size} channels...")
                writeNextCccd(gatt)
            } else {
                Log.e(TAG, "No characteristics found to subscribe to")
                updateStatus(Status.ERROR, "No notification characteristics found")
            }
        }
        
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic.uuid.toString().takeLast(8)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // CRITICAL FIX: Enable local notifications AFTER CCCD write succeeds
                // Some Android BLE stacks (like Motorola) lose notification registrations
                // if setCharacteristicNotification is called before the CCCD write completes.
                // By calling it here, we ensure each characteristic is properly registered
                // before moving to the next one.
                try {
                    gatt.setCharacteristicNotification(descriptor.characteristic, true)
                    Log.i(TAG, ">>> CCCD written OK + notification enabled for: $charUuid")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception enabling notification for $charUuid", e)
                }
            } else {
                Log.e(TAG, ">>> CCCD write FAILED: $status for $charUuid")
            }

            // Write next CCCD or start init sequence
            bleHandler.postDelayed({
                if (cccdWriteQueue.isNotEmpty()) {
                    Log.d(TAG, "CCCD queue remaining: ${cccdWriteQueue.size}")
                    writeNextCccd(gatt)
                } else if (!initSequenceStarted) {
                    Log.i(TAG, ">>> ALL CCCDs done - Starting init sequence!")
                    startInitSequence(gatt)
                }
            }, CCCD_WRITE_DELAY_MS)
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write success to: ${characteristic.uuid}")
            } else {
                Log.e(TAG, "Write failed: $status to ${characteristic.uuid}")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Parse the notification
            handleNotification(characteristic.uuid.toString(), value)
        }
        
        // Deprecated but needed for older Android versions
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { 
                handleNotification(characteristic.uuid.toString(), it) 
            }
        }
    }
    
    /**
     * Enable indications on Service Changed characteristic (write 02 00 to its CCCD)
     * This is the FIRST thing Alpha app does - writes to handle 0x000D
     * After this write, onDescriptorWrite will proceed with other CCCD subscriptions
     */
    private fun enableServiceChangedIndications(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        Log.i(TAG, ">>> ENABLING SERVICE CHANGED INDICATIONS (02 00 to CCCD, handle 0x000D equivalent)")

        try {
            // Write ENABLE_INDICATION_VALUE (0x0002 = "02 00") to the CCCD
            // Note: setCharacteristicNotification will be called in onDescriptorWrite after success
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            // onDescriptorWrite callback will handle enabling notification and processing queue
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception enabling Service Changed indications", e)
            // Proceed with CCCD subscriptions anyway
            if (cccdWriteQueue.isNotEmpty()) {
                writeNextCccd(gatt)
            }
        }
    }

    /**
     * Write next CCCD in queue
     *
     * Call setCharacteristicNotification BEFORE the CCCD write (standard pattern).
     * We also reinforce it AFTER success in onDescriptorWrite for compatibility
     * with devices that need callbacks registered after the CCCD write completes.
     */
    private fun writeNextCccd(gatt: BluetoothGatt) {
        if (cccdWriteQueue.isEmpty()) return

        val descriptor = cccdWriteQueue.removeAt(0)
        try {
            // Enable local notification callback BEFORE CCCD write
            gatt.setCharacteristicNotification(descriptor.characteristic, true)

            // Write to remote CCCD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            Log.d(TAG, "Writing CCCD for: ${descriptor.characteristic.uuid}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception writing CCCD", e)
        }
    }
    
    /**
     * Start the Garmin Alpha init sequence
     * This enables standalone streaming without Garmin Explore!
     */
    private fun startInitSequence(gatt: BluetoothGatt) {
        if (initSequenceStarted) return
        initSequenceStarted = true
        
        val writeChar = writeCharacteristic
        if (writeChar == null) {
            Log.w(TAG, "No write characteristic, skipping init sequence")
            updateStatus(Status.TRACKING, "Tracking (passive mode)")
            return
        }
        
        val id = deviceId ?: generateDeviceId()
        
        updateStatus(Status.INITIALIZING, "Sending init sequence...")
        Log.i(TAG, "Starting Garmin Alpha init sequence")
        Log.d(TAG, "Device ID: ${id.toHexString()}")
        
        // Send commands in phases, generating each command just before sending
        // This ensures we use the dynamically assigned channel prefix
        sendInitPhase1(gatt, writeChar, id)
    }
    
    /**
     * Phase 1: Device ID exchange
     */
    private fun sendInitPhase1(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, id: ByteArray) {
        // Alpha init sequence - Device ID exchange
        // cmd1: 00 05 [8-byte ID] 00 00 (12 bytes) - Device ID send
        // cmd2: 00 00 [8-byte ID] 04 00 00 (13 bytes) - Device ID confirm
        val cmd1 = byteArrayOf(0x00, 0x05).plus(id).plus(byteArrayOf(0x00, 0x00))
        val cmd2 = byteArrayOf(0x00, 0x00).plus(id).plus(byteArrayOf(0x04, 0x00, 0x00))

        Log.i(TAG, "Init phase 1 - Device ID exchange")
        Log.i(TAG, "  cmd1 (ID send, 12B): ${cmd1.toHexString()}")
        Log.i(TAG, "  cmd2 (ID confirm, 13B): ${cmd2.toHexString()}")

        // Send cmd1 first with explicit logging to debug the issue
        bleHandler.postDelayed({
            Log.i(TAG, ">>> SENDING CMD1 NOW (12 bytes)")
            try {
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = gatt.writeCharacteristic(char, cmd1, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    Log.i(TAG, ">>> CMD1 writeCharacteristic result: $result (0=success)")
                } else {
                    @Suppress("DEPRECATION")
                    char.value = cmd1
                    @Suppress("DEPRECATION")
                    val result = gatt.writeCharacteristic(char)
                    Log.i(TAG, ">>> CMD1 writeCharacteristic result: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, ">>> CMD1 EXCEPTION", e)
            }

            // Send cmd2 after delay
            bleHandler.postDelayed({
                Log.i(TAG, ">>> SENDING CMD2 NOW (13 bytes)")
                sendCommand(gatt, char, cmd2, "ID confirm")

                // Wait for response, then continue
                bleHandler.postDelayed({
                    sendInitPhase2(gatt, char, id)
                }, 300)
            }, INIT_STEP_DELAY_MS)
        }, 200)  // 200ms delay to ensure CCCD writes complete
    }
    
    /**
     * Phase 2: Channel enables (uses dynamically assigned prefix)
     */
    private fun sendInitPhase2(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, id: ByteArray) {
        Log.d(TAG, "Init phase 2 - Channel enables with prefix 0x${"%02x".format(channelPrefix)}")
        
        var delay = 0L
        for (channel in 0..4) {
            bleHandler.postDelayed({
                val cmd = byteArrayOf(channelPrefix, channel.toByte())
                sendCommand(gatt, char, cmd, "Channel $channel")
            }, delay)
            delay += INIT_STEP_DELAY_MS
        }
        
        bleHandler.postDelayed({
            sendInitPhase3(gatt, char, id)
        }, delay)
    }
    
    /**
     * Phase 3: Subscriptions and start streaming
     */
    private fun sendInitPhase3(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, id: ByteArray) {
        Log.d(TAG, "Init phase 3 - Subscriptions")
        
        val cmd1 = byteArrayOf(0x00, 0x00).plus(id).plus(byteArrayOf(0x16, 0x00, 0x00))
        val cmd2 = byteArrayOf(0x00, 0x00).plus(id).plus(byteArrayOf(0x01, 0x00, 0x02))  // 01 00 02 - matches Alpha's session byte
        val streamPrefix = (channelPrefix.toInt() + 1).toByte()
        val cmd3 = byteArrayOf(streamPrefix, 0x01, 0x19, 0x00, 0x00, 0x00)
        
        sendCommand(gatt, char, cmd1, "Subscribe ch16")
        
        bleHandler.postDelayed({
            sendCommand(gatt, char, cmd2, "Subscribe ch01")
        }, INIT_STEP_DELAY_MS)
        
        bleHandler.postDelayed({
            sendCommand(gatt, char, cmd3, "Start streaming (prefix 0x${"%02x".format(streamPrefix)})")
            
            // Wait for channel assignment, then send device registration
            bleHandler.postDelayed({
                Log.i(TAG, "Basic init complete, channel 0x${"%02x".format(assignedChannel)}")
                sendDeviceRegistration(gatt, char)
            }, 500)
        }, INIT_STEP_DELAY_MS * 2)
    }
    
    /**
     * Helper to send a single command
     */
    private fun sendCommand(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, cmd: ByteArray, desc: String) {
        Log.d(TAG, "Init: $desc -> ${cmd.toHexString()}")
        try {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = cmd
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Command failed: $desc", e)
        }
    }

    /**
     * Send message using Garmin fragmented multi-packet protocol
     *
     * Btsnoop analysis of Garmin Explore shows:
     * - First: 2-byte marker [0xC0+group] [sequence] is sent
     * - Then: fragmented data with same header prefix
     * - Each fragment max 20 bytes (18 bytes payload after 2-byte header)
     *
     * Example from btsnoop (12:32):
     *   C0 40                       <- 2-byte marker (start of fragmented message)
     *   C0 40 00 02 44 05 88 13...  <- fragment 0 (reuses same sequence!)
     *   C0 41 55 FF FF 13 6D 6F...  <- fragment 1
     *   C0 42 34 31 37 44 29 08...  <- fragment 2
     *   C0 43 6F 20 67 20 35 47...  <- fragment 3
     *
     * @param message The full message payload to fragment
     * @param desc Description for logging
     * @param sendMarker Whether to send 2-byte start marker (default true for large messages)
     * @return Delay in ms for all fragments to complete
     */
    private fun sendFragmented(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        message: ByteArray,
        desc: String,
        baseDelay: Long = 0L,
        sendMarker: Boolean = true
    ): Long {
        // CRITICAL: Always use base group (B0) for init commands - Garmin does NOT rotate groups
        // Group rotation (B0→B1→B2→B3) was wrong; Garmin keeps ALL init on same base group
        val baseGroupByte = fragmentBase.toByte()
        val maxPayload = negotiatedMtu - 3  // ATT header overhead

        // With high MTU (232), send as single write like Garmin Explore does
        // Garmin protocol for single-fragment messages:
        //   First message (02_44): marker [base][0x40] then data [base][0x40][payload]
        //   Subsequent msgs: data [base][seq|0x80][payload] with final flag, NO group rotation
        if (message.size + 2 <= maxPayload) {
            if (fragmentSeq == 0x40 && sendMarker) {
                // FIRST MESSAGE: send 2-byte marker then full data (like Garmin's 02_44)
                val marker = byteArrayOf(baseGroupByte, 0x40)
                val fullMessage = ByteArray(2 + message.size)
                fullMessage[0] = baseGroupByte
                fullMessage[1] = 0x40
                System.arraycopy(message, 0, fullMessage, 2, message.size)

                Log.i(TAG, ">>> MARKER+WRITE $desc: ${fullMessage.size}B base=0x${"%02x".format(fragmentBase)} (MTU=$negotiatedMtu)")

                bleHandler.postDelayed({
                    try {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(characteristic, marker, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = marker
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Marker write failed for $desc", e)
                    }
                }, baseDelay)

                bleHandler.postDelayed({
                    try {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(characteristic, fullMessage, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = fullMessage
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "First data write failed for $desc", e)
                    }
                }, baseDelay + 50L)

                fragmentSeq = 1  // Reset to 1 for subsequent single-fragment messages
                // DON'T rotate group - Garmin keeps all init on base group
                return baseDelay + 100L
            } else {
                // SUBSEQUENT MESSAGES: single write with FINAL FLAG (0x80), same base group
                val seqWithFinal = ((fragmentSeq and 0x7F) or 0x80).toByte()
                val fullMessage = ByteArray(2 + message.size)
                fullMessage[0] = baseGroupByte
                fullMessage[1] = seqWithFinal
                System.arraycopy(message, 0, fullMessage, 2, message.size)

                Log.i(TAG, ">>> SINGLE WRITE $desc: ${fullMessage.size}B base=0x${"%02x".format(fragmentBase)} seq=0x${"%02x".format(seqWithFinal)} (MTU=$negotiatedMtu)")

                bleHandler.postDelayed({
                    try {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(characteristic, fullMessage, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = fullMessage
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Single write failed for $desc", e)
                    }
                }, baseDelay)

                fragmentSeq++
                if (fragmentSeq > 0x7F) fragmentSeq = 0x01  // Wrap at 0x7F (leave room for final flag)
                // DON'T rotate group - Garmin keeps all init on base group
                return baseDelay + 50L
            }
        }

        // Fallback: fragment into 20-byte chunks (default MTU=23)
        val FRAGMENT_PAYLOAD_SIZE = 18  // 20 bytes total - 2 byte header
        val FRAGMENT_DELAY_MS = 50L     // Delay between fragments

        val numFragments = (message.size + FRAGMENT_PAYLOAD_SIZE - 1) / FRAGMENT_PAYLOAD_SIZE
        val startSeq = fragmentSeq
        // Use group rotation for multi-fragment messages (original behavior)
        val groupByte = (fragmentBase + fragmentGroup).toByte()

        Log.d(TAG, "Fragmenting $desc: ${message.size} bytes -> $numFragments fragments (group 0x${"%02x".format(groupByte)}, startSeq 0x${"%02x".format(startSeq)}, fragmentBase=0x${"%02x".format(fragmentBase)})")

        var currentDelay = baseDelay

        // Send 2-byte marker first if requested (Garmin does this for large messages)
        if (sendMarker && numFragments > 1) {
            val marker = byteArrayOf(groupByte, (startSeq and 0xFF).toByte())
            bleHandler.postDelayed({
                Log.d(TAG, ">>> FRAG MARKER: ${marker.toHexString()}")
                try {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(characteristic, marker, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = marker
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Fragment marker failed", e)
                }
            }, currentDelay)
            currentDelay += FRAGMENT_DELAY_MS
        }

        for (i in 0 until numFragments) {
            val offset = i * FRAGMENT_PAYLOAD_SIZE
            val payloadSize = minOf(FRAGMENT_PAYLOAD_SIZE, message.size - offset)

            // Build fragment: [group_byte] [sequence] [payload]
            val fragment = ByteArray(2 + payloadSize)
            fragment[0] = groupByte
            fragment[1] = (fragmentSeq and 0xFF).toByte()
            System.arraycopy(message, offset, fragment, 2, payloadSize)

            val fragNum = i
            val seqByte = fragmentSeq

            bleHandler.postDelayed({
                Log.d(TAG, ">>> FRAG $fragNum/$numFragments seq=0x${"%02x".format(seqByte)}: ${fragment.copyOf(minOf(10, fragment.size)).toHexString()}...")
                try {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(characteristic, fragment, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = fragment
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Fragment $fragNum failed", e)
                }
            }, currentDelay)

            currentDelay += FRAGMENT_DELAY_MS
            fragmentSeq++
            if (fragmentSeq > 0xFF) fragmentSeq = 0x40  // Wrap sequence
        }

        // Rotate to next group for next message
        fragmentGroup = (fragmentGroup + 1) and 0x03

        return currentDelay
    }

    /**
     * Send device registration using Garmin fragmented multi-packet protocol
     *
     * Btsnoop analysis shows Garmin Explore uses fragmentation:
     * - Fragment header: [0xC0+group] [0x40+seq]
     * - Payload: [00] [02 44 ...command data...]
     * - Each fragment max 20 bytes (18 bytes payload)
     *
     * OLD (not working): 06 00 02 44 05 88 13... (single 52-byte packet)
     * NEW (working):     C0 40 00 02 44 05 88... (fragmented across 4 packets)
     */
    private fun sendDeviceRegistration(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        Log.i(TAG, "Sending FRAGMENTED device registration (channel 0x${"%02x".format(assignedChannel)} assigned)")

        // Reset fragmentation state for new init sequence
        fragmentGroup = 0
        fragmentSeq = 0x40

        // Device registration command (02 44)
        // Payload format: [00] [02 44] [params] [len][name] [len][mfg] [len][model] [01] [CRC_HI] [CRC_LO] [00]
        // CRC computed with Garmin CRC-16 (poly=0x0241, init=0xA2E5) over bytes 02..01
        // Length prefix bytes MUST match actual string lengths!
        val deviceRegPayload = byteArrayOf(
            0x00,        // Leading byte for fragmented messages
            0x02, 0x44,  // command type
            0x05, 0x88.toByte(), 0x13, 0xa0.toByte(), 0x13,
            0x02, 0x96.toByte(), 0x3c,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xb5.toByte(), 0x55,
            0xff.toByte(), 0xff.toByte(),
            // Device name: "GdogTAK" (7 chars) - length prefix = 0x07
            0x07,
            0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b,  // "GdogTAK"
            // Manufacturer: "ATAK" (4 chars) - length prefix = 0x04
            0x04, 0x41, 0x54, 0x41, 0x4b,  // "ATAK"
            // Model: "GdogTAK-v1" (10 chars) - length prefix = 0x0A
            0x0a, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b, 0x2d, 0x76, 0x31,  // "GdogTAK-v1"
            // Terminator + CRC (Garmin CRC-16: poly=0x0241, init=0xA2E5) + end
            0x01, 0x40, 0xe8.toByte(), 0x00
        )

        Log.d(TAG, "Device reg payload: ${deviceRegPayload.size} bytes, will fragment")
        
        // ============================================================
        // EXACT GARMIN INIT SEQUENCE - matched byte-for-byte from btsnoop
        // ============================================================
        // Garmin Explore uses SPECIFIC fragment headers with group rotation:
        //   Group 0 (base+0): 02_44, 02_09, 02_16, marker+02_52
        //   Group 1 (base+1): 02_11, 02_06, 02_08
        //   Group 2 (base+2): 02_1D position query
        //   Group 3 (base+3): 02_1D subscribe, 02_11, 02_11
        // Previously we used sendFragmented() with NO group rotation - Alpha rejected it.

        // --- Payload definitions (each starts with 0x00 channel byte) ---

        // Config 02_09 - position subscribe config
        val config09Payload = byteArrayOf(0x00, 0x02, 0x09, 0x01, 0x04,
            0x81.toByte(), 0xba.toByte(), 0x13, 0x03, 0x9d.toByte(), 0xe9.toByte(), 0x00)

        // Config 02_16 - FIXED: byte at offset 14 = 0xA2 (was 0xA0), CRC=8387 (was A047)
        val config16Payload = byteArrayOf(0x00, 0x02, 0x16, 0x06, 0x32,
            0x80.toByte(), 0x0f, 0x50, 0x06, 0x02, 0x14, 0x01, 0x02, 0x04, 0x06,
            0xa2.toByte(), 0x20, 0x13, 0x1d, 0x49, 0x04, 0x01, 0x83.toByte(), 0x87.toByte(), 0x00)

        // Device list query 02_52
        val deviceList52Payload = byteArrayOf(0x00, 0x02, 0x52, 0x03, 0x2b, 0x81.toByte(),
            0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x3e, 0x01, 0x01, 0x02, 0x3e,
            0x01, 0x01, 0x32, 0x6a, 0x3c, 0x42, 0x3a, 0x0a, 0x24,
            0x34, 0x37, 0x31, 0x65, 0x39, 0x35, 0x39, 0x30, 0x2d, 0x38, 0x36,
            0x39, 0x36, 0x2d, 0x34, 0x61, 0x31, 0x39, 0x2d, 0x39, 0x64, 0x31,
            0x39, 0x2d, 0x35, 0x39, 0x37, 0x62, 0x36, 0x33, 0x39, 0x31, 0x38,
            0x30, 0x39, 0x37,
            0x6a, 0x02, 0x08, 0x02, 0x72, 0x02, 0x08, 0x05, 0xb2.toByte(), 0x01, 0x02, 0x08, 0x0a,
            0xc2.toByte(), 0x01, 0x04, 0x08, 0x01, 0x10, 0x01, 0xfb.toByte(), 0xd3.toByte(), 0x00)

        // Collar slot 1 (02_11, slot 0x82)
        val collarSlot1Raw = GarminProtocol.buildCollarSlotPacket(0x82, seqHi = 0x01, seqLo = 0x01)
        val collarSlot1Payload = ByteArray(1 + collarSlot1Raw.size)
        collarSlot1Payload[0] = 0x00
        System.arraycopy(collarSlot1Raw, 0, collarSlot1Payload, 1, collarSlot1Raw.size)

        // Enable streaming 02_06
        val enableStreamPayload = byteArrayOf(0x00, 0x02, 0x06, 0x05, 0x1f,
            0x82.toByte(), 0x88.toByte(), 0xd9.toByte(), 0x00)

        // Polling config 02_08
        val pollingCfgPayload = byteArrayOf(0x00, 0x02, 0x08, 0x04, 0x1e,
            0x83.toByte(), 0x08, 0x03, 0xf1.toByte(), 0x48, 0x00)

        // Position query 02_1D 04 2B (slot 01, full 31-byte command with protobuf + CRC)
        val posQueryPayload = byteArrayOf(0x00,
            0x02, 0x1D, 0x04, 0x2B,
            0x84.toByte(), 0x01,
            0x01, 0x01, 0x01, 0x01,
            0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
            0x01, 0x0C, 0xEA.toByte(), 0x01, 0x06, 0x0A, 0x04, 0x0A, 0x02, 0x10, 0x0B,
            0xE3.toByte(), 0x7B, 0x00)

        // Position subscribe 02_1D 01 04 (full command with protobuf + CRC)
        val posSubPayload = byteArrayOf(0x00,
            0x02, 0x1D, 0x01, 0x04,
            0x83.toByte(), 0xBC.toByte(), 0x13,
            0x0D, 0xBB.toByte(), 0xA2.toByte(), 0x14,
            0x06, 0xC3.toByte(), 0x98.toByte(), 0xEB.toByte(),
            0x43, 0x90.toByte(),
            0x90.toByte(), 0x9D.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x03,
            0xE5.toByte(), 0x71, 0x00)

        // Collar slots 2 and 3 (02_11, slots 0x83 and 0x84) - Garmin sends these after pos subscribe
        val collarSlot2Raw = GarminProtocol.buildCollarSlotPacket(0x83, seqHi = 0x01, seqLo = 0x02)
        val collarSlot2Payload = ByteArray(1 + collarSlot2Raw.size)
        collarSlot2Payload[0] = 0x00
        System.arraycopy(collarSlot2Raw, 0, collarSlot2Payload, 1, collarSlot2Raw.size)

        val collarSlot3Raw = GarminProtocol.buildCollarSlotPacket(0x84, seqHi = 0x01, seqLo = 0x03)
        val collarSlot3Payload = ByteArray(1 + collarSlot3Raw.size)
        collarSlot3Payload[0] = 0x00
        System.arraycopy(collarSlot3Raw, 0, collarSlot3Payload, 1, collarSlot3Raw.size)

        // --- Init messages with EXACT Garmin fragment headers from btsnoop ---
        // Each entry: (groupOffset, seqByte, payload or null for marker, description)
        // Group rotation: base+0, base+1, base+2, base+3
        // Seq bytes: 0x40=start marker, 0x80=final flag, 0xC0=start+final
        data class InitMsg(val grpOff: Int, val seq: Int, val data: ByteArray?, val desc: String)

        val initMessages = listOf(
            // GROUP 0: device reg, configs, device list
            InitMsg(0, 0x40, null, "Marker_02_44"),
            InitMsg(0, 0x40, deviceRegPayload, "DeviceReg_02_44"),
            InitMsg(0, 0x81, config09Payload, "Config_02_09"),
            InitMsg(0, 0x82, config16Payload, "Config_02_16"),
            InitMsg(0, 0xC0, null, "Marker_02_52"),
            InitMsg(0, 0xC3, deviceList52Payload, "DeviceList_02_52"),
            // GROUP 1: collar slot, enable streaming, polling config
            InitMsg(1, 0x44, collarSlot1Payload, "CollarSlot1_02_11"),
            InitMsg(1, 0x45, enableStreamPayload, "EnableStream_02_06"),
            InitMsg(1, 0x86, pollingCfgPayload, "PollingCfg_02_08"),
            // GROUP 2: position query
            InitMsg(2, 0x87, posQueryPayload, "PosQuery_02_1D"),
            // GROUP 3: position subscribe + extra collar slots
            InitMsg(3, 0x08, posSubPayload, "PosSub_02_1D"),
            InitMsg(3, 0x09, collarSlot2Payload, "CollarSlot2_02_11"),
            InitMsg(3, 0x0A, collarSlot3Payload, "CollarSlot3_02_11"),
        )

        Log.d(TAG, "GARMIN INIT: ${initMessages.size} messages with exact fragment headers (base=0x${"%02X".format(fragmentBase)})")

        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            // Send each message with EXACT Garmin fragment headers
            // No sendFragmented() - direct writes with pre-computed headers
            var delay = 0L
            for (msg in initMessages) {
                val groupByte = (fragmentBase + msg.grpOff).toByte()
                val seqByte = msg.seq.toByte()
                val d = delay

                if (msg.data == null) {
                    // Marker: 2 bytes [group][seq]
                    val marker = byteArrayOf(groupByte, seqByte)
                    bleHandler.postDelayed({
                        Log.i(TAG, ">>> MARKER ${msg.desc}: ${marker.toHexString()}")
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(characteristic, marker, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                            } else {
                                @Suppress("DEPRECATION")
                                characteristic.value = marker
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(characteristic)
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Marker failed: ${msg.desc}", e)
                        }
                    }, d)
                } else {
                    // Data: [group][seq][payload]
                    val packet = ByteArray(2 + msg.data.size)
                    packet[0] = groupByte
                    packet[1] = seqByte
                    System.arraycopy(msg.data, 0, packet, 2, msg.data.size)
                    bleHandler.postDelayed({
                        Log.i(TAG, ">>> WRITE ${msg.desc}: ${packet.size}B [${"%02X".format(groupByte)}][${"%02X".format(seqByte)}]")
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                            } else {
                                @Suppress("DEPRECATION")
                                characteristic.value = packet
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(characteristic)
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Write failed: ${msg.desc}", e)
                        }
                    }, d)
                }
                delay += 50L
            }

            bleHandler.postDelayed({
                Log.i(TAG, "Init sequence complete! (${initMessages.size} messages with group rotation)")
                // Set fragment state for polling phase - continue from where init left off
                // Init ended at group 3, seq 0x0A (last message was CollarSlot3)
                fragmentGroup = 4  // next group after init's 0-3
                fragmentSeq = 0x0B  // next seq after init's 0x0A
                Log.i(TAG, "Fragment state for polling: group=$fragmentGroup, seq=0x${"%02X".format(fragmentSeq)}")
                Log.i(TAG, "Starting polling on char1 (matching Garmin Explore)")
                startPeriodicPolling(gatt, characteristic)
                updateStatus(Status.TRACKING, "Tracking active")
            }, delay)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception sending device registration", e)
            updateStatus(Status.ERROR, "Device registration failed")
        }
    }
    
    /**
     * Second init phase on characteristic 2 (6a4e2820)
     * Alpha app sends to BOTH characteristics - this may be what triggers collar data routing
     */
    private fun startSecondInit(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        // Commands for second characteristic (from Alpha btsnoop frames 292-340)
        // These use shortened ID format
        val id = generateDeviceId()
        val shortId = id.sliceArray(2 until 8)  // Skip first 2 bytes of ID
        
        val commands = mutableListOf<ByteArray>()
        
        // Step 1: Send shortened device ID
        commands.add(shortId.plus(byteArrayOf(0x00, 0x00)))
        
        // Step 2: Confirm device ID  
        commands.add(shortId.plus(byteArrayOf(0x04, 0x00, 0x00)))
        
        // Step 3: Empty commands (channel enables already done)
        for (i in 0..4) {
            commands.add(byteArrayOf())  // Empty writes
        }
        
        // Step 4: Channel subscriptions with short ID
        commands.add(shortId.plus(byteArrayOf(0x16, 0x00, 0x00)))
        commands.add(shortId.plus(byteArrayOf(0x01, 0x00, 0x00)))  // 01 00 00 like Alpha!
        
        // Step 5: Start streaming
        commands.add(byteArrayOf(0x00, 0x00, 0x00))
        
        // Step 6: Device registration (no prefix) - NOTE: this function is not currently called
        commands.add(byteArrayOf(
            0x02, 0x44, 0x05, 0x88.toByte(), 0x13, 0xa0.toByte(), 0x13,
            0x02, 0x96.toByte(), 0x3c,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xb5.toByte(), 0x55,
            0xff.toByte(), 0xff.toByte(),
            0x07, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b,  // "GdogTAK"
            0x04, 0x41, 0x54, 0x41, 0x4b,  // "ATAK"
            0x0a, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b, 0x2d, 0x76, 0x31,  // "GdogTAK-v1"
            0x01, 0x40, 0xe8.toByte(), 0x00  // CRC=40E8 (Garmin CRC-16, poly=0x0241, init=0xA2E5)
        ))
        
        // Step 7: Config commands
        commands.add(byteArrayOf(0x02, 0x09, 0x01, 0x04, 
            0x81.toByte(), 0xba.toByte(), 0x13, 0x03, 0x9d.toByte(), 0xe9.toByte(), 0x00))
        commands.add(byteArrayOf(0x02, 0x13, 0x06, 0x32, 
            0x80.toByte(), 0x0c, 0x50, 0x06, 0x02, 0x14, 0x01, 0x02, 0x04, 0x07,
            0xa0.toByte(), 0x20, 0x13, 0x1d, 0x11, 0x93.toByte(), 0x00))
        
        // Device list query
        commands.add(byteArrayOf(0x02, 0x52, 0x03, 0x2b, 0x81.toByte(),
            0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x3e, 0x01, 0x01, 0x02, 0x3e,
            0x01, 0x01, 0x32, 0x6a, 0x3c, 0x42, 0x3a, 0x0a, 0x24,
            0x34, 0x37, 0x31, 0x65, 0x39, 0x35, 0x39, 0x30, 0x2d, 0x38, 0x36,
            0x39, 0x36, 0x2d, 0x34, 0x61, 0x31, 0x39, 0x2d, 0x39, 0x64, 0x31,
            0x39, 0x2d, 0x35, 0x39, 0x37, 0x62, 0x36, 0x33, 0x39, 0x31, 0x38,
            0x30, 0x39, 0x37,
            0x6a, 0x02, 0x08, 0x02, 0x72, 0x02, 0x08, 0x05, 0xb2.toByte(), 0x01, 0x02, 0x08, 0x0a,
            0xc2.toByte(), 0x01, 0x04, 0x08, 0x01, 0x10, 0x01, 0xfb.toByte(), 0xd3.toByte(), 0x00))
        
        Log.i(TAG, "Sending ${commands.size} commands to second characteristic")
        
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        
        var delay = 0L
        for ((index, cmd) in commands.withIndex()) {
            if (cmd.isEmpty()) continue
            
            bleHandler.postDelayed({
                try {
                    Log.d(TAG, "Second init ${index + 1}/${commands.size}: ${cmd.toHexString()}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(characteristic, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = cmd
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Second init command failed", e)
                }
            }, delay)
            delay += 150L
        }
        
        bleHandler.postDelayed({
            Log.i(TAG, ">>> DUAL INIT COMPLETE - Starting periodic polling")
            // Start periodic polling on char2 (6a4e2821) - this is where Alpha sends 02_35 relay commands
            // Init was on char1 (6a4e2824), but polling goes to char2 (matching Alpha app pattern)
            val pollingChar = writeCharacteristic2 ?: writeCharacteristic
            if (pollingChar != null) {
                startPeriodicPolling(gatt, pollingChar)
            }
            updateStatus(Status.TRACKING, "Tracking (no 02_26, full init)")
        }, delay)
    }
    
    /**
     * Start periodic polling after init completes.
     * The Alpha app continuously sends commands to keep the collar relay alive.
     * Key commands: 02_35 (collar relay), 02_08 (polling), 02_34 (position request), 02_11 (collar slot re-registration).
     * Without these, the Alpha stops relaying collar positions after ~1 update.
     * The Alpha app sends 02_35 at ~3/sec (391x in a working session) - this is the most critical.
     *
     * IMPORTANT: Android BLE only allows ONE outstanding write at a time. Each writeCharacteristic
     * call must wait for onCharacteristicWrite callback before the next. We use postDelayed with
     * 200ms gaps (same as init sequence) to avoid write collisions.
     */
    private fun startPeriodicPolling(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        stopPeriodicPolling()
        pollingTickCount = 0
        collarSlotIndex = 0

        val configPrefix = (channelPrefix + 2).toByte()
        Log.i(TAG, "Polling configPrefix=0x${"%02x".format(configPrefix)} (channelPrefix=0x${"%02x".format(channelPrefix)})")

        // Send an initial burst of 02_08 polling commands (Alpha sends 7 during init)
        // Use 250ms gaps to ensure each write completes before the next
        Log.i(TAG, "Sending initial 02_08 polling burst (5 commands, 250ms apart)")
        for (i in 0 until 5) {
            bleHandler.postDelayed({
                val g = bluetoothGatt
                if (g == null) {
                    Log.e(TAG, "Poll burst ${i + 1}: gatt is null!")
                    return@postDelayed
                }
                val cmd = buildPollingCommand(configPrefix)
                val fragged = wrapFragmented(cmd)
                Log.i(TAG, "Poll burst ${i + 1}/5: ${fragged.toHexString()}")
                sendCommandLogged(g, characteristic, fragged, "Poll burst ${i + 1}")
            }, (i * 250).toLong())
        }

        // After 02_08 burst, send 02_1D position query burst - CRITICAL for position streaming!
        // Garmin Explore sends these during init; without them, no 02_3C/02_7A position data flows.
        // FULL 02_1D position query burst with continuation data + CRC
        // Using exact Garmin format: command + protobuf extension + CRC + 00
        val fullPosQueries = listOf(
            // Slot 01 (seq=84): continuation EA 01 06 0A 04 0A 02 10 0B, CRC=E37B
            byteArrayOf(configPrefix, 0x00,
                0x02, 0x1D, 0x04, 0x2B, 0x84.toByte(), 0x01,
                0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                0x01, 0x0C, 0xEA.toByte(), 0x01, 0x06, 0x0A, 0x04, 0x0A, 0x02, 0x10, 0x0B,
                0xE3.toByte(), 0x7B, 0x00),
            // Slot 02 (seq=85): continuation DA 02 06 4A 04 32 02 08 03, CRC=F679
            byteArrayOf(configPrefix, 0x00,
                0x02, 0x1D, 0x04, 0x2B, 0x85.toByte(), 0x02,
                0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                0x01, 0x0C, 0xDA.toByte(), 0x02, 0x06, 0x4A, 0x04, 0x32, 0x02, 0x08, 0x03,
                0xF6.toByte(), 0x79, 0x00),
            // Slot 03 (seq=86): continuation DA 02 06 4A 04 32 02 08 03, CRC=DF1A
            byteArrayOf(configPrefix, 0x00,
                0x02, 0x1D, 0x04, 0x2B, 0x86.toByte(), 0x03,
                0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                0x01, 0x0C, 0xDA.toByte(), 0x02, 0x06, 0x4A, 0x04, 0x32, 0x02, 0x08, 0x03,
                0xDF.toByte(), 0x1A, 0x00)
        )
        Log.i(TAG, "Sending FULL 02_1D position query burst (${fullPosQueries.size} commands with continuation)")
        for ((idx, cmd) in fullPosQueries.withIndex()) {
            bleHandler.postDelayed({
                val g = bluetoothGatt
                if (g == null) {
                    Log.e(TAG, "PosQuery ${idx + 1}: gatt is null!")
                    return@postDelayed
                }
                val fragged = wrapFragmented(cmd)
                Log.i(TAG, "PosQuery FULL ${idx + 1}/${fullPosQueries.size} (${fragged.size}B): ${fragged.toHexString()}")
                sendCommandLogged(g, characteristic, fragged, "PosQuery FULL slot ${idx + 1}")
            }, (5 * 250 + 500 + idx * 250).toLong())
        }

        pollingRunnable = object : Runnable {
            override fun run() {
                val g = bluetoothGatt
                if (g == null) {
                    Log.e(TAG, "Periodic poll: gatt is null, stopping polling")
                    return
                }
                pollingTickCount++

                // Send ONE command per tick to avoid BLE write collisions.
                // 02_35 (collar relay) is the most frequent - sent on most ticks.
                // Alpha sends it ~3/sec (391x in working session) vs 02_11 (~10/sec).
                // Diagnostic: warn if no collar data after 60 seconds of polling
                if (pollingTickCount == 30 && positionCount == 0) {
                    Log.w(TAG, ">>> DIAGNOSTIC: 60 seconds of polling, ZERO collar positions received!")
                    Log.w(TAG, "    Notifications received: $notificationCount total")
                    Log.w(TAG, "    Registry parsed: $registryParsed, collars: ${registeredCollars.size}")
                    Log.w(TAG, "    Handheld position: ${handheldPosition != null}")
                    Log.w(TAG, "    This may indicate the Alpha app was recently connected.")
                    Log.w(TAG, "    Try: power-cycle the Alpha 300i, then start GdogTAK WITHOUT the Alpha app.")
                    updateStatus(Status.TRACKING, "Tracking - waiting for collar data...")
                }

                try {
                    val cmdToSend: ByteArray
                    val cmdDesc: String

                    when {
                        // Every 30th tick (~1 minute): GPS update
                        pollingTickCount % 30 == 0 -> {
                            cmdToSend = buildGpsUpdateCommand(configPrefix)
                            cmdDesc = "GPS update 02_37"
                        }
                        // Every 15th tick (~30 seconds): position request
                        pollingTickCount % 15 == 0 -> {
                            cmdToSend = buildPositionRequestCommand(configPrefix)
                            cmdDesc = "Pos request 02_34"
                        }
                        // Every 10th tick (~20 seconds): polling config
                        pollingTickCount % 10 == 0 -> {
                            cmdToSend = buildPollingCommand(configPrefix)
                            cmdDesc = "Poll config 02_08"
                        }
                        // Every 7th tick (~14 seconds): FULL position query 02_1D with continuation
                        // Uses fixed seq/CRC from Garmin capture (seq varies per slot: 84/85/86)
                        pollingTickCount % 7 == 0 -> {
                            val slotIdx = (pollingTickCount / 7) % 3  // 0, 1, 2
                            // Full commands with protobuf continuation + CRC (exact Garmin bytes)
                            val fullCmds = arrayOf(
                                // Slot 01 (seq=84)
                                byteArrayOf(configPrefix, 0x00,
                                    0x02, 0x1D, 0x04, 0x2B, 0x84.toByte(), 0x01,
                                    0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                                    0x01, 0x0C, 0xEA.toByte(), 0x01, 0x06, 0x0A, 0x04, 0x0A, 0x02, 0x10, 0x0B,
                                    0xE3.toByte(), 0x7B, 0x00),
                                // Slot 02 (seq=85)
                                byteArrayOf(configPrefix, 0x00,
                                    0x02, 0x1D, 0x04, 0x2B, 0x85.toByte(), 0x02,
                                    0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                                    0x01, 0x0C, 0xDA.toByte(), 0x02, 0x06, 0x4A, 0x04, 0x32, 0x02, 0x08, 0x03,
                                    0xF6.toByte(), 0x79, 0x00),
                                // Slot 03 (seq=86)
                                byteArrayOf(configPrefix, 0x00,
                                    0x02, 0x1D, 0x04, 0x2B, 0x86.toByte(), 0x03,
                                    0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                                    0x01, 0x0C, 0xDA.toByte(), 0x02, 0x06, 0x4A, 0x04, 0x32, 0x02, 0x08, 0x03,
                                    0xDF.toByte(), 0x1A, 0x00)
                            )
                            cmdToSend = fullCmds[slotIdx]
                            cmdDesc = "Pos query 02_1D FULL slot ${slotIdx + 1}"
                        }
                        // Every 5th tick (~10 seconds): collar slot re-registration
                        pollingTickCount % 5 == 0 -> {
                            val slot = 0x80 + collarSlotIndex
                            val slotCmd = GarminProtocol.buildCollarSlotPacket(
                                slot, seqHi = 0x02, seqLo = pollingTickCount and 0xFF
                            )
                            val prefixed = ByteArray(2 + slotCmd.size)
                            prefixed[0] = configPrefix
                            prefixed[1] = 0x00
                            System.arraycopy(slotCmd, 0, prefixed, 2, slotCmd.size)
                            cmdToSend = prefixed
                            cmdDesc = "Re-reg slot 0x${"%02x".format(slot)}"
                            collarSlotIndex = (collarSlotIndex + 1) % 31
                        }
                        // All other ticks: collar relay 02_35 (the critical missing command)
                        else -> {
                            cmdToSend = buildCollarRelayCommand(configPrefix)
                            cmdDesc = "Collar relay 02_35"
                        }
                    }

                    // Log full relay command on first send for diagnostic verification
                    if (pollingTickCount <= 2 && cmdDesc.contains("02_35")) {
                        Log.i(TAG, "Poll #$pollingTickCount: $cmdDesc FULL (${cmdToSend.size}B): ${cmdToSend.toHexString()}")
                        Log.i(TAG, "    Using ${if (registeredCollars.isNotEmpty()) "dynamic" else "hardcoded"} collar ID")
                    } else {
                        Log.i(TAG, "Poll #$pollingTickCount: $cmdDesc (${cmdToSend.size}B) ${cmdToSend.copyOf(minOf(6, cmdToSend.size)).toHexString()}")
                    }
                    val fragCmd = wrapFragmented(cmdToSend)
                    sendCommandLogged(g, characteristic, fragCmd, cmdDesc)
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic polling error", e)
                }

                bleHandler.postDelayed(this, 350)  // 350ms interval to match Alpha app cadence (~300-400ms)
            }
        }

        // Start periodic polling after init burst completes
        bleHandler.postDelayed(pollingRunnable!!, 3000 + 5 * 250)
        Log.i(TAG, "Periodic polling scheduled (350ms interval with 02_35 relay - matches Alpha cadence)")
    }

    private fun stopPeriodicPolling() {
        pollingRunnable?.let {
            bleHandler.removeCallbacks(it)
            pollingRunnable = null
            Log.i(TAG, "Periodic polling stopped")
        }
    }

    /** Send a command and log the result at Log.i level */
    private fun sendCommandLogged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, cmd: ByteArray, desc: String) {
        try {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(char, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                Log.i(TAG, ">>> WRITE $desc: result=$result (0=OK)")
            } else {
                @Suppress("DEPRECATION")
                char.value = cmd
                @Suppress("DEPRECATION")
                val result = gatt.writeCharacteristic(char)
                Log.i(TAG, ">>> WRITE $desc: result=$result")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, ">>> WRITE $desc: SecurityException", e)
        } catch (e: Exception) {
            Log.e(TAG, ">>> WRITE $desc: Exception", e)
        }
    }

    /**
     * Wrap a command in Garmin fragment headers for the polling phase.
     *
     * Garmin Explore wraps ALL post-init commands in fragment headers:
     *   Input:  [configPrefix][0x00][cmd...]  (channel-prefix format)
     *   Output: [base+group][seq][0x00][cmd...]  (fragment-header format)
     *
     * The Alpha ignores commands without fragment headers after init!
     * Fragment group rotates 0-15, seq increments 0-63 then wraps.
     */
    private fun wrapFragmented(cmd: ByteArray): ByteArray {
        val grpByte = (fragmentBase + (fragmentGroup and 0x0F)).toByte()
        val seqByte = (fragmentSeq and 0x3F).toByte()

        // Replace [configPrefix] with [base+group][seq], keeping [0x00][cmd...] from position 1
        val wrapped = ByteArray(cmd.size + 1)
        wrapped[0] = grpByte
        wrapped[1] = seqByte
        System.arraycopy(cmd, 1, wrapped, 2, cmd.size - 1)

        // Advance fragment counters
        fragmentSeq++
        if (fragmentSeq > 0x3F) fragmentSeq = 0
        fragmentGroup = (fragmentGroup + 1) and 0x0F  // rotate every message

        return wrapped
    }

    /** Build 02_08 polling command with channel prefix */
    private fun buildPollingCommand(configPrefix: Byte): ByteArray {
        // From BR5 capture: 02 08 04 1e [seq] [xx] 03 [checksum] 00
        // Using a static version - Alpha accepts repeated commands
        val body = byteArrayOf(
            0x02, 0x08, 0x04, 0x1e,
            0x83.toByte(), 0x08, 0x03,
            0xf1.toByte(), 0x48, 0x00
        )
        val cmd = ByteArray(2 + body.size)
        cmd[0] = configPrefix
        cmd[1] = 0x00
        System.arraycopy(body, 0, cmd, 2, body.size)
        return cmd
    }

    /** Build 02_34 position request command with channel prefix */
    private fun buildPositionRequestCommand(configPrefix: Byte): ByteArray {
        // From BR5 capture: 57-byte position request
        val body = byteArrayOf(
            0x02, 0x34, 0x04, 0x2b,
            0x80.toByte(), 0x13, 0x01, 0x01, 0x01, 0x01,
            0x02, 0x20, 0x01, 0x01, 0x02, 0x20, 0x01, 0x01,
            0x23, 0xfa.toByte(), 0x01, 0x1d, 0x1a, 0x1b, 0x12, 0x19,
            0xaa.toByte(), 0x06, 0x16, 0x0a, 0x14, 0x22, 0x12,
            0x09, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x18, 0x02, 0x20, 0x01,
            0x10, 0x46, 0x71, 0x6f, 0x00
        )
        val cmd = ByteArray(2 + body.size)
        cmd[0] = configPrefix
        cmd[1] = 0x00
        System.arraycopy(body, 0, cmd, 2, body.size)
        return cmd
    }

    /** Build 02_37 GPS update command with channel prefix */
    private fun buildGpsUpdateCommand(configPrefix: Byte): ByteArray {
        // From BR5 capture: 60-byte GPS position update
        // Reuse the same bytes from init (static coords for now)
        val body = byteArrayOf(
            0x02, 0x37, 0x04, 0x2b,
            0x88.toByte(), 0x05,
            0x01, 0x01, 0x01, 0x01,
            0x02, 0x23, 0x01, 0x01, 0x02, 0x23, 0x01, 0x01,
            0x14, 0x82.toByte(), 0x02, 0x20,
            0x0a, 0x1e, 0x0a, 0x1a, 0x08, 0x01, 0x12, 0x12,
            0x09,
            0x12, 0x44, 0xd8.toByte(), 0x41, 0x83.toByte(), 0xb6.toByte(), 0x32, 0x12,
            0x11,
            0xdb.toByte(), 0xe5.toByte(), 0xa5.toByte(), 0xb7.toByte(), 0x52, 0x7b, 0x26, 0xb6.toByte(),
            0x18, 0x02, 0x20, 0x01,
            0x10, 0x46, 0x71, 0x6f, 0x00
        )
        val cmd = ByteArray(2 + body.size)
        cmd[0] = configPrefix
        cmd[1] = 0x00
        System.arraycopy(body, 0, cmd, 2, body.size)
        return cmd
    }

    /**
     * Build 02_35 collar relay command with channel prefix.
     * This is the most critical periodic command - Alpha sends it ~3/sec (391x in a working session).
     * The command contains collar subscription data with a rolling sequence counter.
     * Uses dynamically discovered collar device IDs from the 229-byte device registry.
     * Falls back to hardcoded Shadow ID if no registry data available.
     */
    private fun buildCollarRelayCommand(configPrefix: Byte): ByteArray {
        // Get the collar device ID - use dynamic registry or fallback to hardcoded
        val collarId: ByteArray = if (registeredCollars.isNotEmpty()) {
            registeredCollars[0].fullEntry  // Use first registered collar's full 16-byte entry
        } else {
            // Fallback: hardcoded Shadow collar entry from BR5 capture
            byteArrayOf(
                0x33, 0x91.toByte(), 0x77, 0xcd.toByte(), 0x01, 0x01, 0x02, 0x80.toByte(),
                0x02, 0x10, 0x01, 0x03, 0x4b, 0x01, 0x01, 0x0f
            )
        }

        // Body from BR5 capture - the bulk 02_35 pattern (05 2c variant)
        // Bytes 5-6 are rolling counters that we increment each call
        // NOTE: No leading 0x00 here - the channel byte is added below as cmd[1]
        val header = byteArrayOf(
            0x02, 0x35, 0x05, 0x2c,
            relaySeqHi, relaySeqLo,
            0x0f, 0x01, 0x01, 0x01,
            0x02, 0x21, 0x01, 0x01, 0x02, 0x21, 0x01, 0x01,
            0x0f, 0xb2.toByte(), 0x01, 0x1e, 0xea.toByte(), 0x01, 0x1b,
            0x0a, 0x19, 0x0a, 0x10
        )
        val trailer = byteArrayOf(
            0x0a, 0x10, 0x20,
            0x18, 0x84.toByte(), 0x01, 0x28, 0x75,
            0xb5.toByte(), 0x1b, 0x00
        )
        val body = header + collarId + trailer

        // Increment rolling sequence counters (mimic Alpha's cycling pattern)
        relaySeqLo++
        if (relaySeqLo == 0x00.toByte()) {
            val hi = (relaySeqHi.toInt() and 0xFF)
            relaySeqHi = if (hi >= 0x9F) 0x80.toByte() else (hi + 1).toByte()
        }

        val cmd = ByteArray(2 + body.size)
        cmd[0] = configPrefix
        cmd[1] = 0x00
        System.arraycopy(body, 0, cmd, 2, body.size)
        return cmd
    }

    /**
     * Handle incoming BLE notification with position data
     */
    private fun handleNotification(charUuid: String, data: ByteArray) {
        notificationCount++

        // CRITICAL: Learn fragment base from device's first notification
        // Device notifications start with a high byte (>=0x80) that encodes the fragment group.
        // Garmin Explore mirrors this base for ALL outgoing fragmented writes.
        // Example: if device sends A0, we must use A0/A1/A2/A3 (NOT hardcoded C0!)
        if (!fragmentBaseLearned && data.size >= 3) {
            val firstByte = data[0].toInt() and 0xFF
            if (firstByte in 0x80..0xEF) {
                fragmentBase = firstByte and 0xF0
                fragmentBaseLearned = true
                Log.i(TAG, ">>> FRAGMENT BASE LEARNED: 0x${"%02X".format(fragmentBase)} (from notification byte 0x${"%02X".format(firstByte)})")
            }
        }

        // Identify notification command type for diagnostics
        val cmdInfo = if (data.size >= 5) {
            val hi = data[3].toInt() and 0xFF
            val lo = data[4].toInt() and 0xFF
            "%02X_%02X".format(hi, lo)
        } else "tiny"

        // Only log details for significant notifications (>10 bytes)
        if (data.size > 10) {
            Log.i(TAG, ">>> NOTIF #$notificationCount cmd=$cmdInfo size=${data.size} char=${charUuid.takeLast(8)}")
            Log.i(TAG, "Raw data: ${data.toHexString()}")
        } else {
            // Reduce noise: only log small ACK packets at debug level
            Log.d(TAG, "NOTIF #$notificationCount cmd=$cmdInfo size=${data.size}")
        }
        
        // Parse channel prefix from device ID confirm response
        // Format: 00 01 [8-byte ID] 04 00 00 [prefix] 00 01
        // Channel prefix response handling - device assigns unique channel per session
        // Btsnoop shows each session gets different prefix (e.g., 0x42, 0x47)
        // MUST use this prefix for all subsequent commands to work!
        if (data.size >= 16 && data[0] == 0x00.toByte() && data[1] == 0x01.toByte()) {
            // Check command type byte at position 10
            val cmdByte = data[10].toInt() and 0xFF

            if (cmdByte == 0x04 && data.size >= 14) {
                // Device ID confirm response - CRITICAL: use the assigned channel prefix!
                // Btsnoop shows each session gets a unique prefix (e.g., 0x42, 0x47)
                // and ALL subsequent commands must use that assigned prefix.
                val devicePrefix = data[13]
                channelPrefix = devicePrefix
                Log.i(TAG, ">>> CHANNEL PREFIX ASSIGNED: 0x${"%02x".format(channelPrefix)} - will use for all commands")
            } else if (cmdByte == 0x01 && data.size >= 14) {
                // Channel 1 subscription response - extract assigned channel
                val newChannel = data[13]
                if (newChannel != 0x00.toByte()) {
                    assignedChannel = newChannel
                    Log.i(TAG, ">>> CHANNEL ASSIGNED: 0x${"%02x".format(newChannel)} (${newChannel.toInt() and 0xFF})")
                }
            }
        }
        
        // Check for device registry notification (229-byte 07_16 packet)
        if (GarminProtocol.isDeviceRegistryPacket(data)) {
            val entries = GarminProtocol.parseDeviceRegistry(data)
            if (entries.isNotEmpty() && !registryParsed) {
                registeredCollars = entries
                registryParsed = true
                Log.i(TAG, ">>> DEVICE REGISTRY: ${entries.size} collar(s) discovered:")
                for (entry in entries) {
                    Log.i(TAG, "    Collar ID: ${entry.deviceIdHex()}")
                }
            }
            // Registry packets don't contain position data, skip further parsing
            return
        }

        val position = GarminProtocol.parseNotification(data)

        if (position == null) {
            Log.i(TAG, "Parse result: null (no valid position found)")
            return
        }

        Log.i(TAG, ">>> PARSED: type=${position.deviceType}, lat=${position.latitude}, lon=${position.longitude}")

        when (position.deviceType) {
            GarminProtocol.DeviceType.COLLAR -> {
                positionCount++
                lastPosition = position
                lastCollarDataTime = System.currentTimeMillis()

                Log.i(TAG, ">>> DOG POSITION #$positionCount: ${position.latitude}, ${position.longitude}")

                // Generate and send CoT
                val cot = CotGenerator.generateCot(position, dogConfig)
                Log.d(TAG, ">>> Generated CoT for ${dogConfig.callsign}")

                serviceScope.launch {
                    val sent = atakBroadcaster.sendCot(cot)
                    Log.i(TAG, ">>> CoT SENT: $sent")

                    if (sent) {
                        updateStatus(
                            Status.TRACKING,
                            "Tracking: $positionCount positions",
                            position.latitude,
                            position.longitude
                        )
                    } else {
                        Log.e(TAG, ">>> CoT send FAILED!")
                    }
                }

                // Update notification
                updateNotification("Tracking: ${position.latitude.format(5)}, ${position.longitude.format(5)}")

                // Broadcast device list update
                broadcastDeviceList()
            }

            GarminProtocol.DeviceType.CONTACT -> {
                contactPosition = position
                Log.i(TAG, ">>> CONTACT position: ${position.latitude}, ${position.longitude}")
                // Broadcast device list update with contact info
                broadcastDeviceList()
            }

            GarminProtocol.DeviceType.HANDHELD -> {
                handheldPosition = position
                Log.d(TAG, "Handheld position: ${position.latitude}, ${position.longitude}")
                // Broadcast device list when handheld updates
                broadcastDeviceList()
            }
        }
    }
    
    /**
     * Disconnect GATT
     */
    private fun disconnectGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            writeCharacteristic = null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception disconnecting", e)
        }
    }
    
    /**
     * Check for required Bluetooth permissions
     */
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == 
                PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
                PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Update status and broadcast to UI
     */
    /**
     * Broadcast the tracked device list to the UI.
     * Tracks: handheld (reference), collar(s), and contact(s).
     */
    private fun broadcastDeviceList() {
        val intent = Intent(BROADCAST_DEVICES)
        var idx = 0
        val hh = handheldPosition

        // Handheld device (reference point)
        if (hh != null) {
            intent.putExtra("device_${idx}_id", "handheld")
            intent.putExtra("device_${idx}_label", "Alpha 300")
            intent.putExtra("device_${idx}_collar", false)
            intent.putExtra("device_${idx}_lat", hh.latitude)
            intent.putExtra("device_${idx}_lon", hh.longitude)
            intent.putExtra("device_${idx}_time", hh.timestamp)
            intent.putExtra("device_${idx}_bearing", Double.NaN)
            intent.putExtra("device_${idx}_distance", Double.NaN)
            intent.putExtra("device_${idx}_type", "handheld")
            idx++
        }

        // Collar device (if we have a position)
        val collar = lastPosition
        if (collar != null) {
            intent.putExtra("device_${idx}_id", dogConfig.uid)
            intent.putExtra("device_${idx}_label", dogConfig.callsign)
            intent.putExtra("device_${idx}_collar", true)
            intent.putExtra("device_${idx}_lat", collar.latitude)
            intent.putExtra("device_${idx}_lon", collar.longitude)
            intent.putExtra("device_${idx}_time", collar.timestamp)
            intent.putExtra("device_${idx}_type", "collar")

            if (hh != null) {
                intent.putExtra("device_${idx}_bearing", GeoUtils.calculateBearing(
                    hh.latitude, hh.longitude, collar.latitude, collar.longitude))
                intent.putExtra("device_${idx}_distance", GeoUtils.calculateDistance(
                    hh.latitude, hh.longitude, collar.latitude, collar.longitude))
            } else {
                intent.putExtra("device_${idx}_bearing", Double.NaN)
                intent.putExtra("device_${idx}_distance", Double.NaN)
            }
            idx++
        }

        // Contact device (remote Alpha handheld seen via VHF)
        val contact = contactPosition
        if (contact != null) {
            intent.putExtra("device_${idx}_id", "contact")
            intent.putExtra("device_${idx}_label", "Contact")
            intent.putExtra("device_${idx}_collar", false)
            intent.putExtra("device_${idx}_lat", contact.latitude)
            intent.putExtra("device_${idx}_lon", contact.longitude)
            intent.putExtra("device_${idx}_time", contact.timestamp)
            intent.putExtra("device_${idx}_type", "contact")

            if (hh != null) {
                intent.putExtra("device_${idx}_bearing", GeoUtils.calculateBearing(
                    hh.latitude, hh.longitude, contact.latitude, contact.longitude))
                intent.putExtra("device_${idx}_distance", GeoUtils.calculateDistance(
                    hh.latitude, hh.longitude, contact.latitude, contact.longitude))
            } else {
                intent.putExtra("device_${idx}_bearing", Double.NaN)
                intent.putExtra("device_${idx}_distance", Double.NaN)
            }
            idx++
        }

        intent.putExtra(EXTRA_DEVICE_COUNT, idx)
        sendBroadcast(intent)
    }

    private fun updateStatus(
        status: Status,
        message: String,
        lat: Double? = null,
        lon: Double? = null
    ) {
        currentStatus = status
        statusMessage = message
        
        Log.i(TAG, "Status: $status - $message")
        
        // Broadcast to UI
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status.name)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_DOG_COUNT, positionCount)
            lat?.let { putExtra(EXTRA_LAST_LAT, it) }
            lon?.let { putExtra(EXTRA_LAST_LON, it) }
        }
        sendBroadcast(intent)
    }
    
    fun getStatus(): Status = currentStatus
    fun getStatusMessage(): String = statusMessage
    fun getPositionCount(): Int = positionCount
    
    /**
     * Start as foreground service with notification
     */
    private fun startForegroundService() {
        val notification = createNotification("Starting...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    /**
     * Create notification channel (required for Android 8+)
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dog Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows dog tracking status"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * Create the service notification
     */
    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BleTrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GdogTAK")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Update the notification text
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
    
    // Helper to convert hex string to ByteArray
    private fun bytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    private fun ByteArray.toHexString() = joinToString("-") { "%02X".format(it) }
}
