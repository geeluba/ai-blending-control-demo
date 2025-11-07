package com.example.remotecontrolprojector

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotecontrolprojector.remote.RemoteControlClient
import com.example.remotecontrolprojector.remote.RemoteMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class RemoteControlViewModel : ViewModel() {

    private val TAG = "Projector:RemoteVM"

    // !!! IMPORTANT: Update this IP to your server device !!!
    private val serverIp = "172.25.112.181"

    // The Ktor WebSocket client
    private val remoteClient = RemoteControlClient(viewModelScope)

    // --- Public State Flows for the UI to observe ---

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    init {
        // Observe the connection state from the client
        remoteClient.connectionState
            .onEach { state ->
                val connected = (state == RemoteControlClient.ConnectionState.CONNECTED)
                _isConnected.value = connected

                if (connected) {
                    // Once connected, ask the server for its current state
                    Log.d(TAG, "Connected! Requesting video info.")
                    requestVideoInfo()
                } else {
                    Log.d(TAG, "Disconnected or in error state.")
                    // Clear state on disconnect
                    _isPlaying.value = false
                }
            }
            .launchIn(viewModelScope)

        // Observe incoming messages from the server
        remoteClient.eventFlow
            .onEach { message ->
                //Log.d(TAG, "Received message: $message")
                when (message) {
                    is RemoteMessage.GetVideoInfoResponse -> {
                        _durationMs.value = message.durationMs
                        _currentPositionMs.value = message.positionMs
                        _isPlaying.value = message.isPlaying
                    }

                    is RemoteMessage.GetVideoDurationResponse -> {
                        // Note: Per your RemoteMessage.kt, this only contains position.
                        _currentPositionMs.value = message.positionMs
                    }

                    is RemoteMessage.NotifyVideoPosition -> {
                        _currentPositionMs.value = message.positionMs
                    }

                    is RemoteMessage.NotifyVideoPlayState -> {
                        _isPlaying.value = message.isPlaying
                    }

                    is RemoteMessage.ErrorResponse -> {
                        if (message.errorCode != RemoteMessage.RemoteErrorCode.NO_ERROR) {
                            Log.e(
                                TAG,
                                "Received error from server: ${message.errorCode} " +
                                        "for request: ${message.errorCode}"
                            )
                            //todo: Handle errors appropriately in the UI
                        } else {
                            // NO_ERROR is just a success ACK
                            //how to print simple name of message.command

                            Log.d(
                                TAG,
                                "Received Successful for command: ${message.command?.javaClass?.simpleName}"
                            )
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Attempts to connect to the hardcoded server IP.
     */
    fun connect() {
        remoteClient.connect(serverIp)
    }

    /**
     * Asks the server to send its full playback state.
     */
    fun requestVideoInfo() {
        remoteClient.getVideoInfo()
    }

    // --- Playback Commands ---

    fun sendPlay() {
        remoteClient.sendPlay()
    }

    fun sendPause() {
        remoteClient.sendPause()
    }

    fun sendSeek(positionMs: Long) {
        remoteClient.sendSeek(positionMs)
    }

    /**
     * Clean up the client when the ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        remoteClient.release()
    }
}