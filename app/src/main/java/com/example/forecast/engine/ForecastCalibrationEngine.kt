package com.example.forecast.engine

import com.example.forecast.domain.HorizonForecast
import com.example.forecast.domain.RawHorizonOutput
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

@Serializable
data class HorizonCalibrationConfig(
    val horizonMinutes: Int,
    val temperature: Double,
    val biasShift: List<Double>,
    val validationBrierScore: Double,
    val validationEce: Double,
    val sampleSize: Int = 0,
    val qualificationStatus: String = "INSUFFICIENT_SAMPLE_SHADOW_ONLY"
)

@Serializable
data class ForecastCalibrationConfig(
    val version: String,
    val calibrationHash: String,
    val minQualifiedSampleSize: Int = 200,
    val horizons: Map<String, HorizonCalibrationConfig>
)

data class HeadConsistencyResult(
    val isConsistent: Boolean,
    val reason: String
)

/**
 * Applies regularized post-hoc temperature scaling, bounded prior bias adjustment,
 * normalized Shannon entropy calculation, and cross-head consistency verification
 * between the classification head (pDown, pFlat, pUp) and regression/quantile head
 * (expectedReturnPct, q10Pct, q50Pct, q90Pct).
 */
class ForecastCalibrationEngine(
    val config: ForecastCalibrationConfig
) {
    fun calibrateHorizon(raw: RawHorizonOutput): HorizonForecast {
        val hConfig = config.horizons[raw.horizonMinutes.toString()]
        // Guard against degenerate small-sample temperature collapse (< 0.75) or huge bias shifts
        val rawTemp = hConfig?.temperature ?: 1.0
        val temp = max(0.75, min(2.50, rawTemp))
        val bias = hConfig?.biasShift ?: listOf(0.0, 0.0, 0.0)

        val scaledLogits = DoubleArray(3)
        var maxLogit = Double.NEGATIVE_INFINITY
        for (c in 0 until 3) {
            val rawShift = bias.getOrElse(c) { 0.0 }
            val boundedShift = max(-0.25, min(0.25, rawShift))
            val adj = (raw.rawLogits[c] + boundedShift) / temp
            scaledLogits[c] = adj
            if (adj > maxLogit) maxLogit = adj
        }

        var sumExp = 0.0
        val probs = DoubleArray(3)
        for (c in 0 until 3) {
            val e = exp(scaledLogits[c] - maxLogit)
            probs[c] = e
            sumExp += e
        }
        for (c in 0 until 3) {
            probs[c] = max(1e-6, min(1.0 - 1e-6, probs[c] / sumExp))
        }
        val normSum = probs[0] + probs[1] + probs[2]
        val pDown = probs[0] / normSum
        val pFlat = probs[1] / normSum
        val pUp = probs[2] / normSum

        // Normalized Shannon entropy in [0, 1] where ln(3) is maximum entropy
        var entropy = 0.0
        for (p in doubleArrayOf(pDown, pFlat, pUp)) {
            if (p > 1e-12) {
                entropy -= p * ln(p)
            }
        }
        val maxEntropy = ln(3.0)
        val normalizedEntropy = max(0.0, min(1.0, entropy / maxEntropy))

        // Confidence combines directional conviction, low entropy, and narrow quantile interval
        val directionalEdge = abs(pUp - pDown)
        val intervalWidth = max(0.01, raw.q90Pct - raw.q10Pct)
        val intervalPenalty = min(0.45, intervalWidth / 6.0)
        val confidence = max(
            0.0,
            min(
                1.0,
                (1.0 - normalizedEntropy) * 0.55 + directionalEdge * 0.45 - intervalPenalty * 0.25
            )
        )

        val consistency = checkHeadConsistency(
            pDown = pDown,
            pFlat = pFlat,
            pUp = pUp,
            expectedReturnPct = raw.expectedReturnPct,
            q50Pct = raw.q50Pct
        )

        val sampleSize = hConfig?.sampleSize ?: 0
        val qualStatus = if (sampleSize < config.minQualifiedSampleSize) {
            "INSUFFICIENT_SAMPLE_SHADOW_ONLY"
        } else {
            hConfig?.qualificationStatus ?: "QUALIFIED"
        }

        return HorizonForecast(
            horizonMinutes = raw.horizonMinutes,
            pDown = pDown,
            pFlat = pFlat,
            pUp = pUp,
            calibratedLogits = scaledLogits.toList(),
            expectedReturnPct = raw.expectedReturnPct,
            q10Pct = raw.q10Pct,
            q50Pct = raw.q50Pct,
            q90Pct = raw.q90Pct,
            intervalWidthPct = intervalWidth,
            shannonEntropy = normalizedEntropy,
            normalizedConfidence = confidence,
            calibrationBrierScore = hConfig?.validationBrierScore ?: 0.22,
            calibrationEce = hConfig?.validationEce ?: 0.05,
            calibrationSampleSize = sampleSize,
            calibrationQualificationStatus = qualStatus,
            headConsistent = consistency.isConsistent,
            headInconsistencyReason = consistency.reason
        )
    }

    companion object {
        /**
         * Verifies that the direction probability head (pDown, pFlat, pUp) and the
         * expected return / median quantile head (expectedReturnPct, q50Pct) do not
         * contradict each other.
         */
        fun checkHeadConsistency(
            pDown: Double,
            pFlat: Double,
            pUp: Double,
            expectedReturnPct: Double,
            q50Pct: Double,
            returnDeadbandPct: Double = 0.04,
            strongProbThreshold: Double = 0.52
        ): HeadConsistencyResult {
            val netDir = pUp - pDown
            if (pDown >= strongProbThreshold && expectedReturnPct > returnDeadbandPct) {
                return HeadConsistencyResult(
                    isConsistent = false,
                    reason = "HEAD_CONTRADICTION_DOWN_PROB_VS_POS_RETURN(pDown=${"%.3f".format(pDown)}, expRet=${"%.4f".format(expectedReturnPct)}%)"
                )
            }
            if (pUp >= strongProbThreshold && expectedReturnPct < -returnDeadbandPct) {
                return HeadConsistencyResult(
                    isConsistent = false,
                    reason = "HEAD_CONTRADICTION_UP_PROB_VS_NEG_RETURN(pUp=${"%.3f".format(pUp)}, expRet=${"%.4f".format(expectedReturnPct)}%)"
                )
            }
            if (abs(netDir) >= 0.30 && netDir * expectedReturnPct < -0.025) {
                return HeadConsistencyResult(
                    isConsistent = false,
                    reason = "HEAD_SIGN_DIVERGENCE(netDir=${"%.3f".format(netDir)}, expRet=${"%.4f".format(expectedReturnPct)}%)"
                )
            }
            if (abs(expectedReturnPct - q50Pct) > 0.75) {
                return HeadConsistencyResult(
                    isConsistent = false,
                    reason = "HEAD_MEAN_MEDIAN_DIVERGENCE(expRet=${"%.4f".format(expectedReturnPct)}%, q50=${"%.4f".format(q50Pct)}%)"
                )
            }
            return HeadConsistencyResult(isConsistent = true, reason = "OK")
        }
    }
}
