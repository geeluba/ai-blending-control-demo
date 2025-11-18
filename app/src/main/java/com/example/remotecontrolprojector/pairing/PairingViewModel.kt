package com.example.remotecontrolprojector.pairing

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotecontrolprojector.RemoteService
import com.example.remotecontrolprojector.sync.BaseCommunicationManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PairingViewModel : ViewModel() {

    private val TAG = "Projector:PairingVM"
    private var service: RemoteService? = null

    // Combined job to manage all BLE observations (Scanning + Connection State)
    private var bleObserverJob: Job? = null

    // --- UI State Flows ---
    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _leftDeviceAddress = MutableStateFlow<String?>(null)
    val leftDeviceAddress: StateFlow<String?> = _leftDeviceAddress.asStateFlow()

    private val _rightDeviceAddress = MutableStateFlow<String?>(null)
    val rightDeviceAddress: StateFlow<String?> = _rightDeviceAddress.asStateFlow()

    private val _connectedBleDevices = MutableStateFlow<List<String>>(emptyList())

    // Button is visible only if devices are selected AND actually connected
    val isLeftRightDeviceSelected = combine(
        _leftDeviceAddress,
        _rightDeviceAddress,
        _connectedBleDevices
    ) { leftAddr, rightAddr, connectedList ->
        val isLeftReady = leftAddr != null && connectedList.contains(leftAddr)
        val isRightReady = rightAddr != null && connectedList.contains(rightAddr)

        isLeftReady && isRightReady
    }

    private val _navigationEvent = MutableSharedFlow<Triple<String?, String?, String?>>()
    val navigationEvent = _navigationEvent.asSharedFlow()
    private var leftIp: String? = null
    private var rightIp: String? = null

    // Flag to track if navigation has been started
    private val _isNavigationInitiated = MutableStateFlow(false)

    private val commandListener = object : BaseCommunicationManager.CommandListener {
        @SuppressLint("MissingPermission")
        override fun onCommandReceived(command: String, senderId: String) {
            if (command.startsWith(BLE_WIFI_REQUEST_COMMAND + ":")) {
                val ip = command.removePrefix(BLE_WIFI_REQUEST_COMMAND + ":")

                when (senderId) {
                    _leftDeviceAddress.value -> {
                        leftIp = ip
                        Log.i(TAG, ">>> LEFT Projector ($senderId) IP set to: $ip")
                    }

                    _rightDeviceAddress.value -> {
                        rightIp = ip
                        Log.i(TAG, ">>> RIGHT Projector ($senderId) IP set to: $ip")
                    }
                }

                // Trigger navigation only if we haven't already
                if (leftIp != null && rightIp != null && !_isNavigationInitiated.value) {
                    _isNavigationInitiated.value = true

                    val rightMac = _rightDeviceAddress.value
                    // Try to get name from ScanRecord (Advertised name)
                    // fallback to Device Cache, fallback to Default
                    val rightDeviceName =
                        _scanResults.value.find { it.device.address == rightMac }?.scanRecord?.deviceName
                            ?: _scanResults.value.find { it.device.address == rightMac }?.device?.name
                            ?: "Unknown Projector"

                    Log.d(
                        TAG,
                        "--- Navigation Event Fired. Target Right Name: $rightDeviceName ---"
                    )
                    viewModelScope.launch {
                        _navigationEvent.emit(Triple(leftIp, rightIp, rightDeviceName))
                    }
                }
            }
        }
    }

    fun setService(remoteService: RemoteService?) {
        this.service = remoteService

        // Cancel old observation to prevent duplicates
        bleObserverJob?.cancel()

        if (remoteService == null) return

        // Resurrection logic
        if (remoteService.binder.getBleManager() == null) {
            remoteService.binder.initBle()
        }

        val manager = remoteService.binder.getBleManager() ?: return
        setupBleObservation(manager)
    }

    fun startScan() {
        // RESET FLAG HERE: Start of a new session
        _isNavigationInitiated.value = false
        service?.binder?.startScan()
    }

    fun stopScan() {
        service?.binder?.stopScan()
    }

    fun disconnectAllDevices() {
        Log.i(TAG, "Disconnecting all devices.")
        // SyncBluetoothManager.disconnectFromServer() disconnects all clients
        if (_leftDeviceAddress.value != null) {
            service?.binder?.getBleManager()?.disconnectDevice(_leftDeviceAddress.value!!)
        }
        if (_rightDeviceAddress.value != null) {
            service?.binder?.getBleManager()?.disconnectDevice(_rightDeviceAddress.value!!)
        }
        _leftDeviceAddress.value = null
        _rightDeviceAddress.value = null

        leftIp = null
        rightIp = null
    }

    fun assignLeftProjector(address: String) {
        if (_rightDeviceAddress.value == address) {
            _rightDeviceAddress.value = null
            rightIp = null
        }

        if (_leftDeviceAddress.value != address) {
            leftIp = null
        }

        _leftDeviceAddress.value = address
        connectTo(address)
    }

    fun assignRightProjector(address: String) {
        if (_leftDeviceAddress.value == address) {
            _leftDeviceAddress.value = null
            leftIp = null
        }

        if (_rightDeviceAddress.value != address) {
            rightIp = null
        }

        _rightDeviceAddress.value = address
        connectTo(address)
    }

    fun requestProjectorWifiIp() {
        Log.i(TAG, "Broadcasting REQUEST_WIFI_IP command to connected devices")
        service?.binder?.getBleManager()?.dispatchGeneralRequest(BLE_WIFI_REQUEST_COMMAND)
    }

    fun disconnectDevice(address: String) {
        if (_leftDeviceAddress.value == address) {
            _leftDeviceAddress.value = null
            leftIp = null
        }
        if (_rightDeviceAddress.value == address) {
            _rightDeviceAddress.value = null
            rightIp = null
        }

        service?.binder?.disconnectDevice(address)
    }

    fun tearDownBle() {
        Log.i(TAG, "Tearing down BLE connections and instance...")

        service?.binder?.getBleManager()?.removeListener(commandListener)

        stopScan()

        // Clear local states
        _leftDeviceAddress.value = null
        _rightDeviceAddress.value = null

        leftIp = null
        rightIp = null

        // Tell service to destroy the manager
        service?.binder?.shutdownBle()
    }

    fun removeListener() {
        service?.binder?.getBleManager()?.removeListener(commandListener)
    }

    private fun setupBleObservation(manager: com.example.remotecontrolprojector.sync.SyncBluetoothManager) {
        bleObserverJob?.cancel()

        // Re-attach listener
        manager.removeListener(commandListener)
        manager.addListener(commandListener)

        bleObserverJob = viewModelScope.launch {
            launch {
                manager.scanResults.collectLatest { results ->
                    _scanResults.value = results.sortedWith(
                        compareBy<ScanResult> { result ->
                            // PRIORITY first: Keep Selected devices at the top (Stable)
                            val isSelected = result.device.address == _leftDeviceAddress.value ||
                                    result.device.address == _rightDeviceAddress.value
                            if (isSelected) 0 else 1
                        }.thenBy { result ->
                            // PRIORITY second: Sort by MAC Address (Stable)
                            result.device.address
                        }
                    )
                }
            }

            // Observe Connection Status
            // This ensures the button only shows when the connection is physically ready.
            launch {
                manager.connectedDevices.collectLatest { devices ->
                    _connectedBleDevices.value = devices
                }
            }
        }
    }

    private fun connectTo(address: String) {
        Log.i(TAG, "Initiating connection to $address")
        service?.binder?.connectToDevice(address)
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared")
        super.onCleared()
    }

    companion object {
        val BLE_WIFI_REQUEST_COMMAND = "REQUEST_WIFI_IP"
    }
}