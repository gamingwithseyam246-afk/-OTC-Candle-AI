
package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class CandleSignal(
    val signal: String,
    val confidence: Int,
    val greenCount: Int,
    val redCount: Int,
    val message: String
)

object CandleAnalyzer {

    private data class Bar(
        val green: Int,
        val red: Int
    )

    fun analyze(bitmap: Bitmap): CandleSignal {
        val bars = mutableListOf<Bar>()

        val left = (bitmap.width * 0.08).toInt()
        val right = (bitmap.width * 0.68).toInt()
        val top = (bitmap.height * 0.08).toInt()
        val bottom = (bitmap.height * 0.72).toInt()

        val active = mutableListOf<Int>()
        var lastActive = -10

        for (x in left until right) {
            var count = 0

            for (y in top until bottom) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (isGreen(r, g, b) || isRed(r, g, b)) {
                    count++
                }
            }

            if (count >= 1) {
                if (x - lastActive > 3 && active.isNotEmpty()) {
                    addBar(active, bitmap, bars, top, bottom)
                    active.clear()
                }

                active.add(x)
                lastActive = x
            }
        }

        if (active.isNotEmpty()) {
            addBar(active, bitmap, bars, top, bottom)
        }

        if (bars.size < 5) {
            return CandleSignal(
                "WAIT", 0,
                bars.count { it.green > it.red },
                bars.count { it.red > it.green },
                "পর্যাপ্ত ক্যান্ডেল শনাক্ত হয়নি"
            )
        }

        val recent = bars.takeLast(10)
        val green = recent.count { it.green > it.red }
        val red = recent.count { it.red > it.green }

        var up = 0
        var down = 0

        recent.forEachIndexed { index, bar ->
            val weight = index + 1
            if (bar.green > bar.red) up += weight
            if (bar.red > bar.green) down += weight
        }

        val total = up + down

        if (total == 0) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "স্পষ্ট দিক পাওয়া যায়নি"
            )
        }

        val score = abs(up - down) * 100 / total

        val signal = when {
            score < 25 -> "WAIT"
            up > down -> "UP"
            else -> "DOWN"
        }

        val message = when (signal) {
            "UP" -> "আগের ক্যান্ডেলে ঊর্ধ্বমুখী ধারা"
            "DOWN" -> "আগের ক্যান্ডেলে নিম্নমুখী ধারা"
            else -> "স্পষ্ট ধারা নেই - অপেক্ষা করুন"
        }

        return CandleSignal(signal, score, green, red, message)
    }

    private fun isGreen(r: Int, g: Int, b: Int): Boolean {
        return g > 70 && g > r * 1.20 && g > b * 1.08
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        return r > 70 && r > g * 1.20 && r > b * 1.08
    }

    private fun addBar(
        columns: List<Int>,
        bitmap: Bitmap,
        bars: MutableList<Bar>,
        top: Int,
        bottom: Int
    ) {
        if (columns.isEmpty()) return

        val first = columns.first()
        val last = columns.last()

        if (last - first > 25) return

        var green = 0
        var red = 0

        for (x in first..last) {
            for (y in top until bottom) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (isGreen(r, g, b)) green++
                else if (isRed(r, g, b)) red++
            }
        }

        if (green + red >= 4) {
            bars.add(Bar(green, red))
        }
    }
}
