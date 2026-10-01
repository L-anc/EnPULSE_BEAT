package kaist.iclab.phonerelay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the incoming watch stream to per-sensor CSV files in app-private storage
 * (files/recordings/<session>/<sensorId>.csv) for later export by CsvExporter.
 *
 * A session starts with the first line after a gap of more than SESSION_GAP_MS, so
 * brief stream reconnects keep appending to the same files.
 *
 * Columns: phoneReceived (phone clock, ms), then the entity's fields in wire order.
 * List fields (HR ibi/ibiStatus) are joined with ';' like the watch's own CSV export.
 */
object LocalRecorder {
    private const val TAG = "LocalRecorder"
    private const val RECORDINGS_DIR = "recordings"
    private const val SESSION_GAP_MS = 60_000L
    private const val FLUSH_INTERVAL_MS = 1_000L
    private const val PHONE_RECEIVED = "phoneReceived"

    data class Status(
        val sessionId: String? = null,
        val sessionRows: Long = 0,
        val storedSessions: Int = 0,
        val storedBytes: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private class SensorFile(
        val file: File,
        val writer: BufferedWriter,
        val fields: List<String>, // entity fields, after the phoneReceived column
    )

    private var sessionDir: File? = null
    private val openFiles = mutableMapOf<String, SensorFile>()
    private var lastLineAt = 0L
    private var lastFlushAt = 0L

    fun recordingsDir(context: Context): File = File(context.filesDir, RECORDINGS_DIR)

    /** Append one stream line. Called on the channel reader thread. */
    @Synchronized
    fun record(context: Context, line: String) {
        val now = System.currentTimeMillis()
        try {
            val obj = Json.parseToJsonElement(line).jsonObject
            val sensorId = obj["s"]?.jsonPrimitive?.content ?: return
            val data = obj["d"]?.jsonObject ?: return
            // Batched entities carry a dataPoint list; IMU is a single flat sample
            val rows = data["dataPoint"]?.jsonArray?.map { it.jsonObject } ?: listOf(data)
            if (rows.isEmpty()) return

            if (sessionDir == null || now - lastLineAt > SESSION_GAP_MS) startSession(context, now)
            lastLineAt = now

            val sensorFile = openFiles.getOrPut(sensorId) { openSensorFile(sensorId, rows.first()) }
            rows.forEach { row ->
                val writer = sensorFile.writer
                writer.append(now.toString())
                sensorFile.fields.forEach { field -> writer.append(',').append(csvValue(row[field])) }
                writer.append('\n')
            }
            _status.update { it.copy(sessionRows = it.sessionRows + rows.size) }

            if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                openFiles.values.forEach { it.writer.flush() }
                lastFlushAt = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not record line: ${e.message}")
        }
    }

    /**
     * Close the open files when the stream ends. The session stays current, so a
     * reconnect within SESSION_GAP_MS reopens and appends to the same files.
     */
    @Synchronized
    fun closeFiles() {
        openFiles.values.forEach { runCatching { it.writer.close() } }
        openFiles.clear()
    }

    /**
     * Flush the files still being written and return their lengths (by absolute
     * path), so an export copies only complete rows.
     */
    @Synchronized
    fun flushedLengths(): Map<String, Long> {
        openFiles.values.forEach { it.writer.flush() }
        lastFlushAt = System.currentTimeMillis()
        return openFiles.values.associate { it.file.absolutePath to it.file.length() }
    }

    /** Delete every recorded session, including the current one. */
    @Synchronized
    fun deleteAll(context: Context) {
        closeFiles()
        sessionDir = null
        recordingsDir(context).deleteRecursively()
        _status.value = Status()
    }

    /** Recompute the stored-session summary shown in the UI. */
    fun refreshStored(context: Context) {
        val sessions = recordingsDir(context).listFiles()?.filter { it.isDirectory } ?: emptyList()
        val bytes = sessions.sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }
        _status.update { it.copy(storedSessions = sessions.size, storedBytes = bytes) }
    }

    private fun startSession(context: Context, now: Long) {
        closeFiles()
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        sessionDir = File(recordingsDir(context), sessionId).apply { mkdirs() }
        _status.update { it.copy(sessionId = sessionId, sessionRows = 0) }
        Log.i(TAG, "Recording session $sessionId")
    }

    private fun openSensorFile(sensorId: String, firstRow: JsonObject): SensorFile {
        val safeName = sensorId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(sessionDir, "$safeName.csv")
        // Resuming a session after a reconnect: keep the existing header's column order
        val existingHeader = if (file.length() > 0) file.bufferedReader().use { it.readLine() } else null
        val fields = existingHeader?.split(',')?.drop(1) ?: firstRow.keys.toList()
        val writer = BufferedWriter(FileWriter(file, true))
        if (existingHeader == null) {
            writer.append((listOf(PHONE_RECEIVED) + fields).joinToString(",")).append('\n')
        }
        return SensorFile(file, writer, fields)
    }

    private fun csvValue(element: JsonElement?): String {
        val raw = when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString(";") { (it as? JsonPrimitive)?.content ?: it.toString() }
            is JsonObject -> element.toString()
        }
        return if (raw.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + raw.replace("\"", "\"\"") + "\""
        } else raw
    }
}
