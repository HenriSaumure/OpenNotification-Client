package org.opennotification.opennotification_client.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.opennotification.opennotification_client.utils.PermissionManager
import org.opennotification.opennotification_client.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val permissionManager = remember { PermissionManager(context) }
    val permissionSummary by viewModel.permissionSummary.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()

    var newServerUrl by remember { mutableStateOf("") }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(serverUrl) {
        newServerUrl = serverUrl
    }

    LaunchedEffect(Unit) {
        permissionManager.onPermissionChanged = { newSummary ->
            viewModel.updatePermissionSummary()
        }
    }

    DisposableEffect(Unit) {
        viewModel.updatePermissionSummary()

        val lifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                super.onResume(owner)
                viewModel.updatePermissionSummary()
                permissionManager.checkAndNotifyPermissionChanges()
            }
        }

        if (context is androidx.lifecycle.LifecycleOwner) {
            context.lifecycle.addObserver(lifecycleObserver)
        }

        onDispose {
            if (context is androidx.lifecycle.LifecycleOwner) {
                context.lifecycle.removeObserver(lifecycleObserver)
            }
        }
    }

    val activity = context as? ComponentActivity
    LaunchedEffect(activity) {
        activity?.let {
            kotlinx.coroutines.delay(500)
            viewModel.updatePermissionSummary()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Server Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = newServerUrl,
                        onValueChange = { newServerUrl = it },
                        label = { Text("WebSocket Server URL") },
                        placeholder = { Text("https://example.com or wss://example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text(
                                text = "Tip: https:// URLs will be automatically converted to wss://",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    Button(
                        onClick = {
                            viewModel.updateServerUrl(newServerUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = newServerUrl.isNotBlank() && newServerUrl != serverUrl
                    ) {
                        Text("Update Server URL")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "These permissions are required for the app to work properly in the background:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    PermissionItem(
                        icon = Icons.Default.Notifications,
                        title = "Notification Permission",
                        description = "Required to show notifications when messages are received",
                        isGranted = permissionSummary.notificationPermissionGranted,
                        onRequest = {
                            permissionManager.requestNotificationPermission(context as androidx.activity.ComponentActivity)
                        }
                    )

                    PermissionItem(
                        icon = Icons.Default.Warning,
                        title = "Battery Optimization",
                        description = "Prevents the system from stopping the app in the background",
                        isGranted = permissionSummary.batteryOptimizationIgnored,
                        onRequest = {
                            permissionManager.requestIgnoreBatteryOptimization(context as androidx.activity.ComponentActivity)
                        }
                    )

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        PermissionItem(
                            icon = Icons.Default.Notifications,
                            title = "Display Over Other Apps",
                            description = "Enable full-screen alert overlays on top of any app (like timer alarms). Optional but recommended for urgent notifications.",
                            isGranted = permissionSummary.overlayPermissionGranted,
                            onRequest = {
                                permissionManager.requestOverlayPermission(context as androidx.activity.ComponentActivity)
                            }
                        )
                    }

                    if (permissionSummary.allPermissionsGranted) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50)
                                )
                                Text(
                                    text = "All permissions granted! The app can run reliably in the background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = if (isGranted) "Granted" else "Not granted",
                    tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}
