
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

    private data class Swing(
        val index: Int,
        val levelY: Int
    )

    private fun colorType(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return when {
            r > 110 && r > g * 1.35 && r > b * 1.25 -> 2
            g > 90 && g > r * 1.20 && g > b * 1.08 -> 1
            else -> 0
        }
    }

    fun analyze(bitmap: Bitmap): CandleSignal {

        // Detect candles, then exclude the final possibly-forming candle.
        val detectedBars = detectCandles(bitmap)
        val bars = detectedBars.dropLast(1).takeLast(30)

        val green = bars.count { it.bullish }
        val red = bars.count { !it.bullish }

        if (bars.size < 8) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "পর্যাপ্ত সম্পূর্ণ ক্যান্ডেল পাওয়া যায়নি: ${bars.size}টি"
            )
        }

        val highs = findSwingHighs(bars)
        val lows = findSwingLows(bars)

        val last = bars.last()
        val previous = bars[bars.lastIndex - 1]

        // Screen Y: smaller means higher market price.
        val trendMove = bars.first().closeY - last.closeY

        // Market structure
        val higherHigh = highs.size >= 2 &&
            highs.last().levelY < highs[highs.lastIndex - 1].levelY

        val higherLow = lows.size >= 2 &&
            lows.last().levelY < lows[lows.lastIndex - 1].levelY

        val lowerHigh = highs.size >= 2 &&
            highs.last().levelY > highs[highs.lastIndex - 1].levelY

        val lowerLow = lows.size >= 2 &&
            lows.last().levelY > lows[lows.lastIndex - 1].levelY

        val trend = when {
            higherHigh && higherLow -> "UPTREND"
            lowerHigh && lowerLow -> "DOWNTREND"
            trendMove > 8 -> "POSSIBLE UPTREND"
            trendMove < -8 -> "POSSIBLE DOWNTREND"
            else -> "SIDEWAYS"
        }

        // Fresh Break of Structure
        val lastSwingHigh = highs.lastOrNull()
        val lastSwingLow = lows.lastOrNull()

        val bullishBOS = lastSwingHigh != null &&
            lastSwingHigh.index < bars.lastIndex - 1 &&
            previous.closeY >= lastSwingHigh.levelY &&
            last.closeY < lastSwingHigh.levelY

        val bearishBOS = lastSwingLow != null &&
            lastSwingLow.index < bars.lastIndex - 1 &&
            previous.closeY <= lastSwingLow.levelY &&
            last.closeY > lastSwingLow.levelY

        // Change of Character
        val bullishCHOCH = trend == "DOWNTREND" && bullishBOS
        val bearishCHOCH = trend == "UPTREND" && bearishBOS

        // Support and Resistance
        val averageRange = bars.takeLast(10)
            .map { it.range }
            .average()
            .toInt()
            .coerceAtLeast(1)

        val levelDistance = maxOf(8, averageRange * 2)

        val nearSupport = lastSwingLow != null &&
            abs(last.bottomY - lastSwingLow.levelY) <= levelDistance

        val nearResistance = lastSwingHigh != null &&
            abs(last.topY - lastSwingHigh.levelY) <= levelDistance

        // Wick rejection
        val bullishRejection =
            last.lowerWick >= last.body * 2 &&
            last.lowerWick > last.upperWick &&
            nearSupport

        val bearishRejection =
            last.upperWick >= last.body * 2 &&
            last.upperWick > last.lowerWick &&
            nearResistance

        // Candlestick patterns
        val pattern = detectPattern(previous, last, trendMove)

        // Combined score
        var score = 0

        when (trend) {
            "UPTREND" -> score += 4
            "DOWNTREND" -> score -= 4
            "POSSIBLE UPTREND" -> score += 2
            "POSSIBLE DOWNTREND" -> score -= 2
        }

        if (bullishBOS) score += 5
        if (bearishBOS) score -= 5

        if (bullishCHOCH) score += 3
        if (bearishCHOCH) score -= 3

        if (bullishRejection) score += 3
        if (bearishRejection) score -= 3

        if (nearSupport && last.bullish) score += 1
        if (nearResistance && !last.bullish) score -= 1

        when (pattern) {
            "Hammer" -> score += 2
            "Shooting Star" -> score -= 2
            "Bullish Engulfing" -> score += 3
            "Bearish Engulfing" -> score -= 3
        }

        // Recent momentum
        bars.takeLast(5).forEachIndexed { index, bar ->
            val weight = index + 1
            score += if (bar.bullish) weight else -weight
        }

        // Heuristic confidence, not a measured win probability.
        val confidence = (abs(score) * 4).coerceIn(0, 75)

        val signal = when {
            confidence < 20 -> "WAIT"
            score > 0 -> "UP"
            score < 0 -> "DOWN"
            else -> "WAIT"
        }

        val structureText = when {
            higherHigh && higherLow -> "Higher High + Higher Low"
            lowerHigh && lowerLow -> "Lower High + Lower Low"
            else -> "Mixed Market Structure"
        }

        val bosText = when {
            bullishBOS -> "Bullish BOS"
            bearishBOS -> "Bearish BOS"
            else -> "No fresh BOS"
        }

        val chochText = when {
            bullishCHOCH -> "Bullish CHOCH"
            bearishCHOCH -> "Bearish CHOCH"
            else -> "No CHOCH"
        }

        val rejectionText = when {
            bullishRejection -> "Bullish Wick Rejection"
            bearishRejection -> "Bearish Wick Rejection"
            else -> "No strong Wick Rejection"
        }

        val locationText = when {
            nearSupport -> "Near Support"
            nearResistance -> "Near Resistance"
            else -> "Between Levels"
        }

        val patternText = when (pattern) {
            "Hammer" -> "Hammer"
            "Shooting Star" -> "Shooting Star"
            "Doji" -> "Doji"
            "Bullish Engulfing" -> "Bullish Engulfing"
            "Bearish Engulfing" -> "Bearish Engulfing"
            else -> "No clear pattern"
        }

        val directionText = when (signal) {
            "UP" -> "সম্ভাব্য দিক: UP"
            "DOWN" -> "সম্ভাব্য দিক: DOWN"
            else -> "সম্ভাব্য দিক পরিষ্কার নয়: WAIT"
        }

        val message = buildString {
            append(directionText)
            append(" | Trend: $trend")
            append(" | Structure: $structureText")
            append(" | $bosText")
            append(" | $chochText")
            append(" | $rejectionText")
            append(" | $locationText")
            append(" | Pattern: $patternText")
            append(" | Green: $green, Red: $red")
        }

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            message
        )
    }

    private fun findSwingHighs(bars: List<Bar>): List<Swing> {
        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val current = bars[i].topY

            val isSwingHigh = (1..radius).all { distance ->
                current < bars[i - distance].topY &&
                    current < bars[i + distance].topY
            }

            if (isSwingHigh) result.add(Swing(i, current))
        }

        return result
    }

    private fun findSwingLows(bars: List<Bar>): List<Swing> {
        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val current = bars[i].bottomY

            val isSwingLow = (1..radius).all { distance ->
                current > bars[i - distance].bottomY &&
                    current > bars[i + distance].bottomY
            }

            if (isSwingLow) result.add(Swing(i, current))
        }

        return result
    }

    private fun detectPattern(
        previous: Bar,
        current: Bar,
        trend: Int
    ): String {

        val body = current.body.coerceAtLeast(1)

        if (current.body <= maxOf(2, (current.range * 0.12).toInt())) {
            return "Doji"
        }

        if (current.lowerWick >= body * 2 &&
            current.upperWick <= body &&
            trend < 0
        ) return "Hammer"

        if (current.upperWick >= body * 2 &&
            current.lowerWick <= body &&
            trend > 0
        ) return "Shooting Star"

        val engulfing =
            current.bodyTop <= previous.bodyTop &&
                current.bodyBottom >= previous.bodyBottom &&
                current.body > previous.body

        if (engulfing && current.bullish && !previous.bullish) {
            return "Bullish Engulfing"
        }

        if (engulfing && !current.bullish && previous.bullish) {
            return "Bearish Engulfing"
        }

        return "None"
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {

        if (bitmap.width < 100 || bitmap.height < 100) {
            return emptyList()
        }

        val left = (bitmap.width * 0.01).toInt()
        val right = (bitmap.width * 0.60).toInt()
        val top = (bitmap.height * 0.13).toInt()
        val bottom = (bitmap.height * 0.72).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return emptyList()

        val redCounts = IntArray(width)
        val greenCounts = IntArray(width)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val type = colorType(bitmap.getPixel(left + x, top + y))
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

            while (x < width && columnTypes[x] == type) {
                endX = x
                x++
            }

            while (x + 1 < width &&
                columnTypes[x] == 0 &&
                columnTypes[x + 1] == type
            ) {
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

            val threshold = maxOf(2, ceil(peak * 0.45).toInt())

            val peakY = rowCounts.indices.maxByOrNull {
                rowCounts[it]
            } ?: continue

            var bodyTop = peakY
            var bodyBottom = peakY

            while (bodyTop > minY &&
                rowCounts[bodyTop - 1] >= threshold
            ) bodyTop--

            while (bodyBottom < maxY &&
                rowCounts[bodyBottom + 1] >= threshold
            ) bodyBottom++

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
                    closeY = if (type == 1) bodyTop + top else bodyBottom + top,
                    topY = minY + top,
                    bottomY = maxY + top,
                    bodyTop = bodyTop + top,
                    bodyBottom = bodyBottom + top
                )
            )
        }

        return result.sortedBy { it.centerX }
    }
}
