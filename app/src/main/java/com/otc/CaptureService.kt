package com.otc.candleai

import android.app.*
import android.content.Intent
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.os.Build
import android.os.Handler

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channel)
                .setContentTitle("OTC Candle AI")
                .setContentText("Screen capture is running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("OTC Candle AI")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

        startForeground(1, notification)

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888,
            2
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
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}