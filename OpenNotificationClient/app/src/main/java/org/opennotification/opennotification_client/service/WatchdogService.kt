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
        private const val WATCHDOG_INTERVAL = 2000L
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
    private var isShuttingDown = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchdogService created")

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Set high priority process thread")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set maximum priority", e)
        }

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        memoryPressureHandler = MemoryPressureHandler(this)
        memoryPressureHandler.startProtection()

        org.opennotification.opennotification_client.network.WebSocketManager.initializeWithContext(this)
        webSocketManager = org.opennotification.opennotification_client.network.WebSocketManager.getInstance()

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenNotification::WatchdogWakeLock"
        )
        wakeLock.acquire(2 * 60 * 60 * 1000L)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createConsolidatedNotification())

        Log.i(TAG, "Starting watchdog monitoring")
        startWatchdog()

        org.opennotification.opennotification_client.utils.ConnectionKeepAlive.startKeepAlive(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchdogService started")

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost priority on start command", e)
        }

        if (intent?.action == ACTION_SHUTDOWN) {
            Log.i(TAG, "Shutdown action received")
            handleShutdown()
            return START_NOT_STICKY
        }

        if (isShuttingDown) {
            Log.i(TAG, "Shutdown in progress - not restarting watchdog")
            return START_NOT_STICKY
        }

        if (watchdogJob?.isActive != true) {
            startWatchdog()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        memoryPressureHandler.stopProtection()

        if (!isShuttingDown) {
            Log.w(TAG, "WatchdogService destroyed unexpectedly - scheduling restart")
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
            val database = org.opennotification.opennotification_client.data.database.AppDatabase.getDatabase(applicationContext)
            val repository = org.opennotification.opennotification_client.repository.NotificationRepository(database)

            val hasActiveListeners = try {
                kotlinx.coroutines.runBlocking {
                    repository.getActiveListeners().first().isNotEmpty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking active listeners on task removal", e)
                false
            }

            if (hasActiveListeners && !isShuttingDown) {
                val activeListenerCount = kotlinx.coroutines.runBlocking {
                    repository.getActiveListeners().first().size
                }
                Log.i(TAG, "App swiped away but $activeListenerCount listeners are active - staying alive")

                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not boost priority after task removal", e)
                }

                updateConsolidatedNotification("Running in background")

                if (watchdogJob?.isActive != true) {
                    startWatchdog()
                }
            } else {
                Log.i(TAG, "App swiped away and no active listeners - shutting down")
                isShuttingDown = true
                handleShutdown()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved", e)
            isShuttingDown = true
            handleShutdown()
        }
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "WatchdogService received memory trim request: $level")

        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure detected - taking defensive action")

                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    Log.i(TAG, "Priority boosted to maximum")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not boost priority during critical memory pressure", e)
                }

                System.gc()
                scheduleWatchdogRestart()
                updateConsolidatedNotification("Under memory pressure - maintaining connections")
            }
            else -> {
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
            isShuttingDown = true
            org.opennotification.opennotification_client.utils.ConnectionKeepAlive.stopKeepAlive(applicationContext)
            WebSocketService.stopService(applicationContext)
            webSocketManager.disconnectAll()
            watchdogJob?.cancel()
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
                description = "WebSocket and Watchdog service status"
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

        val shutdownIntent = Intent(this, WatchdogService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            1,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeConnections = webSocketManager.getAllConnectionStatuses()
            .count { it.value == org.opennotification.opennotification_client.data.models.ConnectionStatus.CONNECTED }

        val contentText = if (activeConnections > 0) {
            "WebSocket service active - $activeConnections connections"
        } else {
            "WebSocket service monitoring"
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
            .addAction(R.drawable.ic_notification, "Shutdown", shutdownPendingIntent)
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
                .addAction(R.drawable.ic_notification, "Shutdown", shutdownPendingIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun startWatchdog() {
        Log.i(TAG, "Starting watchdog monitoring")

        watchdogJob = serviceScope.launch {
            while (isActive) {
                try {
                    val isWebSocketServiceRunning = isServiceRunning(WebSocketService::class.java.name)

                    if (!isWebSocketServiceRunning) {
                        if (shouldRestartWebSocketService()) {
                            Log.w(TAG, "WebSocketService killed - restarting it")
                            WebSocketService.startService(applicationContext)
                        } else {
                            Log.d(TAG, "WebSocketService is not running and should not be running")
                        }
                    } else {
                        Log.d(TAG, "WebSocketService is running normally")
                    }

                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    } catch (e: Exception) {

                    }

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
            val database = org.opennotification.opennotification_client.data.database.AppDatabase.getDatabase(applicationContext)
            val repository = org.opennotification.opennotification_client.repository.NotificationRepository(database)
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
            val triggerTime = System.currentTimeMillis() + 1000

            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )

            Log.i(TAG, "Scheduled watchdog restart")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog restart", e)
        }
    }
}
