
package com.otc.candleai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.otc.candleai.START_CAPTURE"
        const val ACTION_STOP = "com.otc.candleai.STOP_CAPTURE"
        const val ACTION_UPDATE = "com.otc.candleai.ANALYSIS_UPDATE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "candle_capture_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val ocrRunning = AtomicBoolean(false)
    private val pendingAnalysis = AtomicBoolean(false)
    private val pendingCompletedAnalysis = AtomicBoolean(false)

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var previousSecondsRemaining: Int? = null
    private var timerDirection = 0

    private var lastAnalysisTime = 0L
    private var lastTimerValue: Int? = null

    private val analysisIntervalMs = 3000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startWorker()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    0
                )

                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (data != null) {
                    startForegroundServiceNotification()
                    startCapture(resultCode, data)
                }
            }
        }

        return START_STICKY
    }

    private fun startWorker() {
        workerThread = HandlerThread("CandleCaptureWorker")
        workerThread?.start()
        workerHandler = Handler(workerThread!!.looper)
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {

        if (mediaProjection != null) return

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val projectionManager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(
            resultCode,
            data
        )

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CandleAI_Display",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )

        imageReader?.setOnImageAvailableListener(
            { reader ->
                processLatestImage(reader)
            },
            workerHandler
        )

        previousSecondsRemaining = null
        timerDirection = 0
        lastAnalysisTime = 0L
        lastTimerValue = null

        pendingAnalysis.set(false)
        pendingCompletedAnalysis.set(false)
    }

    // প্রতি ৩ সেকেন্ডে Analysis চালানোর ব্যবস্থা
    private fun processLatestImage(reader: ImageReader) {

        var image: Image? = null

        try {
            image = reader.acquireLatestImage() ?: return

            val planes = image.planes
            val buffer = planes[0].buffer

            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val rowPadding =
                rowStride - pixelStride * screenWidth

            val bitmapWidth =
                screenWidth + rowPadding / pixelStride

            val fullBitmap = Bitmap.createBitmap(
                bitmapWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            fullBitmap.copyPixelsFromBuffer(buffer)

            val bitmap = Bitmap.createBitmap(
                fullBitmap,
                0,
                0,
                screenWidth,
                screenHeight
            )

            fullBitmap.recycle()

            image.close()
            image = null

            val completed =
                pendingCompletedAnalysis.getAndSet(false)

            if (completed) {
                pendingAnalysis.set(false)
                lastAnalysisTime = System.currentTimeMillis()
                runAnalysis(bitmap, false, true)
                return
            }

            val requested =
                pendingAnalysis.getAndSet(false)

            val now = System.currentTimeMillis()

            if (
                requested ||
                now - lastAnalysisTime >= analysisIntervalMs
            ) {
                lastAnalysisTime = now
                runAnalysis(bitmap, true, false)
            } else {
                runOcr(bitmap)
            }

        } catch (e: Exception) {
            image?.close()
            e.printStackTrace()
        }
    }

    private fun runAnalysis(
        bitmap: Bitmap,
        includeLiveCandle: Boolean,
        newMinute: Boolean
    ) {

        try {
            val result = CandleAnalyzer.analyze(
                bitmap,
                includeLiveCandle
            )

            val intent = Intent(ACTION_UPDATE).apply {
                setPackage(packageName)

                putExtra("signal", result.signal)
                putExtra("confidence", result.confidence)
                putExtra("greenCount", result.greenCount)
                putExtra("redCount", result.redCount)
                putExtra("message", result.message)
                putExtra("newMinuteAnalysis", newMinute)
            }

            sendBroadcast(intent)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun runOcr(bitmap: Bitmap) {

        if (!ocrRunning.compareAndSet(false, true)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->

                try {
                    val timer = findTimer(result.text)

                    if (timer != null) {
                        handleTimer(timer)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .addOnFailureListener {
                it.printStackTrace()
            }
            .addOnCompleteListener {

                ocrRunning.set(false)

                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
    }

    private fun findTimer(text: String): Int? {

        val pattern = Pattern.compile(
            "(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)"
        )

        val matcher = pattern.matcher(text)

        while (matcher.find()) {

            val minutes = matcher.group(1)?.toIntOrNull() ?: continue
            val seconds = matcher.group(2)?.toIntOrNull() ?: continue

            if (seconds !in 0..59) continue
            if (minutes !in 0..59) continue

            val totalSeconds = minutes * 60 + seconds

            if (totalSeconds in 0..3600) {
                return totalSeconds
            }
        }

        return null
    }

    private fun handleTimer(remaining: Int) {

        val now = System.currentTimeMillis()
        val previous = previousSecondsRemaining

        var newMinute = false

        if (previous == null) {

            pendingAnalysis.set(true)

        } else {

            val difference = remaining - previous

            if (abs(difference) in 1..15) {

                if (difference < 0) {
                    timerDirection = -1
                } else if (difference > 0) {
                    timerDirection = 1
                }
            }

            val countdownReset =
                timerDirection <= 0 &&
                difference > 15

            val countupReset =
                timerDirection >= 0 &&
                difference < -15

            if (countdownReset || countupReset) {

                newMinute = true

                pendingCompletedAnalysis.set(true)
                pendingAnalysis.set(false)

                lastAnalysisTime = now

            } else {

                if (
                    remaining != lastTimerValue &&
                    now - lastAnalysisTime >= analysisIntervalMs
                ) {
                    pendingAnalysis.set(true)
                    lastAnalysisTime = now
                }
            }
        }

        previousSecondsRemaining = remaining
        lastTimerValue = remaining

        val timerIntent = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)

            putExtra("timerText", formatTimer(remaining))
            putExtra("secondsRemaining", remaining)
            putExtra("timerDetected", true)
            putExtra("newMinuteDetected", newMinute)
        }

        sendBroadcast(timerIntent)
    }

    private fun formatTimer(seconds: Int): String {

        val minutes = seconds / 60
        val remainingSeconds = seconds % 60

        return String.format(
            "%02d:%02d",
            minutes,
            remainingSeconds
        )
    }

    private fun stopCapture() {

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            pendingAnalysis.set(false)
            pendingCompletedAnalysis.set(false)

            previousSecondsRemaining = null
            timerDirection = 0

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Candle AI Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {

        val notification = Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("Candle AI চলছে")
            .setContentText("চার্ট পর্যবেক্ষণ করা হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    override fun onDestroy() {

        stopCapture()

        try {
            recognizer.close()
        } catch (_: Exception) {
        }

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        super.onDestroy()
    }
}
