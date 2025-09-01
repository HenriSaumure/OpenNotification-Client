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
import org.opennotification.opennotification_client.service.WatchdogService
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
                startServicesAfterBoot(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.dataString
                if (packageName?.contains(context.packageName) == true) {
                    Log.i(TAG, "App updated - restarting services")
                    startServicesAfterBoot(context)
                }
            }
        }
    }

    private fun startServicesAfterBoot(context: Context) {
        scope.launch {
            try {
                delay(5000)
                Log.i(TAG, "Starting watchdog service after boot")
                WatchdogService.startService(context)
                ConnectionKeepAlive.startKeepAlive(context)
                Log.i(TAG, "All services started successfully after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting services after boot", e)
            }
        }
    }
}