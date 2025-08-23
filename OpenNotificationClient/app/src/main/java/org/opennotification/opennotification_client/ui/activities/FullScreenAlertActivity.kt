package org.opennotification.opennotification_client.ui.activities

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opennotification.opennotification_client.R
import java.net.URL

class FullScreenAlertActivity : Activity() {
    companion object {
        private const val TAG = "FullScreenAlertActivity"
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
        ) {
            val intent = Intent(context, FullScreenAlertActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DESCRIPTION, description)
                putExtra(EXTRA_PICTURE_LINK, pictureLink)
                putExtra(EXTRA_ICON, icon)
                putExtra(EXTRA_ACTION_LINK, actionLink)
                putExtra(EXTRA_GUID, guid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                       Intent.FLAG_ACTIVITY_CLEAR_TOP or
                       Intent.FLAG_ACTIVITY_SINGLE_TOP or
                       Intent.FLAG_ACTIVITY_NO_HISTORY
            }
            context.startActivity(intent)
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure window for lock screen display BEFORE setting content view
        setupLockScreenWindow()

        setContentView(R.layout.overlay_full_screen_alert)

        // Now configure system UI after content view is set
        setupSystemUI()

        // Get intent data
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alert"
        val description = intent.getStringExtra(EXTRA_DESCRIPTION)
        val pictureLink = intent.getStringExtra(EXTRA_PICTURE_LINK)
        val icon = intent.getStringExtra(EXTRA_ICON)
        val actionLink = intent.getStringExtra(EXTRA_ACTION_LINK)
        val guid = intent.getStringExtra(EXTRA_GUID)

        Log.d(TAG, "Showing full-screen alert activity: $title")

        // Setup UI
        setupViewContent(title, description, pictureLink, icon, actionLink, guid)

        // Start alarm effects
        startAlarmEffects()

        // Auto-dismiss after 30 seconds
        handler.postDelayed(autoDismissRunnable, 30000)
    }

    private fun setupLockScreenWindow() {
        // Set flags for lock screen display - this must be done before setContentView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)

            // Request to dismiss keyguard
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            // For older versions, use window flags
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Additional flags for maximum compatibility
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        // Full screen display
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        Log.d(TAG, "Lock screen window flags setup completed")
    }

    private fun setupSystemUI() {
        // Hide system UI for true full screen - this must be done after setContentView
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
            Log.d(TAG, "System UI setup completed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup system UI, but continuing", e)
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
        findViewById<TextView>(R.id.alertTitle)?.text = title

        findViewById<TextView>(R.id.alertDescription)?.let { descView ->
            if (!description.isNullOrBlank()) {
                descView.text = description
                descView.visibility = View.VISIBLE
            } else {
                descView.visibility = View.GONE
            }
        }

        findViewById<Button>(R.id.dismissButton)?.setOnClickListener {
            dismissAlert()
        }

        findViewById<Button>(R.id.actionButton)?.let { actionBtn ->
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

        setupSwipeGestures(actionLink)
        loadImages(pictureLink, icon)
    }

    private fun setupSwipeGestures(actionLink: String?) {
        var startY = 0f
        val minSwipeDistance = 150f

        findViewById<View>(android.R.id.content)?.setOnTouchListener { v, event ->
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
                            // Swipe up - open action link if available
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
                            // Swipe down - dismiss
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

    private fun loadImages(pictureLink: String?, icon: String?) {
        activityScope.launch {
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
                    val pictureView = findViewById<ImageView>(R.id.alertPicture)
                    val iconView = findViewById<ImageView>(R.id.alertIcon)

                    if (pictureBitmap != null) {
                        pictureView?.setImageBitmap(pictureBitmap)
                        pictureView?.visibility = View.VISIBLE
                        iconView?.visibility = View.GONE
                        Log.d(TAG, "Displaying picture image")
                    } else if (iconBitmap != null) {
                        iconView?.setImageBitmap(iconBitmap)
                        iconView?.visibility = View.VISIBLE
                        pictureView?.visibility = View.GONE
                        Log.d(TAG, "Displaying icon image")
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
                        val scaledBitmap = Bitmap.createScaledBitmap(it, scaledWidth, scaledHeight, true)
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
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
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

                    setDataSource(this@FullScreenAlertActivity, alarmUri)

                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )

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
            Log.d(TAG, "Dismissing full-screen alert activity")
            handler.removeCallbacks(autoDismissRunnable)
            stopAlarmEffects()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing alert", e)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmEffects()
        activityScope.cancel()
        Log.d(TAG, "FullScreenAlertActivity destroyed")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Prevent back button from dismissing the alert
        // User must use dismiss button or swipe gestures
    }
}
