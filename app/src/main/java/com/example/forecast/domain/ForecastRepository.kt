package com.example.forecast.domain

import com.example.data.local.AstraDao
import com.example.data.local.ForecastMetricEntity
import com.example.data.local.ForecastModelEntity
import com.example.data.local.ForecastOutcomeEntity
import com.example.data.local.ForecastPredictionEntity
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Persistence & Outcome Verification Repository for the On-Device Time-Series Forecasting Engine (`ForecastRepository.kt`).
 * Connects `ForecastResult` and `ForecastModelBundle` to Room v7 tables (`forecast_predictions`, `forecast_models`,
 * `forecast_outcomes`, `forecast_metrics`) and enforces TTL pruning so SQLite stays compact on 64 GB S7 Edge storage.
 */
class ForecastRepository(
    private val dao: AstraDao
) {

    companion object {
        const val RETENTION_TTL_MS = 14L * 24L * 60L * 60L * 1000L // 14 days
    }

    fun observeRecentPredictions(): Flow<List<ForecastPredictionEntity>> =
        dao.observeRecentForecastPredictions()

    fun observeMetrics(): Flow<List<ForecastMetricEntity>> =
        dao.observeForecastMetrics()

    suspend fun persistModelBundleRegistration(
        bundle: ForecastModelBundle,
        isRollback: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        dao.upsertForecastModel(
            ForecastModelEntity(
                modelId = if (isRollback) bundle.fallbackModelId else bundle.modelId,
                modelVersion = bundle.modelVersion,
                architecture = bundle.architecture,
                featureSchemaVersion = bundle.featureSchemaVersion,
                featureSchemaHash = bundle.featureSchemaHash,
                runtimeBackend = bundle.runtimeBackend,
                cpuThreads = bundle.recommendedCpuThreads,
                sha256Checksum = bundle.modelTfliteSha256,
                isActive = true,
                isRollback = isRollback,
                goldenSelfTestPassed = bundle.goldenSelfTestPassed,
                registeredAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )
    }

    suspend fun persistForecastResults(
        asOfCloseTimeMs: Long,
        results: Collection<ForecastResult>
    ) {
        if (results.isEmpty()) return
        val entities = results.map { res ->
            ForecastPredictionEntity(
                predictionId = res.predictionId,
                symbol = res.symbol,
                baseTimeframe = res.baseTimeframe,
                horizon = res.horizon,
                asOfCloseTimeMs = asOfCloseTimeMs,
                generatedAtMs = res.generatedAtMs,
                validUntilMs = res.validUntilMs,
                status = res.status.name,
                modelId = res.modelId,
                modelVersion = res.modelVersion,
                featureSchemaHash = res.featureSchemaHash,
                pUp = res.pUp?.toPlainString(),
                pDown = res.pDown?.toPlainString(),
                pFlat = res.pFlat?.toPlainString(),
                expectedReturnPct = res.expectedReturnPct?.toPlainString(),
                q10Pct = res.q10Pct?.toPlainString(),
                q50Pct = res.q50Pct?.toPlainString(),
                q90Pct = res.q90Pct?.toPlainString(),
                predictedVolatilityPct = res.predictedVolatilityPct?.toPlainString(),
                uncertainty = res.uncertainty?.toPlainString(),
                outOfDistributionScore = res.outOfDistributionScore?.toPlainString(),
                calibrationApplied = res.calibrationApplied,
                calibrationMethod = res.calibrationMethod,
                calibrationSampleSize = res.calibrationSampleSize,
                brierScore = res.brierScore?.toPlainString(),
                expectedCalibrationError = res.expectedCalibrationError?.toPlainString(),
                costThresholdPct = res.costThresholdPct.toPlainString(),
                passesQualityGate = res.passesQualityGate,
                advisoryAction = res.advisoryAction,
                reasonVi = res.reasonVi,
                inferenceLatencyMs = res.inferenceLatencyMs
            )
        }
        dao.upsertForecastPredictions(entities)
    }

    suspend fun recordRealizedOutcomeAndRefreshMetrics(
        prediction: ForecastPredictionEntity,
        realizedReturnPct: BigDecimal,
        costThresholdPct: BigDecimal,
        p50LatencyMs: Long,
        p95LatencyMs: Long,
        oodRejectRate: BigDecimal,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val pUp = prediction.pUp?.toBigDecimalOrNull() ?: return
        val pDown = prediction.pDown?.toBigDecimalOrNull() ?: return
        val pFlat = prediction.pFlat?.toBigDecimalOrNull() ?: return
        val predRet = prediction.expectedReturnPct?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val predictedDir = when {
            pUp >= pDown && pUp >= pFlat -> "UP"
            pDown >= pUp && pDown >= pFlat -> "DOWN"
            else -> "FLAT"
        }
        val realizedDir = when {
            realizedReturnPct > costThresholdPct -> "UP"
            realizedReturnPct < costThresholdPct.negate() -> "DOWN"
            else -> "FLAT"
        }

        val yDown = if (realizedDir == "DOWN") 1.0 else 0.0
        val yFlat = if (realizedDir == "FLAT") 1.0 else 0.0
        val yUp = if (realizedDir == "UP") 1.0 else 0.0
        val d0 = pDown.toDouble() - yDown
        val d1 = pFlat.toDouble() - yFlat
        val d2 = pUp.toDouble() - yUp
        val brierContrib = BigDecimal.valueOf((d0 * d0 + d1 * d1 + d2 * d2) / 3.0)
            .setScale(5, RoundingMode.HALF_UP)

        val hit = predictedDir == realizedDir
        val outcomeId = "OUT-${prediction.predictionId}"

        dao.insertForecastOutcome(
            ForecastOutcomeEntity(
                outcomeId = outcomeId,
                predictionId = prediction.predictionId,
                symbol = prediction.symbol,
                horizon = prediction.horizon,
                modelId = prediction.modelId,
                predictedDirection = predictedDir,
                realizedDirection = realizedDir,
                predictedReturnPct = predRet.toPlainString(),
                realizedReturnPct = realizedReturnPct.toPlainString(),
                pUpAtPrediction = pUp.toPlainString(),
                pDownAtPrediction = pDown.toPlainString(),
                pFlatAtPrediction = pFlat.toPlainString(),
                brierContribution = brierContrib.toPlainString(),
                directionHit = hit,
                evaluatedAtMs = nowMs
            )
        )

        val history = dao.getForecastOutcomesOnce(prediction.symbol, prediction.horizon, limit = 100)
        if (history.isNotEmpty()) {
            val n = history.size
            val avgBrier = history.sumOf { it.brierContribution.toDoubleOrNull() ?: 0.25 } / n.toDouble()
            val dirAcc = history.count { it.directionHit }.toDouble() / n.toDouble()
            val mae = history.sumOf {
                val pr = it.predictedReturnPct.toDoubleOrNull() ?: 0.0
                val rr = it.realizedReturnPct.toDoubleOrNull() ?: 0.0
                kotlin.math.abs(pr - rr)
            } / n.toDouble()
            val ece = prediction.expectedCalibrationError?.toDoubleOrNull() ?: 0.045

            dao.upsertForecastMetric(
                ForecastMetricEntity(
                    metricKey = "${prediction.modelId}_${prediction.horizon}",
                    modelId = prediction.modelId,
                    horizon = prediction.horizon,
                    sampleSize = n,
                    brierScore = BigDecimal.valueOf(avgBrier).setScale(5, RoundingMode.HALF_UP).toPlainString(),
                    expectedCalibrationError = BigDecimal.valueOf(ece).setScale(5, RoundingMode.HALF_UP).toPlainString(),
                    directionalAccuracy = BigDecimal.valueOf(dirAcc).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    maeReturnPct = BigDecimal.valueOf(mae).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    oodRejectRate = oodRejectRate.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    p50LatencyMs = p50LatencyMs,
                    p95LatencyMs = p95LatencyMs,
                    updatedAtMs = nowMs
                )
            )
        }
    }

    suspend fun pruneExpiredRecords(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - RETENTION_TTL_MS
        dao.pruneOldForecastPredictions(cutoff)
        dao.pruneOldForecastOutcomes(cutoff)
    }
}
