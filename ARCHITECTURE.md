# ASTRA TRADER V5.1 — SYSTEM ARCHITECTURE (`ARCHITECTURE.md`)

## 1. Architectural Overview & Safety Philosophy

Astra Trader V5.1 is a high-discipline, capital-preservation-first quantitative trading and risk-control system for **Binance USDT-M Perpetual Futures** on Android. Every subsystem is engineered around **Fail-Closed By Default** invariants:

1. **Separated Capital Accounting (`INV-01`..`INV-04`)**: User-declared reference capital in VND (`150,000 VND ≈ 5.8939 USDT`) is strictly segregated from verified exchange equity (`exchangeEquityUsdt`) and available balance (`exchangeAvailableBalanceUsdt`) returned by `GET /fapi/v2/account`. Sizing is immediately rejected (`UNVERIFIED_ACCOUNT_EQUITY`) whenever `exchangeEquityUsdt` is `null` or unverified.
2. **3-Axis Endpoint Capability Matrix (`P0-08`)**:
   - `MarketDataEndpoint` (`MAINNET` / `TESTNET`)
   - `AccountReadEndpoint` (`MAINNET` / `TESTNET`)
   - `OrderExecutionEndpoint` (`DISABLED` / `TESTNET_ONLY` / `MAINNET_ARMED`)
   When `environmentMode == "TEST"` and `binanceEndpointTarget == "MAINNET"`, the app can perform read-only diagnostics against Mainnet, while `OrderExecutionEndpoint` is locked to `DISABLED` at both the coordinator and `BinanceAdapter` layers.
3. **16-Condition Unified Execution Gate (`P0-04`)**: No entry order can be transmitted unless all 16 pre-trade gates pass simultaneously (safety debug lock, network connectivity, clock sync `<= 1500ms`, reconciliation freshness, verified equity, valid `sizingId`, exact `signalId == sizing.signalId` match, fresh `FeatureSnapshot`, non-expired signal, price drift within bound, calibrated positive net EV, Independent Skeptic `PASS` in `LIVE`, zero `UNKNOWN` orders, zero unprotected positions, device thermal/RAM health, and remaining daily risk budget).

---

## 2. Layered Module Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│ UI Layer (Jetpack Compose Material 3 — Compact 360dp Responsive)           │
│  • OverviewTabScreen          • StrategyRiskTabScreen (Risk Exposure UI)   │
│  • ApiIntegrationTabScreen    • HistoryAnalyticsTabScreen                  │
│  • SystemLogsTabScreen        • AiApiCheckerCard                           │
└───────────────────────────────────────┬────────────────────────────────────┘
                                        │ StateFlow (AstraViewModel)
┌───────────────────────────────────────▼────────────────────────────────────┐
│ Core Coordinator & State Machine (AstraTradingCoordinator)                 │
│  • 12-State BotStateMachine           • 16-Condition Unified ExecutionGate │
│  • 8-Stage Panic Close State Machine  • 3-Source Exchange Reconciliation   │
│  • Persistent Execution Lease         • Thermal/RAM/Connectivity Monitors  │
└───────┬───────────────────────┬────────────────────────┬───────────────────┘
        │                       │                        │
┌───────▼──────────┐  ┌─────────▼──────────┐  ┌──────────▼───────────────────┐
│ Quantitative &   │  │ Network & Exchange │  │ AI & Remote Governance       │
│ Risk Engines     │  │ Layer              │  │ Layer                        │
│ • QuantMathEngine│  │ • BinanceAdapter   │  │ • AiIntelligenceGateway      │
│ • NewsEventGate  │  │ • ExecutionCapGate │  │ • StrictAiJsonParser         │
│   Engine         │  │ • HMAC-SHA256 Auth │  │ • StrictSkepticJsonParser    │
│ • Empirical Beta │  │ • Network-Bound IP │  │ • AiApiCheckerEngine         │
│   Calibration    │  │   Diagnostics      │  │ • TelegramBotController      │
└───────┬──────────┘  └─────────┬──────────┘  └──────────┬───────────────────┘
        │                       │                        │
┌───────▼───────────────────────▼────────────────────────▼───────────────────┐
│ Persistence & Hardware Security Layer                                      │
│  • AstraDatabase (Room v6, 17 Tables, Explicit Migrations 1..6, Integrity) │
│  • SecretVault (Android Keystore AES-256-GCM + Per-Slot SHA-256 Integrity) │
│  • TradingForegroundService + BootRecoveryReceiver                         │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core State Machines

### 3.1 8-Stage Panic Close State Machine (`P0-01`, `P0-02`, `P0-12`)
1. `PANIC_INITIATED`: Halts new order generation and records emergency audit event.
2. `EXCHANGE_SNAPSHOT_FETCHED`: Queries `GET /fapi/v1/positionSide/dual` and `GET /fapi/v2/account`. Classifies each local `BOT_OWNED` position into:
   - `FETCH_FAILED`: Aborts blind order submission; retains local position as `PANIC_CLOSE_FAILED`.
   - `SNAPSHOT_INCONSISTENT`: Aborts if Hedge Mode / `positionSide` mismatches local state; flags `RECONCILE_MISMATCH`.
   - `VERIFIED_ZERO`: Skips redundant market close order; proceeds to protective order cleanup.
   - `VERIFIED_NON_ZERO`: Uses exact `abs(exchangePositionAmt)` and `exchangePos.positionSide`.
3. `MARKET_CLOSE_SUBMITTED`: Submits reduce-only `MARKET` close order with deterministic `clientOrderId`.
4. `POST_CLOSE_POSITION_VERIFIED`: Re-queries `GET /fapi/v2/account` to confirm `positionAmt == 0` for `(symbol, positionSide)`.
5. `CANCEL_PROTECTIVE_ORDERS_REQUESTED`: Cancels remaining `BOT_OWNED` conditional/standard orders for `(symbol, positionSide)`.
6. `CANCEL_PROTECTIVE_ORDERS_VERIFIED`: Re-queries `/fapi/v1/openOrders` and `/fapi/v1/openAlgoOrders` to verify zero leftover triggers.
7. `CLOSED_CONFIRMED`: Fetches `/fapi/v1/userTrades` and `/fapi/v1/income?incomeType=FUNDING_FEE`, persists all individual `FillEntity` records (`TRD_${symbol}_${tradeId}`), updates daily realized PnL, and deletes `(symbol, positionSide)` from `positions` in a single `@Transaction`.
8. `PANIC_FAILED_UNVERIFIED`: Entered on any verification failure; never deletes unverified local position state.

### 3.2 Room Database v6 (`AstraDatabase`)
- **17 Tables** covering settings, symbols, market features, news events, signals, AI requests, AI decisions, sizing, order intents, orders, fills, positions (`primaryKeys = ["symbol", "positionSide"]`), funding cashflows, daily snapshots, circuit breakers, system events, and Telegram update offsets.
- **Zero Destructive Fallback**: `.fallbackToDestructiveMigration` is removed; all schema transitions (`MIGRATION_1_2` through `MIGRATION_5_6`) are deterministic and verified with `PRAGMA integrity_check`.
