package com.myworks.pilltaker20

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BluetoothTerminal"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // BLE UUIDs
        private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val CHARACTERISTIC_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private const val REQUEST_PERMISSIONS = 2
        private const val MAX_RETRIES = 3
        private const val SCAN_PERIOD = 10000L // 10 seconds
        private const val NOTIFICATION_CHANNEL_ID = "PILL_REMINDER"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedThread: ConnectedThread? = null

    private lateinit var devicesListView: ListView
    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var terminalScrollView: View
    private lateinit var inputContainer: View
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var connectionStatus: TextView
    private lateinit var reminderSection: View
    private lateinit var morningTimeBtn: Button
    private lateinit var afternoonTimeBtn: Button
    private lateinit var nightTimeBtn: Button
    private lateinit var morningTimeText: TextView
    private lateinit var afternoonTimeText: TextView
    private lateinit var nightTimeText: TextView
    private lateinit var sendTimesButton: Button
    private lateinit var bluetoothSection: View

    private val devicesList = ArrayList<BluetoothDevice>()
    private lateinit var devicesAdapter: ArrayAdapter<String>

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showToast("Bluetooth enabled")
            listPairedDevices()
        } else {
            showToast("Bluetooth is required for this app")
        }
    }

    private var currentDevice: BluetoothDevice? = null
    private var connectionAttempts = 0

    // New BLE properties
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bleGatt: BluetoothGatt? = null
    private var bleCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    private var morningTime: Calendar? = null
    private var afternoonTime: Calendar? = null
    private var nightTime: Calendar? = null

    private val bleGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateConnectionState(true)
                    runOnUiThread {
                        showToast("BLE Connected successfully!")
                        showLoading(false)
                    }
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateConnectionState(false)
                    runOnUiThread {
                        showToast("BLE Disconnected")
                        showLoading(false)
                    }
                    bleGatt?.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                
                if (characteristic != null) {
                    bleCharacteristic = characteristic
                    runOnUiThread {
                        showToast("BLE Service found, enabling notifications...")
                    }
                    
                    // Enable notifications for the characteristic
                    gatt?.setCharacteristicNotification(characteristic, true)
                    
                    // Enable notifications on the device side
                    val descriptor = characteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt?.writeDescriptor(descriptor)
                    }
                    
                    runOnUiThread {
                        // Update UI for connected state
                        devicesListView.visibility = View.GONE
                        scanButton.visibility = View.GONE
                        terminalScrollView.visibility = View.VISIBLE
                        inputContainer.visibility = View.VISIBLE
                        sendButton.visibility = View.VISIBLE
                        disconnectButton.visibility = View.VISIBLE
                    }
                } else {
                    runOnUiThread {
                        showToast("BLE Service not found")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.value?.let { value ->
                try {
                    val message = String(value)
                    runOnUiThread {
                        appendToTerminal("Received: $message")
                        
                        // Handle different types of messages
                        when {
                            message.contains("Morning time set successfully") -> {
                                showToast("Morning time set successfully")
                            }
                            message.contains("Afternoon time set successfully") -> {
                                showToast("Afternoon time set successfully")
                            }
                            message.contains("Night time set successfully") -> {
                                showToast("Night time set successfully")
                            }
                            message.startsWith("Reminder:") -> {
                                showNotification("Medicine Reminder", message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    val hexString = value.joinToString(separator = " ") { 
                        String.format("%02X", it) 
                    }
                    runOnUiThread {
                        appendToTerminal("Received (HEX): $hexString")
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    showToast("Ready to receive data!")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    showToast("Data sent successfully")
                }
            } else {
                runOnUiThread {
                    showToast("Failed to send data")
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic?.value?.let { value ->
                    try {
                        val message = String(value)
                        runOnUiThread {
                            appendToTerminal("Read: $message")
                        }
                    } catch (e: Exception) {
                        val hexString = value.joinToString(separator = " ") { 
                            String.format("%02X", it) 
                        }
                        runOnUiThread {
                            appendToTerminal("Read (HEX): $hexString")
                        }
                    }
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name?.contains("HC-05", ignoreCase = true) == true) {
                stopBleScan()
                connectBleDevice(result.device)
            }
        }
    }

    private fun startBleScan() {
        if (!scanning) {
            showToast("Starting BLE scan...")
            handler.postDelayed({
                scanning = false
                bluetoothLeScanner?.stopScan(bleScanCallback)
                showToast("BLE scan timeout")
            }, SCAN_PERIOD)
            
            scanning = true
            bluetoothLeScanner?.startScan(bleScanCallback)
        }
    }

    private fun stopBleScan() {
        scanning = false
        bluetoothLeScanner?.stopScan(bleScanCallback)
    }

    private fun connectBleDevice(device: BluetoothDevice) {
        showToast("Attempting BLE connection...")
        showLoading(true, "Connecting via BLE...")
        
        // Use autoConnect=true for better connection stability
        bleGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, true, bleGattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, true, bleGattCallback)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            // Initialize UI components
            initializeViews()
            setupTimeButtons()

            // Request notification permission on startup (Android 13+)
            requestNotificationPermissionIfNeeded()

            // Initialize Bluetooth adapter
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
                showToast("Bluetooth is not supported on this device")
                finish()
                return
            }

            // Set up devices adapter
            devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
            devicesListView.adapter = devicesAdapter

            // Set up device selection listener
            devicesListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position < devicesList.size) {
                    connectToDevice(devicesList[position])
                }
            }

            // Set up button listeners
            scanButton.setOnClickListener {
                checkPermissionsAndEnableBluetooth()
            }

            sendButton.setOnClickListener {
                val message = inputField.text.toString().trim()
                if (message.isNotEmpty()) {
                    sendMessage(message)
                    inputField.text.clear()
                }
            }

            disconnectButton.setOnClickListener {
                disconnect()
                // Reset UI to initial state
                runOnUiThread {
                    terminalScrollView.visibility = View.GONE
                    inputContainer.visibility = View.GONE
                    disconnectButton.visibility = View.GONE
                    devicesListView.visibility = View.VISIBLE
                    scanButton.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            checkPermissionsAndEnableBluetooth()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleGatt?.close()
        disconnect()
    }

    private fun checkPermissionsAndEnableBluetooth() {
        try {
            // Check for required permissions on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val permissionsToRequest = ArrayList<String>()

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                    PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                }

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                }

                if (permissionsToRequest.isNotEmpty()) {
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toTypedArray(),
                        REQUEST_PERMISSIONS
                    )
                    return
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // For Android 6.0 - 11, check location permissions (required for Bluetooth scanning)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_PERMISSIONS
                    )
                    return
                }
            }

            // Enable Bluetooth if needed
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBtLauncher.launch(enableBtIntent)
            } else {
                listPairedDevices()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            showToast("Error checking permissions: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            when (requestCode) {
                REQUEST_PERMISSIONS -> {
                    if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                        // Enable Bluetooth if needed
                        if (!bluetoothAdapter.isEnabled) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBtLauncher.launch(enableBtIntent)
                        } else {
                            listPairedDevices()
                        }
                    } else {
                        showToast("Required permissions are not granted")
                    }
                }
                NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        createNotificationChannel()
                        showToast("Notifications enabled")
                    } else {
                        showToast("Notification permission denied. Reminders will not be shown.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permissions result", e)
        }
    }

    private fun listPairedDevices() {
        try {
            devicesList.clear()
            devicesAdapter.clear()

            // Check for the necessary permissions first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED) {
                    showToast("Bluetooth permissions not granted")
                    return
                }
            }

            val pairedDevices = bluetoothAdapter.bondedDevices
            if (pairedDevices.isNotEmpty()) {
                for (device in pairedDevices) {
                    devicesList.add(device)
                    devicesAdapter.add("${device.name ?: "Unknown"} - ${device.address}")
                }
                showToast("Found ${pairedDevices.size} paired devices")
            } else {
                showToast("No paired Bluetooth devices found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing paired devices", e)
            showToast("Error listing devices: ${e.message}")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            connectionAttempts = 0
            currentDevice = device
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    showToast("Bluetooth permissions not granted")
                    return
                }
            }

            disconnect()
            showLoading(true, "Starting connection process...")

            // For HC-05, try both Classic and BLE
            if (device.name?.contains("HC-05", ignoreCase = true) == true) {
                showToast("HC-05 detected, trying BLE first...")
                // Try BLE first
                connectBleDevice(device)
                
                // If BLE fails after 5 seconds, try Classic Bluetooth
                handler.postDelayed({
                    if (bleGatt?.services?.isEmpty() != false) {
                        showToast("BLE connection failed, trying Classic Bluetooth...")
                        ConnectThread(device).start()
                    }
                }, 5000)
            } else {
                // For other devices, use Classic Bluetooth
                ConnectThread(device).start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            showToast("Error connecting: ${e.message}")
            showLoading(false)
        }
    }

    private fun sendMessage(message: String) {
        try {
            val characteristic = bleCharacteristic
            val gatt = bleGatt
            
            if (characteristic != null && gatt != null) {
                // Send via BLE
                val messageBytes = message.toByteArray()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, 
                        messageBytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = messageBytes
                    gatt.writeCharacteristic(characteristic)
                }
                appendToTerminal("Sending: $message")
            } else {
                // Send via Classic Bluetooth
                connectedThread?.write(message.toByteArray())
                appendToTerminal("Sent: $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            appendToTerminal("Error sending: ${e.message}")
            showToast("Error sending message: ${e.message}")
        }
    }

    private fun disconnect() {
        try {
            bluetoothSocket?.close()
            bleGatt?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing connection", e)
        }

        connectedThread?.cancel()
        connectedThread = null
        bluetoothSocket = null
        bleGatt = null
        bleCharacteristic = null
    }

    private fun appendToTerminal(message: String) {
        runOnUiThread {
            try {
                terminalOutput.append("$message\n")
                // Auto-scroll to the bottom
                val layout = terminalOutput.layout
                if (layout != null) {
                    val scrollAmount = layout.getLineTop(terminalOutput.lineCount) - terminalOutput.height
                    if (scrollAmount > 0) {
                        terminalOutput.scrollTo(0, scrollAmount)
                    } else {
                        terminalOutput.scrollTo(0, 0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error appending to terminal", e)
            }
        }
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast", e)
        }
    }

    private fun showLoading(show: Boolean, message: String? = null) {
        runOnUiThread {
            loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
            if (message != null) {
                showToast(message)
            }
        }
    }

    private fun initializeViews() {
        devicesListView = findViewById(R.id.devices_list)
        terminalOutput = findViewById(R.id.terminal_output)
        inputField = findViewById(R.id.input_field)
        sendButton = findViewById(R.id.send_button)
        scanButton = findViewById(R.id.scan_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        terminalScrollView = findViewById(R.id.terminal_scroll)
        inputContainer = findViewById(R.id.input_container)
        loadingIndicator = findViewById(R.id.loading_indicator)
        connectionStatus = findViewById(R.id.connection_status)
        reminderSection = findViewById(R.id.reminder_section)
        morningTimeBtn = findViewById(R.id.morning_time_btn)
        afternoonTimeBtn = findViewById(R.id.afternoon_time_btn)
        nightTimeBtn = findViewById(R.id.night_time_btn)
        morningTimeText = findViewById(R.id.morning_time_text)
        afternoonTimeText = findViewById(R.id.afternoon_time_text)
        nightTimeText = findViewById(R.id.night_time_text)
        sendTimesButton = findViewById(R.id.send_times_button)
        bluetoothSection = findViewById(R.id.bluetooth_section)
    }

    private fun setupTimeButtons() {
        morningTimeBtn.setOnClickListener { showTimePickerDialog("Morning", morningTimeText) { morningTime = it } }
        afternoonTimeBtn.setOnClickListener { showTimePickerDialog("Afternoon", afternoonTimeText) { afternoonTime = it } }
        nightTimeBtn.setOnClickListener { showTimePickerDialog("Night", nightTimeText) { nightTime = it } }
        
        sendTimesButton.setOnClickListener {
            if (validateTimes()) {
                sendTimesToDevice()
            }
        }
    }

    private fun showTimePickerDialog(period: String, textView: TextView, onTimeSet: (Calendar) -> Unit) {
        val calendar = Calendar.getInstance()
        val timePickerDialog = TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                onTimeSet(calendar)
                textView.text = String.format("%02d:%02d", hourOfDay, minute)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        timePickerDialog.show()
    }

    private fun validateTimes(): Boolean {
        if (morningTime == null || afternoonTime == null || nightTime == null) {
            showToast("Please set all reminder times")
            return false
        }
        return true
    }

    private fun sendTimesToDevice() {
        try {
            val morningCmd = "M:${formatTime(morningTime!!)}\n"
            val afternoonCmd = "A:${formatTime(afternoonTime!!)}\n"
            val nightCmd = "N:${formatTime(nightTime!!)}\n"

            // Send commands sequentially
            sendMessage(morningCmd)
            Thread.sleep(500) // Add delay between commands
            sendMessage(afternoonCmd)
            Thread.sleep(500)
            sendMessage(nightCmd)

            showToast("Sending times to device...")
        } catch (e: Exception) {
            showToast("Error sending times: ${e.message}")
        }
    }

    private fun formatTime(calendar: Calendar): String {
        return String.format("%02d:%02d", 
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                // Explain why the permission is needed (optional but recommended)
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission Needed")
                        .setMessage("This permission is needed to show pill reminders.")
                        .setPositiveButton("OK") { _, _ ->
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST_CODE
                            )
                        }
                        .setNegativeButton("Cancel") { dialog, _ ->
                            dialog.dismiss()
                            showToast("Notification permission denied. Reminders will not be shown.")
                        }
                        .create()
                        .show()
                } else {
                    // Request the permission directly
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                }
            } else {
                // Permission already granted, create channel
                createNotificationChannel()
            }
        }
         else {
             // No runtime permission needed for older versions, create channel
            createNotificationChannel()
         }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Pill Reminder"
            val descriptionText = "Notifications for pill reminders"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                setShowBadge(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        // Check if we have notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                // Permission not granted, inform user and guide them to settings
                showToast("Notification permission denied. Please enable it in settings.")
                // Optionally, open app settings
                // val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                // val uri = Uri.fromParts("package", packageName, null)
                // intent.data = uri
                // startActivity(intent)
                return
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(null, false)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setSound(null)
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateConnectionState(connected: Boolean) {
        runOnUiThread {
            connectionStatus.text = if (connected) "Connected" else "Not Connected"
            // Set text color based on theme attribute and state
            val textColorAttr = if (connected) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorError
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(textColorAttr, typedValue, true)
            connectionStatus.setTextColor(typedValue.data)

            reminderSection.visibility = if (connected) View.VISIBLE else View.GONE
            bluetoothSection.visibility = if (connected) View.GONE else View.VISIBLE
            // Show/hide scan button based on connection status
            scanButton.visibility = if (connected) View.GONE else View.VISIBLE
            // Show/hide disconnect button based on connection status
            disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
        }
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private var mmSocket: BluetoothSocket? = null
        private val targetDevice = device

        init {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED) {
                    appendToTerminal("Bluetooth permissions not granted")
                    showLoading(false)
                } else {
                    showLoading(true, "Establishing connection...")
                    
                    // For HC-05, try reflection method first
                    if (targetDevice.name?.contains("HC-05", ignoreCase = true) == true) {
                        try {
                            val m = targetDevice.javaClass.getMethod("createRfcommSocket", Int::class.java)
                            mmSocket = m.invoke(targetDevice, 1) as BluetoothSocket
                            showToast("Using HC-05 specific connection method")
                        } catch (e: Exception) {
                            Log.e(TAG, "HC-05 specific method failed, trying standard methods", e)
                            // Fall through to standard methods
                        }
                    }

                    // If HC-05 specific method failed or for other devices
                    if (mmSocket == null) {
                        try {
                            mmSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                            showToast("Using standard connection method")
                        } catch (e: IOException) {
                            Log.e(TAG, "Standard connection failed, trying fallback", e)
                            try {
                                mmSocket = targetDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                                showToast("Using insecure connection method")
                            } catch (e2: IOException) {
                                Log.e(TAG, "All connection methods failed", e2)
                                appendToTerminal("Failed to create socket: ${e2.message}")
                                showToast("All connection methods failed. Please try again.")
                                showLoading(false)
                            }
                        }
                    }
                    
                    if (mmSocket != null) {
                        appendToTerminal("Connecting to ${targetDevice.name ?: "Unknown Device"}...")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket's create() method failed", e)
                appendToTerminal("Failed to create socket: ${e.message}")
                showToast("Failed to create connection: ${e.message}")
                showLoading(false)
            }
        }

        override fun run() {
            try {
                // Cancel discovery because it otherwise slows down the connection
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) !=
                        PackageManager.PERMISSION_GRANTED) {
                        showLoading(false)
                        return
                    }
                }

                bluetoothAdapter.cancelDiscovery()

                try {
                    // Connect to the remote device through the socket
                    showLoading(true, "Connecting to device...")
                    
                    // Add a small delay before connecting (helps with HC-05)
                    sleep(1000)
                    
                    mmSocket?.connect()
                    showToast("Connected successfully!")
                    showLoading(false)
                    connectionAttempts = 0 // Reset attempts on successful connection

                    // Update UI
                    runOnUiThread {
                        devicesListView.visibility = View.GONE
                        scanButton.visibility = View.GONE
                        terminalScrollView.visibility = View.VISIBLE
                        inputContainer.visibility = View.VISIBLE
                        sendButton.visibility = View.VISIBLE
                        disconnectButton.visibility = View.VISIBLE
                    }

                    // The connection attempt succeeded, manage the connection in a separate thread
                    bluetoothSocket = mmSocket
                    connectedThread = ConnectedThread(mmSocket!!)
                    connectedThread?.start()
                } catch (e: IOException) {
                    // Unable to connect
                    try {
                        mmSocket?.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Could not close the client socket", closeException)
                    }
                    appendToTerminal("Failed to connect: ${e.message}")
                    showToast("Connection failed: ${e.message}")
                    showLoading(false)
                    
                    // Try to reconnect if we haven't exceeded max retries
                    if (connectionAttempts < MAX_RETRIES) {
                        connectionAttempts++
                        // Add increasing delay between retries
                        Thread.sleep(1000L * connectionAttempts)
                        runOnUiThread {
                            showToast("Retry attempt $connectionAttempts of $MAX_RETRIES")
                            currentDevice?.let { connectToDevice(it) }
                        }
                    } else {
                        showToast("Max connection attempts reached. Please try manually connecting again.")
                        connectionAttempts = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connect thread", e)
                appendToTerminal("Connection error: ${e.message}")
                showToast("Connection error: ${e.message}")
                showLoading(false)
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream = mmSocket.inputStream
        private val mmOutStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        override fun run() {
            try {
                var numBytes: Int // bytes returned from read()

                // Keep listening to the InputStream until an exception occurs
                while (true) {
                    try {
                        // Read from the InputStream
                        numBytes = mmInStream.read(mmBuffer)
                        // Send the obtained bytes to the UI activity
                        val readMsg = String(mmBuffer, 0, numBytes)
                        appendToTerminal("Received: $readMsg")
                    } catch (e: IOException) {
                        Log.e(TAG, "Input stream was disconnected", e)
                        appendToTerminal("Connection lost: ${e.message}")

                        // Reset UI on disconnect
                        runOnUiThread {
                            terminalScrollView.visibility = View.GONE
                            inputContainer.visibility = View.GONE
                            disconnectButton.visibility = View.GONE
                            devicesListView.visibility = View.VISIBLE
                            scanButton.visibility = View.VISIBLE
                        }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading from input stream", e)
                        appendToTerminal("Read error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connected thread", e)
                appendToTerminal("Thread error: ${e.message}")
            }
        }

        // Call this from the main activity to send data to the remote device
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)
                appendToTerminal("Failed to send: ${e.message}")
            }
        }

        // Call this method to close the connection
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}