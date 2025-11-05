package com.example.remotecontrolprojector.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the JSON messages (commands) sent *from* the remote client *to* this server.
 */
@Serializable
sealed class RemoteCommand {

    // --- Info Requests ---
    @Serializable
    @SerialName("GetVideoInfoRequest")
    object GetVideoInfoRequest : RemoteCommand()

    @Serializable
    @SerialName("GetVideoDurationRequest")
    object GetVideoDurationRequest : RemoteCommand()


    // --- Playback Commands ---
    @Serializable
    @SerialName("VideoPlayRequest")
    object VideoPlayRequest : RemoteCommand()

    @Serializable
    @SerialName("VideoPauseRequest")
    object VideoPauseRequest : RemoteCommand()

    @Serializable
    @SerialName("VideoSeekRequest")
    data class VideoSeekRequest(val positionMs: Long) : RemoteCommand()
}