

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
        val right = (bitmap.width * 0.62).toInt()
        val top = (bitmap.height * 0.10).toInt()
        val bottom = (bitmap.height * 0.68).toInt()

        val columns = mutableListOf<Int>()

        for (x in left until right) {
            var colored = 0

            for (y in top until bottom) {
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                if (isGreen(r, g, b) || isRed(r, g, b)) {
                    colored++
                }
            }

            if (colored >= 3) {
                columns.add(x)
            } else if (columns.isNotEmpty()) {
                addBar(columns, bitmap, bars, top, bottom)
                columns.clear()
            }
        }

        if (columns.isNotEmpty()) {
            addBar(columns, bitmap, bars, top, bottom)
        }

        if (bars.size < 5) {
            return CandleSignal(
                "WAIT", 0, 0, 0,
                "পর্যাপ্ত ক্যান্ডেল শনাক্ত হয়নি"
            )
        }

        val recent = bars.takeLast(10)

        val greenBars = recent.count { it.green > it.red }
        val redBars = recent.count { it.red > it.green }

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
                "WAIT", 0, greenBars, redBars,
                "স্পষ্ট দিক পাওয়া যায়নি"
            )
        }

        val score = (abs(up - down) * 100 / total)
            .coerceIn(0, 100)

        val signal = when {
            score < 25 -> "WAIT"
            up > down -> "UP"
            else -> "DOWN"
        }

        val message = when (signal) {
            "UP" -> "আগের ক্যান্ডেলের ঊর্ধ্বমুখী ধারা"
            "DOWN" -> "আগের ক্যান্ডেলের নিম্নমুখী ধারা"
            else -> "স্পষ্ট ধারা নেই - অপেক্ষা করুন"
        }

        return CandleSignal(
            signal, score, greenBars, redBars, message
        )
    }

    private fun isGreen(r: Int, g: Int, b: Int): Boolean {
        return g > 70 && g > r * 1.25 && g > b * 1.10
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        return r > 70 && r > g * 1.25 && r > b * 1.15
    }

    private fun addBar(
        columns: List<Int>,
        bitmap: Bitmap,
        bars: MutableList<Bar>,
        top: Int,
        bottom: Int
    ) {
        val first = columns.first()
        val last = columns.last()

        if (last - first > 25) return

        var green = 0
        var red = 0

        for (x in first..last) {
            for (y in top until bottom) {
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                if (isGreen(r, g, b)) green++
                else if (isRed(r, g, b)) red++
            }
        }

        if (green + red >= 8) {
            bars.add(Bar(green, red))
        }
    }
}
