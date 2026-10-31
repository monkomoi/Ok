# Astra Trader — USDⓈ-M Futures Small-Capital Intraday & On-Device Forecasting

Astra Trader is an Android (`arm64-v8a`, minSdk 26 / targetSdk 34, optimized for **Samsung Galaxy S7 Edge / API 30**) intraday quantitative trading, risk-management, and multi-horizon (`15m`, `1h`, `4h`) forecasting application for **Binance USDⓈ-M Perpetual Futures**.

## Key Capabilities
1. **Binance V5 / USDⓈ-M Futures Retrofit Service (`BinanceV5RetrofitService.kt`)**:
   - Full public market data (`klines`, `depth`, `premiumIndex`, `openInterest`, `bookTicker`, `exchangeInfo`) and authenticated HMAC-SHA256 order & conditional Algo Order placement (`/fapi/v1/order`, `/fapi/v1/algoOrder`).
2. **Blank-Slate Data Trust & Quarantine**:
   - Starts with zero synthetic positions, zero fake trades, and zero fabricated PnL.
   - Explicit `DataTrustState` badge (`EMPTY`, `BOOTSTRAPPING`, `VALIDATING`, `SHADOW_READY`, `PAPER_READY`, `LIVE_ELIGIBLE`, `QUARANTINED`).
   - All bundled golden vectors and offline warmup bars carry `"data_trust_tag": "TEST_FIXTURE"`.
3. **Regularized Multi-Horizon Forecasting (`CompactKotlinForecastRuntime`)**:
   - 26 causal sequence features (`L = 64`), hybrid Standard/Robust-Z scaling, and pure-Kotlin zero-step-allocation GRU + dilated causal TCN runtime.
   - Regularized temperature scaling (`T in [0.75, 2.50]`), bounded prior shift (`|bias_shift| <= 0.25`), calibration sample qualification (`INSUFFICIENT_SAMPLE_SHADOW_ONLY` when `N < 200`), and cross-head consistency enforcement (`ForecastStatus.HEAD_INCONSISTENT`).
4. **Small-Capital Loss-Budget Position Sizing (`QuantMathEngine`)**:
   - Sizes strictly from `R = min(Equity * r, remainingDailyRiskBudget)`.
   - Rounds quantity `DOWN` to `stepSize` and **rejects** trades whenever `notional < max(minNotional, 5.0) * 1.05` instead of rounding up and violating `R`.
5. **Dual-Agent AI Gate & Replay-Protected Telegram Control**:
   - Analyst + Independent Skeptic with strict JSON schema validation and deterministic fallback.
   - Telegram controller with Chat ID allowlist, monotonic `update_id` replay guard, 300s freshness check, and 2-step `/panic_close CONFIRM`.

## Verification Commands
- **Off-Device Python Forecasting Pipeline & Unit Tests**:
  ```bash
  python3 ml/forecasting/src/export_litert.py
  python3 -m unittest discover -s ml/forecasting/tests -p "test_*.py" -v
  ```
- **Android Unit & Robolectric Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```

## Documentation Index
- [Architecture & Call-Graph Audit](docs/ARCHITECTURE_AND_CALL_GRAPH_AUDIT.md)
- [Defect & Omission Register](docs/DEFECT_AND_OMISSION_REGISTER.md)
- [Data Schema & API Contracts](docs/DATA_SCHEMA_AND_API_CONTRACTS.md)
- [Backtest & Paper-Trading Report](docs/BACKTEST_AND_PAPER_TRADING_REPORT.md)
- [S7 Edge Hardware Benchmark](docs/S7_EDGE_BENCHMARK.md)
- [Mode-Transition & Acceptance Checklist](docs/MODE_TRANSITION_AND_ACCEPTANCE_CHECKLIST.md)
- [Known Limitations](KNOWN_LIMITATIONS.md)
- [Changelog](CHANGELOG.md)
