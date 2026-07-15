package com.example.wearbabymonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class BabyMonitorListenerService extends WearableListenerService {
    static final String PREFS = "phone_monitor_state";
    static final String KEY_LAST_HEARTBEAT = "last_heartbeat";
    static final String KEY_WATCH_BATTERY = "watch_battery";
    static final String KEY_LAST_ALERT_ID = "last_alert_id";
    static final String KEY_RECEIVER_ENABLED = "receiver_enabled";
    static final String KEY_WATCH_MONITORING = "watch_monitoring";
    static final String KEY_LOW_BATTERY_WARNED = "low_battery_warned";

    static final String ALERT_CHANNEL_ID = "baby_monitor_alerts_v3";
    static final String WARNING_CHANNEL_ID = "baby_monitor_warnings_v3";
    static final int LOW_BATTERY_NOTIFICATION_ID = 4202;
    static final int MONITORING_STOPPED_NOTIFICATION_ID = 4203;

    private static final int LOW_BATTERY_PERCENT = 20;
    private static final int LOW_BATTERY_RESET_PERCENT = 25;

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (Protocol.NOISE_PATH.equals(event.getPath())) {
            handleAlert(event);
        } else if (Protocol.STATUS_PATH.equals(event.getPath())) {
            handleStatus(new String(event.getData(), StandardCharsets.UTF_8));
        }
    }

    private void handleAlert(MessageEvent event) {
        Protocol.Alert alert = Protocol.decodeAlert(event.getData());
        if (alert == null || alert.id.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean duplicate = alert.id.equals(prefs.getString(KEY_LAST_ALERT_ID, null));
        prefs.edit()
                .putString(KEY_LAST_ALERT_ID, alert.id)
                .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                .apply();

        Wearable.getMessageClient(this).sendMessage(
                event.getSourceNodeId(),
                Protocol.ACK_PATH,
                alert.id.getBytes(StandardCharsets.UTF_8)
        );

        if (!duplicate) {
            showAlert(Protocol.ALERT_TYPE_TEST.equals(alert.type));
        }
    }

    private void handleStatus(String payload) {
        Map<String, String> values = parseKeyValues(payload);
        int battery = parseInt(values.get("battery"), -1);
        boolean monitoring = Boolean.parseBoolean(values.getOrDefault("monitoring", "false"));
        String state = values.getOrDefault("state", "unknown");

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean receiverEnabled = prefs.getBoolean(KEY_RECEIVER_ENABLED, false);
        boolean previouslyMonitoring = prefs.getBoolean(KEY_WATCH_MONITORING, false);
        boolean lowBatteryWarned = prefs.getBoolean(KEY_LOW_BATTERY_WARNED, false);

        prefs.edit()
                .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                .putInt(KEY_WATCH_BATTERY, battery)
                .putBoolean(KEY_WATCH_MONITORING, monitoring)
                .apply();

        if (receiverEnabled && previouslyMonitoring && !monitoring) {
            showWarning(
                    "Watch monitoring stopped",
                    stateText(state),
                    MONITORING_STOPPED_NOTIFICATION_ID
            );
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (battery >= 0 && battery <= LOW_BATTERY_PERCENT && !lowBatteryWarned) {
            prefs.edit().putBoolean(KEY_LOW_BATTERY_WARNED, true).apply();
            showWarning(
                    "Watch battery low",
                    "The baby-monitor watch has " + battery + "% battery remaining.",
                    LOW_BATTERY_NOTIFICATION_ID
            );
        } else if (battery >= LOW_BATTERY_RESET_PERCENT && lowBatteryWarned) {
            prefs.edit().putBoolean(KEY_LOW_BATTERY_WARNED, false).apply();
            if (manager != null) manager.cancel(LOW_BATTERY_NOTIFICATION_ID);
        }
    }

    private Map<String, String> parseKeyValues(String payload) {
        Map<String, String> result = new HashMap<>();
        for (String item : payload.split(",")) {
            int separator = item.indexOf('=');
            if (separator > 0) {
                result.put(item.substring(0, separator), item.substring(separator + 1));
            }
        }
        return result;
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void showAlert(boolean test) {
        NotificationChannels.create(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(test ? "Baby monitor test received" : "Noise detected")
                .setContentText(test
                        ? "The complete watch-to-phone alert path is working."
                        : "The watch heard a sustained sound in the baby's room.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build();

        manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
    }

    private void showWarning(String title, String text, int id) {
        NotificationChannels.create(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, WARNING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build();
        manager.notify(id, notification);
    }

    private String stateText(String state) {
        switch (state) {
            case "microphone_unavailable":
                return "The watch microphone could not be opened.";
            case "permission_lost":
                return "The watch lost microphone permission.";
            case "audio_error":
                return "Audio monitoring stopped unexpectedly.";
            case "user_stopped":
                return "Monitoring was stopped on the watch.";
            default:
                return "The watch is no longer monitoring the room.";
        }
    }
}
