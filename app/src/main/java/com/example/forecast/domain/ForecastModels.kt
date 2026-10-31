package com.example.forecast.domain

import com.example.domain.DataTrustState
import com.example.domain.SymbolModelEligibility
import kotlinx.serialization.Serializable

enum class ForecastStatus {
    VALID,
    WARMUP,
    STALE_FEED,
    OUT_OF_DISTRIBUTION,
    MODEL_MISMATCH,
    TIMEOUT,
    LOW_CONFIDENCE,
    DEGRADED_SHADOW_ONLY,
    HEAD_INCONSISTENT,
    INSUFFICIENT_CALIBRATION_SAMPLE
}

enum class ForecastOperatingMode {
    DISABLED,
    SHADOW,
    GATED_ENFORCEMENT,
    ADAPTIVE_SIZING
}

@Serializable
data class ForecastModelBundle(
    val modelId: String,
    val modelVersion: String,
    val architecture: String,
    val artifactFormat: String = "WEIGHTS_COMPACT_JSON_INT8",
    val sequenceLength: Int,
    val featureCount: Int,
    val featureSchemaHash: String,
    val scalerHash: String,
    val calibrationHash: String,
    val weightsCompactSha256: String,
    val goldenVectorsSha256: String,
    val goldenOutputsSha256: String,
    val createdTimestampUtc: String,
    val trainWindowUtc: List<String>,
    val valWindowUtc: List<String>,
    val testWindowUtc: List<String>,
    val supportedSymbols: List<String>,
    val symbolEligibility: Map<String, String> = emptyMap(),
    val horizonsMinutes: List<Int>,
    val quantization: String,
    val minConfidenceThreshold: Double,
    val maxEntropyThreshold: Double,
    val maxOodScoreThreshold: Double,
    val dataTrustTag: String = "TEST_FIXTURE"
)

data class ForecastTensorInput(
    val symbol: String,
    val asOfTimestampMs: Long,
    val sequenceLength: Int,
    val featureCount: Int,
    val flattenedSequence: FloatArray,
    val rawLatestFeatures: DoubleArray,
    val oodAnomalyScore: Double,
    val staleBarsCount: Int,
    val isWarmupComplete: Boolean
)

data class RawHorizonOutput(
    val horizonMinutes: Int,
    val rawLogits: DoubleArray, // [down, flat, up]
    val expectedReturnPct: Double,
    val q10Pct: Double,
    val q50Pct: Double,
    val q90Pct: Double,
    val epistemicUncertainty: Double
)

data class RawForecastOutput(
    val modelId: String,
    val modelVersion: String,
    val symbol: String,
    val asOfTimestampMs: Long,
    val inferenceLatencyMs: Long,
    val horizonOutputs: Map<Int, RawHorizonOutput>
)

@Serializable
data class HorizonForecast(
    val horizonMinutes: Int,
    val pDown: Double,
    val pFlat: Double,
    val pUp: Double,
    val calibratedLogits: List<Double>,
    val expectedReturnPct: Double,
    val q10Pct: Double,
    val q50Pct: Double,
    val q90Pct: Double,
    val intervalWidthPct: Double,
    val shannonEntropy: Double,
    val normalizedConfidence: Double,
    val calibrationBrierScore: Double,
    val calibrationEce: Double,
    val calibrationSampleSize: Int = 0,
    val calibrationQualificationStatus: String = "INSUFFICIENT_SAMPLE_SHADOW_ONLY",
    val headConsistent: Boolean = true,
    val headInconsistencyReason: String = "OK"
)

@Serializable
data class ForecastResult(
    val symbol: String,
    val asOfTimestampMs: Long,
    val modelId: String,
    val modelVersion: String,
    val featureSchemaHash: String,
    val status: ForecastStatus,
    val statusReason: String,
    val operatingMode: ForecastOperatingMode,
    val symbolEligibility: SymbolModelEligibility = SymbolModelEligibility.BASELINE_ONLY,
    val horizons: Map<Int, HorizonForecast>,
    val compositeDirectionalScore: Double,
    val compositeConfidence: Double,
    val uncertaintyScore: Double,
    val oodScore: Double,
    val driftScore: Double,
    val headConsistent: Boolean,
    val inferenceLatencyMs: Long,
    val positionSizingMultiplier: Double,
    val allowTradeExecution: Boolean
)

@Serializable
data class MultiHorizonForecastReport(
    val generatedAtMs: Long,
    val activeModelId: String,
    val activeModelVersion: String,
    val operatingMode: ForecastOperatingMode,
    val dataTrustState: DataTrustState = DataTrustState.EMPTY,
    val fallbackActive: Boolean,
    val fallbackReason: String?,
    val symbolReports: Map<String, ForecastResult>,
    val rollingBrierScore15m: Double,
    val rollingBrierScore1h: Double,
    val rollingBrierScore4h: Double,
    val rollingEce15m: Double,
    val rollingHitRate15m: Double,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long
)

data class ForecastDiagnosticsSnapshot(
    val modelId: String,
    val modelVersion: String,
    val artifactFormat: String,
    val schemaHash: String,
    val operatingMode: ForecastOperatingMode,
    val dataTrustState: DataTrustState,
    val isHealthy: Boolean,
    val fallbackReason: String?,
    val rollingBrier15m: Double,
    val rollingEce15m: Double,
    val rollingHitRate15m: Double,
    val featureDriftPsi: Double,
    val oodRatePct: Double,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val totalInferences: Long,
    val shadowEvaluationsCount: Int
)
