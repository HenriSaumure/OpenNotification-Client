package org.opennotification.opennotification_client.network

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.opennotification.opennotification_client.data.models.ConnectionStatus
import org.opennotification.opennotification_client.data.models.Notification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WebSocketManager private constructor() {
    companion object {
        private const val TAG = "WebSocketManager"
        private var BASE_WS_URL = "wss://api.opennotification.org/ws"
        private const val RECONNECT_DELAY = 10000L // Increased from 5s to 10s for battery
        private const val CONNECTION_MONITOR_INTERVAL = 60000L // Increased from 5s to 1 minute for battery
        private const val PREFS_NAME = "opennotification_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "https://api.opennotification.org"

        @Volatile
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                val instance = WebSocketManager()
                INSTANCE = instance
                instance
            }
        }

        fun initializeWithContext(context: Context) {
            getInstance().loadServerUrlFromPreferences(context)
        }
    }

    private val gson = GsonBuilder().setLenient().create()

    // Optimized OkHttpClient with longer timeouts for battery efficiency
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(60, TimeUnit.SECONDS) // Reduced ping frequency from 20s to 60s
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()
    private val connectingGuids = ConcurrentHashMap.newKeySet<String>()
    private val _connectionStatuses = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<String, ConnectionStatus>> = _connectionStatuses
    var onNotificationReceived: ((Notification) -> Unit)? = null
    private val activeListenerGuids = ConcurrentHashMap.newKeySet<String>()
    private var connectionMonitorJob: Job? = null
    private var isMonitoringActive = false

    data class WebSocketConnection(
        val webSocket: WebSocket?,
        val status: ConnectionStatus,
        val reconnectJob: Job?,
        val lastReconnectAttempt: Long = 0L,
        val reconnectAttempts: Int = 0,
        val lastSuccessfulConnection: Long = 0L
    )

    fun connectToGuid(guid: String) {
        Log.d(TAG, "Connecting to GUID: $guid")

        val currentConnection = connections[guid]
        val isActuallyConnected = currentConnection?.webSocket?.let { ws ->
            try {
                currentConnection.status == ConnectionStatus.CONNECTED
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket test failed for GUID: $guid", e)
                false
            }
        } ?: false

        if (isActuallyConnected && currentConnection?.status != ConnectionStatus.ERROR) {
            Log.d(TAG, "Already connected to GUID: $guid")
            return
        }

        val timeSinceLastAttempt = System.currentTimeMillis() - (currentConnection?.lastReconnectAttempt ?: 0L)
        if (timeSinceLastAttempt < 2000L) { // Increased prevention threshold for battery
            Log.d(TAG, "Preventing rapid reconnection for GUID: $guid")
            return
        }

        if (currentConnection?.status == ConnectionStatus.CONNECTING && timeSinceLastAttempt < 60000L) {
            Log.d(TAG, "Connection already in progress for GUID: $guid")
            return
        }

        connectingGuids.add(guid)
        updateConnectionStatus(guid, ConnectionStatus.CONNECTING)
        attemptConnection(guid, BASE_WS_URL, true)
    }

    /**
     * Starts persistent background monitoring that checks connections every 5 seconds
     * and ensures failed connections always retry
     */
    private fun startConnectionMonitoring() {
        if (isMonitoringActive) {
            Log.d(TAG, "Connection monitoring already active")
            return
        }

        Log.i(TAG, "Starting connection monitoring (every ${CONNECTION_MONITOR_INTERVAL}ms)")
        isMonitoringActive = true

        connectionMonitorJob = scope.launch {
            while (isActive && isMonitoringActive) {
                try {
                    monitorAndReconnectConnections()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connection monitoring", e)
                }

                delay(CONNECTION_MONITOR_INTERVAL)
            }
        }
    }

    /**
     * Stops persistent background monitoring
     */
    private fun stopConnectionMonitoring() {
        Log.i(TAG, "Stopping connection monitoring")
        isMonitoringActive = false
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
    }

    /**
     * Monitors all connections and ensures disconnected/error connections always retry
     */
    private fun monitorAndReconnectConnections() {
        if (activeListenerGuids.isEmpty()) {
            return
        }

        Log.d(TAG, "Monitoring ${activeListenerGuids.size} active connections")

        activeListenerGuids.forEach { guid ->
            val connection = connections[guid]
            val currentTime = System.currentTimeMillis()

            when {
                connection == null -> {
                    Log.w(TAG, "No connection found for GUID: $guid")
                    connectToGuid(guid)
                }

                connection.status == ConnectionStatus.ERROR ||
                        connection.status == ConnectionStatus.DISCONNECTED -> {
                    val timeSinceLastAttempt = currentTime - connection.lastReconnectAttempt

                    if (timeSinceLastAttempt >= RECONNECT_DELAY) {
                        Log.i(TAG, "Retrying connection for GUID: $guid")
                        connectToGuid(guid)
                    }
                }

                connection.status == ConnectionStatus.CONNECTING -> {
                    val timeSinceLastAttempt = currentTime - connection.lastReconnectAttempt

                    if (timeSinceLastAttempt > 60000L) { // Increased timeout for battery
                        Log.w(TAG, "Connection attempt timed out for GUID: $guid")
                        connectToGuid(guid)
                    }
                }
            }
        }

        val connectedCount = connections.values.count { it.status == ConnectionStatus.CONNECTED }
        Log.d(TAG, "Connection monitoring: $connectedCount/${activeListenerGuids.size} connections healthy")
    }

    /**
     * Verifies if a WebSocket connection is actually healthy
     */
    private fun verifyConnectionHealth(guid: String, connection: WebSocketConnection): Boolean {
        return try {
            connection.webSocket?.let { ws ->
                // Check if the connection is still valid without sending data
                // The automatic ping frames from OkHttpClient will handle actual connectivity testing
                val timeSinceLastMessage = System.currentTimeMillis() - connection.lastSuccessfulConnection

                // If we haven't received any data in over 60 seconds, consider it potentially unhealthy
                // But don't immediately mark as dead since the server might just be quiet
                if (timeSinceLastMessage > 60000L) {
                    Log.d(TAG, "No data received for GUID: $guid in ${timeSinceLastMessage}ms - connection may be idle")
                    // Let the automatic ping frames handle this rather than sending test messages
                }

                // Consider connection healthy if it's not obviously broken
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed for GUID: $guid", e)
            false
        }
    }

    private fun attemptConnection(guid: String, wsUrl: String, isPrimaryAttempt: Boolean) {
        val request = Request.Builder()
            .url("$wsUrl/$guid")
            .addHeader("User-Agent", "OpenNotification-Android/1.0")
            .build()

        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened for GUID: $guid")
                connectingGuids.remove(guid)
                connections[guid] = WebSocketConnection(
                    webSocket = webSocket,
                    status = ConnectionStatus.CONNECTED,
                    reconnectJob = null,
                    lastReconnectAttempt = System.currentTimeMillis(),
                    reconnectAttempts = 0,
                    lastSuccessfulConnection = System.currentTimeMillis()
                )
                updateConnectionStatus(guid, ConnectionStatus.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                connections[guid]?.let { connection ->
                    connections[guid] = connection.copy(lastSuccessfulConnection = System.currentTimeMillis())
                }

                if (text.trim().equals("TEST_FULL_SCREEN", ignoreCase = true)) {
                    Log.i(TAG, "TEST COMMAND RECEIVED - Triggering test full screen alert")
                    val testNotification = Notification(
                        id = "test-${System.currentTimeMillis()}",
                        guid = guid,
                        title = "TEST FULL SCREEN ALERT",
                        description = "This is a test notification triggered by TEST_FULL_SCREEN command",
                        isAlert = true,
                        timestamp = System.currentTimeMillis()
                    )
                    onNotificationReceived?.invoke(testNotification)
                    return
                }

                Log.d(TAG, "Message received for GUID $guid: $text")
                try {
                    val notification = gson.fromJson(text, Notification::class.java)
                    val correctedNotification = if (!notification.isAlert && text.contains("\"IsAlert\":\"true\"", ignoreCase = true)) {
                        notification.copy(isAlert = true)
                    } else {
                        notification
                    }
                    onNotificationReceived?.invoke(correctedNotification)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing notification: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing for GUID: $guid")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed for GUID: $guid")
                connectingGuids.remove(guid)
                updateConnectionStatus(guid, ConnectionStatus.DISCONNECTED)

                if (activeListenerGuids.contains(guid)) {
                    scheduleReconnect(guid, "Connection closed")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure for GUID: $guid", t)
                connectingGuids.remove(guid)

                if (isPrimaryAttempt && wsUrl.endsWith("/")) {
                    val fallbackUrl = wsUrl.removeSuffix("/")
                    Log.i(TAG, "Trying fallback URL: $fallbackUrl")
                    attemptConnection(guid, fallbackUrl, false)
                    return
                }

                updateConnectionStatus(guid, ConnectionStatus.ERROR)

                if (activeListenerGuids.contains(guid)) {
                    scheduleReconnect(guid, "Connection failed")
                }
            }
        }

        val webSocket = okHttpClient.newWebSocket(request, webSocketListener)
        val currentConnection = connections[guid]
        connections[guid] = WebSocketConnection(
            webSocket = webSocket,
            status = ConnectionStatus.CONNECTING,
            reconnectJob = null,
            lastReconnectAttempt = System.currentTimeMillis(),
            reconnectAttempts = currentConnection?.reconnectAttempts ?: 0,
            lastSuccessfulConnection = currentConnection?.lastSuccessfulConnection ?: 0L
        )
    }

    fun disconnectFromGuid(guid: String) {
        Log.i(TAG, "Disconnecting WebSocket for GUID: $guid")
        connections[guid]?.let { connection ->
            connection.reconnectJob?.cancel()
            connection.webSocket?.close(1000, "Listener stopped")
        }

        connections.remove(guid)
        connectingGuids.remove(guid)

        val currentStatuses = _connectionStatuses.value.toMutableMap()
        currentStatuses.remove(guid)
        _connectionStatuses.value = currentStatuses
    }

    fun disconnectAll() {
        Log.d(TAG, "Disconnecting all WebSocket connections")
        stopConnectionMonitoring()

        // Clear active listener GUIDs first to prevent reconnections
        activeListenerGuids.clear()

        connections.keys.forEach { guid ->
            disconnectFromGuid(guid)
        }
    }

    private fun scheduleReconnect(guid: String, reason: String = "Unknown", delay: Long = RECONNECT_DELAY) {
        if (!activeListenerGuids.contains(guid)) {
            return
        }

        val currentConnection = connections[guid]
        val attempts = (currentConnection?.reconnectAttempts ?: 0) + 1

        Log.i(TAG, "Scheduling reconnect #$attempts for GUID: $guid in ${delay}ms")

        val reconnectJob = scope.launch {
            delay(delay)
            if (activeListenerGuids.contains(guid)) {
                connectToGuid(guid)
            }
        }

        connections[guid] = connections[guid]?.copy(
            reconnectJob = reconnectJob,
            lastReconnectAttempt = System.currentTimeMillis(),
            reconnectAttempts = attempts
        ) ?: WebSocketConnection(
            webSocket = null,
            status = ConnectionStatus.DISCONNECTED,
            reconnectJob = reconnectJob,
            lastReconnectAttempt = System.currentTimeMillis(),
            reconnectAttempts = attempts
        )
    }

    private fun updateConnectionStatus(guid: String, status: ConnectionStatus) {
        val currentStatuses = _connectionStatuses.value.toMutableMap()
        currentStatuses[guid] = status
        _connectionStatuses.value = currentStatuses
    }

    fun getConnectionStatus(guid: String): ConnectionStatus {
        return connections[guid]?.status ?: ConnectionStatus.DISCONNECTED
    }

    fun isConnected(guid: String): Boolean {
        return connections[guid]?.status == ConnectionStatus.CONNECTED
    }

    fun getAllConnectionStatuses(): Map<String, ConnectionStatus> {
        return connections.mapValues { it.value.status }
    }

    fun updateServerUrl(newBaseUrl: String) {
        Log.i(TAG, "Updating server URL to $newBaseUrl")

        val activeGuids = connections.keys.toList()

        activeGuids.forEach { guid ->
            connections[guid]?.let { connection ->
                connection.reconnectJob?.cancel()
                connection.webSocket?.close(1000, "Server URL changed")
            }
            connections[guid] = WebSocketConnection(null, ConnectionStatus.DISCONNECTED, null)
            updateConnectionStatus(guid, ConnectionStatus.DISCONNECTED)
        }

        BASE_WS_URL = if (newBaseUrl.endsWith("/ws")) {
            newBaseUrl
        } else {
            "$newBaseUrl/ws"
        }

        if (activeGuids.isNotEmpty()) {
            scope.launch {
                delay(1000) // Reduced delay for better user experience
                activeGuids.forEach { guid ->
                    if (connections.containsKey(guid)) {
                        connectToGuid(guid)
                    }
                }
            }
        }
    }

    fun updateActiveListeners(listeners: List<org.opennotification.opennotification_client.data.models.WebSocketListener>) {
        scope.launch {
            val currentGuids = connections.keys.toSet()
            val activeGuids = listeners.filter { it.isActive }.map { it.guid }.toSet()

            activeListenerGuids.clear()
            activeListenerGuids.addAll(activeGuids)

            val toDisconnect = currentGuids.minus(activeGuids)
            toDisconnect.forEach { guid ->
                disconnectFromGuid(guid)
            }

            val toConnect = activeGuids.minus(currentGuids)
            toConnect.forEach { guid ->
                connectToGuid(guid)
            }

            activeGuids.forEach { guid ->
                val currentConnection = connections[guid]
                if (currentConnection == null || currentConnection.status != ConnectionStatus.CONNECTED) {
                    connectToGuid(guid)
                }
            }

            if (activeGuids.isNotEmpty() && !isMonitoringActive) {
                startConnectionMonitoring()
            } else if (activeGuids.isEmpty() && isMonitoringActive) {
                stopConnectionMonitoring()
            }
        }
    }

    fun hasActiveConnections(): Boolean {
        return connections.isNotEmpty()
    }

    fun retryErrorConnections() {
        val errorConnections = connections.filterValues { it.status == ConnectionStatus.ERROR }

        if (errorConnections.isNotEmpty()) {
            Log.i(TAG, "Retrying ${errorConnections.size} error connections")

            errorConnections.forEach { (guid, connection) ->
                if (activeListenerGuids.contains(guid)) {
                    connection.reconnectJob?.cancel()
                    connectingGuids.remove(guid)
                    connection.webSocket?.close(1000, "Error retry")
                    connectToGuid(guid)
                }
            }
        }
    }

    fun getErrorConnections(): List<String> {
        return connections.filterValues { it.status == ConnectionStatus.ERROR }.keys.toList()
    }

    fun forceReconnectAll() {
        Log.i(TAG, "Force reconnecting all active connections")

        val activeGuids = connections.keys.toList()

        activeGuids.forEach { guid ->
            connections[guid]?.let { connection ->
                connection.reconnectJob?.cancel()
                connection.webSocket?.close(1000, "Force reconnect")
            }

            connections.remove(guid)
            connectingGuids.remove(guid)

            val currentStatuses = _connectionStatuses.value.toMutableMap()
            currentStatuses.remove(guid)
            _connectionStatuses.value = currentStatuses
        }

        scope.launch {
            delay(1000)
            activeGuids.forEach { guid ->
                connectToGuid(guid)
            }
        }
    }

    private fun loadServerUrlFromPreferences(context: Context) {
        try {
            // Check if device is unlocked before accessing encrypted storage
            if (!isDeviceUnlocked(context)) {
                Log.d(TAG, "Device not yet unlocked - using default server URL")
                return
            }

            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedUrl = sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

            if (savedUrl != DEFAULT_SERVER_URL) {
                BASE_WS_URL = if (savedUrl.endsWith("/ws")) {
                    savedUrl
                } else {
                    "$savedUrl/ws"
                }
                Log.i(TAG, "Loaded server URL from preferences: $BASE_WS_URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading server URL from preferences - using default", e)
            // Use default URL if preferences can't be accessed
        }
    }

    private fun isDeviceUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }
}
