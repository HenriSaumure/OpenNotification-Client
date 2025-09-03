package org.opennotification.opennotification_client

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
    private var currentScreen by mutableStateOf("main")

    // Broadcast receiver to handle app close signal
    private val closeAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "org.opennotification.opennotification_client.CLOSE_APP") {
                android.util.Log.i("MainActivity", "Received close app broadcast - finishing activity")
                finish()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionSummary() }

    @Suppress("unused")
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        lifecycleScope.launch {
            delay(500)
            updatePermissionSummary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionManager = PermissionManager(this)

        val initialPermissionSummary = permissionManager.getPermissionSummary()

        if (!initialPermissionSummary.allPermissionsGranted &&
            !permissionManager.isBatteryOptimizationIgnored() &&
            permissionManager.shouldShowPermissionDialog()) {
            android.util.Log.w("MainActivity", "Battery optimization is NOT ignored - app will be killed when swiped away!")
            showPermissionDialog = true
        }

        org.opennotification.opennotification_client.service.WebSocketService.startService(this)
        permissionManager.onPermissionChanged = { newSummary ->
            permissionSummary = newSummary
            if (newSummary.allPermissionsGranted && showPermissionDialog) {
                showPermissionDialog = false
            }
        }
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
                                    permissionManager.openNotificationSettings(this@MainActivity)
                                }
                            },
                            onBatteryOptimizationRequest = {
                                try {
                                    permissionManager.requestIgnoreBatteryOptimization(this@MainActivity)
                                } catch (e: Exception) {
                                    permissionManager.openBatteryOptimizationSettings(this@MainActivity)
                                }
                            },
                            onOverlayPermissionRequest = {
                                try {
                                    permissionManager.requestOverlayPermission(this@MainActivity)
                                } catch (e: Exception) {
                                    permissionManager.openAppSettings(this@MainActivity)
                                }
                            },
                            onDismiss = {
                                showPermissionDialog = false
                            },
                            onDontShowAgain = {
                                permissionManager.setDontShowPermissionDialog()
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
        if (requestCode == PermissionManager.NOTIFICATION_PERMISSION_REQUEST_CODE) {
            updatePermissionSummary()
        }
    }

    private fun updatePermissionSummary() {
        try {
            permissionSummary = permissionManager.getPermissionSummary()

            if (!permissionSummary.allPermissionsGranted &&
                !showPermissionDialog &&
                permissionManager.shouldShowPermissionDialog()) {
                showPermissionDialog = true
            }

            if (permissionSummary.allPermissionsGranted && showPermissionDialog) {
                showPermissionDialog = false
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error updating permission summary", e)
        }
    }

    // Register the broadcast receiver
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter("org.opennotification.opennotification_client.CLOSE_APP")

        // Use RECEIVER_NOT_EXPORTED since this is an internal app broadcast
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeAppReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeAppReceiver, intentFilter)
        }
    }

    // Unregister the broadcast receiver
    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(closeAppReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered, ignore
        }
    }
}
