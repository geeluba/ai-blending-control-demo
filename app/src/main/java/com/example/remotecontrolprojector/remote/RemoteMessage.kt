package com.example.remotecontrolprojector.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the JSON messages sent *from* this server *to* the remote client.
 */
@Serializable
sealed class RemoteMessage {

    @Serializable
    enum class RemoteErrorCode {

        NO_ERROR,

        /** Command JSON was malformed or couldn't be deserialized. */
        COMMAND_PARSE_ERROR,

        /** The requested command is unknown or not supported. */
        UNKNOWN_COMMAND,

        /** Failed to serialize the response. */
        RESPONSE_SERIALIZE_ERROR,

        /** General server error. */
        SERVER_INTERNAL_ERROR
    }

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


    @Serializable
    @SerialName("ErrorResponse")
    data class ErrorResponse(val command: RemoteCommand?, val errorCode: RemoteErrorCode) : RemoteMessage()


    //notify usage messages
    @Serializable
    @SerialName("NotifyVideoPosition")
    data class NotifyVideoPosition(val positionMs: Long) : RemoteMessage()

    @Serializable
    @SerialName("NotifyVideoPlayState")
    data class NotifyVideoPlayState(val isPlaying: Boolean) : RemoteMessage()
}