 
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
import android.util.Log

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var display: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val currentProjection = manager.getMediaProjection(resultCode, data)
            projection = currentProjection

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            worker = HandlerThread("CaptureWorker").also { it.start() }
            val handler = Handler(worker!!.looper)

            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }
            currentProjection.registerCallback(projectionCallback!!, handler)

            reader!!.setOnImageAvailableListener(
                { imageReader -> analyzeFrame(imageReader, width) },
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

    private fun analyzeFrame(imageReader: ImageReader, width: Int) {
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

            bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, image.height)
            val result = CandleAnalyzer.analyze(bitmap)

            val update = Intent("com.otc.candleai.ANALYSIS_UPDATE").apply {
                setPackage(packageName)
                putExtra("signal", result.signal)
                putExtra("confidence", result.confidence)
                putExtra("message", result.message)
            }
            sendBroadcast(update)
        } catch (e: Exception) {
            Log.e("CaptureService", "Frame analysis failed", e)
        } finally {
            bitmap?.recycle()
            paddedBitmap?.recycle()
            image?.close()
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

        worker?.quitSafely()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
