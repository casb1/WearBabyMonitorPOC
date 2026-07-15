package com.example.wearbabymonitor;

import java.nio.charset.StandardCharsets;

final class Protocol {
    static final String NOISE_PATH = "/baby_noise";
    static final String ACK_PATH = "/baby_ack";
    static final String STATUS_PATH = "/baby_status";

    static final String ALERT_TYPE_NOISE = "NOISE";
    static final String ALERT_TYPE_TEST = "TEST";

    private Protocol() {}

    static byte[] encodeAlert(String id, String type, double rms) {
        String value = id + "|" + type + "|" + rms + "|" + System.currentTimeMillis();
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
