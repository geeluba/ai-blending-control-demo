package com.example.remotecontrolprojector.dataOverSound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class DeviceDiscoveryManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
) {

    val TAG = "Projector:DeviceDiscoveryManager"

    //for brpoadcasting
    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null

    //for discovery
    private var discoveryJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioBufferSize: Int = 0
    private lateinit var audioBuffer: ByteArray

    private var lastDiscoveredMessage: String? = null
    private var lastDiscoveredTimestamp: Long = 0L
    private val MESSAGE_DEBOUNCE_MS = 1000L // 3-second cooldown

    private val sampleRate = Ggwave.GGWAVE_SAMPLE_RATE_48000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO

    private lateinit var ggwave: Ggwave

    fun init() {
        ggwave = Ggwave()
        ggwave.init(
            payloadLength = 20,
            sampleRate = Ggwave.GGWAVE_SAMPLE_RATE_48000,
            sampleFormat = Ggwave.GGWAVE_SAMPLE_FORMAT_I16
        )
    }

    fun deinit() {

        stopDiscoverDeviceMessage()
        cancelSendingDeviceMessage()
        if (::ggwave.isInitialized) {
            ggwave.deinit()
        }
    }

    fun sendDeviceMessage(
        message: String,
        protocol: Int = Ggwave.GGWAVE_PROTOCOL_ULTRASOUND_FASTEST,
        volume: Int = 50,
    ) {
        if (!::ggwave.isInitialized) {
            Log.e(TAG, "Ggwave is not initialized. Call init() first.")
            return
        }

        if (playbackJob?.isActive == true) {
            Log.w(TAG, "Playback already in progress. Call stop() first.")
            return
        }

        val audioData = ggwave.encode(message, protocol, volume)
        if (audioData == null || audioData.isEmpty()) {
            Log.e(TAG, "Failed to encode message or message is empty.")
            return
        }
        Log.d(TAG, "Encoded message, audio data size: ${audioData.size} bytes.")

        // Use the provided serviceScope to launch the job
        playbackJob = serviceScope.launch {
            playAudioOnce(audioData) // <-- Updated call
        }
    }

    fun cancelSendingDeviceMessage() {
        playbackJob?.cancel()
        playbackJob = null
        Log.d(TAG, "device message sending job cancelled.")
    }

    private suspend fun playAudioOnce(audioData: ByteArray) {
        val playbackComplete = CompletableDeferred<Unit>()

        try {
            coroutineContext.ensureActive()
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(audioData.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.let { track ->
                val written = track.write(audioData, 0, audioData.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack write error: $written")
                    return
                }
                Log.d(TAG, "AudioTrack wrote $written bytes.")

                // 16-bit format (2 bytes per frame)
                val totalFrames = audioData.size / 2
                track.setNotificationMarkerPosition(totalFrames)

                track.setPlaybackPositionUpdateListener(object :
                    AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        Log.d(TAG, "Playback marker reached (one-shot complete).")
                        playbackComplete.complete(Unit)
                    }

                    override fun onPeriodicNotification(track: AudioTrack?) {}
                })

                coroutineContext.ensureActive()
                track.play()

                delay(10)
                shallUseSpeakerToEmitAudio()
                Log.d(TAG, "AudioTrack playing (one-shot)...")

                playbackComplete.await()
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.i(TAG, "Playback coroutine cancelled.")
            } else {
                Log.e(TAG, "Error during audio playback", e)
            }
            playbackComplete.completeExceptionally(e)
        } finally {
            audioTrack?.apply {
                try {
                    setPlaybackPositionUpdateListener(null)
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                        stop()
                    }
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping/releasing AudioTrack", e)
                }
            }
            audioTrack = null
        }
    }

    fun discoverDeviceMessage(onMessageFound: (String) -> Unit) {
        if (discoveryJob?.isActive == true) {
            Log.w(TAG, "Discovery is already in progress.")
            return
        }

        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Cannot start discovery.")
            return
        }

        try {
            // Get the minimum buffer size for recording
            val minBufferSize =
                AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Failed to get min buffer size for AudioRecord")
                return
            }
            val appMinBufferSize = 4096 // (1024 * 4)
            // Use the LARGER of the two values
            audioBufferSize = maxOf(minBufferSize, appMinBufferSize)

            Log.i(
                TAG,
                "AudioRecord buffer: hardware min is $minBufferSize, app min is $appMinBufferSize. Using $audioBufferSize"
            )
            audioBuffer = ByteArray(audioBufferSize)

            // Create AudioRecord instance
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfigIn)
                        .build()
                )
                .setBufferSizeInBytes(audioBufferSize)
                .build()

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord. Check RECORD_AUDIO permission.", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing AudioRecord", e)
            return
        }

        // Launch the recording and decoding job
        discoveryJob = serviceScope.launch {
            try {
                lastDiscoveredMessage = null
                lastDiscoveredTimestamp = 0L

                audioRecord?.startRecording()
                Log.i(TAG, "Audio discovery started. Listening for messages...")

                while (isActive) {
                    val read = audioRecord?.read(audioBuffer, 0, audioBufferSize) ?: 0
                    if (read > 0) {
                        // Feed the audio chunk to the ggwave decoder
                        val message = ggwave.decode(audioBuffer, read)
                        if (message != null && message.isNotEmpty()) {
                            val currentTime = System.currentTimeMillis()

                            // Check if it's a NEW message OR the cooldown has passed
                            if (message != lastDiscoveredMessage || (currentTime - lastDiscoveredTimestamp) > MESSAGE_DEBOUNCE_MS) {
                                // This is a new, valid message
                                lastDiscoveredMessage = message
                                lastDiscoveredTimestamp = currentTime

                                Log.i(TAG, "Discovered message: $message")
                                onMessageFound(message)
                            } else {
                                // It's a duplicate, just log it at a verbose level and ignore
                                Log.v(TAG, "Ignoring duplicate message: $message")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.i(TAG, "Discovery coroutine cancelled.")
                } else {
                    Log.e(TAG, "Error during audio recording/decoding", e)
                }
            } finally {
                // Ensure resources are released when the job stops
                Log.d(TAG, "Releasing AudioRecord resources.")
                audioRecord?.apply {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                    release()
                }
                audioRecord = null
            }
        }
    }

    fun stopDiscoverDeviceMessage() {
        discoveryJob?.cancel()
        discoveryJob = null
        Log.d(TAG, "Device discovery stopped.")
    }

    private fun shallUseSpeakerToEmitAudio(): Boolean {
        // Get the audio manager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Check if a media stream is active (isMusicActive() is true even if muted)
        val isMusicActive = audioManager.isMusicActive

        // Check that no headphones are connected
        val isWiredHeadset = audioManager.isWiredHeadsetOn
        val isBluetoothA2dp = audioManager.isBluetoothA2dpOn

        // Determine the route
        // If music is active AND no headphones are on, the speaker must be the route.
        val isSpeakerTheRoute = isMusicActive && !isWiredHeadset && !isBluetoothA2dp

        // Now, check the mute/volume state
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.d(
            TAG,
            "isMusicActive: $isMusicActive, musicVolume: $musicVolume, isWiredHeadset: $isWiredHeadset, isBluetoothA2dp: $isBluetoothA2dp"
        )
        var isMutedOrZero = (musicVolume == 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Also check the specific mute flag (API 23+)
            isMutedOrZero = isMutedOrZero || audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        }

        if (isSpeakerTheRoute) {
            if (isMutedOrZero) {
                Log.d(TAG, "Speaker is the route, but it's muted.")
                return false
            } else {
                Log.d(TAG, "Speaker is the route and is audibly playing (Volume: $musicVolume)")
                return true
            }
        } else {
            Log.d(TAG, "Speaker is NOT the route (e.g., headphones are in, or nothing is playing).")
            return true
        }
    }
}