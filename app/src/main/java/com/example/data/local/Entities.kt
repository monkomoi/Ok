package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val environmentMode: String = "TEST",
    val binanceEndpointTarget: String = "MAINNET",
    val botState: String = "READY_TEST",
    val configVersion: Int = 2,
    val declaredCapitalVnd: Long = 500_000L,
    val usdtVndRate: String = "25500",
    val usdtVndUpdatedAtMs: Long = 0L,
    val riskPerTradePct: String = "0.0025",
    val dailyLossLimitPct: String = "0.01",
    val maxDrawdownLimitPct: String = "0.05",
    val maxConsecutiveLosses: Int = 3,
    val cooldownMinutesAfterLoss: Int = 30,
    val defaultLeverageCap: Int = 5,
    val maxSpreadPct: String = "0.0012",
    val minRelativeVolume: String = "1.20",
    val stopTriggerWorkingType: String = "MARK_PRICE",
    val maxThermalCelsius: Float = 41.0f,
    val minAvailableRamMb: Long = 450L,
    val telegramUserIdWhitelist: String = "",
    val telegramChatIdWhitelist: String = "",
    val withdrawDisabledConfirmed: Boolean = false,
    val riskLimitsReviewed: Boolean = false,
    val liveExplicitlyArmed: Boolean = false,
    val activeCircuitBreakerReason: String? = null,
    val executionLockOwner: String? = null,
    val executionLockExpiresAtMs: Long = 0L,
    val forecastOperatingMode: String = "SHADOW",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "market_candles",
    primaryKeys = ["symbol", "interval", "openTime"],
    indices = [Index(value = ["symbol", "interval", "closeTime"])]
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
    val isClosed: Boolean = true
)

@Entity(
    tableName = "market_features",
    primaryKeys = ["symbol", "asOfUtcMs"]
)
data class MarketFeatureEntity(
    val symbol: String,
    val asOfUtcMs: Long,
    val featureVersion: String,
    val snapshotHash: String,
    val regime: String,
    val atrPct: String,
    val relativeVolume: String,
    val spreadPct: String,
    val fundingRate: String,
    val breakoutUp: Boolean,
    val breakoutDown: Boolean
)

@Entity(
    tableName = "signals",
    indices = [Index(value = ["symbol", "createdAtMs"])]
)
data class SignalEntity(
    @PrimaryKey val signalId: String,
    val symbol: String,
    val direction: String,
    val regime: String,
    val entryPrice: String,
    val stopPrice: String,
    val targetPrice: String,
    val netEvPct: String,
    val pWinCalibrated: String,
    val skepticVerdict: String,
    val approvedByRisk: Boolean,
    val rejectReasonVi: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
)

@Entity(tableName = "ai_decisions")
data class AiDecisionEntity(
    @PrimaryKey val decisionId: String,
    val symbol: String,
    val providerId: String,
    val modelId: String,
    val role: String,
    val directionCandidate: String,
    val score: String,
    val calibratedProbability: String,
    val recommendation: String,
    val skepticVerdict: String,
    val validationStatus: String,
    val reasonVi: String,
    val latencyMs: Long,
    val createdAtMs: Long
)

@Entity(tableName = "ai_requests")
data class AiRequestEntity(
    @PrimaryKey val requestId: String,
    val correlationId: String,
    val providerId: String,
    val modelId: String,
    val purpose: String,
    val latencyMs: Long,
    val estimatedPromptTokens: Int,
    val status: String,
    val symbol: String = "GLOBAL",
    val requestedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "order_intents",
    indices = [
        Index(value = ["clientOrderId"], unique = true),
        Index(value = ["symbol", "status"])
    ]
)
data class OrderIntentEntity(
    @PrimaryKey val intentId: String,
    val clientOrderId: String,
    val signalId: String,
    val decisionId: String,
    val sizingId: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val quantity: String,
    val price: String,
    val stopPrice: String,
    val reduceOnly: Boolean,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "orders",
    indices = [Index(value = ["symbol", "status"])]
)
data class OrderRecordEntity(
    @PrimaryKey val clientOrderId: String,
    val exchangeOrderId: String,
    val symbol: String,
    val side: String,
    val type: String,
    val origQty: String,
    val executedQty: String,
    val avgPrice: String,
    val stopPrice: String,
    val status: String,
    val reduceOnly: Boolean,
    val workingType: String,
    val updatedAtMs: Long
)

@Entity(
    tableName = "fills",
    indices = [Index(value = ["symbol", "executedAtMs"])]
)
data class FillEntity(
    @PrimaryKey val tradeId: String,
    val clientOrderId: String,
    val exchangeOrderId: String,
    val symbol: String,
    val side: String,
    val quantity: String,
    val price: String,
    val commissionUsdt: String,
    val commissionAsset: String,
    val realizedPnlUsdt: String,
    val executedAtMs: Long,
    val grossPnlUsdt: String = "0.0000",
    val fundingUsdt: String = "0.0000",
    val pnlStatus: String = "VERIFIED",
    val isEstimated: Boolean = false,
    val signalId: String = "",
    val decisionId: String = "",
    val positionLifecycleId: String = "",
    val predictedScoreAtEntry: String = "0.5000",
    val calibratedProbAtEntry: String = "0.5000",
    val reasonVi: String = ""
)

