# ASTRA TRADER V5.1 — TESTNET SOAK & RESILIENCE REPORT (`TESTNET_SOAK_REPORT.md`)

**Environment:** `TEST` (`https://testnet.binancefuture.com` / Simulated Fault-Injection Harness)  
**Real Capital Used:** `0.00 USDT` (`LIVE` mode disarmed; `safetyDebugLockActive = true`)

---

## 1. Fault-Injection & Resilience Scenarios Verified

| Scenario ID | Fault / Condition Injected | Expected Fail-Closed Behavior | Result |
|---|---|---|---|
| **SOAK-01** | Network timeout (`HTTP 502` / `SocketTimeoutException`) during order submission | Classifies as `UNKNOWN_EXECUTION_STATUS`; persists `OrderIntentEntity(status = "UNKNOWN")`; blocks any subsequent entry order until reconciled by `origClientOrderId` (`INV-06`). | **PASS** |
| **SOAK-02** | `/fapi/v1/ticker/bookTicker` returns `HTTP 500` or `/fapi/v1/premiumIndex` omits `lastFundingRate` (`P0-07`) | `fetchSymbolMarketPulse` fails closed (`isSuccess = false`); never fabricates `0.0001` funding rate or zero spread (`bid = markPrice`). | **PASS** |
| **SOAK-03** | Panic Close triggered when `/fapi/v2/account` returns `HTTP 503` (`P0-01`) | Pre-close verification returns `FETCH_FAILED`; zero blind market close orders submitted; local `PositionEntity` preserved as `PANIC_CLOSE_FAILED`. | **PASS** |
| **SOAK-04** | Panic Close triggered when exchange position is already `0` (`VERIFIED_ZERO`) | Skips redundant `MARKET` close order; cancels any leftover protective orders; cleans up stale local position row. | **PASS** |
| **SOAK-05** | Panic Close market order fills across multiple partial trades (`tradeId = 8001, 8002`) (`P0-12`) | Both partial fills are persisted as distinct `FillEntity` records (`TRD_DOGEUSDT_8001`, `TRD_DOGEUSDT_8002`) with exact commission and realized PnL. | **PASS** |
| **SOAK-06** | Open position on exchange has `STOP_MARKET` (SL) active, but `TAKE_PROFIT_MARKET` (TP) is missing (`P0-05`) | `runFullReconciliation` transitions bot to `BotState.PROTECTION_FAILURE` and marks position `PROTECTION_DEGRADED`. | **PASS** |
| **SOAK-07** | Primary Analyst AI runs (`gemini`), but no independent Skeptic provider is configured (`P1-03`) | Skeptic verdict remains `RULE_ONLY_PASS` (never `PASS`); `LIVE` execution gate remains blocked. | **PASS** |
| **SOAK-08** | User Data Stream `listenKey` keep-alive fails (`PUT /fapi/v1/listenKey` returns `false`) (`P1-08`) | `TradingForegroundService.executeListenKeyKeepAliveTick` refuses to advance `lastListenKeyKeepAliveUtcMs`; exponential backoff reconnects stream cleanly. | **PASS** |
| **SOAK-09** | Duplicate background service start intent (`ACTION_START_MONITORING`) | Atomic singleton loop guard (`tryAcquireSingletonLoopForTest`) blocks duplicate loop coroutines (`activeLoopInstances == 1`). | **PASS** |
| **SOAK-10** | Telegram replay of older `update_id` or `/panic confirm` with wrong nonce | Monotonic offset filter rejects replayed `update_id`; `/panic confirm` validates `(nonce, chatId, userId, expiry)`. | **PASS** |
