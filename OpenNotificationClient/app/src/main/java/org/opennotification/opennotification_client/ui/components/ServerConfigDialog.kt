package org.opennotification.opennotification_client.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ServerConfigDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf(currentUrl) }
    var isUrlValid by remember { mutableStateOf(true) }

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
                    text = "WebSocket Server Configuration",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Configure the WebSocket server URL. The app will connect to this server to receive notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        isUrlValid = isValidWebSocketUrl(it)
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("wss://localhost:7129") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = serverUrl.isNotEmpty() && !isUrlValid,
                    supportingText = {
                        if (serverUrl.isNotEmpty() && !isUrlValid) {
                            Text(
                                text = "Invalid WebSocket URL format. Use ws:// or wss://",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                text = "Examples: wss://localhost:7129, ws://192.168.1.100:8080",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )

                // Protocol information
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Protocol Information:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "• ws:// - Standard WebSocket (HTTP)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• wss:// - Secure WebSocket (HTTPS)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (serverUrl.isNotEmpty() && isUrlValid) {
                                onConfirm(serverUrl)
                            }
                        },
                        enabled = serverUrl.isNotEmpty() && isUrlValid
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

private fun isValidWebSocketUrl(url: String): Boolean {
    return try {
        val trimmedUrl = url.trim()
        trimmedUrl.startsWith("ws://") || trimmedUrl.startsWith("wss://")
    } catch (e: Exception) {
        false
    }
}
