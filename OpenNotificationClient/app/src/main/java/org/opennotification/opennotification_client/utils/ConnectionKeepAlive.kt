package org.opennotification.opennotification_client.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.network.WebSocketManager

class ConnectionKeepAlive {
    companion object {
        private const val TAG = "ConnectionKeepAlive"
        // Increased from 19995ms to 5 minutes for better battery life
        private const val KEEP_ALIVE_INTERVAL = 20000L // 20 seconds
        const val ACTION_KEEP_ALIVE = "org.opennotification.opennotification_client.KEEP_ALIVE"

        private var isKeepAliveScheduled = false

        fun startKeepAlive(context: Context) {
            if (isKeepAliveScheduled) {
                Log.d(TAG, "Keep-alive already scheduled")
                return
            }

            Log.i(TAG, "Starting keep-alive alarm system")
            scheduleNextKeepAlive(context)
            isKeepAliveScheduled = true
        }

        fun stopKeepAlive(context: Context) {
            Log.i(TAG, "Stopping keep-alive alarm system")
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getKeepAlivePendingIntent(context)
            alarmManager.cancel(pendingIntent)
            isKeepAliveScheduled = false
        }

        fun scheduleNextKeepAlive(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getKeepAlivePendingIntent(context)

            val nextWakeTime = System.currentTimeMillis() + KEEP_ALIVE_INTERVAL

            try {
                // Use less aggressive alarm for better battery life
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextWakeTime,
                    pendingIntent
                )

                Log.d(TAG, "Scheduled keep-alive alarm in ${KEEP_ALIVE_INTERVAL}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule keep-alive alarm", e)
            }
        }

        private fun getKeepAlivePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            return PendingIntent.getBroadcast(context, 1001, intent, flags)
        }
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "KeepAliveReceiver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ConnectionKeepAlive.ACTION_KEEP_ALIVE -> {
                Log.d(TAG, "Keep-alive alarm triggered")
                handleKeepAlive(context)
            }
        }
    }

    private fun handleKeepAlive(context: Context) {
        scope.launch {
            try {
                val webSocketManager = WebSocketManager.getInstance()

                // Only perform keep-alive if we have active connections
                if (webSocketManager.hasActiveConnections()) {
                    Log.d(TAG, "Performing keep-alive check")
                    webSocketManager.retryErrorConnections()
                    ConnectionKeepAlive.scheduleNextKeepAlive(context)
                } else {
                    Log.i(TAG, "No active connections - stopping keep-alive timer")
                    ConnectionKeepAlive.stopKeepAlive(context)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in keep-alive handler", e)
                // Still reschedule to maintain connection monitoring
                try {
                    val webSocketManager = WebSocketManager.getInstance()
                    if (webSocketManager.hasActiveConnections()) {
                        ConnectionKeepAlive.scheduleNextKeepAlive(context)
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to check connections for rescheduling", ex)
                }
            }
        }
    }
}
