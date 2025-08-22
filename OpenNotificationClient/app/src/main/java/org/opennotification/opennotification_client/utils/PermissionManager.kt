package org.opennotification.opennotification_client.utils

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
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
        const val FULL_SCREEN_INTENT_REQUEST_CODE = 1003
    }

    var onPermissionChanged: ((PermissionSummary) -> Unit)? = null

    fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun isFullScreenIntentPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.canUseFullScreenIntent()
        } else {
            true // Not required for older versions
        }
    }

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        } else {
            openNotificationSettings(activity)
        }
    }

    fun requestFullScreenIntentPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                activity.startActivityForResult(intent, FULL_SCREEN_INTENT_REQUEST_CODE)
                Log.i(TAG, "Opened full-screen intent permission settings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open full-screen intent settings", e)
                // Fallback to general app settings
                openNotificationSettings(activity)
            }
        }
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            Log.d(TAG, "Battery optimization ignored: $isIgnored for package: ${context.packageName}")
            isIgnored
        } else {
            true
        }
    }

    fun requestIgnoreBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                Log.i(TAG, "Requesting unrestricted battery usage for background service")

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

    fun openBatteryOptimizationSettings(activity: Activity) {
        try {
            Log.i(TAG, "Opening battery optimization settings manually")
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                activity.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open any settings", e2)
            }
        }
    }

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

    fun hasAllBackgroundPermissions(): Boolean {
        val batteryOptimized = isBatteryOptimizationIgnored()
        val notificationEnabled = isNotificationPermissionGranted()
        val fullScreenIntentEnabled = isFullScreenIntentPermissionGranted()

        Log.d(TAG, "Permission check - Battery optimized: $batteryOptimized, Notifications: $notificationEnabled, Full-screen intent: $fullScreenIntentEnabled")
        return batteryOptimized && notificationEnabled && fullScreenIntentEnabled
    }

    fun getPermissionSummary(): PermissionSummary {
        return PermissionSummary(
            notificationPermissionGranted = isNotificationPermissionGranted(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
            fullScreenIntentPermissionGranted = isFullScreenIntentPermissionGranted()
        )
    }

    fun checkAndNotifyPermissionChanges() {
        val currentSummary = getPermissionSummary()
        onPermissionChanged?.invoke(currentSummary)
    }

    data class PermissionSummary(
        val notificationPermissionGranted: Boolean,
        val batteryOptimizationIgnored: Boolean,
        val fullScreenIntentPermissionGranted: Boolean = true
    ) {
        val allPermissionsGranted: Boolean
            get() = notificationPermissionGranted && batteryOptimizationIgnored && fullScreenIntentPermissionGranted
    }
}
