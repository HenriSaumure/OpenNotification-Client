package org.opennotification.opennotification_client.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.data.models.WebSocketListener
import org.opennotification.opennotification_client.data.models.Notification
import org.opennotification.opennotification_client.network.WebSocketManager
import org.opennotification.opennotification_client.repository.NotificationRepository
import org.opennotification.opennotification_client.service.WebSocketService
import org.opennotification.opennotification_client.service.WatchdogService
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NotificationRepository(AppDatabase.getDatabase(application))
    private val webSocketManager = WebSocketManager.getInstance()

    val allListeners = repository.getAllListeners()
    val allNotifications = repository.getAllNotifications()
    val connectionStatuses = webSocketManager.connectionStatuses

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // Don't start the WebSocket service automatically - only start when needed
        Log.i("MainViewModel", "ViewModel initialized")

        // Start the watchdog service immediately - it will monitor and restart WebSocket service as needed
        WatchdogService.startService(getApplication())
        Log.i("MainViewModel", "Watchdog service started")

        // Monitor active listeners and ensure WebSocket connections
        viewModelScope.launch {
            allListeners.collect { listeners ->
                val activeListeners = listeners.filter { it.isActive }
                Log.d("MainViewModel", "Listeners changed: Total=${listeners.size}, Active=${activeListeners.size}")

                // Only start WebSocket service if there are active listeners
                if (activeListeners.isNotEmpty()) {
                    Log.d("MainViewModel", "Starting WebSocket service for ${activeListeners.size} active listeners")
                    WebSocketService.startService(getApplication())
                } else {
                    Log.d("MainViewModel", "No active listeners - WebSocket service will stop itself")
                    // Don't explicitly stop the service here - let it stop itself when it detects no active listeners
                    // This prevents race conditions and crashes
                }

                // Always update WebSocketManager, regardless of count
                webSocketManager.updateActiveListeners(activeListeners)
                Log.d("MainViewModel", "Updated WebSocketManager with ${activeListeners.size} active listeners")
            }
        }
    }

    fun addNewListener(guid: String, name: String?) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Validate GUID format
                if (!isValidGuid(guid)) {
                    _errorMessage.value = "Invalid GUID format"
                    return@launch
                }

                // Check if listener already exists
                val existingListener = repository.getListenerByGuid(guid)
                if (existingListener != null) {
                    _errorMessage.value = "Listener for this GUID already exists"
                    return@launch
                }

                val listener = WebSocketListener(
                    guid = guid,
                    name = name?.takeIf { it.isNotBlank() } ?: "Listener ${guid.take(8)}"
                )

                repository.insertListener(listener)

            } catch (e: Exception) {
                _errorMessage.value = "Failed to add listener: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleListenerStatus(listener: WebSocketListener) {
        viewModelScope.launch {
            try {
                val newStatus = !listener.isActive
                Log.d("MainViewModel", "Toggling listener status for ${listener.guid}: ${listener.isActive} -> $newStatus")

                repository.updateListenerStatus(listener.id, newStatus)

                Log.d("MainViewModel", "Listener status updated in database for ${listener.guid}: isActive = $newStatus")

                // If we're activating a listener, ensure the WebSocket service is running
                if (newStatus) {
                    Log.d("MainViewModel", "Listener activated - starting WebSocket service")
                    WebSocketService.startService(getApplication())
                }

                // Force refresh of active listeners
                val updatedListeners = repository.getAllListeners()
                Log.d("MainViewModel", "Triggering WebSocket manager update after status change")

            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to update listener status for ${listener.guid}: ${e.message}", e)
                _errorMessage.value = "Failed to update listener status: ${e.message}"
            }
        }
    }

    fun deleteListener(listener: WebSocketListener) {
        viewModelScope.launch {
            try {
                repository.deleteListener(listener)
                // Also delete associated notifications
                repository.deleteNotificationsByGuid(listener.guid)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete listener: ${e.message}"
            }
        }
    }

    fun renameListener(listener: WebSocketListener, newName: String) {
        viewModelScope.launch {
            try {
                val updatedListener = listener.copy(name = newName.trim())
                repository.updateListener(updatedListener)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to rename listener: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun isValidGuid(guid: String): Boolean {
        return try {
            UUID.fromString(guid)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop the service when ViewModel is cleared (app is closed)
        WebSocketService.stopService(getApplication())
    }
}
