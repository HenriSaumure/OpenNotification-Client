package org.opennotification.opennotification_client.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opennotification.opennotification_client.data.models.WebSocketListener
import org.opennotification.opennotification_client.data.models.ConnectionStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListenerItem(
    listener: WebSocketListener,
    connectionStatus: ConnectionStatus?,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit,
    onRename: ((String) -> Unit)? = null
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showFullGuid by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current

    // Force recomposition when connection status changes
    val currentConnectionStatus by remember(connectionStatus) { mutableStateOf(connectionStatus) }

    // Get short GUID (last 5 characters)
    val shortGuid = listener.guid.takeLast(5)
    val displayGuid = if (showFullGuid) listener.guid else shortGuid

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { showFullGuid = !showFullGuid },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    showContextMenu = true
                },
                onDoubleClick = {
                    // Copy GUID to clipboard
                    clipboardManager.setText(AnnotatedString(listener.guid))
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (showFullGuid) {
            // Show only the full GUID when toggled
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = listener.guid,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Show normal layout with name, short GUID and connection status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Name and GUID
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${listener.name ?: "Unnamed Listener"} • $displayGuid",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Show last connected time if available and not active
                    if (!listener.isActive && listener.lastConnected != null) {
                        Text(
                            text = "Last connected: ${formatTimestamp(listener.lastConnected)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Right side: Connection status indicator and text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ConnectionStatusIndicator(
                        connectionStatus = currentConnectionStatus,
                        isActive = listener.isActive
                    )
                    Text(
                        text = getConnectionStatusText(currentConnectionStatus, listener.isActive),
                        style = MaterialTheme.typography.bodySmall,
                        color = getConnectionStatusColor(currentConnectionStatus, listener.isActive),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Context menu dropdown
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(if (listener.isActive) "Stop Listener" else "Start Listener")
                },
                onClick = {
                    onToggleStatus()
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (listener.isActive) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }
            )

            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showRenameDialog = true
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null
                        )
                    }
                )
            }

            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    showDeleteDialog = true
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Listener") },
            text = {
                Text("Are you sure you want to delete this listener? This will also delete all associated notifications.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog && onRename != null) {
        var newName by remember { mutableStateOf(listener.name ?: "") }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Listener") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Listener Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRename(newName)
                        }
                        showRenameDialog = false
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun ConnectionStatusIndicator(
    connectionStatus: ConnectionStatus?,
    isActive: Boolean
) {
    val color = getConnectionStatusColor(connectionStatus, isActive)

    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun getConnectionStatusColor(
    connectionStatus: ConnectionStatus?,
    isActive: Boolean
): Color {
    return if (!isActive) {
        MaterialTheme.colorScheme.outline
    } else {
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) // Green
            ConnectionStatus.CONNECTING -> Color(0xFFFF9800) // Orange
            ConnectionStatus.ERROR -> Color(0xFFF44336) // Red
            ConnectionStatus.DISCONNECTED, null -> Color(0xFF9E9E9E) // Gray
        }
    }
}

private fun getConnectionStatusText(
    connectionStatus: ConnectionStatus?,
    isActive: Boolean
): String {
    return if (!isActive) {
        "Inactive"
    } else {
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.CONNECTING -> "Connecting..."
            ConnectionStatus.ERROR -> "Connection Error"
            ConnectionStatus.DISCONNECTED, null -> "Disconnected"
        }
    }
}
