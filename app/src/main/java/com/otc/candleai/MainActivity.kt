package com.otc.candleai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Build

class MainActivity : Activity() {

    private val captureRequest = 7001
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val heading = TextView(this).apply {
            text = "OTC Candle AI"
            textSize = 22f
        }

        status = TextView(this).apply {
            text = "Screen Capture চালু করতে নিচের বাটনে চাপ দিন।"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }

        val button = Button(this).apply {
            text = "Start Screen Capture"
            setOnClickListener {
                val manager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    captureRequest
                )
            }
        }

        layout.addView(heading)
        layout.addView(status)
        layout.addView(button)
        setContentView(layout)
    }

    @Deprecated("Deprecated by Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                status.text = "Screen Capture চালু করার অনুরোধ পাঠানো হয়েছে।"
            } else {
                status.text = "Screen Capture অনুমতি পাওয়া যায়নি।"
            }
        }
    }
}