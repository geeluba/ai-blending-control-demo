package com.example.remotecontrolprojector.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Connects to the [RemoteControlServer] via WebSockets to send playback
 * commands and receive state updates.
 *
 * This client is designed to be robust, automatically retrying connections
 * if they are lost.
 */
class RemoteControlClient(private val managerScope: CoroutineScope) {

    private val TAG = "Projector:RemoteClient"
    private val REMOTE_CONTROL_PORT = 9877
    private val CONNECTION_RETRY_DELAY_MS = 5000L

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR // Error is a temporary state before retrying
    }

    // --- (Internal State) ---
    private var connectJob: Job? = null
    private var host: String? = null

    @Volatile
    private var clientSession: WebSocketSession? = null
    private val clientMutex = Mutex()

    // --- (State & Event Flows) ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    val isConnected: StateFlow<Boolean> = _connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(managerScope, SharingStarted.Eagerly, false)

    /**
     * Emits [RemoteMessage] objects received from the server.
     * These are responses to requests or unsolicited notifications.
     */
    private val _eventFlow = MutableSharedFlow<RemoteMessage>()
    val eventFlow = _eventFlow.asSharedFlow()

    // --- (Ktor & Serialization Setup) ---

    private val httpClient = HttpClient(CIO) {
        //HttpClientConfig.install(WebSockets.Plugin)
        install(WebSockets) {
            pingInterval = 20_000 // Optional: Ping every 20 seconds to keep connection alive
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "commandType"
    }

    /**
     * Used to generate unique, incrementing IDs for each request.
     * This is thread-safe.
     */
    private val requestIdCounter = AtomicLong(0)

    /**
     * Gets the next unique request ID as a string.
     */
    private fun getNextRequestId(): String = requestIdCounter.incrementAndGet().toString()

    /**
     * Starts the client's connection loop to connect to the remote server.
     * The client will automatically handle disconnects and retry.
     *
     * @param host The IP address of the device running the [RemoteControlServer].
     */
    fun connect(host: String) {
        if (connectJob?.isActive == true) {
            Log.d(TAG, "Connection loop is already running.")
            return
        }
        this.host = host
        startConnectLoop()
    }

    /**
     * Starts the main auto-retry connection loop.
     */
    private fun startConnectLoop() {
        connectJob?.cancel() // Cancel any previous loop
        connectJob = managerScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // 1. Set state to CONNECTING
                    _connectionState.value = ConnectionState.CONNECTING
                    val currentHost = host ?: throw IllegalStateException("Host is not set")
                    Log.d(
                        TAG,
                        "Connecting to remote server at $currentHost:$REMOTE_CONTROL_PORT..."
                    )

                    // 2. Attempt to connect and listen
                    httpClient.webSocket(
                        host = currentHost,
                        port = REMOTE_CONTROL_PORT,
                        path = "/remote"
                    ) {
                        // --- Connected ---
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.i(TAG, "Successfully connected to remote server.")

                        // Store session safely
                        clientMutex.withLock { clientSession = this }

                        // Start listening for incoming messages
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleServerMessage(text)
                            }
                        }
                    }

                } catch (e: Exception) {
                    if (e is CancellationException) {
                        Log.i(TAG, "Connection loop cancelled.")
                        throw e // Re-throw to stop the loop
                    }
                    if (isActive) {
                        Log.e(TAG, "Connection failed: ${e.message}")
                        _connectionState.value = ConnectionState.ERROR
                    }
                } finally {
                    // --- Disconnected ---
                    Log.d(TAG, "WebSocket session ended.")

                    // Clear session safely
                    clientMutex.withLock { clientSession = null }

                    // If the loop is still active (not manually disconnected),
                    // wait and then retry.
                    if (isActive && _connectionState.value != ConnectionState.DISCONNECTED) {
                        Log.i(TAG, "Retrying in $CONNECTION_RETRY_DELAY_MS ms...")
                        delay(CONNECTION_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }


    /**
     * Disconnects from the server, sets the state, and stops the retry loop.
     */
    fun disconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return

        Log.d(TAG, "Disconnecting from remote server...")
        _connectionState.value = ConnectionState.DISCONNECTED

        // Cancel the main connection loop
        connectJob?.cancel()
        connectJob = null

        // Force-close the current session
        managerScope.launch {
            clientMutex.withLock {
                clientSession?.close()
                clientSession = null
            }
        }
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
        // Don't check state. Just try to send.
        // If the session is null, the lock will handle it.
        managerScope.launch {
            clientMutex.withLock {
                if (clientSession == null) {
                    Log.w(TAG, "Cannot send command, session is null.")
                    return@launch
                }

                try {
                    val jsonString = json.encodeToString(RemoteCommand.serializer(), command)
                    clientSession?.send(Frame.Text(jsonString))
                    Log.d(TAG, "Sent command: $jsonString")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send command: ${e.message}", e)
                    // On send error, close the session.
                    // This will trigger the `finally` block in connectLoop
                    // and start a reconnect.
                    clientSession?.close(
                        CloseReason(
                            CloseReason.Codes.INTERNAL_ERROR,
                            "Send failed"
                        )
                    )
                    clientSession = null
                }
            }
        }
    }

    // --- Public API for Sending Commands (Unchanged) ---
    fun sendDirectConnectRequest(targetMacAddress: String) {
        val command = RemoteCommand.ConnectToMacRequest(
            requestId = getNextRequestId(),
            targetMacAddress = targetMacAddress
        )
        send(command)
    }

    fun sendDiscoveryAndConnect(targetDeviceName: String) {
        val command = RemoteCommand.StartDiscoveryRequest(
            requestId = getNextRequestId(),
            targetDeviceName = targetDeviceName
        )
        send(command)
    }

    fun sendBlendingMode(mode: RemoteCommand.BlendingMode, isController: Boolean = true) {
        val command = RemoteCommand.BlendingModeRequest(
            requestId = getNextRequestId(),
            mode = mode,
            isController = isController
        )
        send(command)
    }

    fun getImageInfo() {
        val command = RemoteCommand.GetImageInfoRequest(requestId = getNextRequestId())
        send(command)
    }

    fun getVideoInfo() {
        val command = RemoteCommand.GetVideoInfoRequest(requestId = getNextRequestId())
        send(command)
    }

    fun getVideoDuration() {
        val command = RemoteCommand.GetVideoDurationRequest(requestId = getNextRequestId())
        send(command)
    }

    fun sendPlay() {
        val command = RemoteCommand.VideoPlayRequest(requestId = getNextRequestId())
        send(command)
    }

    fun sendPause() {
        val command = RemoteCommand.VideoPauseRequest(requestId = getNextRequestId())
        send(command)
    }

    fun sendSeek(positionMs: Long) {
        val command = RemoteCommand.VideoSeekRequest(
            requestId = getNextRequestId(),
            positionMs = positionMs
        )
        send(command)
    }

    fun startSlideshow() {
        val command = RemoteCommand.ImagePlayRequest(requestId = getNextRequestId())
        send(command)
    }

    fun pauseSlideshow() {
        val command = RemoteCommand.ImagePauseRequest(requestId = getNextRequestId())
        send(command)
    }
}