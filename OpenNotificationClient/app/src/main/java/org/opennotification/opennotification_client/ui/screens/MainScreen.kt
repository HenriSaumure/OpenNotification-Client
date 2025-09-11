package org.opennotification.opennotification_client.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.opennotification.opennotification_client.ui.components.AddListenerDialog
import org.opennotification.opennotification_client.ui.components.ListenerItem
import org.opennotification.opennotification_client.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToNotificationHistory: () -> Unit = {},
    viewModel: MainViewModel = viewModel()
) {
    val listeners by viewModel.allListeners.collectAsState(initial = emptyList())
    val connectionStatuses by viewModel.connectionStatuses.collectAsState(initial = emptyMap())
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    // Collecte des états de reconnexion séquentielle
    val sequentialCurrentGuid by viewModel.sequentialCurrentGuid.collectAsState(initial = null)
    val sequentialWaitingGuids by viewModel.sequentialWaitingGuids.collectAsState(initial = emptySet())

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                actions = {
                    IconButton(onClick = onNavigateToNotificationHistory) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notification History",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Listener")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            errorMessage?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.clearError() }
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { viewModel.refreshConnections() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (listeners.isEmpty() && !isLoading) {
                    // Afficher le message uniquement si la liste est vide ET qu'on n'est pas en train de charger
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No listeners added yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the + button to add a WebSocket listener",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Pull down to refresh connections",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Toujours afficher la LazyColumn, même si elle est vide pendant le chargement
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = listeners,
                            key = { it.id }
                        ) { listener ->
                            ListenerItem(
                                listener = listener,
                                connectionStatus = connectionStatuses[listener.guid],
                                onToggleStatus = { viewModel.toggleListenerStatus(listener) },
                                onDelete = { viewModel.deleteListener(listener) },
                                onRename = { newName -> viewModel.renameListener(listener, newName) },
                                sequentialCurrentGuid = sequentialCurrentGuid,
                                sequentialWaitingGuids = sequentialWaitingGuids
                            )
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AddListenerDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { guid: String, name: String? ->
                    viewModel.addNewListener(guid, name)
                    showAddDialog = false
                }
            )
        }
    }
}