
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
import android.widget.TextView
import kotlin.math.abs

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_SHOW = "com.otc.candleai.SHOW_PANEL"
        const val ACTION_HIDE = "com.otc.candleai.HIDE_PANEL"
    }

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private lateinit var signal: TextView
    private lateinit var score: TextView
    private lateinit var info: TextView
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.otc.candleai.ANALYSIS_UPDATE") return
            if (!intent.hasExtra("signal") || panel == null) return

            val result = intent.getStringExtra("signal") ?: "WAIT"

            signal.text = result
            score.text = "Score: ${intent.getIntExtra("confidence", 0)}%"
            info.text = intent.getStringExtra("message") ?: ""

            signal.setTextColor(
                when (result) {
                    "UP" -> Color.GREEN
                    "DOWN" -> Color.RED
                    else -> Color.YELLOW
                }
            )

            panel?.let {
                it.requestLayout()
                try {
                    wm.updateViewLayout(it, it.layoutParams)
                } catch (_: Exception) {
                }
            }
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

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!registered) {
            val filter = IntentFilter("com.otc.candleai.ANALYSIS_UPDATE")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            registered = true
        }

        if (intent?.action == ACTION_HIDE) {
            hidePanel()
        } else {
            showPanel()
        }

        return START_NOT_STICKY
    }

    private fun showPanel() {
        if (panel != null) return

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), 0, dp(1), 0)
            setBackgroundColor(Color.rgb(12, 12, 12))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "OTC CANDLE AI"
            textSize = 5.5f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(1), 0)
        }

        val close = TextView(this).apply {
            text = "X"
            textSize = 6f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setPadding(dp(1), 0, 0, 0)
            setOnClickListener { hidePanel() }
        }

        header.addView(title)
        header.addView(close)

        signal = TextView(this).apply {
            text = "WAIT"
            textSize = 8f
            includeFontPadding = false
            setTextColor(Color.YELLOW)
            setSingleLine(true)
        }

        score = TextView(this).apply {
            text = "Score: --%"
            textSize = 5.5f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }

        info = TextView(this).apply {
            text = "Waiting..."
            textSize = 5f
            includeFontPadding = false
            setTextColor(Color.LTGRAY)
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            maxWidth = dp(145)
        }

        root.addView(header)
        root.addView(signal)
        root.addView(score)
        root.addView(info)

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
            gravity = Gravity.TOP or Gravity.START
            x = dp(4)
            y = dp(4)
        }

        header.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY

                        if (abs(dx) > dp(3) || abs(dy) > dp(3)) {
                            moved = true
                        }

                        if (moved) {
                            val maxX = (
                                resources.displayMetrics.widthPixels - root.width
                            ).coerceAtLeast(0)

                            val maxY = (
                                resources.displayMetrics.heightPixels - root.height
                            ).coerceAtLeast(0)

                            params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                            params.y = (startY + dy.toInt()).coerceIn(0, maxY)

                            try {
                                wm.updateViewLayout(root, params)
                            } catch (_: Exception) {
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return true
            }
        })

        try {
            wm.addView(root, params)
            panel = root
        } catch (e: Exception) {
            android.util.Log.e("FloatingWindowService", "Panel error", e)
        }
    }

    private fun hidePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
    }

    override fun onDestroy() {
        hidePanel()

        if (registered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            registered = false
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
