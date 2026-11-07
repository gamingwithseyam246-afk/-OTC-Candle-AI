

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
        val red: Int,
        val body: Int,
        val wick: Int,
        val centerY: Int
    )

    fun analyze(bitmap: Bitmap): CandleSignal {
        val bars = mutableListOf<Bar>()

        val left = (bitmap.width * 0.01).toInt()
        val right = (bitmap.width * 0.58).toInt()
        val top = (bitmap.height * 0.08).toInt()
        val bottom = (bitmap.height * 0.72).toInt()

        val groups = mutableListOf<Int>()
        var lastX = -10

        for (x in left until right) {
            var count = 0

            for (y in top until bottom) {
                val c = bitmap.getPixel(x, y)
                if (isCandle(c)) count++
            }

            if (count >= 5) {
                if (groups.isNotEmpty() && x - lastX > 2) {
                    addBar(groups, bitmap, bars, top, bottom)
                    groups.clear()
                }
                groups.add(x)
                lastX = x
            }
        }

        if (groups.isNotEmpty()) {
            addBar(groups, bitmap, bars, top, bottom)
        }

        if (bars.size < 5) {
            return CandleSignal(
                "WAIT", 0,
                bars.count { it.green > it.red },
                bars.count { it.red > it.green },
                "ক্যান্ডেল পাওয়া গেছে: ${bars.size}টি"
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

            if (bar.body > 0 && bar.wick > bar.body * 2) {
                up -= 0
                down -= 0
            }
        }

        val total = up + down
        if (total == 0) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "স্পষ্ট Trend পাওয়া যায়নি"
            )
        }

        val score = abs(up - down) * 100 / total

        val signal = when {
            score < 25 -> "WAIT"
            up > down -> "UP"
            else -> "DOWN"
        }

        val message = when (signal) {
            "UP" -> "সবুজ ক্যান্ডেলের ধারা বেশি"
            "DOWN" -> "লাল ক্যান্ডেলের ধারা বেশি"
            else -> "স্পষ্ট দিক নেই - অপেক্ষা করুন"
        }

        return CandleSignal(
            signal, score, green, red, message
        )
    }

    private fun isCandle(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return isGreen(r, g, b) || isRed(r, g, b)
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
        val width = last - first + 1

        if (width > 24) return

        val rowCounts = mutableMapOf<Int, Int>()
        var green = 0
        var red = 0
        var minY = bottom
        var maxY = top

        for (x in first..last) {
            for (y in top until bottom) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (isGreen(r, g, b)) {
                    green++
                    rowCounts[y] = (rowCounts[y] ?: 0) + 1
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                } else if (isRed(r, g, b)) {
                    red++
                    rowCounts[y] = (rowCounts[y] ?: 0) + 1
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }

        if (green + red < 8 || minY >= maxY) return

        val bodyRows = rowCounts.filter {
            it.value >= maxOf(2, width / 3)
        }.keys

        val body = if (bodyRows.isEmpty()) 0
            else bodyRows.max() - bodyRows.min()

        val wick = (maxY - minY - body).coerceAtLeast(0)

        bars.add(
            Bar(green, red, body, wick, (minY + maxY) / 2)
        )
    }
}
