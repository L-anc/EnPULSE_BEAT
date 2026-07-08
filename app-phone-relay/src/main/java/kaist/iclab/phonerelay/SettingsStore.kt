package kaist.iclab.phonerelay

import android.content.Context

/** Persists the lab server URL between sessions. */
class SettingsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "relay_settings"
        private const val KEY_SERVER_URL = "server_url"
        const val DEFAULT_SERVER_URL = "ws://192.168.0.10:8765"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }
}
