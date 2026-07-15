package com.example.wearbabymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

object NotificationChannels {
    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val alertChannel = NotificationChannel(
            BabyMonitorListenerService.ALERT_CHANNEL_ID,
            "Baby monitor alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Noise and end-to-end test alerts from the watch"
            setSound(alarmSound, alarmAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 250, 700, 250, 1_000)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val warningChannel = NotificationChannel(
            BabyMonitorListenerService.WARNING_CHANNEL_ID,
            "Baby monitor warnings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Watch disconnection, stopped monitoring, and low-battery warnings"
            setSound(alarmSound, alarmAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val ongoingChannel = NotificationChannel(
            PhoneWatchdogService.ONGOING_CHANNEL_ID,
            "Baby monitor receiver",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(alertChannel, warningChannel, ongoingChannel))
    }
}
