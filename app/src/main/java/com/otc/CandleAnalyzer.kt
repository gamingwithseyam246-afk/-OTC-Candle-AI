package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class CandleSignal(
    val signal: String,
    val confidence: Int,
    val greenCount: Int,
    val redCount:
    val message: Strin

object CandleAnalyzer {

    fun analyze(bitmap: Bitmap): CandleSignal {
        var green = 0
        var red = 0

        val left = bitmap.width / 4
        val right = bitmap.width * 3 / 4
        val top = bitmap.height / 4
        val bottom = bitmap.height * 3 / 4

        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val pixel = bitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (g > r * 1.2 && g > b * 1.1) {
                    green++
                } else if (r > g * 1.2 && r > b * 1.1) {
                    red++
                }
            }
        }

        val total = green + red

        if (total < 20) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "চার্ট শনাক্ত করা যায়নি"
            )
        }

        val difference = abs(green - red)
        val confidence = (difference * 100 / total).coerceIn(0, 100)

        val signal = when {
            confidence < 20 -> "WAIT"
            green > red -> "UP"
            else -> "DOWN"
        }

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            "প্রাথমিক রং বিশ্লেষণ"
        )
    }
}