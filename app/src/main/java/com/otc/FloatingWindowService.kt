
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_SHOW = "com.otc.candleai.SHOW_PANEL"
        const val ACTION_HIDE = "com.otc.candleai.HIDE_PANEL"
        private const val PREFS = "floating_panel_settings"
        private const val KEY_X = "panel_x"
        private const val KEY_Y = "panel_y"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: LinearLayout? = null
    private var receiverRegistered = false

    private lateinit var signalView: TextView
    private lateinit var infoView: TextView
    private lateinit var confidenceView: TextView

    private val analysisReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.otc.candleai.ANALYSIS_UPDATE") return
            if (!intent.hasExtra("signal") || floatingView == null) return

            val signal = intent.getStringExtra("signal") ?: "WAIT"
            val confidence = intent.getIntExtra("confidence", 0)
            val message = intent.getStringExtra("message") ?: "Waiting"

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

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerAnalysisReceiver()

        when (intent?.action) {
            ACTION_HIDE -> hidePanel()
            else -> showPanel()
        }

        return START_NOT_STICKY
    }

    private fun registerAnalysisReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter("com.otc.candleai.ANALYSIS_UPDATE")

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

    private fun showPanel() {
        if (floatingView != null) return

        val d = resources.displayMetrics.density
        fun dp(value: Int) = (value * d).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setBackgroundColor(Color.rgb(25, 25, 25))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "OTC CANDLE AI"
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), dp(5), dp(4))
        }

        val hideButton = TextView(this).apply {
            text = "×"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(7), dp(2), dp(5), dp(2))
            setOnClickListener { hidePanel() }
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(hideButton)

        signalView = TextView(this).apply {
            text = "WAIT"
            textSize = 16f
            setTextColor(Color.YELLOW)
            setPadding(0, dp(2), 0, 0)
        }

        confidenceView = TextView(this).apply {
            text = "Score: --"
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(2))
        }

        infoView = TextView(this).apply {
            text = "Waiting for analysis"
            textSize = 9f
            setTextColor(Color.WHITE)
            maxLines = 5
            setPadding(0, dp(2), 0, dp(2))
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(infoView)
        }

        panel.addView(header)
        panel.addView(signalView)
        panel.addView(confidenceView)
        panel.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val params = WindowManager.LayoutParams(
            dp(155),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(10))
            y = prefs.getInt(KEY_Y, dp(150))
        }

        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()

                        if (abs(dx) > 3 || abs(dy) > 3) dragging = true

                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels

                        params.x = (startX + dx).coerceIn(
                            0, (screenW - panel.width).coerceAtLeast(0)
                        )
                        params.y = (startY + dy).coerceIn(
                            0, (screenH - panel.height).coerceAtLeast(0)
                        )

                        try {
                            windowManager.updateViewLayout(panel, params)
                        } catch (e: Exception) {
                            android.util.Log.e("FloatingWindowService", "Move failed", e)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .putInt(KEY_X, params.x)
                                .putInt(KEY_Y, params.y)
                                .apply()
                        }
                        return true
                    }
                }
                return true
            }
        })

        try {
            windowManager.addView(panel, params)
            floatingView = panel
        } catch (e: Exception) {
            floatingView = null
            android.util.Log.e("FloatingWindowService", "Panel display failed", e)
        }
    }

    private fun hidePanel() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                android.util.Log.e("FloatingWindowService", "Hide failed", e)
            }
        }
        floatingView = null
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(analysisReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        hidePanel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
