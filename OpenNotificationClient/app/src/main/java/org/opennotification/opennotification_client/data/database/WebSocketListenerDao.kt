package org.opennotification.opennotification_client.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.opennotification.opennotification_client.data.models.WebSocketListener

@Dao
interface WebSocketListenerDao {
    @Query("SELECT * FROM websocket_listeners ORDER BY createdAt DESC")
    fun getAllListeners(): Flow<List<WebSocketListener>>

    @Query("SELECT * FROM websocket_listeners WHERE isActive = 1")
    fun getActiveListeners(): Flow<List<WebSocketListener>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListener(listener: WebSocketListener)

    @Update
    suspend fun updateListener(listener: WebSocketListener)

    @Delete
    suspend fun deleteListener(listener: WebSocketListener)

    @Query("DELETE FROM websocket_listeners")
    suspend fun deleteAllListeners()

    @Query("SELECT * FROM websocket_listeners WHERE guid = :guid LIMIT 1")
    suspend fun getListenerByGuid(guid: String): WebSocketListener?
}
