package com.example.remotecontrolprojector.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SyncMessage

/**
 * Client sends a command to be executed immediately.
 */
@Serializable
@SerialName("GeneralCommand")
data class GeneralCommand(val command: String) : SyncMessage()


@Serializable
@SerialName("GeneralRequest")
data class GeneralRequest(val command: String) : SyncMessage()

@Serializable
@SerialName("GeneralResponse")
data class GeneralResponse(val command: String, val value: String) : SyncMessage()