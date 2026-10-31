# ASTRA TRADER V5.1 — BASELINE AUDIT REPORT (`BASELINE_AUDIT.md`)

**Date:** 2026-10-30  
**Scope:** End-to-End Capital Safety, Execution State Machine, Exchange Reconciliation, Database Schema, Security & Credential Isolation, AI Governance, and Android Runtime Audit against `ASTRA_TRADER_V5_1_FULL_REMEDIATION_UPGRADE_RUNBOOK.md`.  
**Default Operational Posture:** `TEST` Mode (`safetyDebugLockActive = true`, `liveExplicitlyArmed = false`) — **LIVE trading strictly disarmed**.

---

## 1. Executive Summary

This baseline audit inspected every layer of the Astra Trader Android codebase (`MainActivity`, `AstraViewModel`, `AstraTradingCoordinator`, `BinanceAdapter`, `QuantMathEngine`, `NewsEventGateEngine`, `AiIntelligenceGateway`, `AiApiCheckerEngine`, `SecretVault`, `TelegramBotController`, `TradingForegroundService`, `BootRecoveryReceiver`, `AstraDatabase`, `AstraDao`, `Entities`, and UI screens) against the V5.1 Runbook (`P0-01`..`P0-13`, `P1-01`..`P1-15`, `P2-01`..`P2-06`).

All identified P0, P1, and P2 gaps have been directly remediated in source code, covered by deterministic JVM + Robolectric tests, and verified to compile and pass with zero regressions.

---

## 2. Baseline Findings & Verification Status

### 2.1 P0 — Capital Safety, Order Execution & Data Integrity Blockers

