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
        val requestId: String,
        val durationMs: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
    ) : RemoteMessage()

    @Serializable
    @SerialName("GetVideoDurationResponse")
    data class GetVideoDurationResponse(val requestId: String, val positionMs: Long) :
        RemoteMessage()

    @Serializable
    @SerialName("AckResponse")
    data class AckResponse(
        val requestId: String,
        val command: String,
    ) : RemoteMessage()

    @Serializable
    @SerialName("ErrorResponse")
    data class ErrorResponse(
        val requestId: String?,
        val command: String,
        val errorCode: RemoteErrorCode,
        val errorMessage: String? = null,
    ) : RemoteMessage()


    //notify usage messages
    @Serializable
    @SerialName("NotifyVideoPosition")
    data class NotifyVideoPosition(val positionMs: Long) : RemoteMessage()

    @Serializable
    @SerialName("NotifyVideoPlayState")
    data class NotifyVideoPlayState(val isPlaying: Boolean) : RemoteMessage()
}