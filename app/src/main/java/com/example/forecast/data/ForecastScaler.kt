package com.example.forecast.data

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
data class FeatureScalerStats(
    val index: Int,
    val name: String,
    val mean: Double,
    val std: Double,
    val median: Double,
    val iqr: Double,
    val p01: Double,
    val p99: Double,
    val heavyTailed: Boolean,
    val clipMin: Double,
    val clipMax: Double
)

@Serializable
data class ForecastScalerConfig(
    val version: String,
    val scalerHash: String,
    val stats: List<FeatureScalerStats>
)

class ForecastScaler(
    val config: ForecastScalerConfig
) {
    /**
     * Scales a raw feature vector in-place or into [outBuffer] with zero heap allocations
     * when [outBuffer] is pre-allocated. Also returns an OOD (out-of-distribution) score
     * based on average absolute Z-score excess beyond 3.0 sigma.
     */
    fun transformSingleStep(rawFeatures: DoubleArray, outBuffer: FloatArray, outOffset: Int): Double {
        var oodExcessSum = 0.0
        val count = min(rawFeatures.size, config.stats.size)
        for (i in 0 until count) {
            val st = config.stats[i]
            val rawVal = rawFeatures[i]
            val sanitized = if (rawVal.isNaN() || rawVal.isInfinite()) st.median else rawVal
            val clipped = max(st.clipMin, min(st.clipMax, sanitized))

            val scaled = if (st.heavyTailed) {
                val denom = if (st.iqr > 1e-8) st.iqr else 1.0
                (clipped - st.median) / denom
            } else {
                val denom = if (st.std > 1e-8) st.std else 1.0
                (clipped - st.mean) / denom
            }

            val zAbs = abs(scaled)
            if (zAbs > 3.0) {
                oodExcessSum += (zAbs - 3.0)
            }
            val bounded = max(-5.0, min(5.0, scaled))
            outBuffer[outOffset + i] = bounded.toFloat()
        }
        return if (count > 0) oodExcessSum / count.toDouble() else 0.0
    }
}
