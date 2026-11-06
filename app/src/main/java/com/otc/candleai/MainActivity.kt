package com.otc.candleai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
            text = "OTC Candle AI — প্রাথমিক সংস্করণ"
            textSize = 22f
        }
        status = TextView(this).apply {
            text = "এটি পরীক্ষামূলক অ্যাপ। এখনো Candle/OHLC শনাক্ত বা UP/DOWN signal তৈরি করে না।"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        val button = Button(this).apply {
            text = "Screen Capture অনুমতি দিন"
            setOnClickListener {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }
        layout.addView(heading)
        layout.addView(status)
        layout.addView(button)
        setContentView(layout)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            status.text = if (resultCode == RESULT_OK && data != null)
                "Screen capture permission granted for this session. Candle analysis is not implemented yet."
            else "Screen capture permission was not granted."
        }
    }
}
