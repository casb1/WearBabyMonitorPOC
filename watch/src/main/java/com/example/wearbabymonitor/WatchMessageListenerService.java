package com.example.wearbabymonitor;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

public final class WatchMessageListenerService extends WearableListenerService {
    static final String PREFS = "monitor_state";
    static final String KEY_LAST_ACK = "last_ack";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!Protocol.ACK_PATH.equals(event.getPath())) return;
        String alertId = new String(event.getData(), StandardCharsets.UTF_8);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_ACK, alertId)
                .apply();
    }
}
