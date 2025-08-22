package org.opennotification.opennotification_client.utils

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.service.WatchdogService

class MemoryPressureHandler(private val context: Context) : ComponentCallbacks2 {
    companion object {
        private const val TAG = "MemoryPressureHandler"
        private const val ACTION_RESURRECTION = "org.opennotification.opennotification_client.RESURRECTION"
        private const val RESURRECTION_DELAY = 10000L
        private const val FOREGROUND_APP_ADJ = 0
        private const val VISIBLE_APP_ADJ = 100
        private const val PERCEPTIBLE_APP_ADJ = 200
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRegistered = false

    fun startProtection() {
        if (!isRegistered) {
            context.registerComponentCallbacks(this)
            registerResurrectionReceiver()
            schedulePeriodicResurrection()
            setHighPriorityOomAdj()
            isRegistered = true
            Log.i(TAG, "Memory pressure protection started")
        }
    }

    fun stopProtection() {
        if (isRegistered) {
            try {
                context.unregisterComponentCallbacks(this)
                unregisterResurrectionReceiver()
                cancelPeriodicResurrection()
                isRegistered = false
                Log.i(TAG, "Memory pressure protection stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping memory pressure protection", e)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "Memory trim requested with level: $level")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure detected - taking defensive actions")
                handleCriticalMemoryPressure()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.w(TAG, "Moderate memory pressure detected - optimizing")
                handleModerateMemoryPressure()
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "Background memory pressure - preparing for potential termination")
                prepareForTermination()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // Required implementation - no action needed
    }

    override fun onLowMemory() {
        Log.w(TAG, "System low memory warning received")
        handleCriticalMemoryPressure()
    }

    private fun handleCriticalMemoryPressure() {
        scope.launch {
            try {
                Log.w(TAG, "Handling critical memory pressure")

                // Schedule immediate resurrection in case we're killed
                scheduleImmediateResurrection()

                // Force garbage collection
                System.gc()

                // Reduce memory footprint by disconnecting idle connections
                val webSocketManager = org.opennotification.opennotification_client.network.WebSocketManager.getInstance()
                val errorConnections = webSocketManager.getErrorConnections()
                if (errorConnections.isNotEmpty()) {
                    Log.i(TAG, "Disconnecting ${errorConnections.size} error connections to free memory")
                    errorConnections.forEach { guid ->
                        webSocketManager.disconnectFromGuid(guid)
                    }
                }

                // Increase service priority
                setHighPriorityOomAdj()

                Log.i(TAG, "Critical memory pressure handling completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling critical memory pressure", e)
            }
        }
    }

    private fun handleModerateMemoryPressure() {
        scope.launch {
            try {
                Log.w(TAG, "Handling moderate memory pressure")

                // Force garbage collection
                System.gc()

                // Schedule resurrection as precaution
                scheduleImmediateResurrection()

                Log.i(TAG, "Moderate memory pressure handling completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling moderate memory pressure", e)
            }
        }
    }

    private fun prepareForTermination() {
        scope.launch {
            try {
                Log.w(TAG, "Preparing for potential termination")

                // Schedule resurrection
                scheduleImmediateResurrection()

                // Ensure watchdog service is running with highest priority
                WatchdogService.startService(context)

                // Start keep-alive system if not running
                ConnectionKeepAlive.startKeepAlive(context)

                Log.i(TAG, "Termination preparation completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing for termination", e)
            }
        }
    }

    private fun setHighPriorityOomAdj() {
        try {
            // Try to set process priority to foreground
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)

            // Get activity manager and try to move to foreground importance
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // Log current memory info
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            Log.i(TAG, "Memory info - Available: ${memoryInfo.availMem / 1024 / 1024}MB, " +
                    "Total: ${memoryInfo.totalMem / 1024 / 1024}MB, " +
                    "Low memory: ${memoryInfo.lowMemory}")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting high priority OOM adjustment", e)
        }
    }

    private fun scheduleImmediateResurrection() {
        scheduleResurrection(2000L) // 2 seconds
    }

    private fun schedulePeriodicResurrection() {
        scheduleResurrection(RESURRECTION_DELAY)
    }

    private fun scheduleResurrection(delay: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ResurrectionReceiver::class.java).apply {
                action = ACTION_RESURRECTION
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + delay

            // Use the most aggressive alarm type available
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            }

            Log.i(TAG, "Resurrection alarm scheduled for ${delay}ms from now")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling resurrection", e)
        }
    }

    private fun cancelPeriodicResurrection() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ResurrectionReceiver::class.java).apply {
                action = ACTION_RESURRECTION
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            Log.i(TAG, "Resurrection alarm cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling resurrection", e)
        }
    }

    private lateinit var resurrectionReceiver: ResurrectionReceiver

    private fun registerResurrectionReceiver() {
        resurrectionReceiver = ResurrectionReceiver()
        val filter = android.content.IntentFilter(ACTION_RESURRECTION)
        context.registerReceiver(resurrectionReceiver, filter)
        Log.i(TAG, "Resurrection receiver registered")
    }

    private fun unregisterResurrectionReceiver() {
        try {
            context.unregisterReceiver(resurrectionReceiver)
            Log.i(TAG, "Resurrection receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering resurrection receiver", e)
        }
    }

    inner class ResurrectionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESURRECTION) {
                Log.w(TAG, "Resurrection alarm triggered - checking if services need restart")

                scope.launch {
                    try {
                        // Check if watchdog service is running
                        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        @Suppress("DEPRECATION")
                        val services = activityManager.getRunningServices(Integer.MAX_VALUE)
                        val watchdogRunning = services.any {
                            it.service.className == "org.opennotification.opennotification_client.service.WatchdogService"
                        }

                        if (!watchdogRunning) {
                            Log.w(TAG, "Watchdog service not running - restarting it")
                            WatchdogService.startService(context)

                            // Also restart keep-alive
                            ConnectionKeepAlive.startKeepAlive(context)
                        } else {
                            Log.d(TAG, "Watchdog service is running normally")
                        }

                        // Schedule next resurrection check
                        schedulePeriodicResurrection()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error in resurrection receiver", e)
                    }
                }
            }
        }
    }
}
