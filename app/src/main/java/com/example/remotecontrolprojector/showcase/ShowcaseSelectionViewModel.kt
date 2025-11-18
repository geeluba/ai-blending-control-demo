package com.example.remotecontrolprojector.showcase

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotecontrolprojector.RemoteService
import com.example.remotecontrolprojector.remote.RemoteCommand
import com.example.remotecontrolprojector.remote.RemoteMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ShowcaseSelectionViewModel : ViewModel() {

    private val TAG = "Projector:ShowcaseSelectionVM"

    private var service: RemoteService? = null

    private var targetRightDeviceName: String? = null
    private var hasSentDiscoveryRequest = false

    // State to control UI enablement (buttons active only if both projectors connected)
    private val _isPeerPaired = MutableStateFlow(false)
    private val _areProjectorsConnected = MutableStateFlow(false)
    val areProjectorsConnected: StateFlow<Boolean> = _areProjectorsConnected.asStateFlow()

    fun setService(remoteService: RemoteService?) {
        this.service = remoteService

        if (remoteService != null) {
            val leftConnectionFlow = remoteService.binder.getLeftConnectionState()
            val rightConnectionFlow = remoteService.binder.getRightConnectionState()

            viewModelScope.launch {
                remoteService.binder.getLeftClient()?.eventFlow?.collect { message ->
                    if (message is RemoteMessage.NotifyPeerConnectionDone) {
                        Log.i(
                            TAG,
                            "Received NotifyPeerConnectionDone from Left Projector. isDone: ${message.status}"
                        )
                        _isPeerPaired.value = message.status
                    }
                }
            }

            viewModelScope.launch {
                combine(leftConnectionFlow, rightConnectionFlow) { left, right ->
                    left && right
                }.collect { bothWebSocketsConnected ->

                    // Trigger Discovery Logic (Depends only on WebSockets being ready)
                    if (bothWebSocketsConnected && !hasSentDiscoveryRequest && targetRightDeviceName != null) {
                        Log.i(
                            TAG,
                            "WebSockets connected. Sending StartDiscoveryRequest to LEFT projector with RIGHT name: $targetRightDeviceName"
                        )
                        sendDiscoveryRequestToLeft(targetRightDeviceName!!)
                        hasSentDiscoveryRequest = true
                    }
                }
            }

            viewModelScope.launch {
                combine(
                    leftConnectionFlow,
                    rightConnectionFlow,
                    _isPeerPaired
                ) { left, right, paired ->
                    left && right && paired
                }.collect { allReady ->
                    // Only enable buttons when everything is fully connected and paired
                    _areProjectorsConnected.value = allReady
                }
            }
        }
    }

    fun initializeAndConnect(leftIp: String?, rightIp: String?, rightName: String?) {
        this.targetRightDeviceName = rightName
        Log.d(TAG, "connectClients: leftIp = $leftIp, rightIp = $rightIp")
        if (!leftIp.isNullOrEmpty()) {
            service?.binder?.connectLeftClient(leftIp)
        }
        if (!rightIp.isNullOrEmpty()) {
            service?.binder?.connectRightClient(rightIp)
        }
    }

    fun disconnectClients(delayMs: Long = 0L) {
        Log.d(TAG, "disconnectClients")
        service?.binder?.disconnectAllClients(delayMs)
    }

    private fun sendDiscoveryRequestToLeft(targetName: String) {
        service?.binder?.getLeftClient()?.sendDiscoveryAndConnect(targetName)
    }

    fun informRemoteClientsBlendingMode(mode: RemoteCommand.BlendingMode) {
        service?.binder?.getLeftClient()?.sendBlendingMode(mode, true)
        service?.binder?.getRightClient()?.sendBlendingMode(mode, false)
    }


    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared")
        hasSentDiscoveryRequest = false
        informRemoteClientsBlendingMode(RemoteCommand.BlendingMode.NONE)
        disconnectClients(300)
    }
}