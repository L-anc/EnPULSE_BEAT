package kaist.iclab.phonerelay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket connection to the lab server with automatic reconnection.
 * While disconnected, sent lines are dropped — the watch's Room buffer plus
 * CSV export is the recovery path for gaps.
 */
class WsClient(private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "WsClient"
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 15_000L
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var shouldRun = false
    private var url: String = ""
    private var reconnectJob: Job? = null
    private var backoffMs = BACKOFF_MIN_MS

    @Synchronized
    fun start(url: String) {
        this.url = url
        shouldRun = true
        backoffMs = BACKOFF_MIN_MS
        // Restart-safe: drop any existing socket/reconnect before opening a new one
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "restart")
        webSocket = null
        connect()
    }

    @Synchronized
    fun stop() {
        shouldRun = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "relay stopped")
        webSocket = null
        _state.value = State.DISCONNECTED
    }

    /** Send one line as a text frame; drops silently while disconnected. */
    fun send(line: String): Boolean {
        return webSocket?.send(line) ?: false
    }

    private fun connect() {
        if (!shouldRun) return
        _state.value = State.CONNECTING
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid server URL: $url")
            _state.value = State.DISCONNECTED
            return
        }
        webSocket = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        _state.value = State.CONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = BACKOFF_MIN_MS
            _state.value = State.CONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Ignore events from a socket that has already been replaced
            if (webSocket !== this@WsClient.webSocket) return
            Log.w(TAG, "WebSocket failure: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== this@WsClient.webSocket) return
            if (shouldRun) scheduleReconnect() else _state.value = State.DISCONNECTED
        }
    }
}
