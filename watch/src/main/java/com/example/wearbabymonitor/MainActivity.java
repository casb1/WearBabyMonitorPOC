package com.example.wearbabymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int TEST_ACK_POLLS = 20;
    private static final long TEST_ACK_POLL_MS = 250L;

    private TextView status;
    private Button sensitivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setText("Stopped");
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        silenceView(status);

        Button start = button("START", this::ensurePermissionsAndStart);
        Button stop = button("STOP", this::stopMonitor);
        Button test = button("TEST PHONE", this::sendEndToEndTest);
        Button recalibrate = button("RECALIBRATE ROOM", this::recalibrateRoom);
        sensitivity = button(sensitivityLabel(), this::cycleSensitivity);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(26, 26, 26, 48);
        silenceView(layout);
        layout.addView(status);
        layout.addView(start);
        layout.addView(stop);
        layout.addView(test);
        layout.addView(recalibrate);
        layout.addView(sensitivity);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setSmoothScrollingEnabled(true);
        silenceView(scrollView);
        scrollView.addView(
                layout,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );
        setContentView(scrollView);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setMinHeight(56);
        button.setAllCaps(false);
        silenceView(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void silenceView(View view) {
        view.setSoundEffectsEnabled(false);
        view.setHapticFeedbackEnabled(false);
    }

    private String sensitivityLabel() {
        String value = getSharedPreferences(NoiseMonitorService.PREFS, MODE_PRIVATE)
                .getString(NoiseMonitorService.KEY_SENSITIVITY, "medium");
        return "SENSITIVITY: " + value.toUpperCase(java.util.Locale.US);
    }

    private void cycleSensitivity() {
        String current = getSharedPreferences(NoiseMonitorService.PREFS, MODE_PRIVATE)
                .getString(NoiseMonitorService.KEY_SENSITIVITY, "medium");
        String next = "medium";
        if ("medium".equals(current)) next = "high";
        else if ("high".equals(current)) next = "low";
        getSharedPreferences(NoiseMonitorService.PREFS, MODE_PRIVATE)
                .edit().putString(NoiseMonitorService.KEY_SENSITIVITY, next).apply();
        sensitivity.setText(sensitivityLabel());
        status.setText("Sensitivity applies next time monitoring starts");
    }

    private void refreshStatus() {
        android.content.SharedPreferences prefs = getSharedPreferences(NoiseMonitorService.PREFS, MODE_PRIVATE);
        boolean active = prefs.getBoolean(NoiseMonitorService.KEY_MONITORING_ACTIVE, false);
        String state = prefs.getString(NoiseMonitorService.KEY_MONITORING_STATE, active ? "monitoring" : "stopped");
        if (!active) {
            status.setText("Stopped");
        } else if ("calibrating".equals(state)) {
            status.setText("Calibrating — keep the room quiet");
        } else {
            status.setText("Monitoring");
        }
    }

    private void recalibrateRoom() {
        boolean active = getSharedPreferences(NoiseMonitorService.PREFS, MODE_PRIVATE)
                .getBoolean(NoiseMonitorService.KEY_MONITORING_ACTIVE, false);
        if (!active) {
            status.setText("Start monitoring first");
            return;
        }
        Intent intent = new Intent(this, NoiseMonitorService.class);
        intent.setAction(NoiseMonitorService.ACTION_RECALIBRATE);
        startForegroundService(intent);
        status.setText("Recalibrating — keep the room quiet");
    }

    private void ensurePermissionsAndStart() {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (missing.isEmpty()) {
            startMonitor();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void startMonitor() {
        boolean alreadyActive = getSharedPreferences(NoiseMonitorService.PREFS, MODE_PRIVATE)
                .getBoolean(NoiseMonitorService.KEY_MONITORING_ACTIVE, false);
        if (alreadyActive) {
            status.setText("Already monitoring");
            return;
        }
        startForegroundService(new Intent(this, NoiseMonitorService.class));
        status.setText("Calibrating — keep the room quiet");
    }

    private void stopMonitor() {
        sendStatus(false, "user_stopped");
        stopService(new Intent(this, NoiseMonitorService.class));
        status.setText("Stopped");
    }

    private void sendEndToEndTest() {
        String alertId = UUID.randomUUID().toString();
        getSharedPreferences(WatchMessageListenerService.PREFS, MODE_PRIVATE)
                .edit()
                .remove(WatchMessageListenerService.KEY_LAST_ACK)
                .apply();
        status.setText("Testing…");

        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes.isEmpty()) {
                status.setText("Phone disconnected");
                return;
            }
            byte[] payload = Protocol.encodeAlert(alertId, Protocol.ALERT_TYPE_TEST, 0.0);
            for (com.google.android.gms.wearable.Node node : nodes) {
                Wearable.getMessageClient(this).sendMessage(node.getId(), Protocol.NOISE_PATH, payload);
            }
            waitForAck(alertId);
        }).addOnFailureListener(error -> status.setText("Test failed"));
    }

    private void waitForAck(String alertId) {
        Thread thread = new Thread(() -> {
            boolean acknowledged = false;
            for (int poll = 0; poll < TEST_ACK_POLLS && !Thread.currentThread().isInterrupted(); poll++) {
                if (alertId.equals(lastAck())) {
                    acknowledged = true;
                    break;
                }
                try {
                    Thread.sleep(TEST_ACK_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            final boolean result = acknowledged;
            runOnUiThread(() -> status.setText(result ? "Phone confirmed" : "Test failed"));
        }, "baby-monitor-test-ack");
        thread.start();
    }

    private String lastAck() {
        return getSharedPreferences(WatchMessageListenerService.PREFS, MODE_PRIVATE)
                .getString(WatchMessageListenerService.KEY_LAST_ACK, null);
    }

    private void sendStatus(boolean monitoring, String state) {
        String text = "battery=-1,connected=true,monitoring=" + monitoring
                + ",state=" + state + ",time=" + System.currentTimeMillis();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (com.google.android.gms.wearable.Node node : nodes) {
                Wearable.getMessageClient(this).sendMessage(node.getId(), Protocol.STATUS_PATH, payload);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) startMonitor();
        else status.setText("Permissions required");
    }
}
