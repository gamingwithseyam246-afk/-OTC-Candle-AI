
package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil

data class CandleSignal(
    val signal: String,
    val confidence: Int,
    val greenCount: Int,
    val redCount: Int,
    val message: String
)

object CandleAnalyzer {

    private data class Bar(
        val bullish: Boolean,
        val body: Int,
        val upperWick: Int,
        val lowerWick: Int,
        val centerX: Int,
        val closeY: Int,
        val topY: Int,
        val bottomY: Int,
        val bodyTop: Int,
        val bodyBottom: Int
    ) {
        val range: Int
            get() = bottomY - topY + 1
    }

    // Green = Bullish, Red = Bearish
    private fun colorType(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        if (r > 110 && r > g * 1.35 &&
            r > b * 1.25) return 2

        if (g > 90 && g > r * 1.20 &&
            g > b * 1.08) return 1

        return 0
    }

    fun analyze(bitmap: Bitmap): CandleSignal {
        val bars = detectCandles(bitmap).takeLast(10)

        val green = bars.count { it.bullish }
        val red = bars.size - green

        if (bars.size < 5) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "পর্যাপ্ত ক্যান্ডেল পাওয়া যায়নি: ${bars.size}টি"
            )
        }

        var score = 0

        // Recent candles get more weight
        bars.takeLast(5).forEachIndexed { index, bar ->
            val weight = index + 1
            score += if (bar.bullish) weight else -weight
        }

        // Screen trend: smaller Y means higher price
        val trend = bars.first().closeY - bars.last().closeY

        if (trend > 3) score += 3
        if (trend < -3) score -= 3

        val last = bars.last()
        val previous = bars[bars.lastIndex - 1]

        val pattern = detectPattern(previous, last, trend)

        when (pattern) {
            "Hammer" -> score += 4
            "Shooting Star" -> score -= 4
            "Bullish Engulfing" -> score += 5
            "Bearish Engulfing" -> score -= 5
        }

        val confidence =
            (abs(score) * 100 / 25).coerceIn(0, 100)

        val signal = when {
            confidence < 20 -> "WAIT"
            score > 0 -> "UP"
            score < 0 -> "DOWN"
            else -> "WAIT"
        }

        val directionText = when (signal) {
            "UP" -> "সম্ভাব্য ঊর্ধ্বমুখী ধারা"
            "DOWN" -> "সম্ভাব্য নিম্নমুখী ধারা"
            else -> "স্পষ্ট দিক পাওয়া যায়নি"
        }

        val patternText = when (pattern) {
            "Hammer" -> "Hammer Pattern"
            "Shooting Star" -> "Shooting Star Pattern"
            "Doji" -> "Doji Pattern"
            "Bullish Engulfing" -> "Bullish Engulfing Pattern"
            "Bearish Engulfing" -> "Bearish Engulfing Pattern"
            else -> "বিশেষ Pattern পাওয়া যায়নি"
        }

        val message =
            "$directionText | $patternText | " +
            "Green: $green, Red: $red"

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            message
        )
    }

    private fun detectPattern(
        previous: Bar,
        current: Bar,
        trend: Int
    ): String {

        val body = current.body.coerceAtLeast(1)

        // Small body / Doji
        if (current.body <= maxOf(2, (current.range * 0.12).toInt())) {
            return "Doji"
        }

        // Hammer after a downward screen trend
        if (current.lowerWick >= body * 2 &&
            current.upperWick <= body &&
            trend < 0) {
            return "Hammer"
        }

        // Shooting Star after an upward screen trend
        if (current.upperWick >= body * 2 &&
            current.lowerWick <= body &&
            trend > 0) {
            return "Shooting Star"
        }

        // Actual body-range engulfing check
        val engulfing =
            current.bodyTop <= previous.bodyTop &&
            current.bodyBottom >= previous.bodyBottom &&
            current.body > previous.body

        if (engulfing && current.bullish &&
            !previous.bullish) {
            return "Bullish Engulfing"
        }

        if (engulfing && !current.bullish &&
            previous.bullish) {
            return "Bearish Engulfing"
        }

        return "None"
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {
        if (bitmap.width < 100 || bitmap.height < 100) {
            return emptyList()
        }

        // Chart area used by the current screen layout
        val left = (bitmap.width * 0.01).toInt()
        val right = (bitmap.width * 0.60).toInt()
        val top = (bitmap.height * 0.13).toInt()
        val bottom = (bitmap.height * 0.72).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return emptyList()

        // Count red and green pixels in each column
        val redCounts = IntArray(width)
        val greenCounts = IntArray(width)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val type = colorType(
                    bitmap.getPixel(left + x, top + y)
                )

                if (type == 1) greenCounts[x]++
                if (type == 2) redCounts[x]++
            }
        }

        val columnTypes = IntArray(width)

        for (x in 0 until width) {
            val green = greenCounts[x]
            val red = redCounts[x]

            columnTypes[x] = when {
                green >= 2 && green > red -> 1
                red >= 2 && red > green -> 2
                else -> 0
            }
        }

        val result = mutableListOf<Bar>()
        var x = 0

        while (x < width) {
            val type = columnTypes[x]

            if (type == 0) {
                x++
                continue
            }

            val startX = x
            var endX = x
            x++

            // Merge nearby columns belonging to the same candle
            while (x < width &&
                columnTypes[x] == type) {
                endX = x
                x++
            }

            // Allow one empty column inside a candle
            while (x + 1 < width &&
                columnTypes[x] == 0 &&
                columnTypes[x + 1] == type) {
                endX = x + 1
                x += 2
            }

            val candleWidth = endX - startX + 1

            if (candleWidth > 30) continue

            val rowCounts = IntArray(height)
            var minY = height
            var maxY = -1

            for (cx in startX..endX) {
                for (cy in 0 until height) {
                    val pixelType = colorType(
                        bitmap.getPixel(left + cx, top + cy)
                    )

                    if (pixelType == type) {
                        rowCounts[cy]++
                        minY = minOf(minY, cy)
                        maxY = maxOf(maxY, cy)
                    }
                }
            }

            if (maxY < minY) continue

            val peak = rowCounts.maxOrNull() ?: 0
            if (peak < 2) continue

            // Wider rows are treated as the candle body
            val threshold = maxOf(
                2,
                ceil(peak * 0.45).toInt()
            )

            var peakY = rowCounts.indices.maxByOrNull {
                rowCounts[it]
            } ?: continue

            var bodyTop = peakY
            var bodyBottom = peakY

            while (bodyTop > minY &&
                rowCounts[bodyTop - 1] >= threshold) {
                bodyTop--
            }

            while (bodyBottom < maxY &&
                rowCounts[bodyBottom + 1] >= threshold) {
                bodyBottom++
            }

            val body = bodyBottom - bodyTop + 1
            val upperWick = bodyTop - minY
            val lowerWick = maxY - bodyBottom
            val totalHeight = maxY - minY + 1

            if (totalHeight < 6 || body < 1) continue

            result.add(
                Bar(
                    bullish = type == 1,
                    body = body,
                    upperWick = upperWick,
                    lowerWick = lowerWick,
                    centerX = left + (startX + endX) / 2,
                    closeY = if (type == 1) {
                        bodyTop + top
                    } else {
                        bodyBottom + top
                    },
                    topY = minY,
                    bottomY = maxY,
                    bodyTop = bodyTop,
                    bodyBottom = bodyBottom
                )
            )
        }

        // Oldest candle to newest candle
        return result.sortedBy { it.centerX }
    }
}
