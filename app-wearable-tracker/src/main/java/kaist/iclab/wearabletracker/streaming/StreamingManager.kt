package kaist.iclab.wearabletracker.streaming

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kaist.iclab.tracker.sensor.core.Sensor
import kaist.iclab.tracker.sensor.core.SensorEntity
import kaist.iclab.wearabletracker.Constants.Streaming.BUFFER_CAPACITY
import kaist.iclab.wearabletracker.Constants.Streaming.CHANNEL_PATH
import kaist.iclab.wearabletracker.Constants.Streaming.FLUSH_INTERVAL_MS
import kaist.iclab.wearabletracker.Constants.Streaming.FLUSH_LINE_COUNT
import kaist.iclab.wearabletracker.Constants.Streaming.RECONNECT_BACKOFF_MAX_MS
import kaist.iclab.wearabletracker.Constants.Streaming.RECONNECT_BACKOFF_MIN_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Streams every sensor entity to the paired phone in real time over the Wearable
 * ChannelClient (one persistent socket, ordered delivery, JSON line per entity).
 *
 * Runs alongside the Room buffering in SensorDataReceiverService: this is the live
 * fire-and-forget path, Room is the safety net for connection gaps. Lines produced
 * while the phone is unreachable are dropped here (bounded buffer, DROP_OLDEST) and
 * recovered later via CSV export.
 */
class StreamingManager(
    context: Context,
    private val sensors: List<Sensor<*, *>>,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "StreamingManager"
    }

    enum class StreamingState { DISCONNECTED, CONNECTING, STREAMING, RECONNECTING }

    private val nodeClient = Wearable.getNodeClient(context)
    private val channelClient = Wearable.getChannelClient(context)

    private val _streamingState = MutableStateFlow(StreamingState.DISCONNECTED)
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val _linesSent = MutableStateFlow(0L)
    val linesSent: StateFlow<Long> = _linesSent.asStateFlow()

    private val eventChannel = Channel<Pair<String, SensorEntity>>(
        capacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val listener: Map<String, (SensorEntity) -> Unit> = sensors.associate { sensor ->
        sensor.id to { e: SensorEntity ->
            eventChannel.trySend(sensor.id to e)
            Unit
        }
    }

    private var streamJob: Job? = null
    private var listenersRegistered = false

    @Synchronized
    fun start() {
        if (!listenersRegistered) {
            listenersRegistered = true
            sensors.forEach { it.addListener(listener[it.id]!!) }
        }
        if (streamJob?.isActive != true) {
            streamJob = coroutineScope.launch { runStreamLoop() }
        }
    }

    @Synchronized
    fun stop() {
        if (listenersRegistered) {
            sensors.forEach { it.removeListener(listener[it.id]!!) }
            listenersRegistered = false
        }
        streamJob?.cancel()
        streamJob = null
        // Drain anything left so stale data is not sent on the next session
        while (eventChannel.tryReceive().isSuccess) Unit
        _streamingState.value = StreamingState.DISCONNECTED
    }

    private suspend fun runStreamLoop() {
        var backoffMs = RECONNECT_BACKOFF_MIN_MS
        var everConnected = false
        try {
            while (coroutineScope.isActive) {
                _streamingState.value =
                    if (everConnected) StreamingState.RECONNECTING else StreamingState.CONNECTING
                try {
                    val nodes = nodeClient.connectedNodes.await()
                    val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                    if (node == null) {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                        continue
                    }

                    val channel = channelClient.openChannel(node.id, CHANNEL_PATH).await()
                    try {
                        val output = channelClient.getOutputStream(channel).await()
                        BufferedOutputStream(output).use { out ->
                            _streamingState.value = StreamingState.STREAMING
                            everConnected = true
                            backoffMs = RECONNECT_BACKOFF_MIN_MS
                            writeLoop(out)
                        }
                    } finally {
                        runCatching { channelClient.close(channel) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stream connection lost or failed: ${e.message}")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                }
            }
        } finally {
            _streamingState.value = StreamingState.DISCONNECTED
        }
    }

    /**
     * Pulls entities off the buffer, writes one JSON line each, and flushes every
     * FLUSH_INTERVAL_MS or FLUSH_LINE_COUNT lines (whichever comes first) to bound
     * latency while keeping radio wakeups batched. Throws IOException on channel
     * loss, which triggers reconnection in runStreamLoop.
     */
    private suspend fun writeLoop(out: OutputStream) {
        var pendingLines = 0
        var lastFlush = System.currentTimeMillis()

        while (true) {
            val event = if (pendingLines == 0) {
                eventChannel.receive()
            } else {
                val waitMs = FLUSH_INTERVAL_MS - (System.currentTimeMillis() - lastFlush)
                withTimeoutOrNull(waitMs.coerceAtLeast(0)) { eventChannel.receive() }
            }

            if (event != null) {
                val line = StreamProtocol.encode(event.first, event.second) ?: continue
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                pendingLines++
                if (pendingLines >= FLUSH_LINE_COUNT) {
                    out.flush()
                    _linesSent.value += pendingLines
                    pendingLines = 0
                    lastFlush = System.currentTimeMillis()
                }
            } else {
                // Flush interval elapsed with lines pending
                out.flush()
                _linesSent.value += pendingLines
                pendingLines = 0
                lastFlush = System.currentTimeMillis()
            }
        }
    }
}
