package com.example.wearbabymonitor;

import java.nio.charset.StandardCharsets;

final class Protocol {
    static final String NOISE_PATH = "/baby_noise";
    static final String ACK_PATH = "/baby_ack";
    static final String STATUS_PATH = "/baby_status";

    static final String ALERT_TYPE_NOISE = "NOISE";
    static final String ALERT_TYPE_TEST = "TEST";

    private Protocol() {}

    static Alert decodeAlert(byte[] data) {
        if (data == null) return null;
        String[] parts = new String(data, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length >= 4) {
            return new Alert(parts[0], parts[1], parseDouble(parts[2]), parseLong(parts[3]));
        }
        if (parts.length >= 3) {
            return new Alert(parts[0], ALERT_TYPE_NOISE, parseDouble(parts[1]), parseLong(parts[2]));
        }
        return null;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static final class Alert {
        final String id;
        final String type;
        final double rms;
        final long timestamp;

        Alert(String id, String type, double rms, long timestamp) {
            this.id = id;
            this.type = type;
            this.rms = rms;
            this.timestamp = timestamp;
        }
    }
}
