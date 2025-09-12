package org.opennotification.opennotification_client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.opennotification.opennotification_client.data.models.Notification
import org.opennotification.opennotification_client.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    onBackClick: () -> Unit = {},
    onNavigateToDetail: (Notification) -> Unit = {},
    viewModel: MainViewModel = viewModel()
) {
    val notifications by viewModel.allNotifications.collectAsState(initial = emptyList())
    val isLoading by viewModel.isLoading.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()) }
    val context = LocalContext.current

    // History preference state
    val prefs by remember { mutableStateOf(context.getSharedPreferences("opennotification_settings", android.content.Context.MODE_PRIVATE)) }
    var isHistoryEnabled by remember {
        mutableStateOf(prefs.getBoolean("history_enabled", true))
    }

    // Handle back button press
    BackHandler {
        onBackClick()
    }

    // État de chargement initial pour éviter l'affichage prématuré du texte "pas de notifications"
    var isInitialLoading by remember { mutableStateOf(true) }

    // Gérer l'état de chargement initial
    LaunchedEffect(Unit) {
        // Attendre un court délai pour permettre aux données de se charger
        delay(300)
        isInitialLoading = false
    }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification History") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // History toggle button with slash indicator when disabled
                    IconButton(
                        onClick = {
                            val newValue = !isHistoryEnabled
                            isHistoryEnabled = newValue
                            prefs.edit().putBoolean("history_enabled", newValue).apply()
                        }
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = if (isHistoryEnabled) "History enabled - Click to disable" else "History disabled - Click to enable",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )

                            // Add diagonal slash when history is disabled
                            if (!isHistoryEnabled) {
                                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                                Canvas(modifier = Modifier.size(24.dp)) {
                                    drawLine(
                                        color = onSurfaceColor,
                                        start = Offset(6f, 6f),
                                        end = Offset(size.width - 6f, size.height - 6f),
                                        strokeWidth = 6f
                                    )
                                }
                            }
                        }
                    }

                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete all notifications"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { /* Refresh logic if needed */ },
            modifier = Modifier.fillMaxSize()
        ) {
            if (notifications.isEmpty() && !isLoading && !isInitialLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No notifications in history",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = notifications,
                        key = { it.id }
                    ) { notification ->
                        SwipeableNotificationItem(
                            notification = notification,
                            dateFormat = dateFormat,
                            onDelete = { viewModel.deleteNotification(notification) },
                            onClick = { onNavigateToDetail(notification) }
                        )
                    }
                }
            }
        }

        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete all notifications") },
                text = { Text("Are you sure you want to delete all notification history? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteAllNotifications()
                            showDeleteConfirmDialog = false
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNotificationItem(
    notification: Notification,
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    var isDeleting by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                isDeleting = true
                true
            } else {
                false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.3f } // Only 30% swipe needed to trigger
    )

    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            delay(300) // Short delay for animation
            onDelete()
        }
    }

    AnimatedVisibility(
        visible = !isDeleting,
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 300),
            shrinkTowards = Alignment.Top
        ) + fadeOut(animationSpec = tween(durationMillis = 300))
    ) {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.error
                else
                    Color.Transparent
                
                // Use the same shape as the card to ensure the background matches exactly
                val shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 0.dp, bottomEnd = 0.dp)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(color)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete notification",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            },
            content = {
                NotificationItem(
                    notification = notification,
                    dateFormat = dateFormat,
                    onClick = onClick
                )
            },
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true
        )
    }
}

@Composable
fun NotificationItem(
    notification: Notification,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    // Use the same shape as defined in the background of SwipeableNotificationItem
    val shape = RoundedCornerShape(12.dp)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isAlert) 
                MaterialTheme.colorScheme.errorContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (notification.isAlert) 
                        MaterialTheme.colorScheme.onErrorContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = dateFormat.format(Date(notification.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notification.isAlert) 
                        MaterialTheme.colorScheme.onErrorContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))

            notification.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (notification.isAlert)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}