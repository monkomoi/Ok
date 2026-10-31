package com.example.forecast.engine

import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.ForecastTensorInput
import kotlin.math.abs

/**
 * Out-of-Distribution (`OOD`) & Feature Drift Monitor (`ForecastDriftMonitor.kt`, Section 8.3 & 14).
 * - Flags `OUT_OF_DISTRIBUTION` when `outOfDistributionScore >= oodRejectThreshold` (default `0.65f`)
 *   or when more than 25% of the latest step's features exceed `|z| > 3.8`.
 */
object ForecastDriftMonitor {

    const val DEFAULT_OOD_REJECT_THRESHOLD = 0.65f
    const val EXTREME_Z_THRESHOLD = 3.8f
    const val MAX_EXTREME_FEATURE_FRACTION = 0.25f

    data class DriftEvaluation(
        val isOutOfDistribution: Boolean,
        val oodScore: Float,
        val extremeFeatureFraction: Float,
        val maxAbsZScore: Float,
        val statusIfRejected: ForecastStatus?,
        val noteVi: String
    )

    fun evaluateInputDrift(
        input: ForecastTensorInput,
        oodRejectThreshold: Float = DEFAULT_OOD_REJECT_THRESHOLD
    ): DriftEvaluation {
        val lastScaled = input.lastScaledFeatureRow
        var extremeCount = 0
        var maxAbsZ = 0f
        for (z in lastScaled) {
            val az = abs(z)
            if (az > maxAbsZ) maxAbsZ = az
            if (az > EXTREME_Z_THRESHOLD) extremeCount++
        }
        val extremeFrac = if (lastScaled.isNotEmpty()) {
            extremeCount.toFloat() / lastScaled.size.toFloat()
        } else 0f

        val isOod = input.outOfDistributionScore >= oodRejectThreshold ||
            extremeFrac > MAX_EXTREME_FEATURE_FRACTION

        return DriftEvaluation(
            isOutOfDistribution = isOod,
            oodScore = input.outOfDistributionScore,
            extremeFeatureFraction = extremeFrac,
            maxAbsZScore = maxAbsZ,
            statusIfRejected = if (isOod) ForecastStatus.OUT_OF_DISTRIBUTION else null,
            noteVi = if (isOod) {
                "Từ chối dự báo (OUT_OF_DISTRIBUTION): OOD=${String.format("%.3f", input.outOfDistributionScore)} >= $oodRejectThreshold (tỷ lệ đặc trưng lệch chuẩn=${String.format("%.1f%%", extremeFrac * 100f)})."
            } else {
                "Phân phối đặc trưng ổn định (OOD=${String.format("%.3f", input.outOfDistributionScore)} < $oodRejectThreshold)."
            }
        )
    }
}
