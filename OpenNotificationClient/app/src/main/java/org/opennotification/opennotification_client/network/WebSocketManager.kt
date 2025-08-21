package org.opennotification.opennotification_client.network

import android.content.Context
import android.content.SharedPreferences
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
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier

class WebSocketManager private constructor() {
    companion object {
        private const val TAG = "WebSocketManager"
        private var BASE_WS_URL = "wss://api.opennotification.org/ws" // Updated default URL
        private const val RECONNECT_DELAY = 5000L

        // SharedPreferences constants
        private const val PREFS_NAME = "opennotification_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "https://api.opennotification.org" // Updated default URL

        @Volatile
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                val instance = WebSocketManager()
                INSTANCE = instance
                instance
            }
        }

        // Method to initialize with context to load saved settings
        fun initializeWithContext(context: Context) {
            getInstance().loadServerUrlFromPreferences(context)
        }
    }

    private val gson = GsonBuilder()
        .setLenient()
        .create()

    // Create a trust manager that accepts all certificates for development
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    // Create SSL context that uses our trust manager
    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    // Create hostname verifier that accepts all hostnames for development
    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Map of GUID to WebSocket connection and status
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()

    // Set to track GUIDs currently being connected to prevent duplicates
    private val connectingGuids = ConcurrentHashMap.newKeySet<String>()

    // StateFlow for connection status updates
    private val _connectionStatuses = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<String, ConnectionStatus>> = _connectionStatuses

    // Callback for receiving notifications
    var onNotificationReceived: ((Notification) -> Unit)? = null

    // Keep track of active listeners for automatic error retry
    private val activeListenerGuids = ConcurrentHashMap.newKeySet<String>()

    data class WebSocketConnection(
        val webSocket: WebSocket?,
        val status: ConnectionStatus,
        val reconnectJob: Job?
    )

    // Battery-efficient approach: No continuous background monitoring
    // Keep-alive and error retry is handled by alarm-based system

    fun connectToGuid(guid: String) {
        Log.d(TAG, "Connecting to GUID: $guid")

        // Check if already connected or currently connecting
        val currentConnection = connections[guid]
        if (currentConnection?.status == ConnectionStatus.CONNECTED) {
            Log.d(TAG, "Already connected to GUID: $guid")
            return
        }

        // If connecting but it's been too long, allow reconnection attempt
        if (currentConnection?.status == ConnectionStatus.CONNECTING) {
            Log.d(TAG, "Connection already in progress for GUID: $guid")
            return
        }

        // If in connecting set, remove it to allow fresh connection attempt
        if (connectingGuids.contains(guid)) {
            Log.w(TAG, "Removing stale connecting state for GUID: $guid")
            connectingGuids.remove(guid)
        }

        // Cancel any existing reconnect job before starting new connection
        currentConnection?.reconnectJob?.cancel()

        // Add to connecting set to prevent duplicates
        connectingGuids.add(guid)
        updateConnectionStatus(guid, ConnectionStatus.CONNECTING)

        // Try primary URL first
        attemptConnection(guid, BASE_WS_URL, true)
    }

    private fun attemptConnection(guid: String, wsUrl: String, isPrimaryAttempt: Boolean) {
        val request = Request.Builder()
            .url("$wsUrl/$guid")
            .build()

        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened for GUID: $guid using URL: $wsUrl")
                connectingGuids.remove(guid) // Remove from connecting set
                connections[guid] = connections[guid]?.copy(
                    webSocket = webSocket,
                    status = ConnectionStatus.CONNECTED,
                    reconnectJob = null
                ) ?: WebSocketConnection(webSocket, ConnectionStatus.CONNECTED, null)
                updateConnectionStatus(guid, ConnectionStatus.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Check if this GUID is still in our active connections before processing
                if (!connections.containsKey(guid)) {
                    Log.w(TAG, "Received message for disconnected GUID: $guid, ignoring message")
                    return
                }

                Log.d(TAG, "Message received for GUID $guid: $text")
                try {
                    val notification = gson.fromJson(text, Notification::class.java)
                    onNotificationReceived?.invoke(notification)
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
                Log.d(TAG, "WebSocket closed for GUID: $guid")
                connectingGuids.remove(guid) // Remove from connecting set
                updateConnectionStatus(guid, ConnectionStatus.DISCONNECTED)
                scheduleReconnect(guid)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure for GUID: $guid using URL: $wsUrl", t)
                connectingGuids.remove(guid) // Remove from connecting set

                // Try fallback URL if this was the primary attempt and URL ends with /
                if (isPrimaryAttempt && wsUrl.endsWith("/")) {
                    val fallbackUrl = wsUrl.removeSuffix("/")
                    Log.i(TAG, "Primary connection failed, trying fallback URL: $fallbackUrl")
                    attemptConnection(guid, fallbackUrl, false)
                    return
                }

                // If fallback also fails or no fallback needed, mark as error and schedule reconnect
                updateConnectionStatus(guid, ConnectionStatus.ERROR)
                scheduleReconnect(guid)
            }
        }

        val webSocket = okHttpClient.newWebSocket(request, webSocketListener)
        connections[guid] = WebSocketConnection(webSocket, ConnectionStatus.CONNECTING, null)
    }

    fun disconnectFromGuid(guid: String) {
        Log.i(TAG, "Disconnecting WebSocket for GUID: $guid")
        connections[guid]?.let { connection ->
            // Cancel any pending reconnection attempts
            connection.reconnectJob?.cancel()
            // Close the WebSocket connection
            connection.webSocket?.close(1000, "Listener stopped")
            Log.i(TAG, "WebSocket connection closed for GUID: $guid")
        }

        // Remove from connections map
        connections.remove(guid)
        // Remove from connecting set if it was in there
        connectingGuids.remove(guid)

        // Immediately update connection status to disconnected and remove from tracking
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

    private fun scheduleReconnect(guid: String) {
        // Only schedule reconnect if the GUID is still in the connections map
        // This prevents reconnecting to inactive listeners
        if (!connections.containsKey(guid)) {
            Log.d(TAG, "Not scheduling reconnect for GUID: $guid - listener was disconnected")
            return
        }

        val reconnectJob = scope.launch {
            kotlinx.coroutines.delay(RECONNECT_DELAY)
            // Double-check that the GUID is still in connections before reconnecting
            // This handles the case where a listener was deactivated during the delay
            if (connections.containsKey(guid)) {
                Log.d(TAG, "Attempting to reconnect to GUID: $guid")
                connectToGuid(guid)
            } else {
                Log.d(TAG, "Skipping reconnect for GUID: $guid - listener was deactivated")
            }
        }

        connections[guid] = connections[guid]?.copy(reconnectJob = reconnectJob)
            ?: WebSocketConnection(null, ConnectionStatus.DISCONNECTED, reconnectJob)
    }

    private fun updateConnectionStatus(guid: String, status: ConnectionStatus) {
        Log.i(TAG, "Connection status updated for GUID: $guid -> $status")
        val currentStatuses = _connectionStatuses.value.toMutableMap()
        currentStatuses[guid] = status
        _connectionStatuses.value = currentStatuses

        // Log current connection summary
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

    /**
     * Update the WebSocket server URL and reconnect active connections
     */
    fun updateServerUrl(newBaseUrl: String) {
        Log.i(TAG, "Updating server URL from $BASE_WS_URL to $newBaseUrl")

        // Store the currently active GUIDs before disconnecting
        val activeGuids = connections.keys.toList()
        Log.i(TAG, "Preserving ${activeGuids.size} active connections for URL change")

        // Disconnect all current connections but don't remove them from tracking
        activeGuids.forEach { guid ->
            connections[guid]?.let { connection ->
                // Cancel any pending reconnection attempts
                connection.reconnectJob?.cancel()
                // Close the WebSocket connection
                connection.webSocket?.close(1000, "Server URL changed")
                Log.i(TAG, "Disconnected GUID: $guid for URL change")
            }

            // Keep connection in tracking but mark as disconnected
            connections[guid] = WebSocketConnection(null, ConnectionStatus.DISCONNECTED, null)
            updateConnectionStatus(guid, ConnectionStatus.DISCONNECTED)
        }

        // Update the base URL
        BASE_WS_URL = if (newBaseUrl.endsWith("/ws")) {
            newBaseUrl
        } else {
            "$newBaseUrl/ws"
        }

        Log.i(TAG, "Server URL updated to: $BASE_WS_URL")

        // Immediately reconnect all previously active listeners with new URL
        if (activeGuids.isNotEmpty()) {
            scope.launch {
                kotlinx.coroutines.delay(500) // Brief delay for clean disconnection
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
            // Get currently connected GUIDs
            val currentGuids = connections.keys.toSet()

            // Get GUIDs that should be active
            val activeGuids = listeners.filter { it.isActive }.map { it.guid }.toSet()

            // Update the active listeners tracking for error retry monitor
            activeListenerGuids.clear()
            activeListenerGuids.addAll(activeGuids)

            Log.i(TAG, "Updating active listeners:")
            Log.i(TAG, "  Current connections: ${currentGuids.joinToString()}")
            Log.i(TAG, "  Should be active: ${activeGuids.joinToString()}")

            // Disconnect listeners that are no longer active or were stopped
            val toDisconnect = currentGuids.minus(activeGuids)
            if (toDisconnect.isNotEmpty()) {
                Log.i(TAG, "Disconnecting inactive listeners: ${toDisconnect.joinToString()}")
                toDisconnect.forEach { guid ->
                    Log.i(TAG, "Stopping listener for GUID: $guid - closing WebSocket connection")
                    disconnectFromGuid(guid)
                }
            }

            // Connect new active listeners
            val toConnect = activeGuids.minus(currentGuids)
            if (toConnect.isNotEmpty()) {
                Log.i(TAG, "Connecting new active listeners: ${toConnect.joinToString()}")
                toConnect.forEach { guid ->
                    Log.i(TAG, "Starting listener for GUID: $guid - establishing WebSocket connection")
                    connectToGuid(guid)
                }
            }

            // CRITICAL FIX: Also ensure ALL active listeners are connected, even if they appear to already be connected
            // This handles the case where the service restarted and lost WebSocket connections
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

    /**
     * Send keep-alive pings to all connected WebSockets (called by alarm)
     */
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

    /**
     * Retry connections that are in ERROR state and should be active (called by alarm)
     */
    fun retryErrorConnections() {
        val errorConnections = connections.filterValues { it.status == ConnectionStatus.ERROR }

        if (errorConnections.isNotEmpty()) {
            Log.i(TAG, "Retrying ${errorConnections.size} error connections")

            errorConnections.forEach { (guid, connection) ->
                // Only retry if this GUID should still be active
                if (activeListenerGuids.contains(guid)) {
                    Log.i(TAG, "Forcing fresh connection attempt for error GUID: $guid")

                    // Cancel any existing reconnect job
                    connection.reconnectJob?.cancel()

                    // Remove from connecting set to allow fresh attempt
                    connectingGuids.remove(guid)

                    // Close any existing WebSocket connection
                    connection.webSocket?.close(1000, "Error retry")

                    // Force a fresh connection attempt
                    connectToGuid(guid)
                }
            }
        }
    }

    /**
     * Get connections that are in ERROR state
     */
    fun getErrorConnections(): List<String> {
        return connections.filterValues { it.status == ConnectionStatus.ERROR }.keys.toList()
    }

    /**
     * Force reconnect all active connections - disconnects and reconnects all current connections
     */
    fun forceReconnectAll() {
        Log.i(TAG, "Force reconnecting all active connections")

        // Get all current connection GUIDs
        val activeGuids = connections.keys.toList()

        // Disconnect all connections first
        activeGuids.forEach { guid ->
            connections[guid]?.let { connection ->
                // Cancel any pending reconnection attempts
                connection.reconnectJob?.cancel()
                // Close the WebSocket connection
                connection.webSocket?.close(1000, "Force reconnect")
                Log.i(TAG, "WebSocket connection closed for GUID: $guid")
            }

            // Remove from connections map
            connections.remove(guid)
            // Remove from connecting set if it was in there
            connectingGuids.remove(guid)

            // Update status to disconnected
            val currentStatuses = _connectionStatuses.value.toMutableMap()
            currentStatuses.remove(guid)
            _connectionStatuses.value = currentStatuses

            Log.i(TAG, "WebSocket connection and status tracking removed for GUID: $guid")
        }

        // Give a moment for disconnections to complete, then reconnect
        scope.launch {
            kotlinx.coroutines.delay(500) // Wait 500ms for clean disconnection

            Log.i(TAG, "Starting forced reconnection for ${activeGuids.size} listeners")

            // Reconnect to all previously active GUIDs
            activeGuids.forEach { guid ->
                connectToGuid(guid)
            }

            Log.i(TAG, "Force reconnect completed for all active listeners")
        }
    }

    /**
     * Load server URL from SharedPreferences
     */
    private fun loadServerUrlFromPreferences(context: Context) {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedUrl = sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

            // Update the base URL if it's different from default
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
