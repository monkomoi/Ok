package com.example.forecast.engine

import com.example.domain.SymbolModelEligibility
import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.ForecastTensorInput
import com.example.forecast.domain.HorizonForecast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class QualityGateDecision(
    val status: ForecastStatus,
    val statusReason: String,
    val compositeDirectionalScore: Double,
    val compositeConfidence: Double,
    val uncertaintyScore: Double,
    val headConsistent: Boolean,
    val positionSizingMultiplier: Double,
    val allowTradeExecution: Boolean
)

/**
 * Evaluates all safety, freshness, OOD, drift, head-consistency, calibration-sample,
 * and symbol-eligibility gates before allowing a forecast to authorize or size a trade.
 */
class ForecastQualityGate(
    private val maxInferenceLatencyMs: Long = 120L,
    private val maxStaleBars: Int = 2,
    private val maxFeatureDriftPsi: Double = 0.35
) {
    fun evaluate(
        bundle: ForecastModelBundle,
        input: ForecastTensorInput,
        horizons: Map<Int, HorizonForecast>,
        operatingMode: ForecastOperatingMode,
        symbolEligibility: SymbolModelEligibility,
        driftPsi: Double,
        inferenceLatencyMs: Long
    ): QualityGateDecision {
        if (!input.isWarmupComplete) {
            return QualityGateDecision(
                status = ForecastStatus.WARMUP,
                statusReason = "Sequence warmup incomplete (missing ${input.staleBarsCount} bars)",
                compositeDirectionalScore = 0.0,
                compositeConfidence = 0.0,
                uncertaintyScore = 1.0,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        if (input.staleBarsCount > maxStaleBars) {
            return QualityGateDecision(
                status = ForecastStatus.STALE_FEED,
                statusReason = "Market sequence feed is stale by ${input.staleBarsCount} bars (> $maxStaleBars)",
                compositeDirectionalScore = 0.0,
                compositeConfidence = 0.0,
                uncertaintyScore = 1.0,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        if (inferenceLatencyMs > maxInferenceLatencyMs) {
            return QualityGateDecision(
                status = ForecastStatus.TIMEOUT,
                statusReason = "Inference latency ${inferenceLatencyMs}ms exceeded budget ${maxInferenceLatencyMs}ms",
                compositeDirectionalScore = 0.0,
                compositeConfidence = 0.0,
                uncertaintyScore = 1.0,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        val h15 = horizons[15]
        val h60 = horizons[60]
        val h240 = horizons[240]
        if (h15 == null || h60 == null || h240 == null) {
            return QualityGateDecision(
                status = ForecastStatus.MODEL_MISMATCH,
                statusReason = "Missing required multi-horizon outputs (15m/60m/240m)",
                compositeDirectionalScore = 0.0,
                compositeConfidence = 0.0,
                uncertaintyScore = 1.0,
                headConsistent = false,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        // Check cross-head consistency across all horizons (Section 2.2 Defect #3 fix)
        val inconsistentHorizon = listOf(h15, h60, h240).firstOrNull { !it.headConsistent }
        val allHeadsConsistent = (inconsistentHorizon == null)

        // Multi-horizon composite directional score in [-1.0, +1.0]
        val dir15 = h15.pUp - h15.pDown
        val dir60 = h60.pUp - h60.pDown
        val dir240 = h240.pUp - h240.pDown
        val compositeDir = max(-1.0, min(1.0, 0.50 * dir15 + 0.30 * dir60 + 0.20 * dir240))

        val compositeConf = max(
            0.0,
            min(
                1.0,
                0.50 * h15.normalizedConfidence + 0.30 * h60.normalizedConfidence + 0.20 * h240.normalizedConfidence
            )
        )
        val compositeEntropy = 0.50 * h15.shannonEntropy + 0.30 * h60.shannonEntropy + 0.20 * h240.shannonEntropy

        if (!allHeadsConsistent) {
            return QualityGateDecision(
                status = ForecastStatus.HEAD_INCONSISTENT,
                statusReason = "Horizon ${inconsistentHorizon!!.horizonMinutes}m head contradiction: ${inconsistentHorizon.headInconsistencyReason}",
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = false,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        if (input.oodAnomalyScore > bundle.maxOodScoreThreshold || driftPsi > maxFeatureDriftPsi) {
            return QualityGateDecision(
                status = ForecastStatus.OUT_OF_DISTRIBUTION,
                statusReason = "OOD score ${"%.2f".format(input.oodAnomalyScore)} (max ${bundle.maxOodScoreThreshold}) or PSI ${"%.2f".format(driftPsi)}",
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        if (symbolEligibility == SymbolModelEligibility.UNSUPPORTED ||
            symbolEligibility == SymbolModelEligibility.BASELINE_ONLY
        ) {
            return QualityGateDecision(
                status = ForecastStatus.DEGRADED_SHADOW_ONLY,
                statusReason = "Symbol eligibility is $symbolEligibility (ML execution disabled; baseline/shadow only)",
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        // Check if calibration sample size is insufficient (< 200 validation samples)
        val insufficientCalib = listOf(h15, h60, h240).any {
            it.calibrationQualificationStatus == "INSUFFICIENT_SAMPLE_SHADOW_ONLY"
        }

        if (operatingMode == ForecastOperatingMode.DISABLED || operatingMode == ForecastOperatingMode.SHADOW) {
            return QualityGateDecision(
                status = if (insufficientCalib) ForecastStatus.INSUFFICIENT_CALIBRATION_SAMPLE else ForecastStatus.VALID,
                statusReason = if (insufficientCalib) {
                    "Shadow mode active (calibration sample N=${h15.calibrationSampleSize} < 200; shadow logging only)"
                } else {
                    "Shadow mode active (forecast recorded without execution gating)"
                },
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = true,
                positionSizingMultiplier = 1.0,
                allowTradeExecution = false
            )
        }

        // In GATED_ENFORCEMENT or ADAPTIVE_SIZING, block ML trade authorization if calibration sample is unqualified
        if (insufficientCalib) {
            return QualityGateDecision(
                status = ForecastStatus.INSUFFICIENT_CALIBRATION_SAMPLE,
                statusReason = "Calibration validation sample N=${h15.calibrationSampleSize} < 200; restricted to SHADOW until qualified",
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        if (compositeConf < bundle.minConfidenceThreshold || compositeEntropy > bundle.maxEntropyThreshold) {
            return QualityGateDecision(
                status = ForecastStatus.LOW_CONFIDENCE,
                statusReason = "Confidence ${"%.2f".format(compositeConf)} < ${bundle.minConfidenceThreshold} or entropy ${"%.2f".format(compositeEntropy)} > ${bundle.maxEntropyThreshold}",
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        // Require alignment between 15m and 1h directional signs and positive expected return after fees
        val aligned15mAnd1h = (dir15 * dir60) > 0.0
        val costFloorPct = 0.12
        val expectedEdge15m = abs(h15.expectedReturnPct)
        if (!aligned15mAnd1h || expectedEdge15m < costFloorPct) {
            return QualityGateDecision(
                status = ForecastStatus.LOW_CONFIDENCE,
                statusReason = "Multi-horizon alignment=${aligned15mAnd1h} or 15m expected return ${"%.3f".format(h15.expectedReturnPct)}% below cost floor ${costFloorPct}%",
                compositeDirectionalScore = compositeDir,
                compositeConfidence = compositeConf,
                uncertaintyScore = compositeEntropy,
                headConsistent = true,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
        }

        val sizingMult = if (operatingMode == ForecastOperatingMode.ADAPTIVE_SIZING) {
            // Bounded adaptive multiplier in [0.50, 1.00] — never increases leverage above risk budget
            val rawMult = 0.50 + 0.50 * ((compositeConf - bundle.minConfidenceThreshold) / max(0.05, 1.0 - bundle.minConfidenceThreshold))
            max(0.50, min(1.00, rawMult))
        } else {
            1.00
        }

        return QualityGateDecision(
            status = ForecastStatus.VALID,
            statusReason = "All forecast quality, consistency, and cost-floor gates passed",
            compositeDirectionalScore = compositeDir,
            compositeConfidence = compositeConf,
            uncertaintyScore = compositeEntropy,
            headConsistent = true,
            positionSizingMultiplier = sizingMult,
            allowTradeExecution = true
        )
    }
}
