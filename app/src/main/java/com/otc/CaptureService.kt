
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
import android.media.Image
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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var display: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recognizer: TextRecognizer? = null

    private val ocrRunning = AtomicBoolean(false)
    private val pendingAnalysis = AtomicBoolean(false)
    private val frameRunning = AtomicBoolean(false)

    private var lastOcrTime = 0L
    private var lastFallbackTime = 0L
    private var lastTimerSeenTime = 0L
    private var previousSecondsRemaining: Int? = null
    private var firstAnalysisQueued = false

    companion object {
        private const val TAG = "OTCCandleAI"
        private const val NOTIFICATION_ID = 1

        private const val OCR_INTERVAL_MS = 1000L
        private const val FALLBACK_INTERVAL_MS = 60_000L
        private const val TIMER_LOSS_MS = 7000L
    }

    private fun queueAnalysis(reason: String) {
        if (pendingAnalysis.compareAndSet(false, true)) {
            Log.d(TAG, "Analysis queued: $reason")
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
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

            worker = HandlerThread("OTCCaptureWorker").also {
                it.start()
            }

            val handler = Handler(worker!!.looper)

            reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                3
            )

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped")
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

            Log.d(TAG, "Screen capture started: ${width}x${height}")

        } catch (e: Exception) {
            Log.e(TAG, "Capture start failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun analyzeFrame(
        imageReader: ImageReader,
        width: Int
    ) {
        if (!frameRunning.compareAndSet(false, true)) {
            return
        }

        var image: Image? = null
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

            // Read the chart countdown once per second.
            if (
                now - lastOcrTime >= OCR_INTERVAL_MS &&
                ocrRunning.compareAndSet(false, true)
            ) {
                lastOcrTime = now
                readChartTimer(bitmap)
            }

            // Use a fallback only when the chart countdown is unavailable.
            val timerUnavailable =
                lastTimerSeenTime == 0L ||
                now - lastTimerSeenTime >= TIMER_LOSS_MS

            if (timerUnavailable) {
                if (
                    lastFallbackTime == 0L ||
                    now - lastFallbackTime >= FALLBACK_INTERVAL_MS
                ) {
                    lastFallbackTime = now
                    queueAnalysis("timer fallback")
                }
            }

            if (pendingAnalysis.compareAndSet(true, false)) {
                val result = CandleAnalyzer.analyze(bitmap)

                val update = Intent(
                    "com.otc.candleai.ANALYSIS_UPDATE"
                ).apply {
                    setPackage(packageName)
                    putExtra("signal", result.signal)
                    putExtra("confidence", result.confidence)
                    putExtra("message", result.message)
                    putExtra("greenCount", result.greenCount)
                    putExtra("redCount", result.redCount)
                    putExtra("newMinuteAnalysis", true)
                }

                sendBroadcast(update)

                Log.d(
                    TAG,
                    "Analysis complete: ${result.signal}, " +
                        "score=${result.confidence}, " +
                        "green=${result.greenCount}, red=${result.redCount}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed", e)
        } finally {
            bitmap?.recycle()
            paddedBitmap?.recycle()
            image?.close()
            frameRunning.set(false)
        }
    }

    private fun readChartTimer(bitmap: Bitmap) {
        var crop: Bitmap? = null

        try {
            val screenWidth = bitmap.width
            val screenHeight = bitmap.height

            // The chart countdown is near the right side of the chart.
            // The lower trade-duration field is intentionally excluded.
            val left = (screenWidth * 0.60f).toInt()
            val top = (screenHeight * 0.48f).toInt()
            val right = (screenWidth * 0.82f).toInt()
            val bottom = (screenHeight * 0.68f).toInt()

            val cropWidth = right - left
            val cropHeight = bottom - top

            if (
                cropWidth <= 0 || cropHeight <= 0 ||
                right > screenWidth || bottom > screenHeight
            ) {
                ocrRunning.set(false)
                return
            }

            crop = Bitmap.createBitmap(
                bitmap,
                left,
                top,
                cropWidth,
                cropHeight
            )

            val inputImage = InputImage.fromBitmap(crop, 0)
            val imageToRecycle = crop
            crop = null

            val textRecognizer = recognizer

            if (textRecognizer == null) {
                imageToRecycle.recycle()
                ocrRunning.set(false)
                return
            }

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->

                    val pattern = Regex(
                        """(?<!\d)(\d{1,2}):([0-5]\d)(?!\d)"""
                    )

                    var detectedSeconds: Int? = null
                    var detectedText: String? = null

                    for (block in visionText.textBlocks) {
                        val bounds = block.boundingBox ?: continue

                        // Only accept text near the chart countdown position.
                        val centerX =
                            bounds.centerX().toFloat() / cropWidth

                        val centerY =
                            bounds.centerY().toFloat() / cropHeight

                        if (
                            centerX !in 0.05f..0.95f ||
                            centerY !in 0.10f..0.90f
                        ) {
                            continue
                        }

                        val normalized = block.text
                            .replace('O', '0')
                            .replace('o', '0')
                            .replace('I', '1')
                            .replace('l', '1')

                        val match = pattern.find(normalized)
                            ?: continue

                        val minutes =
                            match.groupValues[1].toIntOrNull() ?: continue

                        val seconds =
                            match.groupValues[2].toIntOrNull() ?: continue

                        detectedSeconds = minutes * 60 + seconds
                        detectedText = match.value
                        break
                    }

                    val remaining = detectedSeconds

                    if (remaining != null && detectedText != null) {
                        val now = SystemClock.elapsedRealtime()
                        lastTimerSeenTime = now

                        val previous = previousSecondsRemaining

                        if (previous == null) {
                            // First valid chart timer: start initial analysis.
                            if (!firstAnalysisQueued) {
                                firstAnalysisQueued = true
                                queueAnalysis("first chart timer")
                            }
                        } else {
                            val rollover =
                                remaining > previous + 20 ||
                                (previous <= 3 && remaining >= 50)

                            if (rollover) {
                                queueAnalysis(
                                    "new chart minute: $previous -> $remaining"
                                )
                            }
                        }

                        previousSecondsRemaining = remaining

                        val timerUpdate = Intent(
                            "com.otc.candleai.ANALYSIS_UPDATE"
                        ).apply {
                            setPackage(packageName)
                            putExtra("timerText", detectedText)
                            putExtra("secondsRemaining", remaining)
                            putExtra("timerDetected", true)
                        }

                        sendBroadcast(timerUpdate)

                        Log.d(
                            TAG,
                            "Chart countdown: $detectedText"
                        )
                    } else {
                        Log.d(TAG, "Chart countdown not recognized")
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
            crop?.recycle()
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
