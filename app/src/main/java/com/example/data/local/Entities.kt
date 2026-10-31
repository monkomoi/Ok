package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val environmentMode: String = "TEST", // TEST (Khóa đặt lệnh thật) | LIVE (Đặt lệnh thật sau khi mở khóa)
    val binanceEndpointTarget: String = "MAINNET", // MAINNET (https://fapi.binance.com) | TESTNET (https://testnet.binancefuture.com)
    val botState: String = "READY_TEST",
    val configVersion: Int = 1,
    val declaredCapitalVnd: Long = 150_000L,
    val usdtVndRate: String = "25450.0",
    val usdtVndUpdatedAtMs: Long = System.currentTimeMillis(),
    val riskPerTradePct: String = "0.005", // 0.5% default
    val dailyLossLimitPct: String = "0.015", // 1.5% default
    val maxDrawdownLimitPct: String = "0.050", // 5.0% default
    val maxConsecutiveLosses: Int = 2,
    val cooldownMinutesAfterLoss: Int = 60,
    val defaultLeverageCap: Int = 2,
    val maxSpreadPct: String = "0.0012",
    val minRelativeVolume: String = "1.25",
    val stopTriggerWorkingType: String = "MARK_PRICE", // MARK_PRICE | CONTRACT_PRICE
    val maxThermalCelsius: Float = 41.0f,
    val minAvailableRamMb: Long = 450L,
    val telegramChatIdWhitelist: String = "",
    val telegramUserIdWhitelist: String = "",
    val withdrawDisabledConfirmed: Boolean = false,
    val riskLimitsReviewed: Boolean = false,
    val liveExplicitlyArmed: Boolean = false,
    val activeCircuitBreakerReason: String? = null,
    val executionLockOwner: String? = null,
    val executionLockExpiresAtMs: Long = 0L,
    val forecastOperatingMode: String = "SHADOW", // SHADOW | ADVISORY_TEST_ONLY
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val apiType: String, // GEMINI | OPENAI_COMPATIBLE
    val baseUrl: String,
    val modelId: String,
    val role: String, // FAST_ANALYST | NEWS_ANALYST | SKEPTIC | BACKUP
    val secretSlot: String,
    val timeoutMs: Long = 12_000L,
    val enabled: Boolean = false,
    val healthStatus: String = "UNCONFIGURED", // UNCONFIGURED | VERIFIED_HEALTHY | COOLDOWN_429 | AUTH_ERROR | DEGRADED
    val cooldownUntilMs: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastErrorSummary: String? = null
)

@Entity(
    tableName = "market_candles",
    primaryKeys = ["symbol", "interval", "openTime"]
)
data class MarketCandleEntity(
    val symbol: String,
    val interval: String,
    val openTime: Long,
    val closeTime: Long,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
    val quoteVolume: String,
    val source: String,
    val isClosed: Boolean
)

/**
 * MOD-02 · Table 4: `market_features` — feature snapshot, timestamp, version, hash & data-quality flags.
 */
@Entity(
    tableName = "market_features",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["createdAtMs"])
    ]
)
data class MarketFeatureEntity(
    @PrimaryKey val snapshotHash: String,
    val symbol: String,
    val asOfUtcMs: Long,
    val featureVersion: String,
    val dataSource: String,
    val markPrice: String,
    val return15m: String,
    val return1h: String,
    val atrPct: String,
    val relativeVolume: String,
    val spreadPct: String,
    val microprice: String,
    val orderBookImbalance: String,
    val primaryRegime: String,
    val isDataStale: Boolean,
    val isEstimated: Boolean,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "signals",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["status"]),
        Index(value = ["createdAtMs"])
    ]
)
data class SignalEntity(
    @PrimaryKey val signalId: String,
    val strategy: String,
    val symbol: String,
    val direction: String, // LONG | SHORT | NONE
    val horizon: String,
    val regime: String,
    val entryPrice: String,
    val stopPrice: String,
    val targetPrice: String,
    val expectedNetReturnPct: String,
    val expectedCostPct: String,
    val status: String, // NEW | VALIDATED | EXPIRED | REJECTED | CONSUMED
    val rejectReasonVi: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val rankingScore: String = "0.5000",
    val predictedWinProbability: String? = null,
    val decisionId: String? = null
)

