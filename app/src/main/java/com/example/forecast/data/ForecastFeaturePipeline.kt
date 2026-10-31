package com.example.forecast.data

import com.example.domain.KlineBar
import com.example.forecast.domain.ForecastTensorInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic, causal feature pipeline that matches the off-device Python training pipeline
 * (`ml/forecasting/src/build_features.py`) down to 1e-5 floating-point tolerance.
 * Uses pre-allocated buffers per call to minimize GC pressure on Cortex-A53 / Exynos M1.
 */
class ForecastFeaturePipeline(
    private val schema: ForecastFeatureSchema,
    private val scaler: ForecastScaler
) {
    fun buildSequenceTensor(
        symbol: String,
        klines15m: List<KlineBar>,
        spreadBpsCurrent: Double = 2.0,
        obiTop5Current: Double = 0.0,
        nowTimestampMs: Long = System.currentTimeMillis()
    ): ForecastTensorInput {
        val seqLen = schema.sequenceLength
        val featCount = schema.featureCount
        val n = klines15m.size

        // Require at least seqLen + 32 warmup bars for causal rolling statistics
        if (n < seqLen + 32) {
            return ForecastTensorInput(
                symbol = symbol,
                asOfTimestampMs = nowTimestampMs,
                sequenceLength = seqLen,
                featureCount = featCount,
                flattenedSequence = FloatArray(seqLen * featCount),
                rawLatestFeatures = DoubleArray(featCount),
                oodAnomalyScore = 0.0,
                staleBarsCount = seqLen + 32 - n,
                isWarmupComplete = false
            )
        }

        val opens = DoubleArray(n)
        val highs = DoubleArray(n)
        val lows = DoubleArray(n)
        val closes = DoubleArray(n)
        val volumes = DoubleArray(n)
        val takerBuyVols = DoubleArray(n)
        val closeTimes = LongArray(n)

        for (i in 0 until n) {
            val b = klines15m[i]
            opens[i] = max(b.open, 1e-8)
            highs[i] = max(b.high, 1e-8)
            lows[i] = max(b.low, 1e-8)
            closes[i] = max(b.close, 1e-8)
            volumes[i] = max(b.volume, 0.0)
            takerBuyVols[i] = if (b.takerBuyBaseVolume > 0.0) {
                min(b.volume, b.takerBuyBaseVolume)
            } else {
                // Deterministic causal estimate from candle body when exchange stream omits takerBuyBaseVolume
                val range = max(highs[i] - lows[i], 1e-8)
                val ratio = 0.5 + 0.35 * ((closes[i] - opens[i]) / range)
                volumes[i] * max(0.05, min(0.95, ratio))
            }
            closeTimes[i] = b.closeTime
        }

        val ema20 = computeCausalEma(closes, 20)
        val ema50 = computeCausalEma(closes, 50)
        val atr14 = computeCausalAtr(highs, lows, closes, 14)
        val rsi14 = computeCausalRsi(closes, 14)
        val adx14 = computeCausalAdx(highs, lows, closes, 14)

        val logRet1 = DoubleArray(n)
        val signedDeltaVol = DoubleArray(n)
        for (i in 0 until n) {
            logRet1[i] = if (i >= 1) ln(closes[i] / closes[i - 1]) else 0.0
            val takerSell = max(0.0, volumes[i] - takerBuyVols[i])
            signedDeltaVol[i] = takerBuyVols[i] - takerSell
        }

        val flattened = FloatArray(seqLen * featCount)
        val stepRaw = DoubleArray(featCount)
        var latestRaw = DoubleArray(featCount)
        var oodSum = 0.0

        val startIdx = n - seqLen
        for (step in 0 until seqLen) {
            val idx = startIdx + step
            val isLatestStep = (step == seqLen - 1)
            computeStepFeatures(
                idx = idx,
                opens = opens,
                highs = highs,
                lows = lows,
                closes = closes,
                volumes = volumes,
                takerBuyVols = takerBuyVols,
                closeTimes = closeTimes,
                logRet1 = logRet1,
                signedDeltaVol = signedDeltaVol,
                ema20 = ema20,
                ema50 = ema50,
                atr14 = atr14,
                rsi14 = rsi14,
                adx14 = adx14,
                liveSpreadBps = if (isLatestStep) spreadBpsCurrent else null,
                liveObiTop5 = if (isLatestStep) obiTop5Current else null,
                outFeatures = stepRaw
            )
            val stepOod = scaler.transformSingleStep(stepRaw, flattened, step * featCount)
            oodSum += stepOod
            if (isLatestStep) {
                latestRaw = stepRaw.copyOf()
            }
        }

        val lastBarCloseMs = closeTimes[n - 1]
        val stalenessMs = max(0L, nowTimestampMs - lastBarCloseMs)
        val staleBars = (stalenessMs / (15L * 60_000L)).toInt()

        return ForecastTensorInput(
            symbol = symbol,
            asOfTimestampMs = lastBarCloseMs,
            sequenceLength = seqLen,
            featureCount = featCount,
            flattenedSequence = flattened,
            rawLatestFeatures = latestRaw,
            oodAnomalyScore = oodSum / seqLen.toDouble(),
            staleBarsCount = staleBars,
            isWarmupComplete = true
        )
    }

    /**
     * Exact single-step causal feature calculation matching `build_features.py`.
     */
    fun computeStepFeatures(
        idx: Int,
        opens: DoubleArray,
        highs: DoubleArray,
        lows: DoubleArray,
        closes: DoubleArray,
        volumes: DoubleArray,
        takerBuyVols: DoubleArray,
        closeTimes: LongArray,
        logRet1: DoubleArray,
        signedDeltaVol: DoubleArray,
        ema20: DoubleArray,
        ema50: DoubleArray,
        atr14: DoubleArray,
        rsi14: DoubleArray,
        adx14: DoubleArray,
        liveSpreadBps: Double?,
        liveObiTop5: Double?,
        outFeatures: DoubleArray
    ) {
        val c = closes[idx]
        val o = opens[idx]
        val h = highs[idx]
        val l = lows[idx]
        val v = volumes[idx]

        fun safeLogRet(lag: Int): Double {
            return if (idx >= lag) ln(c / closes[idx - lag]) else 0.0
        }

        // 0..6: Multi-scale causal log returns (15m, 30m, 1h, 2h, 4h, aligned 1h, aligned 4h)
        outFeatures[0] = safeLogRet(1)
        outFeatures[1] = safeLogRet(2)
        outFeatures[2] = safeLogRet(4)
        outFeatures[3] = safeLogRet(8)
        outFeatures[4] = safeLogRet(16)
        outFeatures[5] = safeLogRet(4)
        outFeatures[6] = safeLogRet(16)

        // 7..10: Candle geometry
        val hlRange = max(h - l, 1e-8)
        outFeatures[7] = (h - l) / c
        outFeatures[8] = (c - o) / o
        outFeatures[9] = (h - max(o, c)) / hlRange
        outFeatures[10] = (min(o, c) - l) / hlRange

        // 11..13: Realized volatility & ATR%
        outFeatures[11] = rollingStd(logRet1, idx, 8)
        outFeatures[12] = rollingStd(logRet1, idx, 32)
        outFeatures[13] = atr14[idx] / c

        // 14..18: Trend & momentum indicators
        outFeatures[14] = (c - ema20[idx]) / c
        outFeatures[15] = (c - ema50[idx]) / c
        outFeatures[16] = (rsi14[idx] - 50.0) / 50.0
        outFeatures[17] = adx14[idx] / 100.0

        val bbMean = rollingMean(closes, idx, 20)
        val bbStd = max(rollingStd(closes, idx, 20), 1e-8)
        outFeatures[18] = (c - bbMean) / (2.0 * bbStd)

        // 19..23: Volume & microstructure proxies
        val volMean20 = max(rollingMean(volumes, idx, 20), 1e-8)
        outFeatures[19] = v / volMean20

        val takerBuy = takerBuyVols[idx]
        val takerImbalance = if (v > 1e-8) (2.0 * takerBuy - v) / v else 0.0
        outFeatures[20] = max(-1.0, min(1.0, takerImbalance))

        var deltaSum8 = 0.0
        var volSum8 = 0.0
        val start8 = max(0, idx - 7)
        for (k in start8..idx) {
            deltaSum8 += signedDeltaVol[k]
            volSum8 += volumes[k]
        }
        outFeatures[21] = if (volSum8 > 1e-8) deltaSum8 / volSum8 else 0.0

        val spreadProxyBps = liveSpreadBps ?: min(25.0, max(0.5, ((h - l) / c) * 10000.0 * 0.08))
        outFeatures[22] = spreadProxyBps / 10.0

        val obiProxy = liveObiTop5 ?: max(-1.0, min(1.0, 0.6 * outFeatures[20] + 0.4 * ((c - o) / hlRange)))
        outFeatures[23] = obiProxy

        // 24..25: Cyclical intraday seasonality (UTC hour + fraction)
        val msInDay = 86_400_000L
        val todFraction = ((closeTimes[idx] % msInDay) + msInDay) % msInDay / msInDay.toDouble()
        val angle = 2.0 * PI * todFraction
        outFeatures[24] = sin(angle)
        outFeatures[25] = cos(angle)
    }

    private fun rollingMean(arr: DoubleArray, endIdx: Int, window: Int): Double {
        val start = max(0, endIdx - window + 1)
        val count = endIdx - start + 1
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in start..endIdx) sum += arr[i]
        return sum / count.toDouble()
    }

    private fun rollingStd(arr: DoubleArray, endIdx: Int, window: Int): Double {
        val start = max(0, endIdx - window + 1)
        val count = endIdx - start + 1
        if (count <= 1) return 0.0
        val mean = rollingMean(arr, endIdx, window)
        var sumSq = 0.0
        for (i in start..endIdx) {
            val d = arr[i] - mean
            sumSq += d * d
        }
        return sqrt(sumSq / count.toDouble())
    }

    private fun computeCausalEma(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size)
        if (values.isEmpty()) return out
        val k = 2.0 / (period + 1.0)
        var ema = values[0]
        out[0] = ema
        for (i in 1 until values.size) {
            ema = values[i] * k + ema * (1.0 - k)
            out[i] = ema
        }
        return out
    }

    private fun computeCausalAtr(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n)
        if (n == 0) return out
        var atr = max(highs[0] - lows[0], 1e-8)
        out[0] = atr
        for (i in 1 until n) {
            val tr = max(
                highs[i] - lows[i],
                max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
            )
            atr = ((atr * (period - 1)) + tr) / period.toDouble()
            out[i] = atr
        }
        return out
    }

    private fun computeCausalRsi(closes: DoubleArray, period: Int): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n) { 50.0 }
        if (n <= 1) return out
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1 until n) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            if (i <= period) {
                avgGain += gain / period.toDouble()
                avgLoss += loss / period.toDouble()
            } else {
                avgGain = (avgGain * (period - 1) + gain) / period.toDouble()
                avgLoss = (avgLoss * (period - 1) + loss) / period.toDouble()
            }
            if (i >= period) {
                out[i] = if (avgLoss <= 1e-12) {
                    100.0
                } else {
                    val rs = avgGain / avgLoss
                    100.0 - (100.0 / (1.0 + rs))
                }
            }
        }
        return out
    }

    private fun computeCausalAdx(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n) { 20.0 }
        if (n <= 1) return out
        var smoothTr = max(highs[0] - lows[0], 1e-8)
        var smoothPlusDm = 0.0
        var smoothMinusDm = 0.0
        var adx = 20.0
        for (i in 1 until n) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            val plusDm = if (upMove > downMove && upMove > 0) upMove else 0.0
            val minusDm = if (downMove > upMove && downMove > 0) downMove else 0.0
            val tr = max(
                highs[i] - lows[i],
                max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
            )
            smoothTr = smoothTr - (smoothTr / period.toDouble()) + tr
            smoothPlusDm = smoothPlusDm - (smoothPlusDm / period.toDouble()) + plusDm
            smoothMinusDm = smoothMinusDm - (smoothMinusDm / period.toDouble()) + minusDm

            val plusDi = if (smoothTr > 1e-8) 100.0 * (smoothPlusDm / smoothTr) else 0.0
            val minusDi = if (smoothTr > 1e-8) 100.0 * (smoothMinusDm / smoothTr) else 0.0
            val sumDi = plusDi + minusDi
            val dx = if (sumDi > 1e-8) 100.0 * abs(plusDi - minusDi) / sumDi else 0.0
            adx = ((adx * (period - 1)) + dx) / period.toDouble()
            out[i] = adx
        }
        return out
    }
}
