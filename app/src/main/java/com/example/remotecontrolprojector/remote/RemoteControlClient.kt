package com.example.remotecontrolprojector.remote

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.WebSockets // <-- Explicit import for WebSockets
import io.ktor.client.plugins.websocket.webSocket // <-- Explicit import for the .webSocket function
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Connects to the [RemoteControlServer] via WebSockets to send playback
 * commands and receive state updates.
 */
class RemoteControlClient(private val managerScope: CoroutineScope) {

    private val TAG = "Projector:RemoteClient"
    private val REMOTE_CONTROL_PORT = 9877

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    // --- (Internal State) ---
    private var connectJob: Job? = null

    @Volatile
    private var clientSession: WebSocketSession? = null
    private val clientMutex = Mutex()

    // --- (State & Event Flows) ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /**
     * Emits [RemoteMessage] objects received from the server.
     * These are responses to requests or unsolicited notifications.
     */
    private val _eventFlow = MutableSharedFlow<RemoteMessage>()
    val eventFlow = _eventFlow.asSharedFlow()

    // --- (Ktor & Serialization Setup) ---

    /**
     * The Ktor HttpClient with the WebSockets plugin installed.
     */
    private val httpClient = HttpClient(CIO) {
        install(WebSockets) // This will now resolve
    }

    /**
     * The JSON serializer, configured to match the server.
     * The `classDiscriminator` is critical for polymorphism.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "commandType"
    }

    /**
     * Attempts to connect to the remote control server.
     *
     * @param host The IP address of the device running the [RemoteControlServer].
     */
    fun connect(host: String) {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connecting or connected.")
            return
        }

        connectJob = managerScope.launch(Dispatchers.IO) {
            _connectionState.value = ConnectionState.CONNECTING
            try {
                Log.d(TAG, "Connecting to remote server at $host:$REMOTE_CONTROL_PORT...")
                httpClient.webSocket(
                    host = host,
                    port = REMOTE_CONTROL_PORT,
                    path = "/remote"
                ) {
                    _connectionState.value = ConnectionState.CONNECTED
                    clientSession = this
                    Log.i(TAG, "Successfully connected to remote server.")

                    // Start listening for incoming messages
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            handleServerMessage(text)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Connection failed: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                }
            } finally {
                Log.d(TAG, "WebSocket session ended.")
                if (_connectionState.value != ConnectionState.DISCONNECTED) {
                    // This block runs on disconnect, error, or cancellation
                    disconnect()
                }
            }
        }
    }

    /**
     * Disconnects from the server and resets the state.
     */
    fun disconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return

        Log.d(TAG, "Disconnecting from remote server...")
        connectJob?.cancel()
        connectJob = null

        managerScope.launch {
            clientSession?.close()
            clientSession = null
        }

        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Shuts down the client, closing the HttpClient.
     * This client instance cannot be reused after this.
     */
    fun release() {
        disconnect()
        httpClient.close()
        Log.d(TAG, "Remote client released.")
    }

    /**
     * Handles incoming JSON text from the server, deserializes it,
     * and emits it on the event flow.
     */
    private suspend fun handleServerMessage(text: String) {
        try {
            Log.d(TAG, "Received message: $text")
            val message = json.decodeFromString<RemoteMessage>(text)
            _eventFlow.emit(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize server message: $text", e)
        }
    }

    /**
     * A thread-safe, private helper to send any [RemoteCommand] to the server.
     */
    private fun send(command: RemoteCommand) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send command, not connected.")
            return
        }

        managerScope.launch {
            clientMutex.withLock {
                try {
                    val jsonString = json.encodeToString(RemoteCommand.serializer(), command)
                    clientSession?.send(Frame.Text(jsonString))
                    Log.d(TAG, "Sent command: $jsonString")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send command: ${e.message}", e)
                    // Trigger a disconnect/error state
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

    // --- Public API for Sending Commands ---

    /**
     * Asks the server for its current video info (duration, position, play state).
     * The server will respond with a [RemoteMessage.GetVideoInfoResponse].
     */
    fun getVideoInfo() {
        send(RemoteCommand.GetVideoInfoRequest)
    }

    /**
     * Asks the server for its current video duration.
     * The server will respond with a [RemoteMessage.GetVideoDurationResponse].
     */
    fun getVideoDuration() {
        send(RemoteCommand.GetVideoDurationRequest)
    }

    /**
     * Tells the server to execute a 'PLAY' command.
     */
    fun sendPlay() {
        send(RemoteCommand.VideoPlayRequest)
    }

    /**
     * Tells the server to execute a 'PAUSE' command.
     */
    fun sendPause() {
        send(RemoteCommand.VideoPauseRequest)
    }

    /**
     * Tells the server to execute a 'SEEK' command to a specific position.
     *
     * @param positionMs The target position in milliseconds.
     */
    fun sendSeek(positionMs: Long) {
        send(RemoteCommand.VideoSeekRequest(positionMs))
    }
}