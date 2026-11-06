package com.otc.candleai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Inte
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            val channel = "capture_channel"

            if (Build.VERSION.SDK_INT >= 26) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        "Screen Capture",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }

            val notification = Notification.Builder(
                this,
                channel
            )
                .setContentTitle("OTC Candle AI")
                .setContentText("Screen capture is running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(1, notification)
            }

            val resultCode = intent?.getIntExtra(
                "resultCode",
                Activity.RESULT_CANCELED
            ) ?: Activity.RESULT_CANCELED

            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }

            if (resultCode != Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics

            reader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                android.graphics.PixelFormat.RGBA_8888,
                2
            )

            reader?.setOnImageAvailableListener(
                { imageReader ->
                    imageReader.acquireLatestImage()?.close()
                },
                Handler(Looper.getMainLooper())
            )

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup(false)
                    stopSelf()
                }
            }

            projection?.registerCallback(
                projectionCallback!!,
                Handler(Looper.getMainLooper())
            )

            display = projection?.createVirtualDisplay(
                "OTC-Capture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                Handler(Looper.getMainLooper())
            )

            return START_NOT_STICKY

        } catch (e: Exception) {
            cleanup(true)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun cleanup(stopProjection: Boolean) {
        display?.release()
        display = null

        reader?.close()
        reader = null

        projectionCallback?.let {
            try {
                projection?.unregisterCallback(it)
            } catch (_: Exception) {
            }
        }

        projectionCallback = null

        if (stopProjection) {
            projection?.stop()
        }

        projection = null
    }

    override fun onDestroy() {
        cleanup(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}