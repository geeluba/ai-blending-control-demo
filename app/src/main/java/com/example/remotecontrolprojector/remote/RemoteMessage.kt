package com.example.remotecontrolprojector.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the JSON messages sent *from* this server *to* the remote client.
 */
@Serializable
sealed class RemoteMessage {

    @Serializable
    @SerialName("GetVideoInfoResponse")
    data class GetVideoInfoResponse(
        val durationMs: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
    ) : RemoteMessage()

    @Serializable
    @SerialName("GetVideoDurationResponse")
    data class GetVideoDurationResponse(val positionMs: Long) : RemoteMessage()


    //notify usage messages
    @Serializable
    @SerialName("NotifyVideoPosition")
    data class NotifyVideoPosition(val positionMs: Long) : RemoteMessage()

    @Serializable
    @SerialName("NotifyVideoPlayState")
    data class NotifyVideoPlayState(val isPlaying: Boolean) : RemoteMessage()
}