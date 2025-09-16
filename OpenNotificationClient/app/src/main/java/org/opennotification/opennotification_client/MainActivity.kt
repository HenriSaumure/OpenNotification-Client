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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.gson.Gson
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private var showPermissionDialog by mutableStateOf(false)
    private var permissionSummary by mutableStateOf(PermissionManager.PermissionSummary(false, false))

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

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        lifecycleScope.launch {
            delay(500)
            updatePermissionSummary()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
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
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "main",

                        enterTransition = { androidx.compose.animation.EnterTransition.None },
                        exitTransition = { androidx.compose.animation.ExitTransition.None }
                    ) {
                        composable("main") {
                            MainScreen(
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                onNavigateToNotificationHistory = {
                                    navController.navigate("notification_history")
                                }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("notification_history") {
                            NotificationHistoryScreen(
                                onBackClick = {
                                    navController.popBackStack()
                                },
                                onNavigateToDetail = { notification ->
                                    val gson = Gson()
                                    val notificationJson = gson.toJson(notification)
                                    val encodedJson = URLEncoder.encode(notificationJson, StandardCharsets.UTF_8.toString())
                                    navController.navigate("notification_detail/$encodedJson")
                                }
                            )
                        }

                        composable(
                            route = "notification_detail/{notificationJson}",
                            arguments = listOf(
                                navArgument("notificationJson") {
                                    type = NavType.StringType
                                }
                            )
                        ) { backStackEntry ->
                            val notificationJson = backStackEntry.arguments?.getString("notificationJson") ?: return@composable
                            val gson = Gson()
                            val notification = try {
                                gson.fromJson(
                                    java.net.URLDecoder.decode(notificationJson, StandardCharsets.UTF_8.toString()),
                                    Notification::class.java
                                )
                            } catch (e: Exception) {
                                null
                            }

                            notification?.let {
                                NotificationDetailScreen(
                                    notification = it,
                                    onBackClick = {
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }
                    }

                    if (showPermissionDialog && navController.currentBackStackEntry?.destination?.route == "main") {
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
                                    permissionManager.requestIgnoreBatteryOptimization(this@MainActivity, batteryOptimizationLauncher)
                                } catch (e: Exception) {
                                    permissionManager.openBatteryOptimizationSettings(this@MainActivity, batteryOptimizationLauncher)
                                }
                            },
                            onOverlayPermissionRequest = {
                                try {
                                    permissionManager.requestOverlayPermission(this@MainActivity, overlayPermissionLauncher)
                                } catch (e: Exception) {
                                    permissionManager.openAppSettings(this@MainActivity, overlayPermissionLauncher)
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

    override fun onResume() {
        super.onResume()
        // Check for permission changes when returning to the app
        // This ensures UI updates even if activity result launchers don't work properly
        updatePermissionSummary()
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