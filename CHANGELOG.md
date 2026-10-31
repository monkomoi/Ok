# ASTRA TRADER — CHANGELOG (`CHANGELOG.md`)

## [5.1.0-hardened] — 2026-10-30

### Security & Capital Safety (`P0`)
- **`P0-01` (Panic Close Pre-Close & Hedge Mode Verification)**: Added `PanicPreCloseVerificationState.SNAPSHOT_INCONSISTENT`, queried `GET /fapi/v1/positionSide/dual` + `GET /fapi/v2/account` prior to closing, filtered strictly to `ownershipTag == "BOT_OWNED"`, and blocked blind close orders on mode/side mismatch.
- **`P0-02` (Hedge Mode Composite Primary Key)**: Migrated `PositionEntity` (`positions` table) to composite primary key `primaryKeys = ["symbol", "positionSide"]` with unique index `(symbol, positionSide)` so `LONG` and `SHORT` legs never overwrite each other.
- **`P0-03` (Non-Destructive Room Migrations & Integrity Check)**: Removed `.fallbackToDestructiveMigration(true)` from `AstraDatabase`, added `MIGRATION_5_6`, exposed `ALL_MIGRATIONS`, and added `runIntegrityCheck()` (`PRAGMA integrity_check`).
- **`P0-04` (Unified Execution Gate Exact `signalId` Match)**: Removed `recentSignals.firstOrNull { it.symbol == sizing.symbol }` fallback in `evaluateUnifiedExecutionGate`; enforced exact `signalId == sizing.signalId` match, non-blank `sizingId`, fresh snapshot age (`<= 15m`), and `SkepticVerdict.PASS` in `LIVE`.
- **`P0-05` & `P0-06` (Post-Fill Price Resolution & Protective SL/TP Verification)**: Added fallback lookup via `/fapi/v1/userTrades` and `/fapi/v1/order` when `orderResp.avgPrice <= 0`, enforced direction-aware SL/TP recalculation from actual fill price, and verified prior conditional order cancellation before retry.
- **`P0-07` (Fail-Closed Market Pulse)**: Removed hardcoded `0.0001` funding rate and `bid = markPrice` / `ask = markPrice` fallbacks in `BinanceAdapter.fetchSymbolMarketPulse`; missing or invalid `premiumIndex` or `bookTicker` fields now fail closed.
- **`P0-08` (3-Axis Execution Capability Matrix)**: Added `ExecutionCapability` (`MarketDataEndpoint`, `AccountReadEndpoint`, `OrderExecutionEndpoint`) and enforced `verifyOrderMutationCapability` at the `BinanceAdapter` boundary.
- **`P0-09` (Complete Removal of Compile-Time AI Key Fallbacks)**: Removed all `BuildConfig.GEMINI_API_KEY` fallbacks across `AiIntelligenceGateway`, `AiApiCheckerEngine`, `AstraViewModel`, and `AstraTradingCoordinator`.
- **`P0-10` (Exchange-Side Leverage & Isolated Margin Sync)**: Added `changeSymbolLeverage` (`POST /fapi/v1/leverage`) and `changeSymbolMarginType` (`POST /fapi/v1/marginType`) and enforced them before entry order submission.
- **`P0-11` (Foreign/Manual Position Isolation)**: Flagged unowned exchange positions as `FOREIGN_MANUAL` during reconciliation and blocked automated entry on those symbols.
- **`P0-12` (Multi-Fill Persistence & Funding Fee Sync)**: Updated `finalizeVerifiedPositionCloseAtomically` to persist every partial fill from `/fapi/v1/userTrades` (`TRD_${symbol}_${tradeId}`) and added `fetchFundingIncomeHistory` (`GET /fapi/v1/income?incomeType=FUNDING_FEE`).
- **`P0-13` (Persistent Execution Lease)**: Added `executionLockOwner` and `executionLockExpiresAtMs` to `AppSettingsEntity` to guard against overlapping order submissions across process restarts.

### Architecture, AI Governance & Runtime (`P1` & `P2`)
- **`P1-01`..`P1-05`**: Dynamic verified symbol whitelist from `exchangeInfo` (fail-closed in `LIVE`), per-provider `SecretVault` slot isolation, Independent Skeptic enforcement, Telegram `IN_PROGRESS` crash-loop prevention + `/panic` nonce binding to `(nonce, chatId, userId, expiry)`, and exclusion of curated sample news in `LIVE` mode.
- **`P1-12`**: Fixed `SecretVault.verifyVaultSlotIntegrity` to fail closed (`return false`) when SHA-256 hash or length metadata is missing.
