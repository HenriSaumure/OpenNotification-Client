package org.opennotification.opennotification_client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.utils.ConnectionKeepAlive

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
            Log.i(TAG, "Starting WebSocketService and connection monitoring")

            // Start the WebSocket service with fallback handling
            try {
                org.opennotification.opennotification_client.service.WebSocketService.startService(context)
                Log.i(TAG, "WebSocketService started successfully from BootReceiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebSocketService - scheduling WorkManager fallback", e)
                // If service fails to start, schedule WorkManager as fallback
                try {
                    org.opennotification.opennotification_client.workers.ConnectionWorker.scheduleWork(context)
                    Log.i(TAG, "WorkManager fallback scheduled for connection monitoring")
                } catch (workerException: Exception) {
                    Log.e(TAG, "Failed to schedule WorkManager fallback", workerException)
                }
            }

            // Start the keep-alive system
            ConnectionKeepAlive.startKeepAlive(context)

            // Give the service a moment to initialize, then check connections
            scope.launch {
                delay(5000) // Wait 5 seconds for service to fully initialize

                try {
                    val webSocketManager = org.opennotification.opennotification_client.network.WebSocketManager.getInstance()
                    Log.i(TAG, "Post-boot connection check - forcing reconnection of any failed connections")
                    webSocketManager.retryErrorConnections()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during post-boot connection check", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in BootReceiver - attempting WorkManager fallback", e)
            // Last resort: try WorkManager
            try {
                org.opennotification.opennotification_client.workers.ConnectionWorker.scheduleWork(context)
                Log.w(TAG, "Emergency WorkManager fallback activated")
            } catch (fallbackException: Exception) {
                Log.e(TAG, "All fallback mechanisms failed", fallbackException)
            }
        }
    }
}
