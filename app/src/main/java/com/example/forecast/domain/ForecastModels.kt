package com.example.forecast.domain

import java.math.BigDecimal

/**
 * Domain contracts for the On-Device Time-Series Forecasting Engine (Section 11.2).
 */
data class ForecastModelBundle(
    val modelId: String,
    val modelVersion: String,
    val fallbackModelId: String,
    val architecture: String,
    val lookbackLength: Int,
    val featureCount: Int,
    val featureSchemaVersion: String,
    val featureSchemaHash: String,
    val horizons: List<String>,
    val runtimeBackend: String,
    val recommendedCpuThreads: Int,
    val modelTfliteSha256: String,
    val weightsCompactSha256: String,
    val verifiedChecksumOk: Boolean,
    val goldenSelfTestPassed: Boolean
)

data class ForecastTensorInput(
    val symbol: String,
    val baseTimeframe: String,
    val asOfCloseTimeMs: Long,
    val lookbackLength: Int,
    val featureCount: Int,
    val featureSchemaHash: String,
    val flattenedScaledFeatures: FloatArray,
    val lastRawFeatureRow: FloatArray,
    val lastScaledFeatureRow: FloatArray,
    val outOfDistributionScore: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ForecastTensorInput) return false
        return symbol == other.symbol &&
            baseTimeframe == other.baseTimeframe &&
            asOfCloseTimeMs == other.asOfCloseTimeMs &&
            lookbackLength == other.lookbackLength &&
            featureCount == other.featureCount &&
            featureSchemaHash == other.featureSchemaHash &&
            flattenedScaledFeatures.contentEquals(other.flattenedScaledFeatures)
    }

    override fun hashCode(): Int {
        var result = symbol.hashCode()
        result = 31 * result + asOfCloseTimeMs.hashCode()
        result = 31 * result + flattenedScaledFeatures.contentHashCode()
        return result
    }
}

data class RawForecastOutput(
    val horizon: String,
    val rawLogits: FloatArray, // [logitDown, logitFlat, logitUp]
    val expectedReturn: Float, // log return (e.g. 0.0042 = +0.42%)
    val q10: Float,
    val q50: Float,
    val q90: Float,
    val rawSigma: Float,
    val ensembleDisagreement: Float = 0f,
    val inferenceLatencyMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawForecastOutput) return false
        return horizon == other.horizon &&
            rawLogits.contentEquals(other.rawLogits) &&
            expectedReturn == other.expectedReturn &&
            q10 == other.q10 &&
            q50 == other.q50 &&
            q90 == other.q90 &&
            rawSigma == other.rawSigma
    }

    override fun hashCode(): Int {
        var result = horizon.hashCode()
        result = 31 * result + rawLogits.contentHashCode()
        result = 31 * result + expectedReturn.hashCode()
        return result
    }
}

data class ForecastResult(
    val predictionId: String,
    val symbol: String,
    val baseTimeframe: String,
    val horizon: String, // "15m" | "1h" | "4h"
    val generatedAtMs: Long,
    val validUntilMs: Long,
    val status: ForecastStatus,
    val modelId: String,
    val modelVersion: String,
    val featureSchemaHash: String,
    val pUp: BigDecimal?,
    val pDown: BigDecimal?,
    val pFlat: BigDecimal?,
    val expectedReturnPct: BigDecimal?,
    val q10Pct: BigDecimal?,
    val q50Pct: BigDecimal?,
    val q90Pct: BigDecimal?,
    val predictedVolatilityPct: BigDecimal?,
    val uncertainty: BigDecimal?,
    val outOfDistributionScore: BigDecimal?,
    val calibrationApplied: Boolean,
    val calibrationMethod: String,
    val calibrationSampleSize: Int,
    val brierScore: BigDecimal?,
    val expectedCalibrationError: BigDecimal?,
    val costThresholdPct: BigDecimal,
    val passesQualityGate: Boolean,
    val advisoryAction: String, // "ALLOW_CANDIDATE" | "ABSTAIN_NO_EDGE" | "VETO_OPPOSITE_DIRECTION" | "SHADOW_OBSERVE_ONLY"
    val reasonVi: String,
    val inferenceLatencyMs: Long
) {
    fun isExpired(nowMs: Long): Boolean = nowMs > validUntilMs
}

data class MultiHorizonForecastReport(
    val symbol: String,
    val asOfCloseTimeMs: Long,
    val generatedAtMs: Long,
    val operatingMode: ForecastOperatingMode,
    val overallStatus: ForecastStatus,
    val horizonResults: Map<String, ForecastResult>,
    val primaryResult15m: ForecastResult?,
    val summaryVi: String
)

data class ForecastDiagnosticsSnapshot(
    val activeModelId: String = "astra_gru_tcn_hybrid_v1_int8",
    val activeModelVersion: String = "1.0.0-s7edge",
    val rollbackModelId: String = "astra_logistic_l2_baseline_v1",
    val isRollbackActive: Boolean = false,
    val runtimeBackend: String = "CPU_INT8_COMPACT_2THREADS",
    val cpuThreadsConfigured: Int = 2,
    val primaryAbi: String = "arm64-v8a",
    val bundleChecksumVerified: Boolean = false,
    val goldenSelfTestPassed: Boolean = false,
    val modelLoadTimeMs: Long = 0L,
    val inferenceLatencyP50Ms: Long = 0L,
    val inferenceLatencyP95Ms: Long = 0L,
    val lastInferenceLatencyMs: Long = 0L,
    val nativeHeapDeltaKb: Long = 0L,
    val availableRamMb: Long = 0L,
    val batteryTempCelsius: Float = 0f,
    val thermalStatusLabel: String = "NOMINAL",
    val throttledDueToThermalOrRam: Boolean = false,
    val totalPredictionsGenerated: Int = 0,
    val staleOrMissingCount: Int = 0,
    val oodRejectedCount: Int = 0,
    val consecutiveErrors: Int = 0,
    val lastUpdatedMs: Long = 0L
)
