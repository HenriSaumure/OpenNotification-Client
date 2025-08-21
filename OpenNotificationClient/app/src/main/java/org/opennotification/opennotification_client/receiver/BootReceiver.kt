package org.opennotification.opennotification_client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.repository.NotificationRepository
import org.opennotification.opennotification_client.service.WebSocketService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "Device booted or app updated, starting watchdog service")

                try {
                    // Always start the watchdog service on boot - it will handle monitoring
                    org.opennotification.opennotification_client.service.WatchdogService.startService(context)
                    Log.i(TAG, "Watchdog service started after boot")

                    // Also check if we should start WebSocket service for active listeners
                    CoroutineScope(Dispatchers.IO).launch {
                        val database = AppDatabase.getDatabase(context)
                        val repository = NotificationRepository(database)

                        // Get active listeners from database
                        repository.getActiveListeners().collect { activeListeners ->
                            if (activeListeners.isNotEmpty()) {
                                Log.i(TAG, "Found ${activeListeners.size} active listeners, starting WebSocket service after boot")
                                WebSocketService.startService(context)
                            } else {
                                Log.i(TAG, "No active listeners found, watchdog will monitor for changes")
                            }
                            // Only collect the first emission and then stop
                            return@collect
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start services after boot", e)
                }
            }
        }
    }
}
