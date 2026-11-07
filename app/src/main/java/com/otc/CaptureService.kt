
package com.otc.candleai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.content.Context

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var display: android.hardware.display.VirtualDisplay? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        startForeground(1, notification())

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        worker = HandlerThread("CaptureWorker").also { it.start() }
        val handler = Handler(worker!!.looper)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        reader?.setOnImageAvailableListener({ imageReader ->
            var image: Image? = null
            try {
                image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val paddedBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                paddedBitmap.copyPixelsFromBuffer(buffer)

                val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                paddedBitmap.recycle()

                val result = CandleAnalyzer.analyze(bitmap)

                val update = Intent("com.otc.candleai.ANALYSIS_UPDATE").apply {
                    setPackage(packageName)
                    putExtra("signal", result.signal)
                    putExtra("confidence", result.confidence)
                    putExtra("message", result.message)
                }
                sendBroadcast(update)
                bitmap.recycle()

            } catch (e: Exception) {
                android.util.Log.e("CaptureService", "Frame analysis failed", e)
            } finally {
                image?.close()
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "OTCCandleAI",
            width,
            height,
            density,
            0,
            reader?.surface,
            null,
            handler
        )

        return START_STICKY
    }

    private fun createNotification() {
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

    private fun notification(): Notification {
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

    override fun onDestroy() {
        displa