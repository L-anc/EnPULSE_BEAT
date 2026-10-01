package kaist.iclab.phonerelay

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies LocalRecorder's sessions to the public Downloads collection as
 * Download/EnPULSE/<session>/<sensorId>.csv, where they can be pulled over USB or
 * opened from any file manager. Uses MediaStore, so no storage permission is needed.
 * Re-exporting replaces this app's earlier copies of the same files.
 */
object CsvExporter {
    private const val TAG = "CsvExporter"
    const val EXPORT_FOLDER = "EnPULSE"

    sealed interface State {
        data object Idle : State
        data object Running : State
        data class Done(val files: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Process-wide so rotating the screen doesn't abort an export midway
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (_state.value == State.Running) return
        val appContext = context.applicationContext
        _state.value = State.Running
        scope.launch {
            _state.value = try {
                State.Done(exportAll(appContext))
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun exportAll(context: Context): Int {
        // Lengths of files still being written, so a half-written row is never copied
        val activeLengths = LocalRecorder.flushedLengths()
        val sessions = LocalRecorder.recordingsDir(context).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: return 0

        var count = 0
        sessions.forEach { session ->
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER/${session.name}/"
            session.listFiles { f -> f.extension == "csv" }?.forEach { csv ->
                val length = activeLengths[csv.absolutePath] ?: csv.length()
                if (length > 0) {
                    copyToDownloads(context.contentResolver, csv, length, relativePath)
                    count++
                }
            }
        }
        return count
    }

    private fun copyToDownloads(
        resolver: ContentResolver,
        source: File,
        length: Long,
        relativePath: String
    ) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        removePreviousExport(resolver, collection, relativePath, source.name)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create ${source.name} in Downloads")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open ${source.name} for writing")
            out.use { source.inputStream().use { input -> copyBytes(input, it, length) } }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun removePreviousExport(
        resolver: ContentResolver,
        collection: Uri,
        relativePath: String,
        name: String
    ) {
        try {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(relativePath, name)
            )
        } catch (e: SecurityException) {
            // Written by a previous install, so not ours to delete: MediaStore gives the
            // new copy a numbered name instead
            Log.w(TAG, "Could not replace earlier export of $name: ${e.message}")
        }
    }

    private fun copyBytes(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }
}
