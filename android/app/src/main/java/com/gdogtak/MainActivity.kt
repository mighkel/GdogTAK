package com.gdogtak

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gdogtak.ble.BleTrackingService
import com.gdogtak.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var trackingService: BleTrackingService? = null
    private var serviceBound = false

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
            checkBluetoothAndStart()
        } else {
            Log.w(TAG, "Some permissions denied: $permissions")
            showPermissionDeniedDialog()
        }
    }

    // Bluetooth enable launcher
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startTrackingService()
        } else {
            Toast.makeText(this, "Bluetooth is required for tracking", Toast.LENGTH_LONG).show()
        }
    }

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleTrackingService.LocalBinder
            trackingService = binder.getService()
            serviceBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            serviceBound = false
        }
    }

    // Broadcast receiver for service status updates
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleTrackingService.BROADCAST_STATUS) {
                val status = intent.getStringExtra(BleTrackingService.EXTRA_STATUS) ?: ""
                val message = intent.getStringExtra(BleTrackingService.EXTRA_MESSAGE) ?: ""
                val dogCount = intent.getIntExtra(BleTrackingService.EXTRA_DOG_COUNT, 0)
                val lat = intent.getDoubleExtra(BleTrackingService.EXTRA_LAST_LAT, 0.0)
                val lon = intent.getDoubleExtra(BleTrackingService.EXTRA_LAST_LON, 0.0)

                updateStatusDisplay(status, message, dogCount, lat, lon)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    override fun onStart() {
        super.onStart()

        // Bind to service if it's running
        Intent(this, BleTrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Register for status broadcasts
        val filter = IntentFilter(BleTrackingService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun setupUI() {
        binding.buttonStartStop.setOnClickListener {
            if (isServiceRunning()) {
                stopTrackingService()
            } else {
                requestPermissionsAndStart()
            }
        }

        updateUI()
    }

    private fun updateUI() {
        val isRunning = isServiceRunning()
        binding.buttonStartStop.text = if (isRunning) "Stop Tracking" else "Start Tracking"

        if (isRunning && serviceBound) {
            val service = trackingService
            if (service != null) {
                binding.textStatus.text = service.getStatusMessage()
                binding.textPositionCount.text = "Positions: ${service.getPositionCount()}"
            }
        } else {
            binding.textStatus.text = "Ready"
            binding.textPositionCount.text = "Positions: 0"
        }
    }

    private fun updateStatusDisplay(
        status: String,
        message: String,
        dogCount: Int,
        lat: Double,
        lon: Double
    ) {
        binding.textStatus.text = message
        binding.textPositionCount.text = "Positions: $dogCount"

        if (lat != 0.0 && lon != 0.0) {
            binding.textLastPosition.text = "Last: %.6f, %.6f".format(lat, lon)
        }

        // Update button state
        val isTracking = status == "TRACKING" || status == "SCANNING" ||
                status == "CONNECTING" || status == "CONNECTED"
        binding.buttonStartStop.text = if (isTracking) "Stop Tracking" else "Start Tracking"
    }

    private fun requestPermissionsAndStart() {
        val requiredPermissions = mutableListOf<String>()

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Location (required for BLE on some Android versions)
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Check which permissions need to be requested
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            checkBluetoothAndStart()
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun checkBluetoothAndStart() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableIntent)
        } else {
            startTrackingService()
        }
    }

    private fun startTrackingService() {
        Log.i(TAG, "Starting tracking service")

        val intent = Intent(this, BleTrackingService::class.java).apply {
            action = BleTrackingService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Bind to get updates
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        binding.textStatus.text = "Starting..."
        binding.buttonStartStop.text = "Stop Tracking"
    }

    private fun stopTrackingService() {
        Log.i(TAG, "Stopping tracking service")

        val intent = Intent(this, BleTrackingService::class.java).apply {
            action = BleTrackingService.ACTION_STOP
        }
        startService(intent)

        binding.textStatus.text = "Stopped"
        binding.buttonStartStop.text = "Start Tracking"
    }

    private fun isServiceRunning(): Boolean {
        return serviceBound && trackingService?.getStatus() != BleTrackingService.Status.IDLE
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("GdogTAK needs Bluetooth and Location permissions to scan for and connect to your dog tracking device.\n\nPlease grant these permissions in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}