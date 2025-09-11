package org.opennotification.opennotification_client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
            if (notifications.isEmpty() && !isLoading) {
                // Afficher le message uniquement si la liste est vide ET qu'on n'est pas en train de charger
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
                // Toujours afficher la LazyColumn, même si elle est vide pendant le chargement
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
                        SwipeToDeleteContainer(
                            item = notification,
                            onDelete = { viewModel.deleteNotification(it) }
                        ) { item ->
                            NotificationItem(
                                notification = item,
                                dateFormat = dateFormat,
                                onClick = { onNavigateToDetail(item) }
                            )
                        }
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

@Composable
fun NotificationItem(
    notification: Notification,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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

@Composable
fun <T> SwipeToDeleteContainer(
    item: T,
    onDelete: (T) -> Unit,
    animationDuration: Int = 500,
    content: @Composable (T) -> Unit
) {
    var isRemoved by remember { mutableStateOf(false) }
    var isDeleted by remember { mutableStateOf(false) }
    
    val dismissState = rememberDismissState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == DismissValue.DismissedToStart) {
                isRemoved = true
                true
            } else {
                false
            }
        }
    )

    LaunchedEffect(isRemoved) {
        if (isRemoved) {
            delay(animationDuration.toLong())
            onDelete(item)
            isDeleted = true
        }
    }

    AnimatedVisibility(
        visible = !isDeleted,
        exit = shrinkHorizontally(
            animationSpec = tween(durationMillis = animationDuration),
            shrinkTowards = Alignment.Start
        ) + fadeOut()
    ) {
        SwipeToDismiss(
            state = dismissState,
            background = {
                val color = when (dismissState.dismissDirection) {
                    DismissDirection.StartToEnd -> Color.Transparent
                    DismissDirection.EndToStart -> MaterialTheme.colorScheme.error
                    null -> Color.Transparent
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            },
            dismissContent = { content(item) },
            directions = setOf(DismissDirection.EndToStart)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDismissState(
    initialValue: DismissValue = DismissValue.Default,
    confirmValueChange: (DismissValue) -> Boolean = { true }
): DismissState {
    return remember {
        DismissState(
            initialValue = initialValue,
            confirmValueChange = confirmValueChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismiss(
    state: DismissState,
    background: @Composable RowScope.() -> Unit,
    dismissContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    directions: Set<DismissDirection> = setOf(
        DismissDirection.StartToEnd,
        DismissDirection.EndToStart
    )
) {
    val isRtl = false

    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            content = background
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(
                    x = when (state.dismissDirection) {
                        DismissDirection.StartToEnd -> {
                            if (state.dismissValue == DismissValue.Default) 0.dp else 20.dp
                        }
                        DismissDirection.EndToStart -> {
                            if (state.dismissValue == DismissValue.Default) 0.dp else (-20).dp
                        }
                        null -> 0.dp
                    }
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        when {
                            delta > 0 && DismissDirection.StartToEnd in directions -> {
                                state.dismissDirection = DismissDirection.StartToEnd
                                state.offset = delta
                                if (delta > 200) {
                                    state.dismissValue = DismissValue.DismissedToEnd
                                }
                            }
                            delta < 0 && DismissDirection.EndToStart in directions -> {
                                state.dismissDirection = DismissDirection.EndToStart
                                state.offset = delta
                                if (delta < -200) {
                                    state.dismissValue = DismissValue.DismissedToStart
                                }
                            }
                        }
                    },
                    onDragStopped = {
                        if (state.dismissValue != DismissValue.Default) {
                            state.confirmValueChange(state.dismissValue)
                        }
                        state.offset = 0f
                        state.dismissDirection = null
                        state.dismissValue = DismissValue.Default
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = dismissContent
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
enum class DismissDirection {
    StartToEnd,
    EndToStart
}

@OptIn(ExperimentalMaterial3Api::class)
enum class DismissValue {
    Default,
    DismissedToEnd,
    DismissedToStart
}

@OptIn(ExperimentalMaterial3Api::class)
class DismissState(
    val initialValue: DismissValue,
    val confirmValueChange: (DismissValue) -> Boolean
) {
    var dismissValue by mutableStateOf(initialValue)
    var dismissDirection by mutableStateOf<DismissDirection?>(null)
    var offset by mutableStateOf(0f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDraggableState(
    onDelta: (Float) -> Unit
): DraggableState {
    return remember { DraggableState(onDelta) }
}

@OptIn(ExperimentalMaterial3Api::class)
class DraggableState(
    val onDelta: (Float) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.draggable(
    orientation: Orientation,
    state: DraggableState,
    onDragStopped: () -> Unit
): Modifier {
    return this.clickable { }
}

@OptIn(ExperimentalMaterial3Api::class)
enum class Orientation {
    Horizontal,
    Vertical
}