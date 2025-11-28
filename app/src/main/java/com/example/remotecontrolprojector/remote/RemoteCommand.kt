package com.example.remotecontrolprojector.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the JSON messages (commands) sent *from* the remote client *to* this server.
 */
@Serializable
sealed class RemoteCommand {

    abstract val requestId: String

    @Serializable
    @SerialName("ConnectToMacRequest")
    data class ConnectToMacRequest(
        override val requestId: String,
        val targetMacAddress: String
    ) : RemoteCommand()

    @Serializable
    @SerialName("StartDiscoveryRequest")
    data class StartDiscoveryRequest(
        override val requestId: String,
        val targetDeviceName: String,
    ) : RemoteCommand()

    @Serializable
    @SerialName("BlendingModeRequest")
    data class BlendingModeRequest(
        override val requestId: String,
        val mode: BlendingMode,
        val isController: Boolean,
    ) : RemoteCommand()

    enum class BlendingMode {
        NONE,
        STANDBY,
        IMAGE,
        VIDEO
    }

    // --- Info Requests ---
    @Serializable
    @SerialName("GetVideoInfoRequest")
    data class GetVideoInfoRequest(override val requestId: String) : RemoteCommand()

    @Serializable
    @SerialName("GetVideoDurationRequest")
    data class GetVideoDurationRequest(override val requestId: String) : RemoteCommand()


    // --- Playback Commands ---
    @Serializable
    @SerialName("VideoPlayRequest")
    data class VideoPlayRequest(override val requestId: String) : RemoteCommand()

    @Serializable
    @SerialName("VideoPauseRequest")
    data class VideoPauseRequest(override val requestId: String) : RemoteCommand()

    @Serializable
    @SerialName("VideoSeekRequest")
    data class VideoSeekRequest(override val requestId: String, val positionMs: Long) :
        RemoteCommand()

    @Serializable
    @SerialName("ImagePlayRequest")
    data class ImagePlayRequest(override val requestId: String) : RemoteCommand()

    @Serializable
    @SerialName("ImagePauseRequest")
    data class ImagePauseRequest(override val requestId: String) : RemoteCommand()

    @Serializable
    @SerialName("GetImageInfoRequest")
    data class GetImageInfoRequest(override val requestId: String) : RemoteCommand()
}