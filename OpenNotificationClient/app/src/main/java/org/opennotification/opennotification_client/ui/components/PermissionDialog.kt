package org.opennotification.opennotification_client.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.opennotification.opennotification_client.utils.PermissionManager

@Composable
fun PermissionDialog(
    permissionSummary: PermissionManager.PermissionSummary,
    onNotificationPermissionRequest: () -> Unit,
    onBatteryOptimizationRequest: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Required Permissions",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "To ensure WebSocket notifications work reliably in the background, please grant the following permissions:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Notification Permission
                PermissionItem(
                    icon = Icons.Default.Notifications,
                    title = "Notification Permission",
                    description = "Allow the app to show notifications when messages are received",
                    isGranted = permissionSummary.notificationPermissionGranted,
                    onRequest = onNotificationPermissionRequest
                )

                // Battery Optimization
                PermissionItem(
                    icon = Icons.Default.Warning,
                    title = "Unrestricted Battery Usage",
                    description = "CRITICAL: Allow unrestricted battery usage to keep WebSocket connections alive when app is closed. Without this, notifications will NOT work in background.",
                    isGranted = permissionSummary.batteryOptimizationIgnored,
                    onRequest = onBatteryOptimizationRequest,
                    isCritical = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (permissionSummary.allPermissionsGranted) {
                        Button(onClick = onDismiss) {
                            Text("Continue")
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text("Skip for Now")
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
    onRequest: () -> Unit,
    isCritical: Boolean = false // New parameter to indicate critical permissions
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
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
