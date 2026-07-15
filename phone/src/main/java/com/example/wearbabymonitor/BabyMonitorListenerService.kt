package com.example.wearbabymonitor

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

class BabyMonitorListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Protocol.NOISE_PATH -> handleAlert(event)
            Protocol.STATUS_PATH -> handleStatus(event.data.decodeToString())
        }
    }

    private fun handleAlert(event: MessageEvent) {
        val alert = Protocol.decodeAlert(event.data) ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val duplicate = prefs.getString(KEY_LAST_ALERT_ID, null) == alert.id
        prefs.edit()
            .putString(KEY_LAST_ALERT_ID, alert.id)
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .apply()

        // Every duplicate delivery gets another acknowledgement. This lets the
        // watch recover if the first acknowledgement was lost.
        Wearable.getMessageClient(this)
            .sendMessage(event.sourceNodeId, Protocol.ACK_PATH, alert.id.encodeToByteArray())

        if (!duplicate) {
            showAlert(isTest = alert.type == Protocol.ALERT_TYPE_TEST)
        }
    }

    private fun handleStatus(payload: String) {
        val values = payload.split(',').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()

        val battery = values["battery"]?.toIntOrNull() ?: -1
        val monitoring = values["monitoring"]?.toBooleanStrictOrNull() ?: true
        val state = values["state"] ?: "unknown"
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val receiverEnabled = prefs.getBoolean(KEY_RECEIVER_ENABLED, false)
        val previouslyMonitoring = prefs.getBoolean(KEY_WATCH_MONITORING, false)
        val lowBatteryWarned = prefs.getBoolean(KEY_LOW_BATTERY_WARNED, false)

        prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .putInt(KEY_WATCH_BATTERY, battery)
            .putBoolean(KEY_WATCH_MONITORING, monitoring)
            .apply()

        if (receiverEnabled && previouslyMonitoring && !monitoring) {
            showWarning(
                title = "Watch monitoring stopped",
                text = stateText(state),
                id = MONITORING_STOPPED_NOTIFICATION_ID
            )
        }

        if (battery in 0..LOW_BATTERY_PERCENT && !lowBatteryWarned) {
            prefs.edit().putBoolean(KEY_LOW_BATTERY_WARNED, true).apply()
            showWarning(
                title = "Watch battery low",
                text = "The baby-monitor watch has $battery% battery remaining.",
                id = LOW_BATTERY_NOTIFICATION_ID
            )
        } else if (battery >= LOW_BATTERY_RESET_PERCENT && lowBatteryWarned) {
            prefs.edit().putBoolean(KEY_LOW_BATTERY_WARNED, false).apply()
            getSystemService(NotificationManager::class.java)
                .cancel(LOW_BATTERY_NOTIFICATION_ID)
        }
    }

    private fun showAlert(isTest: Boolean) {
        NotificationChannels.create(this)
        val manager = getSystemService(NotificationManager::class.java)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (isTest) "Baby monitor test received" else "Noise detected")
            .setContentText(
                if (isTest) "The complete watch-to-phone alert path is working."
                else "The watch heard a sustained sound in the baby's room."
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun showWarning(title: String, text: String, id: Int) {
        NotificationChannels.create(this)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
        )
    }

    private fun stateText(state: String): String = when (state) {
        "microphone_unavailable" -> "The watch microphone could not be opened."
        "permission_lost" -> "The watch lost microphone permission."
        "audio_error" -> "Audio monitoring stopped unexpectedly."
        "user_stopped" -> "Monitoring was stopped on the watch."
        else -> "The watch is no longer monitoring the room."
    }

    companion object {
        const val PREFS = "phone_monitor_state"
        const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        const val KEY_WATCH_BATTERY = "watch_battery"
        const val KEY_LAST_ALERT_ID = "last_alert_id"
        const val KEY_RECEIVER_ENABLED = "receiver_enabled"
        const val KEY_WATCH_MONITORING = "watch_monitoring"
        const val KEY_LOW_BATTERY_WARNED = "low_battery_warned"

        // Versioned IDs prevent an older installed channel configuration from
        // silently retaining the wrong sound behavior during POC upgrades.
        const val ALERT_CHANNEL_ID = "baby_monitor_alerts_v2"
        const val WARNING_CHANNEL_ID = "baby_monitor_warnings_v2"
        const val LOW_BATTERY_PERCENT = 20
        const val LOW_BATTERY_RESET_PERCENT = 25
        const val LOW_BATTERY_NOTIFICATION_ID = 4202
        const val MONITORING_STOPPED_NOTIFICATION_ID = 4203
    }
}
