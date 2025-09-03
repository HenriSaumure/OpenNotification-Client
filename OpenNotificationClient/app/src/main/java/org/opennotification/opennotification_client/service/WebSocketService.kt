package org.opennotification.opennotification_client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.MainActivity
import org.opennotification.opennotification_client.R
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.network.WebSocketManager
import org.opennotification.opennotification_client.repository.NotificationRepository

class WebSocketService : Service() {
    companion object {
        private const val TAG = "WebSocketService"
        private const val NOTIFICATION_ID = 1001
        private const val OLD_CHANNEL_ID = "websocket_service_channel"
        private const val CHANNEL_ID = "background_service_channel"
        private const val CHANNEL_NAME = "Background service"
        private const val ACTION_SHUTDOWN = "org.opennotification.opennotification_client.ACTION_SHUTDOWN"
        private const val BACKGROUND_TEXT = "Running in background"

        fun startService(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            context.stopService(intent)
        }
    }

    private var repository: NotificationRepository? = null
    private lateinit var webSocketManager: WebSocketManager
    private var notificationDisplayManager: org.opennotification.opennotification_client.utils.NotificationDisplayManager? = null
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStoppingSelf = false
    private var isDeviceUnlocked = false

    // Broadcast receiver to detect when device is unlocked
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                Log.i(TAG, "Device unlocked - initializing database components")
                onDeviceUnlocked()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WebSocketService created")

        // Check if device is already unlocked
        isDeviceUnlocked = isDeviceUnlocked()

        // Initialize non-database components first
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Initialize WebSocket manager (doesn't require database)
        WebSocketManager.initializeWithContext(this)
        webSocketManager = WebSocketManager.getInstance()

        // Create notification channel and start foreground
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createServiceNotification())

        if (isDeviceUnlocked) {
            // Device is already unlocked, initialize database components immediately
            initializeDatabaseComponents()
        } else {
            // Device is locked, register receiver and wait for unlock
            Log.i(TAG, "Device is locked - waiting for unlock to initialize database")
            registerUnlockReceiver()
        }

