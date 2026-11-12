package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max

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

    private fun colorType(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (saturation < 0.25f || value < 0.25f) return 0

        return when {
            hue in 75f..175f -> 1
            hue <= 18f || hue >= 340f -> -1
            else -> 0
        }
    }

    fun analyze(
        bitmap: Bitmap,
        includeLiveCandle: Boolean = false
    ): CandleSignal {

        val detected = detectCandles(bitmap)

        if (detected.isEmpty()) {
            return emptySignal("কোনো ক্যান্ডেল শনাক্ত হয়নি")
        }

        val bars = if (includeLiveCandle) {
            detected
        } else {
            detected.dropLast(1)
        }

        if (bars.isEmpty()) {
            return emptySignal("সম্পূর্ণ ক্যান্ডেল পাওয়া যায়নি")
        }

        val green = bars.count { it.bullish }
        val red = bars.size - green

        if (bars.size < 3) {
            return CandleSignal(
                "WAIT",
                0,
                green,
                red,
                "ক্যান্ডেল পাওয়া গেছে ${bars.size}টি। আরও ক্যান্ডেল প্রয়োজন।"
            )
        }

        val recent = bars.takeLast(5)

        var score = 0.0

        // Overall candle direction
        val greenRatio = green.toDouble() / bars.size
        score += (greenRatio - 0.5) * 30.0

        // Recent momentum
        val recentGreen = recent.count { it.bullish }
        val recentRed = recent.size - recentGreen
        score += (recentGreen - recentRed) * 4.0

        // Candle body strength
        val averageBody = bars.map { it.body.toDouble() }.average()
        val last = bars.last()

        if (averageBody > 0) {
            val bodyStrength = (last.body / averageBody).coerceIn(0.0, 2.0)
            score += if (last.bullish) {
                bodyStrength * 5.0
            } else {
                -bodyStrength * 5.0
            }
        }

        // Wick rejection
        if (last.lowerWick > last.body * 1.5 &&
            last.lowerWick > last.upperWick
        ) {
            score += 8.0
        }

        if (last.upperWick > last.body * 1.5 &&
            last.upperWick > last.lowerWick
        ) {
            score -= 8.0
        }

        // Recent trend
        val firstClose = bars.first().closeY
        val lastClose = bars.last().closeY

        if (lastClose < firstClose) {
            score += 8.0
        } else if (lastClose > firstClose) {
            score -= 8.0
        }

        // Consecutive candle momentum
        var consecutive = 0
        for (i in bars.indices.reversed()) {
            if (bars[i].bullish == last.bullish) {
                consecutive++
            } else {
                break
            }
        }

        score += if (last.bullish) {
            consecutive.coerceAtMost(4) * 2.0
        } else {
            -consecutive.coerceAtMost(4) * 2.0
        }

        // Avoid strong signals when recent candles conflict
        val recentConflict = recentGreen >= 2 && recentRed >= 2

        val confidence = (
            abs(score) * 1.8
        ).toInt().coerceIn(0, 85)

        val signal = when {
            confidence < 25 -> "WAIT"
            recentConflict && confidence < 45 -> "WAIT"
            score >= 14 -> "UP"
            score <= -14 -> "DOWN"
            else -> "WAIT"
        }

        val message = when (signal) {
            "UP" -> "ঊর্ধ্বমুখী চাপ দেখা যাচ্ছে। পরবর্তী ক্যান্ডেল UP হতে পারে।"
            "DOWN" -> "নিম্নমুখী চাপ দেখা যাচ্ছে। পরবর্তী ক্যান্ডেল DOWN হতে পারে।"
            else -> "দিক পরিষ্কার নয়। অপেক্ষা করুন।"
        }

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            "$message | মোট বিশ্লেষণ: ${bars.size}টি ক্যান্ডেল"
        )
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

    private fun detectCandles(bitmap: Bitmap): List<Bar> {

        val width = bitmap.width
        val height = bitmap.height

        if (width < 100 || height < 100) return emptyList()

        val startX = (width * 0.00f).toInt()
        val endX = (width * 0.57f).toInt()

        val startY = (height * 0.12f).toInt()
        val endY = (height * 0.69f).toInt()

        val columnTypes = IntArray(endX - startX)

        for (x in startX until endX) {
            var greenPixels = 0
            var redPixels = 0

            for (y in startY until endY step 2) {
                val type = colorType(bitmap.getPixel(x, y))

                if (type == 1) greenPixels++
                if (type == -1) redPixels++
            }

            columnTypes[x - startX] = when {
                greenPixels > redPixels && greenPixels >= 2 -> 1
                redPixels > greenPixels && redPixels >= 2 -> -1
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

            if (candleWidth in 2..25) {

                var top = endY
                var bottom = startY
                var bodyTop = endY
                var bodyBottom = startY

                for (cx in candleStart..candleEnd) {
                    val actualX = startX + cx

                    for (cy in startY until endY) {
                        val pixelType = colorType(
                            bitmap.getPixel(actualX, cy)
                        )

                        if (pixelType == type) {
                            top = minOf(top, cy)
                            bottom = maxOf(bottom, cy)
                        }
                    }
                }

                if (bottom > top) {

                    val centerX = startX +
                        (candleStart + candleEnd) / 2

                    val centerColumn = startX + candleStart

                    var bodyFound = false

                    for (cy in top..bottom) {
                        if (colorType(
                                bitmap.getPixel(centerColumn, cy)
                            ) == type
                        ) {
                            bodyTop = minOf(bodyTop, cy)
                            bodyBottom = maxOf(bodyBottom, cy)
                            bodyFound = true
                        }
                    }

                    if (bodyFound) {

                        val body = max(1, bodyBottom - bodyTop + 1)

                        val upperWick = max(0, bodyTop - top)
                        val lowerWick = max(0, bottom - bodyBottom)

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
                                centerX = centerX,
                                closeY = closeY,
                                topY = top,
                                bottomY = bottom
                            )
                        )
                    }
                }
            }

            x = candleEnd + 1
        }

        return bars.sortedBy { it.centerX }
    }
}