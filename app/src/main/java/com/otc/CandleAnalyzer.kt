
package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
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
        val body: Float,
        val upperWick: Float,
        val lowerWick: Float,
        val centerX: Float,
        val closeY: Float,
        val topY: Float,
        val bottomY: Float
    )

    private data class Column(
        val x: Int,
        val type: Int,
        val pixels: List<Int>
    )

    fun analyze(bitmap: Bitmap): CandleSignal {

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return waitResult("Invalid screen image")
        }

        val detectedBars = detectCandles(bitmap)

        if (detectedBars.size < 2) {
            return waitResult(
                "Not enough candles detected: ${detectedBars.size}"
            )
        }

        // The rightmost detected candle is treated as incomplete.
        // All other detected candles are included; no last-30 limit.
        val bars = detectedBars.dropLast(1)

        val greenCount = bars.count { it.bullish }
        val redCount = bars.size - greenCount

        if (bars.size < 5) {
            return CandleSignal(
                "WAIT",
                30,
                greenCount,
                redCount,
                "Only ${bars.size} completed candles. More data needed."
            )
        }

        var score = 0f
        var maximumScore = 0f

        // Analyze every completed candle.
        for (i in bars.indices) {

            val bar = bars[i]

            // Recent candles receive more weight, but older candles
            // are still included in the calculation.
            val recencyWeight =
                0.5f + 0.5f * (i + 1).toFloat() / bars.size

            val candleRange =
                max(1f, bar.body + bar.upperWick + bar.lowerWick)

            val bodyStrength =
                (bar.body / candleRange).coerceIn(0f, 1f)

            val direction = if (bar.bullish) 1f else -1f

            score += direction * bodyStrength * recencyWeight
            maximumScore += recencyWeight

            // Lower wick rejection can support upward movement.
            val lowerRejection =
                (bar.lowerWick / candleRange).coerceIn(0f, 1f)

            // Upper wick rejection can support downward movement.
            val upperRejection =
                (bar.upperWick / candleRange).coerceIn(0f, 1f)

            score += lowerRejection * 0.35f * recencyWeight
            score -= upperRejection * 0.35f * recencyWeight

            maximumScore += 0.35f * recencyWeight
        }

        // Compare early and recent candle positions.
        val firstClose = bars.first().closeY
        val lastClose = bars.last().closeY

        val priceMovement = firstClose - lastClose

        val chartHeight = max(
            1f,
            bars.maxOf { it.bottomY } - bars.minOf { it.topY }
        )

        val normalizedMovement =
            (priceMovement / chartHeight).coerceIn(-1f, 1f)

        score += normalizedMovement * 2f
        maximumScore += 2f

        // Recent momentum.
        val recentBars = bars.takeLast(min(3, bars.size))

        val recentBullish = recentBars.count { it.bullish }
        val recentBearish = recentBars.size - recentBullish

        val momentum =
            (recentBullish - recentBearish).toFloat() /
                recentBars.size

        score += momentum * 1.5f
        maximumScore += 1.5f

        val ratio = (score / max(1f, maximumScore))
            .coerceIn(-1f, 1f)

        val strength = abs(ratio)

        val confidence = (
            50f + strength * 45f
        ).toInt().coerceIn(30, 95)

        val signal = when {
            ratio >= 0.18f -> "UP"
            ratio <= -0.18f -> "DOWN"
            else -> "WAIT"
        }

        val trend = when {
            normalizedMovement > 0.04f -> "UPTREND"
            normalizedMovement < -0.04f -> "DOWNTREND"
            else -> "SIDEWAYS"
        }

        val message = buildString {
            append("Visible candles: ${detectedBars.size}")
            append(" | Analyzed: ${bars.size}")
            append(" | Green: $greenCount")
            append(" | Red: $redCount")
            append(" | Trend: $trend")
            append(" | Momentum: ")
            append(
                when {
                    momentum > 0.2f -> "BULLISH"
                    momentum < -0.2f -> "BEARISH"
                    else -> "NEUTRAL"
                }
            )
        }

        return CandleSignal(
            signal = signal,
            confidence = confidence,
            greenCount = greenCount,
            redCount = redCount,
            message = message
        )
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {

        val width = bitmap.width
        val height = bitmap.height

        // Chart scanning area.
        // Right-side price scale and extreme screen edges are excluded.
        val left = (width * 0.00f).toInt()
        val right = (width * 0.90f).toInt()

        val top = (height * 0.12f).toInt()
        val bottom = (height * 0.69f).toInt()

        val columns = mutableListOf<Column>()

        for (x in left until right) {

            val greenPixels = mutableListOf<Int>()
            val redPixels = mutableListOf<Int>()

            for (y in top until bottom) {

                val pixel = bitmap.getPixel(x, y)
                val type = colorType(pixel)

                if (type == 1) {
                    greenPixels.add(y)
                } else if (type == -1) {
                    redPixels.add(y)
                }
            }

            val selectedType = when {
                greenPixels.size >= redPixels.size &&
                    greenPixels.size >= 2 -> 1

                redPixels.size > greenPixels.size &&
                    redPixels.size >= 2 -> -1

                else -> 0
            }

            if (selectedType != 0) {
                columns.add(
                    Column(
                        x = x,
                        type = selectedType,
                        pixels = if (selectedType == 1) {
                            greenPixels
                        } else {
                            redPixels
                        }
                    )
                )
            }
        }

        if (columns.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Column>>()
        var currentGroup = mutableListOf<Column>()

        for (column in columns) {

            if (currentGroup.isEmpty()) {
                currentGroup.add(column)
                continue
            }

            val previous = currentGroup.last()

            val sameCandle =
                column.type == previous.type &&
                    column.x - previous.x <= 3

            if (sameCandle) {
                currentGroup.add(column)
            } else {
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup)
                }

                currentGroup = mutableListOf(column)
            }
        }

        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
        }

        val bars = mutableListOf<Bar>()

        for (group in groups) {

            val candleWidth =
                group.last().x - group.first().x + 1

            if (candleWidth > 25 || candleWidth < 1) {
                continue
            }

            val allPixels = group.flatMap { it.pixels }

            if (allPixels.size < 5) continue

            val overallTop = allPixels.minOrNull() ?: continue
            val overallBottom = allPixels.maxOrNull() ?: continue

            if (overallBottom <= overallTop) continue

            // Find the dense body rows.
            val rowCounts = mutableMapOf<Int, Int>()

            for (column in group) {
                for (y in column.pixels) {
                    rowCounts[y] = (rowCounts[y] ?: 0) + 1
                }
            }

            val bodyThreshold = max(
                2,
                ceil(candleWidth * 0.35f).toInt()
            )

            val bodyRows = rowCounts
                .filter { it.value >= bodyThreshold }
                .keys
                .sorted()

            val bodyTop: Int
            val bodyBottom: Int

            if (bodyRows.isNotEmpty()) {

                bodyTop = bodyRows.first()
                bodyBottom = bodyRows.last()

            } else {

                bodyTop = overallTop
                bodyBottom = overallBottom
            }

            val bodyHeight = max(
                1,
                bodyBottom - bodyTop
            )

            val upperWick = max(
                0,
                bodyTop - overallTop
            )

            val lowerWick = max(
                0,
                overallBottom - bodyBottom
            )

            val bullish = group.first().type == 1

            val closeY = if (bullish) {
                bodyTop.toFloat()
            } else {
                bodyBottom.toFloat()
            }

            val centerX = (
                group.first().x + group.last().x
            ) / 2f

            bars.add(
                Bar(
                    bullish = bullish,
                    body = bodyHeight.toFloat(),
                    upperWick = upperWick.toFloat(),
                    lowerWick = lowerWick.toFloat(),
                    centerX = centerX,
                    closeY = closeY,
                    topY = overallTop.toFloat(),
                    bottomY = overallBottom.toFloat()
                )
            )
        }

        return bars.sortedBy { it.centerX }
    }

    private fun colorType(pixel: Int): Int {

        val hsv = FloatArray(3)

        Color.colorToHSV(pixel, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val brightness = hsv[2]

        if (saturation < 0.35f || brightness < 0.25f) {
            return 0
        }

        return when {
            hue in 75f..175f -> 1
            hue <= 20f || hue >= 340f -> -1
            else -> 0
        }
    }

    private fun waitResult(message: String): CandleSignal {
        return CandleSignal(
            signal = "WAIT",
            confidence = 0,
            greenCount = 0,
            redCount = 0,
            message = message
        )
    }
}