        Log.i(TAG, "WebSocketService started as foreground service")
    }

    private fun isDeviceUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    private fun registerUnlockReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            Log.d(TAG, "Registered unlock receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register unlock receiver", e)
        }
    }

    private fun onDeviceUnlocked() {
        isDeviceUnlocked = true
        initializeDatabaseComponents()

        // Try to unregister the receiver
        try {
            unregisterReceiver(unlockReceiver)
            Log.d(TAG, "Unregistered unlock receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering unlock receiver", e)
        }
    }

    private fun initializeDatabaseComponents() {
        try {
            Log.d(TAG, "Initializing database components")

            // Initialize database and repository
            repository = NotificationRepository(AppDatabase.getDatabase(this))

            // Initialize notification display manager
            notificationDisplayManager = org.opennotification.opennotification_client.utils.NotificationDisplayManager(this)

            // Setup notification handling and monitoring
            setupNotificationCallback()
            monitorActiveListeners()

            Log.i(TAG, "Database components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize database components", e)

            // If database initialization fails, still try to set up basic WebSocket functionality
            setupNotificationCallback()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WebSocketService started with intent: ${intent?.extras}")

        if (intent?.action == ACTION_SHUTDOWN) {
            Log.i(TAG, "Shutdown action received")
            handleShutdown()
            return START_NOT_STICKY
        }

        if (isStoppingSelf) {
            Log.i(TAG, "Shutdown in progress - not restarting")
            return START_NOT_STICKY
        }

        // Ensure notification callback is set up
        setupNotificationCallback()

        if (intent?.getBooleanExtra("restarted_from_task_removal", false) == true) {
            Log.i(TAG, "Service restarted after task removal - re-establishing WebSocket connections")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WebSocketService destroyed")

        // Cancel service scope
        serviceScope.cancel()

        val hasActiveConnections = try {
            webSocketManager.hasActiveConnections()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active connections in onDestroy", e)
            false
        }

        if (hasActiveConnections && !isStoppingSelf) {
            Log.w(TAG, "WebSocketService destroyed with active connections - scheduling restart")
            scheduleServiceRestart()
        } else {
            Log.d(TAG, "WebSocketService destroyed - no restart needed")
        }
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "WebSocketService received memory trim request: $level")

        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure - optimizing for survival")
                System.gc()
                // Keep the notification text consistent
                updateServiceNotification()
            }
        }
    }

    private fun handleShutdown() {
        try {
            isStoppingSelf = true
            Log.i(TAG, "Shutting down WebSocketService and stopping application")

            // Stop keep-alive monitoring
            org.opennotification.opennotification_client.utils.ConnectionKeepAlive.stopKeepAlive(applicationContext)

            // Disconnect all WebSocket connections
            webSocketManager.disconnectAll()

            // Stop any memory pressure monitoring
            try {
                val memoryHandler = org.opennotification.opennotification_client.utils.MemoryPressureHandler(applicationContext)
                memoryHandler.stopProtection()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping memory pressure handler", e)
            }

            // Cancel any pending alarms related to this app
            try {
                val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager

                // Cancel keep-alive alarms
                val keepAliveIntent = Intent(applicationContext, org.opennotification.opennotification_client.utils.KeepAliveReceiver::class.java)
                val keepAlivePendingIntent = PendingIntent.getBroadcast(
                    applicationContext, 1001, keepAliveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(keepAlivePendingIntent)

                // Cancel resurrection alarms
                val resurrectionIntent = Intent(applicationContext, org.opennotification.opennotification_client.utils.ResurrectionReceiver::class.java)
                val resurrectionPendingIntent = PendingIntent.getBroadcast(
                    applicationContext, 2001, resurrectionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(resurrectionPendingIntent)

                // Cancel service restart alarms
                val restartIntent = Intent(applicationContext, WebSocketService::class.java)
                val restartPendingIntent = PendingIntent.getService(
                    applicationContext, 3002, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(restartPendingIntent)

                Log.i(TAG, "Cancelled all pending alarms")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling alarms", e)
            }

            // Stop overlay service if running
            try {
                val overlayServiceIntent = Intent(applicationContext, org.opennotification.opennotification_client.service.FullScreenOverlayService::class.java)
                stopService(overlayServiceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping overlay service", e)
            }

            // Clear all notifications
            try {
                notificationManager.cancelAll()
                Log.i(TAG, "Cleared all notifications")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing notifications", e)
            }

            // Send broadcast to close MainActivity if it's running
            try {
                val closeAppIntent = Intent("org.opennotification.opennotification_client.CLOSE_APP")
                sendBroadcast(closeAppIntent)
                Log.i(TAG, "Sent close app broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending close app broadcast", e)
            }

            // Cancel service scope
            serviceScope.cancel()

            // Stop the service
            stopSelf()

            Log.i(TAG, "WebSocketService shutdown completed - killing process now")
            Log.w(TAG, "========================== TERMINATING APPLICATION PROCESS ==========================")

            // Force terminate the application process immediately to ensure clean shutdown
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
                Log.e(TAG, "Error killing process", e)
                // Fallback: exit the entire application
                kotlin.system.exitProcess(0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
            // Still try to terminate the process even if cleanup fails
            try {
                Log.w(TAG, "========================== FORCE TERMINATING AFTER ERROR ==========================")
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (ex: Exception) {
                kotlin.system.exitProcess(1)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (OLD_CHANNEL_ID != CHANNEL_ID) {
                    notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID)
                    Log.d(TAG, "Deleted old notification channel: $OLD_CHANNEL_ID")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete old notification channel: $OLD_CHANNEL_ID", e)
            }

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running in background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Created notification channel: $CHANNEL_ID")
        }
    }

    private fun createServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shutdownIntent = Intent(this, WebSocketService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            1,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = BACKGROUND_TEXT

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenNotification Service")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_notification, "Shutdown", shutdownPendingIntent)
            .build()
    }

    private fun updateServiceNotification() {
        try {
            val contentText = BACKGROUND_TEXT

            // Create updated notification with fresh data
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val shutdownIntent = Intent(this, WebSocketService::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            val shutdownPendingIntent = PendingIntent.getService(
                this,
                1,
                shutdownIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val updatedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenNotification Service")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_notification, "Shutdown", shutdownPendingIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, updatedNotification)
            Log.d(TAG, "Updated service notification: $contentText")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update service notification", e)
        }
    }

    private fun monitorActiveListeners() {
        serviceScope.launch {
            repository?.getActiveListeners()?.collect { activeListeners ->
                Log.d(TAG, "Active listeners count: ${activeListeners.size}")

                if (activeListeners.isNotEmpty()) {
                    isStoppingSelf = false
                    webSocketManager.updateActiveListeners(activeListeners)
                    updateServiceNotification()
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

                    repository?.insertNotification(incomingNotification)
                    notificationDisplayManager?.showNotification(incomingNotification)

                    Log.i(TAG, "Processed incoming notification: ${incomingNotification.title}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process incoming notification", e)
                }
            }
        }

        Log.d(TAG, "Notification callback established")
    }

    private fun scheduleServiceRestart() {
        try {
            val restartIntent = Intent(applicationContext, WebSocketService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                3002,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            val triggerTime = System.currentTimeMillis() + 5000 // Increased delay for battery

            // Use less aggressive alarm for restart
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )

            Log.i(TAG, "Scheduled service restart in 5 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
        }
    }
}
