package org.opennotification.opennotification_client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import org.opennotification.opennotification_client.service.WatchdogService
import org.opennotification.opennotification_client.utils.ConnectionKeepAlive

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot receiver triggered with action: $action")

        val pendingResult = goAsync() // goAsync to handle long running operations
        val appContext = context.applicationContext


        Thread { // create a background thread
            var wakeLock: PowerManager.WakeLock? = null
            try {


                try { // Acquire a temporary wakelock to ensure the service starts
                    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenNotification::BootReceiver"
                    )
                    wakeLock.acquire(10_000)
                } catch (e: Exception) {
                    Log.w(TAG, "WakeLock unavailable: ${e.message}", e)
                }

                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED,
                    "android.intent.action.QUICKBOOT_POWERON" -> {
                        Log.i(TAG, "Device boot completed - starting services")
                        safeStartWatchdog(appContext)
                    }
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        val packageDataString = intent.dataString

                        if (packageDataString == null || packageDataString.contains(appContext.packageName)) {
                            Log.i(TAG, "App updated - restarting services")
                            safeStartWatchdog(appContext)
                        }
                    }
                    else -> {
                        // no action needed for other intents
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in boot receiver processing", e)
            } finally {
                // Clean up wakelock
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing wakelock", e)
                }

                pendingResult.finish() // always finish the pending result
            }
        }.start()
    }

    private fun safeStartWatchdog(context: Context) {
        try {
            WatchdogService.startService(context)
            ConnectionKeepAlive.startKeepAlive(context)

            Log.i(TAG, "WatchdogService start requested from BootReceiver")
        } catch (ise: IllegalStateException) { // this can happen if the app is in the background and can't start foreground services
            Log.w(TAG, "IllegalStateException starting foreground service, scheduling fallback", ise)
            scheduleFallbackStart(context)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting WatchdogService", e)
        }
    }

    private fun scheduleFallbackStart(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, WatchdogService::class.java)
            val pendingIntent = android.app.PendingIntent.getService(
                context,
                9001,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + 3000
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )

            Log.i(TAG, "Scheduled fallback watchdog start in 3 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule fallback start", e)
        }
    }
}
