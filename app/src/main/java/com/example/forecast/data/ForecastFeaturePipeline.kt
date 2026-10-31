package com.example.forecast.data

import com.example.domain.CandleBar
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.ForecastTensorInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Causal 26-Feature Pipeline for Astra Trader V5.1 Forecasting Engine (`ForecastFeaturePipeline.kt`).
 * Matches Python `ml/forecasting/src/build_features.py` within `1e-5f` numerical tolerance.
 * - Strictly uses closed candles (`isClosed == true`) with `closeTime <= currentBar.closeTime`.
 * - Never uses approximated `ret15m / 3` or `ret15m / 15`.
 */
object ForecastFeaturePipeline {

    data class MicrostructureBarExtras(
        val bidQty: Double? = null,
        val askQty: Double? = null,
        val fundingRate: Double? = null
    )

    data class FeatureBuildOutcome(
        val status: ForecastStatus,
        val tensorInput: ForecastTensorInput?,
        val errorReasonVi: String?
    )

    private fun safeLogReturn(prevVal: Double, currVal: Double): Double {
        if (prevVal <= 1e-12 || currVal <= 1e-12) return 0.0
        return ln(currVal / prevVal)
    }

    private fun round7(v: Double): Float {
        if (v.isNaN() || v.isInfinite()) return Float.NaN
        return ((v * 10_000_000.0).roundToLong().toDouble() / 10_000_000.0).toFloat()
    }

