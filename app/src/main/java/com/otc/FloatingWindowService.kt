
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

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_SHOW =
            "com.otc.candleai.SHOW_PANEL"

        const val ACTION_HIDE =
            "com.otc.candleai.HIDE_PANEL"

        private const val PREFS = "floating_panel_settings"
        private const val KEY_X = "panel_x"
        private const val KEY_Y = "panel_y"
    }

    private lateinit var windowManager: WindowManager

    private var floatingView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private lateinit var signalView: TextView
    private lateinit var infoView: TextView
    private lateinit var confidenceView: TextView

    private var receiverRegistered = false
    private var panelHidden = false

    private val analysisReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            when (intent?.action) {

                ACTION_HIDE -> hidePanel()

                ACTION_SHOW -> showPanel()

                "com.otc.candleai.ANALYSIS_UPDATE" -> {

                    if (floatingView == null) return

                    val signal =
                        intent.getStringExtra("signal") ?: "WAIT"

                    val confidence =
                        intent.getIntExtra("confidence", 0)

                    val message =
                        intent.getStringExtra("message") ?: "Waiting"

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

        registerAnalysisReceiver()

        when (intent?.action) {
            ACTION_HIDE -> hidePanel()
            ACTION_SHOW -> showPanel()
            else -> showPanel()
        }

        return START_NOT_STICKY
    }

    private fun registerAnalysisReceiver() {

        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction("com.otc.candleai.ANALYSIS_UPDATE")
            addAction(ACTION_HIDE)
            addAction(ACTION_SHOW)
        }

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

        panelHidden = false

        if (floatingView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            setBackgroundColor(Color.rgb(25, 25, 25))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "OTC CANDLE AI"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 20, 0)
        }

        val hideButton = TextView(this).apply {
            text = "  ×  "
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(8, 0, 0, 0)
            setOnClickListener {
                hidePanel()
            }
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        header.addView(hideButton)

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
            maxLines = 5
        }

        panel.addView(header)
        panel.addView(signalView)
        panel.addView(confidenceView)
        panel.addView(infoView)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

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
            x = prefs.getInt(KEY_X, 20)
            y = prefs.getInt(KEY_Y, 200)
        }

        panelParams = params

        // Drag the panel by touching its title area.
        header.setOnTouchListener(
            object : View.OnTouchListener {

                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var dragging = false

                override fun onTouch(
                    view: View,
                    event: MotionEvent
                ): Boolean {

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
                            val dx =
                                (event.rawX - touchX).toInt()

                            val dy =
                                (event.rawY - touchY).toInt()

                            if (kotlin.math.abs(dx) > 4 ||
                                kotlin.math.abs(dy) > 4
                            ) {
                                dragging = true
                            }

                            params.x = (startX + dx).coerceAtLeast(0)
                            params.y = (startY + dy).coerceAtLeast(0)

                            try {
                                windowManager.updateViewLayout(
                                    panel,
                                    params
                                )
                            } catch (_: Exception) {
                            }

                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (dragging) {
                                savePosition(params)
                            }
                            return true
                        }
                    }

                    return false
                }
            }
        )

        try {
            windowManager.addView(panel, params)
            floatingView = panel
        } catch (e: Exception) {
            floatingView = null
            LogError("Panel could not be displayed", e)
        }
    }

    private fun savePosition(
        params: WindowManager.LayoutParams
    ) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    private fun hidePanel() {

        panelHidden = true

        floatingView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }

        floatingView = null
        panelParams = null
    }

    private fun LogError(message: String, error: Exception) {
        android.util.Log.e("FloatingWindowService", message, error)
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
