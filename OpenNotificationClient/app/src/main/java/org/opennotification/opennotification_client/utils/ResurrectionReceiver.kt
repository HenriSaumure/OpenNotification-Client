package org.opennotification.opennotification_client.utils

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Simplified ResurrectionReceiver that only responds to alarms.
 * Removed complex alarm scheduling to improve battery life.
 */
class ResurrectionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ResurrectionReceiver"
        private const val ACTION_RESURRECTION = "org.opennotification.opennotification_client.RESURRECTION"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESURRECTION) {
            Log.w(TAG, "Resurrection alarm triggered - checking if services need restart")

            val pendingResult = goAsync()

            scope.launch {
                try {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    @Suppress("DEPRECATION")
                    val services = activityManager.getRunningServices(Integer.MAX_VALUE)
                    val webSocketServiceRunning = services.any {
                        it.service.className == "org.opennotification.opennotification_client.service.WebSocketService"
                    }

                    if (!webSocketServiceRunning) {
                        Log.w(TAG, "WebSocketService not running - restarting it")

                        try {
                            org.opennotification.opennotification_client.service.WebSocketService.startService(context)
                            ConnectionKeepAlive.startKeepAlive(context)
                            Log.i(TAG, "Services restarted successfully from resurrection")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart services from resurrection", e)
                        }
                    } else {
                        Log.d(TAG, "WebSocketService is running normally")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in resurrection receiver", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