    /**
     * Computes the 26-feature causal row for index `t` using only `candles15m[0..t]` and
     * higher-timeframe candles with `closeTime <= candles15m[t].closeTime`.
     */
    fun computeCausalFeatureRowAt(
        candles15m: List<CandleBar>,
        t: Int,
        candles1hAll: List<CandleBar>,
        candles4hAll: List<CandleBar>,
        extrasByOpenTime: Map<Long, MicrostructureBarExtras> = emptyMap(),
        latestOrderBookImbalanceOverride: Double? = null,
        latestFundingRateOverride: Double? = null
    ): FloatArray {
        val curr = candles15m[t]
        val currCloseTime = curr.closeTime

        val cT = max(curr.close.toDouble(), 1e-8)
        val oT = max(curr.open.toDouble(), 1e-8)
        val hT = curr.high.toDouble()
        val lT = curr.low.toDouble()

        // 0..4: Multi-step log returns
        val ret1 = if (t >= 1) safeLogReturn(candles15m[t - 1].close.toDouble(), cT) else 0.0
        val ret2 = if (t >= 2) safeLogReturn(candles15m[t - 2].close.toDouble(), cT) else 0.0
        val ret4 = if (t >= 4) safeLogReturn(candles15m[t - 4].close.toDouble(), cT) else 0.0
        val ret8 = if (t >= 8) safeLogReturn(candles15m[t - 8].close.toDouble(), cT) else 0.0
        val ret16 = if (t >= 16) safeLogReturn(candles15m[t - 16].close.toDouble(), cT) else 0.0

        // 5..9: Candle geometry
        val span = max(hT - lT, 1e-8)
        val hlRangePct = (hT - lT) / cT
        val coReturnPct = (cT - oT) / oT
        val bodyRatio = abs(cT - oT) / span
        val upperWickRatio = max(0.0, hT - max(oT, cT)) / span
        val lowerWickRatio = max(0.0, min(oT, cT) - lT) / span

        // 10..12 & 14: EMA20, EMA50, EMA20 slope, MACD histogram (12, 26, 9)
        val k20 = 2.0 / 21.0
        val k50 = 2.0 / 51.0
        val k12 = 2.0 / 13.0
        val k26 = 2.0 / 27.0
        val k9 = 2.0 / 10.0

        val emaStart = max(0, t - 50)
        val c0 = candles15m[emaStart].close.toDouble()
        var ema20 = c0
        var prevEma20 = c0
        var ema50 = c0
        var ema12 = c0
        var ema26 = c0
        var macdSig = 0.0

        for (i in (emaStart + 1)..t) {
            prevEma20 = ema20
            val px = candles15m[i].close.toDouble()
            ema20 = px * k20 + ema20 * (1.0 - k20)
            ema50 = px * k50 + ema50 * (1.0 - k50)
            ema12 = px * k12 + ema12 * (1.0 - k12)
            ema26 = px * k26 + ema26 * (1.0 - k26)
            val macdLine = ema12 - ema26
            macdSig = if (i == emaStart + 1) {
                macdLine
            } else {
                macdLine * k9 + macdSig * (1.0 - k9)
            }
        }

        val ema20Dist = (cT - ema20) / cT
        val ema50Dist = (cT - ema50) / cT
        val ema20Slope = (ema20 - prevEma20) / max(prevEma20, 1e-8)

        // 13: RSI-14 normalized to [-1, 1]
        val rsiWin = min(14, t)
        val rsi = if (rsiWin >= 1) {
            var gains = 0.0
            var losses = 0.0
            for (i in (t - rsiWin + 1)..t) {
                val diff = candles15m[i].close.toDouble() - candles15m[i - 1].close.toDouble()
                if (diff > 0.0) gains += diff else losses -= diff
            }
            val avgGain = gains / rsiWin.toDouble()
            val avgLoss = losses / rsiWin.toDouble()
            when {
                avgLoss <= 1e-12 && avgGain <= 1e-12 -> 50.0
                avgLoss <= 1e-12 -> 100.0
                else -> {
                    val rs = avgGain / avgLoss
                    100.0 - (100.0 / (1.0 + rs))
                }
            }
        } else {
            50.0
        }
        val rsi14Norm = (rsi - 50.0) / 50.0

        // 14: MACD histogram %
        val macdHistPct = ((ema12 - ema26) - macdSig) / cT

        // 15..16: Bollinger %B and Bollinger Width % (20-bar window)
        val bbStart = max(0, t - 19)
        val bbLen = (t - bbStart + 1)
        var sumClose20 = 0.0
        for (i in bbStart..t) {
            sumClose20 += candles15m[i].close.toDouble()
        }
        val sma20 = sumClose20 / bbLen.toDouble()
        val std20 = if (bbLen >= 2) {
            var varSum = 0.0
            for (i in bbStart..t) {
                val d = candles15m[i].close.toDouble() - sma20
                varSum += d * d
            }
            sqrt(max(varSum / (bbLen - 1).toDouble(), 0.0))
        } else {
            0.0
        }
        val bandSpan = max(4.0 * std20, 1e-8)
        val bbLower = sma20 - 2.0 * std20
        val bbPercentB = ((cT - bbLower) / bandSpan) - 0.5
        val bbWidthPct = (4.0 * std20) / max(sma20, 1e-8)

        // 17: ATR-14 %
        val atrWin = min(14, t)
        val atr14 = if (atrWin >= 1) {
            var trSum = 0.0
            for (i in (t - atrWin + 1)..t) {
                val hi = candles15m[i].high.toDouble()
                val lo = candles15m[i].low.toDouble()
                val pc = candles15m[i - 1].close.toDouble()
                val tr = max(abs(hi - lo), max(abs(hi - pc), abs(lo - pc)))
                trSum += tr
            }
            trSum / atrWin.toDouble()
        } else {
            max(hT - lT, 0.0)
        }
        val atr14Pct = atr14 / cT

        // 18: EWMA Volatility (20-bar return window, lambda = 0.94)
        val retWinStart = max(1, t - 19)
        val ewmaVol20 = if (t >= retWinStart) {
            val r0 = safeLogReturn(candles15m[retWinStart - 1].close.toDouble(), candles15m[retWinStart].close.toDouble())
            var ewmaVar = r0 * r0
            val lam = 0.94
            for (i in (retWinStart + 1)..t) {
                val r = safeLogReturn(candles15m[i - 1].close.toDouble(), candles15m[i].close.toDouble())
                ewmaVar = lam * ewmaVar + (1.0 - lam) * (r * r)
            }
            sqrt(max(ewmaVar, 0.0))
        } else {
            0.0
        }

        // 19: Log Relative Volume (vs prior 20 closed bars)
        val volStart = max(0, t - 20)
        val volEndExclusive = if (t >= 1) t else 1
        var volSum = 0.0
        val volCount = max(1, volEndExclusive - volStart)
        for (i in volStart until volEndExclusive) {
            volSum += candles15m[i].volume.toDouble()
        }
        val smaVol20 = volSum / volCount.toDouble()
        val rvolRatio = curr.volume.toDouble() / max(smaVol20, 1e-8)
        val rvol20 = ln(max(rvolRatio, 1e-4))

        // 20..21: Higher-timeframe closed returns strictly with closeTime <= currCloseTime
        val closed1h = candles1hAll.filter { it.isClosed && it.closeTime <= currCloseTime }
        val htf1hRet = if (closed1h.size >= 2) {
            safeLogReturn(
                closed1h[closed1h.size - 2].close.toDouble(),
                closed1h[closed1h.size - 1].close.toDouble()
            )
        } else {
            ret4
        }

        val closed4h = candles4hAll.filter { it.isClosed && it.closeTime <= currCloseTime }
        val htf4hRet = if (closed4h.size >= 2) {
            safeLogReturn(
                closed4h[closed4h.size - 2].close.toDouble(),
                closed4h[closed4h.size - 1].close.toDouble()
            )
        } else {
            ret16
        }

        // 22..23: Cyclical UTC time of day
        val minuteOfDay = ((currCloseTime / 60_000L) % 1440L).toDouble()
        val angle = 2.0 * PI * (minuteOfDay / 1440.0)
        val todSin = sin(angle)
        val todCos = cos(angle)

        // 24..25: Microstructure & Derivatives features
        val barExtras = extrasByOpenTime[curr.openTime]
        val obi = when {
            barExtras?.bidQty != null && barExtras.askQty != null -> {
                val bq = barExtras.bidQty
                val aq = barExtras.askQty
                (bq - aq) / max(bq + aq, 1e-8)
            }
            t == candles15m.lastIndex && latestOrderBookImbalanceOverride != null -> latestOrderBookImbalanceOverride
            else -> 0.0
        }

        val frScaled = when {
            barExtras?.fundingRate != null -> barExtras.fundingRate * 1000.0
            t == candles15m.lastIndex && latestFundingRateOverride != null -> latestFundingRateOverride * 1000.0
            else -> 0.0
        }

        return floatArrayOf(
            round7(ret1),
            round7(ret2),
            round7(ret4),
            round7(ret8),
            round7(ret16),
            round7(hlRangePct),
            round7(coReturnPct),
            round7(bodyRatio),
            round7(upperWickRatio),
            round7(lowerWickRatio),
            round7(ema20Dist),
            round7(ema50Dist),
            round7(ema20Slope),
            round7(rsi14Norm),
            round7(macdHistPct),
            round7(bbPercentB),
            round7(bbWidthPct),
            round7(atr14Pct),
            round7(ewmaVol20),
            round7(rvol20),
            round7(htf1hRet),
            round7(htf4hRet),
            round7(todSin),
            round7(todCos),
            round7(obi),
            round7(frScaled)
        )
    }

