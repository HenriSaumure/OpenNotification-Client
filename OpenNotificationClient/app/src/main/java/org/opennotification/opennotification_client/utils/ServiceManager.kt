package org.opennotification.opennotification_client.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import org.opennotification.opennotification_client.service.WebSocketService

class ServiceManager(private val context: Context) {
    companion object {
        private const val TAG = "ServiceManager"
        private const val PREFS_NAME = "service_preferences"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isServiceEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun setServiceEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()

        if (enabled) {
            startService()
        } else {
            stopService()
        }
    }

    fun startService() {
        try {
            WebSocketService.startService(context)
            setServiceEnabled(true)
            Log.i(TAG, "WebSocket service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket service", e)
        }
    }

    fun stopService() {
        try {
            WebSocketService.stopService(context)
            setServiceEnabled(false)
            Log.i(TAG, "WebSocket service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop WebSocket service", e)
        }
    }

    fun restartService() {
        stopService()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startService()
        }, 1000)
    }
}
