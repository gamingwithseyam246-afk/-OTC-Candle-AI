
package com.otc.candleai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val captureRequest = 7001
    private val overlayRequest = 7002

    private lateinit var status: TextView
    private lateinit var panelButton: Button

    private val prefs by lazy {
        getSharedPreferences("panel_settings", Context.MODE_PRIVATE)
    }

    private var panelEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        panelEnabled = prefs.getBoolean("panel_enabled", true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val heading = TextView(this).apply {
            text = "OTC CANDLE AI"
            textSize = 24f
        }

        status = TextView(this).apply {
            text = "Automatic Analysis প্রস্তুত"
            textSize = 17f
            setPadding(0, 24, 0, 24)
        }

        val startButton = Button(this).apply {
            text = "START ANALYSIS"

            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, overlayRequest)
                } else {
                    requestScreenCapture()
                }
            }
        }

        panelButton = Button(this).apply {
            setOnClickListener {
                panelEnabled = !panelEnabled

                prefs.edit()
                    .putBoolean("panel_enabled", panelEnabled)
                    .apply()

                val action = if (panelEnabled) {
                    FloatingWindowService.ACTION_SHOW
                } else {
                    FloatingWindowService.ACTION_HIDE
                }

                startService(
                    Intent(this@MainActivity, FloatingWindowService::class.java)
                        .setAction(action)
                )

                updatePanelButton()
            }
        }

        updatePanelButton()

        layout.addView(heading)
        layout.addView(status)
        layout.addView(startButton)
        layout.addView(panelButton)

        setContentView(layout)
    }

    private fun updatePanelButton() {
        panelButton.text = if (panelEnabled) {
            "PANEL: ON"
        } else {
            "PANEL: OFF"
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            captureRequest
        )
    }

    @Deprecated("Deprecated by Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == overlayRequest) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                status.text = "Floating Window permission দেওয়া হয়নি"
            }
        }

        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {

                // প্রথমে প্যানেল চালু হবে
                panelEnabled = true

                prefs.edit()
                    .putBoolean("panel_enabled", true)
                    .apply()

                startService(
                    Intent(this, FloatingWindowService::class.java)
                        .setAction(FloatingWindowService.ACTION_SHOW)
                )

                updatePanelButton()

                // এরপর Screen Capture চালু হবে
                val captureIntent = Intent(
                    this,
                    CaptureService::class.java
                ).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }

                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(captureIntent)
                } else {
                    startService(captureIntent)
                }

                status.text = "Analysis চালু হয়েছে।"

            } else {
                status.text = "Screen Capture অনুমতি পাওয়া যায়নি"
            }
        }
    }
}
