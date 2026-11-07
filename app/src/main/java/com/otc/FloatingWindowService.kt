package com.otc.candleai

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: LinearLayout? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (floatingView != null) return START_NOT_STICKY

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            setBackgroundColor(Color.rgb(25, 25, 25))
        }

        val title = TextView(this).apply {
            text = "OTC CANDLE AI"
            textSize = 17f
            setTextColor(Color.WHITE)
        }

        val signal = TextView(this).apply {
            text = "WAIT"
            textSize = 26f
            setTextColor(Color.YELLOW)
            setPadding(0, 12, 0, 8)
        }

        val info = TextView(this).apply {
            text = "Candle Analysis: Not Ready"
            textSize = 12f
            setTextColor(Color.WHITE)
        }

        panel.addView(title)
        panel.addView(signal)
        panel.addView(info)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 200
        }

        windowManager.addView(panel, params)
        floatingView = panel

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        floatingView?.let {
            windowManager.removeView(it)
        }
        floatingView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}