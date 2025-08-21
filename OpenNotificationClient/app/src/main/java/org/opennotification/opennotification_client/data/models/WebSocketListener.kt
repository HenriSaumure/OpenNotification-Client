package org.opennotification.opennotification_client.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "websocket_listeners")
data class WebSocketListener(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val guid: String,
    val name: String? = null,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnected: Long? = null
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
