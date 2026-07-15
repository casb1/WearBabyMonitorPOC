package com.example.wearbabymonitor

object Protocol {
    const val NOISE_PATH = "/baby_noise"
    const val ACK_PATH = "/baby_ack"
    const val STATUS_PATH = "/baby_status"

    const val ALERT_TYPE_NOISE = "NOISE"
    const val ALERT_TYPE_TEST = "TEST"

    data class Alert(
        val id: String,
        val type: String,
        val rms: Double,
        val timestamp: Long
    )

    fun decodeAlert(data: ByteArray): Alert? {
        val parts = data.decodeToString().split('|')
        if (parts.size >= 4) {
            return Alert(
                id = parts[0],
                type = parts[1],
                rms = parts[2].toDoubleOrNull() ?: 0.0,
                timestamp = parts[3].toLongOrNull() ?: 0L
            )
        }

        // Compatibility with the original POC payload: id|rms|timestamp
        if (parts.size >= 3) {
            return Alert(
                id = parts[0],
                type = ALERT_TYPE_NOISE,
                rms = parts[1].toDoubleOrNull() ?: 0.0,
                timestamp = parts[2].toLongOrNull() ?: 0L
            )
        }
        return null
    }
}
