package com.example.forecast.runtime

import android.os.SystemClock
import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastTensorInput
import com.example.forecast.domain.RawForecastOutput
import com.example.forecast.domain.RawHorizonOutput
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

@Serializable
data class CompactHorizonHeadWeights(
    val horizonMinutes: Int,
    val wCls: List<List<Double>>, // [3][H]
    val bCls: List<Double>,       // [3]
    val wRet: List<Double>,       // [H]
    val bRet: Double,
    val wQ10: List<Double>,       // [H]
    val bQ10: Double,
    val wQ50: List<Double>,       // [H]
    val bQ50: Double,
    val wQ90: List<Double>,       // [H]
    val bQ90: Double
)

@Serializable
data class CompactModelWeights(
    val modelId: String,
    val modelVersion: String,
    val artifactFormat: String = "WEIGHTS_COMPACT_JSON_INT8",
    val inputDim: Int,
    val hiddenDim: Int,
    val wProj: List<List<Double>>,       // [H][F]
    val bProj: List<Double>,             // [H]
    val wGate: List<List<Double>>,       // [H][F]
    val uGate: List<List<Double>>,       // [H][H]
    val bGate: List<Double>,             // [H]
    val wCand: List<List<Double>>,       // [H][F]
    val uCand: List<List<Double>>,       // [H][H]
    val bCand: List<Double>,             // [H]
    val wTcnBranch: List<List<Double>>,  // [H][F]
    val bTcnBranch: List<Double>,        // [H]
    val heads: Map<String, CompactHorizonHeadWeights>
)

@Serializable
data class GoldenFeatureVectorItem(
    val sampleId: String,
    val symbol: String,
    val timestampMs: Long,
    val isTestFixture: Boolean = true,
    val dataTrustTag: String = "TEST_FIXTURE",
    val flattenedSequence: List<Float>
)

@Serializable
data class GoldenOutputHorizon(
    val rawLogits: List<Double>,
    val calibratedProbs: List<Double> = emptyList(),
    val expectedReturnPct: Double,
    val q10Pct: Double,
    val q50Pct: Double,
    val q90Pct: Double,
    val headConsistent: Boolean = true
)

@Serializable
data class GoldenOutputItem(
    val sampleId: String,
    val symbol: String = "BTCUSDT",
    val isTestFixture: Boolean = true,
    val dataTrustTag: String = "TEST_FIXTURE",
    val horizons: Map<String, GoldenOutputHorizon>
)

/**
 * Pure-Kotlin / NEON-friendly compact recurrent + dilated causal TCN hybrid runtime
 * targeting Samsung Galaxy S7 Edge (arm64-v8a, Exynos 8890, 4 GB RAM, API 30).
 *
 * Uses pre-allocated scratch buffers synchronized per inference call so there is zero
 * per-step heap allocation during sequence scanning, avoiding GC pauses and native
 * JNI / GPU delegate crashes on Mali-T880.
 */
