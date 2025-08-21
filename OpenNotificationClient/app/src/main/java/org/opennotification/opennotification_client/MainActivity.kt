package org.opennotification.opennotification_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.ui.screens.MainScreen
import org.opennotification.opennotification_client.ui.screens.SettingsScreen
import org.opennotification.opennotification_client.ui.theme.OpenNotificationClientTheme
import org.opennotification.opennotification_client.ui.components.PermissionDialog
import org.opennotification.opennotification_client.utils.PermissionManager

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private var showPermissionDialog by mutableStateOf(false)
    private var permissionSummary by mutableStateOf(PermissionManager.PermissionSummary(false, false))
    private var currentScreen by mutableStateOf("main") // "main" or "settings"

    // Activity result launcher for notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updatePermissionSummary()
    }

    // Activity result launcher for battery optimization (used when user returns from settings)
    @Suppress("unused")
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Update permission summary after returning from battery optimization settings
        lifecycleScope.launch {
            delay(500) // Small delay to ensure settings are applied
            updatePermissionSummary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionManager = PermissionManager(this)

        // CRITICAL: Always check and request battery optimization permission first
        // This is essential for background service survival
        if (!permissionManager.isBatteryOptimizationIgnored()) {
            android.util.Log.w("MainActivity", "Battery optimization is NOT ignored - app will be killed when swiped away!")
            // Force show permission dialog immediately
            showPermissionDialog = true
        }

        // Start the WebSocket service to handle incoming notifications
        org.opennotification.opennotification_client.service.WebSocketService.startService(this)

        // Set up permission change listener for immediate UI updates
        permissionManager.onPermissionChanged = { newSummary ->
            permissionSummary = newSummary

            // Auto-hide dialog if all permissions are granted
            if (newSummary.allPermissionsGranted && showPermissionDialog) {
                showPermissionDialog = false
            }
        }

        // Initialize permission summary
        updatePermissionSummary()

        setContent {
            OpenNotificationClientTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        "main" -> MainScreen(
                            onNavigateToSettings = { currentScreen = "settings" }
                        )
                        "settings" -> SettingsScreen(
                            onBackClick = { currentScreen = "main" }
                        )
                    }

                    // Show permission dialog if needed (only on main screen)
                    if (showPermissionDialog && currentScreen == "main") {
                        PermissionDialog(
                            permissionSummary = permissionSummary,
                            onNotificationPermissionRequest = {
                                try {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        permissionManager.openNotificationSettings(this@MainActivity)
                                    }
                                } catch (e: Exception) {
                                    // Fallback to opening notification settings manually
                                    permissionManager.openNotificationSettings(this@MainActivity)
                                }
                            },
                            onBatteryOptimizationRequest = {
                                try {
                                    permissionManager.requestIgnoreBatteryOptimization(this@MainActivity)
                                } catch (e: Exception) {
                                    // Fallback to opening battery optimization settings manually
                                    permissionManager.openBatteryOptimizationSettings(this@MainActivity)
                                }
                            },
                            onDismiss = {
                                showPermissionDialog = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update permission summary when returning to the app and trigger callback
        updatePermissionSummary()
        permissionManager.checkAndNotifyPermissionChanges()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PermissionManager.NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                updatePermissionSummary()
            }
        }
    }

    private fun updatePermissionSummary() {
        try {
            permissionSummary = permissionManager.getPermissionSummary()

            // Show permission dialog if permissions are not granted and dialog is not already shown
            if (!permissionSummary.allPermissionsGranted && !showPermissionDialog) {
                showPermissionDialog = true
            }

            // Auto-hide dialog if all permissions are granted
            if (permissionSummary.allPermissionsGranted && showPermissionDialog) {
                showPermissionDialog = false
            }
        } catch (e: Exception) {
            // Handle any errors in permission checking gracefully
            android.util.Log.e("MainActivity", "Error updating permission summary", e)
        }
    }
}
