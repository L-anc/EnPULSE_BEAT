package kaist.iclab.phonerelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the WebSocket to the lab server and forwards
 * every line from RelayHub verbatim. Started automatically when the watch
 * opens its stream channel, or manually from the UI.
 */
class RelayService : Service() {
    companion object {
        private const val TAG = "RelayService"
        private const val NOTIFICATION_CHANNEL_ID = "relay_service"
        private const val NOTIFICATION_ID = 1

        // Exposed for the UI; null until the service first starts
        @Volatile
        var wsState: StateFlow<WsClient.State>? = null
            private set

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wsClient: WsClient
    private var forwardJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wsClient = WsClient(scope)
        wsState = wsClient.state
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            // Background FGS start restriction (e.g. a sticky restart or a watch-triggered
            // start while the app is in the background): bail out instead of crashing the
            // process. The user can start the relay from the UI.
            Log.w(TAG, "Could not enter foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = SettingsStore(this)
        if (settings.relayEnabled) {
            wsClient.start(settings.serverUrl)
        } else {
            wsClient.stop()
        }

        if (forwardJob?.isActive != true) {
            forwardJob = scope.launch {
                RelayHub.lines.collect { line ->
                    wsClient.send(line)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        wsClient.stop()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.relay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setOngoing(true)
            .build()
    }
}
