package com.example.wearbabymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.SystemClock;

import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class NoiseMonitorService extends Service {
    private static final String CHANNEL_ID = "baby_monitor_running_v3";
    private static final int NOTIFICATION_ID = 1;
    private static final double DEFAULT_THRESHOLD = 0.075;
    private static final double MIN_THRESHOLD = 0.018;
    private static final double BASELINE_MULTIPLIER = 3.0;
    private static final long CALIBRATION_MS = 8000L;
    private static final int REQUIRED_LOUD_WINDOWS = 3;
    private static final long ALERT_COOLDOWN_MS = 10000L;
    private static final long STATUS_INTERVAL_MS = 10000L;
    private static final int MAX_SEND_ATTEMPTS = 6;
    private static final long RETRY_INTERVAL_MS = 1500L;
    private static final int LOW_BATTERY_PERCENT = 20;
    private static final int LOW_BATTERY_RESET_PERCENT = 25;

    private volatile boolean running;
    private AudioRecord recorder;
    private Thread worker;
    private long lastAlertAt;
    private double calibratedThreshold = DEFAULT_THRESHOLD;
    private long lastStatusAt;
    private boolean warnedDisconnected;
    private boolean warnedLowBattery;

    @Override
    public void onCreate() {
        super.onCreate();
        createSilentChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Calibrating room noise…"));
        if (!running) startRecordingLoop();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        stopRecorderSafely();
        if (recorder != null) recorder.release();
        recorder = null;
        if (worker != null) worker.interrupt();
        worker = null;
        if (wasRunning) sendStatus(false, "stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startRecordingLoop() {
        final int sampleRate = 16000;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            failMonitoring("Microphone unavailable", "microphone_unavailable");
            return;
        }

        final int bufferSize = Math.max(minBuffer, 2048);
        AudioRecord audioRecord;
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
            );
        } catch (SecurityException denied) {
            failMonitoring("Microphone permission lost", "permission_lost");
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            failMonitoring("Microphone unavailable", "microphone_unavailable");
            return;
        }

        recorder = audioRecord;
        running = true;
        worker = new Thread(() -> recordLoop(audioRecord, bufferSize), "baby-noise-monitor");
        worker.start();
    }

    private void recordLoop(AudioRecord audioRecord, int bufferSize) {
        short[] buffer = new short[bufferSize];
        List<Double> calibrationSamples = new ArrayList<>();
        long calibrationEnd = SystemClock.elapsedRealtime() + CALIBRATION_MS;
        int loudWindows = 0;

        try {
            audioRecord.startRecording();
            sendStatus(true, "calibrating");
            lastStatusAt = SystemClock.elapsedRealtime();

            while (running && !Thread.currentThread().isInterrupted()) {
                int count = audioRecord.read(buffer, 0, buffer.length);
                if (count <= 0) continue;

                double rms = calculateNormalizedRms(buffer, count);
                long nowElapsed = SystemClock.elapsedRealtime();

                if (nowElapsed < calibrationEnd) {
                    calibrationSamples.add(rms);
                    continue;
                }

                if (!calibrationSamples.isEmpty()) {
                    Collections.sort(calibrationSamples);
                    int index = Math.min(
                            calibrationSamples.size() - 1,
                            (int) (calibrationSamples.size() * 0.8)
                    );
                    double baseline = calibrationSamples.get(index);
                    calibratedThreshold = Math.max(MIN_THRESHOLD, baseline * BASELINE_MULTIPLIER);
                    calibrationSamples.clear();
                    updateNotification("Monitoring • threshold "
                            + String.format(Locale.US, "%.3f", calibratedThreshold));
                    sendStatus(true, "monitoring");
                }

                loudWindows = rms >= calibratedThreshold ? loudWindows + 1 : 0;
                if (loudWindows >= REQUIRED_LOUD_WINDOWS) {
                    long now = System.currentTimeMillis();
                    if (now - lastAlertAt >= ALERT_COOLDOWN_MS) {
                        lastAlertAt = now;
                        deliverAlertWithRetry(rms);
                    }
                    loudWindows = 0;
                }

                if (nowElapsed - lastStatusAt >= STATUS_INTERVAL_MS) {
                    lastStatusAt = nowElapsed;
                    sendStatus(true, "monitoring");
                }
            }
        } catch (SecurityException denied) {
            failMonitoring("Microphone permission lost", "permission_lost");
        } catch (IllegalStateException audioFailure) {
            failMonitoring("Audio monitoring stopped", "audio_error");
        }
    }

    private void deliverAlertWithRetry(double rms) {
        String alertId = UUID.randomUUID().toString();
        Thread retry = new Thread(() -> {
            for (int attempt = 0; attempt < MAX_SEND_ATTEMPTS; attempt++) {
                if (!running || isAcknowledged(alertId)) return;
                sendToConnectedNodes(
                        Protocol.NOISE_PATH,
                        Protocol.encodeAlert(alertId, Protocol.ALERT_TYPE_NOISE, rms)
                );
                if (attempt < MAX_SEND_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_INTERVAL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (!isAcknowledged(alertId)) {
                updateNotification("Alert not confirmed — check phone");
            }
        }, "baby-alert-retry");
        retry.start();
    }

    private boolean isAcknowledged(String alertId) {
        return alertId.equals(
                getSharedPreferences(WatchMessageListenerService.PREFS, MODE_PRIVATE)
                        .getString(WatchMessageListenerService.KEY_LAST_ACK, null)
        );
    }

    private void sendStatus(boolean monitoring, String state) {
        int battery = batteryPercent();
        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            boolean connected = !nodes.isEmpty();
            if (!connected && !warnedDisconnected) {
                warnedDisconnected = true;
                updateNotification("Phone disconnected");
            } else if (connected && warnedDisconnected) {
                warnedDisconnected = false;
                updateNotification(monitoring ? "Monitoring • reconnected" : "Stopped");
            }

            if (battery >= 0 && battery <= LOW_BATTERY_PERCENT && !warnedLowBattery) {
                warnedLowBattery = true;
                updateNotification("Low battery: " + battery + "%");
            } else if (battery >= LOW_BATTERY_RESET_PERCENT) {
                warnedLowBattery = false;
            }

            String value = "battery=" + battery
                    + ",connected=" + connected
                    + ",monitoring=" + monitoring
                    + ",state=" + state
                    + ",time=" + System.currentTimeMillis();
            byte[] payload = value.getBytes(StandardCharsets.UTF_8);
            for (com.google.android.gms.wearable.Node node : nodes) {
                Wearable.getMessageClient(this).sendMessage(node.getId(), Protocol.STATUS_PATH, payload);
            }
        });
    }

    private void sendToConnectedNodes(String path, byte[] payload) {
        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (com.google.android.gms.wearable.Node node : nodes) {
                Wearable.getMessageClient(this).sendMessage(node.getId(), path, payload);
            }
        });
    }

    private int batteryPercent() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level >= 0 && scale > 0 ? level * 100 / scale : -1;
    }

    private double calculateNormalizedRms(short[] samples, int count) {
        double sumSquares = 0.0;
        for (int index = 0; index < count; index++) {
            double normalized = samples[index] / 32768.0;
            sumSquares += normalized * normalized;
        }
        return Math.sqrt(sumSquares / count);
    }

    private Notification notification(String text) {
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Baby monitor active")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private void createSilentChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Baby monitor monitoring",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setVibrationPattern(null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void stopRecorderSafely() {
        if (recorder == null) return;
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (IllegalStateException ignored) {
            // Recorder was already stopped.
        }
    }

    private void failMonitoring(String visibleStatus, String state) {
        updateNotification(visibleStatus);
        sendStatus(false, state);
        stopSelf();
    }
}
