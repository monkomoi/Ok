package com.example.forecast.runtime

import android.content.Context
import com.example.forecast.data.ForecastFeatureSchema
import com.example.forecast.data.ForecastScaler
import com.example.forecast.data.ForecastScalerConfig
import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastTensorInput
import com.example.forecast.engine.ForecastCalibrationConfig
import com.example.forecast.engine.ForecastCalibrationEngine
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.math.abs

data class LoadedForecastArtifacts(
    val bundle: ForecastModelBundle,
    val schema: ForecastFeatureSchema,
    val scaler: ForecastScaler,
    val calibration: ForecastCalibrationEngine,
    val runtime: CompactKotlinForecastRuntime,
    val goldenParityPassed: Boolean,
    val goldenMaxAbsDiff: Double,
    val verificationNote: String
)

class ForecastModelLoader(
    private val context: Context,
    private val assetDir: String = "forecast_bundle"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun loadAndVerifyBundle(): Result<LoadedForecastArtifacts> = runCatching {
        val manifestBytes = readAssetBytes("$assetDir/manifest.json")
        val schemaBytes = readAssetBytes("$assetDir/feature_schema.json")
        val scalerBytes = readAssetBytes("$assetDir/scaler.json")
        val calibrationBytes = readAssetBytes("$assetDir/calibration.json")
        val weightsBytes = readAssetBytes("$assetDir/weights_compact.json")
        val goldenVecBytes = readAssetBytes("$assetDir/golden_feature_vectors.json")
        val goldenOutBytes = readAssetBytes("$assetDir/golden_outputs.json")

        val bundle = json.decodeFromString<ForecastModelBundle>(manifestBytes.decodeToString())
        val schema = json.decodeFromString<ForecastFeatureSchema>(schemaBytes.decodeToString())
        val scalerConfig = json.decodeFromString<ForecastScalerConfig>(scalerBytes.decodeToString())
        val calibrationConfig = json.decodeFromString<ForecastCalibrationConfig>(calibrationBytes.decodeToString())
        val weights = json.decodeFromString<CompactModelWeights>(weightsBytes.decodeToString())
        val goldenVectors = json.decodeFromString<List<GoldenFeatureVectorItem>>(goldenVecBytes.decodeToString())
        val goldenOutputs = json.decodeFromString<List<GoldenOutputItem>>(goldenOutBytes.decodeToString())

        // 1. Validate schema contract
        check(schema.validateContract()) {
            "Feature schema contract validation failed for ${schema.version}"
        }
        check(bundle.featureSchemaHash == schema.schemaHash) {
            "Feature schema hash mismatch: manifest=${bundle.featureSchemaHash}, schema=${schema.schemaHash}"
        }
        check(bundle.scalerHash == scalerConfig.scalerHash) {
            "Scaler hash mismatch: manifest=${bundle.scalerHash}, scaler=${scalerConfig.scalerHash}"
        }
        check(bundle.calibrationHash == calibrationConfig.calibrationHash) {
            "Calibration hash mismatch: manifest=${bundle.calibrationHash}, calibration=${calibrationConfig.calibrationHash}"
        }

        // 2. Validate SHA-256 checksums of weights & golden files
        val actualWeightsSha = sha256Hex(weightsBytes)
        check(bundle.weightsCompactSha256.equals(actualWeightsSha, ignoreCase = true)) {
            "Weights SHA-256 mismatch: expected=${bundle.weightsCompactSha256}, actual=$actualWeightsSha"
        }

        // 3. Verify golden input/output parity across all bundled symbols
        val runtime = CompactKotlinForecastRuntime(bundle, weights)
        val calibration = ForecastCalibrationEngine(calibrationConfig)
        val goldenOutById = goldenOutputs.associateBy { it.sampleId }
        var maxAbsDiff = 0.0

        for (vec in goldenVectors) {
            val expected = goldenOutById[vec.sampleId] ?: continue
            val tensor = ForecastTensorInput(
                symbol = vec.symbol,
                asOfTimestampMs = vec.timestampMs,
                sequenceLength = bundle.sequenceLength,
                featureCount = bundle.featureCount,
                flattenedSequence = vec.flattenedSequence.toFloatArray(),
                rawLatestFeatures = DoubleArray(bundle.featureCount),
                oodAnomalyScore = 0.0,
                staleBarsCount = 0,
                isWarmupComplete = true
            )
            val rawOut = runtime.runInference(tensor)
            for ((horizonKey, expectedH) in expected.horizons) {
                val hMin = horizonKey.toIntOrNull() ?: continue
                val actualH = rawOut.horizonOutputs[hMin]
                    ?: error("Missing horizon $hMin in runtime output for golden sample ${vec.sampleId}")
                for (c in 0 until 3) {
                    val diff = abs(actualH.rawLogits[c] - expectedH.rawLogits[c])
                    if (diff > maxAbsDiff) maxAbsDiff = diff
                }
                val retDiff = abs(actualH.expectedReturnPct - expectedH.expectedReturnPct)
                if (retDiff > maxAbsDiff) maxAbsDiff = retDiff

                // Verify calibration and head consistency on golden sample
                val calH = calibration.calibrateHorizon(actualH)
                check(calH.headConsistent == expectedH.headConsistent) {
                    "Golden sample ${vec.sampleId} horizon ${hMin}m head consistency mismatch: " +
                        "actual=${calH.headConsistent} (${calH.headInconsistencyReason}), expected=${expectedH.headConsistent}"
                }
            }
        }

        val parityPassed = maxAbsDiff <= 1e-4
        check(parityPassed) {
            "Golden vector parity check failed: maxAbsDiff=$maxAbsDiff exceeds tolerance 1e-4"
        }

        LoadedForecastArtifacts(
            bundle = bundle,
            schema = schema,
            scaler = ForecastScaler(scalerConfig),
            calibration = calibration,
            runtime = runtime,
            goldenParityPassed = true,
            goldenMaxAbsDiff = maxAbsDiff,
            verificationNote = "Verified ${bundle.modelId} (${bundle.modelVersion}, ${bundle.artifactFormat}), " +
                "golden maxDiff=${"%.6f".format(maxAbsDiff)}, trustTag=${bundle.dataTrustTag}"
        )
    }

    private fun readAssetBytes(path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
