package com.example.remotecontrolprojector.sync

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class SyncBluetoothManager(context: Context, scope: CoroutineScope) :
    BaseCommunicationManager(scope) {

    override val TAG = "Projector:BleClient"
    private val TARGET_MTU = 185

    private val SYNC_SERVICE_UUID = UUID.fromString("a9422624-7662-471d-bba5-706b53e78ac6")
    private val C2S_COMMAND_CHARACTERISTIC_UUID =
        UUID.fromString("a9422625-7662-471d-bba5-706b53e78ac6")
    private val S2C_COMMAND_CHARACTERISTIC_UUID =
        UUID.fromString("a9422626-7662-471d-bba5-706b53e78ac6")
    private val CCCD_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val appContext: Context = context.applicationContext
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    // State
    private val connectedGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val activeCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val connectionInProgress = Collections.synchronizedSet(HashSet<String>())
    private val connectionRetries = ConcurrentHashMap<String, Int>()
    private val MAX_RETRIES = 3

    // UI Flows
    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<String>>(emptyList())
    val connectedDevices: StateFlow<List<String>> = _connectedDevices.asStateFlow()

    private var isReceiverRegistered = false

    private val DEVICE_TIMEOUT_NANOS = 5_000_000_000L
    private var pruneJob: Job? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF) {
                    forceDisconnectAll()
                    _clientState.value = ClientState.OFF
                } else if (state == BluetoothAdapter.STATE_ON) {
                    _clientState.value = ClientState.DISCONNECTED
                }
            }
        }
    }

    init {
        bluetoothManager =
            appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        try {
            appContext.registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Reg failed", e)
        }
    }

    // --- Scanning ---

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        Log.d(TAG, "Starting BLE Scan...")

        // Clear the list so we only see fresh devices
        _scanResults.value = emptyList()

        managerScope.launch(Dispatchers.Main) {
            // Stop previous scan first (Safety measure)
            try {
                adapter.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
            }

            // Small delay to let the stack settle
            delay(200)

            // Start the Pruning Loop alongside the physical scan
            startPruning()

            // Start new scan
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SYNC_SERVICE_UUID)).build()
            val settings =
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            try {
                adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Scan start failed: ${e.message}")
            }
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Scan stop failed: ${e.message}")
        }
        // Stop pruning when we stop scanning
        pruneJob?.cancel()
        pruneJob = null
    }

    /**
     * Periodically checks the scan results and removes devices
     * that haven't been seen for > 5 seconds.
     */
    private fun startPruning() {
        pruneJob?.cancel()
        pruneJob = managerScope.launch {
            while (isActive) {
                // Check every 1 second
                delay(1000)

                val now = SystemClock.elapsedRealtimeNanos()

                _scanResults.update { list ->
                    list.filter { result ->
                        // Check if the result is recent (< 5 seconds)
                        val isRecent = (now - result.timestampNanos) < DEVICE_TIMEOUT_NANOS

                        // Check if this specific device is currently connected
                        // We must NOT prune connected devices, or they vanish from the UI/Logic
                        val isConnected = connectedGatts.containsKey(result.device.address)

                        // Keep it if it's recent OR connected
                        isRecent || isConnected
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Simple Add/Update logic. No filtering, no pruning.
            _scanResults.update { list ->
                val index = list.indexOfFirst { it.device.address == result.device.address }
                if (index >= 0) {
                    val m = list.toMutableList()
                    m[index] = result
                    m
                } else {
                    list + result
                }
            }
        }
    }

    // --- Connection Logic ---
    override fun connectToServer(serverIp: String?) {
        val address = serverIp ?: return
        connectionRetries[address] = 0
        connectWithRetry(address)
    }

    private fun connectWithRetry(address: String) {
        if (bluetoothAdapter?.isEnabled != true) return

        if (connectedGatts.containsKey(address)) {
            Log.w(TAG, "Already connected to $address")
            return
        }
        if (connectionInProgress.contains(address)) {
            Log.w(TAG, "Connection in progress for $address")
            return
        }

        connectionInProgress.add(address)

        managerScope.launch(Dispatchers.Main) {
            delay(600) // Delay for stack stability

            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device == null) {
                connectionInProgress.remove(address)
                return@launch
            }

            Log.i(TAG, "Connecting to $address...")

            try {
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(
                        appContext,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    device.connectGatt(appContext, false, gattCallback)
                }
                if (gatt == null) handleConnectionFailure(null, address, 1001)
            } catch (e: Exception) {
                handleConnectionFailure(null, address, 1002)
            }
        }
    }

    private fun handleConnectionFailure(gatt: BluetoothGatt?, address: String, status: Int) {
        Log.e(TAG, "Connection failed on $address (Status $status)")
        closeAndCleanUp(gatt)
        connectionInProgress.remove(address)

        val currentRetries = connectionRetries[address] ?: 0
        if (currentRetries < MAX_RETRIES) {
            connectionRetries[address] = currentRetries + 1
            Log.w(TAG, "Retrying $address in 1000ms...")
            managerScope.launch {
                delay(1000)
                connectWithRetry(address)
            }
        } else {
            Log.e(TAG, "Max retries reached for $address")
            connectionRetries.remove(address)
        }
    }

    fun disconnectDevice(address: String) {
        val gatt = connectedGatts[address]
        if (gatt != null) {
            Log.i(TAG, "Disconnecting $address...")
            gatt.disconnect()
            // Safety timeout in case callback never fires
            managerScope.launch {
                delay(2500)
                if (connectedGatts.containsKey(address)) {
                    closeAndCleanUp(gatt)
                }
            }
        } else {
            closeAndCleanUp(null, address)
        }
    }

    override fun disconnectFromServer() {
        connectedGatts.keys().toList().forEach { disconnectDevice(it) }
    }

    private fun forceDisconnectAll() {
        connectedGatts.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {
            }
        }
        connectedGatts.clear()
        activeCharacteristics.clear()
        connectionInProgress.clear()
        updateConnectedList()
    }

    private fun closeAndCleanUp(gatt: BluetoothGatt?, explicitAddress: String? = null) {
        val address = gatt?.device?.address ?: explicitAddress ?: return
        try {
            gatt?.close()
        } catch (e: Exception) {
        }
        connectedGatts.remove(address)
        activeCharacteristics.remove(address)
        connectionInProgress.remove(address)
        updateConnectedList()
    }

    private fun updateConnectedList() {
        _connectedDevices.value = connectedGatts.keys().toList()
        _clientState.value =
            if (_connectedDevices.value.isNotEmpty()) ClientState.CONNECTED else ClientState.DISCONNECTED
    }

    // --- GATT Callback ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            connectionInProgress.remove(address)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to $address")
                    connectionRetries.remove(address)
                    connectedGatts[address] = gatt
                    updateConnectedList()

                    managerScope.launch(Dispatchers.Main) {
                        delay(100)
                        if (!gatt.discoverServices()) disconnectDevice(address)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from $address")
                    closeAndCleanUp(gatt)
                }
            } else {
                handleConnectionFailure(gatt, address, status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                managerScope.launch(Dispatchers.Main) {
                    delay(50)
                    gatt.requestMtu(TARGET_MTU)
                }
            } else {
                disconnectDevice(gatt.device.address)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu for ${gatt.device.address} (Status $status)")
            val service = gatt.getService(SYNC_SERVICE_UUID)
            if (service == null) {
                disconnectDevice(gatt.device.address)
                return
            }
            val w = service.getCharacteristic(C2S_COMMAND_CHARACTERISTIC_UUID)
            val n = service.getCharacteristic(S2C_COMMAND_CHARACTERISTIC_UUID)
            if (w != null && n != null) {
                activeCharacteristics[gatt.device.address] = w
                subscribeToNotifications(gatt, n)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            //managerScope.launch { handleClientMessage(characteristic.value.toString(Charsets.UTF_8)) }
            val address = gatt.device.address
            val text = characteristic.value.toString(Charsets.UTF_8)

            managerScope.launch { handleClientMessage(text, senderId = address) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            //managerScope.launch { handleClientMessage(value.toString(Charsets.UTF_8)) }
            val address = gatt.device.address
            val text = value.toString(Charsets.UTF_8)

            managerScope.launch { handleClientMessage(text, senderId = address) }
        }
    }

    private fun subscribeToNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(char, true)) return
        val desc = char.getDescriptor(CCCD_DESCRIPTOR_UUID) ?: return
        val payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, payload)
        } else {
            @Suppress("DEPRECATION")
            desc.value = payload
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
    }

    // --- Lifecycle ---
    override fun setup(role: Role, serverIp: String?) {
        super.currentRole = role
        if (serverIp != null) connectToServer(serverIp)
    }

    override fun release() {
        stopScan()
        forceDisconnectAll()
        if (isReceiverRegistered) {
            try {
                appContext.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
            }
            isReceiverRegistered = false
        }
        super.release()
    }

    override fun startServer() {}
    override fun stopServer() {}
    override suspend fun sendDirectResponse(responseString: String, sender: Any) {}
    override fun broadcastCommand(jsonString: String, excludeSender: Any?) {}
    override fun broadcastSyncedCommand(jsonString: String, excludeSender: Any?) {}
    override fun sendMessage(message: SyncMessage) {
        if (activeCharacteristics.isEmpty()) return
        managerScope.launch {
            clientMutex.withLock {
                val payload = json.encodeToString(message).toByteArray(Charsets.UTF_8)
                activeCharacteristics.forEach { (addr, char) ->
                    val gatt = connectedGatts[addr]
                    if (gatt != null) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(
                                    char,
                                    payload,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                char.value = payload
                                @Suppress("DEPRECATION")
                                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(char)
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }
    }
}