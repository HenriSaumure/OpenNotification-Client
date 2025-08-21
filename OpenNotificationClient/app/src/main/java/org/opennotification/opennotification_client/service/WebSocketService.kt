package org.opennotification.opennotification_client.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
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
            // Start as regular background service - no foreground notification needed
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

    // Flag to track if the service is stopping itself intentionally
    private var isStoppingSelf = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WebSocketService created")

        repository = NotificationRepository(AppDatabase.getDatabase(this))

        // Initialize WebSocketManager with context to load saved server URL
        WebSocketManager.initializeWithContext(this)
        webSocketManager = WebSocketManager.getInstance()

        notificationDisplayManager = org.opennotification.opennotification_client.utils.NotificationDisplayManager(this)

        // Immediately set up notification handler for incoming WebSocket messages
        setupNotificationCallback()

        // Start monitoring active listeners
        monitorActiveListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebSocketService started with intent: ${intent?.extras}")

        // Don't create a separate notification - the WatchdogService handles all notifications
        // This service runs silently in the background

        // Critical: Re-establish the notification callback every time onStartCommand is called
        // This ensures the callback is set even when service is restarted
        setupNotificationCallback()

        // If this is a restart from task removal, log it
        if (intent?.getBooleanExtra("restarted_from_task_removal", false) == true) {
            Log.i(TAG, "Service restarted after task removal - re-establishing WebSocket connections")
        }

        // Return START_STICKY with explicit restart behavior
        return Service.START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        // Only restart if we have active connections AND this wasn't an intentional stop
        val hasActiveConnections = try {
            webSocketManager.hasActiveConnections()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active connections in onDestroy", e)
            false
        }

        // Only restart if we have active connections and we're not stopping intentionally
        if (hasActiveConnections && !isStoppingSelf) {
            Log.d(TAG, "WebSocketService destroyed with active connections - will restart automatically if needed")
        } else {
            Log.d(TAG, "WebSocketService destroyed - no restart needed (stopping self: $isStoppingSelf, active connections: $hasActiveConnections)")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed (swiped away)")

        try {
            // Get current active connections from WebSocketManager
            val hasActiveConnections = webSocketManager.hasActiveConnections()

            if (hasActiveConnections) {
                Log.i(TAG, "Active WebSocket connections found - service will continue running")
                // Service will continue to monitor connections as needed
                // No need for aggressive restart logic here
            } else {
                Log.i(TAG, "No active connections - allowing service to stop naturally")
                isStoppingSelf = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved", e)
        }

        // CRITICAL: NEVER call super.onTaskRemoved()
        // This would allow Android to kill the service immediately
    }

    private fun monitorActiveListeners() {
        serviceScope.launch {
            repository.getActiveListeners().collect { activeListeners ->
                Log.d(TAG, "Active listeners count: ${activeListeners.size}")

                if (activeListeners.isNotEmpty()) {
                    // Reset the flag since we have active listeners
                    isStoppingSelf = false

                    // Ensure WebSocket connections are established for active listeners
                    webSocketManager.updateActiveListeners(activeListeners)
                } else {
                    // No active listeners - disconnect all and stop service
                    Log.i(TAG, "No active listeners found - all listeners are stopped")

                    // Disconnect all WebSocket connections
                    webSocketManager.disconnectAll()

                    // Set flag to indicate we're stopping intentionally
                    isStoppingSelf = true

                    // Give a moment for disconnections to complete, then stop the service
                    kotlinx.coroutines.delay(1000)
                    Log.i(TAG, "Stopping WebSocket service - no active listeners")
                    stopSelf()
                }
            }
        }
    }

    private fun setupNotificationCallback() {
        Log.d(TAG, "Setting up notification callback for WebSocket messages")

        // Set up notification handler for incoming WebSocket messages
        webSocketManager.onNotificationReceived = { incomingNotification ->
            serviceScope.launch {
                try {
                    Log.i(TAG, "Received WebSocket notification: ${incomingNotification.title}")

                    // Store the notification in the database
                    repository.insertNotification(incomingNotification)

                    // Show the notification to the user
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
