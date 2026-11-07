
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

        val chartLeft = 0
        val chartRight = (bitmap.width * 0.84).toInt()
        val chartTop = (bitmap.height * 0.05).toInt()
        val chartBottom = (bitmap.height * 0.70).toInt()

        val activeColumns = mutableListOf<Int>()

        for (x in chartLeft until chartRight) {
            var green = 0
            var red = 0

            for (y in chartTop until chartBottom step 2) {
                val pixel = bitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (g > 70 && g > r * 1.25 && g > b * 1.10) {
                    green++
                } else if (r > 70 && r > g * 1.25 && r > b * 1.15) {
                    red++
                }
            }

            if (green + red >= 2) {
                activeColumns.add(x)
            } else {
                if (activeColumns.isNotEmpty()) {
                    addBar(activeColumns, bitmap, bars, chartTop, chartBottom)
                    activeColumns.clear()
                }
            }
        }

        if (activeColumns.isNotEmpty()) {
            addBar(activeColumns, bitmap, bars, chartTop, chartBottom)
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

        var upWeight = 0
        var downWeight = 0

        for (i in recent.indices) {
            val bar = recent[i]
            val weight = i + 1

            if (bar.green > bar.red) {
                upWeight += weight
            } else if (bar.red > bar.green) {
                downWeight += weight
            }
        }

        val totalWeight = upWeight + downWeight

        if (totalWeight == 0) {
            return CandleSignal(
                "WAIT", 0, greenBars, redBars,
                "স্পষ্ট দিক পাওয়া যায়নি"
            )
        }

        val difference = abs(upWeight - downWeight)
        val score = (difference * 100 / totalWeight)
            .coerceIn(0, 100)

        val signal = when {
            score < 25 -> "WAIT"
            upWeight > downWeight -> "UP"
            else -> "DOWN"
        }

        val message = when (signal) {
            "UP" -> "আগের ক্যান্ডেলে ঊর্ধ্বমুখী ধারা"
            "DOWN" -> "আগের ক্যান্ডেলে নিম্নমুখী ধারা"
            else -> "পরিষ্কার ধারা নেই - অপেক্ষা করুন"
        }

        return CandleSignal(
            signal,
            score,
            greenBars,
            redBars,
            message
        )
    }

    private fun addBar(
        columns: List<Int>,
        bitmap: Bitmap,
        bars: MutableList<Bar>,
        top: Int,
        bottom: Int
    ) {
        val firstX = columns.first()
        val lastX = columns.last()

        if (lastX - firstX > 35) return

        var green = 0
        var red = 0

        for (x in firstX..lastX) {
            for (y in top until bottom step 2) {
                val pixel = bitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (g > 70 && g > r * 1.25 && g > b * 1.10) {
                    green++
                } else if (r > 70 && r > g * 1.25 && r > b * 1.15) {
                    red++
                }
            }
        }

        if (green + red >= 8) {
            bars.add(Bar(green, red))
        }
    }
}
