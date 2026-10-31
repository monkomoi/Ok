# ASTRA TRADER V5.1 — DATABASE MIGRATION & INTEGRITY REPORT (`DATABASE_MIGRATION_REPORT.md`)

**Database Class:** `com.example.data.local.AstraDatabase`  
**Current Schema Version:** `7`  
**Total Entities:** `21` (17 Core V5.1 Entities + 4 Forecasting Engine Entities)  
**Destructive Fallback Status:** **REMOVED (`P0-03`)**  
**Startup Integrity Check:** `PRAGMA integrity_check` via `AstraDatabase.runIntegrityCheck()`

---

## 1. Migration Chain (`ALL_MIGRATIONS`)

All 6 schema transitions (`1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7`) are explicitly registered in `AstraDatabase.ALL_MIGRATIONS` and verified without destructive fallback:

1. **`MIGRATION_1_2` (`1 -> 2`)**:
   - Creates `market_features`, `news_events`, `ai_requests`, and `telegram_updates` tables along with composite indexes on `(symbol, asOfUtcMs)` and `(symbol, publishedAtMs)`.
2. **`MIGRATION_2_3` (`2 -> 3`)**:
   - Adds `binanceEndpointTarget TEXT NOT NULL DEFAULT 'MAINNET'` to `app_settings`.
3. **`MIGRATION_3_4` (`3 -> 4`)**:
   - Adds `protectiveTpOrderId`, `protectiveTpVerified`, and `positionSide` to `positions`.
   - Adds `positionSide` and `isAlgoOrder` to `orders`.
4. **`MIGRATION_4_5` (`4 -> 5`)**:
   - Adds `grossPnlUsdt`, `pnlStatus`, `isEstimated`, `predictedScoreAtEntry`, `calibratedProbAtEntry`, and `positionLifecycleId` to `fills`.
   - Adds `predictedScoreAtEntry` and `calibratedProbAtEntry` to `positions`.
   - Adds `predictedRankingScore` and `calibratedProbability` to `signals`.
5. **`MIGRATION_5_6` (`5 -> 6`, `P0-02` & `P0-13`)**:
   - Rebuilds `positions` (`positions_v6`) with composite primary key `PRIMARY KEY(symbol, positionSide)` and unique index `index_positions_symbol_positionSide` while preserving 100% of existing position rows via `INSERT OR REPLACE INTO positions_v6 SELECT ... FROM positions`.
   - Adds `executionLockOwner TEXT` and `executionLockExpiresAtMs INTEGER NOT NULL DEFAULT 0` to `app_settings` for cross-process execution lease protection.
6. **`MIGRATION_6_7` (`6 -> 7`, Forecasting Engine Runbook Section 10)**:
   - Adds `forecastOperatingMode TEXT NOT NULL DEFAULT 'SHADOW'` to `app_settings`.
   - Creates `forecast_predictions` with indexes `index_forecast_predictions_symbol_horizon_generatedAtMs` and `index_forecast_predictions_validUntilMs`.
   - Creates `forecast_models` (`PRIMARY KEY(modelId)`).
   - Creates `forecast_outcomes` (`PRIMARY KEY(outcomeId)`) with indexes `index_forecast_outcomes_predictionId` and `index_forecast_outcomes_symbol_horizon_evaluatedAtMs`.
   - Creates `forecast_metrics` (`PRIMARY KEY(metricId)`) with index `index_forecast_metrics_modelId_horizon_computedAtMs`.

---

## 2. Composite Key, Non-Destructive Upgrade & Transactional Atomicity Verification

- **Hedge Mode Isolation (`P0-02`)**: Verified in `ExampleRobolectricTest` that `PositionEntity(symbol = "DOGEUSDT", positionSide = "LONG")` and `PositionEntity(symbol = "DOGEUSDT", positionSide = "SHORT")` coexist simultaneously in `positions` and can be queried or deleted independently via `getPositionBySymbolAndSideOnce(symbol, positionSide)` and `deletePositionBySymbolAndSide(symbol, positionSide)`.
- **Multi-Fill Atomic Close (`P0-12`)**: `AstraDao.finalizeVerifiedPositionCloseAtomically` runs inside a single Room `@Transaction`, inserting the primary and all additional partial `FillEntity` rows (`OnConflictStrategy.IGNORE`), deleting only the verified `(symbol, positionSide)` row, and updating `daily_snapshots`.
- **Non-Destructive `MIGRATION_6_7` (`6 -> 7`)**: Verified in `ExampleRobolectricTest` on a simulated v6 SQLite database that existing `app_settings` rows survive intact with `forecastOperatingMode = 'SHADOW'`, all 4 forecasting tables (`forecast_predictions`, `forecast_models`, `forecast_outcomes`, `forecast_metrics`) are created with their indexes, and `PRAGMA integrity_check` returns `"ok"`.
- **Storage TTL Pruning (`S7 Edge 64 GB ROM Budget`)**: `AstraDao.pruneExpiredTelemetryAndLogs` prunes `forecast_predictions` (`30` days), `forecast_outcomes` (`60` days), and `forecast_metrics` (`90` days) automatically.
