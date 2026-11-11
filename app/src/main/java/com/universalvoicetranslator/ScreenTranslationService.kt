package com.universalvoicetranslator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

class ScreenTranslationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
    private var captureFinished = false
    private var captureReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()
        try {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0)
                ?: throw IllegalArgumentException("Missing screen-capture consent result.")
            val resultData = intent.parcelableExtra<Intent>(EXTRA_RESULT_DATA)
                ?: throw IllegalArgumentException("Missing screen-capture consent data.")
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android could not start screen capture.")
            projection = mediaProjection
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, handler)
            beginSingleCapture(mediaProjection)
        } catch (exception: Exception) {
            finishWithError(exception.message ?: "Screen capture could not start.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        recognizer?.close()
        recognizer = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        restoreFloatingButton()
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        createNotificationChannels()
        val notification = NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen translation")
            .setContentText("Reading the screen you chose to capture")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginSingleCapture(mediaProjection: MediaProjection) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        require(width > 0 && height > 0) { "Could not determine the screen dimensions." }

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            if (captureReady) captureLatestImage(source)
        }, handler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "UniversalTranslatorOneShotCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        ) ?: throw IllegalStateException("Android could not create a screen capture display.")

        handler.postDelayed({
            captureReady = true
            if (!captureFinished) captureLatestImage(reader)
        }, CAPTURE_SETTLE_DELAY_MS)
    }

    private fun captureLatestImage(reader: ImageReader) {
        if (captureFinished) return
        val image = try {
            reader.acquireLatestImage()
        } catch (exception: Exception) {
            finishWithError(exception.message ?: "Could not read the captured screen.")
            return
        } ?: return

        captureFinished = true
        val bitmap = try {
            imageToBitmap(image)
        } catch (exception: Exception) {
            image.close()
            finishWithError(exception.message ?: "Could not decode the captured screen.")
            return
        }
        image.close()
        recognizeText(bitmap)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw IllegalStateException("No screen image was captured.")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return paddedBitmap
        val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        paddedBitmap.recycle()
        return bitmap
    }

    private fun recognizeText(bitmap: Bitmap) {
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer = textRecognizer
        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                bitmap.recycle()
                val recognizedText = result.text.trim().take(MAX_OCR_CHARACTERS)
                if (recognizedText.isBlank()) {
                    finishWithError("No readable text was found on the captured screen.")
                } else {
                    finishWithText(recognizedText)
                }
            }
            .addOnFailureListener { exception ->
                bitmap.recycle()
                finishWithError(exception.message ?: "On-device text recognition failed.")
            }
    }

    private fun finishWithText(text: String) {
        if (!markFinished()) return
        showResultNotification(text, null)
        stopSelf()
    }

    private fun finishWithError(message: String) {
        if (!markFinished()) return
        showResultNotification(null, message)
        stopSelf()
    }

    private fun markFinished(): Boolean {
        if (resultPosted) return false
        resultPosted = true
        return true
    }

    private var resultPosted = false

    private fun showResultNotification(text: String?, error: String?) {
        val resultIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_RESULT
            putExtra(EXTRA_OCR_TEXT, text)
            putExtra(EXTRA_OCR_ERROR, error)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            RESULT_REQUEST_CODE,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(if (error == null) "Screen text is ready" else "Screen capture needs attention")
            .setContentText(if (error == null) "Tap to review the detected text and translation." else "Tap to view the capture error.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (error == null) "Detected text is kept out of the notification. Tap to review it in the app." else error))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_view, "Review translation", pendingIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun restoreFloatingButton() {
        if (Settings.canDrawOverlays(this) && FloatingTranslatorService.isEnabled(this)) {
            FloatingTranslatorService.restoreAfterCapture(this)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(PROGRESS_CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown only while processing a screen capture you approved." },
            )
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Screen translation results", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Tap to review text recognized from your approved screen capture." },
            )
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

    companion object {
        const val ACTION_REQUEST_CAPTURE = "com.universalvoicetranslator.action.REQUEST_SCREEN_CAPTURE"
        const val ACTION_SHOW_RESULT = "com.universalvoicetranslator.action.SHOW_SCREEN_TRANSLATION"
        const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        const val EXTRA_OCR_TEXT = "screen_ocr_text"
        const val EXTRA_OCR_ERROR = "screen_ocr_error"
        const val SETTINGS = "translator_settings"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TARGET_LANGUAGE = "target_language"
        private const val PROGRESS_CHANNEL_ID = "screen_capture_progress"
        private const val RESULT_CHANNEL_ID = "screen_translation_results"
        private const val NOTIFICATION_ID = 401
        private const val RESULT_NOTIFICATION_ID = 402
        private const val RESULT_REQUEST_CODE = 403
        private const val CAPTURE_SETTLE_DELAY_MS = 850L
        private const val MAX_OCR_CHARACTERS = 8_000
    }
}
