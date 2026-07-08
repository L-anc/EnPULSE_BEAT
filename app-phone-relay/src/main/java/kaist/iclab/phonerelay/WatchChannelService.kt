package kaist.iclab.phonerelay

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Receives the watch's streaming channel (path /enpulse/stream) and pumps its
 * JSON lines into RelayHub. Also starts RelayService so relaying begins
 * automatically when the watch starts logging.
 *
 * The line pump runs on the callback thread: WearableListenerService keeps the
 * process alive while the channel is open, and onChannelClosed arrives on a
 * separate binder thread.
 */
class WatchChannelService : WearableListenerService() {
    companion object {
        private const val TAG = "WatchChannelService"
        private const val STREAM_PATH = "/enpulse/stream"
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != STREAM_PATH) return
        Log.i(TAG, "Watch stream channel opened from ${channel.nodeId}")

        try {
            startForegroundService(Intent(this, RelayService::class.java))
        } catch (e: Exception) {
            // Background FGS start restriction: the user can start the relay from the UI
            Log.w(TAG, "Could not auto-start RelayService: ${e.message}")
        }

        RelayHub.onWatchConnected(true)

        val channelClient = Wearable.getChannelClient(this)
        try {
            val inputStream = com.google.android.gms.tasks.Tasks.await(
                channelClient.getInputStream(channel)
            )
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) RelayHub.publish(line)
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Watch stream ended: ${e.message}")
        } finally {
            RelayHub.onWatchConnected(false)
        }
    }

    override fun onChannelClosed(
        channel: ChannelClient.Channel,
        closeReason: Int,
        appSpecificErrorCode: Int
    ) {
        if (channel.path != STREAM_PATH) return
        Log.i(TAG, "Watch stream channel closed (reason=$closeReason)")
        RelayHub.onWatchConnected(false)
    }
}
