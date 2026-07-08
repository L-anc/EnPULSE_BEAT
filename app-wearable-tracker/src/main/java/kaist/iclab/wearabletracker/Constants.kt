package kaist.iclab.wearabletracker

/**
 * Centralized constants for the wearable tracker app.
 * All constants used across the app should be defined here.
 */
object Constants {
    /**
     * Real-time streaming constants (watch -> phone over the Wearable ChannelClient)
     */
    object Streaming {
        const val CHANNEL_PATH = "/enpulse/stream"
        const val BUFFER_CAPACITY = 4096
        const val FLUSH_INTERVAL_MS = 200L
        const val FLUSH_LINE_COUNT = 64
        const val RECONNECT_BACKOFF_MIN_MS = 1_000L
        const val RECONNECT_BACKOFF_MAX_MS = 15_000L
    }

    /**
     * Notification Channel Constants
     */
    object NotificationChannel {
        const val EXPORT_DATA_ID = "export_data_channel"
        const val EXPORT_DATA_NAME = "Export Data"
        const val EXPORT_DATA_DESCRIPTION = "Notifications for data export status"

        const val FLUSH_DATA_ID = "flush_data_channel"
        const val FLUSH_DATA_NAME = "Flush Data"
        const val FLUSH_DATA_DESCRIPTION = "Notifications for data flush status"

        const val ERROR_ID = "error_channel"
        const val ERROR_NAME = "Errors"
        const val ERROR_DESCRIPTION = "Notifications for application errors and exceptions"
    }

    /**
     * Notification ID Constants
     */
    object NotificationId {
        const val EXPORT_DATA_SUCCESS = 1001
        const val EXPORT_DATA_FAILURE = 1002
        const val FLUSH_DATA_SUCCESS = 1003
        const val FLUSH_DATA_FAILURE = 1004
        const val ERROR = 2000 // Base ID for errors, will be incremented for multiple errors
    }

    /**
     * Device Info Constants
     */
    object DeviceInfo {
        const val UNKNOWN_NAME = "Unknown"
        const val UNKNOWN_ID = "Unknown"
    }

    /**
     * Database Constants
     */
    object DB {
        const val BUFFER_SIZE = 1000
        const val BATCH_SIZE = 50
        const val FLUSH_INTERVAL_MS = 2000L
    }
}
