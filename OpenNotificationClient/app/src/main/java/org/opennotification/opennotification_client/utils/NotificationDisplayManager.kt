package org.opennotification.opennotification_client.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opennotification.opennotification_client.MainActivity
import org.opennotification.opennotification_client.R
import org.opennotification.opennotification_client.data.models.Notification
import java.net.URL

class NotificationDisplayManager(private val context: Context) {
    companion object {
        private const val TAG = "NotificationDisplayManager"
        private const val WEBSOCKET_MESSAGES_CHANNEL_ID = "websocket_messages_channel"
        private const val WEBSOCKET_MESSAGES_CHANNEL_NAME = "WebSocket Messages"
        private const val ALERTS_CHANNEL_ID = "alerts_channel"
        private const val ALERTS_CHANNEL_NAME = "Alert Messages"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel for regular WebSocket messages
            val messagesChannel = NotificationChannel(
                WEBSOCKET_MESSAGES_CHANNEL_ID,
                WEBSOCKET_MESSAGES_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for incoming WebSocket messages"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            // Channel for alert messages (higher priority)
            val alertsChannel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                ALERTS_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alert notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    fun showNotification(notification: Notification) {
        try {
            Log.d(TAG, "Showing notification: ${notification.title}")

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("notification_id", notification.id)
                putExtra("guid", notification.guid)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notification.hashCode(),
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            val channelId = if (notification.isAlert) ALERTS_CHANNEL_ID else WEBSOCKET_MESSAGES_CHANNEL_ID

            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(notification.timestamp)
                .setShowWhen(true)
                .setPriority(if (notification.isAlert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (notification.isAlert) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)

            // Add description if available
            if (!notification.description.isNullOrBlank()) {
                notificationBuilder.setContentText(notification.description)
                notificationBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(notification.description)
                )
            }

            // Handle picture if available
            if (!notification.pictureLink.isNullOrBlank()) {
                // Load image asynchronously and update notification
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val bitmap = loadImageFromUrl(notification.pictureLink)
                        if (bitmap != null) {
                            // Update notification with image
                            val updatedBuilder = NotificationCompat.Builder(context, channelId)
                                .setSmallIcon(R.drawable.ic_notification)
                                .setContentTitle(notification.title)
                                .setContentIntent(pendingIntent)
                                .setAutoCancel(true)
                                .setWhen(notification.timestamp)
                                .setShowWhen(true)
                                .setPriority(if (notification.isAlert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                                .setCategory(if (notification.isAlert) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
                                .setLargeIcon(bitmap)

                            // Add description and image in big picture style
                            if (!notification.description.isNullOrBlank()) {
                                updatedBuilder.setContentText(notification.description)
                                updatedBuilder.setStyle(
                                    NotificationCompat.BigPictureStyle()
                                        .bigPicture(bitmap)
                                        .setSummaryText(notification.description)
                                        .setBigContentTitle(notification.title)
                                )
                            } else {
                                updatedBuilder.setStyle(
                                    NotificationCompat.BigPictureStyle()
                                        .bigPicture(bitmap)
                                        .setBigContentTitle(notification.title)
                                )
                            }

                            withContext(Dispatchers.Main) {
                                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                    notificationManager.notify(notification.hashCode(), updatedBuilder.build())
                                    Log.i(TAG, "Notification with image displayed: ${notification.title}")
                                }
                            }
                        } else {
                            // Show notification without image if loading failed
                            showNotificationWithoutImage(notificationBuilder, notification)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load image from URL: ${notification.pictureLink}", e)
                        // Show notification without image if loading failed
                        showNotificationWithoutImage(notificationBuilder, notification)
                    }
                }
            } else {
                // Show notification without image
                showNotificationWithoutImage(notificationBuilder, notification)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    private fun showNotificationWithoutImage(builder: NotificationCompat.Builder, notification: Notification) {
        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                notificationManager.notify(notification.hashCode(), builder.build())
                Log.i(TAG, "Notification displayed: ${notification.title}")
            } else {
                Log.w(TAG, "Notifications are disabled, cannot show notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification without image", e)
        }
    }

    private suspend fun loadImageFromUrl(imageUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading image from URL: $imageUrl")
                val url = URL(imageUrl)
                val connection = url.openConnection()
                connection.connectTimeout = 10000 // 10 seconds
                connection.readTimeout = 10000 // 10 seconds
                connection.doInput = true
                connection.connect()

                val inputStream = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap != null) {
                    Log.d(TAG, "Successfully loaded image: ${bitmap.width}x${bitmap.height}")
                    // Resize bitmap if it's too large
                    if (bitmap.width > 1024 || bitmap.height > 1024) {
                        val scaleFactor = minOf(1024.0 / bitmap.width, 1024.0 / bitmap.height)
                        val scaledWidth = (bitmap.width * scaleFactor).toInt()
                        val scaledHeight = (bitmap.height * scaleFactor).toInt()
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                        bitmap.recycle()
                        scaledBitmap
                    } else {
                        bitmap
                    }
                } else {
                    Log.w(TAG, "Failed to decode bitmap from URL: $imageUrl")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from URL: $imageUrl", e)
                null
            }
        }
    }

    fun cancelNotification(notificationId: Int) {
        try {
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Cancelled notification with ID: $notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification", e)
        }
    }

    fun cancelAllMessageNotifications() {
        try {
            // This will only cancel notifications created by this app
            notificationManager.cancelAll()
            Log.d(TAG, "Cancelled all message notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel all notifications", e)
        }
    }
}
