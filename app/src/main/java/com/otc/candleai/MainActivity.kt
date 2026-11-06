
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val button = Button(this).apply {
            text = "START ANALYSIS"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23 &&
                    !Settings.canDrawOverlays(this@MainActivity)) {

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

        layout.addView(heading)
        layout.addView(status)
        layout.addView(button)
        setContentView(layout)
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

                startService(
                    Intent(this, FloatingWindowService::class.java)
                )

                status.text = "Floating Panel চালু করার অনুরোধ পাঠানো হয়েছে।"
            } else {
                status.text = "Screen Capture অনুমতি পাওয়া যায়নি"
            }
        }
    }
}
