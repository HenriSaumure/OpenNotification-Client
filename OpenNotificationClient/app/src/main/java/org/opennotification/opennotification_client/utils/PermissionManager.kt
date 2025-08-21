package org.opennotification.opennotification_client.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class PermissionManager(private val context: Context) {
    companion object {
        private const val TAG = "PermissionManager"
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        const val BATTERY_OPTIMIZATION_REQUEST_CODE = 1002
    }

    // Callback for permission changes
    var onPermissionChanged: ((PermissionSummary) -> Unit)? = null

    /**
     * Check if notification permission is granted
     */
    fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // For Android 12 and below, check if notifications are enabled
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Request notification permission
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        } else {
            // For older versions, direct to notification settings
            openNotificationSettings(activity)
        }
    }

    /**
     * Check if app is whitelisted from battery optimization
     */
    fun isBatteryOptimizationIgnored(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            Log.d(TAG, "Battery optimization ignored: $isIgnored for package: ${context.packageName}")
            isIgnored
        } else {
            true // Not applicable for older versions
        }
    }

    /**
     * Request to ignore battery optimization with more aggressive approach
     */
    fun requestIgnoreBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                Log.i(TAG, "Requesting unrestricted battery usage for background service")

                // First try the direct request
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                try {
                    activity.startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE)
                    Log.i(TAG, "Opened battery optimization request dialog")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery optimization request dialog", e)
                    // Fallback to general battery optimization settings
                    openBatteryOptimizationSettings(activity)
                }
            } else {
                Log.d(TAG, "Battery optimization already ignored")
            }
        }
    }

    /**
     * Open notification settings manually
     */
    fun openNotificationSettings(activity: Activity) {
        try {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification settings", e)
        }
    }

    /**
     * Open battery optimization settings manually with enhanced guidance
     */
    fun openBatteryOptimizationSettings(activity: Activity) {
        try {
            Log.i(TAG, "Opening battery optimization settings manually")
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Try to open the specific app battery settings first
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            // Last resort - open general settings
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                activity.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open any settings", e2)
            }
        }
    }

    /**
     * Request background app refresh permissions for newer Android versions
     */
    fun requestBackgroundAppPermissions(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, also check background app restrictions
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                activity.startActivity(intent)
                Log.i(TAG, "Opened app details for background permissions")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings", e)
        }
    }

    /**
     * Check if the app has all necessary background permissions
     */
    fun hasAllBackgroundPermissions(): Boolean {
        val batteryOptimized = isBatteryOptimizationIgnored()
        val notificationEnabled = isNotificationPermissionGranted()

        Log.d(TAG, "Permission check - Battery optimized: $batteryOptimized, Notifications: $notificationEnabled")
        return batteryOptimized && notificationEnabled
    }

    /**
     * Get a summary of all permission states
     */
    fun getPermissionSummary(): PermissionSummary {
        return PermissionSummary(
            notificationPermissionGranted = isNotificationPermissionGranted(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored()
        )
    }

    /**
     * Check and notify if permissions have changed
     */
    fun checkAndNotifyPermissionChanges() {
        val currentSummary = getPermissionSummary()
        onPermissionChanged?.invoke(currentSummary)
    }

    data class PermissionSummary(
        val notificationPermissionGranted: Boolean,
        val batteryOptimizationIgnored: Boolean
    ) {
        val allPermissionsGranted: Boolean
            get() = notificationPermissionGranted && batteryOptimizationIgnored
    }
}
