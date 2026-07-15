package com.example.wearbabymonitor

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var startAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.create(this)

        status = TextView(this).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val enable = Button(this).apply {
            text = "ENABLE RECEIVER"
            setOnClickListener { enableReceiverWithPermission() }
        }
        val disable = Button(this).apply {
            text = "DISABLE RECEIVER"
            setOnClickListener { disableReceiver() }
        }
        val test = Button(this).apply {
            text = "TEST PHONE ALARM"
            setOnClickListener { testPhoneAlarmWithPermission() }
        }
        val settings = Button(this).apply {
            text = "PHONE ALERT SETTINGS"
            setOnClickListener { openAlertSettings() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(status)
            addView(enable)
            addView(disable)
            addView(test)
            addView(settings)
        })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun enableReceiverWithPermission() {
        if (needsNotificationPermission()) {
            startAfterPermission = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        } else {
            enableReceiver()
        }
    }

    private fun enableReceiver() {
        getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, true)
            .putLong(BabyMonitorListenerService.KEY_LAST_HEARTBEAT, 0L)
            .putBoolean(BabyMonitorListenerService.KEY_WATCH_MONITORING, false)
            .apply()
        ContextCompat.startForegroundService(
            this,
            Intent(this, PhoneWatchdogService::class.java)
        )
        refreshStatus()
    }

    private fun disableReceiver() {
        getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, false)
            .apply()
        stopService(Intent(this, PhoneWatchdogService::class.java))
        getSystemService(NotificationManager::class.java).apply {
            cancel(PhoneWatchdogService.ONGOING_ID)
            cancel(PhoneWatchdogService.DISCONNECT_ID)
            cancel(BabyMonitorListenerService.MONITORING_STOPPED_NOTIFICATION_ID)
        }
        refreshStatus()
    }

    private fun testPhoneAlarmWithPermission() {
        if (needsNotificationPermission()) {
            startAfterPermission = false
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
            return
        }
        showLocalTestAlert()
    }

    private fun showLocalTestAlert() {
        NotificationChannels.create(this)
        getSystemService(NotificationManager::class.java).notify(
            LOCAL_TEST_NOTIFICATION_ID,
            NotificationCompat.Builder(this, BabyMonitorListenerService.ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Phone alarm test")
                .setContentText("The phone notification channel is working.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun openAlertSettings() {
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, BabyMonitorListenerService.ALERT_CHANNEL_ID)
            }
        )
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(BabyMonitorListenerService.PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(BabyMonitorListenerService.KEY_RECEIVER_ENABLED, false)
        val monitoring = prefs.getBoolean(BabyMonitorListenerService.KEY_WATCH_MONITORING, false)
        val battery = prefs.getInt(BabyMonitorListenerService.KEY_WATCH_BATTERY, -1)
        status.text = when {
            !enabled -> "Receiver disabled"
            monitoring && battery >= 0 -> "Receiver enabled\nWatch monitoring • $battery%"
            monitoring -> "Receiver enabled\nWatch monitoring"
            else -> "Receiver enabled\nWaiting for watch monitoring"
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            status.text = "Notification permission is required"
            return
        }
        if (startAfterPermission) enableReceiver() else showLocalTestAlert()
        startAfterPermission = false
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
        private const val LOCAL_TEST_NOTIFICATION_ID = 999
    }
}
