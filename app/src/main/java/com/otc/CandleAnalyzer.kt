
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

    private data class Component(
        val type: Int,
        var left: Int,
        var right: Int,
        var top: Int,
        var bottom: Int,
        var area: Int
    )

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

        // Latest candle may still be forming.
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

        val trendMove = last.close - complete.first().close

        val trend = when {
            bullishStructure -> "BULLISH"
            bearishStructure -> "BEARISH"
            trendMove > 8 -> "POSSIBLE BULLISH"
            trendMove < -8 -> "POSSIBLE BEARISH"
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

        val averageRange = complete
            .takeLast(10)
            .map { it.range }
            .average()
            .coerceAtLeast(1.0)

        val nearSupport = support != null &&
            abs(last.low - support) <= averageRange * 2

        val nearResistance = resistance != null &&
            abs(last.high - resistance) <= averageRange * 2

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

        val ema9 = ema(complete.map { it.close }, 9)
        val ema20 = ema(complete.map { it.close }, 20)
        val ema50 = ema(complete.map { it.close }, 50)

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
        if (trend == "POSSIBLE BULLISH") score += 1
        if (trend == "POSSIBLE BEARISH") score -= 1

        if (bullishSweep) score += 4
        if (bearishSweep) score -= 4

        if (bullishWick) score += 3
        if (bearishWick) score -= 3

        if (nearSupport && last.bullish) score += 2
        if (nearResistance && !last.bullish) score -= 2

        score += pattern.score

        if (bullishFvg) score += 2
        if (bearishFvg) score -= 2

        if (ema9 > ema20 && ema20 > ema50) score += 4
        if (ema9 < ema20 && ema20 < ema50) score -= 4

        if (rsi in 52.0..68.0) score += 2
        if (rsi in 32.0..48.0) score -= 2
        if (rsi > 75) score -= 2
        if (rsi < 25) score += 2

        score += greenMomentum - redMomentum

        val bullishEvidence =
            bullishBOS || bullishSweep ||
                bullishWick || pattern.score > 0

        val bearishEvidence =
            bearishBOS || bearishSweep ||
                bearishWick || pattern.score < 0

        val conflict = bullishEvidence && bearishEvidence

        if (conflict) {
            score = (score * 0.60).roundToInt()
        }

        val confidence = (
            abs(score) * 3
        ).coerceIn(0, 85)

        val signal = when {
            conflict -> "WAIT"
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

        val message = buildString {
            append("NEXT: $signal | SCORE: $confidence%")
            append(" | TREND: $trend")
            append(" | STRUCTURE: $structure")
            append(" | $bos")
            append(" | $sweep")
            append(" | WICK: $wick")
            append(" | PATTERN: ${pattern.name}")
            append(" | FVG: ${if (bullishFvg) "BULLISH" else if (bearishFvg) "BEARISH" else "NONE"}")
            append(" | RSI: ${rsi.roundToInt()}")
            append(" | EMA: ${if (ema9 > ema20) "BULLISH" else "BEARISH"}")
            append(" | GREEN: $green")
            append(" | RED: $red")
            append(" | COMPLETE: ${complete.size}")
            append(" | DETECTED: ${bars.size}")
            append(" | MOMENTUM: $greenMomentum/$redMomentum")
            append(" | CONFLICT: $conflict")
        }

        return result(signal, confidence, green, red, message)
    }

    // Red and green candle color classification.
    private fun colorType(color: Int): Int {

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val brightness = max(r, max(g, b))

        if (brightness < 65) return 0

        return when {
            g > r * 1.22 &&
                g > b * 1.08 &&
                g > 85 -> 1

            r > g * 1.22 &&
                r > b * 1.12 &&
                r > 100 -> 2

            else -> 0
        }
    }

    // Connected-component candle detection.
    // Each connected red/green candle body and wick is grouped separately.
    private fun detectCandles(bitmap: Bitmap): List<Bar> {

        val screenW = bitmap.width
        val screenH = bitmap.height

        // Chart crop: exclude most controls and the right price labels.
        val left = (screenW * 0.01f).toInt()
        val right = (screenW * 0.88f).toInt()
        val top = (screenH * 0.10f).toInt()
        val bottom = (screenH * 0.70f).toInt()

        val w = right - left
        val h = bottom - top

        if (w < 20 || h < 20) return emptyList()

        val types = ByteArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                types[y * w + x] =
                    colorType(bitmap.getPixel(left + x, top + y)).toByte()
            }
        }

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val components = mutableListOf<Component>()

        for (start in types.indices) {

            val type = types[start].toInt()

            if (type == 0 || visited[start]) continue

            var head = 0
            var tail = 0

            queue[tail++] = start
            visited[start] = true

            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            var area = 0

            while (head < tail) {

                val position = queue[head++]
                val x = position % w
                val y = position / w

                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                area++

                for (dy in -1..1) {
                    for (dx in -1..1) {

                        if (dx == 0 && dy == 0) continue

                        val nx = x + dx
                        val ny = y + dy

                        if (
                            nx !in 0 until w ||
                            ny !in 0 until h
                        ) continue

                        val next = ny * w + nx

                        if (
                            !visited[next] &&
                            types[next].toInt() == type
                        ) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1

            if (
                componentWidth > 22 ||
                componentHeight < 4 ||
                area < 5
            ) continue

            components.add(
                Component(
                    type,
                    minX,
                    maxX,
                    minY,
                    maxY,
                    area
                )
            )
        }

        // Merge vertically separated body/wick pieces belonging
        // to the same candle when their horizontal ranges overlap.
        var changed = true

        while (changed) {
            changed = false

            outer@ for (i in components.indices) {
                for (j in i + 1 until components.size) {

                    val a = components[i]
                    val b = components[j]

                    if (a.type != b.type) continue

                    val overlap =
                        min(a.right, b.right) -
                            max(a.left, b.left) + 1

                    val verticalGap = max(
                        0,
                        max(a.top, b.top) -
                            min(a.bottom, b.bottom) - 1
                    )

                    if (overlap >= 1 && verticalGap <= 4) {

                        val newLeft = min(a.left, b.left)
                        val newRight = max(a.right, b.right)
                        val newTop = min(a.top, b.top)
                        val newBottom = max(a.bottom, b.bottom)

                        if (newRight - newLeft + 1 <= 22) {
                            a.left = newLeft
                            a.right = newRight
                            a.top = newTop
                            a.bottom = newBottom
                            a.area += b.area

                            components.removeAt(j)
                            changed = true
                            break@outer
                        }
                    }
                }
            }
        }

        val result = mutableListOf<Bar>()

        for (component in components) {

            val cw = component.right - component.left + 1
            val ch = component.bottom - component.top + 1

            if (cw !in 2..22 || ch < 5) continue

            val rows = IntArray(ch)

            for (x in component.left..component.right) {
                for (y in component.top..component.bottom) {

                    val type = types[y * w + x].toInt()

                    if (type == component.type) {
                        rows[y - component.top]++
                    }
                }
            }

            val peak = rows.maxOrNull() ?: 0
            if (peak < 2) continue

            val peakY = rows.indices.maxByOrNull {
                rows[it]
            } ?: continue

            val threshold = max(
                2,
                (peak * 0.55).roundToInt()
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
                bodyBottom < ch - 1 &&
                rows[bodyBottom + 1] >= threshold
            ) {
                bodyBottom++
            }

            val bullish = component.type == 1

            val absoluteTop = component.top + top
            val absoluteBottom = component.bottom + top

            val open: Double
            val close: Double

            if (bullish) {
                open = -(component.top + bodyBottom + top).toDouble()
                close = -(component.top + bodyTop + top).toDouble()
            } else {
                open = -(component.top + bodyTop + top).toDouble()
                close = -(component.top + bodyBottom + top).toDouble()
            }

            val body = max(
                1,
                bodyBottom - bodyTop + 1
            ).toDouble()

            val upperWick = max(
                0,
                bodyTop
            ).toDouble()

            val lowerWick = max(
                0,
                ch - 1 - bodyBottom
            ).toDouble()

            result.add(
                Bar(
                    bullish = bullish,
                    open = open,
                    close = close,
                    high = -absoluteTop.toDouble(),
                    low = -absoluteBottom.toDouble(),
                    body = body,
                    upperWick = upperWick,
                    lowerWick = lowerWick,
                    centerX = left +
                        (component.left + component.right) / 2
                )
            )
        }

        // Remove duplicate detections with nearly identical centers.
        val sorted = result.sortedBy { it.centerX }
        val unique = mutableListOf<Bar>()

        for (bar in sorted) {
            val previous = unique.lastOrNull()

            if (
                previous != null &&
                abs(previous.centerX - bar.centerX) <= 2
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

        val engulfing =
            b.high >= a.high &&
                b.low <= a.low &&
                b.body > a.body * 0.9

        if (engulfing && b.bullish && !a.bullish) {
            return Pattern("BULLISH ENGULFING", 5)
        }

        if (engulfing && !b.bullish && a.bullish) {
            return Pattern("BEARISH ENGULFING", -5)
        }

        if (b.bullish && a.bullish && b.body > a.body * 1.5) {
            return Pattern("STRONG BULLISH", 2)
        }

        if (!b.bullish && !a.bullish && b.body > a.body * 1.5) {
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
        val period = min(14, closes.size - 1).coerceAtLeast(1)

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
