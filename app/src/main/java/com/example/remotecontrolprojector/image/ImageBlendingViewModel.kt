package com.example.remotecontrolprojector.image

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

class ImageBlendingViewModel : ViewModel() {

    private val TAG = "Projector:ImageViewModel"

    // Service references
    private var leftClient: RemoteControlClient? = null
    private var rightClient: RemoteControlClient? = null

    private var eventObservationJob: Job? = null

    // --- UI State Flows ---

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentImageIndex = MutableStateFlow(0)
    val currentImageIndex: StateFlow<Int> = _currentImageIndex.asStateFlow()

    private val _isControlsEnabled = MutableStateFlow(false)
    val isControlsEnabled: StateFlow<Boolean> = _isControlsEnabled.asStateFlow()

    fun setService(service: RemoteService) {
        this.leftClient = service.binder.getLeftClient()
        this.rightClient = service.binder.getRightClient()
        setupObservation()
    }

    private fun setupObservation() {
        eventObservationJob?.cancel()

        val leftClient = leftClient ?: return
        // We primarily listen to the Left (Master) client for state

        eventObservationJob = viewModelScope.launch {
            leftClient.connectionState.collect { state ->
                val isConnected = (state == RemoteControlClient.ConnectionState.CONNECTED)
                _isControlsEnabled.value = isConnected

                if (isConnected) {
                    // Workaround delay to ensure connection is stable before requesting info
                    delay(500)
                    Log.d(TAG, "Left client connected, requesting Image info")
                    leftClient.getImageInfo()
                } else {
                    _isPlaying.value = false
                }
            }
        }

        leftClient.eventFlow.onEach { message ->
            handleMasterMessage(message)
        }.launchIn(viewModelScope)
    }

    private fun handleMasterMessage(message: RemoteMessage) {
        when (message) {
            // Initial State Sync
            is RemoteMessage.GetImageInfoResponse -> {
                Log.d(
                    TAG,
                    "Received Info: Index=${message.currentIndex}, Playing=${message.isPlaying}"
                )
                _currentImageIndex.value = message.currentIndex
                _isPlaying.value = message.isPlaying
            }

            is RemoteMessage.NotifyImagePlayState -> {
                _isPlaying.value = message.isPlaying
            }

            // Passive Notification: Image Index Changed
            is RemoteMessage.NotifyImageIndex -> {
                _currentImageIndex.value = message.currentIndex
            }

            else -> { /* Ignore video messages or other types */
            }
        }
    }

    // --- Public Commands ---

    fun sendPlay() {
        leftClient?.startSlideshow()
    }

    fun sendPause() {
        leftClient?.pauseSlideshow()
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared")
        eventObservationJob?.cancel()
        leftClient = null
        rightClient = null
        _isControlsEnabled.value = false
    }
}