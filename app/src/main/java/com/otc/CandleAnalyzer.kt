
package com.otc.candleai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
        val bodyRatio: Double get() = body / range
    }

    private data class Swing(val index: Int, val price: Double)
    private data class Pattern(val name: String, val score: Int)
    private data class CandleColumn(val type: Int, val left: Int, val right: Int)

    fun analyze(bitmap: Bitmap): CandleSignal {
        if (bitmap.isRecycled || bitmap.width < 100 || bitmap.height < 100) {
            return result("WAIT", 0, 0, 0, "WAIT | INVALID SCREEN")
        }

        val bars = detectCandles(bitmap)

        if (bars.size < 12) {
            val green = bars.count { it.bullish }
            return result(
                "WAIT", 0, green, bars.size - green,
                "WAIT | DETECTED: ${bars.size} | NEED MORE CANDLES"
            )
        }

        // Rightmost detected candle is provisionally treated as live.
        val complete = bars.dropLast(1)
        if (complete.size < 11) {
            val green = complete.count { it.bullish }
            return result(
                "WAIT", 0, green, complete.size - green,
                "WAIT | COMPLETE: ${complete.size}"
            )
        }

        val green = complete.count { it.bullish }
        val red = complete.size - green
        val last = complete.last()
        val previous = complete[complete.lastIndex - 1]

        val recent = complete.takeLast(8)
        val averageRange = complete.takeLast(14)
            .map { it.range }.average().coerceAtLeast(1.0)

        val highs = findSwings(complete, true)
        val lows = findSwings(complete, false)

        val hh = highs.size >= 2 &&
            highs.last().price > highs[highs.lastIndex - 1].price
        val lh = highs.size >= 2 &&
            highs.last().price < highs[highs.lastIndex - 1].price
        val hl = lows.size >= 2 &&
            lows.last().price > lows[lows.lastIndex - 1].price
        val ll = lows.size >= 2 &&
            lows.last().price < lows[lows.lastIndex - 1].price

        val bullishStructure = hh && hl
        val bearishStructure = lh && ll

        val trendMove = recent.last().close - recent.first().close
        val upCloses = recent.zipWithNext().count { it.second.close > it.first.close }
        val downCloses = recent.zipWithNext().count { it.second.close < it.first.close }

        val trend = when {
            bullishStructure -> "UPTREND"
            bearishStructure -> "DOWNTREND"
            trendMove > averageRange * 1.0 && upCloses >= 5 -> "POSSIBLE UPTREND"
            trendMove < -averageRange * 1.0 && downCloses >= 5 -> "POSSIBLE DOWNTREND"
            else -> "SIDEWAYS"
        }

        val support = lows.lastOrNull()?.price
        val resistance = highs.lastOrNull()?.price

        val bullishBOS = resistance != null &&
            last.close > resistance && previous.close <= resistance
        val bearishBOS = support != null &&
            last.close < support && previous.close >= support

        val bullishSweep = support != null &&
            last.low < support && last.close > support
        val bearishSweep = resistance != null &&
            last.high > resistance && last.close < resistance

        val nearSupport = support != null &&
            abs(last.low - support) <= averageRange * 1.0
        val nearResistance = resistance != null &&
            abs(last.high - resistance) <= averageRange * 1.0

        val bullishWick = last.lowerWick >= last.body * 1.7 &&
            last.lowerWick > last.upperWick * 1.2 && nearSupport

        val bearishWick = last.upperWick >= last.body * 1.7 &&
            last.upperWick > last.lowerWick * 1.2 && nearResistance

        val pattern = detectPattern(previous, last)

        // Momentum uses candle direction AND closing-price movement.
        val greenMomentum = recent.count { it.bullish }
        val redMomentum = recent.size - greenMomentum
        val bullishMomentum = greenMomentum >= 5 && upCloses >= 4 &&
            recent.last().close > recent.first().close
        val bearishMomentum = redMomentum >= 5 && downCloses >= 4 &&
            recent.last().close < recent.first().close

        val closes = complete.map { it.close }
        val ema9 = ema(closes, 9)
        val ema20 = ema(closes, 20)
        val ema50 = if (complete.size >= 50) ema(closes, 50) else 0.0
        val rsi = calculateRsi(complete)

        val emaBullish = complete.size >= 20 && ema9 > ema20
        val emaBearish = complete.size >= 20 && ema9 < ema20
        val longEmaBullish = complete.size >= 50 && ema20 > ema50
        val longEmaBearish = complete.size >= 50 && ema20 < ema50

        val bullishFvg = complete.size >= 3 &&
            complete.last().low > complete[complete.lastIndex - 2].high
        val bearishFvg = complete.size >= 3 &&
            complete.last().high < complete[complete.lastIndex - 2].low

        // Confirmation groups avoid counting correlated evidence twice.
        val bullishTrendGroup = bullishStructure ||
            trend == "UPTREND" || trend == "POSSIBLE UPTREND"
        val bearishTrendGroup = bearishStructure ||
            trend == "DOWNTREND" || trend == "POSSIBLE DOWNTREND"

        val bullishTrigger = bullishBOS || bullishSweep ||
            bullishWick || pattern.score >= 3
        val bearishTrigger = bearishBOS || bearishSweep ||
            bearishWick || pattern.score <= -3

        val bullishMomentumGroup = bullishMomentum
        val bearishMomentumGroup = bearishMomentum

        val bullishEmaGroup = emaBullish &&
            (!longEmaBearish || longEmaBullish)
        val bearishEmaGroup = emaBearish &&
            (!longEmaBullish || longEmaBearish)

        val bullishRsi = rsi in 50.0..68.0
        val bearishRsi = rsi in 32.0..50.0

        val bullishGroups = listOf(
            bullishTrendGroup,
            bullishTrigger,
            bullishMomentumGroup,
            bullishEmaGroup,
            bullishRsi
        ).count { it }

        val bearishGroups = listOf(
            bearishTrendGroup,
            bearishTrigger,
            bearishMomentumGroup,
            bearishEmaGroup,
            bearishRsi
        ).count { it }

        val strongBullishConflict =
            bearishBOS || bearishSweep || bearishWick ||
                pattern.score <= -3 || bearishMomentum

        val strongBearishConflict =
            bullishBOS || bullishSweep || bullishWick ||
                pattern.score >= 3 || bullishMomentum

        val conflict = (bullishGroups >= 2 && strongBullishConflict) ||
            (bearishGroups >= 2 && strongBearishConflict) ||
            (bullishGroups >= 2 && bearishGroups >= 2)

        // Technical score is a strength indicator, not a win probability.
        var score = 0
        if (bullishTrendGroup) score += 2
        if (bearishTrendGroup) score -= 2
        if (bullishBOS) score += 5
        if (bearishBOS) score -= 5
        if (bullishSweep) score += 4
        if (bearishSweep) score -= 4
        if (bullishWick) score += 2
        if (bearishWick) score -= 2
        if (bullishMomentum) score += 4
        if (bearishMomentum) score -= 4
        if (emaBullish) score += 2
        if (emaBearish) score -= 2
        if (longEmaBullish) score += 1
        if (longEmaBearish) score -= 1
        if (bullishRsi) score += 1
        if (bearishRsi) score -= 1
        if (bullishFvg) score += 1
        if (bearishFvg) score -= 1
        score += pattern.score

        // Avoid entries after an unusually large candle.
        val oversizedCandle = last.range > averageRange * 2.0
        val weakBody = last.bodyRatio < 0.20

        val signal = when {
            conflict -> "WAIT"
            oversizedCandle || weakBody -> "WAIT"

            score >= 10 &&
                bullishGroups >= 3 &&
                bullishGroups > bearishGroups &&
                bullishTrigger &&
                bullishMomentumGroup &&
                !strongBullishConflict -> "UP"

            score <= -10 &&
                bearishGroups >= 3 &&
                bearishGroups > bullishGroups &&
                bearishTrigger &&
                bearishMomentumGroup &&
                !strongBearishConflict -> "DOWN"

            else -> "WAIT"
        }

        val confidence = when (signal) {
            "UP", "DOWN" -> (abs(score) * 5 + bullishGroups.coerceAtLeast(bearishGroups) * 3)
                .coerceIn(30, 85)
            else -> 0
        }

        val structure = when {
            bullishStructure -> "HH + HL"
            bearishStructure -> "LH + LL"
            else -> "MIXED"
        }

        val bos = when {
            bullishBOS -> "BULLISH BOS"
            bearishBOS -> "BEARISH BOS"
            else -> "NO FRESH BOS"
        }

        val sweep = when {
            bullishSweep -> "BULLISH SWEEP"
            bearishSweep -> "BEARISH SWEEP"
            else -> "NO SWEEP"
        }

        val wick = when {
            bullishWick -> "BULLISH REJECTION"
            bearishWick -> "BEARISH REJECTION"
            else -> "NO STRONG REJECTION"
        }

        val emaDirection = when {
            complete.size < 20 -> "NOT READY"
            emaBullish -> "BULLISH"
            emaBearish -> "BEARISH"
            else -> "NEUTRAL"
        }

        val live = bars.last()
        val liveDirection = if (live.bullish) "UP FORMING" else "DOWN FORMING"

        val message = buildString {
            append("NEXT: $signal | SCORE: $confidence")
            append(" | CURRENT: $liveDirection")
            append(" | TREND: $trend")
            append(" | STRUCTURE: $structure")
            append(" | $bos | $sweep | $wick")
            append(" | PATTERN: ${pattern.name}")
            append(" | RSI: ${rsi.roundToInt()}")
            append(" | EMA: $emaDirection")
            append(" | GREEN: $green | RED: $red")
            append(" | COMPLETE: ${complete.size}")
            append(" | DETECTED: ${bars.size}")
            append(" | MOMENTUM: $greenMomentum/$redMomentum")
            append(" | GROUPS: $bullishGroups/$bearishGroups")
            append(" | CONFLICT: $conflict")
            if (oversizedCandle) append(" | HIGH VOLATILITY")
            if (weakBody) append(" | WEAK BODY")
        }

        return result(signal, confidence, green, red, message)
    }

    private fun colorType(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (value < 0.25f || saturation < 0.28f) return 0

        return when {
            hue in 65f..175f -> 1
            hue <= 18f || hue >= 342f -> 2
            else -> 0
        }
    }

    private fun detectCandles(bitmap: Bitmap): List<Bar> {
        val screenW = bitmap.width
        val screenH = bitmap.height

        val left = (screenW * 0.01f).toInt()
        val right = (screenW * 0.88f).toInt()
        val top = (screenH * 0.10f).toInt()
        val bottom = (screenH * 0.70f).toInt()

        val w = right - left
        val h = bottom - top
        if (w < 20 || h < 20) return emptyList()

        val types = ByteArray(w * h)
        val columnCounts = Array(3) { IntArray(w) }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val type = colorType(bitmap.getPixel(left + x, top + y))
                types[y * w + x] = type.toByte()
                if (type != 0) columnCounts[type][x]++
            }
        }

        val result = mutableListOf<Bar>()

        for (type in 1..2) {
            val active = BooleanArray(w) { x ->
                columnCounts[type][x] >= 2
            }

            val groups = mutableListOf<CandleColumn>()
            var x = 0

            while (x < w) {
                if (!active[x]) {
                    x++
                    continue
                }

                val start = x
                var end = x
                while (end + 1 < w && active[end + 1]) end++

                var groupLeft = start
                var groupRight = end

                if (groupLeft > 0 && columnCounts[type][groupLeft - 1] == 1) {
                    groupLeft--
                }
                if (groupRight + 1 < w &&
                    columnCounts[type][groupRight + 1] == 1
                ) {
                    groupRight++
                }

                if (groupRight - groupLeft + 1 in 1..22) {
                    groups.add(CandleColumn(type, groupLeft, groupRight))
                }

                x = end + 1
            }

            for (group in groups) {
                val pixelRows = IntArray(h)
                var totalPixels = 0
                var minY = h
                var maxY = -1

                for (gx in group.left..group.right) {
                    for (gy in 0 until h) {
                        if (types[gy * w + gx].toInt() == type) {
                            pixelRows[gy]++
                            totalPixels++
                            minY = min(minY, gy)
                            maxY = max(maxY, gy)
                        }
                    }
                }

                if (maxY < 0 || totalPixels < 4) continue
                if (maxY - minY + 1 < 4) continue

                val rows = pixelRows.copyOfRange(minY, maxY + 1)
                val peak = rows.maxOrNull() ?: 0
                if (peak < 1) continue

                val peakY = rows.indices.maxByOrNull { rows[it] } ?: continue
                val threshold = max(1, (peak * 0.50).roundToInt())

                var bodyTop = peakY
                var bodyBottom = peakY

                while (bodyTop > 0 && rows[bodyTop - 1] >= threshold) bodyTop--
                while (bodyBottom < rows.lastIndex &&
                    rows[bodyBottom + 1] >= threshold
                ) bodyBottom++

                if (bodyBottom - bodyTop + 1 < 2) continue

                val bullish = type == 1
                val absoluteBodyTop = minY + bodyTop + top
                val absoluteBodyBottom = minY + bodyBottom + top
                val absoluteHigh = minY + top
                val absoluteLow = maxY + top

                val open: Double
                val close: Double

                if (bullish) {
                    open = -absoluteBodyBottom.toDouble()
                    close = -absoluteBodyTop.toDouble()
                } else {
                    open = -absoluteBodyTop.toDouble()
                    close = -absoluteBodyBottom.toDouble()
                }

                result.add(
                    Bar(
                        bullish = bullish,
                        open = open,
                        close = close,
                        high = -absoluteHigh.toDouble(),
                        low = -absoluteLow.toDouble(),
                        body = (bodyBottom - bodyTop + 1).toDouble(),
                        upperWick = max(0, absoluteBodyTop - absoluteHigh).toDouble(),
                        lowerWick = max(0, absoluteLow - absoluteBodyBottom).toDouble(),
                        centerX = left + (group.left + group.right) / 2
                    )
                )
            }
        }

        val sorted = result.sortedBy { it.centerX }
        val unique = mutableListOf<Bar>()

        for (bar in sorted) {
            val previous = unique.lastOrNull()
            if (previous != null &&
                abs(previous.centerX - bar.centerX) <= 1 &&
                previous.bullish == bar.bullish
            ) {
                if (bar.range > previous.range) {
                    unique[unique.lastIndex] = bar
                }
            } else {
                unique.add(bar)
            }
        }

        return unique
    }

    private fun detectPattern(a: Bar, b: Bar): Pattern {
        val body = b.body.coerceAtLeast(1.0)

        if (b.body <= b.range * 0.12) return Pattern("DOJI", 0)

        if (b.lowerWick >= body * 2 && b.upperWick <= body) {
            return Pattern("HAMMER", 3)
        }

        if (b.upperWick >= body * 2 && b.lowerWick <= body) {
            return Pattern("SHOOTING STAR", -3)
        }

        if (b.bullish && !a.bullish &&
            b.open <= a.close && b.close >= a.open &&
            b.body > a.body * 0.9
        ) return Pattern("BULLISH ENGULFING", 5)

        if (!b.bullish && a.bullish &&
            b.open >= a.close && b.close <= a.open &&
            b.body > a.body * 0.9
        ) return Pattern("BEARISH ENGULFING", -5)

        if (b.bullish && a.bullish && b.body > a.body * 1.5) {
            return Pattern("STRONG BULLISH", 2)
        }

        if (!b.bullish && !a.bullish && b.body > a.body * 1.5) {
            return Pattern("STRONG BEARISH", -2)
        }

        return Pattern("NONE", 0)
    }

    private fun findSwings(bars: List<Bar>, high: Boolean): List<Swing> {
        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {
            val price = if (high) bars[i].high else bars[i].low

            val valid = (1..radius).all { d ->
                if (high) {
                    price > bars[i - d].high && price > bars[i + d].high
                } else {
                    price < bars[i - d].low && price < bars[i + d].low
                }
            }

            if (valid) result.add(Swing(i, price))
        }

        return result
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val multiplier = 2.0 / (period + 1)
        var value = values.first()

        for (i in 1 until values.size) {
            value = values[i] * multiplier + value * (1.0 - multiplier)
        }
        return value
    }

    private fun calculateRsi(bars: List<Bar>): Double {
        val closes = bars.map { it.close }
        val period = min(14, closes.size - 1).coerceAtLeast(1)

        var gains = 0.0
        var losses = 0.0

        for (i in closes.size - period until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) gains += change else losses += abs(change)
        }

        val averageGain = gains / period
        val averageLoss = losses / period

        return when {
            averageGain == 0.0 && averageLoss == 0.0 -> 50.0
            averageLoss == 0.0 -> 100.0
            else -> 100.0 - (100.0 / (1.0 + averageGain / averageLoss))
        }
    }

    private fun result(
        signal: String,
        confidence: Int,
        green: Int,
        red: Int,
        message: String
    ) = CandleSignal(signal, confidence, green, red, message)
}
