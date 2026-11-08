
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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
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

    private var lastOcrTime = 0L

    @Volatile
    private var previousSecondsRemaining: Int? = null

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

            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

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

        } catch (e: Exception) {
            Log.e("CaptureService", "Capture start failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun analyzeFrame(
        imageReader: ImageReader,
        width: Int
    ) {
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

            // Read chart timer approximately once per second.
            val now = SystemClock.elapsedRealtime()

            if (
                now - lastOcrTime >= 1000 &&
                ocrRunning.compareAndSet(false, true)
            ) {
                lastOcrTime = now
                readChartTimer(bitmap)
            }

            // Analyze once after a confirmed timer reset.
            if (pendingAnalysis.compareAndSet(true, false)) {

                val result = CandleAnalyzer.analyze(bitmap)

                val update = Intent(
                    "com.otc.candleai.ANALYSIS_UPDATE"
                ).apply {
                    setPackage(packageName)
                    putExtra("signal", result.signal)
                    putExtra("confidence", result.confidence)
                    putExtra("message", result.message)
                    putExtra("newMinuteAnalysis", true)
                }

                sendBroadcast(update)

                Log.d(
                    "OTCCandleAI",
                    "New minute analysis: ${result.signal}, confidence: ${result.confidence}"
                )
            }

        } catch (e: Exception) {
            Log.e("CaptureService", "Frame analysis failed", e)
        } finally {
            bitmap?.recycle()
            paddedBitmap?.recycle()
            image?.close()
        }
    }

    private fun readChartTimer(bitmap: Bitmap) {

        var timerBitmap: Bitmap? = null

        try {
            // Chart countdown timer crop.
            val left = (bitmap.width * 0.67f).toInt()
            val top = (bitmap.height * 0.60f).toInt()
            val right = (bitmap.width * 0.79f).toInt()
            val bottom = (bitmap.height * 0.645f).toInt()

            val safeLeft = left.coerceIn(0, bitmap.width - 1)
            val safeTop = top.coerceIn(0, bitmap.height - 1)

            val safeRight = right.coerceIn(
                safeLeft + 1,
                bitmap.width
            )

            val safeBottom = bottom.coerceIn(
                safeTop + 1,
                bitmap.height
            )

            timerBitmap = Bitmap.createBitmap(
                bitmap,
                safeLeft,
                safeTop,
                safeRight - safeLeft,
                safeBottom - safeTop
            )

            val inputImage = InputImage.fromBitmap(
                timerBitmap,
                0
            )

            val imageToRecycle = timerBitmap
            timerBitmap = null

            val textRecognizer = recognizer

            if (textRecognizer == null) {
                imageToRecycle.recycle()
                ocrRunning.set(false)
                return
            }

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->

                    val normalized = visionText.text
                        .replace('O', '0')
                        .replace('o', '0')

                    // Accept only countdown format MM:SS.
                    val match = Regex(
                        """\b(\d{1,2}):([0-5]\d)\b"""
                    ).find(normalized)

                    if (match != null) {

                        val minutes =
                            match.groupValues[1].toIntOrNull() ?: return@addOnSuccessListener

                        val seconds =
                            match.groupValues[2].toIntOrNull() ?: return@addOnSuccessListener

                        val remaining = minutes * 60 + seconds

                        val previous = previousSecondsRemaining

                        // Detect transition from near zero to a new minute.
                        if (
                            previous != null &&
                            previous <= 3 &&
                            remaining >= 55
                        ) {
                            pendingAnalysis.set(true)

                            Log.d(
                                "OTCCandleAI",
                                "New minute detected: $remaining seconds"
                            )
                        }

                        previousSecondsRemaining = remaining

                        val update = Intent(
                            "com.otc.candleai.ANALYSIS_UPDATE"
                        ).apply {
                            setPackage(packageName)
                            putExtra("timerText", match.value)
                            putExtra("secondsRemaining", remaining)
                            putExtra("timerDetected", true)
                        }

                        sendBroadcast(update)

                        Log.d(
                            "OTCCandleAI",
                            "Chart timer: ${match.value}"
                        )
                    }
                }
                .addOnFailureListener { error ->
                    Log.e(
                        "OTCCandleAI",
                        "Timer OCR failed",
                        error
                    )
                }
                .addOnCompleteListener {
                    imageToRecycle.recycle()
                    ocrRunning.set(false)
                }

        } catch (e: Exception) {
            timerBitmap?.recycle()
            ocrRunning.set(false)

            Log.e(
                "OTCCandleAI",
                "Timer reading error",
                e
            )
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
