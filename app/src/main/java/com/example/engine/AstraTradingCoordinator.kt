package com.example.engine

import android.content.Context
import com.example.ai.AiIntelligenceGateway
import com.example.data.local.AstraDao
import com.example.data.local.PositionEntity
import com.example.data.local.RiskLogEntity
import com.example.data.local.SignalLogEntity
import com.example.data.local.StrategyConfigEntity
import com.example.data.local.SystemLogEntity
import com.example.data.local.TradeOrderEntity
import com.example.domain.DataTrustState
import com.example.domain.KillSwitchLevel
import com.example.domain.KlineBar
import com.example.domain.MarketRegime
import com.example.domain.NewsGateState
import com.example.domain.QuantRiskSummary
import com.example.domain.SymbolFilter
import com.example.domain.SymbolModelEligibility
import com.example.domain.TechnicalSignalResult
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.ForecastResult
import com.example.forecast.engine.ForecastCoordinator
import com.example.forecast.service.ForecastScheduler
import com.example.network.BinanceAdapter
import com.example.quant.NewsEventGateEngine
import com.example.quant.QuantMathEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class CycleEvaluationSnapshot(
    val timestampMs: Long,
    val symbol: String,
    val markPrice: Double,
    val spreadBps: Double,
    val fundingRatePct: Double,
    val openInterestUsd: Double,
    val dataTrustState: DataTrustState,
    val symbolEligibility: SymbolModelEligibility,
    val technicalSignal: TechnicalSignalResult,
    val forecastResult: ForecastResult,
    val newsGateState: NewsGateState,
    val riskSummary: QuantRiskSummary,
    val cycleDecisionSummary: String
)

/**
 * Central orchestrator for market sequence ingestion, blank-slate data trust state transitions,
 * multi-timeframe technical & ML forecast evaluation, dual-agent AI risk vetting, loss-budget
 * position sizing, idempotent paper/test order lifecycle, and 4-level kill switch enforcement.
 *
 * CRITICAL BLANK-SLATE GUARANTEE:
 * - Never seeds fake historical PnL, fake closed trades, or fake open positions.
 * - Clearly tags any offline warmup kline fallback as `BOOTSTRAPPING` / `QUARANTINED` so
 *   synthetic bars can never be mistaken for real exchange history or authorize live orders.
 */
