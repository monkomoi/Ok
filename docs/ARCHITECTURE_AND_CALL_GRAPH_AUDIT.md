# Architecture & End-to-End Call-Graph Audit — Astra Trader (USDⓈ-M Futures)

## 1. Executive Summary & Blank-Slate Philosophy
Astra Trader is a survival-first, small-capital ($50–$500 USDT) intraday trading, risk-management, and on-device multi-horizon forecasting application for Binance USDⓈ-M Perpetual Futures, engineered specifically for the **Samsung Galaxy S7 Edge (`arm64-v8a`, Exynos 8890, 4 GB LPDDR4 RAM, LineageOS Android 11 / API 30)**.

All bundled golden vectors, synthetic evaluation fixtures, and offline warmup sequences are strictly quarantined under `DataTrustState` (`EMPTY`, `BOOTSTRAPPING`, `VALIDATING`, `SHADOW_READY`, `PAPER_READY`, `LIVE_ELIGIBLE`, `QUARANTINED`) and tagged with `"is_test_fixture": true` / `"data_trust_tag": "TEST_FIXTURE"`. Fresh installations start with zero positions, zero trade orders, and zero synthetic PnL.

---

## 2. Module & Package Inventory

| Package / Module | Primary Classes | Responsibility |
|---|---|---|
| `com.example.network` | `BinanceV5RetrofitService`, `BinanceV5RetrofitModule`, `BinanceAdapter`, `NetworkConnectivityMonitor` | Authenticated HMAC-SHA256 REST & Algo Order execution (`/fapi/v1/order`, `/fapi/v1/algoOrder`), 6-step auth diagnostics, NTP/exchange clock offset tracking, public klines (`15m`, `1h`, `4h`) & order-book microstructure. |
| `com.example.forecast.data` | `ForecastFeatureSchema`, `ForecastScaler`, `MarketSequenceRepository`, `ForecastFeaturePipeline` | Causal 26-feature sequence engineering (`L = 64`), hybrid Standard/Robust-Z scaling (`[-5, +5]` clip), and OOD anomaly scoring with zero per-step heap allocations. |
| `com.example.forecast.runtime` | `CompactKotlinForecastRuntime`, `ForecastModelLoader`, `ForecastModelRegistry` | Pure-Kotlin NEON-friendly recurrent GRU + dilated causal TCN hybrid runtime (`WEIGHTS_COMPACT_JSON_INT8`), SHA-256 manifest verification, and golden vector parity checks across `BTCUSDT`, `ETHUSDT`, `SOLUSDT`, and `DOGEUSDT`. |
| `com.example.forecast.engine` | `ForecastCalibrationEngine`, `ForecastQualityGate`, `ForecastDriftMonitor`, `ForecastEnsemble`, `ForecastCoordinator` | Regularized temperature scaling (`T in [0.75, 2.50]`), shrunk prior bias adjustment (`|bias_shift| <= 0.25`), cross-head consistency verification (`checkHeadConsistency`), PSI drift monitoring, and multi-horizon (`15m`, `1h`, `4h`) outcome resolution. |
| `com.example.quant` | `QuantMathEngine`, `NewsEventGateEngine` | Multi-timeframe (`4h`/`1h`/`15m`) regime detection, volatility-scaled breakout/pullback entry bands, loss-budget position sizing (`R = min(E * r, remainingDailyLoss)`), strict `minNotional` rejection when `N < minNotional * 1.05`, and macro/microstructure shock freeze gating. |
| `com.example.ai` | `AiIntelligenceGateway`, `AiApiCheckerEngine` | Dual-agent AI Analyst + Independent Skeptic with strict JSON schema validation, provider failover (Gemini -> OpenAI -> Claude -> Deterministic Quant Fallback), and real HTTP model-endpoint health verification. |
| `com.example.telegram` | `TelegramBotController` | Out-of-band operator alerts and remote commands (`/status`, `/positions`, `/risk`, `/pause`, `/drain`, `/resume`, `/panic_close`) protected by Chat ID allowlist, monotonic `update_id` replay guard, 300s timestamp freshness, and 2-step panic confirmation. |
| `com.example.security` | `SecretVault` | Android Keystore `EncryptedSharedPreferences` (`AES256-GCM`) for Binance, AI, and Telegram credentials with memory zeroization on Level-4 Credential Lockdown. |
| `com.example.data.local` | `AstraDatabase`, `AstraDao`, `Entities` | Room SQLite persistence with WAL journaling, idempotent multi-horizon forecast evaluation index `(symbol, horizonMinutes, forecastTimestampMs)`, and blank-slate startup guarantees. |
| `com.example.service` | `TradingForegroundService`, `BootRecoveryReceiver` | Android 11 foreground service lifecycle and boot/package-replace recovery enforcement into `TEST`/`SHADOW` safety lock. |
| `com.example.engine` | `AstraTradingCoordinator` | End-to-end evaluation cycle orchestration, `DataTrustState` transitions, and 4-level kill switch execution (`PAUSE`, `DRAIN`, `PANIC_CLOSE`, `CREDENTIAL_LOCKDOWN`). |

---

## 3. End-to-End Call Graph

```text
[MainActivity / TradingForegroundService]
   └── AstraViewModel.runEvaluationCycle()
         └── AstraTradingCoordinator.runEvaluationCycle(symbol, config, ...)
               ├── 1. BinanceAdapter.fetchPublicKlines(15m, 1h, 4h) & fetchSymbolMicrostructure()
               │      └── BinanceV5RetrofitService (OkHttp + HMAC-SHA256 interceptor)
               ├── 2. ForecastCoordinator.evaluateSymbolSequence()
               │      ├── MarketSequenceRepository.updateSequences()
               │      ├── ForecastFeaturePipeline.buildSequenceTensor() -> ForecastScaler.transformSingleStep()
               │      ├── CompactKotlinForecastRuntime.runInference() [Zero per-step heap allocs]
               │      ├── ForecastCalibrationEngine.calibrateHorizon() -> checkHeadConsistency()
               │      ├── ForecastDriftMonitor.recordSample()
               │      └── ForecastQualityGate.evaluate() [Enforces headConsistent, OOD, sample N >= 200]
               ├── 3. ForecastCoordinator.resolvePendingEvaluations() & persistMultiHorizonEvaluations()
               ├── 4. QuantMathEngine.evaluateMultiTimeframeSignal(15m, 1h, 4h)
               ├── 5. NewsEventGateEngine.evaluateGate()
               ├── 6. ForecastEnsemble.combineRuleAndForecast()
               ├── 7. AiIntelligenceGateway.evaluateDualAgentGate() [Analyst + Skeptic]
               └── 8. QuantMathEngine.calculatePositionSizing() [Rejects if Notional < minNotional * 1.05]
```
