package org.opennotification.opennotification_client.repository

import kotlinx.coroutines.flow.Flow
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.data.models.Notification
import org.opennotification.opennotification_client.data.models.WebSocketListener

class NotificationRepository(private val database: AppDatabase) {

    private val notificationDao = database.notificationDao()
    private val listenerDao = database.webSocketListenerDao()

    // Notification operations
    fun getAllNotifications(): Flow<List<Notification>> {
        return notificationDao.getAllNotifications()
    }

    suspend fun insertNotification(notification: Notification) {
        notificationDao.insertNotification(notification)
    }

    suspend fun deleteNotification(notification: Notification) {
        notificationDao.deleteNotification(notification)
    }

    suspend fun deleteAllNotifications() {
        notificationDao.deleteAllNotifications()
    }

    suspend fun getNotificationCount(): Int {
        return notificationDao.getNotificationCount()
    }

    // WebSocket Listener operations
    fun getAllListeners(): Flow<List<WebSocketListener>> {
        return listenerDao.getAllListeners()
    }

    fun getActiveListeners(): Flow<List<WebSocketListener>> {
        return listenerDao.getActiveListeners()
    }

    suspend fun insertListener(listener: WebSocketListener) {
        listenerDao.insertListener(listener)
    }

    suspend fun updateListener(listener: WebSocketListener) {
        listenerDao.updateListener(listener)
    }

    suspend fun deleteListener(listener: WebSocketListener) {
        listenerDao.deleteListener(listener)
    }

    suspend fun deleteAllListeners() {
        listenerDao.deleteAllListeners()
    }

    suspend fun getListenerByGuid(guid: String): WebSocketListener? {
        return listenerDao.getListenerByGuid(guid)
    }
}
