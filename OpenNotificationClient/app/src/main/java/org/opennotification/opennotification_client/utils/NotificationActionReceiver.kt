package org.opennotification.opennotification_client.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opennotification.opennotification_client.data.database.AppDatabase
import org.opennotification.opennotification_client.repository.NotificationRepository

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NotificationActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "MARK_AS_READ" -> {
                val notificationId = intent.getStringExtra("notification_id")
                if (notificationId != null) {
                    markAsRead(context, notificationId)
                }
            }
        }
    }

    private fun markAsRead(context: Context, notificationId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = NotificationRepository(AppDatabase.getDatabase(context))
                Log.d(TAG, "Marked notification as read: $notificationId")

                val notificationManager = NotificationDisplayManager(context)
                notificationManager.cancelNotification(notificationId.hashCode())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark notification as read", e)
            }
        }
    }
}
