package kaist.iclab.phonerelay

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RelayScreen()
                }
            }
        }
    }
}

@Composable
fun RelayScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var url by remember { mutableStateOf(settings.serverUrl) }

    val watchConnected by RelayHub.watchConnected.collectAsState()
    val linesReceived by RelayHub.linesReceived.collectAsState()
    val latestBySensor by RelayHub.latestBySensor.collectAsState()

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
            when (wsState) {
                WsClient.State.CONNECTED -> "connected"
                WsClient.State.CONNECTING -> "connecting…"
                WsClient.State.DISCONNECTED -> "disconnected"
            }
        )
        StatusRow("Throughput", "$linesPerSec lines/s ($linesReceived total)")

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
