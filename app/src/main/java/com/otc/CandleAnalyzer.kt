
package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

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
        val open: Double,
        val close: Double,
        val high: Double,
        val low: Double,
        val body: Double,
        val upperWick: Double,
        val lowerWick: Double,
        val centerX: Int
    ) {
        val range: Double get() = max(1.0, high - low)
    }

    private data class Swing(val index: Int, val price: Double)

    private data class PatternResult(
        val name: String,
        val score: Int
    )

    private data class Indicators(
        val ema9: Double,
        val ema20: Double,
        val ema50: Double,
        val rsi: Double,
        val macd: Double,
        val signalLine: Double,
        val upperBand: Double,
        val lowerBand: Double,
        val atr: Double
    )

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
        if (bitmap.isRecycled || bitmap.width < 100 ||
            bitmap.height < 100
        ) {
            return result("WAIT", 0, 0, 0, "INVALID CHART")
        }

        val bars = detectCandles(bitmap)

        if (bars.size < 8) {
            val green = bars.count { it.bullish }
            val red = bars.size - green

            return result(
                "WAIT", 0, green, red,
                "WAIT | COMPLETE: ${bars.size} | DETECTED: ${bars.size} | NEED MORE CANDLES"
            )
        }

        // The newest candle is treated as possibly incomplete.
        val complete = bars.dropLast(1)

        if (complete.size < 8) {
            return result(
                "WAIT", 0,
                complete.count { it.bullish },
                complete.count { !it.bullish },
                "WAIT | COMPLETE: ${complete.size} | DETECTED: ${bars.size}"
            )
        }

        val green = complete.count { it.bullish }
        val red = complete.size - green

        val last = complete.last()
        val previous = complete[complete.lastIndex - 1]
        val recent = complete.takeLast(6)

        val highs = findSwings(complete, true)
        val lows = findSwings(complete, false)

        val hh = highs.size >= 2 &&
            highs.last().price > highs[highs.lastIndex - 1].price

        val hl = lows.size >= 2 &&
            lows.last().price > lows[lows.lastIndex - 1].price

        val lh = highs.size >= 2 &&
            highs.last().price < highs[highs.lastIndex - 1].price

        val ll = lows.size >= 2 &&
            lows.last().price < lows[lows.lastIndex - 1].price

        val bullishStructure = hh && hl
        val bearishStructure = lh && ll

        val trendMove = last.close - complete.first().close

        val trend = when {
            bullishStructure -> "BULLISH"
            bearishStructure -> "BEARISH"
            trendMove > 8 -> "POSSIBLE BULLISH"
            trendMove < -8 -> "POSSIBLE BEARISH"
            else -> "SIDEWAYS"
        }

        val resistance = highs.lastOrNull()?.price
        val support = lows.lastOrNull()?.price

        val bullishBOS = resistance != null &&
            last.close > resistance &&
            previous.close <= resistance

        val bearishBOS = support != null &&
            last.close < support &&
            previous.close >= support

        val bullishCHOCH = bearishStructure && bullishBOS
        val bearishCHOCH = bullishStructure && bearishBOS

        val averageRange = complete.takeLast(10)
            .map { it.range }.average().coerceAtLeast(1.0)

        val nearSupport = support != null &&
            abs(last.low - support) <= averageRange * 2.0

        val nearResistance = resistance != null &&
            abs(last.high - resistance) <= averageRange * 2.0

        val bullishSweep = support != null &&
            previous.low >= support &&
            last.low < support &&
            last.close > support

        val bearishSweep = resistance != null &&
            previous.high <= resistance &&
            last.high > resistance &&
            last.close < resistance

        val bullishRejection =
            last.lowerWick >= last.body * 2.0 &&
            last.lowerWick > last.upperWick &&
            nearSupport

        val bearishRejection =
            last.upperWick >= last.body * 2.0 &&
            last.upperWick > last.lowerWick &&
            nearResistance

        val pattern = detectPattern(previous, last, trendMove)
        val chartPattern = detectChartPattern(highs, lows)
        val indicators = calculateIndicators(complete)

        val bullishFvg = complete.size >= 3 &&
            complete.last().low >
            complete[complete.lastIndex - 2].high

        val bearishFvg = complete.size >= 3 &&
            complete.last().high <
            complete[complete.lastIndex - 2].low

        val orderBlock = findOrderBlock(complete, bullishBOS, bearishBOS)

        val equalHighs = highs.size >= 2 &&
            abs(highs.last().price -
                highs[highs.lastIndex - 1].price) <= averageRange * 0.25

        val equalLows = lows.size >= 2 &&
            abs(lows.last().price -
                lows[lows.lastIndex - 1].price) <= averageRange * 0.25

        val momentumGreen = recent.count { it.bullish }
        val momentumRed = recent.size - momentumGreen

        val volatility = when {
            indicators.atr > averageRange * 1.6 -> "HIGH"
            indicators.atr < averageRange * 0.65 -> "LOW"
            else -> "NORMAL"
        }

        var score = 0

        // Market structure
        if (bullishStructure) score += 5
        if (bearishStructure) score -= 5

        if (bullishBOS) score += 6
        if (bearishBOS) score -= 6

        if (bullishCHOCH) score += 4
        if (bearishCHOCH) score -= 4

        // Trend
        if (trend == "BULLISH") score += 3
        if (trend == "BEARISH") score -= 3
        if (trend == "POSSIBLE BULLISH") score += 1
        if (trend == "POSSIBLE BEARISH") score -= 1

        // Liquidity and rejection
        if (bullishSweep) score += 5
        if (bearishSweep) score -= 5

        if (bullishRejection) score += 4
        if (bearishRejection) score -= 4

        if (equalHighs && bearishSweep) score -= 2
        if (equalLows && bullishSweep) score += 2

        // Support and resistance
        if (nearSupport && last.bullish) score += 2
        if (nearResistance && !last.bullish) score -= 2

        // Candlestick patterns
        score += pattern.score

        // Fair Value Gap
        if (bullishFvg) score += 2
        if (bearishFvg) score -= 2

        // EMA trend alignment
        if (indicators.ema9 > indicators.ema20 &&
            indicators.ema20 > indicators.ema50
        ) score += 4

        if (indicators.ema9 < indicators.ema20 &&
            indicators.ema20 < indicators.ema50
        ) score -= 4

        // RSI
        if (indicators.rsi in 52.0..68.0) score += 2
        if (indicators.rsi in 32.0..48.0) score -= 2
        if (indicators.rsi > 75.0) score -= 2
        if (indicators.rsi < 25.0) score += 2

        // MACD
        if (indicators.macd > indicators.signalLine) score += 2
        if (indicators.macd < indicators.signalLine) score -= 2

        // Momentum
        score += momentumGreen - momentumRed

        // Volatility and conflict
        val bullishEvidence =
            bullishBOS || bullishSweep || bullishRejection ||
                pattern.score > 0

        val bearishEvidence =
            bearishBOS || bearishSweep || bearishRejection ||
                pattern.score < 0

        val conflict = bullishEvidence && bearishEvidence

        if (conflict) score = (score * 0.65).roundToInt()

        val confidence = (
            abs(score) * 3 +
                if (abs(score) >= 12) 5 else 0
            ).coerceIn(0, 85)

        val signal = when {
            conflict -> "WAIT"
            volatility == "HIGH" && abs(score) < 12 -> "WAIT"
            score >= 12 && confidence >= 35 -> "UP"
            score <= -12 && confidence >= 35 -> "DOWN"
            else -> "WAIT"
        }

        val structure = when {
            bullishStructure -> "HH + HL"
            bearishStructure -> "LH + LL"
            else -> "MIXED"
        }

        val bos = when {
            bullishBOS -> "BULLISH BOS"
            bearishBOS -> "BEARISH BOS"
            else -> "NO BOS"
        }

        val choch = when {
            bullishCHOCH -> "BULLISH CHOCH"
            bearishCHOCH -> "BEARISH CHOCH"
            else -> "NO CHOCH"
        }

        val sweep = when {
            bullishSweep -> "BULLISH SWEEP"
            bearishSweep -> "BEARISH SWEEP"
            else -> "NO SWEEP"
        }

        val location = when {
            nearSupport -> "SUPPORT"
            nearResistance -> "RESISTANCE"
            else -> "MID ZONE"
        }

        val message = buildString {
            append("NEXT: $signal | SCORE: $confidence%")
            append(" | TREND: $trend")
            append(" | STRUCTURE: $structure")
            append(" | $bos | $choch")
            append(" | $sweep")
            append(" | WICK: ${if (bullishRejection) "BULLISH" else if (bearishRejection) "BEARISH" else "NORMAL"}")
            append(" | PATTERN: ${pattern.name}")
            append(" | CHART: $chartPattern")
            append(" | FVG: ${if (bullishFvg) "BULLISH" else if (bearishFvg) "BEARISH" else "NONE"}")
            append(" | OB: $orderBlock")
            append(" | LEVEL: $location")
            append(" | EMA9/20/50: ${indicators.ema9.roundToInt()}/${indicators.ema20.roundToInt()}/${indicators.ema50.roundToInt()}")
            append(" | RSI: ${indicators.rsi.roundToInt()}")
            append(" | MACD: ${if (indicators.macd > indicators.signalLine) "BULLISH" else "BEARISH"}")
            append(" | BB: ${if (last.close > indicators.upperBand) "UPPER" else if (last.close < indicators.lowerBand) "LOWER" else "INSIDE"}")
            append(" | ATR: ${indicators.atr.roundToInt()}")
            append(" | VOLATILITY: $volatility")
            append(" | MOMENTUM: GREEN $momentumGreen / RED $momentumRed")
            append(" | UPPER WICK: ${last.upperWick.roundToInt()}")
            append(" | LOWER WICK: ${last.lowerWick.roundToInt()}")
            append(" | BODY: ${last.body.roundToInt()}")
            append(" | EQUAL HIGH: $equalHighs")
            append(" | EQUAL LOW: $equalLows")
            append(" | CONFLICT: $conflict")
            append(" | GREEN: $green | RED: $red")
            append(" | COMPLETE: ${complete.size}")
            append(" | DETECTED: ${bars.size}")
            append(" | OTHER TIMEFRAMES: NOT AVAILABLE")
            append(" | VOLUME: NOT AVAILABLE")
        }

        return CandleSignal(signal, confidence, green, red, message)
    }

    private fun detectPattern(a: Bar, b: Bar, trend: Double): PatternResult {
        val body = b.body.coerceAtLeast(1.0)
        val small = b.body <= b.range * 0.12

        if (small) return PatternResult("DOJI", 0)

        if (b.lowerWick >= body * 2 &&
            b.upperWick <= body && trend < 0
        ) return PatternResult("HAMMER", 3)

        if (b.upperWick >= body * 2 &&
            b.lowerWick <= body && trend > 0
        ) return PatternResult("SHOOTING STAR", -3)

        if (b.lowerWick >= body * 2 &&
            b.upperWick <= body
        ) return PatternResult("PIN BAR BULLISH", 2)

        if (b.upperWick >= body * 2 &&
            b.lowerWick <= body
        ) return PatternResult("PIN BAR BEARISH", -2)

        val engulfing = b.high >= a.high &&
            b.low <= a.low &&
            b.body > a.body * 0.9

        if (engulfing && b.bullish && !a.bullish) {
            return PatternResult("BULLISH ENGULFING", 5)
        }

        if (engulfing && !b.bullish && a.bullish) {
            return PatternResult("BEARISH ENGULFING", -5)
        }

        if (b.bullish && a.bullish &&
            b.body > a.body * 1.5
        ) return PatternResult("STRONG BULLISH", 2)

        if (!b.bullish && !a.bullish &&
            b.body > a.body * 1.5
        ) return PatternResult("STRONG BEARISH", -2)

        return PatternResult("NONE", 0)
    }

    private fun findSwings(
        bars: List<Bar>,
        high: Boolean
    ): List<Swing> {
        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val price = if (high) bars[i].high else bars[i].low

            val valid = (1..radius).all { d ->
                if (high) {
                    price > bars[i - d].high &&
                        price > bars[i + d].high
                } else {
                    price < bars[i - d].low &&
                        price < bars[i + d].low
                }
            }

            if (valid) result.add(Swing(i, price))
        }

        return result
    }

    private fun detectChartPattern(
        highs: List<Swing>,
        lows: List<Swing>
    ): String {
        if (highs.size >= 2) {
            val a = highs[highs.lastIndex - 1].price
            val b = highs.last().price
            val tolerance = max(3.0, abs(a) * 0.002)

            if (abs(a - b) <= tolerance) return "DOUBLE TOP"
        }

        if (lows.size >= 2) {
            val a = lows[lows.lastIndex - 1].price
            val b = lows.last().price
            val tolerance = max(3.0, abs(a) * 0.002)

            if (abs(a - b) <= tolerance) return "DOUBLE BOTTOM"
        }

        if (highs.size >= 3 && lows.size >= 3) {
            val h1 = highs[highs.lastIndex - 2].price
            val h2 = highs[highs.lastIndex - 1].price
            val h3 = highs.last().price

            val l1 = lows[lows.lastIndex - 2].price
            val l2 = lows[lows.lastIndex - 1].price
            val l3 = lows.last().price

            if (h2 > h1 && h2 > h3 &&
                h3 < h2 && l3 > l1
            ) return "POSSIBLE HEAD & SHOULDERS"

            if (h2 < h1 && h2 < h3 &&
                l2 < l1 && l3 > l2
            ) return "POSSIBLE TRIANGLE"
        }

        return "NONE"
    }

    private fun findOrderBlock(
        bars: List<Bar>,
        bullishBOS: Boolean,
        bearishBOS: Boolean
    ): String {
        if (!bullishBOS && !bearishBOS) return "NOT CONFIRMED"

        val bullish = bullishBOS

        for (i in bars.lastIndex - 1 downTo max(0, bars.size - 6)) {
            if (bars[i].bullish != bullish) {
                return if (bullish) {
                    "POSSIBLE BULLISH OB"
                } else {
                    "POSSIBLE BEARISH OB"
                }
            }
        }

        return "NOT FOUND"
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0

        val multiplier = 2.0 / (period + 1)
        var value = values.first()

        for (i in 1 until values.size) {
            value = values[i] * multiplier +
                value * (1.0 - multiplier)
        }

        return value
    }

    private fun calculateIndicators(bars: List<Bar>): Indicators {
        val closes = bars.map { it.close }

        val ema9 = ema(closes, 9)
        val ema20 = ema(closes, 20)
        val ema50 = ema(closes, 50)

        var gains = 0.0
        var losses = 0.0

        val period = min(14, closes.size - 1).coerceAtLeast(1)

        for (i in closes.size - period until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) gains += change
            else losses += abs(change)
        }

        val avgGain = gains / period
        val avgLoss = losses / period

        val rsi = when {
            avgLoss == 0.0 && avgGain == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            else -> 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
        }

        val fast = ema(closes, 12)
        val slow = ema(closes, 26)
        val macd = fast - slow

        val macdValues = mutableListOf<Double>()
        for (i in 1..closes.size) {
            macdValues.add(
                ema(closes.take(i), 12) -
                    ema(closes.take(i), 26)
            )
        }

        val signalLine = ema(macdValues, 9)

        val sample = closes.takeLast(min(20, closes.size))
        val mean = sample.average()

        val variance = sample.map {
            (it - mean) * (it - mean)
        }.average()

        val deviation = sqrt(variance.coerceAtLeast(0.0))

        val upperBand = mean + 2 * deviation
        val lowerBand = mean - 2 * deviation

        val ranges = bars.takeLast(min(14, bars.size)).map { it.range }
        val atr = ranges.average()

        return Indicators(
            ema9, ema20, ema50, rsi,
            macd, signalLine, upperBand,
            lowerBand, atr
        )
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {
        val w = bitmap.width
        val h = bitmap.height

        val left = (w * 0.04f).toInt()
        val right = (w * 0.96f).toInt()
        val top = (h * 0.16f).toInt()
        val bottom = (h * 0.84f).toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return emptyList()

        val types = IntArray(width)
        val strengths = IntArray(width)

        for (x in 0 until width) {
            var green = 0
            var red = 0
            var greenRun = 0
            var redRun = 0
            var maxGreen = 0
            var maxRed = 0

            for (y in 0 until height) {
                when (colorType(bitmap.getPixel(left + x, top + y))) {
                    1 -> {
                        green++
                        greenRun++
                        redRun = 0
                        maxGreen = max(maxGreen, greenRun)
                    }
                    2 -> {
                        red++
                        redRun++
                        greenRun = 0
                        maxRed = max(maxRed, redRun)
                    }
                    else -> {
                        greenRun = 0
                        redRun = 0
                    }
                }
            }

            if (green > red && green >= 3 && maxGreen >= 3) {
                types[x] = 1
                strengths[x] = green
            } else if (red > green && red >= 3 && maxRed >= 3) {
                types[x] = 2
                strengths[x] = red
            }
        }

        val result = mutableListOf<Bar>()
        var x = 0

        while (x < width) {
            if (types[x] == 0) {
                x++
                continue
            }

            val type = types[x]
            val start = x
            var end = x

            while (
                end + 1 < width &&
                types[end + 1] == type
            ) {
                end++
            }

            val segments = mutableListOf<Pair<Int, Int>>()
            var segmentStart = start

            while (end - segmentStart + 1 > 20) {
                val middle = (segmentStart + end) / 2
                val searchStart = max(segmentStart + 4, middle - 4)
                val searchEnd = min(end - 4, middle + 4)

                if (searchStart > searchEnd) break

                var split = searchStart
                for (candidate in searchStart..searchEnd) {
                    if (strengths[candidate] < strengths[split]) {
                        split = candidate
                    }
                }

                segments.add(segmentStart to (split - 1))
                segmentStart = split
            }

            segments.add(segmentStart to end)

            for ((s, e) in segments) {
                val candleWidth = e - s + 1
                if (candleWidth !in 2..20) continue

                val rows = IntArray(height)
                var minY = height
                var maxY = -1

                for (cx in s..e) {
                    for (cy in 0 until height) {
                        if (colorType(
                                bitmap.getPixel(left + cx, top + cy)
                            ) == type
                        ) {
                            rows[cy]++
                            minY = min(minY, cy)
                            maxY = max(maxY, cy)
                        }
                    }
                }

                if (maxY < minY) continue

                val peak = rows.maxOrNull() ?: 0
                if (peak < 2) continue

                val peakY = rows.indices.maxByOrNull { rows[it] } ?: continue
                val threshold = max(1, (peak * 0.55).roundToInt())

                var bodyTop = peakY
                var bodyBottom = peakY

                while (bodyTop > minY &&
                    rows[bodyTop - 1] >= threshold
                ) bodyTop--

                while (bodyBottom < maxY &&
                    rows[bodyBottom + 1] >= threshold
                ) bodyBottom++

                val bodySize = bodyBottom - bodyTop + 1
                val totalHeight = maxY - minY + 1

                if (totalHeight < 5 || bodySize < 2) continue

                val bullish = type == 1

                val high = -(minY + top).toDouble()
                val low = -(maxY + top).toDouble()

                val open = if (bullish) {
                    -(bodyBottom + top).toDouble()
                } else {
                    -(bodyTop + top).toDouble()
                }

                val close = if (bullish) {
                    -(bodyTop + top).toDouble()
                } else {
                    -(bodyBottom + top).toDouble()
                }

                result.add(
                    Bar(
                        bullish = bullish,
                        open = open,
                        close = close,
                        high = high,
                        low = low,
                        body = bodySize.toDouble(),
                        upperWick = (bodyTop - minY).toDouble(),
                        lowerWick = (maxY - bodyBottom).toDouble(),
                        centerX = left + (s + e) / 2
                    )
                )
            }

            x = end + 1
        }

        return result.sortedBy { it.centerX }
    }

    private fun result(
        signal: String,
        confidence: Int,
        green: Int,
        red: Int,
        message: String
    ) = CandleSignal(signal, confidence, green, red, message)
}
