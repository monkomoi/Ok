package com.example.forecast.engine

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Out-of-Sample Temperature Scaling & Platt Class-Prior Calibration Engine (`ForecastCalibrationEngine.kt`, Section 8).
 * - Applies frozen calibration parameters (`temperature`, `bias_shift`) from `calibration.json`
 *   fitted strictly on out-of-sample validation data (`sampleSize >= 30`, `Brier <= 0.30`, `ECE <= 0.12`).
 * - Enforces `pDown + pFlat + pUp == 1.0` within `1e-5` and never fabricates calibrated status when `calibrated == false`.
 */
class ForecastCalibrationEngine(
    val schemaVersion: String,
    val featureSchemaHash: String,
    val horizonConfigs: Map<String, HorizonCalibrationConfig>
) {

    data class HorizonCalibrationConfig(
        val horizon: String,
        val calibrated: Boolean,
        val method: String,
        val sampleSize: Int,
        val minRequiredSamples: Int,
        val temperature: Double,
        val biasShift: DoubleArray,
        val brierScore: BigDecimal?,
        val logLoss: BigDecimal?,
        val ece: BigDecimal?
    )

    data class CalibratedDistribution(
        val pDown: BigDecimal,
        val pFlat: BigDecimal,
        val pUp: BigDecimal,
        val entropyNormalized: Double,
        val uncertainty: BigDecimal,
        val calibrationApplied: Boolean,
        val method: String,
        val sampleSize: Int,
        val brierScore: BigDecimal?,
        val ece: BigDecimal?
    )

    fun calibrateLogits(
        horizon: String,
        rawLogits: FloatArray,
        ensembleDisagreement: Float
    ): CalibratedDistribution {
        val cfg = horizonConfigs[horizon]
        val temp = max(cfg?.temperature ?: 1.0, 0.15)
        val bias = cfg?.biasShift ?: doubleArrayOf(0.0, 0.0, 0.0)

        val s0 = (rawLogits[0].toDouble() + bias[0]) / temp
        val s1 = (rawLogits[1].toDouble() + bias[1]) / temp
        val s2 = (rawLogits[2].toDouble() + bias[2]) / temp
        val maxS = max(s0, max(s1, s2))

        val e0 = exp(s0 - maxS)
        val e1 = exp(s1 - maxS)
        val e2 = exp(s2 - maxS)
        val denom = e0 + e1 + e2

        val p0D = ((e0 / denom) * 1_000_000.0).roundToLong().toDouble() / 1_000_000.0
        val p1D = ((e1 / denom) * 1_000_000.0).roundToLong().toDouble() / 1_000_000.0
        val p2D = ((max(0.0, 1.0 - p0D - p1D)) * 1_000_000.0).roundToLong().toDouble() / 1_000_000.0

        val probs = doubleArrayOf(p0D, p1D, p2D)
        var entSum = 0.0
        for (p in probs) {
            entSum += -p * ln(max(p, 1e-8))
        }
        val entropyNorm = entSum / ln(3.0)
        val rawUnc = min(1.0, 0.65 * entropyNorm + 0.35 * ensembleDisagreement.toDouble() * 3.0)
        val uncRounded = ((rawUnc * 100_000.0).roundToLong().toDouble() / 100_000.0)

        val isCalibrated = cfg != null &&
            cfg.calibrated &&
            cfg.sampleSize >= cfg.minRequiredSamples &&
            (cfg.brierScore == null || cfg.brierScore <= BigDecimal("0.30000")) &&
            (cfg.ece == null || cfg.ece <= BigDecimal("0.12000"))

        return CalibratedDistribution(
            pDown = BigDecimal.valueOf(p0D).setScale(6, RoundingMode.HALF_UP),
            pFlat = BigDecimal.valueOf(p1D).setScale(6, RoundingMode.HALF_UP),
            pUp = BigDecimal.valueOf(p2D).setScale(6, RoundingMode.HALF_UP),
            entropyNormalized = entropyNorm,
            uncertainty = BigDecimal.valueOf(uncRounded).setScale(5, RoundingMode.HALF_UP),
            calibrationApplied = isCalibrated,
            method = cfg?.method ?: "UNCALIBRATED_NO_CONFIG",
            sampleSize = cfg?.sampleSize ?: 0,
            brierScore = cfg?.brierScore,
            ece = cfg?.ece
        )
    }

    companion object {
        fun fromJsonString(jsonStr: String): ForecastCalibrationEngine {
            val obj = JSONObject(jsonStr)
            val horizonsObj = obj.getJSONObject("horizons")
            val map = mutableMapOf<String, HorizonCalibrationConfig>()
            val keys = horizonsObj.keys()
            while (keys.hasNext()) {
                val h = keys.next()
                val hObj = horizonsObj.getJSONObject(h)
                val biasArr = hObj.getJSONArray("bias_shift")
                val bias = DoubleArray(biasArr.length()) { idx -> biasArr.getDouble(idx) }
                val brier = if (hObj.isNull("brier_score")) null else BigDecimal.valueOf(hObj.getDouble("brier_score")).setScale(5, RoundingMode.HALF_UP)
                val logLoss = if (hObj.isNull("log_loss")) null else BigDecimal.valueOf(hObj.getDouble("log_loss")).setScale(5, RoundingMode.HALF_UP)
                val ece = if (hObj.isNull("ece")) null else BigDecimal.valueOf(hObj.getDouble("ece")).setScale(5, RoundingMode.HALF_UP)
                map[h] = HorizonCalibrationConfig(
                    horizon = h,
                    calibrated = hObj.getBoolean("calibrated"),
                    method = hObj.getString("method"),
                    sampleSize = hObj.getInt("sample_size"),
                    minRequiredSamples = hObj.optInt("min_required_samples", 30),
                    temperature = hObj.getDouble("temperature"),
                    biasShift = bias,
                    brierScore = brier,
                    logLoss = logLoss,
                    ece = ece
                )
            }
            return ForecastCalibrationEngine(
                schemaVersion = obj.getString("schema_version"),
                featureSchemaHash = obj.getString("feature_schema_hash"),
                horizonConfigs = map
            )
        }
    }
}
