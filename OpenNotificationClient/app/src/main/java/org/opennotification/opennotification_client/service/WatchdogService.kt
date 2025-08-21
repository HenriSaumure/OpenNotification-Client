package org.opennotification.opennotification_client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.MainActivity
import org.opennotification.opennotification_client.R
import org.opennotification.opennotification_client.utils.MemoryPressureHandler

class WatchdogService : Service() {
    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "websocket_service_channel"
        private const val CHANNEL_NAME = "WebSocket Service"
        private const val WATCHDOG_INTERVAL = 2000L // 2 seconds
        private const val ACTION_SHUTDOWN = "org.opennotification.opennotification_client.ACTION_SHUTDOWN"

        fun startService(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var webSocketManager: org.opennotification.opennotification_client.network.WebSocketManager
    private lateinit var memoryPressureHandler: MemoryPressureHandler
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null

    // Flag to track if shutdown was requested
    private var isShuttingDown = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchdogService created - implementing heavy app resistance")

        // Set highest available process priority to prevent being killed by heavy apps
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Set high priority process thread to resist memory pressure from heavy apps")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set maximum priority - continuing with normal priority", e)
        }

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        // Initialize enhanced memory pressure protection
        memoryPressureHandler = MemoryPressureHandler(this)
        memoryPressureHandler.startProtection()

        // Initialize WebSocketManager with context to load saved server URL
        org.opennotification.opennotification_client.network.WebSocketManager.initializeWithContext(this)
        webSocketManager = org.opennotification.opennotification_client.network.WebSocketManager.getInstance()

        // Acquire wake lock with longer duration to survive heavy app launches
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenNotification::WatchdogWakeLock"
        )
        wakeLock.acquire(2 * 60 * 60 * 1000L) // 2 hours - longer to survive heavy apps

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createConsolidatedNotification())

        Log.i(TAG, "Starting enhanced watchdog monitoring with heavy app resistance")
        startWatchdog()

        // Start battery-efficient keep-alive system
        org.opennotification.opennotification_client.utils.ConnectionKeepAlive.startKeepAlive(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchdogService started - boosting priority against heavy apps")

        // Boost priority on each start command to maintain high priority
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost priority on start command", e)
        }

        // Handle shutdown action
        if (intent?.action == ACTION_SHUTDOWN) {
            Log.i(TAG, "Shutdown action received - stopping all services")
            handleShutdown()
            return START_NOT_STICKY
        }

        // Don't restart watchdog if shutdown was requested
        if (isShuttingDown) {
            Log.i(TAG, "Shutdown in progress - not restarting watchdog")
            return START_NOT_STICKY
        }

        // Restart watchdog if it's not running
        if (watchdogJob?.isActive != true) {
            startWatchdog()
        }

        // Return START_STICKY to ensure the service restarts if killed by heavy apps
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // Stop memory pressure protection
        memoryPressureHandler.stopProtection()

        // Only log error and restart if shutdown wasn't requested
        if (!isShuttingDown) {
            Log.w(TAG, "WatchdogService destroyed unexpectedly (possibly by heavy app) - scheduling restart")
            scheduleWatchdogRestart()
        } else {
            Log.i(TAG, "WatchdogService destroyed as part of shutdown")
        }

        watchdogJob?.cancel()
        serviceScope.cancel()

        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed - checking if services should continue")

        try {
            // Check if we have active listeners before deciding to stay alive
            val database = org.opennotification.opennotification_client.data.database.AppDatabase.getDatabase(applicationContext)
            val repository = org.opennotification.opennotification_client.repository.NotificationRepository(database)

            // Use runBlocking to get a synchronous result instead of launching a coroutine
            val hasActiveListeners = try {
                kotlinx.coroutines.runBlocking {
                    // Use first() instead of collect to get just the first emission
                    repository.getActiveListeners().first().isNotEmpty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking active listeners on task removal", e)
                false // Assume no active listeners if we can't check
            }

            if (hasActiveListeners && !isShuttingDown) {
                val activeListenerCount = kotlinx.coroutines.runBlocking {
                    repository.getActiveListeners().first().size
                }
                Log.i(TAG, "App swiped away but $activeListenerCount listeners are active - staying alive with enhanced priority")

                // Boost priority to maximum to survive memory pressure from heavy apps
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not boost priority after task removal", e)
                }

                // Update notification to show we're running in background
                updateConsolidatedNotification("Running in background - resistant to heavy apps")

                // Ensure we continue monitoring
                if (watchdogJob?.isActive != true) {
                    startWatchdog()
                }
            } else {
                Log.i(TAG, "App swiped away and no active listeners - shutting down")
                // Set shutdown flag and stop all services
                isShuttingDown = true
                handleShutdown()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved", e)
            // If we can't check, shutdown to be safe
            isShuttingDown = true
            handleShutdown()
        }

        // DON'T call super to prevent default behavior
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "WatchdogService received memory trim request: $level - resisting memory pressure from heavy apps")

        // Don't call super - we want to resist being trimmed by heavy apps
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure detected (heavy app running) - taking maximum defensive action")

                // Boost our priority to maximum to resist heavy app pressure
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    Log.i(TAG, "Priority boosted to maximum to resist heavy app memory pressure")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not boost priority during critical memory pressure", e)
                }

                // Force GC to free up any memory we can
                System.gc()

                // Schedule immediate restart in case we get killed by heavy app
                scheduleWatchdogRestart()

                // Update notification to show we're under pressure
                updateConsolidatedNotification("Under memory pressure from heavy app - maintaining connections")
            }
            else -> {
                // For other memory trim levels, still boost priority
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not boost priority during moderate memory pressure", e)
                }
            }
        }
    }

    private fun handleShutdown() {
        try {
            // Set shutdown flag to prevent restarts
            isShuttingDown = true

            // Stop battery-efficient keep-alive system
            org.opennotification.opennotification_client.utils.ConnectionKeepAlive.stopKeepAlive(applicationContext)

            // Stop WebSocket service
            WebSocketService.stopService(applicationContext)

            // Disconnect all WebSocket connections
            webSocketManager.disconnectAll()

            // Stop watchdog
            watchdogJob?.cancel()

            // Stop this service
            stopSelf()

            Log.i(TAG, "All services shut down successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Consolidated WebSocket and Watchdog service status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createConsolidatedNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create shutdown action
        val shutdownIntent = Intent(this, WatchdogService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            1,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Get current status
        val activeConnections = webSocketManager.getAllConnectionStatuses()
            .count { it.value == org.opennotification.opennotification_client.data.models.ConnectionStatus.CONNECTED }

        val contentText = if (activeConnections > 0) {
            "WebSocket service active - $activeConnections connections (heavy app resistant)"
        } else {
            "WebSocket service monitoring (heavy app resistant)"
        }

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
            .addAction(
                R.drawable.ic_notification,
                "Shutdown",
                shutdownPendingIntent
            )
            .build()
    }

    private fun updateConsolidatedNotification(customText: String? = null) {
        try {
            val activeConnections = webSocketManager.getAllConnectionStatuses()
                .count { it.value == org.opennotification.opennotification_client.data.models.ConnectionStatus.CONNECTED }

            val contentText = customText ?: if (activeConnections > 0) {
                "WebSocket service active - $activeConnections connections"
            } else {
                "WebSocket service monitoring (no active connections)"
            }

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Create shutdown action
            val shutdownIntent = Intent(this, WatchdogService::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            val shutdownPendingIntent = PendingIntent.getService(
                this,
                1,
                shutdownIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
                .addAction(
                    R.drawable.ic_notification,
                    "Shutdown",
                    shutdownPendingIntent
                )
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update consolidated notification", e)
        }
    }

    private fun startWatchdog() {
        Log.i(TAG, "Starting enhanced watchdog monitoring with heavy app resistance")

        watchdogJob = serviceScope.launch {
            while (isActive) {
                try {
                    // Check if WebSocketService is running
                    val isWebSocketServiceRunning = isServiceRunning(WebSocketService::class.java.name)

                    if (!isWebSocketServiceRunning) {
                        // Check if we should restart the WebSocket service
                        if (shouldRestartWebSocketService()) {
                            Log.w(TAG, "WebSocketService killed (possibly by heavy app) - restarting it")
                            WebSocketService.startService(applicationContext)
                        } else {
                            Log.d(TAG, "WebSocketService is not running and should not be running")
                        }
                    } else {
                        Log.d(TAG, "WebSocketService is running normally")
                    }

                    // Periodically boost priority to maintain high priority against heavy apps
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (e: Exception) {
                        // Ignore - priority boosting is best effort
                    }

                    // Update the consolidated notification with current status
                    updateConsolidatedNotification()

                } catch (e: Exception) {
                    Log.e(TAG, "Error in watchdog monitoring", e)
                }

                delay(WATCHDOG_INTERVAL)
            }
        }
    }

    private fun isServiceRunning(serviceName: String): Boolean {
        return try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == serviceName }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }

    private suspend fun shouldRestartWebSocketService(): Boolean {
        return try {
            // Check if there are active listeners that need WebSocket connections
            val database = org.opennotification.opennotification_client.data.database.AppDatabase.getDatabase(applicationContext)
            val repository = org.opennotification.opennotification_client.repository.NotificationRepository(database)

            // Use first() to get just the first emission instead of collect
            repository.getActiveListeners().first().isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for active listeners", e)
            false
        }
    }

    private fun scheduleWatchdogRestart() {
        try {
            val restartIntent = Intent(applicationContext, WatchdogService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                3001,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            val triggerTime = System.currentTimeMillis() + 1000 // 1 second for faster recovery

            // Use the most aggressive alarm type to ensure restart even when heavy apps are running
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )

            Log.i(TAG, "Scheduled high-priority watchdog restart to counter heavy app interference")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog restart", e)
        }
    }
}
