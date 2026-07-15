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
import android.widget.TextView;

import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int TEST_ACK_POLLS = 20;
    private static final long TEST_ACK_POLL_MS = 250L;

    private TextView status;

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

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(18, 18, 18, 18);
        silenceView(layout);
        layout.addView(status);
        layout.addView(start);
        layout.addView(stop);
        layout.addView(test);
        setContentView(layout);
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        silenceView(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void silenceView(View view) {
        view.setSoundEffectsEnabled(false);
        view.setHapticFeedbackEnabled(false);
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
        startForegroundService(new Intent(this, NoiseMonitorService.class));
        status.setText("Calibrating");
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
