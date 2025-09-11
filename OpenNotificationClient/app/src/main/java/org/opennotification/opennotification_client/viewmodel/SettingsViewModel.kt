package org.opennotification.opennotification_client.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.network.WebSocketManager
import org.opennotification.opennotification_client.utils.PermissionManager

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "SettingsViewModel"
        private const val PREFS_NAME = "opennotification_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "wss://api.opennotification.org"
    }

    private val context = application.applicationContext
    private val permissionManager = PermissionManager(context)
    private val webSocketManager = WebSocketManager.getInstance()
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _permissionSummary = MutableStateFlow(PermissionManager.PermissionSummary(false, false))
    val permissionSummary: StateFlow<PermissionManager.PermissionSummary> = _permissionSummary.asStateFlow()

    private val _serverUrl = MutableStateFlow(getStoredServerUrl())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    init {
        updatePermissionSummary()
        Log.d(TAG, "SettingsViewModel initialized")
    }

    fun updatePermissionSummary() {
        viewModelScope.launch {
            try {
                _permissionSummary.value = permissionManager.getPermissionSummary()
                Log.d(TAG, "Permission summary updated: ${_permissionSummary.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating permission summary", e)
            }
        }
    }

    fun updateServerUrl(newUrl: String) {
        viewModelScope.launch {
            try {
                val cleanUrl = newUrl.trim()
                val webSocketUrl = convertToWebSocketUrl(cleanUrl)

                if (isValidWebSocketUrl(webSocketUrl)) {
                    sharedPreferences.edit()
                        .putString(KEY_SERVER_URL, webSocketUrl)
                        .apply()

                    _serverUrl.value = webSocketUrl

                    webSocketManager.updateServerUrl(webSocketUrl)

                    Log.i(TAG, "Server URL updated to: $webSocketUrl")
                } else {
                    Log.w(TAG, "Invalid server URL: $newUrl")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating server URL", e)
            }
        }
    }

    fun requestNotificationPermission() {
        try {
            openNotificationSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting notification permission", e)
        }
    }

    fun requestBatteryOptimization() {
        try {
            openBatterySettings()
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization", e)
        }
    }

    fun openNotificationSettings() {
        try {
            Log.d(TAG, "Notification settings should be opened")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening notification settings", e)
        }
    }

    fun openBatterySettings() {
        try {
            Log.d(TAG, "Battery settings should be opened")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening battery settings", e)
        }
    }

    private fun convertToWebSocketUrl(url: String): String {
        val trimmedUrl = url.trim()

        return when {
            trimmedUrl.startsWith("https://") -> trimmedUrl.replace("https://", "wss://")
            trimmedUrl.startsWith("http://") -> trimmedUrl.replace("http://", "ws://")
            trimmedUrl.startsWith("wss://") || trimmedUrl.startsWith("ws://") -> trimmedUrl
            else -> {
                if (trimmedUrl.isNotEmpty()) "wss://$trimmedUrl" else trimmedUrl
            }
        }
    }

    private fun getStoredServerUrl(): String {
        return sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    private fun isValidWebSocketUrl(url: String): Boolean {
        return try {
            val trimmedUrl = url.trim()
            trimmedUrl.startsWith("ws://") || trimmedUrl.startsWith("wss://")
        } catch (e: Exception) {
            false
        }
    }
}

