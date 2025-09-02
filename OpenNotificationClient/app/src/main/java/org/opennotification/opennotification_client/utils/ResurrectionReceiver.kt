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
import org.opennotification.opennotification_client.service.WatchdogService

/**
 * Standalone ResurrectionReceiver that can be instantiated by the Android system.
 * This receiver handles resurrection alarms to restart services if they're killed.
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


            val pendingResult = goAsync() // goAsync to handle long running operations

            scope.launch {
                try {
                    
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    @Suppress("DEPRECATION")
                    val services = activityManager.getRunningServices(Integer.MAX_VALUE)
                    val watchdogRunning = services.any {
                        it.service.className == "org.opennotification.opennotification_client.service.WatchdogService"
                    }

                    if (!watchdogRunning) {
                        Log.w(TAG, "Watchdog service not running - restarting it")

                        try {
                            WatchdogService.startService(context)
                            ConnectionKeepAlive.startKeepAlive(context)
                            Log.i(TAG, "Services restarted successfully from resurrection")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart services from resurrection", e)
                        }
                    } else {
                        Log.d(TAG, "Watchdog service is running normally")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in resurrection receiver", e)
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            Log.d(TAG, "Received unexpected action: ${intent.action}")
        }
    }
}
