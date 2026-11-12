
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
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var display: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    private val ocrRunning = AtomicBoolean(false)
    private val pendingAnalysis = AtomicBoolean(false)

    private var lastOcrTime = 0L

    @Volatile
    private var previousSecondsRemaining: Int? = null

    @Volatile
    private var lastAnalysisTriggerTime = 0L

    private val analysisLock = Any()

    companion object {
        private const val TAG = "OTCCandleAI"
        private const val ACTION_UPDATE =
            "com.otc.candleai.ANALYSIS_UPDATE"

        private const val OCR_INTERVAL_MS = 1000L
        private const val MIN_ANALYSIS_GAP_MS = 45000L
        private const val TIMER_RESET_THRESHOLD = 15
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, createNotification())
        }

        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra("resultCode", 0)
            ?: return stopWithError()

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (data == null) return stopWithError()

        try {
            recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            val currentProjection =
                manager.getMediaProjection(resultCode, data)

            projection = currentProjection

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            worker = HandlerThread("CaptureWorker").also {
                it.start()
            }

            val handler = Handler(worker!!.looper)

            reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }

            currentProjection.registerCallback(
                projectionCallback!!,
                handler
            )

            reader!!.setOnImageAvailableListener(
                { imageReader ->
                    analyzeFrame(imageReader, width)
                },
                handler
            )

            display = currentProjection.createVirtualDisplay(
                "OTCCandleAI",
                width,
                height,
                density,
                0,
                reader!!.surface,
                null,
                handler
            )

            Log.d(TAG, "Screen capture started")

        } catch (e: Exception) {
            Log.e(TAG, "Capture start failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun queueAnalysis(reason: String) {
        synchronized(analysisLock) {
            val now = SystemClock.elapsedRealtime()

            if (lastAnalysisTriggerTime == 0L) {
                lastAnalysisTriggerTime = now
                pendingAnalysis.set(true)
                Log.d(TAG, "Analysis queued: $reason")
                return
            }

            val elapsed = now - lastAnalysisTriggerTime

            if (elapsed >= MIN_ANALYSIS_GAP_MS) {
                lastAnalysisTriggerTime = now
                pendingAnalysis.set(true)
                Log.d(TAG, "Analysis queued: $reason")
            } else {
                Log.d(
                    TAG,
                    "Analysis ignored by cooldown: ${elapsed}ms"
                )
            }
        }
    }

    private fun analyzeFrame(
        imageReader: ImageReader,
        width: Int
    ) {
        var image: android.media.Image? = null
        var paddedBitmap: Bitmap? = null
        var bitmap: Bitmap? = null

        try {
            image = imageReader.acquireLatestImage() ?: return

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            paddedBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            paddedBitmap.copyPixelsFromBuffer(plane.buffer)

            bitmap = Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                width,
                image.height
            )

            val now = SystemClock.elapsedRealtime()

            if (
                now - lastOcrTime >= OCR_INTERVAL_MS &&
                ocrRunning.compareAndSet(false, true)
            ) {
                lastOcrTime = now
                readChartTimer(bitmap)
            }

            if (pendingAnalysis.compareAndSet(true, false)) {
                val result = CandleAnalyzer.analyze(bitmap)

                val update = Intent(ACTION_UPDATE).apply {
                    setPackage(packageName)
                    putExtra("signal", result.signal)
                    putExtra("confidence", result.confidence)
                    putExtra("message", result.message)
                    putExtra("newMinuteAnalysis", true)
                }

                sendBroadcast(update)

                Log.d(
                    TAG,
                    "Analysis result: ${result.signal}, confidence: ${result.confidence}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed", e)
        } finally {
            bitmap?.recycle()
            paddedBitmap?.recycle()
            image?.close()
        }
    }

    private fun readChartTimer(bitmap: Bitmap) {
        var ocrBitmap: Bitmap? = null

        try {
            val screenWidth = bitmap.width
            val screenHeight = bitmap.height

            ocrBitmap = bitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )

            val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
            val imageToRecycle = ocrBitmap
            ocrBitmap = null

            val textRecognizer = recognizer

            if (textRecognizer == null) {
                imageToRecycle.recycle()
                ocrRunning.set(false)
                return
            }

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->

                    val timerPattern = Regex(
                        """(?<!\d)(\d{1,2}):([0-5]\d)(?!\d)"""
                    )

                    data class TimerCandidate(
                        val text: String,
                        val seconds: Int,
                        val x: Float
                    )

                    val candidates = mutableListOf<TimerCandidate>()

                    for (block in visionText.textBlocks) {
                        val bounds = block.boundingBox ?: continue

                        val centerX =
                            bounds.centerX().toFloat() / screenWidth

                        val centerY =
                            bounds.centerY().toFloat() / screenHeight

                        if (
                            centerX < 0.45f ||
                            centerX > 0.98f ||
                            centerY < 0.30f ||
                            centerY > 0.78f
                        ) {
                            continue
                        }

                        val normalized = block.text
                            .replace('O', '0')
                            .replace('o', '0')

                        val match = timerPattern.find(normalized)
                            ?: continue

                        val minutes =
                            match.groupValues[1].toIntOrNull() ?: continue

                        val seconds =
                            match.groupValues[2].toIntOrNull() ?: continue

                        val totalSeconds = minutes * 60 + seconds

                        if (totalSeconds > 599) continue

                        candidates.add(
                            TimerCandidate(
                                text = match.value,
                                seconds = totalSeconds,
                                x = centerX
                            )
                        )
                    }

                    // Prefer the timer farthest to the right in the chart area.
                    val candidate = candidates.maxByOrNull { it.x }

                    if (candidate != null) {
                        val remaining = candidate.seconds
                        val previous = previousSecondsRemaining

                        if (previous == null) {
                            queueAnalysis("Initial timer detected")
                        } else if (
                            remaining > previous + TIMER_RESET_THRESHOLD
                        ) {
                            queueAnalysis(
                                "New minute detected: ${candidate.text}"
                            )
                        }

                        previousSecondsRemaining = remaining

                        val update = Intent(ACTION_UPDATE).apply {
                            setPackage(packageName)
                            putExtra("timerText", candidate.text)
                            putExtra("secondsRemaining", remaining)
                            putExtra("timerDetected", true)
                        }

                        sendBroadcast(update)

                        Log.d(
                            TAG,
                            "Chart timer: ${candidate.text}"
                        )
                    } else {
                        Log.d(TAG, "Chart timer not found")
                    }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Timer OCR failed", error)
                }
                .addOnCompleteListener {
                    imageToRecycle.recycle()
                    ocrRunning.set(false)
                }

        } catch (e: Exception) {
            ocrBitmap?.recycle()
            ocrRunning.set(false)
            Log.e(TAG, "Timer reading error", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "capture_channel",
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "capture_channel")
                .setContentTitle("OTC Candle AI")
                .setContentText("Screen analysis running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("OTC Candle AI")
                .setContentText("Screen analysis running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    private fun stopWithError(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        reader?.setOnImageAvailableListener(null, null)

        display?.release()
        display = null

        reader?.close()
        reader = null

        projectionCallback?.let { callback ->
            projection?.unregisterCallback(callback)
        }

        projectionCallback = null

        projection?.stop()
        projection = null

        recognizer?.close()
        recognizer = null

        worker?.quitSafely()
        worker = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
