package com.example.wearbabymonitor

object Protocol {
    const val NOISE_PATH = "/baby_noise"
    const val ACK_PATH = "/baby_ack"
    const val STATUS_PATH = "/baby_status"

    const val ALERT_TYPE_NOISE = "NOISE"
    const val ALERT_TYPE_TEST = "TEST"

    fun encodeAlert(
        id: String,
        type: String,
        rms: Double,
        timestamp: Long = System.currentTimeMillis()
    ): ByteArray = "$id|$type|$rms|$timestamp".encodeToByteArray()
}
