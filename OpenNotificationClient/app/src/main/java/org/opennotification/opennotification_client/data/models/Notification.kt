package org.opennotification.opennotification_client.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

@Entity(tableName = "notifications")
data class Notification(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @SerializedName("Guid") val guid: String = "",
    @SerializedName("Title") val title: String = "",
    @SerializedName("Description") val description: String? = null,
    @SerializedName("PictureLink") val pictureLink: String? = null,
    @SerializedName("IsAlert") val isAlert: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    // Secondary constructor for JSON deserialization
    constructor() : this(
        id = UUID.randomUUID().toString(),
        guid = "",
        title = "",
        description = null,
        pictureLink = null,
        isAlert = false,
        timestamp = System.currentTimeMillis()
    )
}

data class NotificationRequest(
    val guid: String,
    val title: String,
    val description: String? = null,
    val pictureLink: String? = null,
    val isAlert: Boolean = false
)
