package com.example.wearbabymonitor;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 100;
    private static final int LOCAL_TEST_NOTIFICATION_ID = 999;

    private TextView status;
    private TextView alertState;
    private TextView history;
    private TextView batteryEstimate;
    private boolean startAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotificationChannels.create(this);

        status = textView(19f);
        alertState = textView(22f);
        history = textView(15f);
        batteryEstimate = textView(15f);

        Button acknowledge = button("ACKNOWLEDGE ALERT", this::acknowledgeAlert);
        Button enable = button("ENABLE RECEIVER", this::enableReceiverWithPermission);
        Button disable = button("DISABLE RECEIVER", this::disableReceiver);
        Button test = button("TEST PHONE ALARM", this::testPhoneAlarmWithPermission);
        Button settings = button("PHONE ALERT SETTINGS", this::openAlertSettings);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(24, 24, 24, 24);
        layout.addView(status);
        layout.addView(alertState);
        layout.addView(acknowledge);
        layout.addView(enable);
        layout.addView(disable);
        layout.addView(test);
        layout.addView(settings);
        layout.addView(batteryEstimate);
        layout.addView(history);
        setContentView(layout);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    private TextView textView(float size) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setPadding(24, 20, 24, 20);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void enableReceiverWithPermission() {
        if (needsNotificationPermission()) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else {
            enableReceiver();
        }
    }

    private void enableReceiver() {
        NotificationChannels.create(this);
        if (!NotificationChannels.alertsCanNotify(this)) {
            status.setText("Enable baby monitor alerts in phone notification settings");
            return;
        }
        getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, true)
                .putLong(BabyMonitorListenerService.KEY_LAST_HEARTBEAT, 0L)
                .putBoolean(BabyMonitorListenerService.KEY_WATCH_MONITORING, false)
                .remove(BabyMonitorListenerService.KEY_BATTERY_SESSION_START_TIME)
                .remove(BabyMonitorListenerService.KEY_BATTERY_SESSION_START_LEVEL)
                .remove(BabyMonitorListenerService.KEY_BATTERY_DRAIN_PER_HOUR)
                .apply();
        startForegroundService(new Intent(this, PhoneWatchdogService.class));
        refreshStatus();
    }

    private void disableReceiver() {
        getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, false)
                .apply();
        stopService(new Intent(this, PhoneWatchdogService.class));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(PhoneWatchdogService.ONGOING_ID);
            manager.cancel(PhoneWatchdogService.DISCONNECT_ID);
            manager.cancel(BabyMonitorListenerService.MONITORING_STOPPED_NOTIFICATION_ID);
        }
        refreshStatus();
    }

    private void acknowledgeAlert() {
        BabyMonitorListenerService.acknowledgeCurrentAlert(this);
        refreshStatus();
    }

    private void testPhoneAlarmWithPermission() {
        if (needsNotificationPermission()) {
            startAfterPermission = false;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else {
            showLocalTestAlert();
        }
    }

    private void showLocalTestAlert() {
        NotificationChannels.create(this);
        if (!NotificationChannels.alertsCanNotify(this)) {
            status.setText("Phone alarm blocked — enable baby monitor alerts in settings");
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Notification notification = new Notification.Builder(this, BabyMonitorListenerService.ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Phone alarm test")
                .setContentText("The phone notification channel is working.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build();
        manager.notify(LOCAL_TEST_NOTIFICATION_ID, notification);
    }

    private void openAlertSettings() {
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        intent.putExtra(Settings.EXTRA_CHANNEL_ID, BabyMonitorListenerService.ALERT_CHANNEL_ID);
        startActivity(intent);
    }

    private boolean needsNotificationPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, false);
        boolean monitoring = prefs.getBoolean(BabyMonitorListenerService.KEY_WATCH_MONITORING, false);
        int battery = prefs.getInt(BabyMonitorListenerService.KEY_WATCH_BATTERY, -1);
        boolean activeAlert = prefs.getBoolean(BabyMonitorListenerService.KEY_ACTIVE_ALERT, false);

        if (enabled && !NotificationChannels.alertsCanNotify(this)) {
            status.setText("Receiver needs attention\nEnable baby monitor alerts in phone settings");
        } else if (!enabled) {
            status.setText("Receiver disabled");
        } else if (monitoring && battery >= 0) {
            status.setText("Receiver enabled\nWatch monitoring • " + battery + "%");
        } else if (monitoring) {
            status.setText("Receiver enabled\nWatch monitoring");
        } else {
            status.setText("Receiver enabled\nWaiting for watch monitoring");
        }

        alertState.setText(activeAlert ? "⚠ NOISE DETECTED — ACKNOWLEDGE" : "No active alert");
        if (monitoring && prefs.contains(BabyMonitorListenerService.KEY_BATTERY_DRAIN_PER_HOUR)) {
            float drain = prefs.getFloat(BabyMonitorListenerService.KEY_BATTERY_DRAIN_PER_HOUR, 0f);
            batteryEstimate.setText(String.format(java.util.Locale.getDefault(),
                    "Estimated watch drain: %.1f%% per hour", drain));
        } else if (monitoring) {
            batteryEstimate.setText("Battery estimate: collecting data (needs at least 30 minutes and a 2% drop)");
        } else {
            batteryEstimate.setText("");
        }
        String value = prefs.getString(BabyMonitorListenerService.KEY_ALERT_HISTORY, "");
        history.setText(value.isEmpty() ? "Alert history\nNo alerts yet" : "Alert history\n" + value);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (!granted) {
            status.setText("Notification permission is required");
            startAfterPermission = false;
            return;
        }
        if (startAfterPermission) enableReceiver();
        else showLocalTestAlert();
        startAfterPermission = false;
    }
}
