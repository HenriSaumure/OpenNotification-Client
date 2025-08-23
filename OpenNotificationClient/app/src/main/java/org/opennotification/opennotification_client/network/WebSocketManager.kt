package org.opennotification.opennotification_client.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        private const val RECONNECT_DELAY = 5000L
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

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()
    private val connectingGuids = ConcurrentHashMap.newKeySet<String>()
    private val _connectionStatuses = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<String, ConnectionStatus>> = _connectionStatuses
    var onNotificationReceived: ((Notification) -> Unit)? = null
    private val activeListenerGuids = ConcurrentHashMap.newKeySet<String>()

    data class WebSocketConnection(
        val webSocket: WebSocket?,
        val status: ConnectionStatus,
        val reconnectJob: Job?,
        val lastReconnectAttempt: Long = 0L
    )

    fun connectToGuid(guid: String) {
        Log.d(TAG, "Connecting to GUID: $guid")

        val currentConnection = connections[guid]
        Log.d(TAG, "Current connection for GUID $guid: status=${currentConnection?.status}, webSocket=${currentConnection?.webSocket != null}")

        val isActuallyConnected = currentConnection?.webSocket?.let { ws ->
            try {
                val wasConnected = currentConnection.status == ConnectionStatus.CONNECTED
                if (wasConnected) {
                    val testResult = ws.send("")
                    if (!testResult) {
                        Log.w(TAG, "WebSocket send test failed for GUID: $guid - connection is dead")
                        return@let false
                    }
                }
                wasConnected
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket test failed for GUID: $guid, connection appears dead", e)
                false
            }
        } ?: false

        Log.d(TAG, "Actual connection state for GUID $guid: stored_status=${currentConnection?.status}, actually_connected=$isActuallyConnected")

        val wasJustMarkedAsError = currentConnection?.status == ConnectionStatus.ERROR
        if (wasJustMarkedAsError) {
            Log.w(TAG, "Connection was marked as ERROR for GUID: $guid - forcing fresh reconnection")
        }

        if (isActuallyConnected && currentConnection?.status == ConnectionStatus.CONNECTED && !wasJustMarkedAsError) {
            Log.d(TAG, "Already connected to GUID: $guid (verified working connection)")
            return
        }

        if ((currentConnection?.status == ConnectionStatus.CONNECTED && !isActuallyConnected) || wasJustMarkedAsError) {
            Log.w(TAG, "Connection marked as ${currentConnection?.status} but forcing cleanup for GUID: $guid")
            try {
                currentConnection?.webSocket?.close(1000, "Force cleanup due to ${if (wasJustMarkedAsError) "ERROR state" else "dead connection"}")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing WebSocket during cleanup for GUID: $guid", e)
            }
            connections.remove(guid)
            connectingGuids.remove(guid)
        }

        if (currentConnection?.status == ConnectionStatus.ERROR) {
            Log.i(TAG, "Connection in ERROR state for GUID: $guid - forcing reconnection")
        }

        if (currentConnection?.status == ConnectionStatus.DISCONNECTED) {
            Log.i(TAG, "Connection in DISCONNECTED state for GUID: $guid - proceeding with reconnection")
        }

        val preventionThreshold = if (wasJustMarkedAsError) 500L else 1000L
        val timeSinceLastAttempt = System.currentTimeMillis() - (currentConnection?.lastReconnectAttempt ?: 0L)

        if (timeSinceLastAttempt < preventionThreshold) {
            Log.d(TAG, "Preventing rapid reconnection attempt for GUID: $guid (${timeSinceLastAttempt}ms ago, threshold: ${preventionThreshold}ms)")
            return
        }

        if (currentConnection?.status == ConnectionStatus.CONNECTING && timeSinceLastAttempt > 30000L) {
            Log.w(TAG, "Connection stuck in CONNECTING state for ${timeSinceLastAttempt}ms - forcing fresh attempt")
        } else if (currentConnection?.status == ConnectionStatus.CONNECTING && timeSinceLastAttempt < 30000L) {
            Log.d(TAG, "Connection already in progress for GUID: $guid (${timeSinceLastAttempt}ms ago)")
            return
        }

        if (connectingGuids.contains(guid)) {
            Log.w(TAG, "Removing stale connecting state for GUID: $guid")
            connectingGuids.remove(guid)
        }

        connections[guid]?.reconnectJob?.cancel()

        connections[guid]?.webSocket?.let { ws ->
            Log.i(TAG, "Closing existing WebSocket for fresh connection attempt for GUID: $guid")
            try {
                ws.close(1000, "Fresh connection attempt")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing WebSocket for GUID: $guid", e)
            }
        }

        Log.i(TAG, "Proceeding with fresh connection attempt for GUID: $guid (previous status: ${currentConnection?.status})")

        connectingGuids.add(guid)
        updateConnectionStatus(guid, ConnectionStatus.CONNECTING)
        attemptConnection(guid, BASE_WS_URL, true)
    }

    private fun attemptConnection(guid: String, wsUrl: String, isPrimaryAttempt: Boolean) {
        val request = Request.Builder()
            .url("$wsUrl/$guid")
            .addHeader("User-Agent", "OpenNotification-Android/1.0")
            .build()

        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened for GUID: $guid using URL: $wsUrl")
                connectingGuids.remove(guid)
                connections[guid] = WebSocketConnection(
                    webSocket = webSocket,
                    status = ConnectionStatus.CONNECTED,
                    reconnectJob = null,
                    lastReconnectAttempt = System.currentTimeMillis()
                )
                updateConnectionStatus(guid, ConnectionStatus.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
                    Log.d(TAG, "Parsed notification - Title: ${notification.title}, IsAlert: ${notification.isAlert}, Description: ${notification.description}")

                    val correctedNotification = if (!notification.isAlert && text.contains("\"IsAlert\":\"true\"", ignoreCase = true)) {
                        Log.w(TAG, "Detected string 'true' for IsAlert field, correcting to boolean true")
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
                Log.d(TAG, "WebSocket closing for GUID: $guid, code: $code, reason: $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed for GUID: $guid")
                connectingGuids.remove(guid)
                updateConnectionStatus(guid, ConnectionStatus.DISCONNECTED)
                scheduleReconnect(guid, "Connection closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure for GUID: $guid using URL: $wsUrl", t)
                connectingGuids.remove(guid)

                val isConnectionIssue = t.message?.contains("Software caused connection abort") == true ||
                        t.message?.contains("Connection reset") == true ||
                        t.message?.contains("Connection refused") == true

                if (isConnectionIssue) {
                    Log.w(TAG, "Detected connection issue for GUID: $guid - scheduling immediate reconnect")
                }

                if (isPrimaryAttempt && wsUrl.endsWith("/")) {
                    val fallbackUrl = wsUrl.removeSuffix("/")
                    Log.i(TAG, "Primary connection failed, trying fallback URL: $fallbackUrl")
                    attemptConnection(guid, fallbackUrl, false)
                    return
                }

                updateConnectionStatus(guid, ConnectionStatus.ERROR)
                val reconnectDelay = if (isConnectionIssue) 2000L else RECONNECT_DELAY
                scheduleReconnect(guid, "Connection failed: ${t.message}", reconnectDelay)
            }
        }

        val webSocket = okHttpClient.newWebSocket(request, webSocketListener)
        connections[guid] = WebSocketConnection(
            webSocket = webSocket,
            status = ConnectionStatus.CONNECTING,
            reconnectJob = null,
            lastReconnectAttempt = System.currentTimeMillis()
        )
    }

    fun disconnectFromGuid(guid: String) {
        Log.i(TAG, "Disconnecting WebSocket for GUID: $guid")
        connections[guid]?.let { connection ->
            connection.reconnectJob?.cancel()
            connection.webSocket?.close(1000, "Listener stopped")
            Log.i(TAG, "WebSocket connection closed for GUID: $guid")
        }

        connections.remove(guid)
        connectingGuids.remove(guid)

        val currentStatuses = _connectionStatuses.value.toMutableMap()
        currentStatuses.remove(guid)
        _connectionStatuses.value = currentStatuses

        Log.i(TAG, "WebSocket connection and status tracking removed for GUID: $guid")
    }

    fun disconnectAll() {
        Log.d(TAG, "Disconnecting all WebSocket connections")
        connections.keys.forEach { guid ->
            disconnectFromGuid(guid)
        }
    }

    private fun scheduleReconnect(guid: String, reason: String = "Unknown", delay: Long = RECONNECT_DELAY) {
        if (!connections.containsKey(guid)) {
            Log.d(TAG, "Not scheduling reconnect for GUID: $guid - listener was disconnected")
            return
        }

        Log.i(TAG, "Scheduling reconnect for GUID: $guid in ${delay}ms due to: $reason")

        val reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            if (connections.containsKey(guid)) {
                Log.d(TAG, "Attempting to reconnect to GUID: $guid after $reason")
                connectToGuid(guid)
            } else {
                Log.d(TAG, "Skipping reconnect for GUID: $guid - listener was deactivated")
            }
        }

        connections[guid] = connections[guid]?.copy(
            reconnectJob = reconnectJob,
            lastReconnectAttempt = System.currentTimeMillis()
        ) ?: WebSocketConnection(
            webSocket = null,
            status = ConnectionStatus.DISCONNECTED,
            reconnectJob = reconnectJob,
            lastReconnectAttempt = System.currentTimeMillis()
        )
    }

    private fun updateConnectionStatus(guid: String, status: ConnectionStatus) {
        Log.i(TAG, "Connection status updated for GUID: $guid -> $status")
        val currentStatuses = _connectionStatuses.value.toMutableMap()
        currentStatuses[guid] = status
        _connectionStatuses.value = currentStatuses

        val totalConnections = connections.size
        val activeConnections = connections.values.count { it.status == ConnectionStatus.CONNECTED }
        Log.i(TAG, "Connection summary: $activeConnections/$totalConnections active connections")
    }

    fun getConnectionStatus(guid: String): ConnectionStatus {
        val status = connections[guid]?.status ?: ConnectionStatus.DISCONNECTED
        Log.v(TAG, "Getting connection status for GUID: $guid -> $status")
        return status
    }

    fun isConnected(guid: String): Boolean {
        val isConnected = connections[guid]?.status == ConnectionStatus.CONNECTED
        Log.v(TAG, "Checking if GUID: $guid is connected -> $isConnected")
        return isConnected
    }

    fun getAllConnectionStatuses(): Map<String, ConnectionStatus> {
        return connections.mapValues { it.value.status }
    }

    fun updateServerUrl(newBaseUrl: String) {
        Log.i(TAG, "Updating server URL from $BASE_WS_URL to $newBaseUrl")

        val activeGuids = connections.keys.toList()
        Log.i(TAG, "Preserving ${activeGuids.size} active connections for URL change")

        activeGuids.forEach { guid ->
            connections[guid]?.let { connection ->
                connection.reconnectJob?.cancel()
                connection.webSocket?.close(1000, "Server URL changed")
                Log.i(TAG, "Disconnected GUID: $guid for URL change")
            }

            connections[guid] = WebSocketConnection(null, ConnectionStatus.DISCONNECTED, null)
            updateConnectionStatus(guid, ConnectionStatus.DISCONNECTED)
        }

        BASE_WS_URL = if (newBaseUrl.endsWith("/ws")) {
            newBaseUrl
        } else {
            "$newBaseUrl/ws"
        }

        Log.i(TAG, "Server URL updated to: $BASE_WS_URL")

        if (activeGuids.isNotEmpty()) {
            scope.launch {
                kotlinx.coroutines.delay(500)
                Log.i(TAG, "Reconnecting ${activeGuids.size} listeners with new URL")

                activeGuids.forEach { guid ->
                    if (connections.containsKey(guid)) {
                        Log.i(TAG, "Reconnecting GUID: $guid to new URL")
                        connectToGuid(guid)
                    }
                }

                Log.i(TAG, "URL change reconnection completed")
            }
        }
    }

    fun updateActiveListeners(listeners: List<org.opennotification.opennotification_client.data.models.WebSocketListener>) {
        scope.launch {
            val currentGuids = connections.keys.toSet()
            val activeGuids = listeners.filter { it.isActive }.map { it.guid }.toSet()

            activeListenerGuids.clear()
            activeListenerGuids.addAll(activeGuids)

            Log.i(TAG, "Updating active listeners:")
            Log.i(TAG, "  Current connections: ${currentGuids.joinToString()}")
            Log.i(TAG, "  Should be active: ${activeGuids.joinToString()}")

            val toDisconnect = currentGuids.minus(activeGuids)
            if (toDisconnect.isNotEmpty()) {
                Log.i(TAG, "Disconnecting inactive listeners: ${toDisconnect.joinToString()}")
                toDisconnect.forEach { guid ->
                    Log.i(TAG, "Stopping listener for GUID: $guid - closing WebSocket connection")
                    disconnectFromGuid(guid)
                }
            }

            val toConnect = activeGuids.minus(currentGuids)
            if (toConnect.isNotEmpty()) {
                Log.i(TAG, "Connecting new active listeners: ${toConnect.joinToString()}")
                toConnect.forEach { guid ->
                    Log.i(TAG, "Starting listener for GUID: $guid - establishing WebSocket connection")
                    connectToGuid(guid)
                }
            }

            activeGuids.forEach { guid ->
                val currentConnection = connections[guid]
                if (currentConnection == null || currentConnection.status != ConnectionStatus.CONNECTED) {
                    Log.i(TAG, "Re-establishing connection for GUID: $guid (status: ${currentConnection?.status})")
                    connectToGuid(guid)
                }
            }

            if (toDisconnect.isEmpty() && toConnect.isEmpty()) {
                Log.d(TAG, "No changes needed for active listeners, but verified all connections are active")
            }

            Log.i(TAG, "Active listeners update completed: ${activeGuids.size} active, ${toDisconnect.size} stopped, ${toConnect.size} started")
        }
    }

    fun hasActiveConnections(): Boolean {
        return connections.isNotEmpty()
    }

    fun sendKeepAlivePings() {
        Log.d(TAG, "Sending keep-alive pings to ${connections.size} connections")

        connections.forEach { (guid, connection) ->
            try {
                if (connection.status == ConnectionStatus.CONNECTED && connection.webSocket != null) {
                    val pingResult = connection.webSocket.send("ping")
                    if (pingResult) {
                        Log.v(TAG, "Keep-alive ping sent to GUID: $guid")
                    } else {
                        Log.w(TAG, "Failed to send keep-alive ping to GUID: $guid")
                        updateConnectionStatus(guid, ConnectionStatus.ERROR)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending keep-alive ping to GUID: $guid", e)
                updateConnectionStatus(guid, ConnectionStatus.ERROR)
            }
        }
    }

    fun retryErrorConnections() {
        val errorConnections = connections.filterValues { it.status == ConnectionStatus.ERROR }

        if (errorConnections.isNotEmpty()) {
            Log.i(TAG, "Retrying ${errorConnections.size} error connections")

            errorConnections.forEach { (guid, connection) ->
                if (activeListenerGuids.contains(guid)) {
                    Log.i(TAG, "Forcing fresh connection attempt for error GUID: $guid")

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
                Log.i(TAG, "WebSocket connection closed for GUID: $guid")
            }

            connections.remove(guid)
            connectingGuids.remove(guid)

            val currentStatuses = _connectionStatuses.value.toMutableMap()
            currentStatuses.remove(guid)
            _connectionStatuses.value = currentStatuses

            Log.i(TAG, "WebSocket connection and status tracking removed for GUID: $guid")
        }

        scope.launch {
            kotlinx.coroutines.delay(500)

            Log.i(TAG, "Starting forced reconnection for ${activeGuids.size} listeners")

            activeGuids.forEach { guid ->
                connectToGuid(guid)
            }

            Log.i(TAG, "Force reconnect completed for all active listeners")
        }
    }

    private fun loadServerUrlFromPreferences(context: Context) {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedUrl = sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

            if (savedUrl != DEFAULT_SERVER_URL) {
                BASE_WS_URL = if (savedUrl.endsWith("/ws")) {
                    savedUrl
                } else {
                    "$savedUrl/ws"
                }
                Log.i(TAG, "Loaded server URL from preferences: $BASE_WS_URL")
            } else {
                Log.d(TAG, "Using default server URL: $BASE_WS_URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading server URL from preferences, using default", e)
        }
    }
}
