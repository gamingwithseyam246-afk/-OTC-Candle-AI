
package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        val bottomY: Int
    ) {
        val range: Int
            get() = max(1, bottomY - topY + 1)
    }

    private data class Analysis(
        val score: Double,
        val trend: String,
        val momentum: String,
        val support: Boolean,
        val resistance: Boolean,
        val bos: String,
        val choch: String,
        val sweep: String,
        val pattern: String,
        val rejection: String
    )

    fun analyze(
        bitmap: Bitmap,
        includeLiveCandle: Boolean = false
    ): CandleSignal {

        val detected = detectCandles(bitmap)

        if (detected.isEmpty()) {
            return emptySignal("চার্টে ক্যান্ডেল শনাক্ত হয়নি")
        }

        val bars = if (includeLiveCandle) {
            detected
        } else {
            detected.dropLast(1)
        }

        if (bars.size < 3) {
            return CandleSignal(
                "WAIT",
                0,
                bars.count { it.bullish },
                bars.count { !it.bullish },
                "পর্যাপ্ত সম্পূর্ণ ক্যান্ডেল পাওয়া যায়নি।"
            )
        }

        val green = bars.count { it.bullish }
        val red = bars.size - green

        val analysis = analyzeStructure(bars)

        val rawScore = analysis.score
        val confidence = abs(rawScore)
            .toInt()
            .coerceIn(0, 100)

        val recent = bars.takeLast(5)
        val recentGreen = recent.count { it.bullish }
        val recentRed = recent.size - recentGreen

        val conflict =
            (rawScore > 0 && recentRed >= 4) ||
            (rawScore < 0 && recentGreen >= 4)

        val signal = when {
            confidence < 35 -> "WAIT"
            conflict && confidence < 65 -> "WAIT"
            rawScore >= 35 -> "UP"
            rawScore <= -35 -> "DOWN"
            else -> "WAIT"
        }

        val directionText = when (signal) {
            "UP" -> "পরবর্তী ক্যান্ডেলের জন্য UP চাপ দেখা যাচ্ছে"
            "DOWN" -> "পরবর্তী ক্যান্ডেলের জন্য DOWN চাপ দেখা যাচ্ছে"
            else -> "সিগন্যাল নিশ্চিত করার মতো পর্যাপ্ত মিল নেই"
        }

        val message = buildString {
            append(directionText)
            append("\n")
            append("Signal Strength: $confidence/100")
            append("\n")
            append("Trend: ${analysis.trend}")
            append("\n")
            append("Momentum: ${analysis.momentum}")
            append("\n")
            append("Support: ${if (analysis.support) "কাছাকাছি" else "নিশ্চিত নয়"}")
            append("\n")
            append("Resistance: ${if (analysis.resistance) "কাছাকাছি" else "নিশ্চিত নয়"}")
            append("\n")
            append("BOS: ${analysis.bos} | CHOCH: ${analysis.choch}")
            append("\n")
            append("Liquidity Sweep: ${analysis.sweep}")
            append("\n")
            append("Pattern: ${analysis.pattern}")
            append("\n")
            append("Wick: ${analysis.rejection}")
            append("\n")
            append("Analyzed Candles: ${bars.size}")
            if (includeLiveCandle) {
                append("\nLIVE observation — preliminary")
            }
        }

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            message
        )
    }

    private fun analyzeStructure(bars: List<Bar>): Analysis {

        var score = 0.0

        val recent = bars.takeLast(5)
        val green = recent.count { it.bullish }
        val red = recent.size - green

        val first = bars.first()
        val last = bars.last()

        // Overall trend
        val trend = when {
            last.closeY < first.closeY -> "UPTREND"
            last.closeY > first.closeY -> "DOWNTREND"
            else -> "SIDEWAYS"
        }

        when (trend) {
            "UPTREND" -> score += 12.0
            "DOWNTREND" -> score -= 12.0
        }

        // Candle momentum
        val momentumScore = (green - red) * 4.0
        score += momentumScore

        val momentum = when {
            momentumScore >= 12 -> "STRONG BULLISH"
            momentumScore >= 4 -> "BULLISH"
            momentumScore <= -12 -> "STRONG BEARISH"
            momentumScore <= -4 -> "BEARISH"
            else -> "NEUTRAL"
        }

        // Body strength
        val averageBody = recent.map {
            it.body.toDouble()
        }.average()

        if (averageBody > 0) {
            val strength = (
                last.body.toDouble() / averageBody
            ).coerceIn(0.0, 2.0)

            score += if (last.bullish) {
                strength * 5.0
            } else {
                -strength * 5.0
            }
        }

        // Support and resistance
        val previousBars = bars.dropLast(1)

        val supportLevel = previousBars.minOf { it.bottomY }
        val resistanceLevel = previousBars.maxOf { it.topY }

        val distanceToSupport = abs(last.bottomY - supportLevel)
        val distanceToResistance = abs(last.topY - resistanceLevel)

        val support = distanceToSupport <= last.range * 2
        val resistance = distanceToResistance <= last.range * 2

        if (support && last.lowerWick > last.body) {
            score += 7.0
        }

        if (resistance && last.upperWick > last.body) {
            score -= 7.0
        }

        // BOS
        val previous = previousBars.takeLast(5)
        val previousHigh = previous.minOf { it.topY }
        val previousLow = previous.maxOf { it.bottomY }

        val bos = when {
            last.closeY < previousHigh -> {
                score += 8.0
                "BULLISH BREAK"
            }

            last.closeY > previousLow -> {
                score -= 8.0
                "BEARISH BREAK"
            }

            else -> "NONE"
        }

        // CHOCH
        val older = bars.dropLast(3).takeLast(5)

        val choch = if (older.isNotEmpty()) {
            val oldTrendUp =
                older.last().closeY < older.first().closeY

            val oldTrendDown =
                older.last().closeY > older.first().closeY

            when {
                oldTrendUp && !last.bullish &&
                    last.closeY > older.maxOf { it.bottomY } -> {
                    score -= 7.0
                    "BEARISH CHANGE"
                }

                oldTrendDown && last.bullish &&
                    last.closeY < older.minOf { it.topY } -> {
                    score += 7.0
                    "BULLISH CHANGE"
                }

                else -> "NONE"
            }
        } else {
            "INSUFFICIENT DATA"
        }

        // Liquidity sweep
        val sweep = when {
            last.topY < previousHigh &&
                last.closeY > previousHigh -> {
                score -= 3.0
                "HIGH SWEEP"
            }

            last.bottomY > previousLow &&
                last.closeY < previousLow -> {
                score += 3.0
                "LOW SWEEP"
            }

            else -> "NONE"
        }

        // Wick rejection
        val rejection = when {
            last.lowerWick > last.body * 1.5 &&
                last.lowerWick > last.upperWick -> {
                score += 9.0
                "LOWER WICK REJECTION"
            }

            last.upperWick > last.body * 1.5 &&
                last.upperWick > last.lowerWick -> {
                score -= 9.0
                "UPPER WICK REJECTION"
            }

            else -> "NONE"
        }

        // Candle patterns
        val previousCandle = bars[bars.lastIndex - 1]

        val pattern = when {
            !previousCandle.bullish &&
                last.bullish &&
                last.body > previousCandle.body * 1.2 -> {
                score += 10.0
                "BULLISH ENGULFING-LIKE"
            }

            previousCandle.bullish &&
                !last.bullish &&
                last.body > previousCandle.body * 1.2 -> {
                score -= 10.0
                "BEARISH ENGULFING-LIKE"
            }

            last.lowerWick > last.body * 2 &&
                last.upperWick < last.body -> {
                score += 7.0
                "HAMMER-LIKE"
            }

            last.upperWick > last.body * 2 &&
                last.lowerWick < last.body -> {
                score -= 7.0
                "SHOOTING STAR-LIKE"
            }

            else -> "NONE"
        }

        if (
            (trend == "UPTREND" && red >= 4) ||
            (trend == "DOWNTREND" && green >= 4)
        ) {
            score *= 0.75
        }

        score = score.coerceIn(-100.0, 100.0)

        return Analysis(
            score = score,
            trend = trend,
            momentum = momentum,
            support = support,
            resistance = resistance,
            bos = bos,
            choch = choch,
            sweep = sweep,
            pattern = pattern,
            rejection = rejection
        )
    }

    private fun colorType(color: Int): Int {

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (saturation < 0.25f || value < 0.25f) {
            return 0
        }

        return when {
            hue in 75f..175f -> 1
            hue <= 18f || hue >= 340f -> -1
            else -> 0
        }
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {

        val width = bitmap.width
        val height = bitmap.height

        if (width < 100 || height < 100) return emptyList()

        // Chart crop: excludes most controls below the chart.
        val startX = (width * 0.00f).toInt()
        val endX = (width * 0.57f).toInt()
        val startY = (height * 0.12f).toInt()
        val endY = (height * 0.69f).toInt()

        if (endX <= startX || endY <= startY) return emptyList()

        val scanHeight = endY - startY
        val columnTypes = IntArray(endX - startX)

        // First pass: identify dominant red/green columns.
        for (x in startX until endX) {
            var greenPixels = 0
            var redPixels = 0

            for (y in startY until endY step 2) {
                when (colorType(bitmap.getPixel(x, y))) {
                    1 -> greenPixels++
                    -1 -> redPixels++
                }
            }

            columnTypes[x - startX] = when {
                greenPixels >= 3 &&
                    greenPixels >= redPixels * 1.5f -> 1

                redPixels >= 3 &&
                    redPixels >= greenPixels * 1.5f -> -1

                else -> 0
            }
        }

        val bars = mutableListOf<Bar>()
        var x = 0

        while (x < columnTypes.size) {
            val type = columnTypes[x]

            if (type == 0) {
                x++
                continue
            }

            val candleStart = x
            var candleEnd = x

            while (
                candleEnd + 1 < columnTypes.size &&
                columnTypes[candleEnd + 1] == type
            ) {
                candleEnd++
            }

            val candleWidth = candleEnd - candleStart + 1

            // Reject unusually wide colored blocks such as UI buttons.
            if (candleWidth in 2..14) {

                val rowCounts = IntArray(scanHeight)

                for (cx in candleStart..candleEnd) {
                    val actualX = startX + cx

                    for (cy in startY until endY) {
                        if (
                            colorType(bitmap.getPixel(actualX, cy)) == type
                        ) {
                            rowCounts[cy - startY]++
                        }
                    }
                }

                var top = endY
                var bottom = startY
                var bodyTop = endY
                var bodyBottom = startY

                val bodyThreshold = max(
                    2,
                    (candleWidth * 0.65f).toInt()
                )

                for (i in rowCounts.indices) {
                    val count = rowCounts[i]
                    val actualY = startY + i

                    if (count > 0) {
                        top = min(top, actualY)
                        bottom = max(bottom, actualY)
                    }

                    if (count >= bodyThreshold) {
                        bodyTop = min(bodyTop, actualY)
                        bodyBottom = max(bodyBottom, actualY)
                    }
                }

                val totalHeight = bottom - top + 1
                val bodyHeight = bodyBottom - bodyTop + 1

                val bodyFill = if (bodyHeight > 0) {
                    rowCounts.count {
                        it >= bodyThreshold
                    }.toFloat() / bodyHeight
                } else {
                    0f
                }

                val plausibleShape =
                    bottom > top &&
                    bodyBottom >= bodyTop &&
                    bodyHeight >= 2 &&
                    totalHeight >= 4 &&
                    totalHeight <= scanHeight * 0.45f &&
                    bodyFill >= 0.65f

                if (plausibleShape) {
                    val body = max(1, bodyHeight)

                    val upperWick = max(
                        0,
                        bodyTop - top
                    )

                    val lowerWick = max(
                        0,
                        bottom - bodyBottom
                    )

                    val closeY = if (type == 1) {
                        bodyTop
                    } else {
                        bodyBottom
                    }

                    bars.add(
                        Bar(
                            bullish = type == 1,
                            body = body,
                            upperWick = upperWick,
                            lowerWick = lowerWick,
                            centerX = startX +
                                (candleStart + candleEnd) / 2,
                            closeY = closeY,
                            topY = top,
                            bottomY = bottom
                        )
                    )
                }
            }

            x = candleEnd + 1
        }

        return bars.sortedBy { it.centerX }
    }

    private fun emptySignal(message: String): CandleSignal {
        return CandleSignal(
            "WAIT",
            0,
            0,
            0,
            message
        )
    }
}
