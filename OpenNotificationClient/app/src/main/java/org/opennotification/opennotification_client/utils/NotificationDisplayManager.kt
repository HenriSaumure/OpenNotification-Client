package org.opennotification.opennotification_client.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import org.opennotification.opennotification_client.service.FullScreenOverlayService
import java.net.URL

class NotificationDisplayManager(private val context: Context) {
    companion object {
        private const val TAG = "NotificationDisplayManager"
        private const val WEBSOCKET_MESSAGES_CHANNEL_ID = "websocket_messages_channel"
        private const val WEBSOCKET_MESSAGES_CHANNEL_NAME = "WebSocket Messages"
        private const val ALERTS_CHANNEL_ID = "alerts_channel"
        private const val ALERTS_CHANNEL_NAME = "Alert Messages"
        private const val FULL_SCREEN_ALERTS_CHANNEL_ID = "full_screen_alerts_channel"
        private const val FULL_SCREEN_ALERTS_CHANNEL_NAME = "Full Screen Alerts"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

            val fullScreenAlertsChannel = NotificationChannel(
                FULL_SCREEN_ALERTS_CHANNEL_ID,
                FULL_SCREEN_ALERTS_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full screen alert notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                setBypassDnd(true)
            }

            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(alertsChannel)
            notificationManager.createNotificationChannel(fullScreenAlertsChannel)
        }
    }

    fun showNotification(notification: Notification) {
        try {
            Log.d(TAG, "Showing notification: ${notification.title}, isAlert: ${notification.isAlert}")

            if (notification.isAlert) {
                showFullScreenAlert(notification)
                return
            }

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

            val channelId = WEBSOCKET_MESSAGES_CHANNEL_ID

            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(notification.timestamp)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)

            if (!notification.actionLink.isNullOrBlank()) {
                addActionButton(notificationBuilder, notification)
            }

            if (!notification.description.isNullOrBlank()) {
                notificationBuilder.setContentText(notification.description)
                notificationBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(notification.description)
                )
            }

            loadNotificationImages(notificationBuilder, notification, channelId, pendingIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    private fun showFullScreenAlert(notification: Notification) {
        try {
            Log.d(TAG, "Showing full-screen overlay alert: ${notification.title}")

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted, falling back to regular notification")
                showRegularNotificationAsFallback(notification)
                return
            }

            try {
                FullScreenOverlayService.showAlert(
                    context = context,
                    title = notification.title,
                    description = notification.description,
                    pictureLink = notification.pictureLink,
                    icon = notification.icon,
                    actionLink = notification.actionLink,
                    guid = notification.guid
                )
                Log.i(TAG, "Full-screen overlay service started successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show overlay, falling back to notification", e)
                showRegularNotificationAsFallback(notification)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show full-screen alert", e)
            showRegularNotificationAsFallback(notification)
        }
    }

    private fun showRegularNotificationAsFallback(notification: Notification) {
        try {
            Log.w(TAG, "Falling back to regular notification for alert: ${notification.title}")

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("notification_id", notification.id)
                putExtra("guid", notification.guid)
                putExtra("is_alert_fallback", true)
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

            val notificationBuilder = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⚠️ ${notification.title}")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            if (!notification.description.isNullOrBlank()) {
                notificationBuilder.setContentText(notification.description)
                notificationBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(notification.description)
                )
            }

            if (!notification.actionLink.isNullOrBlank()) {
                addActionButton(notificationBuilder, notification)
            }

            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                notificationManager.notify(notification.hashCode(), notificationBuilder.build())
                Log.i(TAG, "Alert fallback notification displayed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show fallback notification", e)
        }
    }

    private fun addActionButton(builder: NotificationCompat.Builder, notification: Notification) {
        try {
            val actionIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(notification.actionLink)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val actionPendingIntent = PendingIntent.getActivity(
                context,
                notification.hashCode() + 1000,
                actionIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            builder.addAction(
                R.drawable.ic_notification,
                "Open Link",
                actionPendingIntent
            )

            Log.d(TAG, "Added action button for: ${notification.actionLink}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add action button", e)
        }
    }

    private fun loadNotificationImages(
        builder: NotificationCompat.Builder,
        notification: Notification,
        channelId: String,
        pendingIntent: PendingIntent
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var iconBitmap: Bitmap? = null
                var pictureBitmap: Bitmap? = null

                if (!notification.icon.isNullOrBlank()) {
                    iconBitmap = loadImageFromUrl(notification.icon)
                    Log.d(TAG, "Icon loaded: ${iconBitmap != null}")
                }

                if (!notification.pictureLink.isNullOrBlank()) {
                    pictureBitmap = loadImageFromUrl(notification.pictureLink)
                    Log.d(TAG, "Picture loaded: ${pictureBitmap != null}")
                }

                withContext(Dispatchers.Main) {
                    val updatedBuilder = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(notification.title)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setWhen(notification.timestamp)
                        .setShowWhen(true)
                        .setPriority(
                            if (notification.isAlert) NotificationCompat.PRIORITY_HIGH
                            else NotificationCompat.PRIORITY_DEFAULT
                        )
                        .setCategory(
                            if (notification.isAlert) NotificationCompat.CATEGORY_ALARM
                            else NotificationCompat.CATEGORY_MESSAGE
                        )

                    if (iconBitmap != null) {
                        updatedBuilder.setLargeIcon(iconBitmap)
                    }

                    if (!notification.actionLink.isNullOrBlank()) {
                        addActionButton(updatedBuilder, notification)
                    }

                    if (notification.isAlert) {
                        updatedBuilder.setFullScreenIntent(pendingIntent, true)
                    }

                    if (pictureBitmap != null) {
                        if (!notification.description.isNullOrBlank()) {
                            updatedBuilder.setContentText(notification.description)
                            updatedBuilder.setStyle(
                                NotificationCompat.BigPictureStyle()
                                    .bigPicture(pictureBitmap)
                                    .setSummaryText(notification.description)
                                    .setBigContentTitle(notification.title)
                            )
                        } else {
                            updatedBuilder.setStyle(
                                NotificationCompat.BigPictureStyle()
                                    .bigPicture(pictureBitmap)
                                    .setBigContentTitle(notification.title)
                            )
                        }
                    } else if (!notification.description.isNullOrBlank()) {
                        updatedBuilder.setContentText(notification.description)
                        updatedBuilder.setStyle(
                            NotificationCompat.BigTextStyle()
                                .bigText(notification.description)
                        )
                    }

                    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        notificationManager.notify(notification.hashCode(), updatedBuilder.build())
                        Log.i(TAG, "Notification displayed: ${notification.title} (Alert: ${notification.isAlert})")
                    } else {
                        Log.w(TAG, "Notifications are disabled, cannot show notification")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading notification images", e)
                showNotificationWithoutImage(builder, notification)
            }
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
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doInput = true
                connection.connect()

                val inputStream = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap != null) {
                    Log.d(TAG, "Successfully loaded image: ${bitmap.width}x${bitmap.height}")
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
            notificationManager.cancelAll()
            Log.d(TAG, "Cancelled all message notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel all notifications", e)
        }
    }
}
