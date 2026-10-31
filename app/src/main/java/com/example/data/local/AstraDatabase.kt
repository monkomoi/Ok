package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppSettingsEntity::class,
        ProviderConfigEntity::class,
        MarketCandleEntity::class,
        MarketFeatureEntity::class,
        SignalEntity::class,
        AiRequestEntity::class,
        AiDecisionEntity::class,
        OrderIntentEntity::class,
        OrderRecordEntity::class,
        PositionEntity::class,
        FillEntity::class,
        FundingLedgerEntity::class,
        CashflowEntity::class,
        DailySnapshotEntity::class,
        RiskEventEntity::class,
        SystemEventEntity::class,
        TelegramUpdateEntity::class,
        ForecastPredictionEntity::class,
        ForecastModelEntity::class,
        ForecastOutcomeEntity::class,
        ForecastMetricEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AstraDatabase : RoomDatabase() {
    abstract fun astraDao(): AstraDao

    /**
     * P0-03 · Runs SQLite PRAGMA integrity_check and returns true only if the database reports "ok".
     */
    fun runIntegrityCheck(): Boolean {
        return try {
            val db = openHelper.writableDatabase
            db.query("PRAGMA integrity_check").use { cursor ->
                if (!cursor.moveToFirst()) return false
                val result = cursor.getString(0)?.trim() ?: return false
                result.equals("ok", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AstraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `market_features` (
                        `snapshotHash` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `asOfUtcMs` INTEGER NOT NULL,
                        `featureVersion` TEXT NOT NULL,
                        `dataSource` TEXT NOT NULL,
                        `markPrice` TEXT NOT NULL,
                        `return15m` TEXT NOT NULL,
                        `return1h` TEXT NOT NULL,
                        `atrPct` TEXT NOT NULL,
                        `relativeVolume` TEXT NOT NULL,
                        `spreadPct` TEXT NOT NULL,
                        `microprice` TEXT NOT NULL,
                        `orderBookImbalance` TEXT NOT NULL,
                        `primaryRegime` TEXT NOT NULL,
                        `isDataStale` INTEGER NOT NULL,
                        `isEstimated` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`snapshotHash`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_requests` (
                        `requestId` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `purpose` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `latencyMs` INTEGER NOT NULL,
                        `estimatedPromptTokens` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `correlationId` TEXT NOT NULL,
                        `requestedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`requestId`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `binanceEndpointTarget` TEXT NOT NULL DEFAULT 'MAINNET'"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `positionSide` TEXT NOT NULL DEFAULT 'BOTH'")
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `signalId` TEXT")
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `decisionId` TEXT")
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `sizingId` TEXT")
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `riskApprovalId` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `positionSide` TEXT NOT NULL DEFAULT 'BOTH'")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `isAlgoOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `protectiveTpOrderId` TEXT")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `protectiveTpVerified` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `positionSide` TEXT NOT NULL DEFAULT 'BOTH'")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `signalId` TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `signals` ADD COLUMN `rankingScore` TEXT NOT NULL DEFAULT '0.5000'")
                db.execSQL("ALTER TABLE `signals` ADD COLUMN `predictedWinProbability` TEXT")
                db.execSQL("ALTER TABLE `signals` ADD COLUMN `decisionId` TEXT")
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `predictedScoreAtEntry` TEXT")
                db.execSQL("ALTER TABLE `order_intents` ADD COLUMN `calibratedProbAtEntry` TEXT")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `decisionId` TEXT")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `predictedScoreAtEntry` TEXT")
                db.execSQL("ALTER TABLE `positions` ADD COLUMN `calibratedProbAtEntry` TEXT")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `grossPnlUsdt` TEXT NOT NULL DEFAULT '0.0000'")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `fundingUsdt` TEXT NOT NULL DEFAULT '0.0000'")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `pnlStatus` TEXT NOT NULL DEFAULT 'VERIFIED'")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `isEstimated` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `exchangeOrderId` TEXT")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `signalId` TEXT")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `decisionId` TEXT")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `predictedScoreAtEntry` TEXT")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `calibratedProbAtEntry` TEXT")
                db.execSQL("ALTER TABLE `fills` ADD COLUMN `positionLifecycleId` TEXT")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_features_symbol` ON `market_features` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_features_createdAtMs` ON `market_features` (`createdAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signals_symbol` ON `signals` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signals_status` ON `signals` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signals_createdAtMs` ON `signals` (`createdAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_requests_symbol` ON `ai_requests` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_requests_correlationId` ON `ai_requests` (`correlationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_requests_requestedAtMs` ON `ai_requests` (`requestedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_decisions_symbol` ON `ai_decisions` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_decisions_createdAtMs` ON `ai_decisions` (`createdAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_intents_symbol` ON `order_intents` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_intents_status` ON `order_intents` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_intents_signalId` ON `order_intents` (`signalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_intents_createdAtMs` ON `order_intents` (`createdAtMs`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_orders_clientOrderId` ON `orders` (`clientOrderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_orders_symbol` ON `orders` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_orders_status` ON `orders` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_orders_updatedAtMs` ON `orders` (`updatedAtMs`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_positions_symbol` ON `positions` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_positions_state` ON `positions` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_positions_updatedAtMs` ON `positions` (`updatedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fills_symbol` ON `fills` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fills_clientOrderId` ON `fills` (`clientOrderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fills_executedAtMs` ON `fills` (`executedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fills_pnlStatus` ON `fills` (`pnlStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_funding_ledger_symbol` ON `funding_ledger` (`symbol`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_funding_ledger_timestampMs` ON `funding_ledger` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cashflows_timestampMs` ON `cashflows` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_risk_events_triggerCode` ON `risk_events` (`triggerCode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_risk_events_occurredAtMs` ON `risk_events` (`occurredAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_events_category` ON `system_events` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_events_correlationId` ON `system_events` (`correlationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_events_occurredAtMs` ON `system_events` (`occurredAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telegram_updates_processedAtMs` ON `telegram_updates` (`processedAtMs`)")
            }
        }

        /**
         * P0-02, P0-03, P0-13 · Migrates `positions` to composite primary key (`symbol`, `positionSide`)
         * without data loss and adds persistent execution lock columns to `app_settings`.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `executionLockOwner` TEXT")
                db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `executionLockExpiresAtMs` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `positions_new` (
                        `symbol` TEXT NOT NULL,
                        `signedQuantity` TEXT NOT NULL,
                        `entryAvgPrice` TEXT NOT NULL,
                        `markPrice` TEXT NOT NULL,
                        `unrealizedPnlUsdt` TEXT NOT NULL,
                        `isolatedMarginUsdt` TEXT NOT NULL,
                        `leverage` INTEGER NOT NULL,
                        `stopPrice` TEXT NOT NULL,
                        `targetPrice` TEXT NOT NULL,
                        `netBreakevenEstimate` TEXT NOT NULL,
                        `protectiveSlOrderId` TEXT,
                        `protectiveSlVerified` INTEGER NOT NULL,
                        `protectiveTpOrderId` TEXT,
                        `protectiveTpVerified` INTEGER NOT NULL,
                        `positionSide` TEXT NOT NULL,
                        `signalId` TEXT,
                        `decisionId` TEXT,
                        `predictedScoreAtEntry` TEXT,
                        `calibratedProbAtEntry` TEXT,
                        `ownershipTag` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `openedAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`symbol`, `positionSide`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `positions_new` (
                        `symbol`, `signedQuantity`, `entryAvgPrice`, `markPrice`, `unrealizedPnlUsdt`,
                        `isolatedMarginUsdt`, `leverage`, `stopPrice`, `targetPrice`, `netBreakevenEstimate`,
                        `protectiveSlOrderId`, `protectiveSlVerified`, `protectiveTpOrderId`, `protectiveTpVerified`,
                        `positionSide`, `signalId`, `decisionId`, `predictedScoreAtEntry`, `calibratedProbAtEntry`,
                        `ownershipTag`, `state`, `openedAtMs`, `updatedAtMs`
                    )
                    SELECT
                        `symbol`, `signedQuantity`, `entryAvgPrice`, `markPrice`, `unrealizedPnlUsdt`,
                        `isolatedMarginUsdt`, `leverage`, `stopPrice`, `targetPrice`, `netBreakevenEstimate`,
                        `protectiveSlOrderId`, `protectiveSlVerified`, `protectiveTpOrderId`, `protectiveTpVerified`,
                        COALESCE(`positionSide`, 'BOTH'), `signalId`, `decisionId`, `predictedScoreAtEntry`, `calibratedProbAtEntry`,
                        `ownershipTag`, `state`, `openedAtMs`, `updatedAtMs`
                    FROM `positions`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `positions`")
                db.execSQL("ALTER TABLE `positions_new` RENAME TO `positions`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_positions_symbol_positionSide` ON `positions` (`symbol`, `positionSide`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_positions_state` ON `positions` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_positions_updatedAtMs` ON `positions` (`updatedAtMs`)")
            }
        }

        /**
         * Forecasting Engine Runbook Section 11.4 · Upgrades schema from version 6 to version 7:
         * Adds `forecastOperatingMode` to `app_settings` and creates `forecast_predictions`,
         * `forecast_models`, `forecast_outcomes`, and `forecast_metrics` with composite indexes.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `forecastOperatingMode` TEXT NOT NULL DEFAULT 'SHADOW'"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `forecast_predictions` (
                        `predictionId` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `baseTimeframe` TEXT NOT NULL,
                        `horizon` TEXT NOT NULL,
                        `asOfCloseTimeMs` INTEGER NOT NULL,
                        `generatedAtMs` INTEGER NOT NULL,
                        `validUntilMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `featureSchemaHash` TEXT NOT NULL,
                        `pUp` TEXT,
                        `pDown` TEXT,
                        `pFlat` TEXT,
                        `expectedReturnPct` TEXT,
                        `q10Pct` TEXT,
                        `q50Pct` TEXT,
                        `q90Pct` TEXT,
                        `predictedVolatilityPct` TEXT,
                        `uncertainty` TEXT,
                        `outOfDistributionScore` TEXT,
                        `calibrationApplied` INTEGER NOT NULL,
                        `calibrationMethod` TEXT NOT NULL,
                        `calibrationSampleSize` INTEGER NOT NULL,
                        `brierScore` TEXT,
                        `expectedCalibrationError` TEXT,
                        `costThresholdPct` TEXT NOT NULL,
                        `passesQualityGate` INTEGER NOT NULL,
                        `advisoryAction` TEXT NOT NULL,
                        `reasonVi` TEXT NOT NULL,
                        `inferenceLatencyMs` INTEGER NOT NULL,
                        PRIMARY KEY(`predictionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_predictions_symbol_horizon_generatedAtMs` ON `forecast_predictions` (`symbol`, `horizon`, `generatedAtMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_predictions_validUntilMs` ON `forecast_predictions` (`validUntilMs`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `forecast_models` (
                        `modelId` TEXT NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `architecture` TEXT NOT NULL,
                        `featureSchemaVersion` TEXT NOT NULL,
                        `featureSchemaHash` TEXT NOT NULL,
                        `runtimeBackend` TEXT NOT NULL,
                        `cpuThreads` INTEGER NOT NULL,
                        `sha256Checksum` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `isRollback` INTEGER NOT NULL,
                        `goldenSelfTestPassed` INTEGER NOT NULL,
                        `registeredAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`modelId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_models_isActive` ON `forecast_models` (`isActive`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `forecast_outcomes` (
                        `outcomeId` TEXT NOT NULL,
                        `predictionId` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `horizon` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `predictedDirection` TEXT NOT NULL,
                        `realizedDirection` TEXT NOT NULL,
                        `predictedReturnPct` TEXT NOT NULL,
                        `realizedReturnPct` TEXT NOT NULL,
                        `pUpAtPrediction` TEXT NOT NULL,
                        `pDownAtPrediction` TEXT NOT NULL,
                        `pFlatAtPrediction` TEXT NOT NULL,
                        `brierContribution` TEXT NOT NULL,
                        `directionHit` INTEGER NOT NULL,
                        `evaluatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`outcomeId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_outcomes_symbol_horizon_evaluatedAtMs` ON `forecast_outcomes` (`symbol`, `horizon`, `evaluatedAtMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_outcomes_predictionId` ON `forecast_outcomes` (`predictionId`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `forecast_metrics` (
                        `metricKey` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `horizon` TEXT NOT NULL,
                        `sampleSize` INTEGER NOT NULL,
                        `brierScore` TEXT NOT NULL,
                        `expectedCalibrationError` TEXT NOT NULL,
                        `directionalAccuracy` TEXT NOT NULL,
                        `maeReturnPct` TEXT NOT NULL,
                        `oodRejectRate` TEXT NOT NULL,
                        `p50LatencyMs` INTEGER NOT NULL,
                        `p95LatencyMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`metricKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_metrics_modelId_horizon` ON `forecast_metrics` (`modelId`, `horizon`)"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7
        )

        fun getInstance(context: Context): AstraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AstraDatabase::class.java,
                    "astra_trader_v51.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
