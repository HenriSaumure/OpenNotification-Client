package org.opennotification.opennotification_client.utils

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log

class MemoryPressureHandler(private val context: Context) : ComponentCallbacks2 {
    companion object {
        private const val TAG = "MemoryPressureHandler"
    }

    private var isRegistered = false

    fun startProtection() {
        if (!isRegistered) {
            context.registerComponentCallbacks(this)
            isRegistered = true
            Log.i(TAG, "Memory pressure protection started")
        }
    }

    fun stopProtection() {
        if (isRegistered) {
            try {
                context.unregisterComponentCallbacks(this)
                isRegistered = false
                Log.i(TAG, "Memory pressure protection stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping memory pressure protection", e)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "Memory trim requested with level: $level")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure detected - optimizing")
                handleCriticalMemoryPressure()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "Low memory pressure - minor optimization")
                System.gc()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // No action needed
    }

    override fun onLowMemory() {
        Log.w(TAG, "System low memory warning received")
        handleCriticalMemoryPressure()
    }

    private fun handleCriticalMemoryPressure() {
        try {
            Log.w(TAG, "Handling critical memory pressure")

            // Force garbage collection
            System.gc()

            // Disconnect error connections to free memory
            val webSocketManager = org.opennotification.opennotification_client.network.WebSocketManager.getInstance()
            val errorConnections = webSocketManager.getErrorConnections()
            if (errorConnections.isNotEmpty()) {
                Log.i(TAG, "Disconnecting ${errorConnections.size} error connections to free memory")
                errorConnections.forEach { guid ->
                    webSocketManager.disconnectFromGuid(guid)
                }
            }

            Log.i(TAG, "Critical memory pressure handling completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling critical memory pressure", e)
        }
    }
}
