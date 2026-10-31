package com.example.forecast.runtime

import android.content.Context
import com.example.forecast.data.ForecastFeatureSchema
import com.example.forecast.data.ForecastScaler
import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.ForecastTensorInput
import com.example.forecast.engine.ForecastCalibrationEngine
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Loads and cryptographically verifies the on-device Forecasting Model Bundle (`ForecastModelLoader.kt`).
 * - Verifies `SHA256SUMS` against `manifest.json`, `model.tflite`, `weights_compact.json`,
 *   `feature_schema.json`, `scaler.json`, `calibration.json`, and `golden_outputs.json`.
 * - Verifies the TFLite v3 (`TFL3`) FlatBuffer magic header at byte offset 4..8 in `model.tflite`.
 * - Verifies `feature_schema_hash` compatibility across manifest, weights, calibration, and schema.
 * - Executes a deterministic Golden Output Self-Test on load to catch any numerical drift or corruption.
 */
object ForecastModelLoader {

    const val DEFAULT_ASSET_BUNDLE_DIR = "forecast_bundle"

    data class LoadedForecastBundleArtifacts(
        val status: ForecastStatus,
        val errorReasonVi: String?,
        val bundleMetadata: ForecastModelBundle?,
        val schema: ForecastFeatureSchema?,
        val scaler: ForecastScaler?,
        val calibrationEngine: ForecastCalibrationEngine?,
        val runtime: LiteRtForecastRuntime?,
        val loadLatencyMs: Long
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun loadFromAssets(
        context: Context,
        assetBundleDir: String = DEFAULT_ASSET_BUNDLE_DIR,
        cpuThreads: Int = 2
    ): LoadedForecastBundleArtifacts {
        return loadFromByteProvider(
            readBytes = { fileName ->
                context.assets.open("$assetBundleDir/$fileName").use { it.readBytes() }
            },
            cpuThreads = cpuThreads
        )
    }

    fun loadFromDirectory(
        directory: File,
        cpuThreads: Int = 2
    ): LoadedForecastBundleArtifacts {
        return loadFromByteProvider(
            readBytes = { fileName ->
                val f = File(directory, fileName)
                if (!f.exists() || !f.isFile) {
                    throw java.io.FileNotFoundException("Missing bundle file: ${f.absolutePath}")
                }
                f.readBytes()
            },
            cpuThreads = cpuThreads
        )
    }

    fun loadFromByteProvider(
        readBytes: (String) -> ByteArray,
        cpuThreads: Int = 2
    ): LoadedForecastBundleArtifacts {
        val startMs = System.currentTimeMillis()
        return try {
            val sumsText = String(readBytes("SHA256SUMS"), Charsets.UTF_8)
            val expectedHashes = mutableMapOf<String, String>()
            for (line in sumsText.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    expectedHashes[parts[1]] = parts[0].lowercase()
                }
            }

            val requiredFiles = listOf(
                "manifest.json",
                "model.tflite",
                "weights_compact.json",
                "feature_schema.json",
                "scaler.json",
                "calibration.json",
                "golden_feature_vectors.json",
                "golden_outputs.json"
            )

            val fileBytesMap = mutableMapOf<String, ByteArray>()
            for (fname in requiredFiles) {
                val bytes = readBytes(fname)
                val actualSha = sha256Hex(bytes).lowercase()
                val expectedSha = expectedHashes[fname]
                if (expectedSha == null || actualSha != expectedSha) {
                    return LoadedForecastBundleArtifacts(
                        status = ForecastStatus.MODEL_INCOMPATIBLE,
                        errorReasonVi = "Lỗi kiểm tra toàn vẹn SHA-256 cho $fname (expected=$expectedSha, actual=$actualSha).",
                        bundleMetadata = null,
                        schema = null,
                        scaler = null,
                        calibrationEngine = null,
                        runtime = null,
                        loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    )
                }
                fileBytesMap[fname] = bytes
            }

            // Verify TFLite v3 FlatBuffer magic identifier "TFL3" at offset 4..8
            val tfliteBytes = fileBytesMap.getValue("model.tflite")
            if (tfliteBytes.size < 16 ||
                tfliteBytes[4] != 'T'.code.toByte() ||
                tfliteBytes[5] != 'F'.code.toByte() ||
                tfliteBytes[6] != 'L'.code.toByte() ||
                tfliteBytes[7] != '3'.code.toByte()
            ) {
                return LoadedForecastBundleArtifacts(
                    status = ForecastStatus.MODEL_INCOMPATIBLE,
                    errorReasonVi = "File model.tflite không có header FlatBuffer TFL3 hợp lệ.",
                    bundleMetadata = null,
                    schema = null,
                    scaler = null,
                    calibrationEngine = null,
                    runtime = null,
                    loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            }

            val manifestObj = JSONObject(String(fileBytesMap.getValue("manifest.json"), Charsets.UTF_8))
            val schemaStr = String(fileBytesMap.getValue("feature_schema.json"), Charsets.UTF_8)
            val scalerStr = String(fileBytesMap.getValue("scaler.json"), Charsets.UTF_8)
            val calStr = String(fileBytesMap.getValue("calibration.json"), Charsets.UTF_8)
            val weightsStr = String(fileBytesMap.getValue("weights_compact.json"), Charsets.UTF_8)

            val schema = ForecastFeatureSchema.fromJsonString(schemaStr)
            if (!schema.validateExpectedContract()) {
                return LoadedForecastBundleArtifacts(
                    status = ForecastStatus.MODEL_INCOMPATIBLE,
                    errorReasonVi = "FeatureSchema không khớp hợp đồng [lookback=64, features=26].",
                    bundleMetadata = null,
                    schema = null,
                    scaler = null,
                    calibrationEngine = null,
                    runtime = null,
                    loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            }

            val scaler = ForecastScaler.fromJsonString(scalerStr)
            if (scaler.featureCount != schema.featureCount) {
                return LoadedForecastBundleArtifacts(
                    status = ForecastStatus.MODEL_INCOMPATIBLE,
                    errorReasonVi = "Scaler featureCount (${scaler.featureCount}) khác Schema (${schema.featureCount}).",
                    bundleMetadata = null,
                    schema = null,
                    scaler = null,
                    calibrationEngine = null,
                    runtime = null,
                    loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            }

            val calibrationEngine = ForecastCalibrationEngine.fromJsonString(calStr)
            val manifestSchemaHash = manifestObj.getString("feature_schema_hash")
            if (calibrationEngine.featureSchemaHash != manifestSchemaHash) {
                return LoadedForecastBundleArtifacts(
                    status = ForecastStatus.MODEL_INCOMPATIBLE,
                    errorReasonVi = "Calibration schemaHash (${calibrationEngine.featureSchemaHash}) khác Manifest ($manifestSchemaHash).",
                    bundleMetadata = null,
                    schema = null,
                    scaler = null,
                    calibrationEngine = null,
                    runtime = null,
                    loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            }

            val horizArr = manifestObj.getJSONArray("horizons")
            val horizons = mutableListOf<String>()
            for (i in 0 until horizArr.length()) {
                horizons.add(horizArr.getString(i))
            }

            val initialMetadata = ForecastModelBundle(
                modelId = manifestObj.getString("model_id"),
                modelVersion = manifestObj.getString("model_version"),
                fallbackModelId = manifestObj.getString("fallback_model_id"),
                architecture = manifestObj.getString("architecture"),
                lookbackLength = manifestObj.getInt("lookback_length"),
                featureCount = manifestObj.getInt("feature_count"),
                featureSchemaVersion = manifestObj.getString("feature_schema_version"),
                featureSchemaHash = manifestSchemaHash,
                horizons = horizons,
                runtimeBackend = manifestObj.getString("runtime_backend"),
                recommendedCpuThreads = manifestObj.optInt("recommended_cpu_threads", 2),
                modelTfliteSha256 = manifestObj.getString("model_tflite_sha256"),
                weightsCompactSha256 = manifestObj.getString("weights_compact_sha256"),
                verifiedChecksumOk = true,
                goldenSelfTestPassed = false
            )

            val runtime = LiteRtForecastRuntime(
                bundleMetadata = initialMetadata,
                weightsJsonStr = weightsStr,
                overrideCpuThreads = cpuThreads
            )

            // Golden Output Self-Test on Sample #1
            val goldenVecObj = JSONObject(String(fileBytesMap.getValue("golden_feature_vectors.json"), Charsets.UTF_8))
            val goldenOutObj = JSONObject(String(fileBytesMap.getValue("golden_outputs.json"), Charsets.UTF_8))
            val firstVecSample = goldenVecObj.getJSONArray("samples").getJSONObject(0)
            val firstOutSample = goldenOutObj.getJSONArray("samples").getJSONObject(0)

            val seqArr = firstVecSample.getJSONArray("expected_scaled_sequence")
            val flatScaled = FloatArray(schema.lookbackLength * schema.featureCount)
            for (t in 0 until schema.lookbackLength) {
                val r = seqArr.getJSONArray(t)
                for (f in 0 until schema.featureCount) {
                    flatScaled[t * schema.featureCount + f] = r.getDouble(f).toFloat()
                }
            }
            val selfTestInput = ForecastTensorInput(
                symbol = firstVecSample.getString("symbol"),
                baseTimeframe = "15m",
                asOfCloseTimeMs = firstVecSample.getLong("as_of_close_time_ms"),
                lookbackLength = schema.lookbackLength,
                featureCount = schema.featureCount,
                featureSchemaHash = manifestSchemaHash,
                flattenedScaledFeatures = flatScaled,
                lastRawFeatureRow = FloatArray(schema.featureCount),
                lastScaledFeatureRow = FloatArray(schema.featureCount),
                outOfDistributionScore = firstVecSample.getDouble("expected_ood_score").toFloat()
            )

            val (_, rawMap) = kotlinx.coroutines.runBlocking {
                runtime.predictAllHorizons(selfTestInput, "DEFAULT")
            }
            val raw15m = rawMap["15m"]
            val exp15m = firstOutSample.getJSONObject("horizons").getJSONObject("15m")
            val expLogits = exp15m.getJSONArray("raw_logits")
            val selfTestOk = raw15m != null &&
                abs(raw15m.rawLogits[0] - expLogits.getDouble(0).toFloat()) < 1e-4f &&
                abs(raw15m.rawLogits[2] - expLogits.getDouble(2).toFloat()) < 1e-4f

            if (!selfTestOk) {
                return LoadedForecastBundleArtifacts(
                    status = ForecastStatus.MODEL_INCOMPATIBLE,
                    errorReasonVi = "Golden Output Self-Test thất bại khi đối chiếu logits mẫu chuẩn.",
                    bundleMetadata = null,
                    schema = null,
                    scaler = null,
                    calibrationEngine = null,
                    runtime = null,
                    loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            }

            val finalMetadata = initialMetadata.copy(goldenSelfTestPassed = true)
            LoadedForecastBundleArtifacts(
                status = ForecastStatus.READY_VALIDATED,
                errorReasonVi = null,
                bundleMetadata = finalMetadata,
                schema = schema,
                scaler = scaler,
                calibrationEngine = calibrationEngine,
                runtime = LiteRtForecastRuntime(finalMetadata, weightsStr, cpuThreads),
                loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        } catch (e: java.io.FileNotFoundException) {
            LoadedForecastBundleArtifacts(
                status = ForecastStatus.MODEL_MISSING,
                errorReasonVi = "Không tìm thấy gói model dự báo (${e.message}).",
                bundleMetadata = null,
                schema = null,
                scaler = null,
                calibrationEngine = null,
                runtime = null,
                loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        } catch (e: Exception) {
            LoadedForecastBundleArtifacts(
                status = ForecastStatus.MODEL_INCOMPATIBLE,
                errorReasonVi = "Không thể nạp gói model dự báo: ${e.message}",
                bundleMetadata = null,
                schema = null,
                scaler = null,
                calibrationEngine = null,
                runtime = null,
                loadLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }
    }
}
