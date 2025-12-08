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
    
    // Device ID for init sequence (generated once per app install)
    private var deviceId: ByteArray? = null
    
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
     * Uses Android ID as seed for consistency across sessions.
     */
    private fun generateDeviceId(): ByteArray {
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
        
        stopBleScan()
        disconnectGatt()
        atakBroadcaster.shutdown()
        bleHandler.removeCallbacksAndMessages(null)
        
        updateStatus(Status.IDLE, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * Start BLE scan for Garmin Alpha devices
     * Uses device name filtering since Alpha may not advertise service UUID
     */
    private fun startBleScan() {
        if (isScanning) return
        
        if (!hasBluetoothPermissions()) {
            updateStatus(Status.ERROR, "Missing Bluetooth permissions")
            return
        }
        
        try {
            // Scan without filters - we'll filter by device name in the callback
            // The Alpha doesn't reliably advertise its service UUID
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
            
            // Find the Garmin service
            val service = gatt.getService(UUID.fromString(GarminProtocol.SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Garmin service not found")
                updateStatus(Status.ERROR, "Garmin service not found")
                return
            }
            
            // Get the write characteristic for init sequence
            writeCharacteristic = service.getCharacteristic(
                UUID.fromString(GarminProtocol.WRITE_CHAR_UUID)
            )
            if (writeCharacteristic == null) {
                Log.w(TAG, "Write characteristic not found, init sequence may fail")
            } else {
                Log.i(TAG, "Write characteristic found: ${GarminProtocol.WRITE_CHAR_UUID}")
            }
            
            // Build list of all characteristics to subscribe to
            cccdWriteQueue.clear()
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            
            for (charUuid in GarminProtocol.NOTIFY_CHAR_UUIDS) {
                val characteristic = service.getCharacteristic(UUID.fromString(charUuid))
                if (characteristic != null) {
                    val descriptor = characteristic.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        cccdWriteQueue.add(descriptor)
                        Log.i(TAG, "Queued subscription for: $charUuid")
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD write success for: ${descriptor.characteristic.uuid}")
            } else {
                Log.e(TAG, "CCCD write failed: $status for ${descriptor.characteristic.uuid}")
            }
            
            // Write next CCCD or start init sequence
            bleHandler.postDelayed({
                if (cccdWriteQueue.isNotEmpty()) {
                    writeNextCccd(gatt)
                } else if (!initSequenceStarted) {
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
        
        // Build init commands
        val commands = mutableListOf<ByteArray>()
        
        // Step 1: Device ID exchange
        commands.add(byteArrayOf(0x00, 0x05) + id + byteArrayOf(0x00, 0x00))
        commands.add(byteArrayOf(0x00, 0x00) + id + byteArrayOf(0x04, 0x00, 0x00))
        
        // Step 2: Enable 5 channels
        for (channel in 0..4) {
            commands.add(byteArrayOf(0x15, channel.toByte()))
        }
        
        // Step 3: Start streaming
        commands.add(byteArrayOf(0x16, 0x01, 0x19, 0x00, 0x00, 0x00))
        
        // Send commands with delays
        sendInitCommands(gatt, writeChar, commands, 0)
    }
    
    /**
     * Send init commands sequentially with delays
     */
    private fun sendInitCommands(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        commands: List<ByteArray>,
        index: Int
    ) {
        if (index >= commands.size) {
            Log.i(TAG, "Init sequence complete!")
            updateStatus(Status.TRACKING, "Tracking active (standalone)")
            return
        }
        
        val command = commands[index]
        Log.d(TAG, "Init step ${index + 1}/${commands.size}: ${command.toHexString()}")
        
        try {
            // Set write type to no response for speed
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            
            // Schedule next command
            bleHandler.postDelayed({
                sendInitCommands(gatt, characteristic, commands, index + 1)
            }, INIT_STEP_DELAY_MS)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in init sequence", e)
            updateStatus(Status.ERROR, "Init sequence failed: permission denied")
        }
    }
    
    /**
     * Handle incoming BLE notification with position data
     */
    private fun handleNotification(charUuid: String, data: ByteArray) {
        // Log raw data for debugging
        if (data.size > 10) {
            Log.v(TAG, "Notification from $charUuid: ${data.take(20).toByteArray().toHexString()}...")
        }
        
        val position = GarminProtocol.parseNotification(data)
        
        if (position != null && position.isCollar) {
            positionCount++
            lastPosition = position
            
            Log.d(TAG, "Dog position #$positionCount: ${position.latitude}, ${position.longitude}")
            
            // Generate and send CoT
            val cot = CotGenerator.generateCot(position, dogConfig)
            
            serviceScope.launch {
                val sent = atakBroadcaster.sendCot(cot)
                if (sent) {
                    updateStatus(
                        Status.TRACKING, 
                        "Tracking: $positionCount positions",
                        position.latitude,
                        position.longitude
                    )
                }
            }
            
            // Update notification
            updateNotification("Tracking: ${position.latitude.format(5)}, ${position.longitude.format(5)}")
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
    
    private fun ByteArray.toHexString() = joinToString("-") { "%02X".format(it) }
}
