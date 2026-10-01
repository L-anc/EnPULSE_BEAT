package kaist.iclab.phonerelay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
            )
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Relay") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Debug") })
        }
        when (tab) {
            0 -> RelayScreen()
            1 -> DebugScreen()
        }
    }
}

@Composable
fun RelayScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var uploadEnabled by remember { mutableStateOf(settings.relayEnabled) }
    var recordEnabled by remember { mutableStateOf(settings.recordEnabled) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val watchConnected by RelayHub.watchConnected.collectAsState()
    val linesReceived by RelayHub.linesReceived.collectAsState()
    val latestBySensor by RelayHub.latestBySensor.collectAsState()
    val recorder by LocalRecorder.status.collectAsState()
    val exportState by CsvExporter.state.collectAsState()

    // Service state is exposed via statics; poll them for the UI
    var relayRunning by remember { mutableStateOf(RelayService.isRunning) }
    var wsState by remember { mutableStateOf(WsClient.State.DISCONNECTED) }
    var linesPerSec by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastCount = RelayHub.linesReceived.value
        while (true) {
            relayRunning = RelayService.isRunning
            wsState = RelayService.wsState?.value ?: WsClient.State.DISCONNECTED
            val count = RelayHub.linesReceived.value
            linesPerSec = count - lastCount
            lastCount = count
            withContext(Dispatchers.IO) { LocalRecorder.refreshStored(context) }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("EnPULSE Relay", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Lab server URL (ws://host:port)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Upload to server", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = uploadEnabled,
                onCheckedChange = { enabled ->
                    uploadEnabled = enabled
                    settings.relayEnabled = enabled
                    // Re-deliver onStartCommand so the running service applies the change
                    if (RelayService.isRunning) RelayService.start(context)
                }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    settings.serverUrl = url.trim()
                    RelayService.stop(context)
                    RelayService.start(context)
                },
                enabled = url.isNotBlank()
            ) {
                Text(if (relayRunning) "Restart relay" else "Start relay")
            }
            OutlinedButton(
                onClick = { RelayService.stop(context) },
                enabled = relayRunning
            ) {
                Text("Stop")
            }
        }

        HorizontalDivider()

        StatusRow("Relay service", if (relayRunning) "running" else "stopped")
        StatusRow("Watch stream", if (watchConnected) "connected" else "not connected")
        StatusRow(
            "Server",
            when {
                !uploadEnabled -> "upload off"
                wsState == WsClient.State.CONNECTED -> "connected"
                wsState == WsClient.State.CONNECTING -> "connecting…"
                else -> "disconnected"
            }
        )
        StatusRow("Throughput", "$linesPerSec lines/s ($linesReceived total)")

        HorizontalDivider()

        Text("Local recording", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Save to phone", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = recordEnabled,
                onCheckedChange = { enabled ->
                    recordEnabled = enabled
                    settings.recordEnabled = enabled
                }
            )
        }
        StatusRow(
            "Session",
            recorder.sessionId?.let { "$it (${recorder.sessionRows} rows)" } ?: "none"
        )
        StatusRow(
            "Stored",
            "${recorder.storedSessions} sessions · %.1f MB".format(recorder.storedBytes / 1_000_000f)
        )
        val exporting = exportState == CsvExporter.State.Running
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { CsvExporter.start(context) },
                enabled = !exporting && recorder.storedSessions > 0
            ) {
                Text("Export CSV")
            }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = !exporting && recorder.storedSessions > 0
            ) {
                Text("Delete local data")
            }
        }
        when (val state = exportState) {
            CsvExporter.State.Idle -> {}
            CsvExporter.State.Running -> Text("Exporting…", style = MaterialTheme.typography.bodySmall)
            is CsvExporter.State.Done -> Text(
                "Exported ${state.files} files to Download/${CsvExporter.EXPORT_FOLDER}",
                style = MaterialTheme.typography.bodySmall
            )
            is CsvExporter.State.Failed -> Text(
                "Export failed: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider()

        Text("Live values", style = MaterialTheme.typography.titleMedium)
        if (latestBySensor.isEmpty()) {
            Text(
                "No data yet. Start logging on the watch.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            latestBySensor.toSortedMap().forEach { (sensorId, data) ->
                Column {
                    Text(sensorId, style = MaterialTheme.typography.labelLarge)
                    Text(
                        data.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete local data?") },
            text = {
                Text(
                    "This deletes all ${recorder.storedSessions} recorded sessions from the app. " +
                            "Files already exported to Downloads are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch(Dispatchers.IO) { LocalRecorder.deleteAll(context) }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(0.4f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
