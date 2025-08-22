package org.opennotification.opennotification_client.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.network.WebSocketManager
import org.opennotification.opennotification_client.repository.NotificationRepository

class WebSocketService : Service() {
    companion object {
        private const val TAG = "WebSocketService"

        fun startService(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var repository: NotificationRepository
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var notificationDisplayManager: org.opennotification.opennotification_client.utils.NotificationDisplayManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStoppingSelf = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WebSocketService created")

        repository = NotificationRepository(AppDatabase.getDatabase(this))

        WebSocketManager.initializeWithContext(this)
        webSocketManager = WebSocketManager.getInstance()

        notificationDisplayManager = org.opennotification.opennotification_client.utils.NotificationDisplayManager(this)

        setupNotificationCallback()
        monitorActiveListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebSocketService started with intent: ${intent?.extras}")

        setupNotificationCallback()

        if (intent?.getBooleanExtra("restarted_from_task_removal", false) == true) {
            Log.i(TAG, "Service restarted after task removal - re-establishing WebSocket connections")
        }

        return Service.START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        val hasActiveConnections = try {
            webSocketManager.hasActiveConnections()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active connections in onDestroy", e)
            false
        }

        if (hasActiveConnections && !isStoppingSelf) {
            Log.d(TAG, "WebSocketService destroyed with active connections - will restart automatically if needed")
        } else {
            Log.d(TAG, "WebSocketService destroyed - no restart needed (stopping self: $isStoppingSelf, active connections: $hasActiveConnections)")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed (swiped away)")

        try {
            val hasActiveConnections = webSocketManager.hasActiveConnections()

            if (hasActiveConnections) {
                Log.i(TAG, "Active WebSocket connections found - service will continue running")
            } else {
                Log.i(TAG, "No active connections - allowing service to stop naturally")
                isStoppingSelf = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved", e)
        }
    }

    private fun monitorActiveListeners() {
        serviceScope.launch {
            repository.getActiveListeners().collect { activeListeners ->
                Log.d(TAG, "Active listeners count: ${activeListeners.size}")

                if (activeListeners.isNotEmpty()) {
                    isStoppingSelf = false
                    webSocketManager.updateActiveListeners(activeListeners)
                } else {
                    Log.i(TAG, "No active listeners found - all listeners are stopped")

                    webSocketManager.disconnectAll()
                    isStoppingSelf = true

                    kotlinx.coroutines.delay(1000)
                    Log.i(TAG, "Stopping WebSocket service - no active listeners")
                    stopSelf()
                }
            }
        }
    }

    private fun setupNotificationCallback() {
        Log.d(TAG, "Setting up notification callback for WebSocket messages")

        webSocketManager.onNotificationReceived = { incomingNotification ->
            serviceScope.launch {
                try {
                    Log.i(TAG, "Received WebSocket notification: ${incomingNotification.title}")

                    repository.insertNotification(incomingNotification)
                    notificationDisplayManager.showNotification(incomingNotification)

                    Log.i(TAG, "Processed incoming notification: ${incomingNotification.title}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process incoming notification", e)
                }
            }
        }

        Log.d(TAG, "Notification callback established")
    }
}
