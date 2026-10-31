package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AstraDao {

    // 1. App Settings
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettingsOnce(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)

    // 2. AI Provider Configs
    @Query("SELECT * FROM provider_configs ORDER BY providerId ASC")
    fun observeProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs ORDER BY providerId ASC")
    suspend fun getProvidersOnce(): List<ProviderConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE providerId = :providerId")
    suspend fun deleteProvider(providerId: String)

    // 3. Market Candles
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<MarketCandleEntity>)

    @Query("SELECT * FROM market_candles WHERE symbol = :symbol AND interval = :interval ORDER BY openTime ASC")
    suspend fun getCandles(symbol: String, interval: String): List<MarketCandleEntity>

    // 4. Market Features (MOD-02)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketFeatureSnapshot(feature: MarketFeatureEntity)

    @Query("SELECT * FROM market_features ORDER BY asOfUtcMs DESC LIMIT 30")
    fun observeMarketFeatures(): Flow<List<MarketFeatureEntity>>

    // 5. Signals
    @Query("SELECT * FROM signals ORDER BY createdAtMs DESC LIMIT 50")
    fun observeRecentSignals(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals ORDER BY createdAtMs DESC LIMIT 100")
    suspend fun getRecentSignalsOnce(): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE signalId = :signalId LIMIT 1")
    suspend fun getSignalByIdOnce(signalId: String): SignalEntity?

    @Query("SELECT * FROM signals WHERE symbol = :symbol ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun getLatestSignalForSymbolOnce(symbol: String): SignalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignal(signal: SignalEntity)

    // 6. AI Requests (MOD-02)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiRequest(request: AiRequestEntity)

    @Query("SELECT * FROM ai_requests ORDER BY requestedAtMs DESC LIMIT 50")
    fun observeAiRequests(): Flow<List<AiRequestEntity>>

    @Query("SELECT * FROM ai_requests ORDER BY requestedAtMs DESC LIMIT 100")
    suspend fun getRecentAiRequestsOnce(): List<AiRequestEntity>

    // 7. AI Decisions
    @Query("SELECT * FROM ai_decisions ORDER BY createdAtMs DESC LIMIT 40")
    fun observeRecentAiDecisions(): Flow<List<AiDecisionEntity>>

    @Query("SELECT * FROM ai_decisions ORDER BY createdAtMs DESC LIMIT 100")
    suspend fun getRecentAiDecisionsOnce(): List<AiDecisionEntity>

    @Query("SELECT * FROM ai_decisions WHERE decisionId = :decisionId LIMIT 1")
    suspend fun getAiDecisionByIdOnce(decisionId: String): AiDecisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiDecision(decision: AiDecisionEntity)

    // 8. Order Intents (INV-04)
    @Query("SELECT * FROM order_intents ORDER BY createdAtMs DESC LIMIT 50")
    fun observeOrderIntents(): Flow<List<OrderIntentEntity>>

    @Query("SELECT * FROM order_intents WHERE clientOrderId = :clientOrderId LIMIT 1")
    suspend fun getOrderIntentByClientId(clientOrderId: String): OrderIntentEntity?

    @Query("SELECT * FROM order_intents WHERE status IN ('PERSISTED', 'SUBMITTED', 'UNKNOWN') LIMIT 10")
    suspend fun getActiveOrUnknownIntents(): List<OrderIntentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderIntent(intent: OrderIntentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrderIntentStrict(intent: OrderIntentEntity)

    // 9. Orders
    @Query("SELECT * FROM orders ORDER BY updatedAtMs DESC LIMIT 60")
    fun observeOrders(): Flow<List<OrderRecordEntity>>

    @Query("SELECT * FROM orders WHERE status IN ('NEW', 'WORKING', 'PARTIALLY_FILLED', 'UNKNOWN', 'CANCEL_PENDING')")
    suspend fun getOpenOrdersOnce(): List<OrderRecordEntity>

    @Query("SELECT * FROM orders WHERE clientOrderId = :clientOrderId LIMIT 1")
    suspend fun getOrderByClientIdOnce(clientOrderId: String): OrderRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: OrderRecordEntity)

    @Query("UPDATE orders SET status = :newStatus, updatedAtMs = :nowMs WHERE clientOrderId = :clientOrderId")
    suspend fun updateOrderStatus(clientOrderId: String, newStatus: String, nowMs: Long)

    // 10. Positions
    @Query("SELECT * FROM positions WHERE state != 'CLOSED' ORDER BY updatedAtMs DESC")
    fun observeActivePositions(): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions WHERE state != 'CLOSED'")
    suspend fun getActivePositionsOnce(): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE symbol = :symbol AND positionSide = :positionSide LIMIT 1")
    suspend fun getPositionBySymbolAndSideOnce(symbol: String, positionSide: String): PositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: PositionEntity)

    @Query("DELETE FROM positions WHERE symbol = :symbol")
    suspend fun deletePosition(symbol: String)

    @Query("DELETE FROM positions WHERE symbol = :symbol AND positionSide = :positionSide")
    suspend fun deletePositionBySymbolAndSide(symbol: String, positionSide: String)

    // 11. Fills & Trade Ledger
    @Query("SELECT * FROM fills ORDER BY executedAtMs DESC LIMIT 100")
    fun observeFills(): Flow<List<FillEntity>>

    @Query("SELECT * FROM fills ORDER BY executedAtMs DESC")
    suspend fun getAllFillsOnce(): List<FillEntity>

    @Query("SELECT * FROM fills WHERE pnlStatus = 'VERIFIED' AND isEstimated = 0 ORDER BY executedAtMs DESC")
    suspend fun getVerifiedFillsOnce(): List<FillEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM fills WHERE tradeId = :tradeId)")
    suspend fun hasFill(tradeId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFillIgnoreDuplicate(fill: FillEntity): Long

    // 12. Funding Ledger
    @Query("SELECT * FROM funding_ledger ORDER BY timestampMs DESC LIMIT 50")
    fun observeFundingLedger(): Flow<List<FundingLedgerEntity>>

    @Query("SELECT * FROM funding_ledger ORDER BY timestampMs DESC")
    suspend fun getAllFundingOnce(): List<FundingLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFunding(funding: FundingLedgerEntity): Long

    // 13. Cashflows (INV-18)
    @Query("SELECT * FROM cashflows ORDER BY timestampMs DESC LIMIT 50")
    fun observeCashflows(): Flow<List<CashflowEntity>>

    @Query("SELECT * FROM cashflows ORDER BY timestampMs ASC")
    suspend fun getAllCashflowsOnce(): List<CashflowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCashflow(cashflow: CashflowEntity)

    // 14. Daily Snapshots
    @Query("SELECT * FROM daily_snapshots ORDER BY dateKey DESC LIMIT 30")
    fun observeDailySnapshots(): Flow<List<DailySnapshotEntity>>

    @Query("SELECT * FROM daily_snapshots ORDER BY dateKey DESC LIMIT 1")
    suspend fun getLatestDailySnapshotOnce(): DailySnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySnapshot(snapshot: DailySnapshotEntity)

    // 15. Risk Events
    @Query("SELECT * FROM risk_events ORDER BY occurredAtMs DESC LIMIT 60")
    fun observeRiskEvents(): Flow<List<RiskEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiskEvent(event: RiskEventEntity)

    // 16. System Audit Events
    @Query("SELECT * FROM system_events ORDER BY occurredAtMs DESC LIMIT 120")
    fun observeSystemEvents(): Flow<List<SystemEventEntity>>

    @Query("SELECT * FROM system_events ORDER BY occurredAtMs DESC LIMIT 100")
    suspend fun getRecentSystemEventsOnce(): List<SystemEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSystemEvent(event: SystemEventEntity)

    // 17. Telegram Idempotency (MOD-14 / MOD-15)
    @Query("SELECT EXISTS(SELECT 1 FROM telegram_updates WHERE updateId = :updateId)")
    suspend fun isTelegramUpdateProcessed(updateId: Long): Boolean

    @Query("SELECT * FROM telegram_updates WHERE updateId = :updateId LIMIT 1")
    suspend fun getTelegramUpdateOnce(updateId: Long): TelegramUpdateEntity?

    @Query("SELECT MAX(updateId) FROM telegram_updates")
    suspend fun getMaxTelegramUpdateId(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTelegramUpdate(update: TelegramUpdateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTelegramUpdate(update: TelegramUpdateEntity)

    /**
     * P2-04 · Atomic multi-table mutation for persisting an entry position along with its order records.
     */
    @Transaction
    suspend fun persistPositionAndOrdersAtomically(
        position: PositionEntity,
        orders: List<OrderRecordEntity>,
        systemEvent: SystemEventEntity? = null,
        riskEvent: RiskEventEntity? = null
    ) {
        for (ord in orders) {
            upsertOrder(ord)
        }
        upsertPosition(position)
        if (systemEvent != null) {
            insertSystemEvent(systemEvent)
        }
        if (riskEvent != null) {
            insertRiskEvent(riskEvent)
        }
    }

    /**
     * P0-01/P0-02/P0-04 & P2-04 · Atomic multi-table mutation when Panic Close is 100% verified on the exchange.
     */
    @Transaction
    suspend fun finalizeVerifiedPositionCloseAtomically(
        symbol: String,
        closeIntent: OrderIntentEntity,
        closeOrderRecord: OrderRecordEntity?,
        fillRecord: FillEntity?,
        updatedDailySnapshot: DailySnapshotEntity?,
        systemEvent: SystemEventEntity,
        positionSide: String? = null,
        additionalFillRecords: List<FillEntity> = emptyList()
    ) {
        upsertOrderIntent(closeIntent)
        if (closeOrderRecord != null) {
            upsertOrder(closeOrderRecord)
        }
        if (fillRecord != null) {
            insertFillIgnoreDuplicate(fillRecord)
        }
        for (extraFill in additionalFillRecords) {
            insertFillIgnoreDuplicate(extraFill)
        }
        if (updatedDailySnapshot != null) {
            upsertDailySnapshot(updatedDailySnapshot)
        }
        if (positionSide.isNullOrBlank()) {
            deletePosition(symbol)
        } else {
            deletePositionBySymbolAndSide(symbol, positionSide)
        }
        insertSystemEvent(systemEvent)
    }

    // 18. Forecasting Engine Persistence (Room v7 · MIGRATION_6_7)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastPrediction(prediction: ForecastPredictionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastPredictions(predictions: List<ForecastPredictionEntity>)

    @Query("SELECT * FROM forecast_predictions WHERE symbol = :symbol ORDER BY generatedAtMs DESC LIMIT 30")
    fun observeForecastPredictionsForSymbol(symbol: String): Flow<List<ForecastPredictionEntity>>

    @Query("SELECT * FROM forecast_predictions ORDER BY generatedAtMs DESC LIMIT 50")
    fun observeRecentForecastPredictions(): Flow<List<ForecastPredictionEntity>>

    @Query("SELECT * FROM forecast_predictions WHERE symbol = :symbol AND horizon = :horizon ORDER BY generatedAtMs DESC LIMIT 1")
    suspend fun getLatestForecastPredictionOnce(symbol: String, horizon: String): ForecastPredictionEntity?

    @Query("SELECT * FROM forecast_predictions WHERE symbol = :symbol ORDER BY generatedAtMs DESC LIMIT :limit")
    suspend fun getRecentForecastPredictionsForSymbolOnce(symbol: String, limit: Int = 30): List<ForecastPredictionEntity>

    @Query("DELETE FROM forecast_predictions WHERE generatedAtMs < :cutoffMs")
    suspend fun pruneOldForecastPredictions(cutoffMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastModel(model: ForecastModelEntity)

    @Query("SELECT * FROM forecast_models ORDER BY updatedAtMs DESC")
    fun observeForecastModels(): Flow<List<ForecastModelEntity>>

    @Query("SELECT * FROM forecast_models ORDER BY updatedAtMs DESC")
    suspend fun getForecastModelsOnce(): List<ForecastModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecastOutcome(outcome: ForecastOutcomeEntity)

    @Query("SELECT * FROM forecast_outcomes WHERE symbol = :symbol AND horizon = :horizon ORDER BY evaluatedAtMs DESC LIMIT :limit")
    suspend fun getForecastOutcomesOnce(symbol: String, horizon: String, limit: Int = 100): List<ForecastOutcomeEntity>

    @Query("DELETE FROM forecast_outcomes WHERE evaluatedAtMs < :cutoffMs")
    suspend fun pruneOldForecastOutcomes(cutoffMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastMetric(metric: ForecastMetricEntity)

    @Query("SELECT * FROM forecast_metrics ORDER BY updatedAtMs DESC")
    fun observeForecastMetrics(): Flow<List<ForecastMetricEntity>>

    @Query("SELECT * FROM forecast_metrics WHERE metricKey = :metricKey LIMIT 1")
    suspend fun getForecastMetricOnce(metricKey: String): ForecastMetricEntity?
}
