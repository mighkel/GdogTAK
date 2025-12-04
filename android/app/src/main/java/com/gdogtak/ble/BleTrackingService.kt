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
import android.os.IBinder
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
 * 2. Subscribes to position notifications on multiple characteristics
 * 3. Parses dog collar positions
 * 4. Broadcasts to ATAK via UDP multicast
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
    }

    // Service state
    enum class Status {
        IDLE, SCANNING, CONNECTING, CONNECTED, TRACKING, ERROR
    }

    private var currentStatus = Status.IDLE
    private var statusMessage = "Ready"

    // Bluetooth components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // Track which characteristics we've enabled notifications on
    private val pendingNotifyChars = mutableListOf<BluetoothGattCharacteristic>()
    private var notifyEnableIndex = 0

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

        // Create notification channel
        createNotificationChannel()
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
        Log.i(TAG, "Service destroyed")
    }

    /**
     * Start the tracking process
     */
    private fun startTracking() {
        Log.i(TAG, "Starting tracking")

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

        updateStatus(Status.IDLE, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Start BLE scan for Garmin Alpha devices
     */
    private fun startBleScan() {
        if (isScanning) return

        if (!hasBluetoothPermissions()) {
            updateStatus(Status.ERROR, "Missing Bluetooth permissions")
            return
        }

        try {
            val scanFilter = ScanFilter.Builder()
                // Filter by Garmin service UUID
                .setServiceUuid(android.os.ParcelUuid.fromString(GarminProtocol.SERVICE_UUID))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true
            updateStatus(Status.SCANNING, "Scanning for Alpha...")

            Log.i(TAG, "BLE scan started")
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
     * BLE scan callback
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            val name = device.name ?: "Unknown"
            Log.i(TAG, "Scan found: $address ($name)")

            // Connect to Garmin Alpha devices by name
            if (name.contains("Alpha", ignoreCase = true)) {
                Log.i(TAG, "Found Garmin Alpha! Connecting to $address...")
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
                    updateStatus(Status.SCANNING, "Disconnected, reconnecting...")
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

            // Collect all notify characteristics
            pendingNotifyChars.clear()
            notifyEnableIndex = 0

            for (charUuid in GarminProtocol.NOTIFY_CHAR_UUIDS) {
                val characteristic = service.getCharacteristic(UUID.fromString(charUuid))
                if (characteristic != null) {
                    pendingNotifyChars.add(characteristic)
                    Log.i(TAG, "Found characteristic: $charUuid")
                } else {
                    Log.w(TAG, "Characteristic not found: $charUuid")
                }
            }

            if (pendingNotifyChars.isEmpty()) {
                Log.e(TAG, "No notification characteristics found")
                updateStatus(Status.ERROR, "No position characteristics found")
                return
            }

            Log.i(TAG, "Enabling notifications on ${pendingNotifyChars.size} characteristics...")
            // Start enabling notifications on the first characteristic
            enableNextNotification(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val charUuid = characteristic.uuid.toString().takeLast(4)
            Log.d(TAG, "onCharacteristicChanged (char $charUuid): ${value.size} bytes")
            // Parse the notification
            handleNotification(value, charUuid)
        }

        // Deprecated but needed for older Android versions
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val charUuid = characteristic.uuid.toString().takeLast(4)
            Log.d(TAG, "onCharacteristicChanged (char $charUuid, old API): ${characteristic.value?.size} bytes")
            characteristic.value?.let { handleNotification(it, charUuid) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic.uuid.toString().takeLast(4)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD write SUCCESS for char $charUuid")
                // Enable notifications on the next characteristic
                notifyEnableIndex++
                if (notifyEnableIndex < pendingNotifyChars.size) {
                    enableNextNotification(gatt)
                } else {
                    Log.i(TAG, "All ${pendingNotifyChars.size} characteristics enabled!")
                    updateStatus(Status.TRACKING, "Tracking active (${pendingNotifyChars.size} channels)")
                }
            } else {
                Log.e(TAG, "CCCD write FAILED for char $charUuid with status: $status")
                // Try the next one anyway
                notifyEnableIndex++
                if (notifyEnableIndex < pendingNotifyChars.size) {
                    enableNextNotification(gatt)
                } else {
                    updateStatus(Status.TRACKING, "Tracking (some channels failed)")
                }
            }
        }
    }

    /**
     * Enable notifications on the next characteristic in the queue
     */
    private fun enableNextNotification(gatt: BluetoothGatt) {
        if (notifyEnableIndex >= pendingNotifyChars.size) return

        val characteristic = pendingNotifyChars[notifyEnableIndex]
        val charUuid = characteristic.uuid.toString().takeLast(4)
        Log.i(TAG, "Enabling notifications on char $charUuid (${notifyEnableIndex + 1}/${pendingNotifyChars.size})")

        enableNotifications(gatt, characteristic)
    }

    /**
     * Enable notifications on a characteristic
     */
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            // Enable local notifications
            gatt.setCharacteristicNotification(characteristic, true)

            // Enable remote notifications via CCCD
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = characteristic.getDescriptor(cccdUuid)

            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Log.i(TAG, "CCCD write initiated for ${characteristic.uuid.toString().takeLast(4)}")
            } else {
                Log.e(TAG, "CCCD descriptor not found for ${characteristic.uuid.toString().takeLast(4)}")
                // Skip to next
                notifyEnableIndex++
                if (notifyEnableIndex < pendingNotifyChars.size) {
                    enableNextNotification(gatt)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception enabling notifications", e)
        }
    }

    /**
     * Handle incoming BLE notification with position data
     */
    private fun handleNotification(data: ByteArray, charUuid: String) {
        Log.d(TAG, "Received notification on $charUuid: ${data.size} bytes")

        // Log first 20 bytes for debugging
        val hexPreview = data.take(20).joinToString("-") { "%02X".format(it) }
        Log.d(TAG, "Data preview: $hexPreview...")

        val position = GarminProtocol.parseNotification(data)

        if (position != null && position.isCollar) {
            positionCount++
            lastPosition = position

            Log.i(TAG, "Dog position #$positionCount from char $charUuid: ${position.latitude}, ${position.longitude}")

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
}
