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
        
        // Init sequence timing
        private const val INIT_STEP_DELAY_MS = 150L
        private const val CCCD_WRITE_DELAY_MS = 100L
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
    
    // Dog configuration (TODO: make configurable via settings)
    private val dogConfig = CotGenerator.DogConfig(
        uid = "GDOG-K9-001",
        callsign = "K9-DOG1",
        team = "SAR"
    )
    
    // Tracking stats
    private var positionCount = 0
    private var lastPosition: GarminProtocol.DogPosition? = null
    
    // Init sequence state
    private var initSequenceStarted = false
    private var cccdWriteQueue: MutableList<BluetoothGattDescriptor> = mutableListOf()
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic2: BluetoothGattCharacteristic? = null  // Second write char for dual init
    
    // Dynamically assigned data channel from Alpha
    private var assignedChannel: Byte = 0x0f  // Default, will be updated from response
    private var channelPrefix: Byte = 0x2f  // Channel prefix from device, varies each session

    // Device ID for init sequence (generated once per app install)
    private var deviceId: ByteArray? = null

    // Periodic polling state - keeps collar relay alive after init
    private var pollingRunnable: Runnable? = null
    private var pollingTickCount = 0
    private var collarSlotIndex = 0  // Cycles through 0x80-0x9E for re-registration
    
    // Binder for activity communication
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): BleTrackingService = this@BleTrackingService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        
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
                    Log.i(TAG, "Connected to GATT server")
                    updateStatus(Status.CONNECTED, "Connected, discovering services...")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception discovering services", e)
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
            
            // Build list of FIRST instance of each notification characteristic to subscribe to
            // Alpha subscribes to ONE specific instance that receives collar data
            cccdWriteQueue.clear()
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            
            for (charUuid in GarminProtocol.NOTIFY_CHAR_UUIDS) {
                val uuid = UUID.fromString(charUuid)
                // Get FIRST instance (lowest instanceId) of this characteristic
                val characteristic = service.characteristics
                    .filter { it.uuid == uuid }
                    .minByOrNull { it.instanceId }
                    
                if (characteristic != null) {
                    val descriptor = characteristic.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        cccdWriteQueue.add(descriptor)
                        Log.i(TAG, "Queued subscription for: $charUuid instance=${characteristic.instanceId}")
                    }
                } else {
                    Log.w(TAG, "Characteristic not found: $charUuid")
                }
            }
            
            // Start subscribing to characteristics
            if (cccdWriteQueue.isNotEmpty()) {
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
                Log.i(TAG, ">>> CCCD written OK for: $charUuid")
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
     * Write next CCCD in queue
     */
    private fun writeNextCccd(gatt: BluetoothGatt) {
        if (cccdWriteQueue.isEmpty()) return
        
        val descriptor = cccdWriteQueue.removeAt(0)
        try {
            // Enable local notifications
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
        val cmd1 = byteArrayOf(0x00, 0x05).plus(id).plus(byteArrayOf(0x00, 0x00))
        val cmd2 = byteArrayOf(0x00, 0x00).plus(id).plus(byteArrayOf(0x04, 0x00, 0x00))
        
        Log.d(TAG, "Init phase 1 - Device ID exchange")
        sendCommand(gatt, char, cmd1, "ID send")
        
        bleHandler.postDelayed({
            sendCommand(gatt, char, cmd2, "ID confirm")
            
            // Wait for response with channel prefix, then continue
            bleHandler.postDelayed({
                sendInitPhase2(gatt, char, id)
            }, 300)  // Wait for prefix response
        }, INIT_STEP_DELAY_MS)
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
     * Send device registration packet
     * Based on Alpha app protocol - NO channel prefix, just raw 02 44 format
     */
    private fun sendDeviceRegistration(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        Log.i(TAG, "Sending device registration (channel 0x${"%02x".format(assignedChannel)} assigned)")
        
        // Channel prefix for config commands = channelPrefix + 2
        // e.g., if prefix is 0x15, config prefix is 0x17
        val configPrefix = (channelPrefix + 2).toByte()
        Log.d(TAG, "Config command prefix: 0x${"%02x".format(configPrefix)}")
        
        // Build device registration WITH channel prefix!
        // Format: [prefix+2] 00 02 44 [params] [device_name] [manufacturer] [model]
        val deviceRegBase = byteArrayOf(
            0x02, 0x44,  // command type
            0x05, 0x88.toByte(), 0x13, 0xa0.toByte(), 0x13, 
            0x02, 0x96.toByte(), 0x3c, 
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xb5.toByte(), 0x55, 0xff.toByte(), 0xff.toByte(),
            // Device name: "GdogTAK" (7 chars) - length-prefixed
            0x07, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b,
            // Manufacturer: "ATAK" (4 chars)  
            0x04, 0x41, 0x54, 0x41, 0x4b,
            // Model: "GdogTAK-v1" (10 chars)
            0x0a, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b, 0x2d, 0x76, 0x31,
            // Terminator
            0x01, 0xf8.toByte(), 0xa4.toByte(), 0x00
        )
        // Prepend channel prefix - build explicitly to avoid any array issues
        val deviceRegPacket = ByteArray(2 + deviceRegBase.size)
        deviceRegPacket[0] = configPrefix
        deviceRegPacket[1] = 0x00
        System.arraycopy(deviceRegBase, 0, deviceRegPacket, 2, deviceRegBase.size)
        
        Log.d(TAG, "Device reg packet size: ${deviceRegPacket.size}, first 4 bytes: ${deviceRegPacket.copyOf(4).toHexString()}")
        
        // Config commands - all need [prefix+2] 00 header prepended
        // Build explicitly to ensure prefix is included
        val configCommandsBase = listOf(
            // Position subscribe: 02 09 01 04 81 ba 13 03 9d e9 00
            byteArrayOf(0x02, 0x09, 0x01, 0x04, 
                0x81.toByte(), 0xba.toByte(), 0x13, 0x03, 0x9d.toByte(), 0xe9.toByte(), 0x00),
            // Config 0x16 - matches Alpha's registration: 02 16 06 32 80 0f 50 06 02 14 01 02 04 06 a0 20 13 1d 49 04 01 a0 47 00
            byteArrayOf(0x02, 0x16, 0x06, 0x32,
                0x80.toByte(), 0x0f, 0x50, 0x06, 0x02, 0x14, 0x01, 0x02, 0x04, 0x06,
                0xa0.toByte(), 0x20, 0x13, 0x1d, 0x49, 0x04, 0x01, 0xa0.toByte(), 0x47, 0x00),
            // DOG LIST SYNC - 02 26 command that triggers collar data relay!
            byteArrayOf(0x02, 0x26, 0x03, 0x2b, 0x81.toByte(),
                0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x12, 0x01, 0x01, 0x02, 0x12,
                0x01, 0x01, 0x12, 0x6a, 0x10, 0x42, 0x0e, 0x62, 0x04, 0x08,
                0x80.toByte(), 0x86.toByte(), 0x1c, 0x6a, 0x02, 0x08, 0x02, 0x72, 0x02, 0x08, 0x03,
                0x06, 0x56, 0x00)
            // Note: Collar slot registrations (02 11) will be added dynamically below
        )
        
        // Dynamically generate collar slot registrations (02 11) for slots 0x80-0x9F
        // with computed checksums (reverse-engineered from Dec 8 btsnoop capture)
        val collarSlotCommands = (0x80..0x9E).mapIndexed { index, slot ->
            val seqLo = index + 1
            GarminProtocol.buildCollarSlotPacket(slot, seqHi = 0x02, seqLo = seqLo)
        }
        Log.d(TAG, "Generated ${collarSlotCommands.size} collar slot registrations with computed checksums")
        
        // Combine base config + collar slots + remaining commands
        val remainingCommands = listOf(
            // Enable streaming: 02 06 05 1f 82 88 d9 00
            byteArrayOf(0x02, 0x06, 0x05, 0x1f, 
                0x82.toByte(), 0x88.toByte(), 0xd9.toByte(), 0x00),
            // Polling config: 02 08 04 1e 83 08 03 f1 48 00
            byteArrayOf(0x02, 0x08, 0x04, 0x1e, 
                0x83.toByte(), 0x08, 0x03, 0xf1.toByte(), 0x48, 0x00),
            // Device list query: 02 52 03 2b 81...
            byteArrayOf(0x02, 0x52, 0x03, 0x2b, 0x81.toByte(),
                0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x3e, 0x01, 0x01, 0x02, 0x3e,
                0x01, 0x01, 0x32, 0x6a, 0x3c, 0x42, 0x3a, 0x0a, 0x24,
                0x34, 0x37, 0x31, 0x65, 0x39, 0x35, 0x39, 0x30, 0x2d, 0x38, 0x36,
                0x39, 0x36, 0x2d, 0x34, 0x61, 0x31, 0x39, 0x2d, 0x39, 0x64, 0x31,
                0x39, 0x2d, 0x35, 0x39, 0x37, 0x62, 0x36, 0x33, 0x39, 0x31, 0x38,
                0x30, 0x39, 0x37,
                0x6a, 0x02, 0x08, 0x02, 0x72, 0x02, 0x08, 0x05, 0xb2.toByte(), 0x01, 0x02, 0x08, 0x0a,
                0xc2.toByte(), 0x01, 0x04, 0x08, 0x01, 0x10, 0x01, 0xfb.toByte(), 0xd3.toByte(), 0x00),
            // Additional poll commands
            byteArrayOf(0x02, 0x1d, 0x04, 0x2b, 0x84.toByte(),
                0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                0x01, 0x0c, 0xea.toByte(), 0x01, 0x06, 0x0a, 0x04, 0x0a, 0x02, 0x10, 0x0b,
                0xe3.toByte(), 0x7b, 0x00),
            byteArrayOf(0x02, 0x1d, 0x04, 0x2b, 0x85.toByte(),
                0x02, 0x01, 0x01, 0x01, 0x01, 0x02, 0x09, 0x01, 0x01, 0x02, 0x09, 0x01,
                0x01, 0x0c, 0xda.toByte(), 0x02, 0x06, 0x4a, 0x04, 0x32, 0x02, 0x08, 0x03,
                0xf6.toByte(), 0x79, 0x00),
            // GPS position command (02 37) - tells Alpha the phone's location
            // Captured from Alpha app btsnoop: enables collar position relay
            byteArrayOf(0x02, 0x37, 0x04, 0x2b,
                0x88.toByte(), 0x05,                                     // sequence
                0x01, 0x01, 0x01, 0x01,                                  // padding
                0x02, 0x23, 0x01, 0x01, 0x02, 0x23, 0x01, 0x01,         // sub-headers
                0x14, 0x82.toByte(), 0x02, 0x20,                         // protobuf framing
                0x0a, 0x1e, 0x0a, 0x1a, 0x08, 0x01, 0x12, 0x12,         // coord block header
                0x09,                                                    // lat field tag (fixed64)
                0x12, 0x44, 0xd8.toByte(), 0x41, 0x83.toByte(), 0xb6.toByte(), 0x32, 0x12,  // lat
                0x11,                                                    // lon field tag (fixed64)
                0xdb.toByte(), 0xe5.toByte(), 0xa5.toByte(), 0xb7.toByte(), 0x52, 0x7b, 0x26, 0xb6.toByte(),  // lon
                0x18, 0x02, 0x20, 0x01,                                  // trailing fields
                0x10, 0x46, 0x71, 0x6f, 0x00)                            // checksum + terminator
        )
        
        // Combine all commands: base config + collar slots + remaining
        val allCommandsBase = configCommandsBase + collarSlotCommands + remainingCommands
        
        // Prepend channel prefix to all config commands - build explicitly
        val configCommands = allCommandsBase.map { cmd ->
            val prefixedCmd = ByteArray(2 + cmd.size)
            prefixedCmd[0] = configPrefix
            prefixedCmd[1] = 0x00
            System.arraycopy(cmd, 0, prefixedCmd, 2, cmd.size)
            prefixedCmd
        }
        
        Log.d(TAG, "Total config commands: ${configCommands.size} (including ${collarSlotCommands.size} collar slots)")
        Log.d(TAG, "Device registration: ${deviceRegPacket.toHexString()}")
        Log.d(TAG, "First config cmd size: ${configCommands[0].size}, first 4 bytes: ${configCommands[0].copyOf(4).toHexString()}")
        
        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            
            // Send device registration - log exactly what we're about to write
            Log.d(TAG, ">>> WRITING device reg (${deviceRegPacket.size} bytes): ${deviceRegPacket.copyOf(6).toHexString()}...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.d(TAG, "Using Android 13+ write API")
                gatt.writeCharacteristic(characteristic, deviceRegPacket, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                Log.d(TAG, "Using deprecated write API")
                @Suppress("DEPRECATION")
                characteristic.value = deviceRegPacket
                val charVal = characteristic.value
                Log.d(TAG, "Char value set to (${charVal?.size} bytes): ${charVal?.copyOf(6)?.toHexString()}...")
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            
            // Send config commands with delays
            var delay = 200L
            for (cmd in configCommands) {
                bleHandler.postDelayed({
                    Log.d(TAG, ">>> WRITING config (${cmd.size} bytes): ${cmd.copyOf(6).toHexString()}...")
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(characteristic, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = cmd
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Config command failed", e)
                    }
                }, delay)
                delay += 200L
            }
            
            bleHandler.postDelayed({
                Log.i(TAG, "Init sequence on char1 complete!")
                
                // Now do second init on characteristic 2 (6a4e2820)
                // Alpha app inits on BOTH characteristics!
                val writeChar2 = writeCharacteristic2
                if (writeChar2 != null) {
                    Log.i(TAG, "Starting second init on ${GarminProtocol.WRITE_CHAR_UUID_2}")
                    startSecondInit(gatt, writeChar2)
                } else {
                    Log.w(TAG, "Second write characteristic not available, skipping")
                    startPeriodicPolling(gatt, characteristic)
                    updateStatus(Status.TRACKING, "Tracking active (standalone)")
                }
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
        
        // Step 6: Device registration (no prefix)
        commands.add(byteArrayOf(
            0x02, 0x44, 0x05, 0x88.toByte(), 0x13, 0xa0.toByte(), 0x13, 
            0x02, 0x96.toByte(), 0x3c,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xb5.toByte(), 0x55, 0xff.toByte(), 0xff.toByte(),
            0x07, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b,  // "GdogTAK"
            0x04, 0x41, 0x54, 0x41, 0x4b,  // "ATAK"
            0x0a, 0x47, 0x64, 0x6f, 0x67, 0x54, 0x41, 0x4b, 0x2d, 0x76, 0x31,  // "GdogTAK-v1"
            0x01, 0xf8.toByte(), 0xa4.toByte(), 0x00
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
            // Start periodic polling on char1 (the main data characteristic)
            val mainChar = writeCharacteristic
            if (mainChar != null) {
                startPeriodicPolling(gatt, mainChar)
            }
            updateStatus(Status.TRACKING, "Tracking active (dual init + polling)")
        }, delay)
    }
    
    /**
     * Start periodic polling after init completes.
     * The Alpha app continuously sends commands to keep the collar relay alive.
     * Key commands: 02_08 (polling), 02_34 (position request), 02_11 (collar slot re-registration).
     * Without these, the Alpha stops relaying collar positions after ~1 update.
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
                Log.i(TAG, "Poll burst ${i + 1}/5: ${cmd.toHexString()}")
                sendCommandLogged(g, characteristic, cmd, "Poll burst ${i + 1}")
            }, (i * 250).toLong())
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
                // Rotate through: collar slot re-reg (most ticks), polling, position req, GPS
                try {
                    val cmdToSend: ByteArray
                    val cmdDesc: String

                    when {
                        // Every 24th tick (~2 minutes): GPS update
                        pollingTickCount % 24 == 0 -> {
                            cmdToSend = buildGpsUpdateCommand(configPrefix)
                            cmdDesc = "GPS update 02_37"
                        }
                        // Every 12th tick (~1 minute): position request
                        pollingTickCount % 12 == 0 -> {
                            cmdToSend = buildPositionRequestCommand(configPrefix)
                            cmdDesc = "Pos request 02_34"
                        }
                        // Every 6th tick (~30 seconds): polling config
                        pollingTickCount % 6 == 0 -> {
                            cmdToSend = buildPollingCommand(configPrefix)
                            cmdDesc = "Poll config 02_08"
                        }
                        // All other ticks: collar slot re-registration
                        else -> {
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
                    }

                    Log.i(TAG, "Poll #$pollingTickCount: $cmdDesc (${cmdToSend.size}B) ${cmdToSend.copyOf(minOf(6, cmdToSend.size)).toHexString()}")
                    sendCommandLogged(g, characteristic, cmdToSend, cmdDesc)
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic polling error", e)
                }

                bleHandler.postDelayed(this, 5000)  // 5-second interval
            }
        }

        // Start periodic polling 3 seconds after init burst completes
        bleHandler.postDelayed(pollingRunnable!!, 3000 + 5 * 250)
        Log.i(TAG, "Periodic polling scheduled (5-second interval, starts after burst)")
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
     * Handle incoming BLE notification with position data
     */
    private fun handleNotification(charUuid: String, data: ByteArray) {
        // Log ALL notifications for debugging
        Log.i(TAG, ">>> BLE NOTIFICATION received! Char: ${charUuid.takeLast(8)}, Size: ${data.size}")
        
        if (data.size > 10) {
            // Log raw hex for position packet debugging
            Log.i(TAG, "Raw data: ${data.toHexString()}")
        }
        
        // Parse channel prefix from device ID confirm response
        // Format: 00 01 [8-byte ID] 04 00 00 [prefix] 00 01
        if (data.size >= 16 && data[0] == 0x00.toByte() && data[1] == 0x01.toByte()) {
            // Check command byte at position 10
            val cmdByte = data[10].toInt() and 0xFF
            
            if (cmdByte == 0x04 && data.size >= 14) {
                // Device ID confirm response - extract channel prefix from byte 13
                val newPrefix = data[13]
                if (newPrefix != 0x00.toByte()) {
                    channelPrefix = newPrefix
                    Log.i(TAG, ">>> CHANNEL PREFIX: 0x${"%02x".format(newPrefix)} (${newPrefix.toInt() and 0xFF})")
                }
            } else if (cmdByte == 0x01 && data.size >= 14) {
                // Channel 1 subscription response - extract assigned channel
                val newChannel = data[13]
                if (newChannel != 0x00.toByte()) {
                    assignedChannel = newChannel
                    Log.i(TAG, ">>> CHANNEL ASSIGNED: 0x${"%02x".format(newChannel)} (${newChannel.toInt() and 0xFF})")
                }
            }
        }
        
        val position = GarminProtocol.parseNotification(data)
        
        if (position == null) {
            Log.i(TAG, "Parse result: null (no valid position found)")
            return
        }
        
        Log.i(TAG, ">>> PARSED: isCollar=${position.isCollar}, lat=${position.latitude}, lon=${position.longitude}")
        
        if (position.isCollar) {
            positionCount++
            lastPosition = position
            
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
        } else {
            Log.d(TAG, "Skipping handheld position (not collar)")
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
