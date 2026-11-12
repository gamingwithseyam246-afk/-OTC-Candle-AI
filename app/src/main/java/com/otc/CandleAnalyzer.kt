
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

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (saturation < 0.35f || value < 0.20f) return 0

        return when {
            hue in 85f..175f &&
                g > r * 1.15 &&
                g > b * 1.05 -> 1

            (hue <= 18f || hue >= 340f) &&
                r > g * 1.25 &&
                r > b * 1.15 -> 2

            else -> 0
        }
    }

    fun analyze(bitmap: Bitmap): CandleSignal {

        if (bitmap.isRecycled ||
            bitmap.width < 100 ||
            bitmap.height < 100
        ) {
            return CandleSignal(
                "WAIT", 0, 0, 0,
                "Invalid screen image"
            )
        }

        val detectedBars = detectCandles(bitmap)

        // শেষ ক্যান্ডেলটি অসম্পূর্ণ হতে পারে।
        // বাকি সব শনাক্ত হওয়া ক্যান্ডেল বিশ্লেষণ হবে।
        val bars = if (detectedBars.size > 1) {
            detectedBars.dropLast(1)
        } else {
            emptyList()
        }

        val green = bars.count { it.bullish }
        val red = bars.count { !it.bullish }

        if (bars.size < 8) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "সম্পূর্ণ ক্যান্ডেল কম: ${bars.size}টি (Detected: ${detectedBars.size})"
            )
        }

        val highs = findSwingHighs(bars)
        val lows = findSwingLows(bars)

        val last = bars.last()
        val previous = bars[bars.lastIndex - 1]

        val trendMove = bars.first().closeY - last.closeY

        val higherHigh = highs.size >= 2 &&
            highs.last().levelY <
            highs[highs.lastIndex - 1].levelY

        val higherLow = lows.size >= 2 &&
            lows.last().levelY <
            lows[lows.lastIndex - 1].levelY

        val lowerHigh = highs.size >= 2 &&
            highs.last().levelY >
            highs[highs.lastIndex - 1].levelY

        val lowerLow = lows.size >= 2 &&
            lows.last().levelY >
            lows[lows.lastIndex - 1].levelY

        val trend = when {
            higherHigh && higherLow -> "UPTREND"
            lowerHigh && lowerLow -> "DOWNTREND"
            trendMove > 8 -> "POSSIBLE UPTREND"
            trendMove < -8 -> "POSSIBLE DOWNTREND"
            else -> "SIDEWAYS"
        }

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

        val bullishCHOCH =
            trend == "DOWNTREND" && bullishBOS

        val bearishCHOCH =
            trend == "UPTREND" && bearishBOS

        val averageRange = bars.takeLast(10)
            .map { it.range }
            .average()
            .toInt()
            .coerceAtLeast(1)

        val levelDistance = maxOf(8, averageRange * 2)
        val sweepBuffer = maxOf(2, averageRange / 5)

        val nearSupport = lastSwingLow != null &&
            abs(last.bottomY - lastSwingLow.levelY) <= levelDistance

        val nearResistance = lastSwingHigh != null &&
            abs(last.topY - lastSwingHigh.levelY) <= levelDistance

        val bullishSweep = lastSwingLow != null &&
            lastSwingLow.index < bars.lastIndex &&
            last.bottomY > lastSwingLow.levelY + sweepBuffer &&
            last.closeY < lastSwingLow.levelY

        val bearishSweep = lastSwingHigh != null &&
            lastSwingHigh.index < bars.lastIndex &&
            last.topY < lastSwingHigh.levelY - sweepBuffer &&
            last.closeY > lastSwingHigh.levelY

        val bullishRejection =
            last.lowerWick >= last.body.coerceAtLeast(1) * 2 &&
            last.lowerWick > last.upperWick &&
            nearSupport

        val bearishRejection =
            last.upperWick >= last.body.coerceAtLeast(1) * 2 &&
            last.upperWick > last.lowerWick &&
            nearResistance

        val pattern = detectPattern(
            previous,
            last,
            trendMove
        )

        var score = 0

        when (trend) {
            "UPTREND" -> score += 3
            "DOWNTREND" -> score -= 3
            "POSSIBLE UPTREND" -> score += 1
            "POSSIBLE DOWNTREND" -> score -= 1
        }

        if (higherHigh && higherLow) score += 2
        if (lowerHigh && lowerLow) score -= 2

        if (bullishBOS) score += 5
        if (bearishBOS) score -= 5

        if (bullishCHOCH) score += 3
        if (bearishCHOCH) score -= 3

        if (bullishSweep) score += 5
        if (bearishSweep) score -= 5

        if (bullishRejection) score += 2
        if (bearishRejection) score -= 2

        if (nearSupport && last.bullish) score += 1
        if (nearResistance && !last.bullish) score -= 1

        when (pattern) {
            "Hammer" -> score += 2
            "Shooting Star" -> score -= 2
            "Bullish Engulfing" -> score += 3
            "Bearish Engulfing" -> score -= 3
        }

        bars.takeLast(5).forEachIndexed { index, bar ->
            val weight = index + 1
            score += if (bar.bullish) weight else -weight
        }

        val bullishEvidence =
            (if (bullishBOS) 1 else 0) +
            (if (bullishSweep) 1 else 0) +
            (if (bullishRejection) 1 else 0)

        val bearishEvidence =
            (if (bearishBOS) 1 else 0) +
            (if (bearishSweep) 1 else 0) +
            (if (bearishRejection) 1 else 0)

        val conflict =
            bullishEvidence > 0 && bearishEvidence > 0

        var confidence = (abs(score) * 5)
            .coerceIn(0, 85)

        if (conflict) {
            confidence = (confidence - 15).coerceAtLeast(0)
        }

        val signal = when {
            confidence < 25 -> "WAIT"
            score >= 5 -> "UP"
            score <= -5 -> "DOWN"
            else -> "WAIT"
        }

        val structureText = when {
            higherHigh && higherLow ->
                "Higher High + Higher Low"

            lowerHigh && lowerLow ->
                "Lower High + Lower Low"

            else -> "Mixed Structure"
        }

        val bosText = when {
            bullishBOS -> "Bullish BOS"
            bearishBOS -> "Bearish BOS"
            else -> "No Fresh BOS"
        }

        val chochText = when {
            bullishCHOCH -> "Bullish CHOCH"
            bearishCHOCH -> "Bearish CHOCH"
            else -> "No CHOCH"
        }

        val sweepText = when {
            bullishSweep -> "Bullish Liquidity Sweep"
            bearishSweep -> "Bearish Liquidity Sweep"
            else -> "No Liquidity Sweep"
        }

        val rejectionText = when {
            bullishRejection -> "Bullish Wick Rejection"
            bearishRejection -> "Bearish Wick Rejection"
            else -> "No Strong Rejection"
        }

        val locationText = when {
            nearSupport -> "Near Support"
            nearResistance -> "Near Resistance"
            else -> "Between Levels"
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
            append(" | $sweepText")
            append(" | $rejectionText")
            append(" | $locationText")
            append(" | Pattern: $pattern")
            append(" | Conflict: $conflict")
            append(" | Green: $green, Red: $red")
            append(" | Total: ${bars.size}")
        }

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            message
        )
    }

    private fun findSwingHighs(
        bars: List<Bar>
    ): List<Swing> {

        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val current = bars[i].topY

            val isSwingHigh = (1..radius).all { distance ->
                current < bars[i - distance].topY &&
                    current < bars[i + distance].topY
            }

            if (isSwingHigh) {
                result.add(Swing(i, current))
            }
        }

        return result
    }

    private fun findSwingLows(
        bars: List<Bar>
    ): List<Swing> {

        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val current = bars[i].bottomY

            val isSwingLow = (1..radius).all { distance ->
                current > bars[i - distance].bottomY &&
                    current > bars[i + distance].bottomY
            }

            if (isSwingLow) {
                result.add(Swing(i, current))
            }
        }

        return result
    }

    private fun detectPattern(
        previous: Bar,
        current: Bar,
        trend: Int
    ): String {

        val body = current.body.coerceAtLeast(1)

        if (
            current.body <=
            maxOf(2, (current.range * 0.12).toInt())
        ) {
            return "Doji"
        }

        if (
            current.lowerWick >= body * 2 &&
            current.upperWick <= body &&
            trend < 0
        ) {
            return "Hammer"
        }

        if (
            current.upperWick >= body * 2 &&
            current.lowerWick <= body &&
            trend > 0
        ) {
            return "Shooting Star"
        }

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

        // চার্টের বেশি অংশ স্ক্যান করা হবে
        val left = (bitmap.width * 0.00f).toInt()
        val right = (bitmap.width * 0.95f).toInt()
        val top = (bitmap.height * 0.12f).toInt()
        val bottom = (bitmap.height * 0.69f).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return emptyList()

        val types = IntArray(width)

        for (x in 0 until width) {
            var green = 0
            var red = 0

            for (y in 0 until height) {
                when (colorType(bitmap.getPixel(left + x, top + y))) {
                    1 -> green++
                    2 -> red++
                }
            }

            types[x] = when {
                green >= 3 && green > red * 1.3 -> 1
                red >= 3 && red > green * 1.3 -> 2
                else -> 0
            }
        }

        val result = mutableListOf<Bar>()
        var x = 0

        while (x < width) {

            val type = types[x]

            if (type == 0) {
                x++
                continue
            }

            val start = x
            var end = x
            x++

            // পাশের ক্যান্ডেলের ফাঁক জোড়া দেওয়া হবে না
            while (x < width && types[x] == type) {
                end = x
                x++
            }

            val candleWidth = end - start + 1

            if (candleWidth > 25) continue

            val rows = IntArray(height)
            var minY = height
            var maxY = -1

            for (cx in start..end) {
                for (cy in 0 until height) {
                    if (colorType(bitmap.getPixel(left + cx, top + cy)) == type) {
                        rows[cy]++
                        minY = minOf(minY, cy)
                        maxY = maxOf(maxY, cy)
                    }
                }
            }

            if (maxY < minY) continue

            val peak = rows.maxOrNull() ?: 0
            if (peak < 1) continue

            val threshold = maxOf(1, (peak * 0.40f).toInt())

            val peakY = rows.indices.maxByOrNull { rows[it] } ?: continue

            var bodyTop = peakY
            var bodyBottom = peakY

            while (bodyTop > minY && rows[bodyTop - 1] >= threshold) {
                bodyTop--
            }

            while (bodyBottom < maxY && rows[bodyBottom + 1] >= threshold) {
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
                    centerX = left + (start + end) / 2,
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
