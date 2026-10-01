package kaist.iclab.phonerelay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the raw stream lines into per-channel ring buffers and latest values for
 * the debug screen. Started/stopped with the screen's visibility, so it costs
 * nothing while the debug tab is closed (RelayHub.lines has no replay — buffers
 * simply start empty on open).
 */
object DebugFeed {
    private const val TAG = "DebugFeed"
    private const val CAPACITY = 512
    private const val FRAME_INTERVAL_MS = 80L // ~12 fps UI refresh

    /**
     * Fixed-capacity ring of (arrivalTime, value). Writers are sensor-rate (<= 50 Hz),
     * readers snapshot ~12x/s; synchronized access is negligible at these rates.
     */
    class RingBuffer(private val capacity: Int = CAPACITY) {
        private val values = FloatArray(capacity)
        private val times = LongArray(capacity)
        private var head = 0 // next write index
        private var size = 0

        @Synchronized
        fun add(timeMs: Long, value: Float) {
            values[head] = value
            times[head] = timeMs
            head = (head + 1) % capacity
            if (size < capacity) size++
        }

        /** Copy of the buffered values, oldest to newest. */
        @Synchronized
        fun snapshotValues(): FloatArray {
            val out = FloatArray(size)
            val start = (head - size + capacity) % capacity
            for (i in 0 until size) {
                out[i] = values[(start + i) % capacity]
            }
            return out
        }

        @Synchronized
        fun countSince(cutoffMs: Long): Int {
            var count = 0
            val start = (head - size + capacity) % capacity
            for (i in 0 until size) {
                if (times[(start + i) % capacity] >= cutoffMs) count++
            }
            return count
        }

        @Synchronized
        fun clear() {
            head = 0
            size = 0
        }
    }

    // Channel keys must match what parse() writes — keep both in this file.
    val channels: Map<String, RingBuffer> = listOf(
        "ppg.green", "ppg.red", "ppg.ir",
        "acc.x", "acc.y", "acc.z",
        "imu.accX", "imu.accY", "imu.accZ",
        "imu.gyroX", "imu.gyroY", "imu.gyroZ",
    ).associateWith { RingBuffer() }

    data class Latest(
        val hr: Int? = null,
        val hrStatus: Int? = null,
        val lastIbi: Int? = null,
        val objTemp: Float? = null,
        val ambTemp: Float? = null,
        val eda: Float? = null,
    )

    private val _latest = MutableStateFlow(Latest())
    val latest: StateFlow<Latest> = _latest.asStateFlow()

    /** sensorId -> last arrival time (phone clock, ms) */
    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen.asStateFlow()

    /** Monotonic tick that drives chart recomposition. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame.asStateFlow()

    private var scope: CoroutineScope? = null

    @Synchronized
    fun start() {
        if (scope?.isActive == true) return
        Log.d(TAG, "start")
        channels.values.forEach { it.clear() }
        _latest.value = Latest()
        _lastSeen.value = emptyMap()

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        newScope.launch {
            RelayHub.lines.collect { line ->
                try {
                    parse(line)
                } catch (e: Exception) {
                    // Malformed line: ignore for display purposes
                }
            }
        }
        newScope.launch {
            while (isActive) {
                _frame.value++
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "stop")
        scope?.cancel()
        scope = null
    }

    private fun parse(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        val sensorId = obj["s"]?.jsonPrimitive?.content ?: return
        val data = obj["d"]?.jsonObject ?: return
        val now = System.currentTimeMillis()

        _lastSeen.value = _lastSeen.value + (sensorId to now)

        when (sensorId) {
            "PPG" -> forEachDataPoint(data) { p ->
                channels["ppg.green"]!!.add(now, p["green"]!!.jsonPrimitive.float)
                channels["ppg.red"]!!.add(now, p["red"]!!.jsonPrimitive.float)
                channels["ppg.ir"]!!.add(now, p["ir"]!!.jsonPrimitive.float)
            }

            "Accelerometer" -> forEachDataPoint(data) { p ->
                channels["acc.x"]!!.add(now, p["x"]!!.jsonPrimitive.float)
                channels["acc.y"]!!.add(now, p["y"]!!.jsonPrimitive.float)
                channels["acc.z"]!!.add(now, p["z"]!!.jsonPrimitive.float)
            }

            "IMU" -> {
                channels["imu.accX"]!!.add(now, data["accX"]!!.jsonPrimitive.float)
                channels["imu.accY"]!!.add(now, data["accY"]!!.jsonPrimitive.float)
                channels["imu.accZ"]!!.add(now, data["accZ"]!!.jsonPrimitive.float)
                channels["imu.gyroX"]!!.add(now, data["gyroX"]!!.jsonPrimitive.float)
                channels["imu.gyroY"]!!.add(now, data["gyroY"]!!.jsonPrimitive.float)
                channels["imu.gyroZ"]!!.add(now, data["gyroZ"]!!.jsonPrimitive.float)
            }

            "HeartRate" -> lastDataPoint(data)?.let { p ->
                _latest.value = _latest.value.copy(
                    hr = p["hr"]?.jsonPrimitive?.int,
                    hrStatus = p["hrStatus"]?.jsonPrimitive?.int,
                    lastIbi = p["ibi"]?.jsonArray?.lastOrNull()?.jsonPrimitive?.int
                        ?: _latest.value.lastIbi
                )
            }

            "SkinTemperature" -> lastDataPoint(data)?.let { p ->
                _latest.value = _latest.value.copy(
                    objTemp = p["objectTemperature"]?.jsonPrimitive?.float,
                    ambTemp = p["ambientTemperature"]?.jsonPrimitive?.float
                )
            }

            "EDA" -> lastDataPoint(data)?.let { p ->
                _latest.value = _latest.value.copy(
                    eda = p["skinConductance"]?.jsonPrimitive?.float
                )
            }
        }
    }

    private inline fun forEachDataPoint(data: JsonObject, block: (JsonObject) -> Unit) {
        data["dataPoint"]?.jsonArray?.forEach { block(it.jsonObject) }
    }

    private fun lastDataPoint(data: JsonObject): JsonObject? =
        data["dataPoint"]?.jsonArray?.lastOrNull()?.jsonObject
}
