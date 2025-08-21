package org.opennotification.opennotification_client.repository

import kotlinx.coroutines.flow.Flow
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.data.models.Notification
import org.opennotification.opennotification_client.data.models.WebSocketListener

class NotificationRepository(private val database: AppDatabase) {

    fun getAllNotifications(): Flow<List<Notification>> {
        return database.notificationDao().getAllNotifications()
    }

    fun getNotificationsByGuid(guid: String): Flow<List<Notification>> {
        return database.notificationDao().getNotificationsByGuid(guid)
    }

    suspend fun insertNotification(notification: Notification) {
        database.notificationDao().insertNotification(notification)
    }

    suspend fun deleteNotification(notification: Notification) {
        database.notificationDao().deleteNotification(notification)
    }

    suspend fun deleteNotificationsByGuid(guid: String) {
        database.notificationDao().deleteNotificationsByGuid(guid)
    }

    fun getAllListeners(): Flow<List<WebSocketListener>> {
        return database.webSocketListenerDao().getAllListeners()
    }

    fun getActiveListeners(): Flow<List<WebSocketListener>> {
        return database.webSocketListenerDao().getActiveListeners()
    }

    suspend fun insertListener(listener: WebSocketListener) {
        database.webSocketListenerDao().insertListener(listener)
    }

    suspend fun updateListener(listener: WebSocketListener) {
        database.webSocketListenerDao().updateListener(listener)
    }

    suspend fun deleteListener(listener: WebSocketListener) {
        database.webSocketListenerDao().deleteListener(listener)
    }

    suspend fun updateListenerStatus(id: String, isActive: Boolean) {
        database.webSocketListenerDao().updateListenerStatus(id, isActive)
    }

    suspend fun getListenerByGuid(guid: String): WebSocketListener? {
        return database.webSocketListenerDao().getListenerByGuid(guid)
    }
}
