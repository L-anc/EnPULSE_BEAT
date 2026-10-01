package kaist.iclab.wearabletracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import kaist.iclab.tracker.permission.AndroidPermissionManager
import kaist.iclab.tracker.sensor.controller.ControllerState
import kaist.iclab.tracker.sensor.core.SensorState
import kaist.iclab.wearabletracker.R
import kaist.iclab.wearabletracker.data.DeviceInfo
import kaist.iclab.wearabletracker.helpers.PermissionCheckResult
import kaist.iclab.wearabletracker.streaming.StreamingManager
import kaist.iclab.wearabletracker.helpers.PermissionHelper
import kaist.iclab.wearabletracker.ui.components.DeviceStatusInfo
import kaist.iclab.wearabletracker.ui.components.FlushConfirmationDialog
import kaist.iclab.wearabletracker.ui.components.PermissionPermanentlyDeniedDialog
import kaist.iclab.wearabletracker.ui.components.SamsungHealthConnectionErrorScreen
import kaist.iclab.wearabletracker.ui.components.SdkPolicyErrorScreen
import kaist.iclab.wearabletracker.ui.components.SensorToggleChip
import kaist.iclab.wearabletracker.ui.components.SettingController
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsScreen(
    androidPermissionManager: AndroidPermissionManager,
    settingsViewModel: SettingsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val sensorMap = settingsViewModel.sensorMap
    val isCollecting = settingsViewModel.controllerState.collectAsState().value
    val sensorState = settingsViewModel.sensorState

    val sensorStates = sensorState.mapValues { it.value.collectAsState() }
    val availableSensors = sensorStates.filter { (_, state) ->
        state.value.flag != SensorState.FLAG.UNAVAILABLE
    }

    var showFlushDialog by remember { mutableStateOf(false) }
    var showPermissionPermanentlyDeniedDialog by remember { mutableStateOf(false) }

    /**
     * Helper function to handle notification permission check and execute action if granted.
     * Reduces code duplication across different features (export, flush, startLogging).
     */
    fun handleNotificationPermissionCheck(onGranted: () -> Unit) {
        when (PermissionHelper.checkNotificationPermission(context, androidPermissionManager)) {
            PermissionCheckResult.Granted -> {
                onGranted()
            }

            PermissionCheckResult.PermanentlyDenied -> {
                showPermissionPermanentlyDeniedDialog = true
            }

            PermissionCheckResult.Requested -> {
                // Permission requested - user needs to grant it and try again
            }
        }
    }

    // Check if any sensor is enabled
    val hasEnabledSensors = sensorState.values.any { stateFlow ->
        val state = stateFlow.collectAsState().value
        state.flag == SensorState.FLAG.ENABLED || state.flag == SensorState.FLAG.RUNNING
    }

    // Samsung Health connection state
    val isSamsungHealthConnected by settingsViewModel.isSamsungHealthConnected.collectAsState()

    // SDK Policy Error state (dev mode not enabled on Health Platform)
    val hasSdkPolicyError by settingsViewModel.sdkPolicyError.collectAsState()

    // State for showing connection error when user tries to start without connection
    var showConnectionError by remember { mutableStateOf(false) }

    // Device information state
    var deviceInfo by remember { mutableStateOf(DeviceInfo()) }
    LaunchedEffect(Unit) {
        settingsViewModel.getDeviceInfo(context) { receivedDeviceInfo ->
            deviceInfo = receivedDeviceInfo
        }

        // Check notification permission at app startup (will request if needed, but won't show dialog for permanent denial)
        // The permanent denial dialog will only show when user tries to perform an action
        PermissionHelper.checkNotificationPermission(context, androidPermissionManager)
    }

    // Observe dashboard data
    val totalRecordCount by settingsViewModel.totalRecordCount.collectAsState()
    val batteryLevel by settingsViewModel.batteryLevel.collectAsState()
    val recordingStartTime by settingsViewModel.recordingStartTime.collectAsState()

    // Observe phone connection status
    val isPhoneConnected by settingsViewModel.isPhoneConnected.collectAsState()

    // Observe live streaming state
    val streamingState by settingsViewModel.streamingState.collectAsState()
    val streamingStatusText = stringResource(
        when (streamingState) {
            StreamingManager.StreamingState.DISCONNECTED -> R.string.streaming_status_disconnected
            StreamingManager.StreamingState.CONNECTING -> R.string.streaming_status_connecting
            StreamingManager.StreamingState.STREAMING -> R.string.streaming_status_streaming
            StreamingManager.StreamingState.RECONNECTING -> R.string.streaming_status_reconnecting
        }
    )

    //UI
    when {
        hasSdkPolicyError -> {
            // Show error screen when SDK Policy Error (dev mode not enabled)
            SdkPolicyErrorScreen(
                onDismiss = { settingsViewModel.clearSdkPolicyError() }
            )
        }

        showConnectionError -> {
            // Show error screen when user tries to start without Samsung Health connection
            SamsungHealthConnectionErrorScreen(
                onRetry = { showConnectionError = false }
            )
        }

        else -> {
            // Always show main settings UI
            Scaffold(
                vignette = {
                    Vignette(vignettePosition = VignettePosition.TopAndBottom)
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 10.dp),
                ) {
                    SettingController(
                        export = {
                            handleNotificationPermissionCheck {
                                settingsViewModel.export(context)
                            }
                        },
                        flush = {
                            handleNotificationPermissionCheck {
                                showFlushDialog = true
                            }
                        },
                        startLogging = {
                            handleNotificationPermissionCheck {
                                // Check Samsung Health connection first
                                if (!isSamsungHealthConnected) {
                                    showConnectionError = true
                                } else {
                                    settingsViewModel.startLogging()
                                }
                            }
                        },
                        stopLogging = { settingsViewModel.stopLogging() },
                        isCollecting = (isCollecting.flag == ControllerState.FLAG.RUNNING),
                        hasEnabledSensors = hasEnabledSensors
                    )
                    DeviceStatusInfo(
                        deviceInfo = deviceInfo,
                        totalRecordCount = totalRecordCount,
                        batteryLevel = batteryLevel,
                        isRecording = (isCollecting.flag == ControllerState.FLAG.RUNNING),
                        recordingStartTime = recordingStartTime,
                        isPhoneConnected = isPhoneConnected,
                        streamingStatus = streamingStatusText,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp)
                    ) {
                        availableSensors.forEach { (name, _) ->
                            SensorToggleChip(
                                sensorId = name,
                                sensorStateFlow = sensorState[name]!!,
                                updateStatus = { status ->
                                    if (status) {
                                        settingsViewModel.enableWhenGranted(name, androidPermissionManager)
                                    } else {
                                        settingsViewModel.update(name, false)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog
    FlushConfirmationDialog(
        showDialog = showFlushDialog,
        onDismiss = { showFlushDialog = false },
        onConfirm = {
            settingsViewModel.flush(context)
            showFlushDialog = false
        }
    )

    // Permission Permanently Denied Dialog
    PermissionPermanentlyDeniedDialog(
        showDialog = showPermissionPermanentlyDeniedDialog,
        onDismiss = { showPermissionPermanentlyDeniedDialog = false },
        onOpenSettings = {
            PermissionHelper.openNotificationSettings(context)
            showPermissionPermanentlyDeniedDialog = false
        }
    )
}
