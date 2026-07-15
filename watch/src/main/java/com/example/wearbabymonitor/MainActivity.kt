package com.example.wearbabymonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Stopped"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val start = Button(this).apply {
            text = "START"
            setOnClickListener { ensurePermissionsAndStart() }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                sendStoppedStatus()
                stopService(Intent(this@MainActivity, NoiseMonitorService::class.java))
                status.text = "Stopped"
            }
        }
        val test = Button(this).apply {
            text = "TEST PHONE"
            setOnClickListener { sendEndToEndTest() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18, 18, 18, 18)
            addView(status)
            addView(start)
            addView(stop)
            addView(test)
        })
    }

    private fun ensurePermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startMonitor()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS &&
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startMonitor()
        } else {
            status.text = "Permissions required"
        }
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, NoiseMonitorService::class.java)
        )
        status.text = "Calibrating"
    }

    private fun sendEndToEndTest() {
        val alertId = UUID.randomUUID().toString()
        getSharedPreferences(WatchMessageListenerService.PREFS, MODE_PRIVATE)
            .edit()
            .remove(WatchMessageListenerService.KEY_LAST_ACK)
            .apply()
        status.text = "Testing…"

        Thread {
            var acknowledged = false
            try {
                for (attempt in 0 until TEST_SEND_ATTEMPTS) {
                    val nodes = Tasks.await(
                        Wearable.getNodeClient(this).connectedNodes,
                        TEST_TASK_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                    for (node in nodes) {
                        Tasks.await(
                            Wearable.getMessageClient(this).sendMessage(
                                node.id,
                                Protocol.NOISE_PATH,
                                Protocol.encodeAlert(
                                    id = alertId,
                                    type = Protocol.ALERT_TYPE_TEST,
                                    rms = 0.0
                                )
                            ),
                            TEST_TASK_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                        )
                    }

                    for (poll in 0 until TEST_ACK_POLLS) {
                        Thread.sleep(TEST_ACK_POLL_MS)
                        if (lastAck() == alertId) {
                            acknowledged = true
                            break
                        }
                    }
                    if (acknowledged) break
                }
            } catch (_: Exception) {
                acknowledged = false
            }

            runOnUiThread {
                status.text = if (acknowledged) "Phone confirmed" else "Test failed"
            }
        }.apply {
            name = "baby-monitor-e2e-test"
            start()
        }
    }

    private fun sendStoppedStatus() {
        val payload = "battery=-1,connected=true,monitoring=false,state=user_stopped,time=${System.currentTimeMillis()}"
            .encodeToByteArray()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this)
                    .sendMessage(node.id, Protocol.STATUS_PATH, payload)
            }
        }
    }

    private fun lastAck(): String? =
        getSharedPreferences(WatchMessageListenerService.PREFS, MODE_PRIVATE)
            .getString(WatchMessageListenerService.KEY_LAST_ACK, null)

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private const val TEST_SEND_ATTEMPTS = 4
        private const val TEST_ACK_POLLS = 5
        private const val TEST_ACK_POLL_MS = 300L
        private const val TEST_TASK_TIMEOUT_SECONDS = 3L
    }
}
