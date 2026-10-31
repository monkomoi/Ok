package com.example.forecast.data

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Applies the frozen training scaler (`scaler.json`) to a `[64, 26]` raw feature sequence
 * and computes the Out-of-Distribution (`OOD`) score identically to `build_features.py`.
 */
class ForecastScaler(
    val schemaVersion: String,
    val scalerType: String,
    val featureCount: Int,
    val clipZ: Float,
    val means: FloatArray,
    val stds: FloatArray,
    val medians: FloatArray,
    val iqrs: FloatArray
) {

    data class ScaledSequenceResult(
        val flattenedScaled: FloatArray,
        val lastScaledRow: FloatArray,
        val outOfDistributionScore: Float
    )

    fun scaleSequenceWindow(
        rawSequence: Array<FloatArray>,
        preallocatedOutput: FloatArray? = null
    ): ScaledSequenceResult {
        val lookback = rawSequence.size
        val outSize = lookback * featureCount
        val flat = if (preallocatedOutput != null && preallocatedOutput.size == outSize) {
            preallocatedOutput
        } else {
            FloatArray(outSize)
        }
        val lastScaled = FloatArray(featureCount)

        for (t in 0 until lookback) {
            val row = rawSequence[t]
            val baseOffset = t * featureCount
            for (f in 0 until featureCount) {
                val z = (row[f].toDouble() - means[f].toDouble()) / max(stds[f].toDouble(), 1e-6)
                val zClipped = max(-clipZ.toDouble(), min(clipZ.toDouble(), z))
                // Round to 6 decimals to match Python export parity
                val rounded = ((zClipped * 1_000_000.0).roundToInt().toDouble() / 1_000_000.0).toFloat()
                flat[baseOffset + f] = rounded
                if (t == lookback - 1) {
                    lastScaled[f] = rounded
                }
            }
        }

        // Compute OOD score on the latest step of the window
        val lastRaw = rawSequence[lookback - 1]
        var extremeCount = 0
        var zSqSum = 0.0
        for (f in 0 until featureCount) {
            val rawZ = abs(lastRaw[f].toDouble() - means[f].toDouble()) / max(stds[f].toDouble(), 1e-6)
            if (rawZ > 3.8) {
                extremeCount++
            }
            val cappedZ = min(rawZ, 10.0)
            zSqSum += cappedZ * cappedZ
        }
        val rmsZ = sqrt(zSqSum / featureCount.toDouble())
        val extremeFrac = extremeCount.toDouble() / featureCount.toDouble()
        val rawOod = min(1.0, 0.55 * extremeFrac * 4.0 + 0.45 * max(0.0, (rmsZ - 1.5) / 3.5))
        val oodScore = ((rawOod * 10_000.0).roundToInt().toDouble() / 10_000.0).toFloat()

        return ScaledSequenceResult(
            flattenedScaled = flat,
            lastScaledRow = lastScaled,
            outOfDistributionScore = oodScore
        )
    }

    companion object {
        fun fromJsonString(jsonStr: String): ForecastScaler {
            val obj = JSONObject(jsonStr)
            val count = obj.getInt("feature_count")

            fun parseFloatArray(key: String): FloatArray {
                val arr = obj.getJSONArray(key)
                val res = FloatArray(arr.length())
                for (i in 0 until arr.length()) {
                    res[i] = arr.getDouble(i).toFloat()
                }
                return res
            }

            return ForecastScaler(
                schemaVersion = obj.getString("schema_version"),
                scalerType = obj.getString("scaler_type"),
                featureCount = count,
                clipZ = obj.optDouble("clip_z", 5.0).toFloat(),
                means = parseFloatArray("means"),
                stds = parseFloatArray("stds"),
                medians = parseFloatArray("medians"),
                iqrs = parseFloatArray("iqrs")
            )
        }
    }
}
