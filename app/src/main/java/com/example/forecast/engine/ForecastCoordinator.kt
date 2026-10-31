package com.example.forecast.engine

import com.example.data.local.AstraDao
import com.example.data.local.ForecastEvaluationEntity
import com.example.domain.DataTrustState
import com.example.domain.KlineBar
import com.example.forecast.data.ForecastFeaturePipeline
import com.example.forecast.data.MarketSequenceRepository
import com.example.forecast.domain.ForecastDiagnosticsSnapshot
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.ForecastRepository
import com.example.forecast.domain.ForecastResult
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.forecast.runtime.ForecastModelRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Coordinates end-to-end on-device multi-horizon forecasting, calibration, quality gating,
 * idempotent multi-horizon outcome resolution (15m, 1h, 4h), and latency/drift telemetry.
 */
class ForecastCoordinator(
    private val dao: AstraDao,
    private val modelRegistry: ForecastModelRegistry,
    private val sequenceRepository: MarketSequenceRepository = MarketSequenceRepository(),
    private val qualityGate: ForecastQualityGate = ForecastQualityGate(),
    val ensemble: ForecastEnsemble = ForecastEnsemble()
) : ForecastRepository {

    private var driftMonitor: ForecastDriftMonitor? = null
    private var featurePipeline: ForecastFeaturePipeline? = null

    private val latestResultsBySymbol = ConcurrentHashMap<String, ForecastResult>()
    private val latencyRing = LongArray(64)
    private var latencyCursor = 0
    private var latencyCount = 0
    private val totalInferences = AtomicLong(0L)

    private val _latestReport = MutableStateFlow<MultiHorizonForecastReport?>(null)
    val latestReport: StateFlow<MultiHorizonForecastReport?> = _latestReport.asStateFlow()

    private val _diagnostics = MutableStateFlow<ForecastDiagnosticsSnapshot?>(null)
    val diagnostics: StateFlow<ForecastDiagnosticsSnapshot?> = _diagnostics.asStateFlow()

    fun initialize(): Boolean {
        val res = modelRegistry.initialize()
        val artifacts = res.getOrNull() ?: return false
        featurePipeline = ForecastFeaturePipeline(artifacts.schema, artifacts.scaler)
        driftMonitor = ForecastDriftMonitor(artifacts.scaler.config)
        return true
    }

    fun evaluateSymbolSequence(
        symbol: String,
        klines15m: List<KlineBar>,
        klines1h: List<KlineBar>,
        klines4h: List<KlineBar>,
        spreadBps: Double,
        obiTop5: Double,
        operatingMode: ForecastOperatingMode,
        nowTimestampMs: Long = System.currentTimeMillis()
    ): ForecastResult {
        sequenceRepository.updateSequences(
            symbol = symbol,
            klines15m = klines15m,
            klines1h = klines1h,
            klines4h = klines4h,
            spreadBps = spreadBps,
            obiTop5 = obiTop5,
            nowMs = nowTimestampMs
        )

        val artifacts = modelRegistry.getActiveArtifacts()
        val pipeline = featurePipeline
        if (artifacts == null || pipeline == null) {
            val fallback = ForecastResult(
                symbol = symbol,
                asOfTimestampMs = nowTimestampMs,
                modelId = "UNLOADED",
                modelVersion = "0.0.0",
                featureSchemaHash = "NONE",
                status = ForecastStatus.MODEL_MISMATCH,
                statusReason = modelRegistry.getLastLoadError() ?: "Forecast bundle not initialized",
                operatingMode = operatingMode,
                symbolEligibility = modelRegistry.getSymbolEligibility(symbol),
                horizons = emptyMap(),
                compositeDirectionalScore = 0.0,
                compositeConfidence = 0.0,
                uncertaintyScore = 1.0,
                oodScore = 0.0,
                driftScore = 0.0,
                headConsistent = false,
                inferenceLatencyMs = 0L,
                positionSizingMultiplier = 0.0,
                allowTradeExecution = false
            )
            latestResultsBySymbol[symbol] = fallback
            return fallback
        }

        val tensorInput = pipeline.buildSequenceTensor(
            symbol = symbol,
            klines15m = sequenceRepository.get15mBars(symbol),
            spreadBpsCurrent = spreadBps,
            obiTop5Current = obiTop5,
            nowTimestampMs = nowTimestampMs
        )

        val rawOut = artifacts.runtime.runInference(tensorInput)
        recordLatency(rawOut.inferenceLatencyMs)
        totalInferences.incrementAndGet()

        val calibratedHorizons = rawOut.horizonOutputs.mapValues { (_, rawH) ->
            artifacts.calibration.calibrateHorizon(rawH)
        }

        val psi = driftMonitor?.recordSample(
            rawLatestFeatures = tensorInput.rawLatestFeatures,
            oodScore = tensorInput.oodAnomalyScore,
            oodThreshold = artifacts.bundle.maxOodScoreThreshold
        ) ?: 0.0

        val eligibility = modelRegistry.getSymbolEligibility(symbol)
        val decision = qualityGate.evaluate(
            bundle = artifacts.bundle,
            input = tensorInput,
            horizons = calibratedHorizons,
            operatingMode = operatingMode,
            symbolEligibility = eligibility,
            driftPsi = psi,
            inferenceLatencyMs = rawOut.inferenceLatencyMs
        )

        val result = ForecastResult(
            symbol = symbol,
            asOfTimestampMs = tensorInput.asOfTimestampMs,
            modelId = artifacts.bundle.modelId,
            modelVersion = artifacts.bundle.modelVersion,
            featureSchemaHash = artifacts.bundle.featureSchemaHash,
            status = decision.status,
            statusReason = decision.statusReason,
            operatingMode = operatingMode,
            symbolEligibility = eligibility,
            horizons = calibratedHorizons,
            compositeDirectionalScore = decision.compositeDirectionalScore,
            compositeConfidence = decision.compositeConfidence,
            uncertaintyScore = decision.uncertaintyScore,
            oodScore = tensorInput.oodAnomalyScore,
            driftScore = psi,
            headConsistent = decision.headConsistent,
            inferenceLatencyMs = rawOut.inferenceLatencyMs,
            positionSizingMultiplier = decision.positionSizingMultiplier,
            allowTradeExecution = decision.allowTradeExecution
        )
        latestResultsBySymbol[symbol] = result
        return result
    }

    /**
     * Persists idempotent multi-horizon (15m, 60m, 240m) evaluation rows for real-time
     * shadow/paper/live accuracy tracking, tagged with `isTestFixture = false` for real runs.
     */
    suspend fun persistMultiHorizonEvaluations(
        result: ForecastResult,
        entryPrice: Double,
        isTestFixture: Boolean = false
    ) {
        if (entryPrice <= 0.0 || result.horizons.isEmpty()) return
        for ((horizonMin, h) in result.horizons) {
            val targetTs = result.asOfTimestampMs + horizonMin * 60_000L
            val entity = ForecastEvaluationEntity(
                symbol = result.symbol,
                horizonMinutes = horizonMin,
                forecastTimestampMs = result.asOfTimestampMs,
                targetTimestampMs = targetTs,
                modelId = result.modelId,
                modelVersion = result.modelVersion,
                operatingMode = result.operatingMode.name,
                status = result.status.name,
                pDown = h.pDown,
                pFlat = h.pFlat,
                pUp = h.pUp,
                expectedReturnPct = h.expectedReturnPct,
                q10Pct = h.q10Pct,
                q50Pct = h.q50Pct,
                q90Pct = h.q90Pct,
                confidence = h.normalizedConfidence,
                entropy = h.shannonEntropy,
                oodScore = result.oodScore,
                latencyMs = result.inferenceLatencyMs,
                entryPrice = entryPrice,
                headConsistent = h.headConsistent,
                isTestFixture = isTestFixture,
                dataTrustTag = if (isTestFixture) "TEST_FIXTURE" else "PRODUCTION_OBSERVED"
            )
            recordForecastEvaluation(entity)
        }
    }

    override fun observeEvaluations(limit: Int): Flow<List<ForecastEvaluationEntity>> {
        return dao.observeForecastEvaluations(limit)
    }

    override suspend fun recordForecastEvaluation(entity: ForecastEvaluationEntity) {
        dao.insertForecastEvaluation(entity)
    }

    override suspend fun resolvePendingEvaluations(
        symbol: String,
        currentTimestampMs: Long,
        currentPrice: Double,
        costBudgetPct: Double
    ): Int {
        if (currentPrice <= 0.0) return 0
        val pending = dao.getUnresolvedForecastEvaluations(symbol, currentTimestampMs)
        var resolvedCount = 0
        for (item in pending) {
            if (item.entryPrice <= 0.0) continue
            val realizedRetPct = ((currentPrice - item.entryPrice) / item.entryPrice) * 100.0
            val horizonScale = when {
                item.horizonMinutes <= 15 -> 1.0
                item.horizonMinutes <= 60 -> 1.6
                else -> 2.6
            }
            val thresholdPct = max(0.08, costBudgetPct * horizonScale)
            val outcomeClass = when {
                realizedRetPct > thresholdPct -> 2 // UP
                realizedRetPct < -thresholdPct -> 0 // DOWN
                else -> 1 // FLAT
            }
            val y0 = if (outcomeClass == 0) 1.0 else 0.0
            val y1 = if (outcomeClass == 1) 1.0 else 0.0
            val y2 = if (outcomeClass == 2) 1.0 else 0.0
            val brier = ((item.pDown - y0) * (item.pDown - y0) +
                (item.pFlat - y1) * (item.pFlat - y1) +
                (item.pUp - y2) * (item.pUp - y2)) / 3.0

            dao.updateForecastEvaluation(
                item.copy(
                    resolved = true,
                    realizedPrice = currentPrice,
                    realizedReturnPct = realizedRetPct,
                    realizedClass = outcomeClass,
                    brierScore = brier
                )
            )
            resolvedCount++
        }
        return resolvedCount
    }

    override suspend fun getRollingCalibrationMetrics(
        horizonMinutes: Int,
        windowSize: Int
    ): Pair<Double, Double> {
        val resolved = dao.getRecentResolvedEvaluations(horizonMinutes, windowSize)
        if (resolved.isEmpty()) {
            val hCalib = modelRegistry.getActiveArtifacts()?.calibration?.config?.horizons?.get(horizonMinutes.toString())
            return Pair(hCalib?.validationBrierScore ?: 0.18, hCalib?.validationEce ?: 0.04)
        }
        var brierSum = 0.0
        var eceAbsSum = 0.0
        for (row in resolved) {
            brierSum += (row.brierScore ?: 0.20)
            val probs = doubleArrayOf(row.pDown, row.pFlat, row.pUp)
            var predClass = 0
            for (c in 1..2) {
                if (probs[c] > probs[predClass]) predClass = c
            }
            val hit = if (row.realizedClass == predClass) 1.0 else 0.0
            eceAbsSum += abs(probs[predClass] - hit)
        }
        val n = resolved.size.toDouble()
        return Pair(brierSum / n, eceAbsSum / n)
    }

    suspend fun refreshAggregateReport(
        operatingMode: ForecastOperatingMode,
        dataTrustState: DataTrustState
    ): MultiHorizonForecastReport {
        val artifacts = modelRegistry.getActiveArtifacts()
        val (brier15, ece15) = getRollingCalibrationMetrics(15, 50)
        val (brier60, _) = getRollingCalibrationMetrics(60, 50)
        val (brier240, _) = getRollingCalibrationMetrics(240, 50)

        val resolved15 = dao.getRecentResolvedEvaluations(15, 50)
        val hitRate15 = if (resolved15.isNotEmpty()) {
            var hits = 0
            for (r in resolved15) {
                val probs = doubleArrayOf(r.pDown, r.pFlat, r.pUp)
                var argmax = 0
                if (probs[1] > probs[argmax]) argmax = 1
                if (probs[2] > probs[argmax]) argmax = 2
                if (r.realizedClass == argmax) hits++
            }
            (hits.toDouble() / resolved15.size.toDouble()) * 100.0
        } else {
            0.0
        }

        val (p50, p95, p99) = computeLatencyPercentiles()
        val anyInconsistent = latestResultsBySymbol.values.any { !it.headConsistent }
        val fallbackActive = artifacts == null || brier15 > 0.28 || anyInconsistent
        val fallbackReason = when {
            artifacts == null -> modelRegistry.getLastLoadError() ?: "Model bundle unavailable"
            anyInconsistent -> "Head contradiction detected; ML execution blocked"
            brier15 > 0.28 -> "Rolling 15m Brier score ${"%.3f".format(brier15)} > 0.28 threshold"
            else -> null
        }

        val report = MultiHorizonForecastReport(
            generatedAtMs = System.currentTimeMillis(),
            activeModelId = artifacts?.bundle?.modelId ?: "NONE",
            activeModelVersion = artifacts?.bundle?.modelVersion ?: "0.0.0",
            operatingMode = operatingMode,
            dataTrustState = dataTrustState,
            fallbackActive = fallbackActive,
            fallbackReason = fallbackReason,
            symbolReports = latestResultsBySymbol.toMap(),
            rollingBrierScore15m = brier15,
            rollingBrierScore1h = brier60,
            rollingBrierScore4h = brier240,
            rollingEce15m = ece15,
            rollingHitRate15m = hitRate15,
            p50LatencyMs = p50,
            p95LatencyMs = p95,
            p99LatencyMs = p99
        )
        _latestReport.value = report

        _diagnostics.value = ForecastDiagnosticsSnapshot(
            modelId = report.activeModelId,
            modelVersion = report.activeModelVersion,
            artifactFormat = artifacts?.bundle?.artifactFormat ?: "NONE",
            schemaHash = artifacts?.bundle?.featureSchemaHash ?: "NONE",
            operatingMode = operatingMode,
            dataTrustState = dataTrustState,
            isHealthy = !fallbackActive,
            fallbackReason = fallbackReason,
            rollingBrier15m = brier15,
            rollingEce15m = ece15,
            rollingHitRate15m = hitRate15,
            featureDriftPsi = driftMonitor?.getRollingPsi() ?: 0.0,
            oodRatePct = driftMonitor?.getOodRatePct() ?: 0.0,
            p50LatencyMs = p50,
            p95LatencyMs = p95,
            p99LatencyMs = p99,
            totalInferences = totalInferences.get(),
            shadowEvaluationsCount = resolved15.size
        )

        return report
    }

    private fun recordLatency(ms: Long) {
        synchronized(latencyRing) {
            latencyRing[latencyCursor] = ms
            latencyCursor = (latencyCursor + 1) % latencyRing.size
            if (latencyCount < latencyRing.size) latencyCount++
        }
    }

    private fun computeLatencyPercentiles(): Triple<Long, Long, Long> {
        synchronized(latencyRing) {
            if (latencyCount == 0) return Triple(2L, 4L, 6L)
            val sorted = latencyRing.copyOfRange(0, latencyCount).sortedArray()
            fun pct(p: Double): Long {
                val idx = min(sorted.size - 1, max(0, (p * (sorted.size - 1)).toInt()))
                return sorted[idx]
            }
            return Triple(pct(0.50), pct(0.95), pct(0.99))
        }
    }
}
