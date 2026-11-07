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

    private lateinit var signalView: TextView
    private lateinit var infoView: TextView
    private lateinit var confidenceView: TextView

    private var receiverRegistered = false

    private val analysisReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            val signal = intent?.getStringExtra("signal") ?: "WAIT"
            val confidence = intent?.getIntExtra("confidence", 0) ?: 0
            val message = intent?.getStringExtra("message") ?: "Waiting"

            signalView.text = signal
            confidenceView.text = "Score: $confidence%"
            infoView.text = message

            signalView.setTextColor(
                when (signal) {
                    "UP" -> Color.GREEN
                    "DOWN" -> Color.RED
                    else -> Color.YELLOW
                }
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        if (floatingView == null) {

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

            signalView = TextView(this).apply {
                text = "WAIT"
                textSize = 26f
                setTextColor(Color.YELLOW)
                setPadding(0, 12, 0, 8)
            }

            confidenceView = TextView(this).apply {
                text = "Score: --"
                textSize = 14f
                setTextColor(Color.WHITE)
            }

            infoView = TextView(this).apply {
                text = "Waiting for analysis"
                textSize = 12f
                setTextColor(Color.WHITE)
            }

            panel.addView(title)
            panel.addView(signalView)
            panel.addView(confidenceView)
            panel.addView(infoView)

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
        }

        if (!receiverRegistered) {
            val filter = IntentFilter(
                "com.otc.candleai.ANALYSIS_UPDATE"
            )

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    analysisReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(analysisReceiver, filter)
            }

            receiverRegistered = true
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {

        if (receiverRegistered) {
            unregisterReceiver(analysisReceiver)
            receiverRegistered = false
        }

        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        floatingView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}