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
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import org.opennotification.opennotification_client.ui.screens.NotificationHistoryScreen
import org.opennotification.opennotification_client.ui.screens.NotificationDetailScreen
import org.opennotification.opennotification_client.ui.theme.OpenNotificationClientTheme
import org.opennotification.opennotification_client.ui.components.PermissionDialog
import org.opennotification.opennotification_client.utils.PermissionManager
import org.opennotification.opennotification_client.data.models.Notification

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private var showPermissionDialog by mutableStateOf(false)
    private var permissionSummary by mutableStateOf(PermissionManager.PermissionSummary(false, false))
    private var currentScreen by mutableStateOf("main")
    private var selectedNotification by mutableStateOf<Notification?>(null)

    // Navigation history and direction tracking
    private var navigationStack by mutableStateOf(listOf("main"))
    private var isNavigatingBack by mutableStateOf(false)

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

    @OptIn(ExperimentalAnimationApi::class)
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
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            if (isNavigatingBack) {
                                // Back navigation: slide in from left, slide out to right
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> -fullWidth },
                                    animationSpec = tween(durationMillis = 300)
                                ) + fadeIn() togetherWith slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(durationMillis = 300)
                                ) + fadeOut()
                            } else {
                                // Forward navigation: slide in from right, slide out to left
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(durationMillis = 300)
                                ) + fadeIn() togetherWith slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> -fullWidth },
                                    animationSpec = tween(durationMillis = 300)
                                ) + fadeOut()
                            }
                        }
                    ) { screen ->
                        when (screen) {
                            "main" -> MainScreen(
                                onNavigateToSettings = {
                                    navigateForward("settings")
                                },
                                onNavigateToNotificationHistory = {
                                    navigateForward("notification_history")
                                }
                            )
                            "settings" -> SettingsScreen(
                                onBackClick = {
                                    navigateBack()
                                }
                            )
                            "notification_history" -> NotificationHistoryScreen(
                                onBackClick = {
                                    navigateBack()
                                },
                                onNavigateToDetail = { notification ->
                                    selectedNotification = notification
                                    navigateForward("notification_detail")
                                }
                            )
                            "notification_detail" -> selectedNotification?.let { notification ->
                                NotificationDetailScreen(
                                    notification = notification,
                                    onBackClick = {
                                        navigateBack()
                                    }
                                )
                            }
                        }
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

    private fun navigateForward(destination: String) {
        isNavigatingBack = false
        navigationStack = navigationStack + destination
        currentScreen = destination
    }

    private fun navigateBack() {
        if (navigationStack.size > 1) {
            isNavigatingBack = true
            navigationStack = navigationStack.dropLast(1)
            currentScreen = navigationStack.last()
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