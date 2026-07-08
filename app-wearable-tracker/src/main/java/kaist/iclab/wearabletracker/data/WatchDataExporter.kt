package kaist.iclab.wearabletracker.data

import android.content.Context
import kaist.iclab.wearabletracker.db.dao.BaseDao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports all locally buffered sensor data to CSV files in the app's external files
 * directory, so it can be retrieved with:
 * adb pull /sdcard/Android/data/<applicationId>/files/exports
 *
 * This is the recovery path for data collected while the live stream was disconnected.
 */
class WatchDataExporter(
    private val context: Context,
    private val sensorDataStorages: Map<String, BaseDao<*>>
) {
    /**
     * Write one CSV file per sensor that has data.
     * @return the number of files written
     */
    suspend fun exportAll(): Int {
        val exportDir = File(context.getExternalFilesDir(null), "exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var fileCount = 0

        sensorDataStorages.forEach { (sensorId, dao) ->
            val rows = dao.getAllForExport()
            if (rows.isEmpty()) return@forEach

            val file = File(exportDir, "${sensorId}_$timestamp.csv")
            file.bufferedWriter().use { writer ->
                writer.appendLine(rows.first().toCsvHeader())
                rows.forEach { writer.appendLine(it.toCsvRow()) }
            }
            fileCount++
        }
        return fileCount
    }
}
