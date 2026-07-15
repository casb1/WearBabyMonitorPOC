package com.example.wearbabymonitor

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PhoneWatchdogService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var serviceStartedAt = 0L
    private var disconnectAlertShown = false

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        serviceStartedAt = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_ID, ongoingNotification("Waiting for watch heartbeat…"))
        if (!running) startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        running = true
        worker = Thread {
            while (running && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                val prefs = getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE)
                val receiverEnabled = prefs.getBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, false)
                val last = prefs.getLong(BabyMonitorListenerService.KEY_LAST_HEARTBEAT, 0L)
                val monitoring = prefs.getBoolean(BabyMonitorListenerService.KEY_WATCH_MONITORING, false)
                val age = if (last == 0L) Long.MAX_VALUE else now - last

                if (!receiverEnabled) {
                    stopSelf()
                    return@Thread
                }

                when {
                    last == 0L && now - serviceStartedAt > INITIAL_CONNECT_GRACE_MS -> {
                        updateOngoing("Watch has not connected")
                        showDisconnectAlert(
                            title = "Baby monitor not connected",
                            text = "No heartbeat has arrived from the watch. Start monitoring and run the watch test."
                        )
                    }

                    last == 0L -> {
                        updateOngoing("Waiting for watch heartbeat…")
                    }

                    !monitoring -> {
                        disconnectAlertShown = false
                        updateOngoing("Watch connected • monitoring stopped")
                    }

                    age > DISCONNECT_AFTER_MS -> {
                        updateOngoing("Watch connection lost")
                        showDisconnectAlert(
                            title = "Baby monitor disconnected",
                            text = "No heartbeat has arrived from the watch. Check Bluetooth, battery, and the watch app."
                        )
                    }

                    else -> {
                        disconnectAlertShown = false
                        val battery = prefs.getInt(BabyMonitorListenerService.KEY_WATCH_BATTERY, -1)
                        updateOngoing(
                            if (battery >= 0) "Watch monitoring • $battery% battery"
                            else "Watch monitoring"
                        )
                    }
                }
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }.apply {
            name = "phone-monitor-watchdog"
            start()
        }
    }

    private fun showDisconnectAlert(title: String, text: String) {
        if (disconnectAlertShown) return
        disconnectAlertShown = true
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(NotificationManager::class.java).notify(
            DISCONNECT_ID,
            NotificationCompat.Builder(this, BabyMonitorListenerService.WARNING_CHANNEL_ID)
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

    private fun ongoingNotification(text: String) = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Baby monitor receiver")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateOngoing(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_ID, ongoingNotification(text))
    }

    companion object {
        const val ONGOING_CHANNEL_ID = "baby_monitor_receiver"
        const val ONGOING_ID = 4200
        const val DISCONNECT_ID = 4201
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val DISCONNECT_AFTER_MS = 30_000L
        private const val INITIAL_CONNECT_GRACE_MS = 45_000L
    }
}
