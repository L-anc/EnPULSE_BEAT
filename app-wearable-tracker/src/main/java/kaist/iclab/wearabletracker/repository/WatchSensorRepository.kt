package kaist.iclab.wearabletracker.repository

/**
 * Repository interface for watch sensor data operations.
 * Abstracts data access from the ViewModel layer.
 */
interface WatchSensorRepository {
    /**
     * Delete all sensor data from local storage.
     */
    suspend fun deleteAllSensorData(): Result<Unit>

    /**
     * Get the total number of records across all sensors since the given timestamp.
     */
    suspend fun getRecordCountSince(timestamp: Long): Int

    /**
     * Get the total number of records across all sensors.
     */
    suspend fun getTotalRecordCount(): Int
}
