package org.opennotification.opennotification_client.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.UUID

@Composable
fun AddListenerDialog(
    onDismiss: () -> Unit,
    onConfirm: (guid: String, name: String?) -> Unit
) {
    var guid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isGuidValid by remember { mutableStateOf(true) }

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
                    text = "New Connection",
                    style = MaterialTheme.typography.headlineSmall
                )

                OutlinedTextField(
                    value = guid,
                    onValueChange = {
                        guid = it
                        isGuidValid = isValidGuid(it)
                    },
                    label = { Text("GUID") },
                    placeholder = { Text("e.g., 550e8400-e29b-41d4-a716-446655440000") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = guid.isNotEmpty() && !isGuidValid,
                    supportingText = {
                        if (guid.isNotEmpty() && !isGuidValid) {
                            Text(
                                text = "Invalid GUID format",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (Optional)") },
                    placeholder = { Text("e.g., Production Server") },
                    modifier = Modifier.fillMaxWidth()
                )

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
                            if (guid.isNotEmpty() && isGuidValid) {
                                onConfirm(guid, name.takeIf { it.isNotBlank() })
                            }
                        },
                        enabled = guid.isNotEmpty() && isGuidValid
                    ) {
                        Text("Add Listener")
                    }
                }
            }
        }
    }
}

private fun isValidGuid(guid: String): Boolean {
    return try {
        UUID.fromString(guid)
        true
    } catch (e: IllegalArgumentException) {
        false
    }
}