@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val positionId: String,
    val symbol: String,
    val side: String,
    val signedQuantity: String,
    val entryAvgPrice: String,
    val markPrice: String,
    val unrealizedPnlUsdt: String,
    val leverage: Int,
    val marginType: String,
    val stopPrice: String,
    val targetPrice: String,
    val netBreakevenEstimate: String,
    val protectiveSlOrderId: String,
    val protectiveTpOrderId: String,
    val protectiveSlVerified: Boolean,
    val protectionState: String,
    val openedAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "risk_events",
    indices = [Index(value = ["timestampMs"])]
)
data class RiskEventEntity(
    @PrimaryKey val eventId: String,
    val category: String,
    val severity: String,
    val symbol: String,
    val actionTaken: String,
    val messageVi: String,
    val timestampMs: Long
)

@Entity(
    tableName = "system_events",
    indices = [Index(value = ["timestampMs"])]
)
data class SystemEventEntity(
    @PrimaryKey val eventId: String,
    val type: String,
    val level: String,
    val messageVi: String,
    val timestampMs: Long
)

@Entity(tableName = "daily_snapshots")
data class DailySnapshotEntity(
    @PrimaryKey val dateKey: String,
    val startEquityUsdt: String,
    val currentEquityUsdt: String,
    val peakEquityUsdt: String,
    val availableBalanceUsdt: String,
    val realizedPnlUsdt: String,
    val unrealizedPnlUsdt: String,
    val totalFeesUsdt: String,
    val totalFundingUsdt: String,
    val netExternalCashflowUsdt: String,
    val cashflowAdjustedPnlUsdt: String,
    val consecutiveLosses: Int,
    val updatedAtMs: Long
)

@Entity(tableName = "cashflows")
data class CashflowEntity(
    @PrimaryKey val cashflowId: String,
    val type: String,
    val amountUsdt: String,
    val noteVi: String,
    val verifiedFromExchange: Boolean,
    val timestampMs: Long
)

@Entity(tableName = "funding_ledger")
data class FundingLedgerEntity(
    @PrimaryKey val tranId: String,
    val symbol: String,
    val incomeUsdt: String,
    val asset: String,
    val timestampMs: Long
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val role: String,
    val protocol: String = "OPENAI_COMPATIBLE",
    val enabled: Boolean = false,
    val priorityOrder: Int = 1,
    val timeoutMs: Long = 8000L,
    val lastLatencyMs: Long = 0L,
    val successRatePct: String = "100.0",
    val lastVerifiedStatus: String = "UNVERIFIED",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "telegram_updates")
data class TelegramUpdateEntity(
    @PrimaryKey val updateId: Long,
    val userId: String,
    val chatId: String,
    val command: String,
    val outcomeStatus: String,
    val processedAtMs: Long
)

@Entity(tableName = "forecast_models")
data class ForecastModelEntity(
    @PrimaryKey val modelId: String,
    val modelVersion: String,
    val architecture: String,
    val runtimeBackend: String,
    val featureSchemaHash: String,
    val modelSha256: String,
    val lookbackLength: Int,
    val featureCount: Int,
    val operatingMode: String,
    val isActive: Boolean,
    val isRollbackTarget: Boolean,
    val registeredAtMs: Long
)

@Entity(
    tableName = "forecast_predictions",
    indices = [
        Index(value = ["symbol", "horizon", "asOfCloseTimeMs"]),
        Index(value = ["generatedAtMs"])
    ]
)
data class ForecastPredictionEntity(
    @PrimaryKey val predictionId: String,
    val symbol: String,
    val timeframe: String,
    val horizon: String,
    val asOfCloseTimeMs: Long,
    val generatedAtMs: Long,
    val expiresAtMs: Long,
    val modelId: String,
    val modelVersion: String,
    val featureSchemaVersion: String,
    val pUp: String,
    val pFlat: String,
    val pDown: String,
    val expectedReturnPct: String,
    val predictedVolatilityPct: String,
    val q10Pct: String,
    val q50Pct: String,
    val q90Pct: String,
    val uncertainty: String,
    val oodScore: String,
    val status: String,
    val operatingMode: String,
    val entryPriceSnapshot: String,
    val inferenceLatencyMs: Long
)

@Entity(
    tableName = "forecast_outcomes",
    indices = [
        Index(value = ["predictionId"], unique = true),
        Index(value = ["symbol", "horizon", "resolvedAtMs"])
    ]
)
data class ForecastOutcomeEntity(
    @PrimaryKey val outcomeId: String,
    val predictionId: String,
    val symbol: String,
    val horizon: String,
    val modelId: String,
    val realizedDirection: String,
    val realizedReturnPct: String,
    val brierContribution: String,
    val absErrorPct: String,
    val directionCorrect: Boolean,
    val resolvedAtMs: Long
)

@Entity(tableName = "forecast_metrics")
data class ForecastMetricEntity(
    @PrimaryKey val metricKey: String,
    val modelId: String,
    val symbol: String,
    val horizon: String,
    val sampleCount: Int,
    val brierScore: String,
    val expectedCalibrationError: String,
    val directionalAccuracy: String,
    val maeReturnPct: String,
    val oodRejectRate: String,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val driftDetected: Boolean,
    val updatedAtMs: Long
)
