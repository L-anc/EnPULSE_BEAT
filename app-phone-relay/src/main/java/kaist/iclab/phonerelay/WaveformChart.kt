package kaist.iclab.phonerelay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val TRACE_COLORS = listOf(
    Color(0xFF4CAF50), // green
    Color(0xFFF44336), // red
    Color(0xFF2196F3), // blue
)

/**
 * Minimal scrolling-waveform chart for the debug screen: one autoscaled polyline
 * per trace, drawn with Canvas (no chart library). Traces are snapshots of ring
 * buffers, oldest to newest.
 */
@Composable
fun WaveformChart(
    title: String,
    traces: List<Pair<String, FloatArray>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            traces.forEachIndexed { i, (label, values) ->
                val lastValue = values.lastOrNull()
                Text(
                    text = if (lastValue != null) "$label %.2f".format(lastValue) else label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TRACE_COLORS[i % TRACE_COLORS.size]
                )
            }
        }

        // Shared autoscale across all traces of this chart
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        traces.forEach { (_, values) ->
            values.forEach { v ->
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        if (min > max) {
            min = 0f
            max = 1f
        } else if (max - min < 1e-6f) {
            min -= 1f
            max += 1f
        }
        val range = max - min

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 4.dp)
                .background(Color(0x11000000), RoundedCornerShape(4.dp))
        ) {
            traces.forEachIndexed { i, (_, values) ->
                if (values.size < 2) return@forEachIndexed
                val path = Path()
                val stepX = size.width / (values.size - 1)
                values.forEachIndexed { j, v ->
                    val x = j * stepX
                    val y = size.height * (1f - (v - min) / range)
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = TRACE_COLORS[i % TRACE_COLORS.size],
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}