/**
 * MOD-02 · Table 6: `ai_requests` — provider/model, purpose, timing, token/cost metadata, status (no secret prompts or API keys).
 */
@Entity(
    tableName = "ai_requests",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["correlationId"]),
        Index(value = ["requestedAtMs"])
    ]
)
data class AiRequestEntity(
    @PrimaryKey val requestId: String,
    val providerId: String,
    val modelId: String,
    val purpose: String, // CANDIDATE_ANALYSIS | SKEPTIC_REVIEW | HEALTH_CHECK
    val symbol: String,
    val latencyMs: Long,
    val estimatedPromptTokens: Int,
    val status: String, // SUCCESS | SCHEMA_REJECTED | HTTP_ERROR | TIMEOUT
    val correlationId: String,
    val requestedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_decisions",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["createdAtMs"])
    ]
)
data class AiDecisionEntity(
    @PrimaryKey val decisionId: String,
    val providerId: String,
    val modelId: String,
    val role: String,
    val symbol: String,
    val recommendation: String,
    val directionCandidate: String,
    val score: String?,
    val calibratedProbability: String?,
    val skepticVerdict: String,
    val validationStatus: String, // VALID | SCHEMA_INVALID | EXPIRED
    val reasonVi: String,
    val latencyMs: Long,
    val createdAtMs: Long
)

@Entity(
    tableName = "order_intents",
    indices = [
        Index(value = ["clientOrderId"], unique = true),
        Index(value = ["symbol"]),
        Index(value = ["status"]),
        Index(value = ["signalId"]),
        Index(value = ["createdAtMs"])
    ]
)
data class OrderIntentEntity(
    @PrimaryKey val intentId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String, // BUY | SELL
    val positionDirection: String, // LONG | SHORT
    val positionSide: String = "BOTH", // BOTH | LONG | SHORT
    val orderType: String, // MARKET | LIMIT | STOP_MARKET | TAKE_PROFIT_MARKET
    val quantity: String,
    val intendedEntryPrice: String,
    val intendedStopPrice: String,
    val intendedTargetPrice: String,
    val intendedRiskUsdt: String,
    val environmentMode: String, // TEST | LIVE
    val status: String, // PERSISTED | SUBMITTED | FILLED | PARTIALLY_FILLED | UNKNOWN | REJECTED | CANCELLED
    val exchangeOrderId: String? = null,
    val signalId: String? = null,
    val decisionId: String? = null,
    val sizingId: String? = null,
    val riskApprovalId: String? = null,
    val predictedScoreAtEntry: String? = null,
    val calibratedProbAtEntry: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["clientOrderId"], unique = true),
        Index(value = ["symbol"]),
        Index(value = ["status"]),
        Index(value = ["updatedAtMs"])
    ]
)
data class OrderRecordEntity(
    @PrimaryKey val clientOrderId: String,
    val exchangeOrderId: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val purpose: String, // ENTRY | PROTECTIVE_SL | PROTECTIVE_TP | PANIC_CLOSE | REDUCE_ONLY
    val quantity: String,
    val executedQty: String,
    val avgPrice: String,
    val stopPrice: String?,
    val workingType: String?,
    val status: String, // NEW | WORKING | PARTIALLY_FILLED | FILLED | CANCELED | EXPIRED | REJECTED | UNKNOWN | CANCEL_PENDING
    val isBotOwned: Boolean = true,
    val positionSide: String = "BOTH",
    val isAlgoOrder: Boolean = false,
    val updatedAtMs: Long
)

