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

    private data class Swing(
        val index: Int,
        val price: Double
    )

    private data class Pattern(
        val name: String,
        val direction: Int,
        val strength: Int
    )

    private data class Cluster(
        val type: Int,
        val left: Int,
        val right: Int,
        val center: Int
    )

    fun analyze(bitmap: Bitmap): CandleSignal {

        if (
            bitmap.isRecycled ||
            bitmap.width < 100 ||
            bitmap.height < 100
        ) {
            return result(
                "WAIT", 0, 0, 0,
                "INVALID SCREEN"
            )
        }

        val bars = detectCandles(bitmap)

        if (bars.size < 12) {
            val green = bars.count { it.bullish }
            val red = bars.size - green

            return result(
                "WAIT", 0, green, red,
                "WAIT | DETECTED: ${bars.size} | NEED 12+"
            )
        }

        // Exclude last candle because it may be forming.
        val complete = bars.dropLast(1)

        if (complete.size < 10) {
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
        val lastSix = complete.takeLast(6)

        val averageRange = complete.takeLast(14)
            .map { it.range }
            .average()
            .coerceAtLeast(1.0)

        val averageBody = lastSix.map { it.body }
            .average()
            .coerceAtLeast(1.0)

        val priceMove =
            recent.last().close - recent.first().close

        val risingCloses = recent.zipWithNext()
            .count {
                it.second.close > it.first.close
            }

        val fallingCloses = recent.zipWithNext()
            .count {
                it.second.close < it.first.close
            }

        val greenMomentum = lastSix.count { it.bullish }
        val redMomentum = lastSix.size - greenMomentum

        // ─────────────────────────
        // RECENT REVERSAL
        // ─────────────────────────

        val lastThree = complete.takeLast(3)

        val recentRising = lastThree.zipWithNext()
            .count {
                it.second.close > it.first.close
            }

        val recentFalling = lastThree.zipWithNext()
            .count {
                it.second.close < it.first.close
            }

        val recentMove =
            if (lastThree.size >= 2) {
                lastThree.last().close -
                    lastThree.first().close
            } else {
                0.0
            }

        val recentBullishReversal =
            lastThree.size == 3 &&
            recentRising >= 2 &&
            lastThree.count { it.bullish } >= 2 &&
            recentMove > averageRange * 0.35

        val recentBearishReversal =
            lastThree.size == 3 &&
            recentFalling >= 2 &&
            lastThree.count { !it.bullish } >= 2 &&
            recentMove < -averageRange * 0.35

        // ─────────────────────────
        // MARKET STRUCTURE
        // ─────────────────────────

        val highs = findSwings(complete, true)
        val lows = findSwings(complete, false)

        val higherHighs =
            highs.size >= 2 &&
            highs.last().price >
                highs[highs.lastIndex - 1].price

        val lowerHighs =
            highs.size >= 2 &&
            highs.last().price <
                highs[highs.lastIndex - 1].price

        val higherLows =
            lows.size >= 2 &&
            lows.last().price >
                lows[lows.lastIndex - 1].price

        val lowerLows =
            lows.size >= 2 &&
            lows.last().price <
                lows[lows.lastIndex - 1].price

        val bullishStructure =
            higherHighs && higherLows

        val bearishStructure =
            lowerHighs && lowerLows

        val trend = when {

            bullishStructure ->
                "UPTREND"

            bearishStructure ->
                "DOWNTREND"

            priceMove > averageRange * 0.7 &&
                risingCloses >= 5 ->
                "POSSIBLE UPTREND"

            priceMove < -averageRange * 0.7 &&
                fallingCloses >= 5 ->
                "POSSIBLE DOWNTREND"

            else ->
                "SIDEWAYS"
        }

        // ─────────────────────────
        // SUPPORT / RESISTANCE
        // ─────────────────────────

        val support = lows.lastOrNull()?.price
        val resistance = highs.lastOrNull()?.price

        val bullishBOS =
            resistance != null &&
            last.close > resistance &&
            previous.close <= resistance

        val bearishBOS =
            support != null &&
            last.close < support &&
            previous.close >= support

        val bullishSweep =
            support != null &&
            last.low < support &&
            last.close > support

        val bearishSweep =
            resistance != null &&
            last.high > resistance &&
            last.close < resistance

        val nearSupport =
            support != null &&
            abs(last.low - support) <=
                averageRange * 0.8

        val nearResistance =
            resistance != null &&
            abs(last.high - resistance) <=
                averageRange * 0.8

        // ─────────────────────────
        // WICK ANALYSIS
        // ─────────────────────────

        val bullishWick =
            last.lowerWick >= last.body * 1.5 &&
            last.lowerWick > last.upperWick * 1.2

        val bearishWick =
            last.upperWick >= last.body * 1.5 &&
            last.upperWick > last.lowerWick * 1.2

        // ─────────────────────────
        // PATTERNS
        // ─────────────────────────

        val priorBars = complete
            .dropLast(1)
            .takeLast(5)

        val priorMove =
            if (priorBars.size >= 2) {
                priorBars.last().close -
                    priorBars.first().close
            } else {
                0.0
            }

        val pattern = detectPattern(
            previous,
            last,
            priorMove < 0,
            priorMove > 0
        )

        // ─────────────────────────
        // MOMENTUM
        // ─────────────────────────

        val bullishMomentum =
            greenMomentum >= 4 &&
            risingCloses >= 4 &&
            priceMove > averageRange * 0.5

        val bearishMomentum =
            redMomentum >= 4 &&
            fallingCloses >= 4 &&
            priceMove < -averageRange * 0.5

        // ─────────────────────────
        // EMA
        // ─────────────────────────

        val closes = complete.map { it.close }

        val ema9 = ema(closes, 9)
        val ema20 = ema(closes, 20)

        val emaBullish = ema9 > ema20
        val emaBearish = ema9 < ema20

        // ─────────────────────────
        // EVIDENCE
        // ─────────────────────────

        val bullishPattern =
            pattern.direction > 0 &&
            pattern.strength > 0

        val bearishPattern =
            pattern.direction < 0 &&
            pattern.strength > 0

        val bullishEvidence =
            (if (bullishStructure) 2 else 0) +
            (if (bullishBOS) 3 else 0) +
            (if (bullishSweep) 2 else 0) +
            (if (bullishMomentum) 3 else 0) +
            (if (emaBullish) 1 else 0) +
            (if (bullishWick && nearSupport) 1 else 0) +
            (if (bullishPattern) pattern.strength else 0)

        val bearishEvidence =
            (if (bearishStructure) 2 else 0) +
            (if (bearishBOS) 3 else 0) +
            (if (bearishSweep) 2 else 0) +
            (if (bearishMomentum) 3 else 0) +
            (if (emaBearish) 1 else 0) +
            (if (bearishWick && nearResistance) 1 else 0) +
            (if (bearishPattern) pattern.strength else 0)

        // ─────────────────────────
        // FILTERS
        // ─────────────────────────

        val oversizedCandle =
            last.range > averageRange * 2.0

        val weakBody =
            last.bodyRatio < 0.20

        val dojiDetected =
            pattern.name == "DOJI"

        val strongBullishOpposition =
            bearishBOS ||
            bearishSweep ||
            (bearishMomentum &&
                bearishEvidence >= 5) ||
            (bearishWick && nearResistance)

        val strongBearishOpposition =
            bullishBOS ||
            bullishSweep ||
            (bullishMomentum &&
                bullishEvidence >= 5) ||
            (bullishWick && nearSupport)

        val blockedUp =
            nearResistance && !bullishBOS

        val blockedDown =
            nearSupport && !bearishBOS

        val lastStrongBearish =
            !last.bullish &&
            last.bodyRatio >= 0.55 &&
            last.body >= averageBody * 1.3

        val lastStrongBullish =
            last.bullish &&
            last.bodyRatio >= 0.55 &&
            last.body >= averageBody * 1.3

        // ─────────────────────────
        // SIGNAL
        // ─────────────────────────

        val signal = when {

            oversizedCandle ->
                "WAIT"

            weakBody ->
                "WAIT"

            dojiDetected &&
                !bullishBOS &&
                !bearishBOS ->
                "WAIT"

            recentBullishReversal ->
                "WAIT"

            recentBearishReversal ->
                "WAIT"

            bullishEvidence >= 6 &&
                bullishEvidence >= bearishEvidence + 3 &&
                bullishMomentum &&
                !strongBullishOpposition &&
                !blockedUp &&
                !lastStrongBearish ->
                "UP"

            bearishEvidence >= 6 &&
                bearishEvidence >= bullishEvidence + 3 &&
                bearishMomentum &&
                !strongBearishOpposition &&
                !blockedDown &&
                !lastStrongBullish ->
                "DOWN"

            else ->
                "WAIT"
        }

        // ─────────────────────────
        // DISPLAY
        // ─────────────────────────

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
            if (live.bullish) {
                "UP FORMING"
            } else {
                "DOWN FORMING"
            }

        val reason = when {

            recentBullishReversal ->
                "RECENT UP REVERSAL"

            recentBearishReversal ->
                "RECENT DOWN REVERSAL"

            oversizedCandle ->
                "HIGH VOLATILITY"

            weakBody ->
                "WEAK BODY"

            dojiDetected ->
                "DOJI / INDECISION"

            blockedUp ->
                "RESISTANCE BLOCK"

            blockedDown ->
                "SUPPORT BLOCK"

            strongBullishOpposition ->
                "BEARISH CONFLICT"

            strongBearishOpposition ->
                "BULLISH CONFLICT"

            else ->
                "NO CLEAR DIRECTION"
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

            append(" | REVERSAL: ")

            append(
                when {
                    recentBullishReversal ->
                        "UP REVERSAL"

                    recentBearishReversal ->
                        "DOWN REVERSAL"

                    else ->
                        "NONE"
                }
            )

            append(" | GREEN: $green")

            append(" | RED: $red")

            append(" | DETECTED: ${complete.size}")

            append(" | MOMENTUM: $greenMomentum/$redMomentum")

            append(" | EVIDENCE: $bullishEvidence/$bearishEvidence")

            if (signal == "WAIT") {
                append(" | REASON: $reason")
            }
        }

        return result(
            signal,
            0,
            green,
            red,
            message
        )
    }

    // ─────────────────────────────
    // COLOR DETECTION
    // ─────────────────────────────

    private fun colorType(color: Int): Int {

        val hsv = FloatArray(3)

        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (
            value < 0.18f ||
            saturation < 0.25f
        ) {
            return 0
        }

        return when {

            hue in 65f..175f ->
                1

            hue <= 18f || hue >= 342f ->
                2

            else ->
                0
        }
    }

    // ─────────────────────────────
    // CANDLE DETECTION - IMPROVED
    // ─────────────────────────────

    private fun detectCandles(
        bitmap: Bitmap
    ): List<Bar> {

        val screenW = bitmap.width
        val screenH = bitmap.height

        // Chart region
        val left = (screenW * 0.01f).toInt()
        val right = (screenW * 0.88f).toInt()

        val top = (screenH * 0.10f).toInt()
        val bottom = (screenH * 0.70f).toInt()

        val w = right - left
        val h = bottom - top

        if (w < 30 || h < 30) {
            return emptyList()
        }

        val types = ByteArray(w * h)

        val columnCounts = Array(3) {
            IntArray(w)
        }

        // Read colors once
        for (y in 0 until h) {

            for (x in 0 until w) {

                val type = colorType(
                    bitmap.getPixel(
                        left + x,
                        top + y
                    )
                )

                types[y * w + x] = type.toByte()

                if (type != 0) {
                    columnCounts[type][x]++
                }
            }
        }

        val clusters = mutableListOf<Cluster>()

        // Detect candle body columns separately
        for (type in 1..2) {

            val counts = columnCounts[type]

            val peak = counts.maxOrNull() ?: 0

            if (peak < 2) continue

            // Adaptive threshold
            val threshold = max(
                2,
                (peak * 0.22).roundToInt()
            )

            val active = BooleanArray(w) { x ->
                counts[x] >= threshold
            }

            var x = 0

            while (x < w) {

                if (!active[x]) {
                    x++
                    continue
                }

                val start = x

                var end = x

                // Merge tiny gaps caused by anti-aliasing
                while (
                    end + 1 < w &&
                    (
                        active[end + 1] ||
                        (
                            end + 2 < w &&
                            active[end + 2]
                        )
                    )
                ) {

                    if (!active[end + 1]) {
                        end++
                    }

                    end++
                }

                val width = end - start + 1

                // Reject very wide chart objects
                if (width in 1..22) {

                    clusters.add(
                        Cluster(
                            type = type,
                            left = start,
                            right = end,
                            center = (start + end) / 2
                        )
                    )
                }

                x = end + 1
            }
        }

        if (clusters.isEmpty()) {
            return emptyList()
        }

        val sorted = clusters.sortedBy { it.center }

        val cleaned = mutableListOf<Cluster>()

        for (cluster in sorted) {

            val previous = cleaned.lastOrNull()

            if (
                previous != null &&
                abs(previous.center - cluster.center) <= 2
            ) {

                // Same candle detected in overlapping colors
                if (
                    cluster.right - cluster.left >
                    previous.right - previous.left
                ) {
                    cleaned[cleaned.lastIndex] = cluster
                }

            } else {
                cleaned.add(cluster)
            }
        }

        val result = mutableListOf<Bar>()

        for (i in cleaned.indices) {

            val cluster = cleaned[i]

            val regionLeft =
                if (i == 0) {
                    0
                } else {
                    (
                        cleaned[i - 1].center +
                            cluster.center
                    ) / 2 + 1
                }

            val regionRight =
                if (i == cleaned.lastIndex) {
                    w - 1
                } else {
                    (
                        cluster.center +
                            cleaned[i + 1].center
                    ) / 2
                }

            val startX = max(
                0,
                regionLeft
            )

            val endX = min(
                w - 1,
                regionRight
            )

            if (endX < startX) continue

            val rowCounts = IntArray(h)

            var minY = h
            var maxY = -1

            var totalPixels = 0

            // Count only this candle's color
            for (cx in startX..endX) {

                for (cy in 0 until h) {

                    if (
                        types[cy * w + cx]
                            .toInt() == cluster.type
                    ) {

                        rowCounts[cy]++

                        totalPixels++

                        minY = min(minY, cy)

                        maxY = max(maxY, cy)
                    }
                }
            }

            if (
                maxY < 0 ||
                totalPixels < 4 ||
                maxY - minY < 3
            ) {
                continue
            }

            val peakRow =
                rowCounts.maxOrNull() ?: 0

            if (peakRow < 1) continue

            // Dense rows usually belong to the candle body
            val bodyThreshold = max(
                1,
                (peakRow * 0.45).roundToInt()
            )

            var bodyTop = -1
            var bodyBottom = -1

            var bestRun = 0

            var runStart = -1
            var runLength = 0

            for (row in minY..maxY) {

                if (rowCounts[row] >= bodyThreshold) {

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
            ) {
                continue
            }

            val absoluteHigh = minY + top
            val absoluteLow = maxY + top

            val absoluteBodyTop = bodyTop + top
            val absoluteBodyBottom = bodyBottom + top

            val bullish = cluster.type == 1

            val open: Double
            val close: Double

            if (bullish) {

                open = -absoluteBodyBottom.toDouble()

                close = -absoluteBodyTop.toDouble()

            } else {

                open = -absoluteBodyTop.toDouble()

                close = -absoluteBodyBottom.toDouble()
            }

            val bodySize = max(
                1,
                absoluteBodyBottom -
                    absoluteBodyTop + 1
            ).toDouble()

            result.add(
                Bar(
                    bullish = bullish,
                    open = open,
                    close = close,
                    high = -absoluteHigh.toDouble(),
                    low = -absoluteLow.toDouble(),
                    body = bodySize,

                    upperWick = max(
                        0,
                        absoluteBodyTop -
                            absoluteHigh
                    ).toDouble(),

                    lowerWick = max(
                        0,
                        absoluteLow -
                            absoluteBodyBottom
                    ).toDouble(),

                    centerX = left + cluster.center
                )
            )
        }

        return result
            .sortedBy { it.centerX }
            .distinctBy { it.centerX }
    }

    // ─────────────────────────────
    // PATTERN DETECTION
    // ─────────────────────────────

    private fun detectPattern(
        a: Bar,
        b: Bar,
        priorDowntrend: Boolean,
        priorUptrend: Boolean
    ): Pattern {

        val body = b.body.coerceAtLeast(1.0)

        val range = b.range

        val upper = b.upperWick

        val lower = b.lowerWick

        if (b.bodyRatio <= 0.12) {
            return Pattern("DOJI", 0, 0)
        }

        // Bullish Engulfing
        if (
            b.bullish &&
            !a.bullish &&
            b.open <= a.close &&
            b.close >= a.open &&
            b.body >= a.body * 0.90
        ) {
            return Pattern(
                "BULLISH ENGULFING",
                1,
                2
            )
        }

        // Bearish Engulfing
        if (
            !b.bullish &&
            a.bullish &&
            b.open >= a.close &&
            b.close <= a.open &&
            b.body >= a.body * 0.90
        ) {
            return Pattern(
                "BEARISH ENGULFING",
                -1,
                2
            )
        }

        val hammerShape =
            b.bodyRatio <= 0.35 &&
            lower >= body * 2.0 &&
            upper <= body * 0.70 &&
            (b.high - max(b.open, b.close)) <=
                range * 0.40

        if (
            hammerShape &&
            priorDowntrend
        ) {
            return Pattern(
                "HAMMER",
                1,
                2
            )
        }

        val invertedHammerShape =
            b.bodyRatio <= 0.35 &&
            upper >= body * 2.0 &&
            lower <= body * 0.70

        if (
            invertedHammerShape &&
            priorDowntrend
        ) {
            return Pattern(
                "INVERTED HAMMER",
                1,
                1
            )
        }

        if (
            invertedHammerShape &&
            priorUptrend
        ) {
            return Pattern(
                "SHOOTING STAR",
                -1,
                2
            )
        }

        if (
            hammerShape &&
            priorUptrend
        ) {
            return Pattern(
                "HANGING MAN",
                -1,
                1
            )
        }

        return Pattern(
            "NONE",
            0,
            0
        )
    }

    // ─────────────────────────────
    // SWING DETECTION
    // ─────────────────────────────

    private fun findSwings(
        bars: List<Bar>,
        high: Boolean
    ): List<Swing> {

        val result = mutableListOf<Swing>()

        val radius = 2

        for (
            i in radius until
                bars.size - radius
        ) {

            val price =
                if (high) {
                    bars[i].high
                } else {
                    bars[i].low
                }

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
                result.add(
                    Swing(i, price)
                )
            }
        }

        return result
    }

    // ─────────────────────────────
    // EMA
    // ─────────────────────────────

    private fun ema(
        values: List<Double>,
        period: Int
    ): Double {

        if (values.isEmpty()) {
            return 0.0
        }

        val multiplier =
            2.0 / (period + 1)

        var value = values.first()

        for (i in 1 until values.size) {

            value =
                values[i] * multiplier +
                    value * (1.0 - multiplier)
        }

        return value
    }

    // ─────────────────────────────
    // RESULT
    // ─────────────────────────────

    private fun result(
        signal: String,
        confidence: Int,
        green: Int,
        red: Int,
        message: String
    ): CandleSignal {

        return CandleSignal(
            signal = signal,
            confidence = confidence,
            greenCount = green,
            redCount = red,
            message = message
        )
    }
}