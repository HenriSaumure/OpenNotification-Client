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
        Log.i("MainViewModel", "ViewModel initialized with battery optimization")

        org.opennotification.opennotification_client.utils.ConnectionKeepAlive.startKeepAlive(getApplication())

        viewModelScope.launch {
            allListeners.collect { listeners ->
                val activeListeners = listeners.filter { it.isActive }
                Log.d("MainViewModel", "Listeners changed: Total=${listeners.size}, Active=${activeListeners.size}")

                if (activeListeners.isNotEmpty()) {
                    Log.d("MainViewModel", "Starting WebSocket service for ${activeListeners.size} active listeners")
                    WebSocketService.startService(getApplication())
                } else {
                    Log.d("MainViewModel", "No active listeners - stopping WebSocket service to save battery")
                    WebSocketService.stopService(getApplication())
                }
            }
        }
    }

    fun addListener(name: String, guid: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val listener = WebSocketListener(
                    guid = guid,
                    name = name,
                    isActive = true
                )
                repository.insertListener(listener)
                _errorMessage.value = null
                Log.i("MainViewModel", "Listener added: $name ($guid)")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to add listener: ${e.message}"
                Log.e("MainViewModel", "Error adding listener", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun generateNewGuid(): String {
        return UUID.randomUUID().toString()
    }

    fun toggleListener(listener: WebSocketListener) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val updatedListener = listener.copy(isActive = !listener.isActive)
                repository.updateListener(updatedListener)
                _errorMessage.value = null
                Log.i("MainViewModel", "Listener toggled: ${listener.name} -> active=${updatedListener.isActive}")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to toggle listener: ${e.message}"
                Log.e("MainViewModel", "Error toggling listener", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteListener(listener: WebSocketListener) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                repository.deleteListener(listener)
                _errorMessage.value = null
                Log.i("MainViewModel", "Listener deleted: ${listener.name}")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete listener: ${e.message}"
                Log.e("MainViewModel", "Error deleting listener", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteAllNotifications() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                repository.deleteAllNotifications()
                _errorMessage.value = null
                Log.i("MainViewModel", "All notifications deleted")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete notifications: ${e.message}"
                Log.e("MainViewModel", "Error deleting notifications", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteNotification(notification: Notification) {
        viewModelScope.launch {
            try {
                repository.deleteNotification(notification)
                Log.i("MainViewModel", "Notification deleted: ${notification.title}")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete notification: ${e.message}"
                Log.e("MainViewModel", "Error deleting notification", e)
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun toggleListenerStatus(listener: WebSocketListener) {
        toggleListener(listener)
    }

    fun addNewListener(guid: String, name: String?) {
        addListener(name ?: "Unnamed Listener", guid)
    }

    fun renameListener(listener: WebSocketListener, newName: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val updatedListener = listener.copy(name = newName)
                repository.updateListener(updatedListener)
                _errorMessage.value = null
                Log.i("MainViewModel", "Listener renamed: ${listener.name} -> $newName")
            } catch (e: Exception) {
                _errorMessage.value = "Failed to rename listener: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshConnections() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.i("MainViewModel", "Refreshing all WebSocket connections")

                webSocketManager.forceReconnectAll()

                kotlinx.coroutines.delay(2000)

                _errorMessage.value = null
                Log.i("MainViewModel", "Connection refresh completed")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error refreshing connections", e)
                _errorMessage.value = "Failed to refresh connections: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
