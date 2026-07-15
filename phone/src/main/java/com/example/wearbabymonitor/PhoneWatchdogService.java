package com.example.wearbabymonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

public final class PhoneWatchdogService extends Service {
    static final String ONGOING_CHANNEL_ID = "baby_monitor_receiver_v3";
    static final int ONGOING_ID = 4200;
    static final int DISCONNECT_ID = 4201;

    private static final long CHECK_INTERVAL_MS = 5000L;
    private static final long DISCONNECT_AFTER_MS = 30000L;
    private static final long INITIAL_CONNECT_GRACE_MS = 45000L;

    private volatile boolean running;
    private Thread worker;
    private long serviceStartedAt;
    private boolean disconnectAlertShown;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannels.create(this);
        serviceStartedAt = System.currentTimeMillis();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ONGOING_ID, ongoingNotification("Waiting for watch heartbeat…"));
        if (!running) startLoop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startLoop() {
        running = true;
        worker = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                evaluateState();
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "phone-monitor-watchdog");
        worker.start();
    }

    private void evaluateState() {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, false);
        long last = prefs.getLong(BabyMonitorListenerService.KEY_LAST_HEARTBEAT, 0L);
        boolean monitoring = prefs.getBoolean(BabyMonitorListenerService.KEY_WATCH_MONITORING, false);
        long age = last == 0L ? Long.MAX_VALUE : now - last;

        if (!enabled) {
            stopSelf();
            running = false;
            return;
        }

        if (last == 0L && now - serviceStartedAt > INITIAL_CONNECT_GRACE_MS) {
            updateOngoing("Watch has not connected");
            showDisconnectAlert(
                    "Baby monitor not connected",
                    "No heartbeat has arrived from the watch. Start monitoring and run the watch test."
            );
        } else if (last == 0L) {
            updateOngoing("Waiting for watch heartbeat…");
        } else if (!monitoring) {
            disconnectAlertShown = false;
            updateOngoing("Watch connected • monitoring stopped");
        } else if (age > DISCONNECT_AFTER_MS) {
            updateOngoing("Watch connection lost");
            showDisconnectAlert(
                    "Baby monitor disconnected",
                    "No heartbeat has arrived from the watch. Check Bluetooth, battery, and the watch app."
            );
        } else {
            disconnectAlertShown = false;
            int battery = prefs.getInt(BabyMonitorListenerService.KEY_WATCH_BATTERY, -1);
            updateOngoing(battery >= 0
                    ? "Watch monitoring • " + battery + "% battery"
                    : "Watch monitoring");
        }
    }

    private Notification ongoingNotification(String text) {
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, ONGOING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Baby monitor receiver")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp)
                .build();
    }

    private void updateOngoing(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(ONGOING_ID, ongoingNotification(text));
    }

    private void showDisconnectAlert(String title, String text) {
        if (disconnectAlertShown) return;
        disconnectAlertShown = true;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(
                this,
                BabyMonitorListenerService.WARNING_CHANNEL_ID
        )
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build();
        manager.notify(DISCONNECT_ID, notification);
    }
}
