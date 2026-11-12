
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

    private data class Swing(val index: Int, val levelY: Int)

    private fun colorType(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        if (s < 0.22f || v < 0.15f) return 0

        return when {
            h in 65f..180f -> 1
            h <= 25f || h >= 335f -> 2
            else -> 0
        }
    }

    fun analyze(bitmap: Bitmap): CandleSignal {
        if (bitmap.isRecycled ||
            bitmap.width < 100 ||
            bitmap.height < 100
        ) {
            return CandleSignal("WAIT", 0, 0, 0, "Invalid chart image")
        }

        val detectedBars = detectCandles(bitmap)

        // Exclude the newest candle because it may still be forming.
        val bars = if (detectedBars.size >= 3) {
            detectedBars.dropLast(1)
        } else {
            detectedBars
        }

        val green = bars.count { it.bullish }
        val red = bars.size - green

        if (bars.size < 8) {
            return CandleSignal(
                "WAIT",
                0,
                green,
                red,
                "WAIT | COMPLETE: ${bars.size} | DETECTED: ${detectedBars.size}"
            )
        }

        val highs = findSwingHighs(bars)
        val lows = findSwingLows(bars)

        val last = bars.last()
        val previous = bars[bars.lastIndex - 1]

        val recent = bars.takeLast(6)
        val recentGreen = recent.count { it.bullish }
        val recentRed = recent.size - recentGreen

        val trendMove = bars.first().closeY - last.closeY

        val higherHigh = highs.size >= 2 &&
            highs.last().levelY < highs[highs.lastIndex - 1].levelY

        val higherLow = lows.size >= 2 &&
            lows.last().levelY < lows[lows.lastIndex - 1].levelY

        val lowerHigh = highs.size >= 2 &&
            highs.last().levelY > highs[highs.lastIndex - 1].levelY

        val lowerLow = lows.size >= 2 &&
            lows.last().levelY > lows[lows.lastIndex - 1].levelY

        val trend = when {
            higherHigh && higherLow -> "BULLISH"
            lowerHigh && lowerLow -> "BEARISH"
            trendMove > 8 -> "POSSIBLE BULLISH"
            trendMove < -8 -> "POSSIBLE BEARISH"
            else -> "SIDEWAYS"
        }

        val lastSwingHigh = highs.lastOrNull()
        val lastSwingLow = lows.lastOrNull()

        val bullishBOS = lastSwingHigh != null &&
            lastSwingHigh.index < bars.lastIndex - 1 &&
            previous.closeY > lastSwingHigh.levelY &&
            last.closeY < lastSwingHigh.levelY

        val bearishBOS = lastSwingLow != null &&
            lastSwingLow.index < bars.lastIndex - 1 &&
            previous.closeY < lastSwingLow.levelY &&
            last.closeY > lastSwingLow.levelY

        val bullishCHOCH = trend == "BEARISH" && bullishBOS
        val bearishCHOCH = trend == "BULLISH" && bearishBOS

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

        val pattern = detectPattern(previous, last, trendMove)

        var score = 0

        when (trend) {
            "BULLISH" -> score += 4
            "BEARISH" -> score -= 4
            "POSSIBLE BULLISH" -> score += 2
            "POSSIBLE BEARISH" -> score -= 2
        }

        if (higherHigh && higherLow) score += 2
        if (lowerHigh && lowerLow) score -= 2

        if (bullishBOS) score += 5
        if (bearishBOS) score -= 5

        if (bullishCHOCH) score += 3
        if (bearishCHOCH) score -= 3

        if (bullishSweep) score += 4
        if (bearishSweep) score -= 4

        if (bullishRejection) score += 3
        if (bearishRejection) score -= 3

        if (nearSupport && last.bullish) score += 2
        if (nearResistance && !last.bullish) score -= 2

        when (pattern) {
            "Hammer" -> score += 2
            "Shooting Star" -> score -= 2
            "Bullish Engulfing" -> score += 4
            "Bearish Engulfing" -> score -= 4
        }

        bars.takeLast(6).forEachIndexed { index, bar ->
            val weight = index + 1
            score += if (bar.bullish) weight else -weight
        }

        if (last.body > averageRange * 0.55f) {
            score += if (last.bullish) 2 else -2
        }

        if (last.upperWick > last.body * 2) score -= 2
        if (last.lowerWick > last.body * 2) score += 2

        val conflict =
            (bullishBOS || bullishSweep || bullishRejection) &&
            (bearishBOS || bearishSweep || bearishRejection)

        var confidence = (abs(score) * 3).coerceIn(0, 85)

        if (recentGreen >= 5 || recentRed >= 5) {
            confidence = (confidence + 5).coerceAtMost(85)
        }

        if (conflict) {
            confidence = (confidence - 15).coerceAtLeast(0)
        }

        val signal = when {
            conflict -> "WAIT"
            score >= 7 && confidence >= 30 -> "UP"
            score <= -7 && confidence >= 30 -> "DOWN"
            else -> "WAIT"
        }

        val strength = when {
            signal == "WAIT" -> "WEAK / WAIT"
            confidence >= 65 -> "STRONG"
            confidence >= 45 -> "MODERATE"
            else -> "WEAK"
        }

        val structure = when {
            higherHigh && higherLow -> "HIGHER HIGH + HIGHER LOW"
            lowerHigh && lowerLow -> "LOWER HIGH + LOWER LOW"
            else -> "MIXED STRUCTURE"
        }

        val bos = when {
            bullishBOS -> "BULLISH BOS"
            bearishBOS -> "BEARISH BOS"
            else -> "NO FRESH BOS"
        }

        val choch = when {
            bullishCHOCH -> "BULLISH CHOCH"
            bearishCHOCH -> "BEARISH CHOCH"
            else -> "NO CHOCH"
        }

        val sweep = when {
            bullishSweep -> "BULLISH SWEEP"
            bearishSweep -> "BEARISH SWEEP"
            else -> "NO LIQUIDITY SWEEP"
        }

        val wick = when {
            bullishRejection -> "BULLISH WICK REJECTION"
            bearishRejection -> "BEARISH WICK REJECTION"
            else -> "NO STRONG REJECTION"
        }

        val location = when {
            nearSupport -> "NEAR SUPPORT"
            nearResistance -> "NEAR RESISTANCE"
            else -> "BETWEEN LEVELS"
        }

        val direction = when (signal) {
            "UP" -> "NEXT CANDLE: UP"
            "DOWN" -> "NEXT CANDLE: DOWN"
            else -> "NEXT CANDLE: WAIT"
        }

        val message = buildString {
            append(direction)
            append(" | CONFIDENCE: $confidence%")
            append(" | SIGNAL: $strength")
            append(" | TREND: $trend")
            append(" | STRUCTURE: $structure")
            append(" | $bos | $choch")
            append(" | $sweep | $wick")
            append(" | LEVEL: $location")
            append(" | PATTERN: ${pattern.uppercase()}")
            append(" | MOMENTUM: GREEN $recentGreen / RED $recentRed")
            append(" | UPPER WICK: ${last.upperWick}")
            append(" | LOWER WICK: ${last.lowerWick}")
            append(" | BODY: ${last.body}")
            append(" | CONFLICT: $conflict")
            append(" | GREEN: $green | RED: $red")
            append(" | TOTAL: ${bars.size}")
        }

        return CandleSignal(signal, confidence, green, red, message)
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
            current.upperWick <= body && trend < 0
        ) return "Hammer"

        if (current.upperWick >= body * 2 &&
            current.lowerWick <= body && trend > 0
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

    private fun findSwingHighs(bars: List<Bar>): List<Swing> {
        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val current = bars[i].topY

            if ((1..radius).all { d ->
                    current < bars[i - d].topY &&
                    current < bars[i + d].topY
                }) {
                result.add(Swing(i, current))
            }
        }

        return result
    }

    private fun findSwingLows(bars: List<Bar>): List<Swing> {
        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val current = bars[i].bottomY

            if ((1..radius).all { d ->
                    current > bars[i - d].bottomY &&
                    current > bars[i + d].bottomY
                }) {
                result.add(Swing(i, current))
            }
        }

        return result
    }

    // Detect candle bodies first, instead of joining all colored pixels.
    private fun detectCandles(bitmap: Bitmap): List<Bar> {
        val w = bitmap.width
        val h = bitmap.height

        if (w < 100 || h < 100) return emptyList()

        val left = (w * 0.04f).toInt()
        val right = (w * 0.96f).toInt()
        val top = (h * 0.16f).toInt()
        val bottom = (h * 0.84f).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return emptyList()

        val types = IntArray(width)
        val counts = IntArray(width)
        val longestRuns = IntArray(width)

        // Find the dominant candle color and longest vertical colored run
        // in every screen column.
        for (x in 0 until width) {
            var greenCount = 0
            var redCount = 0
            var greenRun = 0
            var redRun = 0
            var maxGreenRun = 0
            var maxRedRun = 0

            for (y in 0 until height) {
                when (colorType(bitmap.getPixel(left + x, top + y))) {
                    1 -> {
                        greenCount++
                        greenRun++
                        redRun = 0
                        maxGreenRun = maxOf(maxGreenRun, greenRun)
                    }
                    2 -> {
                        redCount++
                        redRun++
                        greenRun = 0
                        maxRedRun = maxOf(maxRedRun, redRun)
                    }
                    else -> {
                        greenRun = 0
                        redRun = 0
                    }
                }
            }

            when {
                greenCount > redCount &&
                    greenCount >= 3 &&
                    maxGreenRun >= 2 -> {
                    types[x] = 1
                    counts[x] = greenCount
                    longestRuns[x] = maxGreenRun
                }

                redCount > greenCount &&
                    redCount >= 3 &&
                    maxRedRun >= 2 -> {
                    types[x] = 2
                    counts[x] = redCount
                    longestRuns[x] = maxRedRun
                }
            }
        }

        // A candle body is a group of neighboring columns with a
        // substantial vertical colored run. Thin wick-only columns
        // are not used to join separate candles.
        val bodyColumns = BooleanArray(width) { x ->
            types[x] != 0 &&
                counts[x] >= 3 &&
                longestRuns[x] >= 2
        }

        val result = mutableListOf<Bar>()
        var x = 0

        while (x < width) {
            if (!bodyColumns[x]) {
                x++
                continue
            }

            val type = types[x]
            val start = x
            var end = x

            while (
                end + 1 < width &&
                bodyColumns[end + 1] &&
                types[end + 1] == type
            ) {
                end++
            }

            // Split unusually wide merged groups at their weakest
            // body column, instead of discarding the entire group.
            val segments = mutableListOf<Pair<Int, Int>>()
            var segmentStart = start

            while (end - segmentStart + 1 > 18) {
                val searchStart = segmentStart + 5
                val searchEnd = minOf(segmentStart + 14, end - 4)

                if (searchStart > searchEnd) break

                var splitAt = searchStart
                var weakest = Int.MAX_VALUE

                for (candidate in searchStart..searchEnd) {
                    val strength = counts[candidate]

                    if (strength < weakest) {
                        weakest = strength
                        splitAt = candidate
                    }
                }

                segments.add(segmentStart to (splitAt - 1))
                segmentStart = splitAt
            }

            segments.add(segmentStart to end)

            for ((partStart, partEnd) in segments) {
                val candleWidth = partEnd - partStart + 1

                if (candleWidth !in 1..18) continue

                val rows = IntArray(height)
                var minY = height
                var maxY = -1

                for (cx in partStart..partEnd) {
                    for (cy in 0 until height) {
                        if (
                            colorType(
                                bitmap.getPixel(left + cx, top + cy)
                            ) == type
                        ) {
                            rows[cy]++
                            minY = minOf(minY, cy)
                            maxY = maxOf(maxY, cy)
                        }
                    }
                }

                if (maxY < minY) continue

                val peak = rows.maxOrNull() ?: 0
                val peakY = rows.indices.maxByOrNull { rows[it] } ?: minY
                val threshold = maxOf(1, ceil(peak * 0.60).toInt())

                var bodyTop = peakY
                var bodyBottom = peakY

                while (
                    bodyTop > minY &&
                    rows[bodyTop - 1] >= threshold
                ) {
                    bodyTop--
                }

                while (
                    bodyBottom < maxY &&
                    rows[bodyBottom + 1] >= threshold
                ) {
                    bodyBottom++
                }

                val bodySize = bodyBottom - bodyTop + 1
                val totalHeight = maxY - minY + 1

                if (totalHeight < 5 || bodySize < 1) continue

                result.add(
                    Bar(
                        bullish = type == 1,
                        body = bodySize,
                        upperWick = bodyTop - minY,
                        lowerWick = maxY - bodyBottom,
                        centerX = left + (partStart + partEnd) / 2,
                        closeY = if (type == 1) {
                            bodyTop + top
                        } else {
                            bodyBottom + top
                        },
                        topY = minY + top,
                        bottomY = maxY + top,
                        bodyTop = bodyTop + top,
                        bodyBottom = bodyBottom + top
                    )
                )
            }

            x = end + 1
        }

        return result.sortedBy { it.centerX }
    }
}
