package com.example.remotecontrolprojector.video

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotecontrolprojector.RemoteService
import com.example.remotecontrolprojector.remote.RemoteControlClient
import com.example.remotecontrolprojector.remote.RemoteMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class VideoBlendingViewModel : ViewModel() {

    private val TAG = "Projector:VideoViewModel"

    // Service references
    private var leftClient: RemoteControlClient? = null
    private var rightClient: RemoteControlClient? = null

    // Job to handle event observation (so we can cancel it if service unbinds)
    private var eventObservationJob: Job? = null

    // --- UI State Flows ---

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isControlsEnabled = MutableStateFlow(false)
    val isControlsEnabled: StateFlow<Boolean> = _isControlsEnabled.asStateFlow()

    init {

    }

    fun setService(service: RemoteService) {
        this.leftClient = service.binder.getLeftClient()
        this.rightClient = service.binder.getRightClient()
        setupObservation()
    }

    private fun setupObservation() {
        eventObservationJob?.cancel()

        val leftClient = leftClient ?: return
        val rightClient = rightClient ?: return

        eventObservationJob = viewModelScope.launch {
            leftClient.connectionState.collect { state ->
                val isConnected = (state == RemoteControlClient.ConnectionState.CONNECTED)
                _isControlsEnabled.value = isConnected
                if (isConnected) {
                    //todo: workaround, fix later
                    delay(500)
                    Log.d(TAG, "Left client connected, requesting video info")
                    leftClient.getVideoInfo()
                } else {
                    _isPlaying.value = false
                }
            }
        }

        leftClient.eventFlow.onEach { message ->
            handleMasterMessage(message)
        }.launchIn(viewModelScope)

        rightClient.eventFlow.onEach { message ->
            handleSlaveMessage(message)
        }.launchIn(viewModelScope)
    }

    private fun handleMasterMessage(message: RemoteMessage) {
        when (message) {
            is RemoteMessage.GetVideoInfoResponse -> {
                _durationMs.value = message.durationMs
                _currentPositionMs.value = message.positionMs
                _isPlaying.value = message.isPlaying
            }

            is RemoteMessage.NotifyVideoPosition -> {
                _currentPositionMs.value = message.positionMs
            }

            is RemoteMessage.NotifyVideoPlayState -> {
                _isPlaying.value = message.isPlaying
            }

            is RemoteMessage.GetVideoDurationResponse -> {
                // Usually handled by InfoResponse, but good for safety
                if (_durationMs.value == 0L) {
                    // Assuming message might carry duration in future,
                    // currently acts as position update in your existing VM logic
                    _currentPositionMs.value = message.positionMs
                }
            }

            else -> { /* Ignore unrelated messages */
            }
        }
    }

    private fun handleSlaveMessage(message: RemoteMessage) {
        // For now, we don't process Slave messages in this blending scenario.
        // All relevant state comes from the Master (Left) client.
    }

    // --- Public Commands ---
    fun sendPlay() {
        leftClient?.sendPlay()
    }

    fun sendPause() {
        leftClient?.sendPause()
    }

    fun sendSeek(positionMs: Long) {
        // Seek the master
        leftClient?.sendSeek(positionMs)

        // Optimistic UI update
        _currentPositionMs.value = positionMs
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared")
        eventObservationJob?.cancel()
        leftClient = null
        rightClient = null
        _isControlsEnabled.value = false
    }
}
