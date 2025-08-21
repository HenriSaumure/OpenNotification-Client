package org.opennotification.opennotification_client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.opennotification.opennotification_client.service.WebSocketService

class ServiceRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Service restart receiver triggered")

        try {
            // Restart the WebSocket service
            WebSocketService.startService(context)
            Log.i(TAG, "WebSocket service restarted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart WebSocket service", e)
        }
    }
}
