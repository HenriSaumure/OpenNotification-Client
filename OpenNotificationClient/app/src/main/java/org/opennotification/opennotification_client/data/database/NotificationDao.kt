package org.opennotification.opennotification_client.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.opennotification.opennotification_client.data.models.Notification

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<Notification>>

    @Query("SELECT * FROM notifications WHERE guid = :guid ORDER BY timestamp DESC")
    fun getNotificationsByGuid(guid: String): Flow<List<Notification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: Notification)

    @Update
    suspend fun updateNotification(notification: Notification)

    @Delete
    suspend fun deleteNotification(notification: Notification)

    @Query("DELETE FROM notifications WHERE guid = :guid")
    suspend fun deleteNotificationsByGuid(guid: String)

    @Query("DELETE FROM notifications")
    suspend fun deleteAllNotifications()
}
