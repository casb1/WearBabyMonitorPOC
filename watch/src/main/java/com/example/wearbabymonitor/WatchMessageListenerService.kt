package com.example.wearbabymonitor

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchMessageListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.ACK_PATH) return
        val alertId = event.data.decodeToString()
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ACK, alertId)
            .apply()
    }

    companion object {
        const val PREFS = "monitor_state"
        const val KEY_LAST_ACK = "last_ack"
    }
}