class CompactKotlinForecastRuntime(
    val bundle: ForecastModelBundle,
    private val weights: CompactModelWeights
) {
    private val hDim = weights.hiddenDim
    private val fDim = weights.inputDim

    // Pre-allocated scratch buffers (guarded by inferenceLock)
    private val inferenceLock = Any()
    private val hState = DoubleArray(hDim)
    private val hPrevStep = DoubleArray(hDim)
    private val hMeanPool = DoubleArray(hDim)
    private val tcnAccum = DoubleArray(hDim)
    private val contextVec = DoubleArray(hDim)

    fun runInference(input: ForecastTensorInput): RawForecastOutput {
        val startNs = try {
            SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }

        synchronized(inferenceLock) {
            val seqLen = input.sequenceLength
            val featCount = input.featureCount
            val flat = input.flattenedSequence

            for (j in 0 until hDim) {
                hState[j] = 0.0
                hMeanPool[j] = 0.0
                tcnAccum[j] = 0.0
            }

            // Dilated TCN sample steps at causal lags (t-15, t-7, t-3, t-1, t)
            val tcnSteps = intArrayOf(
                max(0, seqLen - 16),
                max(0, seqLen - 8),
                max(0, seqLen - 4),
                max(0, seqLen - 2),
                seqLen - 1
            )
            val tcnWeights = doubleArrayOf(0.10, 0.15, 0.20, 0.25, 0.30)

            for (step in 0 until seqLen) {
                val baseIdx = step * featCount
                for (j in 0 until hDim) {
                    hPrevStep[j] = hState[j]
                }

                for (j in 0 until hDim) {
                    var gateLinear = weights.bGate[j]
                    var candLinear = weights.bCand[j]
                    val wG = weights.wGate[j]
                    val wC = weights.wCand[j]
                    for (k in 0 until min(fDim, featCount)) {
                        val xk = flat[baseIdx + k].toDouble()
                        gateLinear += wG[k] * xk
                        candLinear += wC[k] * xk
                    }
                    val uG = weights.uGate[j]
                    val uC = weights.uCand[j]
                    for (m in 0 until hDim) {
                        val hm = hPrevStep[m]
                        gateLinear += uG[m] * hm
                        candLinear += uC[m] * hm
                    }
                    val z = sigmoid(gateLinear)
                    val cand = tanh(candLinear)
                    val newH = (1.0 - z) * hPrevStep[j] + z * cand
                    hState[j] = newH
                    hMeanPool[j] += newH / seqLen.toDouble()
                }
            }

            // Causal dilated TCN residual branch over multi-scale lag steps
            for (idx in tcnSteps.indices) {
                val step = tcnSteps[idx]
                val wStep = tcnWeights[idx]
                val baseIdx = step * featCount
                for (j in 0 until hDim) {
                    var sum = weights.bTcnBranch[j]
                    val wT = weights.wTcnBranch[j]
                    for (k in 0 until min(fDim, featCount)) {
                        sum += wT[k] * flat[baseIdx + k].toDouble()
                    }
                    tcnAccum[j] += wStep * tanh(sum)
                }
            }

            // Combine final GRU state, temporal mean pool, and dilated TCN residual
            var hiddenEnergy = 0.0
            for (j in 0 until hDim) {
                val combined = 0.50 * hState[j] + 0.25 * hMeanPool[j] + 0.25 * tcnAccum[j]
                contextVec[j] = combined
                hiddenEnergy += abs(combined)
            }
            val avgEnergy = hiddenEnergy / hDim.toDouble()

            val horizonOutputs = mutableMapOf<Int, RawHorizonOutput>()
            for (horizonMin in bundle.horizonsMinutes) {
                val head = weights.heads[horizonMin.toString()] ?: continue
                val logits = DoubleArray(3)
                for (c in 0 until 3) {
                    var s = head.bCls[c]
                    val wc = head.wCls[c]
                    for (j in 0 until hDim) {
                        s += wc[j] * contextVec[j]
                    }
                    logits[c] = s
                }

                var expRet = head.bRet
                var q10 = head.bQ10
                var q50 = head.bQ50
                var q90 = head.bQ90
                for (j in 0 until hDim) {
                    val cj = contextVec[j]
                    expRet += head.wRet[j] * cj
                    q10 += head.wQ10[j] * cj
                    q50 += head.wQ50[j] * cj
                    q90 += head.wQ90[j] * cj
                }

                // Enforce monotonic quantiles (q10 <= q50 <= q90)
                val sortedQ10 = min(q10, min(q50, q90))
                val sortedQ90 = max(q10, max(q50, q90))
                val sortedQ50 = max(sortedQ10, min(sortedQ90, q50))

                // Epistemic uncertainty proxy from logit margin & hidden activation dispersion
                val maxLogit = max(logits[0], max(logits[1], logits[2]))
                val minLogit = min(logits[0], min(logits[1], logits[2]))
                val margin = max(0.05, maxLogit - minLogit)
                val epistemic = max(0.01, min(1.0, 0.35 / (margin + 0.35) + 0.10 * max(0.0, avgEnergy - 0.85)))

                horizonOutputs[horizonMin] = RawHorizonOutput(
                    horizonMinutes = horizonMin,
                    rawLogits = logits,
                    expectedReturnPct = expRet,
                    q10Pct = sortedQ10,
                    q50Pct = sortedQ50,
                    q90Pct = sortedQ90,
                    epistemicUncertainty = epistemic
                )
            }

            val endNs = try {
                SystemClock.elapsedRealtimeNanos()
            } catch (_: Throwable) {
                System.nanoTime()
            }
            val latencyMs = max(1L, (endNs - startNs) / 1_000_000L)

            return RawForecastOutput(
                modelId = bundle.modelId,
                modelVersion = bundle.modelVersion,
                symbol = input.symbol,
                asOfTimestampMs = input.asOfTimestampMs,
                inferenceLatencyMs = latencyMs,
                horizonOutputs = horizonOutputs
            )
        }
    }

    private fun sigmoid(x: Double): Double {
        return when {
            x >= 16.0 -> 0.99999988
            x <= -16.0 -> 0.00000012
            else -> 1.0 / (1.0 + exp(-x))
        }
    }
}

typealias LiteRtForecastRuntime = CompactKotlinForecastRuntime