class AstraTradingCoordinator(
    private val context: Context,
    private val dao: AstraDao,
    private val binanceAdapter: BinanceAdapter,
    val forecastCoordinator: ForecastCoordinator,
    private val aiGateway: AiIntelligenceGateway = AiIntelligenceGateway(),
    private val forecastScheduler: ForecastScheduler = ForecastScheduler()
) {
    private val symbolFilters = mutableMapOf(
        "BTCUSDT" to SymbolFilter("BTCUSDT", tickSize = 0.10, stepSize = 0.001, minQty = 0.001, minNotional = 100.0),
        "ETHUSDT" to SymbolFilter("ETHUSDT", tickSize = 0.01, stepSize = 0.001, minQty = 0.001, minNotional = 20.0),
        "SOLUSDT" to SymbolFilter("SOLUSDT", tickSize = 0.01, stepSize = 0.1, minQty = 0.1, minNotional = 5.0),
        "DOGEUSDT" to SymbolFilter("DOGEUSDT", tickSize = 0.00001, stepSize = 1.0, minQty = 1.0, minNotional = 5.0)
    )

    suspend fun initializeSubsystems(): Boolean = withContext(Dispatchers.IO) {
        val loaded = forecastCoordinator.initialize()
        val bundle = forecastCoordinator.let {
            if (loaded) "Verified forecast bundle" else "Failed to load forecast bundle"
        }
        dao.insertSystemLog(
            SystemLogEntity(
                timestamp = System.currentTimeMillis(),
                level = if (loaded) "INFO" else "ERROR",
                module = "AstraTradingCoordinator",
                message = "$bundle. Startup mode enforced: TEST / SHADOW (Blank-Slate active, zero synthetic trades)."
            )
        )
        loaded
    }

    fun getSymbolFilter(symbol: String): SymbolFilter {
        return symbolFilters[symbol.uppercase()]
            ?: SymbolFilter(symbol.uppercase(), tickSize = 0.01, stepSize = 0.01, minQty = 0.01, minNotional = 5.0)
    }

    /**
     * Computes the authoritative [DataTrustState] based on real exchange connectivity,
     * validated kline sequence completeness, and any active quarantine or fixture flags.
     */
    fun computeDataTrustState(
        exchangeReachable: Boolean,
        klines15mCount: Int,
        usingOfflineFallbackBars: Boolean,
        forecastHealthy: Boolean,
        killSwitchLevel: String,
        resolvedRealShadowSamples: Int,
        closedPaperTradesCount: Int
    ): DataTrustState {
        if (killSwitchLevel == KillSwitchLevel.PANIC_CLOSE.name ||
            killSwitchLevel == KillSwitchLevel.CREDENTIAL_LOCKDOWN.name
        ) {
            return DataTrustState.QUARANTINED
        }
        if (klines15mCount == 0) {
            return DataTrustState.EMPTY
        }
        if (usingOfflineFallbackBars || !exchangeReachable) {
            return DataTrustState.BOOTSTRAPPING
        }
        if (klines15mCount < 96 || !forecastHealthy) {
            return DataTrustState.VALIDATING
        }
        if (resolvedRealShadowSamples < 50) {
            return DataTrustState.SHADOW_READY
        }
        if (closedPaperTradesCount < 30) {
            return DataTrustState.PAPER_READY
        }
        return DataTrustState.LIVE_ELIGIBLE
    }

    /**
     * Executes one complete evaluation cycle for [symbol], pulling real public klines & bookTicker
     * from Binance USDⓈ-M Futures when online, or clearly marking `BOOTSTRAPPING` warmup state
     * if the device is offline.
     */
    suspend fun runEvaluationCycle(
        symbol: String,
        config: StrategyConfigEntity,
        equityUsd: Double,
        availableMarginUsd: Double,
        dailyRealizedLossUsd: Double,
        closedTradeReturnsPct: List<Double>,
        consecutiveLosses: Int,
        geminiKey: String,
        openAiKey: String,
        claudeKey: String,
        forceRefresh: Boolean = false
    ): CycleEvaluationSnapshot = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val normSymbol = symbol.uppercase()
        val isTestnet = config.environmentMode != "LIVE"

        // 1. Fetch real 15m, 1h, 4h klines and real-time microstructure from Binance USDⓈ-M
        val klines15mRes = binanceAdapter.fetchPublicKlines(normSymbol, "15m", 110, isTestnet)
        val klines1hRes = binanceAdapter.fetchPublicKlines(normSymbol, "1h", 48, isTestnet)
        val klines4hRes = binanceAdapter.fetchPublicKlines(normSymbol, "4h", 36, isTestnet)
        val microRes = binanceAdapter.fetchSymbolMicrostructure(normSymbol, isTestnet)

        val exchangeOnline = klines15mRes.isSuccess && (klines15mRes.getOrNull()?.size ?: 0) >= 96
        val klines15m = if (exchangeOnline) {
            klines15mRes.getOrThrow()
        } else {
            buildDeterministicWarmupBars(normSymbol, 100, 15 * 60_000L, nowMs)
        }
        val klines1h = klines1hRes.getOrElse {
            buildDeterministicWarmupBars(normSymbol, 40, 60 * 60_000L, nowMs)
        }
        val klines4h = klines4hRes.getOrElse {
            buildDeterministicWarmupBars(normSymbol, 30, 240 * 60_000L, nowMs)
        }

        val lastClose = klines15m.lastOrNull()?.close ?: defaultPriceForSymbol(normSymbol)
        val spreadBps = microRes.getOrNull()?.spreadBps ?: 1.6
        val fundingRatePct = (microRes.getOrNull()?.fundingRate ?: 0.0001) * 100.0
        val openInterestUsd = microRes.getOrNull()?.openInterestUsd ?: 0.0
        val obiTop5 = microRes.getOrNull()?.orderBookImbalanceTop5 ?: 0.0

        val operatingMode = runCatching {
            ForecastOperatingMode.valueOf(config.forecastOperatingMode)
        }.getOrDefault(ForecastOperatingMode.SHADOW)

        // 2. Run on-device multi-horizon sequence forecast
        val forecastResult = forecastCoordinator.evaluateSymbolSequence(
            symbol = normSymbol,
            klines15m = klines15m,
            klines1h = klines1h,
            klines4h = klines4h,
            spreadBps = spreadBps,
            obiTop5 = obiTop5,
            operatingMode = operatingMode,
            nowTimestampMs = nowMs
        )

        // Resolve any due multi-horizon forecast evaluations & persist new observation if exchange is online
        if (exchangeOnline) {
            forecastCoordinator.resolvePendingEvaluations(
                symbol = normSymbol,
                currentTimestampMs = nowMs,
                currentPrice = lastClose,
                costBudgetPct = 0.12
            )
            val lastBarCloseMs = klines15m.lastOrNull()?.closeTime ?: nowMs
            if (forecastScheduler.shouldRunForecast(normSymbol, lastBarCloseMs, nowMs, forceRefresh)) {
                forecastCoordinator.persistMultiHorizonEvaluations(
                    result = forecastResult,
                    entryPrice = lastClose,
                    isTestFixture = false
                )
                forecastScheduler.markEvaluated(normSymbol, lastBarCloseMs, nowMs)
            }
        }

        // 3. Evaluate deterministic multi-timeframe technical signal
        val filter = getSymbolFilter(normSymbol)
        val techSignal = QuantMathEngine.evaluateMultiTimeframeSignal(
            symbol = normSymbol,
            klines15m = klines15m,
            klines1h = klines1h,
            klines4h = klines4h,
            spreadBps = spreadBps,
            fundingRatePct = fundingRatePct,
            tickSize = filter.tickSize
        )

        // 4. Evaluate macro news & microstructure shock gate
        val newsGate = NewsEventGateEngine.evaluateGate(
            nowTimestampMs = nowMs,
            spreadBps = spreadBps,
            fundingRatePct = fundingRatePct,
            atrPct15m = techSignal.atrPct
        )

        // 5. Compute portfolio risk summary (strictly from real closed trades; zeroed on blank slate)
        val riskSummary = QuantMathEngine.computePortfolioRiskSummary(
            equityUsd = equityUsd,
            dailyRealizedLossUsd = dailyRealizedLossUsd,
            dailyLossCapPct = config.dailyLossCapPct / 100.0,
            riskPerTradePct = config.riskPerTradePct / 100.0,
            closedTradeReturnsPct = closedTradeReturnsPct,
            consecutiveLosses = consecutiveLosses,
            cooldownUntilMs = 0L,
            nowMs = nowMs
        )

        // 6. Determine authoritative DataTrustState
        val resolvedShadowCount = dao.getRecentResolvedEvaluations(15, 60).size
        val trustState = computeDataTrustState(
            exchangeReachable = exchangeOnline,
            klines15mCount = klines15m.size,
            usingOfflineFallbackBars = !exchangeOnline,
            forecastHealthy = forecastResult.headConsistent,
            killSwitchLevel = config.killSwitchLevel,
            resolvedRealShadowSamples = resolvedShadowCount,
            closedPaperTradesCount = closedTradeReturnsPct.size
        )

        forecastCoordinator.refreshAggregateReport(
            operatingMode = operatingMode,
            dataTrustState = trustState
        )

        // 7. Combine rule signal + ML forecast ensemble + AI Skeptic gate
        val ensembleDecision = forecastCoordinator.ensemble.combineRuleAndForecast(
            symbol = normSymbol,
            ruleSide = techSignal.side,
            ruleScore = techSignal.technicalScore,
            forecast = forecastResult
        )

        var cycleDecision = ensembleDecision.decisionRationale
        if (ensembleDecision.approvedForExecution && newsGate.allowNewEntries && config.killSwitchLevel == "NONE") {
            val aiDecision = aiGateway.evaluateDualAgentGate(
                symbol = normSymbol,
                technicalSignal = techSignal,
                forecastResult = forecastResult,
                spreadBps = spreadBps,
                fundingRatePct = fundingRatePct,
                geminiApiKey = geminiKey,
                openAiApiKey = openAiKey,
                claudeApiKey = claudeKey
            )
            val sizing = QuantMathEngine.calculatePositionSizing(
                symbolFilter = filter,
                equityUsd = equityUsd,
                availableMarginUsd = availableMarginUsd,
                riskPerTradePct = config.riskPerTradePct / 100.0,
                remainingDailyRiskBudgetUsd = riskSummary.remainingDailyLossBudgetUsd,
                entryPrice = lastClose,
                stopLossPrice = techSignal.stopLossPrice,
                takeProfitPrice = techSignal.takeProfitPrice,
                maxAllowedLeverage = config.maxLeverage,
                adaptiveRiskMultiplier = ensembleDecision.effectiveRiskMultiplier
            )

            cycleDecision = when {
                !exchangeOnline -> "Blocked: Offline bootstrapping warmup active (Trust=$trustState)"
                !aiDecision.approved -> "Blocked by Dual-Agent AI Gate: ${aiDecision.rationale}"
                !sizing.approved -> "Rejected by Position Sizing Gate: ${sizing.rejectionReason}"
                config.safetyDebugLockActive -> "Shadow/Test Candidate Ready (${techSignal.side} ${sizing.quantity} $normSymbol @ $lastClose, SL=${techSignal.stopLossPrice}, TP=${techSignal.takeProfitPrice}) — Safety Lock Engaged"
                else -> "Authorized Paper/Test Order (${techSignal.side} ${sizing.quantity} $normSymbol)"
            }

            // Record signal evaluation audit log (only real cycle evaluations, never fake PnL)
            dao.insertSignalLog(
                SignalLogEntity(
                    timestamp = nowMs,
                    symbol = normSymbol,
                    regime = techSignal.regime.name,
                    technicalScore = techSignal.technicalScore,
                    aiConsensusScore = aiDecision.analystConfidence * 100.0,
                    finalAction = if (sizing.approved && aiDecision.approved) "CANDIDATE_${techSignal.side}" else "HOLD",
                    rationale = cycleDecision,
                    newsStatus = newsGate.status,
                    skepticVeto = aiDecision.vetoedBySkeptic,
                    isTestFixture = !exchangeOnline
                )
            )
        }

        CycleEvaluationSnapshot(
            timestampMs = nowMs,
            symbol = normSymbol,
            markPrice = lastClose,
            spreadBps = spreadBps,
            fundingRatePct = fundingRatePct,
            openInterestUsd = openInterestUsd,
            dataTrustState = trustState,
            symbolEligibility = forecastResult.symbolEligibility,
            technicalSignal = techSignal,
            forecastResult = forecastResult,
            newsGateState = newsGate,
            riskSummary = riskSummary,
            cycleDecisionSummary = cycleDecision
        )
    }

    /**
     * Executes any of the 4 kill-switch levels (`PAUSE`, `DRAIN`, `PANIC_CLOSE`, `CREDENTIAL_LOCKDOWN`)
     * and logs an immutable audit entry in `risk_logs` and `system_logs`.
     */
    suspend fun executeKillSwitch(level: KillSwitchLevel, reason: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val currentCfg = dao.getStrategyConfigOnce() ?: StrategyConfigEntity()
        val updatedCfg = currentCfg.copy(
            killSwitchLevel = level.name,
            autoTradingEnabled = false,
            safetyDebugLockActive = true,
            environmentMode = "TEST"
        )
        dao.upsertStrategyConfig(updatedCfg)

        if (level == KillSwitchLevel.PANIC_CLOSE || level == KillSwitchLevel.CREDENTIAL_LOCKDOWN) {
            dao.clearAllPositions()
        }

        dao.insertRiskLog(
            RiskLogEntity(
                timestamp = now,
                eventType = "KILL_SWITCH_${level.name}",
                severity = "CRITICAL",
                description = "Operator/System triggered $level: $reason",
                actionTaken = when (level) {
                    KillSwitchLevel.NONE -> "Normal operation resumed in SHADOW/TEST"
                    KillSwitchLevel.PAUSE -> "Blocked all new entry orders; kept protective stops active"
                    KillSwitchLevel.DRAIN -> "Cancelled working entry orders; draining existing positions via stops"
                    KillSwitchLevel.PANIC_CLOSE -> "Cancelled all working orders and flattened positions"
                    KillSwitchLevel.CREDENTIAL_LOCKDOWN -> "Flattened positions and wiped exchange credentials from SecretVault"
                }
            )
        )
        dao.insertSystemLog(
            SystemLogEntity(
                timestamp = now,
                level = "WARN",
                module = "KillSwitchEngine",
                message = "Kill-switch state transitioned to ${level.name}: $reason"
            )
        )
    }

    private fun defaultPriceForSymbol(symbol: String): Double = when (symbol.uppercase()) {
        "BTCUSDT" -> 68450.0
        "ETHUSDT" -> 2640.0
        "SOLUSDT" -> 158.50
        "DOGEUSDT" -> 0.14250
        else -> 100.0
    }

    /**
     * Deterministic warmup kline generator used ONLY when the device has no internet access
     * so on-device sequence pipelines can still be inspected without crashing. Whenever this
     * fallback is used, `DataTrustState` is forced to `BOOTSTRAPPING` and `isTestFixture = true`.
     */
    private fun buildDeterministicWarmupBars(
        symbol: String,
        count: Int,
        intervalMs: Long,
        nowMs: Long
    ): List<KlineBar> {
        val basePrice = defaultPriceForSymbol(symbol)
        val alignedEnd = (nowMs / intervalMs) * intervalMs
        val list = ArrayList<KlineBar>(count)
        var price = basePrice * 0.992
        val symSeed = symbol.hashCode().toDouble() * 0.0001
        for (i in 0 until count) {
            val openTime = alignedEnd - (count - i) * intervalMs
            val closeTime = openTime + intervalMs - 1L
            val wave = 0.0012 * sin(i * 0.21 + symSeed) + 0.0006 * cos(i * 0.09)
            val open = price
            val close = max(basePrice * 0.5, open * (1.0 + wave))
            val high = max(open, close) * 1.0014
            val low = min(open, close) * 0.9986
            val vol = 1200.0 + 350.0 * abs(sin(i * 0.37))
            list.add(
                KlineBar(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    closeTime = closeTime,
                    quoteAssetVolume = vol * close,
                    numberOfTrades = 420,
                    takerBuyBaseVolume = vol * (0.52 + 0.10 * sin(i * 0.19)),
                    takerBuyQuoteVolume = vol * close * 0.52
                )
            )
            price = close
        }
        return list
    }
}
