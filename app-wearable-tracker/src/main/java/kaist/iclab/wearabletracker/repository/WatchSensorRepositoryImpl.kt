package kaist.iclab.wearabletracker.repository

import kaist.iclab.wearabletracker.db.dao.BaseDao

/**
 * Implementation of WatchSensorRepository.
 * Handles data operations using DAOs.
 */
class WatchSensorRepositoryImpl(
    private val sensorDataStorages: Map<String, BaseDao<*>>
) : WatchSensorRepository {
    private val TAG = WatchSensorRepositoryImpl::class.simpleName ?: "WatchSensorRepositoryImpl"

    override suspend fun deleteAllSensorData(): Result<Unit> =
        ErrorClassifier.runClassified(TAG, "delete all sensor data") {
            sensorDataStorages.values.forEach { it.deleteAll() }
        }

    override suspend fun getTotalRecordCount(): Int {
        return sensorDataStorages.values.sumOf { it.getCount() }
    }

    override suspend fun getRecordCountSince(timestamp: Long): Int {
        return sensorDataStorages.values.sumOf { it.getCountSince(timestamp) }
    }
}
