package com.example.wearbabymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

final class NotificationChannels {
    private NotificationChannels() {}

    static void create(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) {
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        AudioAttributes alarmAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel alerts = new NotificationChannel(
                BabyMonitorListenerService.ALERT_CHANNEL_ID,
                "Baby monitor alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alerts.setDescription("Noise and end-to-end test alerts from the watch");
        alerts.setSound(alarmSound, alarmAttributes);
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[]{0, 700, 250, 700, 250, 1000});
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel warnings = new NotificationChannel(
                BabyMonitorListenerService.WARNING_CHANNEL_ID,
                "Baby monitor warnings",
                NotificationManager.IMPORTANCE_HIGH
        );
        warnings.setDescription("Watch disconnect, stopped monitoring, and low-battery warnings");
        warnings.setSound(alarmSound, alarmAttributes);
        warnings.enableVibration(true);
        warnings.setVibrationPattern(new long[]{0, 500, 250, 500});
        warnings.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel ongoing = new NotificationChannel(
                PhoneWatchdogService.ONGOING_CHANNEL_ID,
                "Baby monitor receiver",
                NotificationManager.IMPORTANCE_LOW
        );
        ongoing.setSound(null, null);
        ongoing.enableVibration(false);
        ongoing.setVibrationPattern(null);

        manager.createNotificationChannel(alerts);
        manager.createNotificationChannel(warnings);
        manager.createNotificationChannel(ongoing);
    }
}
