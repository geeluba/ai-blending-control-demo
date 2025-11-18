package com.example.remotecontrolprojector // Or your service package

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.remotecontrolprojector.dataOverSound.DeviceDiscoveryManager
import com.example.remotecontrolprojector.remote.RemoteControlClient
import com.example.remotecontrolprojector.sync.BaseCommunicationManager
import com.example.remotecontrolprojector.sync.SyncBluetoothManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A general-purpose foreground service to manage a remote BLE connection.
 *
 * This ensures that BLE scanning, connection, and data transfer
 * can continue even when the main app UI is in the background.
 */
class RemoteService : Service() {

    private val TAG = "Projector:RemoteService"

    // A dedicated scope for the service
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var bleCommunicationManager: BaseCommunicationManager? = null
    private var deviceDiscoveryManager: DeviceDiscoveryManager? = null
    private var leftClient: RemoteControlClient? = null
    private var rightClient: RemoteControlClient? = null

    // A default flow to return if clients are null (avoids creating new flows constantly)
    private val _disconnectedState = MutableStateFlow(false).asStateFlow()
    internal val binder = RemoteBinder()


    /**
     * The Binder class that allows clients (ViewModels) to
     * get a reference to the GGWaveBleClient.
     */
    inner class RemoteBinder : Binder() {

        fun getService(): RemoteService = this@RemoteService

        fun getBleManager(): SyncBluetoothManager? {
            return bleCommunicationManager as SyncBluetoothManager?
        }

        //ble related
        fun initBle() = this@RemoteService.initializeBle()
        fun shutdownBle() = this@RemoteService.shutdownBle()
        fun startScan() = this@RemoteService.startScan()
        fun stopScan() = this@RemoteService.stopScan()
        fun connectToDevice(macAddress: String) = this@RemoteService.connectToDevice(macAddress)
        fun disconnectDevice(macAddress: String) = this@RemoteService.disconnectDevice(macAddress)

        //websocket related
        fun connectLeftClient(ip: String) = this@RemoteService.connectLeftClient(ip)
        fun connectRightClient(ip: String) = this@RemoteService.connectRightClient(ip)
        fun disconnectAllClients(delayMs: Long = 0L) =
            this@RemoteService.disconnecAllClients(delayMs)

        fun getLeftConnectionState(): StateFlow<Boolean> =
            leftClient?.isConnected ?: _disconnectedState

        fun getRightConnectionState(): StateFlow<Boolean> =
            rightClient?.isConnected ?: _disconnectedState

        fun getLeftClient(): RemoteControlClient? {
            return leftClient
        }

        fun getRightClient(): RemoteControlClient? {
            return rightClient
        }
    }

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RemoteService created.")
        createNotificationChannel()

        bleCommunicationManager = SyncBluetoothManager(this, serviceScope)

        deviceDiscoveryManager = DeviceDiscoveryManager(this, serviceScope)
        deviceDiscoveryManager?.init()

        leftClient = RemoteControlClient(serviceScope)
        rightClient = RemoteControlClient(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "RemoteService started.")

        val notification = createNotification("Initializing...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }


        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Client binding to RemoteService.")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Client unbinding.")
        return true // Allow re-binding
    }

    override fun onDestroy() {
        Log.d(TAG, "RemoteService destroyed.")
        stopForeground(STOP_FOREGROUND_REMOVE)

        bleCommunicationManager?.shutdown()
        bleCommunicationManager = null

        //release the device discovery manager
        deviceDiscoveryManager?.deinit()
        deviceDiscoveryManager = null

        leftClient?.release()
        leftClient = null
        rightClient?.release()
        rightClient = null

        // Cancel the service's scope
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Notification Helper Methods ---

    private fun createNotification(text: String): Notification {
        val notificationIcon = R.drawable.ic_launcher_foreground // Change this icon

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Control")
            .setContentText(text)
            .setSmallIcon(notificationIcon)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Remote Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    //==========ble client methods end==========
    fun startScan() {
        // Ensure we are in CLIENT mode before scanning
        if (bleCommunicationManager?.clientState?.value == BaseCommunicationManager.ClientState.DISCONNECTED ||
            bleCommunicationManager?.clientState?.value == BaseCommunicationManager.ClientState.OFF
        ) {
            bleCommunicationManager?.setup(BaseCommunicationManager.Role.CLIENT)
        }
        (bleCommunicationManager as? SyncBluetoothManager)?.startScan()
    }

    fun stopScan() {
        (bleCommunicationManager as? SyncBluetoothManager)?.stopScan()

    }

    fun initializeBle() {
        Log.i(TAG, "Initializing fresh BLE Manager instance...")
        bleCommunicationManager?.shutdown()
        bleCommunicationManager = SyncBluetoothManager(this, serviceScope)
    }

    fun shutdownBle() {
        Log.i(TAG, "Tearing down BLE Manager instance.")
        bleCommunicationManager?.shutdown()
        bleCommunicationManager = null
    }

    fun connectToDevice(macAddress: String) {
        (bleCommunicationManager as? SyncBluetoothManager)?.setup(
            BaseCommunicationManager.Role.CLIENT,
            macAddress
        )
    }

    fun disconnectDevice(macAddress: String) {
        (bleCommunicationManager as? SyncBluetoothManager)?.disconnectDevice(macAddress)
    }


    //===websocket client methods ==========
    fun connectLeftClient(ip: String) {
        Log.i(TAG, "Connecting LEFT WebSocket to $ip")
        // Assuming RemoteControlClient has a connect method accepting IP
        leftClient?.connect(ip)
    }

    fun connectRightClient(ip: String) {
        Log.i(TAG, "Connecting RIGHT WebSocket to $ip")
        rightClient?.connect(ip)
    }

    fun disconnecAllClients(delayMs: Long) {
        Log.i(TAG, "Disconnecting all WebSocket clients.")
        serviceScope.launch {
            if (delayMs > 0L) {
                kotlinx.coroutines.delay(delayMs)
            }
            leftClient?.disconnect()
            rightClient?.disconnect()
        }
    }

    //==========device pairing==========

    fun startDiscoveringDeviceMessages() {
        Log.d(TAG, "Starting device discovery via sound...")
        deviceDiscoveryManager?.discoverDeviceMessage { message ->
            Log.i(TAG, "ggwave discovered message: $message")
        }
    }

    fun stopDiscoveringDeviceMessages() {
        Log.d(TAG, "Stopping device discovery via sound...")
        deviceDiscoveryManager?.stopDiscoverDeviceMessage()
    }

    companion object {
        const val CHANNEL_ID = "RemoteServiceChannel"
        const val NOTIFICATION_ID = 2 // Use a different ID from your other service
    }
}