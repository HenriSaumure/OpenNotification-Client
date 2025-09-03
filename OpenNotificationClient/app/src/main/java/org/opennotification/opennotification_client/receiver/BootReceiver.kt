package org.opennotification.opennotification_client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.opennotification.opennotification_client.utils.ConnectionKeepAlive

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot receiver triggered with action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Device boot completed - starting services")
                safeStartService(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageDataString = intent.dataString
                if (packageDataString == null || packageDataString.contains(context.packageName)) {
                    Log.i(TAG, "App updated - restarting services")
                    safeStartService(context)
                }
            }
        }
    }

    private fun safeStartService(context: Context) {
        try {
            org.opennotification.opennotification_client.service.WebSocketService.startService(context)
            ConnectionKeepAlive.startKeepAlive(context)
            Log.i(TAG, "WebSocketService started from BootReceiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocketService", e)
        }
    }
}
