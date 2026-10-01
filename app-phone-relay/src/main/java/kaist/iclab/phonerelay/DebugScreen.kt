package kaist.iclab.phonerelay

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val RATE_WINDOW_MS = 4_000L
private const val STALL_THRESHOLD_MS = 3_000L

/**
 * Live view of the incoming watch stream for development and debugging: numeric
 * latest values, per-sensor rate/age, and scrolling waveforms. Works entirely off
 * the watch -> phone stream — no server needed.
 */
@Composable
fun DebugScreen() {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        DebugFeed.start()
        onDispose {
            // Keep the feed (and its buffers) alive across rotation; the recreated
            // screen's start() is then a no-op instead of clearing the charts.
            if (activity?.isChangingConfigurations != true) DebugFeed.stop()
        }
    }

    val frame by DebugFeed.frame.collectAsState()
    val latest by DebugFeed.latest.collectAsState()
    val lastSeen by DebugFeed.lastSeen.collectAsState()

    // Snapshot ring buffers once per frame tick
    val ppgTraces = remember(frame) {
        listOf("green", "red", "ir").map { it to DebugFeed.channels["ppg.$it"]!!.snapshotValues() }
    }
    val accTraces = remember(frame) {
        listOf("x", "y", "z").map { it to DebugFeed.channels["acc.$it"]!!.snapshotValues() }
    }
    val imuAccTraces = remember(frame) {
        listOf("accX", "accY", "accZ").map { it to DebugFeed.channels["imu.$it"]!!.snapshotValues() }
    }
    val imuGyroTraces = remember(frame) {
        listOf("gyroX", "gyroY", "gyroZ").map { it to DebugFeed.channels["imu.$it"]!!.snapshotValues() }
    }

    val now = System.currentTimeMillis()
    val hasData = lastSeen.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Live data", style = MaterialTheme.typography.headlineSmall)

        if (!hasData) {
            Text(
                "No data yet. Start logging on the watch.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Numeric latest values
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NumericValue(
                label = "HR",
                value = latest.hr?.let { hr ->
                    buildString {
                        append("$hr bpm")
                        latest.lastIbi?.let { append(" · ibi $it") }
                    }
                }
            )
            NumericValue(
                label = "Skin temp",
                value = if (latest.objTemp != null) {
                    "%.1f / %.1f °C".format(latest.objTemp, latest.ambTemp ?: Float.NaN)
                } else null
            )
            NumericValue(
                label = "EDA",
                value = latest.eda?.let { "%.3f µS".format(it) }
            )
        }

        HorizontalDivider()

        // Per-sensor rate and last-sample age
        lastSeen.toSortedMap().forEach { (sensorId, seenAt) ->
            val ageMs = now - seenAt
            val hz = sensorRateHz(sensorId, now)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    sensorId,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(0.4f)
                )
                Text(
                    text = buildString {
                        if (hz != null) append("%.1f Hz · ".format(hz))
                        append("%.1f s ago".format(ageMs / 1000f))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ageMs > STALL_THRESHOLD_MS) Color(0xFFD32F2F)
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        HorizontalDivider()

        WaveformChart("PPG", ppgTraces)
        WaveformChart("Accelerometer (m/s²)", accTraces)
        WaveformChart("IMU accel", imuAccTraces)
        WaveformChart("IMU gyro", imuGyroTraces)
    }
}

@Composable
private fun NumericValue(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value ?: "—", style = MaterialTheme.typography.titleMedium)
    }
}

/** Sample rate over the recent window, from the sensor's first waveform channel. */
private fun sensorRateHz(sensorId: String, now: Long): Float? {
    val channelKey = when (sensorId) {
        "PPG" -> "ppg.green"
        "Accelerometer" -> "acc.x"
        "IMU" -> "imu.accX"
        else -> return null // HR/temp/EDA are low-rate; age alone is enough
    }
    val count = DebugFeed.channels[channelKey]?.countSince(now - RATE_WINDOW_MS) ?: return null
    return count / (RATE_WINDOW_MS / 1000f)
}
