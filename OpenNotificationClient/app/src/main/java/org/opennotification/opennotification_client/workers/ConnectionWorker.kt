package org.opennotification.opennotification_client.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.delay
import org.opennotification.opennotification_client.network.WebSocketManager
import org.opennotification.opennotification_client.service.WebSocketService
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based fallback for when foreground services are restricted.
 * This ensures critical connection monitoring continues even when the service can't run.
 */
class ConnectionWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ConnectionWorker"
        const val WORK_NAME = "connection_monitoring_work"

        fun scheduleWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ConnectionWorker>(
                15, TimeUnit.MINUTES, // Minimum interval for periodic work
                5, TimeUnit.MINUTES   // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

            Log.i(TAG, "Scheduled periodic connection monitoring work")
        }

        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled connection monitoring work")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Connection monitoring work started")

            // Check if the main service is running
            val serviceRunning = isServiceRunning()

            if (!serviceRunning) {
                Log.w(TAG, "WebSocket service not running - attempting to start")

                try {
                    WebSocketService.startService(applicationContext)
                    delay(2000) // Give service time to start
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service from worker", e)
                }
            }

            // Perform connection health check
            try {
                val webSocketManager = WebSocketManager.getInstance()

                // Retry any error connections
                webSocketManager.retryErrorConnections()

                val allStatuses = webSocketManager.getAllConnectionStatuses()
                val errorCount = allStatuses.values.count {
                    it == org.opennotification.opennotification_client.data.models.ConnectionStatus.ERROR
                }

                if (errorCount > 0) {
                    Log.i(TAG, "Found $errorCount error connections - triggering reconnection")
                    webSocketManager.retryErrorConnections()
                }

                Log.d(TAG, "Connection monitoring work completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during connection health check", e)
            }

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Connection monitoring work failed", e)
            Result.retry()
        }
    }

    private fun isServiceRunning(): Boolean {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == "org.opennotification.opennotification_client.service.WebSocketService" }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }
}
