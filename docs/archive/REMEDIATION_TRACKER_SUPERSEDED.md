# ASTRA TRADER V5.1 — REMEDIATION TRACKER (`REMEDIATION_TRACKER.md`)

**Version:** 5.1.0-hardened  
**Status:** ALL P0, P1, AND P2 ITEMS REMEDIATED & VERIFIED  
**Live Trading Status:** DISARMED (`environmentMode = "TEST"`, `safetyDebugLockActive = true`, `liveExplicitlyArmed = false`)

---

## 1. P0 Remediation Matrix (Capital Safety & Execution Integrity)

| Item ID | Title | Modified Files | Verification Test | Status |
|---|---|---|---|---|
| `P0-01` | Panic Close Pre-Close Verification (`FETCH_FAILED`, `VERIFIED_ZERO`, `VERIFIED_NON_ZERO`, `SNAPSHOT_INCONSISTENT`) & `BOT_OWNED` Ownership Filter | `DomainModels.kt`, `BinanceAdapter.kt`, `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.verify P0_01 P0_02 P0_03 P0_04 Panic Close state machine...` | **DONE** |
| `P0-02` | Hedge Mode Support & Composite Primary Key `(symbol, positionSide)` on `positions` | `Entities.kt`, `AstraDao.kt`, `AstraDatabase.kt`, `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.verify V5_1 Runbook P0_02 Hedge Mode composite key...` | **DONE** |
| `P0-03` | Removal of `fallbackToDestructiveMigration` + Explicit Migration `5 -> 6` + `PRAGMA integrity_check` | `AstraDatabase.kt` | `ExampleRobolectricTest.verify V5_1 Runbook P0_02 Hedge Mode composite key...` | **DONE** |
| `P0-04` | 16-Condition Unified ExecutionGate: Exact `signalId` Match (No Symbol Fallback), Fresh Snapshot Re-check, and Skeptic `PASS` Gate | `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.verify P0_3_2 Panic Close never deletes position on failure and P0_3_3 ExecutionGate...` | **DONE** |
| `P0-05` | Protective SL/TP Pre-Retry Verification & Verified Cancellation Before Re-Order | `AstraTradingCoordinator.kt`, `BinanceAdapter.kt` | `ExampleUnitTest.Test 14`, `ExampleRobolectricTest.verify P0_01 P0_02 P0_03 P0_04...` | **DONE** |
| `P0-06` | Post-Fill Actual Fill Price Resolution (`/fapi/v1/userTrades` & `/fapi/v1/order`) & Direction-Aware SL/TP Recalculation | `QuantMathEngine.kt`, `AstraTradingCoordinator.kt` | `ExampleUnitTest.Test 14` | **DONE** |
| `P0-07` | Fail-Closed Market Pulse (`fetchSymbolMarketPulse`) Without Fabricated Funding Rate (`0.0001`) or Synthetic Zero Spread | `BinanceAdapter.kt` | `ExampleUnitTest.Test 20 - P0_07 fetchSymbolMarketPulse fails closed...` | **DONE** |
| `P0-08` | 3-Axis Endpoint Capability Matrix (`MarketDataEndpoint`, `AccountReadEndpoint`, `OrderExecutionEndpoint`) & Adapter Guard | `DomainModels.kt`, `BinanceAdapter.kt`, `AstraTradingCoordinator.kt` | `ExampleUnitTest.Test 21 - P0_08 ExecutionCapability blocks order mutations...` | **DONE** |
| `P0-09` | Complete Removal of `BuildConfig.GEMINI_API_KEY` Runtime Fallbacks | `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt`, `AstraViewModel.kt`, `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.verify P1_3_7 strict per-provider API key isolation...` | **DONE** |
| `P0-10` | Exchange-Side Leverage (`/fapi/v1/leverage`) & Margin Type (`/fapi/v1/marginType`) Enforcement Prior to Order Submission | `BinanceAdapter.kt`, `AstraTradingCoordinator.kt` | `ExampleUnitTest.Test 21`, `BinanceAdapter.changeSymbolLeverage` | **DONE** |
| `P0-11` | Foreign/Manual Position Detection (`FOREIGN_MANUAL`) & Symbol Mutation Lock During Reconciliation | `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.verify P0_05 Independent Skeptic... P0_06 SL+TP reconciliation...` | **DONE** |
| `P0-12` | Multi-Fill Individual `FillEntity` Persistence (`TRD_${symbol}_${tradeId}`) & `/fapi/v1/income` (`FUNDING_FEE`) Sync | `AstraDao.kt`, `BinanceAdapter.kt`, `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.verify V5_1 Runbook P0_02... P0_12 multi-fill accounting...` | **DONE** |
| `P0-13` | Persistent Execution Lease (`executionLockOwner`, `executionLockExpiresAtMs`) in `app_settings` | `Entities.kt`, `AstraDatabase.kt`, `AstraTradingCoordinator.kt` | `ExampleRobolectricTest.Tests 06 07 09 10 11...` | **DONE** |

---

## 2. P1 Remediation Matrix (Security, AI Governance, Telemetry & Resilience)

