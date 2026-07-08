package kaist.iclab.wearabletracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kaist.iclab.tracker.listener.SamsungHealthSensorInitializer
import kaist.iclab.tracker.sensor.controller.BackgroundController
import kaist.iclab.tracker.sensor.controller.ControllerState
import kaist.iclab.wearabletracker.data.DeviceInfo
import kaist.iclab.wearabletracker.data.WatchDataExporter
import kaist.iclab.wearabletracker.helpers.NotificationHelper
import kaist.iclab.wearabletracker.repository.Result
import kaist.iclab.wearabletracker.repository.WatchSensorRepository
import kaist.iclab.wearabletracker.storage.SensorDataReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SettingsViewModel(
    private val sensorController: BackgroundController,
    private val sensorDataReceiver: SensorDataReceiver,
    private val repository: WatchSensorRepository,
    private val samsungHealthSensorInitializer: SamsungHealthSensorInitializer,
    private val applicationContext: Context,
    private val watchDataExporter: WatchDataExporter
) : ViewModel() {
    companion object {
        private val TAG = SettingsViewModel::class.simpleName
        private const val RECORD_COUNT_REFRESH_MS = 10_000L // 10 seconds
        private const val BATTERY_REFRESH_MS = 30_000L // 30 seconds
        private const val PHONE_STATUS_REFRESH_MS = 15_000L // 15 seconds
    }

    // Total record count across all sensors
    private val _totalRecordCount = MutableStateFlow(0)
    val totalRecordCount: StateFlow<Int> = _totalRecordCount.asStateFlow()

    // Phone connection status
    private val _isPhoneConnected = MutableStateFlow(false)
    val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected.asStateFlow()

    private val nodeClient by lazy { Wearable.getNodeClient(applicationContext) }

    // Battery level (0-100)
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    // Recording start time (null when not recording)
    private val _recordingStartTime = MutableStateFlow<Long?>(null)
    val recordingStartTime: StateFlow<Long?> = _recordingStartTime.asStateFlow()

    // Samsung Health connection state - Start button should be disabled when false
    val isSamsungHealthConnected: StateFlow<Boolean> =
        samsungHealthSensorInitializer.connectionStateFlow

    // SDK Policy Error state - true when dev mode is not enabled on Health Platform
    val sdkPolicyError: StateFlow<Boolean> = samsungHealthSensorInitializer.sdkPolicyErrorStateFlow

    /**
     * Clear the SDK Policy Error and stop logging (called when user dismisses the error screen).
     */
    fun clearSdkPolicyError() {
        sensorController.stop()
        samsungHealthSensorInitializer.clearSdkPolicyError()
    }

    init {
        viewModelScope.launch {
            sensorController.controllerStateFlow.collect {
                if (it.flag == ControllerState.FLAG.RUNNING) {
                    sensorDataReceiver.startBackgroundCollection()
                    // Track recording start time
                    if (_recordingStartTime.value == null) {
                        _recordingStartTime.value = System.currentTimeMillis()
                    }
                } else {
                    sensorDataReceiver.stopBackgroundCollection()
                    _recordingStartTime.value = null
                }
            }
        }

        // Stop controller immediately when SDK Policy Error is detected
        viewModelScope.launch {
            sdkPolicyError.collect { hasError ->
                if (hasError) {
                    sensorController.stop()
                }
            }
        }

        // Periodic record count refresh
        viewModelScope.launch {
            while (true) {
                refreshRecordCount()
                delay(RECORD_COUNT_REFRESH_MS)
            }
        }

        // Periodic battery level refresh
        viewModelScope.launch {
            while (true) {
                refreshBatteryLevel()
                delay(BATTERY_REFRESH_MS)
            }
        }

        // Periodic phone connection status refresh
        viewModelScope.launch {
            while (true) {
                refreshPhoneConnectionStatus()
                delay(PHONE_STATUS_REFRESH_MS)
            }
        }
    }

    val sensorMap = sensorController.sensors.associateBy { it.name }
    val sensorState = sensorController.sensors.associate { it.name to it.sensorStateFlow }
    val controllerState = sensorController.controllerStateFlow

    fun update(sensorName: String, status: Boolean) {
        val sensor = sensorMap[sensorName] ?: run {
            Log.w(TAG, "Sensor not found: $sensorName")
            return
        }
        if (status) sensor.enable()
        else sensor.disable()
    }

    fun getDeviceInfo(context: Context, callback: (DeviceInfo) -> Unit) {
        Wearable.getNodeClient(context).localNode
            .addOnSuccessListener { localNode ->
                val deviceInfo = DeviceInfo(
                    name = localNode.displayName,
                    id = localNode.id
                )
                callback(deviceInfo)
            }
            .addOnFailureListener { exception ->
                Log.e(
                    TAG,
                    "Error getting device information from getDeviceInfo(): ${exception.message}",
                    exception
                )
                NotificationHelper.showException(
                    context,
                    exception,
                    "Failed to get device information"
                )
            }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun startLogging() {
        sensorController.start()
    }

    fun stopLogging() {
        sensorController.stop()
    }

    fun export(context: Context) {
        viewModelScope.launch {
            try {
                val fileCount = withContext(Dispatchers.IO) {
                    watchDataExporter.exportAll()
                }
                NotificationHelper.showExportSuccess(context, fileCount)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export sensor data: ${e.message}", e)
                NotificationHelper.showExportFailure(context, e, "Failed to export sensor data")
            }
        }
    }

    fun flush(context: Context) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.deleteAllSensorData()
            }

            when (result) {
                is Result.Success -> {
                    NotificationHelper.showFlushSuccess(context)
                    refreshRecordCount()
                }

                is Result.Error -> {
                    NotificationHelper.showFlushFailure(
                        context,
                        result.exception,
                        "Failed to delete sensor data"
                    )
                }
            }
        }
    }

    private suspend fun refreshRecordCount() {
        try {
            val count = withContext(Dispatchers.IO) {
                repository.getTotalRecordCount()
            }
            _totalRecordCount.value = count
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh record count: ${e.message}")
        }
    }

    private fun refreshBatteryLevel() {
        try {
            val batteryStatus =
                applicationContext.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            _batteryLevel.value = if (level >= 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery level: ${e.message}")
        }
    }

    private suspend fun refreshPhoneConnectionStatus() {
        try {
            val nodes = suspendCancellableCoroutine<List<Node>> { continuation ->
                nodeClient.connectedNodes
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            _isPhoneConnected.value = nodes.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check phone connection: ${e.message}")
            _isPhoneConnected.value = false
        }
    }
}
