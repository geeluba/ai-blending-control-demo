package com.example.remotecontrolprojector.sync

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.util.concurrent.CopyOnWriteArrayList


abstract class BaseCommunicationManager(
    protected val managerScope: CoroutineScope,
) {

    // Make TAG abstract so children can define their own
    protected abstract val TAG: String


    // --- (All Enums and Interfaces are defined here) ---
    enum class Role { SERVER, CLIENT }
    enum class ServerState { STOPPED, RUNNING, OFF }
    enum class ClientState { DISCONNECTED, CONNECTING, CONNECTED, ERROR, OFF }

    /*interface CommandListener {
        fun onCommandReceived(command: String)
    }*/

    /**
     * For BLE, senderId is the MAC Address.
     */
    interface CommandListener {
        fun onCommandReceived(command: String, senderId: String)
    }

    // --- (Serialization is defined here) ---
    protected val appSerializersModule = SerializersModule {
        polymorphic(SyncMessage::class) {
            subclass(GeneralCommand::class)
            subclass(GeneralRequest::class)
            subclass(GeneralResponse::class)
        }
    }
    protected val json = Json {
        ignoreUnknownKeys = true
        serializersModule = appSerializersModule
        classDiscriminator = "type"
    }

    protected val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState
    protected val _clientState = MutableStateFlow(ClientState.DISCONNECTED)
    val clientState: StateFlow<ClientState> = _clientState

    protected var currentRole: Role? = null
    protected var currentServerIp: String? = null
    protected var serverJob: Job? = null
    protected var clientJob: Job? = null

    protected val clientMutex = Mutex()
    protected val serverMutex = Mutex()
    protected var correctionLoopJob: Job? = null
    protected val listeners = CopyOnWriteArrayList<CommandListener>()

    // --- (Abstract methods to be implemented by children) ---

    /** Starts the server (e.g., listen on a port, start advertising). */
    protected abstract fun startServer()

    /** Stops the server. */
    protected abstract fun stopServer()

    /**
     * Connects to the server.
     * @param serverIp The IP address (for Ktor) or null (for BLE scan).
     */
    protected abstract fun connectToServer(serverIp: String?)

    /** Disconnects from the server. */
    protected abstract fun disconnectFromServer()

    /**
     * Sends a [SyncMessage] from the client to the server.
     * This is the core transport mechanism for C -> S.
     */
    protected abstract fun sendMessage(message: SyncMessage)

    /**
     * Sends a direct response from the server to a specific client.
     * @param responseString The JSON string to send.
     * @param sender A transport-specific object identifying the client (e.g., WebSocketSession, BluetoothDevice).
     */
    protected abstract suspend fun sendDirectResponse(responseString: String, sender: Any)

    /**
     * Broadcasts a command JSON string from the server to all clients.
     * @param jsonString The JSON string to send.
     * @param excludeSender A transport-specific object to exclude (the original sender).
     */
    protected abstract fun broadcastCommand(jsonString: String, excludeSender: Any? = null)

    /**
     * Broadcasts a synced command JSON string from the server to all clients.
     * @param jsonString The JSON string to send.
     * @param excludeSender A transport-specific object to exclude (the original sender).
     */
    protected abstract fun broadcastSyncedCommand(jsonString: String, excludeSender: Any? = null)

    // --- (Public API with common implementation) ---

    fun addListener(listener: CommandListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CommandListener) {
        listeners.remove(listener)
    }

    open fun setup(role: Role, serverIp: String? = null) {
        this.currentRole = role
        if (role == Role.SERVER) {
            startServer()
        } else {
            this.currentServerIp = serverIp
            connectToServer(serverIp)
        }
    }

    open fun retryConnection() {
        if (_clientState.value == ClientState.CONNECTING || _clientState.value == ClientState.CONNECTED) return
        currentServerIp?.let {
            Log.i(TAG, "[Client] Retrying connection...")
            connectToServer(it)
        } ?: Log.e(TAG, "[Client] Cannot retry: Server IP/config is unknown.")
    }

    open fun release() {
        Log.d(TAG, "Releasing all resources.")

        serverJob?.cancel()
        clientJob?.cancel()

        stopServer()
        disconnectFromServer()
    }

    /**
     * Complete shutdown. Calls release() and clears all listeners.
     * To be used by service onDestroy.
     */
    open fun shutdown() {
        Log.d(TAG, "Shutting down manager completely.")

        release() // Release transport

        // Now clear listeners
        listeners.clear()
    }

    fun dispatchGeneralCommand(command: String) {
        when (currentRole) {
            Role.CLIENT -> sendCommand(command)
            Role.SERVER -> broadcastCommandFromServer(command)
            null -> Log.e(TAG, "Cannot dispatch command: Role not set up.")
        }
    }

    fun dispatchGeneralRequest(request: String) {
        if (currentRole != Role.CLIENT) {
            Log.e(TAG, "Cannot dispatch request: Not in CLIENT role.")
            return
        } else {
            sendMessage(GeneralRequest(request))
        }
    }

    private fun sendCommand(command: String) {
        sendMessage(GeneralCommand(command))
    }

    private fun broadcastCommandFromServer(command: String) {
        if (_serverState.value != ServerState.RUNNING) {
            Log.w(TAG, "[Server] Cannot broadcast command, server is not running.")
            return
        }
        managerScope.launch {
            serverMutex.withLock {
                val message = GeneralCommand(command)
                val jsonString = json.encodeToString<SyncMessage>(message)
                broadcastCommand(jsonString, null)
            }
        }
    }

    // --- (Internal Message Handlers - called by child implementations) ---

    /**
     * The server-side logic for processing messages from a client.
     * Called by the child class's transport layer (e.g., onWebSocketReceive, onCharacteristicWrite).
     * @param sender A transport-specific object (WebSocketSession, BluetoothDevice)
     * @param text The raw JSON string received.
     */
    protected open suspend fun handleServerMessage(sender: Any, text: String) {
        /*try {
            val message = json.decodeFromString<SyncMessage>(text)

            when (message) {
                is GeneralCommand -> {
                    serverMutex.withLock {
                        Log.d(TAG, "[Server] Received GeneralCommand: ${message.command}")
                        broadcastLocalCommand(message.command)
                        broadcastCommand(text, excludeSender = sender)
                    }
                }

                else -> { /* Ignore server-to-client messages */
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Server] Failed to handle message: ${e.message} -- $text")
        }*/
    }

    /**
     * The client-side logic for processing messages from the server.
     * Called by the child class's transport layer (e.g., onWebSocketReceive, onCharacteristicChanged).
     * @param text The raw JSON string received.
     */
    protected open suspend fun handleClientMessage(text: String, senderId: String) {
        try {
            val message = json.decodeFromString<SyncMessage>(text)
            Log.d(TAG, "[Client] Received message: $text")

            when (message) {

                is GeneralResponse -> {
                    clientMutex.withLock {
                        broadcastLocalCommand(message.command + ":" + message.value, senderId)
                    }
                }

                is GeneralCommand -> {
                    clientMutex.withLock {
                        broadcastLocalCommand(message.command, senderId)
                    }
                }

                else -> { /* Ignore client-to-server messages */
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Client] Failed to handle message: ${e.message} -- $text")
        }
    }

    protected fun broadcastLocalCommand(command: String, senderId: String) {
        Log.d(TAG, "[Local] Broadcasting command: '$command' from '$senderId'")
        managerScope.launch {
            listeners.forEach { it.onCommandReceived(command, senderId) }
        }
    }
}