| ID | Baseline Finding | Root Cause Location | Remediation Implemented | Status |
|---|---|---|---|---|
| **P0-01** | Panic Close needed strict pre-close Hedge Mode / `positionSide` consistency checks and `BOT_OWNED` ownership filtering before submitting reduce-only market orders. | `AstraTradingCoordinator.executePanicCloseStateMachine`, `DomainModels.PanicPreCloseVerificationState` | Added `SNAPSHOT_INCONSISTENT` state, queried `GET /fapi/v1/positionSide/dual` + `GET /fapi/v2/account`, filtered strictly to `ownershipTag == "BOT_OWNED"`, and blocked blind close orders on mode/side mismatch. | **CLOSED (VERIFIED)** |
| **P0-02** | `PositionEntity` used `symbol` as sole `@PrimaryKey`, which would overwrite `LONG` and `SHORT` legs in Hedge Mode. | `Entities.kt`, `AstraDao.kt`, `AstraDatabase.kt` | Migrated `positions` to composite primary key `primaryKeys = ["symbol", "positionSide"]` with unique index `(symbol, positionSide)` via `MIGRATION_5_6`. | **CLOSED (VERIFIED)** |
| **P0-03** | `AstraDatabase` enabled `.fallbackToDestructiveMigration(true)`, risking silent wipe of open positions and fills on schema mismatch. | `AstraDatabase.kt` | Removed `.fallbackToDestructiveMigration(true)`, registered explicit `ALL_MIGRATIONS` (`1->2` through `5->6`), and added `runIntegrityCheck()` (`PRAGMA integrity_check`). | **CLOSED (VERIFIED)** |
| **P0-04** | `evaluateUnifiedExecutionGate` fell back to `recentSignals.firstOrNull { it.symbol == sizing.symbol }` if `sizing.signalId` did not match, allowing an unlinked signal to pass Gate 3. | `AstraTradingCoordinator.evaluateUnifiedExecutionGate` | Removed symbol fallback; required exact `it.signalId == sizing.signalId` match, non-blank `sizingId`, fresh snapshot age (`<= 900_000 ms`), and `SkepticVerdict.PASS` in `LIVE`. | **CLOSED (VERIFIED)** |
| **P0-05** | Protective SL/TP retry path needed verified exchange cancellation before re-submitting to prevent duplicate conditional orders. | `AstraTradingCoordinator.placeAndVerifyProtectiveOrdersWithRetry` | Enforced pre-retry query and verified cancellation of any prior SL/TP conditional order before re-submitting, plus full 3-source reconciliation (`account`, `openOrders`, `openAlgoOrders`). | **CLOSED (VERIFIED)** |
| **P0-06** | Entry fill price fell back to planned entry price if `orderResp.avgPrice <= 0`, and protective SL/TP did not enforce direction-aware distance against actual fill and mark price. | `AstraTradingCoordinator.executeApprovedCandidateIfEligible` | Added fallback query to `fetchUserTradesForOrder` and `queryOrderByClientId` when `avgPrice <= 0`; aborted entry into `UNKNOWN_ORDER_STATUS` if actual fill price remains unverified. | **CLOSED (VERIFIED)** |
| **P0-07** | `fetchSymbolMarketPulse` used silent fallback defaults (`lastFundingRate = "0.0001"`, `bidPrice = markPrice`, `askPrice = markPrice`) when `premiumIndex` or `bookTicker` failed or omitted fields. | `BinanceAdapter.fetchSymbolMarketPulse` | Removed all fabricated market defaults; `fetchSymbolMarketPulse` now fails closed (`isSuccess = false`) if `markPrice`, `indexPrice`, `lastFundingRate`, `bidPrice`, `askPrice`, `bidQty`, or `askQty` are missing or invalid. | **CLOSED (VERIFIED)** |
| **P0-08** | Endpoint selection (`MAINNET` vs `TESTNET`) needed an explicit adapter-level capability guard so `TEST` mode inspecting `MAINNET` can never submit new orders. | `DomainModels.ExecutionCapability`, `BinanceAdapter.verifyOrderMutationCapability` | Added `MarketDataEndpoint`, `AccountReadEndpoint`, `OrderExecutionEndpoint`, and `ExecutionCapability`; enforced `verifyOrderMutationCapability` at the start of `submitFuturesOrder`, `submitAlgoConditionalOrder`, `changeSymbolLeverage`, and `changeSymbolMarginType`. | **CLOSED (VERIFIED)** |
| **P0-09** | `BuildConfig.GEMINI_API_KEY` was used as a fallback credential when `SecretVault` slots were empty. | `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt`, `AstraViewModel.kt`, `AstraTradingCoordinator.kt` | Removed all runtime credential fallbacks to `BuildConfig.GEMINI_API_KEY`; AI keys are resolved exclusively from each provider's isolated `SecretVault` slot. | **CLOSED (VERIFIED)** |
| **P0-10** | Symbol leverage and margin type (`ISOLATED`) were stored locally without exchange-side enforcement before order entry. | `BinanceAdapter.changeSymbolLeverage`, `BinanceAdapter.changeSymbolMarginType`, `AstraTradingCoordinator.executeApprovedCandidateIfEligible` | Added signed `POST /fapi/v1/marginType` and `POST /fapi/v1/leverage` calls and enforced exchange confirmation before submitting any entry order. | **CLOSED (VERIFIED)** |
| **P0-11** | Reconciliation needed to classify positions existing on the exchange with no local `BOT_OWNED` record as `FOREIGN_MANUAL` and block automated entry on that symbol. | `AstraTradingCoordinator.runFullReconciliation` | Enforced `FOREIGN_MANUAL` classification for unowned exchange positions and blocked automated mutations on symbols with foreign/manual exposure. | **CLOSED (VERIFIED)** |
| **P0-12** | Multi-fill orders collapsed into a single `FillEntity` row (`trades.first().tradeId`), and funding fee cashflows were not synced into `FundingCashflowEntity`. | `AstraDao.finalizeVerifiedPositionCloseAtomically`, `BinanceAdapter.fetchFundingIncomeHistory`, `AstraTradingCoordinator` | Persisted every partial fill from `/fapi/v1/userTrades` as its own `FillEntity` (`TRD_${symbol}_${tradeId}`) and synced `GET /fapi/v1/income?incomeType=FUNDING_FEE` into `funding_cashflows`. | **CLOSED (VERIFIED)** |
| **P0-13** | Execution mutex was in-memory only (`AtomicBoolean`), lacking a persisted lease across process death. | `Entities.AppSettingsEntity`, `AstraTradingCoordinator.executeApprovedCandidateIfEligible` | Added `executionLockOwner` and `executionLockExpiresAtMs` to `app_settings` and enforced lease check + release around order execution. | **CLOSED (VERIFIED)** |

