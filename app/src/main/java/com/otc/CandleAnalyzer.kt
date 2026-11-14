
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
    }

    private data class Swing(
        val index: Int,
        val price: Double
    )

    private data class Pattern(
        val name: String,
        val score: Int
    )

    private data class CandleColumn(
        val type: Int,
        val left: Int,
        val right: Int
    )

    fun analyze(bitmap: Bitmap): CandleSignal {

        if (
            bitmap.isRecycled ||
            bitmap.width < 100 ||
            bitmap.height < 100
        ) {
            return result("WAIT", 0, 0, 0, "INVALID SCREEN")
        }

        val bars = detectCandles(bitmap)

        if (bars.size < 9) {
            val green = bars.count { it.bullish }
            val red = bars.size - green

            return result(
                "WAIT",
                0,
                green,
                red,
                "WAIT | DETECTED: ${bars.size} | NEED MORE CANDLES"
            )
        }

        // Last detected candle is treated as the live candle.
        val live = bars.last()
        val complete = bars.dropLast(1)

        if (complete.size < 8) {
            return result(
                "WAIT",
                0,
                complete.count { it.bullish },
                complete.count { !it.bullish },
                "WAIT | COMPLETE: ${complete.size}"
            )
        }

        val green = complete.count { it.bullish }
        val red = complete.size - green

        val last = complete.last()
        val previous = complete[complete.lastIndex - 1]

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

        val averageRange = complete
            .takeLast(10)
            .map { it.range }
            .average()
            .coerceAtLeast(1.0)

        val trendBars = complete.takeLast(8)
        val trendMove =
            trendBars.last().close - trendBars.first().close

        val upwardCloses = trendBars
            .zipWithNext()
            .count { (a, b) -> b.close > a.close }

        val downwardCloses = trendBars
            .zipWithNext()
            .count { (a, b) -> b.close < a.close }

        val trend = when {
            bullishStructure -> "BULLISH"
            bearishStructure -> "BEARISH"

            trendMove >= averageRange * 1.2 &&
                upwardCloses > downwardCloses ->
                "POSSIBLE BULLISH"

            trendMove <= -averageRange * 1.2 &&
                downwardCloses > upwardCloses ->
                "POSSIBLE BEARISH"

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

        val nearSupport = support != null &&
            abs(last.low - support) <= averageRange * 1.5

        val nearResistance = resistance != null &&
            abs(last.high - resistance) <= averageRange * 1.5

        val bullishSweep = support != null &&
            last.low < support &&
            last.close > support

        val bearishSweep = resistance != null &&
            last.high > resistance &&
            last.close < resistance

        val bullishWick =
            last.lowerWick >= last.body * 1.5 &&
                last.lowerWick > last.upperWick &&
                nearSupport

        val bearishWick =
            last.upperWick >= last.body * 1.5 &&
                last.upperWick > last.lowerWick &&
                nearResistance

        val pattern = detectPattern(previous, last)

        val recent = complete.takeLast(6)
        val greenMomentum = recent.count { it.bullish }
        val redMomentum = recent.size - greenMomentum

        val closes = complete.map { it.close }

        val ema9 = ema(closes, 9)
        val ema20 = ema(closes, 20)

        val ema20Ready = complete.size >= 20
        val ema50Ready = complete.size >= 50

        val ema50 = if (ema50Ready) {
            ema(closes, 50)
        } else {
            0.0
        }

        val rsi = calculateRsi(complete)

        val bullishFvg = complete.size >= 3 &&
            complete.last().low >
            complete[complete.lastIndex - 2].high

        val bearishFvg = complete.size >= 3 &&
            complete.last().high <
            complete[complete.lastIndex - 2].low

        var score = 0

        if (bullishStructure) score += 5
        if (bearishStructure) score -= 5

        if (bullishBOS) score += 6
        if (bearishBOS) score -= 6

        if (trend == "BULLISH") score += 3
        if (trend == "BEARISH") score -= 3
        if (trend == "POSSIBLE BULLISH") score += 2
        if (trend == "POSSIBLE BEARISH") score -= 2

        if (bullishSweep) score += 4
        if (bearishSweep) score -= 4

        if (bullishWick) score += 3
        if (bearishWick) score -= 3

        if (nearSupport && last.bullish) score += 2
        if (nearResistance && !last.bullish) score -= 2

        score += pattern.score

        if (bullishFvg) score += 2
        if (bearishFvg) score -= 2

        if (ema20Ready) {
            if (ema9 > ema20) score += 3
            if (ema9 < ema20) score -= 3
        }

        if (ema50Ready) {
            if (ema20 > ema50) score += 2
            if (ema20 < ema50) score -= 2
        }

        if (rsi in 52.0..68.0) score += 2
        if (rsi in 32.0..48.0) score -= 2
        if (rsi > 75) score -= 2
        if (rsi < 25) score += 2

        score += greenMomentum - redMomentum

        if (trendMove >= averageRange * 1.5) score += 2
        if (trendMove <= -averageRange * 1.5) score -= 2

        val bullishEvidence =
            bullishBOS ||
                bullishSweep ||
                bullishWick ||
                pattern.score > 0

        val bearishEvidence =
            bearishBOS ||
                bearishSweep ||
                bearishWick ||
                pattern.score < 0

        val conflict = bullishEvidence && bearishEvidence

        if (conflict) {
            score = (score * 0.60).roundToInt()
        }

        val bullishConfirmations = listOf(
            bullishStructure,
            trend == "BULLISH" ||
                trend == "POSSIBLE BULLISH",
            bullishBOS,
            bullishSweep,
            bullishWick,
            ema20Ready && ema9 > ema20,
            greenMomentum >= 4,
            pattern.score > 0
        ).count { it }

        val bearishConfirmations = listOf(
            bearishStructure,
            trend == "BEARISH" ||
                trend == "POSSIBLE BEARISH",
            bearishBOS,
            bearishSweep,
            bearishWick,
            ema20Ready && ema9 < ema20,
            redMomentum >= 4,
            pattern.score < 0
        ).count { it }

        // This is a technical strength score, NOT a win probability.
        val confidence =
            (abs(score) * 3).coerceIn(0, 85)

        // Strict entry filter: weak or conflicting evidence means WAIT.
        val signal = when {
            conflict -> "WAIT"

            score >= 20 &&
                confidence >= 60 &&
                bullishConfirmations >= 3 &&
                bullishConfirmations > bearishConfirmations ->
                "UP"

            score <= -20 &&
                confidence >= 60 &&
                bearishConfirmations >= 3 &&
                bearishConfirmations > bullishConfirmations ->
                "DOWN"

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

        val sweep = when {
            bullishSweep -> "BULLISH SWEEP"
            bearishSweep -> "BEARISH SWEEP"
            else -> "NO SWEEP"
        }

        val wick = when {
            bullishWick -> "BULLISH"
            bearishWick -> "BEARISH"
            else -> "NORMAL"
        }

        val emaDirection = when {
            !ema20Ready -> "NOT READY"
            ema9 > ema20 -> "BULLISH"
            ema9 < ema20 -> "BEARISH"
            else -> "NEUTRAL"
        }

        val fvg = when {
            bullishFvg -> "BULLISH"
            bearishFvg -> "BEARISH"
            else -> "NONE"
        }

        val liveDirection = when {
            live.bullish -> "UP FORMING"
            else -> "DOWN FORMING"
        }

        val message = buildString {
            append("NEXT: $signal | SCORE: $confidence%")
            append(" | CURRENT: $liveDirection")
            append(" | TREND: $trend")
            append(" | STRUCTURE: $structure")
            append(" | $bos")
            append(" | $sweep")
            append(" | WICK: $wick")
            append(" | PATTERN: ${pattern.name}")
            append(" | FVG: $fvg")
            append(" | RSI: ${rsi.roundToInt()}")
            append(" | EMA: $emaDirection")
            append(" | GREEN: $green")
            append(" | RED: $red")
            append(" | COMPLETE: ${complete.size}")
            append(" | DETECTED: ${bars.size}")
            append(" | MOMENTUM: $greenMomentum/$redMomentum")
            append(" | CONFIRM: $bullishConfirmations/$bearishConfirmations")
            append(" | CONFLICT: $conflict")
        }

        return result(signal, confidence, green, red, message)
    }

    private fun colorType(color: Int): Int {

        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (value < 0.25f || saturation < 0.28f) {
            return 0
        }

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

        // Detect red and green candles independently.
        for (type in 1..2) {

            val active = BooleanArray(w)

            for (x in 0 until w) {
                // A candle body normally creates multiple colored pixels
                // in at least one vertical image column.
                active[x] = columnCounts[type][x] >= 2
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

                while (end + 1 < w && active[end + 1]) {
                    end++
                }

                // Include a single thin neighboring column for a wick.
                var groupLeft = start
                var groupRight = end

                if (
                    groupLeft > 0 &&
                    columnCounts[type][groupLeft - 1] == 1
                ) {
                    groupLeft--
                }

                if (
                    groupRight + 1 < w &&
                    columnCounts[type][groupRight + 1] == 1
                ) {
                    groupRight++
                }

                val groupWidth = groupRight - groupLeft + 1

                if (groupWidth in 1..22) {
                    groups.add(
                        CandleColumn(
                            type,
                            groupLeft,
                            groupRight
                        )
                    )
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

                val candleHeight = maxY - minY + 1
                if (candleHeight < 4) continue

                val rows = pixelRows.copyOfRange(
                    minY,
                    maxY + 1
                )

                val peak = rows.maxOrNull() ?: 0
                if (peak < 1) continue

                val peakY = rows.indices.maxByOrNull {
                    rows[it]
                } ?: continue

                // Dense rows approximate the candle body.
                val threshold = max(
                    1,
                    (peak * 0.50).roundToInt()
                )

                var bodyTop = peakY
                var bodyBottom = peakY

                while (
                    bodyTop > 0 &&
                    rows[bodyTop - 1] >= threshold
                ) {
                    bodyTop--
                }

                while (
                    bodyBottom < rows.lastIndex &&
                    rows[bodyBottom + 1] >= threshold
                ) {
                    bodyBottom++
                }

                val bodyHeight = bodyBottom - bodyTop + 1
                if (bodyHeight < 2) continue

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

                val body = bodyHeight.toDouble()

                val upperWick =
                    max(0, absoluteBodyTop - absoluteHigh).toDouble()

                val lowerWick =
                    max(0, absoluteLow - absoluteBodyBottom).toDouble()

                result.add(
                    Bar(
                        bullish = bullish,
                        open = open,
                        close = close,
                        high = -absoluteHigh.toDouble(),
                        low = -absoluteLow.toDouble(),
                        body = body,
                        upperWick = upperWick,
                        lowerWick = lowerWick,
                        centerX = left +
                            (group.left + group.right) / 2
                    )
                )
            }
        }

        val sorted = result.sortedBy { it.centerX }
        val unique = mutableListOf<Bar>()

        for (bar in sorted) {

            val previous = unique.lastOrNull()

            if (
                previous != null &&
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

        if (b.body <= b.range * 0.12) {
            return Pattern("DOJI", 0)
        }

        if (
            b.lowerWick >= body * 2 &&
            b.upperWick <= body
        ) {
            return Pattern("HAMMER / BULLISH PIN", 3)
        }

        if (
            b.upperWick >= body * 2 &&
            b.lowerWick <= body
        ) {
            return Pattern("SHOOTING STAR / BEARISH PIN", -3)
        }

        val bullishEngulfing =
            b.bullish &&
                !a.bullish &&
                b.open <= a.close &&
                b.close >= a.open &&
                b.body > a.body * 0.9

        if (bullishEngulfing) {
            return Pattern("BULLISH ENGULFING", 5)
        }

        val bearishEngulfing =
            !b.bullish &&
                a.bullish &&
                b.open >= a.close &&
                b.close <= a.open &&
                b.body > a.body * 0.9

        if (bearishEngulfing) {
            return Pattern("BEARISH ENGULFING", -5)
        }

        if (
            b.bullish &&
            a.bullish &&
            b.body > a.body * 1.5
        ) {
            return Pattern("STRONG BULLISH", 2)
        }

        if (
            !b.bullish &&
            !a.bullish &&
            b.body > a.body * 1.5
        ) {
            return Pattern("STRONG BEARISH", -2)
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

    private fun ema(
        values: List<Double>,
        period: Int
    ): Double {

        if (values.isEmpty()) return 0.0

        val multiplier = 2.0 / (period + 1)
        var value = values.first()

        for (i in 1 until values.size) {
            value =
                values[i] * multiplier +
                    value * (1.0 - multiplier)
        }

        return value
    }

    private fun calculateRsi(bars: List<Bar>): Double {

        val closes = bars.map { it.close }

        val period = min(14, closes.size - 1)
            .coerceAtLeast(1)

        var gains = 0.0
        var losses = 0.0

        for (i in closes.size - period until closes.size) {

            val change = closes[i] - closes[i - 1]

            if (change > 0) gains += change
            else losses += abs(change)
        }

        val averageGain = gains / period
        val averageLoss = losses / period

        return when {
            averageGain == 0.0 && averageLoss == 0.0 -> 50.0
            averageLoss == 0.0 -> 100.0
            else -> 100.0 -
                (100.0 / (1.0 + averageGain / averageLoss))
        }
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