@Entity(
    tableName = "positions",
    primaryKeys = ["symbol", "positionSide"],
    indices = [
        Index(value = ["symbol", "positionSide"], unique = true),
        Index(value = ["state"]),
        Index(value = ["updatedAtMs"])
    ]
)
data class PositionEntity(
    val symbol: String,
    val signedQuantity: String,
    val entryAvgPrice: String,
    val markPrice: String,
    val unrealizedPnlUsdt: String,
    val isolatedMarginUsdt: String,
    val leverage: Int,
    val stopPrice: String,
    val targetPrice: String,
    val netBreakevenEstimate: String,
    val protectiveSlOrderId: String?,
    val protectiveSlVerified: Boolean,
    val protectiveTpOrderId: String? = null,
    val protectiveTpVerified: Boolean = false,
    val positionSide: String = "BOTH", // BOTH | LONG | SHORT
    val signalId: String? = null,
    val decisionId: String? = null,
    val predictedScoreAtEntry: String? = null,
    val calibratedProbAtEntry: String? = null,
    val ownershipTag: String, // BOT_OWNED | MANUAL_EXTERNAL
    val state: String, // OPEN_PROTECTED | PROTECTION_DEGRADED | PROTECTION_PENDING | PROTECTION_UNKNOWN | PROTECTION_FAILURE | ORPHAN_UNPROTECTED | PANIC_CLOSE_FAILED | CLOSED
    val openedAtMs: Long,
    val updatedAtMs: Long
) {
    val positionKey: String
        get() = "${symbol}_${positionSide}"
}

@Entity(
    tableName = "fills",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["clientOrderId"]),
        Index(value = ["executedAtMs"]),
        Index(value = ["pnlStatus"])
    ]
)
data class FillEntity(
    @PrimaryKey val tradeId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val price: String,
    val quantity: String,
    val realizedPnlUsdt: String,
    val commissionUsdt: String,
    val commissionAsset: String,
    val reasonVi: String,
    val executedAtMs: Long,
    val grossPnlUsdt: String = "0.0000",
    val fundingUsdt: String = "0.0000",
    val pnlStatus: String = "VERIFIED", // VERIFIED | ESTIMATED | ACCOUNTING_PENDING | PNL_UNVERIFIED
    val isEstimated: Boolean = false,
    val exchangeOrderId: String? = null,
    val signalId: String? = null,
    val decisionId: String? = null,
    val predictedScoreAtEntry: String? = null,
    val calibratedProbAtEntry: String? = null,
    val positionLifecycleId: String? = null
)

@Entity(
    tableName = "funding_ledger",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["timestampMs"])
    ]
)
data class FundingLedgerEntity(
    @PrimaryKey val fundingId: String,
    val symbol: String,
    val fundingRate: String,
    val paymentUsdt: String,
    val timestampMs: Long
)

@Entity(
    tableName = "cashflows",
    indices = [
        Index(value = ["timestampMs"])
    ]
)
data class CashflowEntity(
    @PrimaryKey val cashflowId: String,
    val type: String, // DEPOSIT | WITHDRAW | TRANSFER_IN | TRANSFER_OUT
    val amountUsdt: String, // positive for deposit, negative for withdraw
    val noteVi: String,
    val verifiedFromExchange: Boolean,
    val timestampMs: Long
)

@Entity(tableName = "daily_snapshots")
data class DailySnapshotEntity(
    @PrimaryKey val dateKey: String, // YYYY-MM-DD UTC
    val startEquityUsdt: String,
    val currentEquityUsdt: String,
    val availableBalanceUsdt: String,
    val netExternalCashflowUsdt: String,
    val realizedPnlUsdt: String,
    val unrealizedPnlUsdt: String,
    val totalFeesUsdt: String,
    val totalFundingUsdt: String,
    val cashflowAdjustedPnlUsdt: String,
    val peakEquityUsdt: String,
    val consecutiveLosses: Int,
    val updatedAtMs: Long
)

