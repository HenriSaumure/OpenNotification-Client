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
import org.opennotification.opennotification_client.ui.activities.FullScreenAlertActivity
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(alertsChannel)
            notificationManager.createNotificationChannel(fullScreenAlertsChannel)
        }
    }

    fun showNotification(notification: Notification) {
        try {
            Log.d(TAG, "Showing notification: ${notification.title}, isAlert: ${notification.isAlert}")
            Log.d(TAG, "Notification details - ID: ${notification.id}, GUID: ${notification.guid}, Description: ${notification.description}")
            Log.d(TAG, "Notification media - PictureLink: ${notification.pictureLink}, Icon: ${notification.icon}, ActionLink: ${notification.actionLink}")

            if (notification.isAlert) {
                Log.i(TAG, "Processing as FULL SCREEN ALERT - triggering showFullScreenAlert()")
                showFullScreenAlert(notification)
                return
            } else {
                Log.i(TAG, "Processing as REGULAR NOTIFICATION - isAlert flag is false")
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
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

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
            Log.d(TAG, "Showing full-screen alert: ${notification.title}")

            try {
                Log.i(TAG, "Attempting to show full screen alert via activity")
                FullScreenAlertActivity.showAlert(
                    context = context,
                    title = notification.title,
                    description = notification.description,
                    pictureLink = notification.pictureLink,
                    icon = notification.icon,
                    actionLink = notification.actionLink,
                    guid = notification.guid
                )
                Log.i(TAG, "Full-screen alert activity launched successfully - no backup notification needed")
                return

            } catch (e: Exception) {
                Log.w(TAG, "Full-screen alert activity failed - creating backup notification", e)
                createHighPriorityNotificationWithFullScreenIntent(notification)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show full-screen alert", e)
            createHighPriorityNotificationWithFullScreenIntent(notification)
        }
    }

    private fun createHighPriorityNotificationWithFullScreenIntent(notification: Notification) {
        try {
            Log.d(TAG, "Creating high-priority notification with full-screen intent")

            val fullScreenIntent = Intent(context, FullScreenAlertActivity::class.java).apply {
                putExtra(FullScreenAlertActivity.EXTRA_TITLE, notification.title)
                putExtra(FullScreenAlertActivity.EXTRA_DESCRIPTION, notification.description)
                putExtra(FullScreenAlertActivity.EXTRA_PICTURE_LINK, notification.pictureLink)
                putExtra(FullScreenAlertActivity.EXTRA_ICON, notification.icon)
                putExtra(FullScreenAlertActivity.EXTRA_ACTION_LINK, notification.actionLink)
                putExtra(FullScreenAlertActivity.EXTRA_GUID, notification.guid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                notification.hashCode() + 2000,
                fullScreenIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("notification_id", notification.id)
                putExtra("guid", notification.guid)
                putExtra("is_alert", true)
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

            val notificationBuilder = NotificationCompat.Builder(context, FULL_SCREEN_ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("🚨 ${notification.title}")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setOngoing(false)

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
                notificationManager.notify(notification.hashCode() + 1000, notificationBuilder.build())
                Log.i(TAG, "High-priority notification with full-screen intent created")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create high-priority notification with full-screen intent", e)
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
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

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


    fun testFullScreenAlert() {
        Log.i(TAG, "TESTING: Triggering test full screen alert")

        val testNotification = Notification(
            id = "test-alert-${System.currentTimeMillis()}",
            guid = "test-guid",
            title = "TEST FULL SCREEN ALERT",
            description = "This is a test full screen alert to verify the functionality",
            pictureLink = null,
            icon = null,
            actionLink = null,
            isAlert = true,
            timestamp = System.currentTimeMillis()
        )

        Log.i(TAG, "TESTING: Created test notification with isAlert=true")
        showNotification(testNotification)
    }
}
