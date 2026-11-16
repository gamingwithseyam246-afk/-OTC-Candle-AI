
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
        val range: Double
            get() = max(1.0, high - low)

        val bodyRatio: Double
            get() = body / range
    }

    private data class Swing(val index: Int, val price: Double)

    private data class CandleBody(
        val type: Int,
        val left: Int,
        val right: Int,
        val center: Int
    )

    private data class Pattern(
        val name: String,
        val direction: Int
    )

    fun analyze(bitmap: Bitmap): CandleSignal {

        if (
            bitmap.isRecycled ||
            bitmap.width < 100 ||
            bitmap.height < 100
        ) {
            return result(
                "WAIT", 0, 0, 0,
                "WAIT | INVALID SCREEN"
            )
        }

        val bars = detectCandles(bitmap)

        if (bars.size < 12) {
            val green = bars.count { it.bullish }
            val red = bars.size - green

            return result(
                "WAIT", 0, green, red,
                "WAIT | CANDLES: ${bars.size} | NEED MORE"
            )
        }

        // Rightmost candle is treated as the live candle.
        val complete = bars.dropLast(1)

        if (complete.size < 10) {
            val green = complete.count { it.bullish }

            return result(
                "WAIT", 0, green, complete.size - green,
                "WAIT | COMPLETE CANDLES: ${complete.size}"
            )
        }

        val green = complete.count { it.bullish }
        val red = complete.size - green

        val last = complete.last()
        val previous = complete[complete.lastIndex - 1]

        val recent = complete.takeLast(8)
        val lastSix = complete.takeLast(6)

        val averageRange = complete.takeLast(14)
            .map { it.range }
            .average()
            .coerceAtLeast(1.0)

        val averageBody = lastSix.map { it.body }
            .average()
            .coerceAtLeast(1.0)

        // Price rises upward on the screen because price values
        // are represented by negative Y coordinates.
        val priceMove = recent.last().close - recent.first().close

        val risingCloses = recent.zipWithNext()
            .count { it.second.close > it.first.close }

        val fallingCloses = recent.zipWithNext()
            .count { it.second.close < it.first.close }

        val greenMomentum = lastSix.count { it.bullish }
        val redMomentum = lastSix.size - greenMomentum

        val highs = findSwings(complete, true)
        val lows = findSwings(complete, false)

        val higherHighs = highs.size >= 2 &&
            highs.last().price > highs[highs.lastIndex - 1].price

        val lowerHighs = highs.size >= 2 &&
            highs.last().price < highs[highs.lastIndex - 1].price

        val higherLows = lows.size >= 2 &&
            lows.last().price > lows[lows.lastIndex - 1].price

        val lowerLows = lows.size >= 2 &&
            lows.last().price < lows[lows.lastIndex - 1].price

        val bullishStructure = higherHighs && higherLows
        val bearishStructure = lowerHighs && lowerLows

        val trend = when {
            bullishStructure -> "UPTREND"
            bearishStructure -> "DOWNTREND"
            priceMove > averageRange * 0.7 &&
                risingCloses >= 5 -> "POSSIBLE UPTREND"

            priceMove < -averageRange * 0.7 &&
                fallingCloses >= 5 -> "POSSIBLE DOWNTREND"

            else -> "SIDEWAYS"
        }

        val support = lows.lastOrNull()?.price
        val resistance = highs.lastOrNull()?.price

        val bullishBOS = resistance != null &&
            last.close > resistance &&
            previous.close <= resistance

        val bearishBOS = support != null &&
            last.close < support &&
            previous.close >= support

        val bullishSweep = support != null &&
            last.low < support &&
            last.close > support

        val bearishSweep = resistance != null &&
            last.high > resistance &&
            last.close < resistance

        val nearSupport = support != null &&
            abs(last.low - support) <= averageRange * 0.8

        val nearResistance = resistance != null &&
            abs(last.high - resistance) <= averageRange * 0.8

        val bullishWick =
            last.lowerWick >= last.body * 1.5 &&
            last.lowerWick > last.upperWick * 1.2

        val bearishWick =
            last.upperWick >= last.body * 1.5 &&
            last.upperWick > last.lowerWick * 1.2

        val pattern = detectPattern(previous, last)

        val bullishMomentum =
            greenMomentum >= 4 &&
            risingCloses >= 4 &&
            priceMove > averageRange * 0.5

        val bearishMomentum =
            redMomentum >= 4 &&
            fallingCloses >= 4 &&
            priceMove < -averageRange * 0.5

        val closes = complete.map { it.close }

        val ema9 = ema(closes, 9)
        val ema20 = ema(closes, 20)

        val emaBullish = ema9 > ema20
        val emaBearish = ema9 < ema20

        val bullishEvidence =
            (if (bullishStructure) 2 else 0) +
            (if (bullishBOS) 3 else 0) +
            (if (bullishSweep) 2 else 0) +
            (if (bullishMomentum) 3 else 0) +
            (if (emaBullish) 1 else 0) +
            (if (bullishWick && nearSupport) 1 else 0) +
            (if (pattern.direction > 0) 1 else 0)

        val bearishEvidence =
            (if (bearishStructure) 2 else 0) +
            (if (bearishBOS) 3 else 0) +
            (if (bearishSweep) 2 else 0) +
            (if (bearishMomentum) 3 else 0) +
            (if (emaBearish) 1 else 0) +
            (if (bearishWick && nearResistance) 1 else 0) +
            (if (pattern.direction < 0) 1 else 0)

        val oversizedCandle =
            last.range > averageRange * 2.0

        val weakBody =
            last.bodyRatio < 0.20

        val strongBullishOpposition =
            bearishBOS ||
            bearishSweep ||
            (bearishMomentum && bearishEvidence >= 5) ||
            (bearishWick && nearResistance)

        val strongBearishOpposition =
            bullishBOS ||
            bullishSweep ||
            (bullishMomentum && bullishEvidence >= 5) ||
            (bullishWick && nearSupport)

        // Avoid entering directly against nearby support/resistance.
        val blockedUp = nearResistance && !bullishBOS
        val blockedDown = nearSupport && !bearishBOS

        // Avoid a signal when the latest completed candle
        // strongly contradicts the proposed direction.
        val lastStrongBearish =
            !last.bullish &&
            last.bodyRatio >= 0.55 &&
            last.body >= averageBody * 1.3

        val lastStrongBullish =
            last.bullish &&
            last.bodyRatio >= 0.55 &&
            last.body >= averageBody * 1.3

        val signal = when {

            oversizedCandle -> "WAIT"
            weakBody -> "WAIT"

            bullishEvidence >= 6 &&
                bullishEvidence >= bearishEvidence + 3 &&
                bullishMomentum &&
                !strongBullishOpposition &&
                !blockedUp &&
                !lastStrongBearish -> "UP"

            bearishEvidence >= 6 &&
                bearishEvidence >= bullishEvidence + 3 &&
                bearishMomentum &&
                !strongBearishOpposition &&
                !blockedDown &&
                !lastStrongBullish -> "DOWN"

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
            else -> "NO FRESH BOS"
        }

        val sweep = when {
            bullishSweep -> "BULLISH SWEEP"
            bearishSweep -> "BEARISH SWEEP"
            else -> "NO SWEEP"
        }

        val wick = when {
            bullishWick -> "LOWER WICK"
            bearishWick -> "UPPER WICK"
            else -> "NO STRONG WICK"
        }

        val level = when {
            nearSupport -> "NEAR SUPPORT"
            nearResistance -> "NEAR RESISTANCE"
            else -> "CLEAR"
        }

        val live = bars.last()
        val liveDirection =
            if (live.bullish) "UP FORMING" else "DOWN FORMING"

        val reason = when {
            oversizedCandle -> "HIGH VOLATILITY"
            weakBody -> "WEAK BODY"
            blockedUp && bullishEvidence > bearishEvidence ->
                "RESISTANCE BLOCK"

            blockedDown && bearishEvidence > bullishEvidence ->
                "SUPPORT BLOCK"

            strongBullishOpposition &&
                bullishEvidence > bearishEvidence ->
                "BEARISH CONFLICT"

            strongBearishOpposition &&
                bearishEvidence > bullishEvidence ->
                "BULLISH CONFLICT"

            else -> "NO CLEAR DIRECTION"
        }

        val message = buildString {
            append("NEXT: $signal")
            append(" | CURRENT: $liveDirection")
            append(" | TREND: $trend")
            append(" | STRUCTURE: $structure")
            append(" | $bos")
            append(" | $sweep")
            append(" | $wick")
            append(" | LEVEL: $level")
            append(" | PATTERN: ${pattern.name}")
            append(" | GREEN: $green")
            append(" | RED: $red")
            append(" | COMPLETE: ${complete.size}")
            append(" | MOMENTUM: $greenMomentum/$redMomentum")
            append(" | EVIDENCE: $bullishEvidence/$bearishEvidence")

            if (signal == "WAIT") {
                append(" | REASON: $reason")
            }
        }

        // Confidence is retained for compatibility with the existing UI.
        // It does not decide the UP/DOWN signal.
        val confidence = 0

        return result(
            signal,
            confidence,
            green,
            red,
            message
        )
    }

    private fun colorType(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (value < 0.20f || saturation < 0.30f) {
            return 0
        }

        return when {
            hue in 65f..175f -> 1
            hue <= 18f || hue >= 342f -> 2
            else -> 0
        }
    }

    /**
     * Detect individual candle bodies using dense color columns.
     * This helps separate adjacent candles of the same color.
     */
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

                val type = colorType(
                    bitmap.getPixel(left + x, top + y)
                )

                types[y * w + x] = type.toByte()

                if (type != 0) {
                    columnCounts[type][x]++
                }
            }
        }

        val result = mutableListOf<Bar>()

        for (type in 1..2) {

            val peakCount =
                columnCounts[type].maxOrNull() ?: 0

            if (peakCount < 2) continue

            val bodyThreshold =
                max(2, (peakCount * 0.42).roundToInt())

            val bodyColumns = BooleanArray(w) { x ->
                columnCounts[type][x] >= bodyThreshold
            }

            val bodies = mutableListOf<CandleBody>()

            var x = 0

            while (x < w) {

                if (!bodyColumns[x]) {
                    x++
                    continue
                }

                val start = x
                var end = x

                while (
                    end + 1 < w &&
                    bodyColumns[end + 1]
                ) {
                    end++
                }

                val width = end - start + 1

                if (width in 1..18) {
                    bodies.add(
                        CandleBody(
                            type,
                            start,
                            end,
                            (start + end) / 2
                        )
                    )
                }

                x = end + 1
            }

            if (bodies.isEmpty()) continue

            for (i in bodies.indices) {

                val body = bodies[i]

                val regionLeft = if (i == 0) {
                    0
                } else {
                    (bodies[i - 1].center + body.center) / 2 + 1
                }

                val regionRight = if (i == bodies.lastIndex) {
                    w - 1
                } else {
                    (body.center + bodies[i + 1].center) / 2
                }

                val startX = max(regionLeft, body.left)
                val endX = min(regionRight, body.right)

                if (endX < startX) continue

                val rowCounts = IntArray(h)

                var minY = h
                var maxY = -1
                var totalPixels = 0

                for (cx in regionLeft..regionRight) {

                    for (cy in 0 until h) {

                        if (
                            types[cy * w + cx].toInt() == type
                        ) {
                            rowCounts[cy]++
                            totalPixels++

                            minY = min(minY, cy)
                            maxY = max(maxY, cy)
                        }
                    }
                }

                if (maxY < 0 || totalPixels < 4) continue
                if (maxY - minY + 1 < 4) continue

                val peak = rowCounts.maxOrNull() ?: 0
                if (peak < 1) continue

                val threshold =
                    max(1, (peak * 0.50).roundToInt())

                var bodyTop = -1
                var bodyBottom = -1
                var bestRun = 0

                var runStart = -1
                var runLength = 0

                for (row in minY..maxY) {

                    if (rowCounts[row] >= threshold) {

                        if (runStart == -1) {
                            runStart = row
                        }

                        runLength++

                    } else {

                        if (runLength > bestRun) {
                            bestRun = runLength
                            bodyTop = runStart
                            bodyBottom = row - 1
                        }

                        runStart = -1
                        runLength = 0
                    }
                }

                if (runLength > bestRun) {
                    bodyTop = runStart
                    bodyBottom = maxY
                }

                if (
                    bodyTop < 0 ||
                    bodyBottom < bodyTop
                ) continue

                val absoluteHigh = minY + top
                val absoluteLow = maxY + top

                val absoluteBodyTop = bodyTop + top
                val absoluteBodyBottom = bodyBottom + top

                val bullish = type == 1

                val open: Double
                val close: Double

                if (bullish) {
                    open = -absoluteBodyBottom.toDouble()
                    close = -absoluteBodyTop.toDouble()
                } else {
                    open = -absoluteBodyTop.toDouble()
                    close = -absoluteBodyBottom.toDouble()
                }

                val centerX =
                    left + body.center

                result.add(
                    Bar(
                        bullish = bullish,
                        open = open,
                        close = close,
                        high = -absoluteHigh.toDouble(),
                        low = -absoluteLow.toDouble(),
                        body = (
                            absoluteBodyBottom -
                                absoluteBodyTop + 1
                            ).toDouble(),
                        upperWick = max(
                            0,
                            absoluteBodyTop - absoluteHigh
                        ).toDouble(),
                        lowerWick = max(
                            0,
                            absoluteLow - absoluteBodyBottom
                        ).toDouble(),
                        centerX = centerX
                    )
                )
            }
        }

        return result
            .sortedBy { it.centerX }
            .distinctBy { it.centerX }
    }

    private fun detectPattern(
        a: Bar,
        b: Bar
    ): Pattern {

        val body = b.body.coerceAtLeast(1.0)

        if (b.bodyRatio <= 0.12) {
            return Pattern("DOJI", 0)
        }

        if (
            b.lowerWick >= body * 2 &&
            b.upperWick <= body
        ) {
            return Pattern("HAMMER", 1)
        }

        if (
            b.upperWick >= body * 2 &&
            b.lowerWick <= body
        ) {
            return Pattern("SHOOTING STAR", -1)
        }

        if (
            b.bullish &&
            !a.bullish &&
            b.open <= a.close &&
            b.close >= a.open &&
            b.body > a.body * 0.9
        ) {
            return Pattern("BULLISH ENGULFING", 1)
        }

        if (
            !b.bullish &&
            a.bullish &&
            b.open >= a.close &&
            b.close <= a.open &&
            b.body > a.body * 0.9
        ) {
            return Pattern("BEARISH ENGULFING", -1)
        }

        return Pattern("NONE", 0)
    }

    private fun findSwings(
        bars: List<Bar>,
        high: Boolean
    ): List<Swing> {

        val result = mutableListOf<Swing>()
        val radius = 2

        for (i in radius until bars.size - radius) {

            val price =
                if (high) bars[i].high else bars[i].low

            val valid = (1..radius).all { d ->

                if (high) {
                    price > bars[i - d].high &&
                        price > bars[i + d].high
                } else {
                    price < bars[i - d].low &&
                        price < bars[i + d].low
                }
            }

            if (valid) {
                result.add(Swing(i, price))
            }
        }

        return result
    }

    private fun ema(
        values: List<Double>,
        period: Int
    ): Double {

        if (values.isEmpty()) return 0.0

        val multiplier = 2.0 / (period + 1)
        var value = values.first()

        for (i in 1 until values.size) {

            value = values[i] * multiplier +
                value * (1.0 - multiplier)
        }

        return value
    }

    private fun result(
        signal: String,
        confidence: Int,
        green: Int,
        red: Int,
        message: String
    ): CandleSignal {

        return CandleSignal(
            signal,
            confidence,
            green,
            red,
            message
        )
    }
}
