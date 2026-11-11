package com.universalvoicetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class FloatingTranslatorService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val preferences by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val restoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Settings.canDrawOverlays(this@FloatingTranslatorService) && preferences.getBoolean(KEY_ENABLED, false)) {
                bubble?.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_RESTORE_AFTER_CAPTURE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(restoreReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(restoreReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopOverlay()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAsForegroundService()
            if (bubble == null) {
                showBubble()
            } else {
                bubble?.visibility = View.VISIBLE
            }
            preferences.edit().putBoolean(KEY_ENABLED, true).apply()
        } catch (_: Exception) {
            stopOverlay()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeBubble()
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        runCatching { unregisterReceiver(restoreReceiver) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubble() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(BUBBLE_SIZE_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(KEY_X, 0)
            y = preferences.getInt(KEY_Y, dp(DEFAULT_Y_DP))
        }
        val view = TextView(this).apply {
            text = "文"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Capture screen text for translation. Drag to move."
            isClickable = true
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(8, 127, 116))
                setStroke(dp(2), Color.WHITE)
            }
            setOnClickListener {
                hideBubbleForCapture()
                openScreenCapture()
            }
        }
        installDragHandling(view, params, manager, size)
        manager.addView(view, params)
        windowManager = manager
        bubble = view
        layoutParams = params
    }

    private fun installDragHandling(
        view: View,
        params: WindowManager.LayoutParams,
        manager: WindowManager,
        size: Int,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) moved = true
                    if (moved) {
                        val (screenWidth, screenHeight) = screenSize()
                        params.x = (startX + deltaX).coerceIn(0, (screenWidth - size).coerceAtLeast(0))
                        params.y = (startY + deltaY).coerceIn(0, (screenHeight - size).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(touchedView, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val (screenWidth, _) = screenSize()
                        params.x = if (params.x + size / 2 < screenWidth / 2) 0 else (screenWidth - size).coerceAtLeast(0)
                        runCatching { manager.updateViewLayout(touchedView, params) }
                        preferences.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        touchedView.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun openTranslator() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launchIntent)
    }

    private fun openScreenCapture() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launchIntent.action = ScreenTranslationService.ACTION_REQUEST_CAPTURE
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launchIntent)
    }

    private fun hideBubbleForCapture() {
        bubble?.visibility = View.INVISIBLE
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hideIntent = Intent(this, FloatingTranslatorService::class.java).setAction(ACTION_HIDE)
        val hidePendingIntent = PendingIntent.getService(
            this,
            REQUEST_HIDE,
            hideIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Floating translator is on")
            .setContentText("Tap the floating button to capture screen text, or hide it here.")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide button", hidePendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating translator",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the floating translator button is enabled."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopOverlay() {
        removeBubble()
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeBubble() {
        val view = bubble ?: return
        runCatching { windowManager?.removeView(view) }
        bubble = null
        layoutParams = null
        windowManager = null
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_HIDE = "com.universalvoicetranslator.action.HIDE_FLOATING_BUTTON"
        const val ACTION_RESTORE_AFTER_CAPTURE = "com.universalvoicetranslator.action.RESTORE_FLOATING_BUTTON"
        const val PREFERENCES = "floating_translator"
        const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val CHANNEL_ID = "floating_translator"
        private const val NOTIFICATION_ID = 302
        private const val REQUEST_OPEN = 303
        private const val REQUEST_HIDE = 304
        private const val BUBBLE_SIZE_DP = 56
        private const val DEFAULT_Y_DP = 180

        fun show(context: Context) {
            val serviceIntent = Intent(context, FloatingTranslatorService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, FloatingTranslatorService::class.java))
        }

        fun restoreAfterCapture(context: Context) {
            val restoreIntent = Intent(ACTION_RESTORE_AFTER_CAPTURE).setPackage(context.packageName)
            context.sendBroadcast(restoreIntent)
        }

        fun isEnabled(context: Context): Boolean = context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }
}
