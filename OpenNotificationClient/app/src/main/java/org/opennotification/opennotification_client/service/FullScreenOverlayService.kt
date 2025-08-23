package org.opennotification.opennotification_client.service

import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opennotification.opennotification_client.R
import org.opennotification.opennotification_client.ui.activities.FullScreenAlertActivity
import java.net.URL

class FullScreenOverlayService : Service() {
    companion object {
        private const val TAG = "FullScreenOverlayService"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_PICTURE_LINK = "extra_picture_link"
        const val EXTRA_ICON = "extra_icon"
        const val EXTRA_ACTION_LINK = "extra_action_link"
        const val EXTRA_GUID = "extra_guid"

        fun showAlert(
            context: Context,
            title: String,
            description: String? = null,
            pictureLink: String? = null,
            icon: String? = null,
            actionLink: String? = null,
            guid: String? = null
        ): Boolean {
            Log.d(TAG, "showAlert called - title: $title")

            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            try {
                Log.i(TAG, "Attempting to show full screen alert via activity")
                FullScreenAlertActivity.showAlert(
                    context, title, description, pictureLink, icon, actionLink, guid
                )
                Log.i(TAG, "Full screen alert activity launched successfully")

                if (!keyguardManager.isKeyguardLocked && canDrawOverlays(context)) {
                    Log.d(TAG, "Device unlocked with overlay permission - but activity already shown, skipping overlay to prevent duplicates")
                }

                return true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to show full screen alert activity", e)

                if (canDrawOverlays(context)) {
                    Log.w(TAG, "Activity failed, trying overlay as fallback")
                    try {
                        val intent = Intent(context, FullScreenOverlayService::class.java).apply {
                            putExtra(EXTRA_TITLE, title)
                            putExtra(EXTRA_DESCRIPTION, description)
                            putExtra(EXTRA_PICTURE_LINK, pictureLink)
                            putExtra(EXTRA_ICON, icon)
                            putExtra(EXTRA_ACTION_LINK, actionLink)
                            putExtra(EXTRA_GUID, guid)
                        }
                        context.startService(intent)
                        return true
                    } catch (overlayException: Exception) {
                        Log.e(TAG, "Overlay fallback also failed", overlayException)
                    }
                }

                return false
            }
        }

        private fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun requestOverlayPermission(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "Opened overlay permission settings")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissAlert() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        Log.d(TAG, "FullScreenOverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (canDrawOverlays(this)) {
                showFullScreenAlert(it)
            } else {
                Log.w(TAG, "Permission not available, stopping service")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun showFullScreenAlert(intent: Intent) {
        try {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alert"
            val description = intent.getStringExtra(EXTRA_DESCRIPTION)
            val pictureLink = intent.getStringExtra(EXTRA_PICTURE_LINK)
            val icon = intent.getStringExtra(EXTRA_ICON)
            val actionLink = intent.getStringExtra(EXTRA_ACTION_LINK)
            val guid = intent.getStringExtra(EXTRA_GUID)

            Log.d(TAG, "Showing full-screen overlay alert: $title")

            removeExistingOverlay()

            createOverlayView(title, description, pictureLink, icon, actionLink, guid)
            startAlarmEffects()

            handler.postDelayed(autoDismissRunnable, 30000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show full-screen alert", e)
            stopSelf()
        }
    }

    private fun removeExistingOverlay() {
        try {
            handler.removeCallbacks(autoDismissRunnable)

            stopAlarmEffects()

            overlayView?.let { view ->
                windowManager?.removeView(view)
                overlayView = null
                Log.d(TAG, "Removed existing overlay view")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing existing overlay", e)
        }
    }

    private fun createOverlayView(
        title: String,
        description: String?,
        pictureLink: String?,
        icon: String?,
        actionLink: String?,
        guid: String?
    ) {
        try {
            val layoutInflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = layoutInflater.inflate(R.layout.overlay_full_screen_alert, null)

            val layoutParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }
                format = PixelFormat.TRANSLUCENT

                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER

                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

            setupViewContent(title, description, pictureLink, icon, actionLink, guid)

            windowManager?.addView(overlayView, layoutParams)
            Log.d(TAG, "Full-screen overlay added to window manager")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay view", e)
            stopSelf()
        }
    }

    private fun setupViewContent(
        title: String,
        description: String?,
        pictureLink: String?,
        icon: String?,
        actionLink: String?,
        guid: String?
    ) {
        overlayView?.let { view ->
            view.findViewById<TextView>(R.id.alertTitle)?.text = title
            view.findViewById<TextView>(R.id.alertDescription)?.let { descView ->
                if (!description.isNullOrBlank()) {
                    descView.text = description
                    descView.visibility = View.VISIBLE
                } else {
                    descView.visibility = View.GONE
                }
            }

            view.findViewById<Button>(R.id.dismissButton)?.setOnClickListener {
                dismissAlert()
            }

            view.findViewById<Button>(R.id.actionButton)?.let { actionBtn ->
                if (!actionLink.isNullOrBlank()) {
                    actionBtn.visibility = View.VISIBLE
                    actionBtn.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(actionLink)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                            dismissAlert()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open action link", e)
                        }
                    }
                } else {
                    actionBtn.visibility = View.GONE
                }
            }

            setupFullScreenSwipeGestures(view, actionLink)

            loadImages(view, pictureLink, icon)
        }
    }

    private fun setupFullScreenSwipeGestures(view: View, actionLink: String?) {
        var startY = 0f
        val minSwipeDistance = 150f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val endY = event.y
                    val deltaY = startY - endY

                    when {
                        deltaY > minSwipeDistance -> {
                            if (!actionLink.isNullOrBlank()) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse(actionLink)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to open action link", e)
                                }
                            }
                            dismissAlert()
                            true
                        }
                        deltaY < -minSwipeDistance -> {
                            dismissAlert()
                            true
                        }
                        else -> {
                            v.performClick()
                            false
                        }
                    }
                }
                else -> true
            }
        }
    }

    private fun loadImages(view: View, pictureLink: String?, icon: String?) {
        serviceScope.launch {
            try {
                var iconBitmap: Bitmap? = null
                var pictureBitmap: Bitmap? = null

                if (!pictureLink.isNullOrBlank()) {
                    pictureBitmap = loadImageFromUrl(pictureLink)
                }

                if (pictureBitmap == null && !icon.isNullOrBlank()) {
                    iconBitmap = loadImageFromUrl(icon)
                }

                withContext(Dispatchers.Main) {
                    val pictureView = view.findViewById<ImageView>(R.id.alertPicture)
                    val iconView = view.findViewById<ImageView>(R.id.alertIcon)

                    if (pictureBitmap != null) {
                        pictureView?.setImageBitmap(pictureBitmap)
                        pictureView?.visibility = View.VISIBLE
                        iconView?.visibility = View.GONE
                        Log.d(TAG, "Displaying picture image")
                    } else if (iconBitmap != null) {
                        iconView?.setImageBitmap(iconBitmap)
                        iconView?.visibility = View.VISIBLE
                        pictureView?.visibility = View.GONE
                        Log.d(TAG, "Displaying icon image (no picture available)")
                    } else {
                        pictureView?.visibility = View.GONE
                        iconView?.visibility = View.GONE
                        Log.d(TAG, "No images to display")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load images", e)
            }
        }
    }

    private suspend fun loadImageFromUrl(imageUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doInput = true
                connection.connect()

                val inputStream = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                bitmap?.let {
                    if (it.width > 512 || it.height > 512) {
                        val scaleFactor = minOf(512.0 / it.width, 512.0 / it.height)
                        val scaledWidth = (it.width * scaleFactor).toInt()
                        val scaledHeight = (it.height * scaleFactor).toInt()
                        val scaledBitmap =
                            Bitmap.createScaledBitmap(it, scaledWidth, scaledHeight, true)
                        it.recycle()
                        scaledBitmap
                    } else {
                        it
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from URL: $imageUrl", e)
                null
            }
        }
    }

    private fun startAlarmEffects() {
        try {
            vibrator?.let { vib ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val vibrationEffect = VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 200, 500, 200, 500),
                        intArrayOf(0, 255, 0, 255, 0, 255),
                        0
                    )
                    vib.vibrate(vibrationEffect)
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), 0)
                }
            }

            try {
                mediaPlayer = MediaPlayer().apply {
                    val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

                    setDataSource(this@FullScreenOverlayService, alarmUri)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_ALARM)
                    }

                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start alarm sound", e)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm effects", e)
        }
    }

    private fun stopAlarmEffects() {
        try {
            vibrator?.cancel()

            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                mediaPlayer = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm effects", e)
        }
    }

    private fun dismissAlert() {
        try {
            Log.d(TAG, "Dismissing full-screen overlay alert")

            handler.removeCallbacks(autoDismissRunnable)

            stopAlarmEffects()

            overlayView?.let { view ->
                windowManager?.removeView(view)
                overlayView = null
            }

            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing alert", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissAlert()
        serviceScope.cancel()
        Log.d(TAG, "FullScreenOverlayService destroyed")
    }
}