| Item ID | Title | Modified Files | Verification Test | Status |
|---|---|---|---|---|
| `P1-01` | Dynamic Verified Symbol Whitelist from `exchangeInfo` (Fail-Closed in `LIVE`) | `BinanceAdapter.kt`, `AstraTradingCoordinator.kt` | `ExampleUnitTest.Test 21` | **DONE** |
| `P1-02` | Dedicated Per-Provider `SecretVault` Slot Isolation (`slot_ai_provider_<id>`) | `SecretVault.kt`, `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt` | `ExampleRobolectricTest.verify P1_3_7...` | **DONE** |
| `P1-03` | Independent Skeptic Enforcement (`RULE_ONLY_PASS` vs `PASS`) | `AiIntelligenceGateway.kt`, `AstraTradingCoordinator.kt` | `ExampleUnitTest.Test 08`, `ExampleRobolectricTest.verify P0_05...` | **DONE** |
| `P1-04` | Telegram `IN_PROGRESS` Crash-Loop Prevention & `(nonce, chatId, userId, expiry)` Binding for `/panic` | `TelegramBotController.kt`, `AstraDao.kt` | `ExampleRobolectricTest.verify V5_1 Runbook...` | **DONE** |
| `P1-05` | Prohibition of Sample/Curated News (`defaultCuratedFeed`) in `LIVE` Mode | `AstraTradingCoordinator.kt`, `NewsEventGateEngine.kt` | `ExampleUnitTest.Section 10 and Appendix B9...` | **DONE** |
| `P1-06` | Historical Entry-Score Probability Calibration (`N >= 30`, Brier `<= 0.30`) | `QuantMathEngine.kt`, `AstraTradingCoordinator.kt` | `ExampleUnitTest.Test 15`, `ExampleRobolectricTest.verify P0_05... P1_03...` | **DONE** |
| `P1-07` | `MARKET_LOT_SIZE` Parsing & Enforcement for Market Orders | `BinanceAdapter.kt`, `QuantMathEngine.kt` | `ExampleUnitTest.Test 16` | **DONE** |
| `P1-08` | Foreground Service Singleton Loop Guard, Exponential Backoff, and Verified ListenKey Keep-Alive | `TradingForegroundService.kt` | `ExampleUnitTest.Test 17`, `ExampleUnitTest.Test 19` | **DONE** |
| `P1-09` | Safe Boot Recovery (`BootRecoveryReceiver`) Without Blind Auto-Trading | `BootRecoveryReceiver.kt`, `TradingForegroundService.kt` | Verified in `BootRecoveryReceiver.kt` | **DONE** |
| `P1-10` | Android Network-Bound Outbound IP & 6-Step Read-Only Binance Diagnostics | `BinanceAdapter.kt`, `AstraTradingCoordinator.kt`, `ApiIntegrationTabScreen.kt` | `ExampleRobolectricTest.verify X-MBX-APIKEY header integrity...` | **DONE** |
| `P1-11` | Real-Time `ConnectivityManager` Monitor & Offline Banner | `MainActivity.kt`, `AstraViewModel.kt`, `AstraTradingCoordinator.kt` | Verified in `MainActivity.kt` & `AstraTradingCoordinator.kt` | **DONE** |
| `P1-12` | Fail-Closed `SecretVault.verifyVaultSlotIntegrity` When Hash Metadata Is Missing | `SecretVault.kt` | `ExampleRobolectricTest.verify V5_1 Runbook... P1_12 vault hash fail-closed` | **DONE** |
| `P1-13` | Comprehensive Credential & Token Redaction in Logs and Diagnostic Reports | `BinanceAdapter.kt`, `TelegramBotController.kt` | `ExampleUnitTest.Test 13`, `ExampleRobolectricTest.verify X-MBX-APIKEY...` | **DONE** |
| `P1-14` | Release R8/ProGuard Keep Rules for Room, JSON/Serialization, and OkHttp | `app/proguard-rules.pro` | Verified via `compile_applet` | **DONE** |
| `P1-15` | Quantitative Risk Exposure Visualization & Compact 360dp Responsive UI | `StrategyRiskTabScreen.kt`, `OverviewTabScreen.kt` | `ExampleRobolectricTest.Test 12`, `Test 12b` | **DONE** |

---

## 3. P2 Remediation Matrix (Observability, Audit Trail & Documentation)

| Item ID | Title | Deliverable / File | Status |
|---|---|---|---|
| `P2-01` | Cashflow-Adjusted PnL (`INV-18`) & Funding Fee Attribution | `QuantMathEngine.kt`, `HistoryAnalyticsTabScreen.kt` | **DONE** |
| `P2-02` | System Event Structured Correlation IDs & Severity Filtering | `SystemLogsTabScreen.kt`, `Entities.kt` | **DONE** |
| `P2-03` | Device Thermal & Low-RAM (`/proc/meminfo` + `ActivityManager`) Circuit Breakers | `AstraTradingCoordinator.kt` | **DONE** |
| `P2-04` | Database Migration & Integrity Audit Documentation | `/DATABASE_MIGRATION_REPORT.md` | **DONE** |
| `P2-05` | Testnet Soak & Operational Runbook Documentation | `/TESTNET_SOAK_REPORT.md`, `/OPERATIONS_RUNBOOK.md` | **DONE** |
| `P2-06` | Complete Release & Security Audit Documentation Package | `/SECURITY_AUDIT.md`, `/APK_AUDIT_REPORT.md`, `/CHANGELOG.md`, `/RELEASE_NOTES.md` | **DONE** |