@Entity(
    tableName = "risk_events",
    indices = [
        Index(value = ["triggerCode"]),
        Index(value = ["occurredAtMs"])
    ]
)
data class RiskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerCode: String,
    val severity: String, // INFO | WARNING | CRITICAL
    val actionTaken: String,
    val detailVi: String,
    val acknowledged: Boolean = false,
    val occurredAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "system_events",
    indices = [
        Index(value = ["category"]),
        Index(value = ["correlationId"]),
        Index(value = ["occurredAtMs"])
    ]
)
data class SystemEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // NETWORK | RECONCILE | EXECUTION | AI | TELEGRAM | THERMAL | SECURITY
    val level: String, // INFO | WARN | ERROR
    val titleVi: String,
    val detailVi: String,
    val correlationId: String,
    val occurredAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "telegram_updates",
    indices = [
        Index(value = ["processedAtMs"])
    ]
)
data class TelegramUpdateEntity(
    @PrimaryKey val updateId: Long,
    val chatId: String,
    val userId: String,
    val command: String,
    val status: String,
    val processedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "forecast_predictions",
    indices = [
        Index(value = ["symbol", "horizon", "generatedAtMs"], name = "index_forecast_predictions_symbol_horizon_generatedAtMs"),
        Index(value = ["validUntilMs"], name = "index_forecast_predictions_validUntilMs")
    ]
)
data class ForecastPredictionEntity(
    @PrimaryKey val predictionId: String,
    val symbol: String,
    val baseTimeframe: String,
    val horizon: String,
    val asOfCloseTimeMs: Long,
    val generatedAtMs: Long,
    val validUntilMs: Long,
    val status: String,
    val modelId: String,
    val modelVersion: String,
    val featureSchemaHash: String,
    val pUp: String?,
    val pDown: String?,
    val pFlat: String?,
    val expectedReturnPct: String?,
    val q10Pct: String?,
    val q50Pct: String?,
    val q90Pct: String?,
    val predictedVolatilityPct: String?,
    val uncertainty: String?,
    val outOfDistributionScore: String?,
    val calibrationApplied: Boolean,
    val calibrationMethod: String,
    val calibrationSampleSize: Int,
    val brierScore: String?,
    val expectedCalibrationError: String?,
    val costThresholdPct: String,
    val passesQualityGate: Boolean,
    val advisoryAction: String,
    val reasonVi: String,
    val inferenceLatencyMs: Long
)

@Entity(
    tableName = "forecast_models",
    indices = [
        Index(value = ["isActive"], name = "index_forecast_models_isActive")
    ]
)
data class ForecastModelEntity(
    @PrimaryKey val modelId: String,
    val modelVersion: String,
    val architecture: String,
    val featureSchemaVersion: String,
    val featureSchemaHash: String,
    val runtimeBackend: String,
    val cpuThreads: Int,
    val sha256Checksum: String,
    val isActive: Boolean,
    val isRollback: Boolean,
    val goldenSelfTestPassed: Boolean,
    val registeredAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "forecast_outcomes",
    indices = [
        Index(value = ["symbol", "horizon", "evaluatedAtMs"], name = "index_forecast_outcomes_symbol_horizon_evaluatedAtMs"),
        Index(value = ["predictionId"], name = "index_forecast_outcomes_predictionId")
    ]
)
data class ForecastOutcomeEntity(
    @PrimaryKey val outcomeId: String,
    val predictionId: String,
    val symbol: String,
    val horizon: String,
    val modelId: String,
    val predictedDirection: String,
    val realizedDirection: String,
    val predictedReturnPct: String,
    val realizedReturnPct: String,
    val pUpAtPrediction: String,
    val pDownAtPrediction: String,
    val pFlatAtPrediction: String,
    val brierContribution: String,
    val directionHit: Boolean,
    val evaluatedAtMs: Long
)

@Entity(
    tableName = "forecast_metrics",
    indices = [
        Index(value = ["modelId", "horizon"], name = "index_forecast_metrics_modelId_horizon")
    ]
)
data class ForecastMetricEntity(
    @PrimaryKey val metricKey: String,
    val modelId: String,
    val horizon: String,
    val sampleSize: Int,
    val brierScore: String,
    val expectedCalibrationError: String,
    val directionalAccuracy: String,
    val maeReturnPct: String,
    val oodRejectRate: String,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val updatedAtMs: Long
)

