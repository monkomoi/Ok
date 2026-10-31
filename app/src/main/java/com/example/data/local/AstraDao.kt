package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AstraDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<MarketCandleEntity>)

    @Query("SELECT * FROM market_candles WHERE symbol = :symbol AND interval = :interval ORDER BY openTime ASC")
    suspend fun getCandles(symbol: String, interval: String): List<MarketCandleEntity>

    @Query("DELETE FROM market_candles WHERE symbol = :symbol AND interval = :interval AND openTime < :minOpenTimeMs")
    suspend fun pruneOldCandles(symbol: String, interval: String, minOpenTimeMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketFeature(feature: MarketFeatureEntity)

    @Query("SELECT * FROM market_features ORDER BY asOfUtcMs DESC LIMIT 50")
    fun observeMarketFeatures(): Flow<List<MarketFeatureEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignal(signal: SignalEntity)

    @Query("SELECT * FROM signals ORDER BY createdAtMs DESC LIMIT 50")
    fun observeRecentSignals(): Flow<List<SignalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiDecision(decision: AiDecisionEntity)

    @Query("SELECT * FROM ai_decisions ORDER BY createdAtMs DESC LIMIT 50")
    fun observeRecentAiDecisions(): Flow<List<AiDecisionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiRequest(request: AiRequestEntity)

    @Query("SELECT * FROM ai_requests ORDER BY requestedAtMs DESC LIMIT 100")
    fun observeAiRequests(): Flow<List<AiRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrderIntentStrict(intent: OrderIntentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderIntent(intent: OrderIntentEntity)

    @Query("SELECT * FROM order_intents WHERE clientOrderId = :clientOrderId LIMIT 1")
    suspend fun getOrderIntentByClientId(clientOrderId: String): OrderIntentEntity?

    @Query("SELECT * FROM order_intents ORDER BY updatedAtMs DESC LIMIT 100")
    fun observeOrderIntents(): Flow<List<OrderIntentEntity>>

    @Query("SELECT * FROM order_intents WHERE status IN ('INTENT_PERSISTED', 'SUBMITTING', 'UNKNOWN')")
    suspend fun getUnresolvedOrderIntents(): List<OrderIntentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: OrderRecordEntity)

    @Query("UPDATE orders SET status = :status, updatedAtMs = :updatedAtMs WHERE clientOrderId = :clientOrderId")
    suspend fun updateOrderStatus(clientOrderId: String, status: String, updatedAtMs: Long)

    @Query("SELECT * FROM orders ORDER BY updatedAtMs DESC LIMIT 100")
    fun observeOrders(): Flow<List<OrderRecordEntity>>

    @Query("SELECT * FROM orders WHERE status IN ('NEW', 'PARTIALLY_FILLED', 'PENDING_NEW')")
    suspend fun getOpenOrders(): List<OrderRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFill(fill: FillEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM fills WHERE tradeId = :tradeId)")
    suspend fun hasFill(tradeId: String): Boolean

    @Query("SELECT * FROM fills ORDER BY executedAtMs DESC LIMIT 200")
    fun observeFills(): Flow<List<FillEntity>>

    @Query("SELECT * FROM fills ORDER BY executedAtMs ASC")
    suspend fun getAllFillsChronological(): List<FillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: PositionEntity)

    @Query("SELECT * FROM positions ORDER BY updatedAtMs DESC")
    fun observeActivePositions(): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions")
    suspend fun getActivePositions(): List<PositionEntity>

    @Query("DELETE FROM positions WHERE positionId = :positionId")
    suspend fun deletePosition(positionId: String)

    @Query("DELETE FROM positions WHERE symbol = :symbol AND side = :side")
    suspend fun deletePositionBySymbolAndSide(symbol: String, side: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiskEvent(event: RiskEventEntity)

    @Query("SELECT * FROM risk_events ORDER BY timestampMs DESC LIMIT 100")
    fun observeRiskEvents(): Flow<List<RiskEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSystemEvent(event: SystemEventEntity)

    @Query("SELECT * FROM system_events ORDER BY timestampMs DESC LIMIT 150")
    fun observeSystemEvents(): Flow<List<SystemEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySnapshot(snapshot: DailySnapshotEntity)

    @Query("SELECT * FROM daily_snapshots ORDER BY dateKey DESC LIMIT 30")
    fun observeDailySnapshots(): Flow<List<DailySnapshotEntity>>

    @Query("SELECT * FROM daily_snapshots WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getDailySnapshot(dateKey: String): DailySnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCashflow(cashflow: CashflowEntity)

    @Query("SELECT * FROM cashflows ORDER BY timestampMs DESC LIMIT 100")
    fun observeCashflows(): Flow<List<CashflowEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFundingRecord(record: FundingLedgerEntity)

    @Query("SELECT * FROM funding_ledger ORDER BY timestampMs DESC LIMIT 100")
    fun observeFundingLedger(): Flow<List<FundingLedgerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE providerId = :providerId")
    suspend fun deleteProvider(providerId: String)

    @Query("SELECT * FROM provider_configs ORDER BY priorityOrder ASC")
    fun observeProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs ORDER BY priorityOrder ASC")
    suspend fun getProviders(): List<ProviderConfigEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTelegramUpdate(update: TelegramUpdateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTelegramUpdate(update: TelegramUpdateEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM telegram_updates WHERE updateId = :updateId)")
    suspend fun hasProcessedTelegramUpdate(updateId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastModel(model: ForecastModelEntity)

    @Query("SELECT * FROM forecast_models ORDER BY registeredAtMs DESC")
    fun observeForecastModels(): Flow<List<ForecastModelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastPrediction(prediction: ForecastPredictionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastPredictions(predictions: List<ForecastPredictionEntity>)

    @Query("SELECT * FROM forecast_predictions ORDER BY generatedAtMs DESC LIMIT 90")
    fun observeRecentForecastPredictions(): Flow<List<ForecastPredictionEntity>>

    @Query("SELECT * FROM forecast_predictions WHERE symbol = :symbol ORDER BY generatedAtMs DESC LIMIT 30")
    fun observeForecastPredictionsForSymbol(symbol: String): Flow<List<ForecastPredictionEntity>>

    @Query(
        """
        SELECT p.* FROM forecast_predictions p
        LEFT JOIN forecast_outcomes o ON p.predictionId = o.predictionId
        WHERE p.symbol = :symbol
          AND p.horizon = :horizon
          AND p.status = 'READY_VALIDATED'
          AND o.outcomeId IS NULL
          AND p.asOfCloseTimeMs + :horizonMs <= :currentClosedCandleTimeMs
        ORDER BY p.asOfCloseTimeMs ASC
        LIMIT 32
        """
    )
    suspend fun getUnresolvedEligibleForecastPredictions(
        symbol: String,
        horizon: String,
        horizonMs: Long,
        currentClosedCandleTimeMs: Long
    ): List<ForecastPredictionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM forecast_outcomes WHERE predictionId = :predictionId)")
    suspend fun hasForecastOutcomeForPrediction(predictionId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForecastOutcome(outcome: ForecastOutcomeEntity): Long

    @Query("SELECT * FROM forecast_outcomes WHERE symbol = :symbol AND horizon = :horizon ORDER BY resolvedAtMs DESC LIMIT :limit")
    suspend fun getRecentForecastOutcomes(symbol: String, horizon: String, limit: Int = 100): List<ForecastOutcomeEntity>

    @Query(
        """
        SELECT p.pDown, p.pFlat, p.pUp, o.realizedDirection, o.brierContribution, o.absErrorPct, o.directionCorrect
        FROM forecast_outcomes o
        INNER JOIN forecast_predictions p ON o.predictionId = p.predictionId
        WHERE o.symbol = :symbol AND o.horizon = :horizon
        ORDER BY o.resolvedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun getJoinedForecastOutcomeRows(
        symbol: String,
        horizon: String,
        limit: Int = 100
    ): List<JoinedForecastOutcomeRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecastMetric(metric: ForecastMetricEntity)

    @Query("SELECT * FROM forecast_metrics ORDER BY updatedAtMs DESC")
    fun observeForecastMetrics(): Flow<List<ForecastMetricEntity>>

    @Query(
        """
        DELETE FROM forecast_predictions
        WHERE generatedAtMs < :cutoffMs
          AND (
            status != 'READY_VALIDATED'
            OR EXISTS (SELECT 1 FROM forecast_outcomes o WHERE o.predictionId = forecast_predictions.predictionId)
          )
        """
    )
    suspend fun pruneOldForecastPredictions(cutoffMs: Long)

    @Query("DELETE FROM forecast_outcomes WHERE resolvedAtMs < :cutoffMs")
    suspend fun pruneOldForecastOutcomes(cutoffMs: Long)

    @Transaction
    suspend fun persistPositionAndOrdersAtomically(
        position: PositionEntity,
        orders: List<OrderRecordEntity>,
        systemEvent: SystemEventEntity,
        riskEvent: RiskEventEntity? = null
    ) {
        upsertPosition(position)
        orders.forEach { upsertOrder(it) }
        insertSystemEvent(systemEvent)
        if (riskEvent != null) {
            insertRiskEvent(riskEvent)
        }
    }

    @Transaction
    suspend fun finalizeVerifiedPositionCloseAtomically(
        positionId: String,
        closeIntent: OrderIntentEntity,
        closeOrder: OrderRecordEntity,
        fill: FillEntity,
        dailySnapshot: DailySnapshotEntity,
        systemEvent: SystemEventEntity,
        symbol: String,
        cancelledProtectiveOrderIds: List<String>
    ) {
        deletePosition(positionId)
        upsertOrderIntent(closeIntent)
        upsertOrder(closeOrder)
        insertFill(fill)
        upsertDailySnapshot(dailySnapshot)
        val now = System.currentTimeMillis()
        cancelledProtectiveOrderIds.forEach { cid ->
            updateOrderStatus(cid, "CANCELED", now)
        }
        insertSystemEvent(systemEvent)
    }
}

data class JoinedForecastOutcomeRow(
    val pDown: String,
    val pFlat: String,
    val pUp: String,
    val realizedDirection: String,
    val brierContribution: String,
    val absErrorPct: String,
    val directionCorrect: Boolean
)