    /**
     * Builds the scaled `[1, 64, 26]` `ForecastTensorInput` from aligned multi-timeframe candles.
     */
    fun buildSequenceTensor(
        symbol: String,
        alignedWindow: MarketSequenceRepository.AlignedMultiTimeframeWindow,
        schema: ForecastFeatureSchema,
        scaler: ForecastScaler,
        featureSchemaHash: String,
        extrasByOpenTime: Map<Long, MicrostructureBarExtras> = emptyMap(),
        latestOrderBookImbalanceOverride: Double? = null,
        latestFundingRateOverride: Double? = null,
        preallocatedScaledBuffer: FloatArray? = null
    ): FeatureBuildOutcome {
        if (!alignedWindow.isValid) {
            return FeatureBuildOutcome(
                status = alignedWindow.failureStatus ?: ForecastStatus.INSUFFICIENT_DATA,
                tensorInput = null,
                errorReasonVi = alignedWindow.failureReasonVi
            )
        }
        val c15 = alignedWindow.closed15m
        val lookback = schema.lookbackLength
        val fCount = schema.featureCount
        if (c15.size < lookback) {
            return FeatureBuildOutcome(
                status = ForecastStatus.INSUFFICIENT_DATA,
                tensorInput = null,
                errorReasonVi = "Chuỗi nến đóng 15m (${c15.size}) nhỏ hơn lookback=$lookback."
            )
        }

        val startIdx = c15.size - lookback
        val rawWindow = Array(lookback) { step ->
            val t = startIdx + step
            computeCausalFeatureRowAt(
                candles15m = c15,
                t = t,
                candles1hAll = alignedWindow.closed1h,
                candles4hAll = alignedWindow.closed4h,
                extrasByOpenTime = extrasByOpenTime,
                latestOrderBookImbalanceOverride = if (t == c15.lastIndex) latestOrderBookImbalanceOverride else null,
                latestFundingRateOverride = if (t == c15.lastIndex) latestFundingRateOverride else null
            )
        }

        // Verify zero NaN/Inf in rawWindow
        for (step in 0 until lookback) {
            val row = rawWindow[step]
            if (row.size != fCount) {
                return FeatureBuildOutcome(
                    status = ForecastStatus.FEATURE_INVALID,
                    tensorInput = null,
                    errorReasonVi = "Chiều đặc trưng tại bước $step (${row.size}) khác $fCount."
                )
            }
            for (f in 0 until fCount) {
                if (row[f].isNaN() || row[f].isInfinite()) {
                    return FeatureBuildOutcome(
                        status = ForecastStatus.FEATURE_INVALID,
                        tensorInput = null,
                        errorReasonVi = "Đặc trưng thứ $f tại bước $step chứa NaN hoặc Infinite."
                    )
                }
            }
        }

        val scaledRes = scaler.scaleSequenceWindow(rawWindow, preallocatedScaledBuffer)

        return FeatureBuildOutcome(
            status = ForecastStatus.READY_VALIDATED,
            tensorInput = ForecastTensorInput(
                symbol = symbol,
                baseTimeframe = schema.baseTimeframe,
                asOfCloseTimeMs = alignedWindow.asOfCloseTimeMs,
                lookbackLength = lookback,
                featureCount = fCount,
                featureSchemaHash = featureSchemaHash,
                flattenedScaledFeatures = scaledRes.flattenedScaled,
                lastRawFeatureRow = rawWindow.last(),
                lastScaledFeatureRow = scaledRes.lastScaledRow,
                outOfDistributionScore = scaledRes.outOfDistributionScore
            ),
            errorReasonVi = null
        )
    }
}
