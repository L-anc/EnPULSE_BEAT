package kaist.iclab.phonerelay

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Process-wide hub between the watch channel reader (WatchChannelService) and the
 * WebSocket relay (RelayService), plus observable state for the UI.
 *
 * Lines flow through verbatim: the phone never re-encodes what the watch sent.
 * Parsing happens only lazily, per line, to update the live-values display.
 */
object RelayHub {
    private val _lines = MutableSharedFlow<String>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Raw JSON lines from the watch, relayed as-is to the server. */
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _linesReceived = MutableStateFlow(0L)
    val linesReceived: StateFlow<Long> = _linesReceived.asStateFlow()

    /** Latest raw entity JSON per sensor id, for the live display. */
    private val _latestBySensor = MutableStateFlow<Map<String, String>>(emptyMap())
    val latestBySensor: StateFlow<Map<String, String>> = _latestBySensor.asStateFlow()

    fun onWatchConnected(connected: Boolean) {
        _watchConnected.value = connected
    }

    fun publish(line: String) {
        _lines.tryEmit(line)
        _linesReceived.value++
        updateLatest(line)
    }

    private fun updateLatest(line: String) {
        try {
            val obj = Json.parseToJsonElement(line).jsonObject
            val sensorId = obj["s"]?.jsonPrimitive?.content ?: return
            val data = obj["d"]?.toString() ?: return
            _latestBySensor.value = _latestBySensor.value + (sensorId to data)
        } catch (_: Exception) {
            // Malformed line: still relayed, just not displayed
        }
    }
}