### 2.2 P1 — Architecture, Security, AI Governance & Runtime Hardening

| ID | Summary | Status |
|---|---|---|
| **P1-01** | Replaced hardcoded 5-symbol AI parser whitelist with `binanceAdapter.getVerifiedTradingSymbols(mode)` (failing closed in `LIVE` if `exchangeInfo` is unverified). | **CLOSED (VERIFIED)** |
| **P1-02** | Enforced strict per-provider `SecretVault` slot isolation (`secretSlotForProvider(providerId)`) with zero cross-provider or `BuildConfig` leakage. | **CLOSED (VERIFIED)** |
| **P1-03** | Enforced Independent Skeptic separation (`analyst.providerId != skeptic.providerId` or distinct `modelId` + `secretSlot`); defaults to `RULE_ONLY_PASS` when unconfigured. | **CLOSED (VERIFIED)** |
| **P1-04** | Persisted Telegram `IN_PROGRESS` state before command execution (preventing crash replay loops) and bound `/panic` confirmation nonce to `(nonce, chatId, userId, expiresAtMs)`. | **CLOSED (VERIFIED)** |
| **P1-05** | Disabled `NewsEventGateEngine.defaultCuratedFeed` sample news in `LIVE` mode so only real ingested news events are evaluated. | **CLOSED (VERIFIED)** |
| **P1-06** | Enforced empirical calibration sample threshold (`N >= 30` closed fills with historical `predictedScoreAtEntry` and Brier score `<= 0.30`). | **CLOSED (VERIFIED)** |
| **P1-07** | Enforced `MARKET_LOT_SIZE` (`marketStepSize`, `marketMinQty`, `marketMaxQty`) for `MARKET` orders and rejected fallback filters in `LIVE`. | **CLOSED (VERIFIED)** |
| **P1-08** | Enforced 30-minute ListenKey keep-alive verification (`executeListenKeyKeepAliveTick`) and stale stream detection (`isStreamStale`). | **CLOSED (VERIFIED)** |
| **P1-09** | Enforced BootRecoveryReceiver reconciliation-first recovery without auto-resuming blind trading after device reboot. | **CLOSED (VERIFIED)** |
| **P1-10** | Bound Outbound IP diagnostics and Binance read-only diagnostics to the active Android `Network` with explicit dual-stack / CGNAT disclaimers. | **CLOSED (VERIFIED)** |
| **P1-11** | Enforced `ConnectivityManager.NetworkCallback` real-time offline banner and execution gate lock when network is unvalidated. | **CLOSED (VERIFIED)** |
| **P1-12** | Fixed `SecretVault.verifyVaultSlotIntegrity(slot)` to return `false` (fail-closed) when `slot_sha256_<slot>` or `slot_len_<slot>` metadata is missing. | **CLOSED (VERIFIED)** |
| **P1-13** | Enforced log redaction across `SystemEventEntity`, `BinanceRequestDiagnostic`, and `TelegramBotController` so API keys/secrets/tokens never appear in logs. | **CLOSED (VERIFIED)** |
| **P1-14** | Verified ProGuard/R8 rules (`proguard-rules.pro`) preserve Room entities, serialization models, and OkHttp/TLS classes. | **CLOSED (VERIFIED)** |
| **P1-15** | Added Quantitative Risk Exposure & Margin Utilization visualization card on `StrategyRiskTabScreen` and compact 360dp responsive layout guards. | **CLOSED (VERIFIED)** |
