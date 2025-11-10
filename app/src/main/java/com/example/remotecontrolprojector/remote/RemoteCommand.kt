package com.example.remotecontrolprojector.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the JSON messages (commands) sent *from* the remote client *to* this server.
 */
@Serializable
sealed class RemoteCommand {

    abstract val requestId: String

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
}