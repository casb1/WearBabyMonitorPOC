package com.example.wearbabymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

class NoiseMonitorService : Service() {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var lastAlertAt = 0L
    private var calibratedThreshold = DEFAULT_THRESHOLD
    private var lastStatusAt = 0L
    private var warnedDisconnected = false
    private var warnedLowBattery = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Calibrating room noise…"))
        if (!running) startRecordingLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val wasRunning = running
        running = false
        recorder?.stopSafely()
        recorder?.release()
        recorder = null
        worker?.interrupt()
        worker = null
        if (wasRunning) {
            sendStatus(monitoring = false, state = "stopped")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecordingLoop() {
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            updateNotification("Microphone unavailable")
            sendStatus(monitoring = false, state = "microphone_unavailable")
            stopSelf()
            return
        }

        val bufferSize = max(minBuffer, 2048)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            updateNotification("Microphone unavailable")
            sendStatus(monitoring = false, state = "microphone_unavailable")
            stopSelf()
            return
        }

        recorder = audioRecord
        running = true
        worker = Thread {
            val buffer = ShortArray(bufferSize)
            val calibrationSamples = mutableListOf<Double>()
            val calibrationEnd = SystemClock.elapsedRealtime() + CALIBRATION_MS
            var loudWindows = 0

            try {
                audioRecord.startRecording()
                sendStatus(monitoring = true, state = "calibrating")
                lastStatusAt = SystemClock.elapsedRealtime()

                while (running && !Thread.currentThread().isInterrupted) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    val rms = calculateNormalizedRms(buffer, count)
                    val nowElapsed = SystemClock.elapsedRealtime()

                    if (nowElapsed < calibrationEnd) {
                        calibrationSamples += rms
                        continue
                    } else if (calibrationSamples.isNotEmpty()) {
                        val sorted = calibrationSamples.sorted()
                        val baseline = sorted[(sorted.size * 0.8).toInt().coerceAtMost(sorted.lastIndex)]
                        calibratedThreshold = max(MIN_THRESHOLD, baseline * BASELINE_MULTIPLIER)
                        calibrationSamples.clear()
                        updateNotification(
                            "Monitoring • threshold ${String.format(Locale.US, "%.3f", calibratedThreshold)}"
                        )
                        sendStatus(monitoring = true, state = "monitoring")
                    }

                    loudWindows = if (rms >= calibratedThreshold) loudWindows + 1 else 0
                    if (loudWindows >= REQUIRED_LOUD_WINDOWS) {
                        val now = System.currentTimeMillis()
                        if (now - lastAlertAt >= ALERT_COOLDOWN_MS) {
                            lastAlertAt = now
                            deliverAlertWithRetry(rms)
                        }
                        loudWindows = 0
                    }

                    if (nowElapsed - lastStatusAt >= STATUS_INTERVAL_MS) {
                        lastStatusAt = nowElapsed
                        sendStatus(monitoring = true, state = "monitoring")
                    }
                }
            } catch (_: SecurityException) {
                updateNotification("Microphone permission lost")
                sendStatus(monitoring = false, state = "permission_lost")
                stopSelf()
            } catch (_: IllegalStateException) {
                updateNotification("Audio monitoring stopped")
                sendStatus(monitoring = false, state = "audio_error")
                stopSelf()
            }
        }.apply {
            name = "baby-noise-monitor"
            start()
        }
    }

    private fun deliverAlertWithRetry(rms: Double) {
        val alertId = UUID.randomUUID().toString()
        Thread {
            repeat(MAX_SEND_ATTEMPTS) { attempt ->
                if (!running || isAcknowledged(alertId)) return@Thread
                sendToConnectedNodes(
                    Protocol.NOISE_PATH,
                    Protocol.encodeAlert(
                        id = alertId,
                        type = Protocol.ALERT_TYPE_NOISE,
                        rms = rms
                    )
                )
                if (attempt < MAX_SEND_ATTEMPTS - 1) Thread.sleep(RETRY_INTERVAL_MS)
            }
            if (!isAcknowledged(alertId)) {
                // Visual-only status. The watch channel has sound and vibration disabled.
                updateNotification("Alert not confirmed — check phone")
            }
        }.apply {
            name = "baby-alert-retry"
            start()
        }
    }

    private fun isAcknowledged(alertId: String): Boolean =
        getSharedPreferences(WatchMessageListenerService.PREFS, MODE_PRIVATE)
            .getString(WatchMessageListenerService.KEY_LAST_ACK, null) == alertId

    private fun sendStatus(monitoring: Boolean, state: String) {
        val battery = batteryPercent()
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val connected = nodes.isNotEmpty()
                if (!connected && !warnedDisconnected) {
                    warnedDisconnected = true
                    updateNotification("Phone disconnected")
                } else if (connected && warnedDisconnected) {
                    warnedDisconnected = false
                    updateNotification(if (monitoring) "Monitoring • reconnected" else "Stopped")
                }

                if (battery in 0..LOW_BATTERY_PERCENT && !warnedLowBattery) {
                    warnedLowBattery = true
                    updateNotification("Low battery: $battery%")
                } else if (battery >= LOW_BATTERY_RESET_PERCENT) {
                    warnedLowBattery = false
                }

                val payload = buildString {
                    append("battery=").append(battery)
                    append(",connected=").append(connected)
                    append(",monitoring=").append(monitoring)
                    append(",state=").append(state)
                    append(",time=").append(System.currentTimeMillis())
                }.encodeToByteArray()
                nodes.forEach { node ->
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, Protocol.STATUS_PATH, payload)
                }
            }
    }

    private fun sendToConnectedNodes(path: String, payload: ByteArray) {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, path, payload)
            }
        }
    }

    private fun batteryPercent(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun calculateNormalizedRms(samples: ShortArray, count: Int): Double {
        var sumSquares = 0.0
        for (i in 0 until count) {
            val normalized = samples[i] / 32768.0
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / count)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Baby monitor active")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Baby monitor monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = null
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun AudioRecord.stopSafely() {
        try {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
    }

    companion object {
        private const val CHANNEL_ID = "baby_monitor_running_v2"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_THRESHOLD = 0.075
        private const val MIN_THRESHOLD = 0.018
        private const val BASELINE_MULTIPLIER = 3.0
        private const val CALIBRATION_MS = 8_000L
        private const val REQUIRED_LOUD_WINDOWS = 3
        private const val ALERT_COOLDOWN_MS = 10_000L
        private const val STATUS_INTERVAL_MS = 10_000L
        private const val MAX_SEND_ATTEMPTS = 6
        private const val RETRY_INTERVAL_MS = 1_500L
        private const val LOW_BATTERY_PERCENT = 20
        private const val LOW_BATTERY_RESET_PERCENT = 25
    }
}
