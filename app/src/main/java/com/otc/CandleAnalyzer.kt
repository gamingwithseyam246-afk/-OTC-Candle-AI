



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
        val closeY: Int
    )

    private fun colorType(c: Int): Int {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)

        // Red bearish candle
        if (r > 100 && r > g * 1.25 &&
            r > b * 1.15) return 2

        // Green bullish candle
        if (g > 90 && g > r * 1.20 &&
            g > b * 1.08) return 1

        // White bullish candle on dark chart
        if (r > 180 && g > 180 && b > 180) return 1

        return 0
    }

    fun analyze(bitmap: Bitmap): CandleSignal {
        val bars = detectCandles(bitmap).takeLast(10)

        val green = bars.count { it.bullish }
        val red = bars.size - green

        if (bars.size < 5) {
            return CandleSignal(
                "WAIT", 0, green, red,
                "ক্যান্ডেল পাওয়া গেছে: ${bars.size}টি"
            )
        }

        var score = 0

        bars.forEachIndexed { index, bar ->
            val weight = index + 1

            // Candle body direction
            score += if (bar.bullish) weight else -weight

            // Wick rejection analysis
            val wickTotal = bar.upperWick + bar.lowerWick
            if (wickTotal > 0) {
                val wickScore =
                    (abs(bar.lowerWick - bar.upperWick) *
                        weight / (wickTotal + bar.body + 1))
                        .coerceAtMost(3)

                if (bar.lowerWick > bar.upperWick) {
                    score += wickScore
                } else if (bar.upperWick > bar.lowerWick) {
                    score -= wickScore
                }
            }
        }

        // Screen trend: smaller Y is higher on screen
        val trend = bars.first().closeY - bars.last().closeY

        if (trend > 3) score += 5
        if (trend < -3) score -= 5

        val confidence =
            (abs(score) * 100 / 65).coerceIn(0, 100)

        val signal = when {
            confidence < 20 -> "WAIT"
            score > 0 -> "UP"
            else -> "DOWN"
        }

        val message = when (signal) {
            "UP" -> "ঊর্ধ্বমুখী Body ও Wick ধারা"
            "DOWN" -> "নিম্নমুখী Body ও Wick ধারা"
            else -> "স্পষ্ট দিক নেই - অপেক্ষা করুন"
        }

        return CandleSignal(
            signal, confidence, green, red, message
        )
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {
        val left = (bitmap.width * 0.01).toInt()
        val right = (bitmap.width * 0.60).toInt()
        val top = (bitmap.height * 0.13).toInt()
        val bottom = (bitmap.height * 0.72).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return emptyList()

        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        val result = mutableListOf<Bar>()

        fun typeAt(x: Int, y: Int): Int {
            return colorType(bitmap.getPixel(x + left, y + top))
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val start = y * width + x

                if (visited[start]) continue

                val type = typeAt(x, y)
                if (type == 0) continue

                var head = 0
                var tail = 0

                queue[tail++] = start
                visited[start] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var pixels = 0

                val rowCounts = IntArray(height)

                while (head < tail) {
                    val current = queue[head++]
                    val cx = current % width
                    val cy = current / width

                    pixels++
                    minX = minOf(minX, cx)
                    maxX = maxOf(maxX, cx)
                    minY = minOf(minY, cy)
                    maxY = maxOf(maxY, cy)
                    rowCounts[cy]++

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue

                            val nx = cx + dx
                            val ny = cy + dy

                            if (nx !in 0 until width ||
                                ny !in 0 until height) continue

                            val next = ny * width + nx

                            if (!visited[next] &&
                                typeAt(nx, ny) == type) {
                                visited[next] = true
                                queue[tail++] = next
                            }
                        }
                    }
                }

                val candleWidth = maxX - minX + 1
                val candleHeight = maxY - minY + 1

                if (pixels < 8 ||
                    candleWidth > 25 ||
                    candleHeight < 6) continue

                val threshold = maxOf(2, candleWidth / 3)
                var bodyTop = -1
                var bodyBottom = -1

                for (row in minY..maxY) {
                    if (rowCounts[row] >= threshold) {
                        if (bodyTop == -1) bodyTop = row
                        bodyBottom = row
                    }
                }

                if (bodyTop == -1 ||
                    bodyBottom <= bodyTop) continue

                val body = bodyBottom - bodyTop + 1
                val upperWick = bodyTop - minY
                val lowerWick = maxY - bodyBottom

                result.add(
                    Bar(
                        bullish = type == 1,
                        body = body,
                        upperWick = upperWick,
                        lowerWick = lowerWick,
                        centerX = (minX + maxX) / 2,
                        closeY = if (type == 1) {
                            bodyTop
                        } else {
                            bodyBottom
                        }
                    )
                )
            }
        }

        // Oldest candle to newest candle
        return result.sortedBy { it.centerX }
    }
}
