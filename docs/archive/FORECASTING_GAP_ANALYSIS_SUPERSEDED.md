# ASTRA TRADER V5.1 — FORECASTING ENGINE GAP ANALYSIS (`FORECASTING_GAP_ANALYSIS.md`)

**Target Device Profile:** Samsung Galaxy S7 Edge (`RAM 4 GB LPDDR4 / ~3.6 GB Kernel-visible`, `ROM 64 GB UFS 2.0`, `Exynos 8890 / Snapdragon 820 arm64-v8a`, `LineageOS 18.1 Android 11 / API 30`).  
**Operating Mode:** `SHADOW / ADVISORY` (`TEST` mode locked, `safetyDebugLockActive = true`, `liveExplicitlyArmed = false`).

---

## 1. Current Repository Verification (Exact File / Method / Line Evidence)

| Subsystem | File & Method / Line | Current State Verified in Codebase | Gap for On-Device Neural Time-Series Forecasting |
|---|---|---|---|
| **Quantitative Features & Regime** | `app/src/main/java/com/example/quant/QuantMathEngine.kt` (`computeFeaturesAndRegime`, lines `774–970`) | Computes 1-step deterministic `FeatureSnapshot` from `15m` and `1h` closed candles (`ret15m`, `ret1h`, `ema20_15m`, `ema50_15m`, `emaSlope15m`, `ema20_1h`, `ema50_1h`, `atr14`, `atrPct`, `relativeVolume`, `spreadPct`, `microprice`, `orderBookImbalance`, `markIndexBasisBps`, `bodyToRangeRatio`, `upperWickRatio`, `lowerWickRatio`, `rollingReturnStd15m`, `ewmaVolatility15m`, `robustReturnZScore`). Lines `804–805` still approximate `ret5m = ret15m / 3` and `ret1m = ret15m / 15` when sub-15m klines are not fetched. | Lacks a multi-step 3D sequence tensor builder (`[1, L, F]`, `FloatArray(L * F)`), lacks `4h` multi-timeframe alignment, lacks RSI-14, MACD-hist, Bollinger %B/Width, cyclical time-of-day/day-of-week encodings, and versioned `ForecastFeatureSchema` + `ForecastScaler` parity with Python training. `ret5m = ret15m / 3` and `ret1m = ret15m / 15` must **never** be fed into the neural forecasting tensor. |
| **Probability Calibration & Net EV** | `app/src/main/java/com/example/quant/QuantMathEngine.kt` (`calibrateWinProbabilityFromHistory`, lines `592–674`; `calculateNetEv`, lines `681–746`) | Implements empirical Beta-Binomial + Platt shrinkage on historical closed trade fills (`N >= 30`, `Brier <= 0.30`). Returns `calibratedProbability = null` when `N < 30`. | Operates on scalar trade-level `rankingScore`, not multi-horizon (`15m`, `1h`, `4h`) 3-class directional logits (`pUp`, `pDown`, `pFlat`), quantile return heads (`q10`, `q50`, `q90`), predicted volatility (`sigma_hat`), or Out-of-Distribution (`OOD`) Mahalanobis/Z-score feature distance. |
| **Walk-Forward Backtest** | `app/src/main/java/com/example/quant/QuantMathEngine.kt` (`runChronologicalBacktest`, lines `1070–1204`) | Runs a single-symbol chronological rule backtest on `15m` closed bars with next-open execution and fee/slippage deduction. | Does not train, calibrate, or evaluate sequence models (`GRU`, `TCN`, `LSTM`, `Tiny Transformer`) across multi-fold purged/embargoed walk-forward splits. |
| **AI Intelligence Layer** | `app/src/main/java/com/example/ai/AiIntelligenceGateway.kt` & `AiApiCheckerEngine.kt` | HTTP client calling external LLM APIs (`Gemini`, `OpenAI-compatible`) for candidate review and skeptic JSON validation. | External network LLM only — no on-device neural sequence runtime (`LiteRT` / quantized tensor runtime), no local model registry, no SHA-256 model bundle verification, and no offline sequence inference. |
| **Market Data Adapter** | `app/src/main/java/com/example/network/BinanceAdapter.kt` (`fetchKlines`, `fetchSymbolMarketPulse`) | **Updated vs. older snapshot:** In V5.1 remediation (`P0-07`), `fetchSymbolMarketPulse` was already hardened to fail closed when partial endpoints fail (never fabricating `bestBid = markPrice * 0.9998` or `fundingRate = 0.0001`). Supports fetching klines by interval (`15m`, `1h`, `4h`). | Needs a dedicated `MarketSequenceRepository` that aligns closed candles across `15m`, `1h`, and `4h` strictly by `closeTime <= asOfCloseTime`, detects timestamp gaps/duplicates/NaNs, and rejects incomplete windows (`INSUFFICIENT_DATA`). |
| **Database & Persistence** | `app/src/main/java/com/example/data/local/AstraDatabase.kt` (lines `10–271`), `Entities.kt`, `AstraDao.kt` | **Updated vs. older snapshot:** In V5.1 remediation (`P0-03`), `AstraDatabase` was already upgraded to **version 6** with non-destructive migrations `MIGRATION_1_2` through `MIGRATION_5_6` (`fallbackToDestructiveMigration` was already removed, and `runIntegrityCheck()` was added at line `39`). | Missing the 4 forecasting tables (`forecast_predictions`, `forecast_models`, `forecast_outcomes`, `forecast_metrics`), their composite indexes `(symbol, horizon, generatedAtMs)`, DAO queries, TTL pruning, and non-destructive **`MIGRATION_6_7` (v6 -> v7)**. |
| **Execution Coordinator & Concurrency** | `app/src/main/java/com/example/engine/AstraTradingCoordinator.kt` (`globalCapitalMutex`, line `152`; `scanAndEvaluateSymbol`, lines `960–1253`) | Serializes capital/order operations with `globalCapitalMutex` and persistent DB execution locks (`P0-13`). | Needs event-driven `ForecastScheduler` & `ForecastCoordinator` running **outside** `globalCapitalMutex` on `Dispatchers.Default.limitedParallelism(1)`, throttled on closed-candle events and S7 Edge thermal/RAM pressure, operating in `SHADOW` / `ADVISORY` mode without bypassing `RiskEngine`. |
| **UI & S7 Edge Diagnostics** | `app/src/main/java/com/example/ui/screens/OverviewTabScreen.kt` (lines `81–952`) | Displays mode/lock status, capital separation, candidate signals, risk exposure chart, and `DeviceHealthSnapshot` (`availableRamMb`, `kernelTotalRamMb`, `batteryTempCelsius`). | Missing the **On-Device Time-Series Forecast Card** (`15m`/`1h`/`4h` probabilities `pUp`/`pDown`/`pFlat`, `expectedReturnPct`, quantiles `q10`/`q50`/`q90`, `predictedVolatilityPct`, `uncertainty`, `OOD` score, calibration status, model version, rejection reason) and **S7 Edge Forecast Runtime Diagnostics Card** (active/rollback model, backend/threads, load/inference P50/P95 latency, native heap delta, missing/stale counters). |
| **Gradle & Build Dependencies** | `gradle/libs.versions.toml` & `app/build.gradle.kts` | Uses Kotlin `2.2.10`, AGP `9.1.1`, Room `2.7.0`, Coroutines `1.10.2`, OkHttp `4.10.0`, Compose BOM `2024.09.00`. No ML/LiteRT dependencies or custom native `.so` bloat. | Must keep APK lean for S7 Edge (`arm64-v8a` primary, no GPU/Flex delegate bloat) while providing a deterministic, thread-safe on-device `LiteRtForecastRuntime` supporting both `.tflite` flatbuffer inspection/execution and verified compact INT8/FP16/FP32 GRU+TCN+Baseline tensor execution with preallocated buffers. |

---

## 2. Reconciling Historical Runbook Notes with V5.1 Codebase State

Per Section 2 of the Forecasting Runbook (*"Nếu audit code hiện tại cho thấy một nhận định cũ không còn đúng, phải trích file + method + test chứng minh và cập nhật báo cáo; không sửa theo kết luận lỗi thời"*):

1. **Room Schema Version (`v6` vs. historical `v4`)**:
   - **Evidence**: `AstraDatabase.kt` lines `30` and `56–258` already define `version = 6` and `ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)`. `.fallbackToDestructiveMigration()` is already absent.
   - **Action**: Implement **`MIGRATION_6_7`** (`version = 6 -> 7`) to add `forecast_predictions`, `forecast_models`, `forecast_outcomes`, and `forecast_metrics` with zero data loss, and test `6 -> 7` migration under Robolectric/SQLite.
2. **Market Pulse Partial Fallback (`P0-07`)**:
   - **Evidence**: `BinanceAdapter.kt` (`fetchSymbolMarketPulse`) already returns `BinanceAdapterResult(isSuccess = false, errorCategory = PARSE_OR_EMPTY_ERROR)` if any of `/fapi/v1/premiumIndex`, `/fapi/v1/ticker/bookTicker`, or `/fapi/v1/klines` fails, tested by `ExampleUnitTest`.
   - **Action**: Preserve this fail-closed behavior and add `MarketSequenceRepository` validation on top of closed candles (`15m`, `1h`, `4h`).
3. **SecretVault Fail-Closed (`P1-12`)**:
   - **Evidence**: `SecretVault.kt` already throws/locks on Keystore failure without `PlaintextFallback` (`isHardwareKeystoreBacked()`).
   - **Action**: Preserve unchanged.

---

## 3. Architectural Plan for On-Device Forecasting Engine (Phases 1–6)

1. **Phase 1–2 (Off-Device Python Training, Walk-Forward, Calibration & Export Pipeline)**:
   - Create `/ml/forecasting/` with versioned `configs/feature_schema.json` (`26` features: `20 CORE`, `3 OPTIONAL_MARKET_MICROSTRUCTURE`, `3 OPTIONAL_DERIVATIVES`), model configs (`gru_v1.yaml`, `tcn_v1.yaml`, `lstm_v1.yaml`, `tiny_transformer_v1.yaml`), runnable training/evaluation/calibration/export scripts in `/ml/forecasting/src/`, and automated parity/leakage tests in `/ml/forecasting/tests/`.
   - Export verified model bundle to `app/src/main/assets/forecast_bundle/` containing `manifest.json`, `model.tflite`, `weights_compact.json` (quantized/compact GRU v1 + TCN v1 + Logistic Baseline weights for deterministic cross-platform parity), `feature_schema.json`, `scaler.json`, `calibration.json`, `golden_feature_vectors.json`, `golden_outputs.json`, and `SHA256SUMS`.
2. **Phase 3 (Android Forecasting Engine Package `com.example.forecast.*`)**:
   - `domain/`: `ForecastModels.kt`, `ForecastStatus.kt`, `ForecastRepository.kt`
   - `data/`: `ForecastFeatureSchema.kt`, `ForecastScaler.kt`, `MarketSequenceRepository.kt`, `ForecastFeaturePipeline.kt`
   - `runtime/`: `ForecastModelRuntime.kt`, `LiteRtForecastRuntime.kt` (Mutex-guarded, single/dual CPU thread, preallocated `FloatArray` / `ByteBuffer` buffers, zero hot-path allocation), `ForecastModelLoader.kt` (SHA-256 + schema hash + golden output self-test), `ForecastModelRegistry.kt` (champion/challenger + automatic rollback)
   - `engine/`: `ForecastCalibrationEngine.kt` (Temperature Scaling + Platt/Isotonic + ECE/Brier check), `ForecastDriftMonitor.kt` (Mahalanobis/Z-score OOD + PSI drift), `ForecastQualityGate.kt` (fail-closed status & cost-aware edge check), `ForecastEnsemble.kt` (regime-aware combination of GRU v1 + TCN v1 + Statistical Baseline), `ForecastCoordinator.kt`
   - `service/`: `ForecastScheduler.kt` (closed-candle event-driven trigger, thermal/RAM throttling for S7 Edge, completely decoupled from `globalCapitalMutex`)
3. **Phase 4–6 (Room `MIGRATION_6_7`, UI Integration, Parity/Fault Tests & Deliverables)**:
   - Upgrade `AstraDatabase` to `version = 7` with `MIGRATION_6_7`.
   - Wire `ForecastCoordinator` into `AstraTradingCoordinator` and `AstraViewModel` in **SHADOW** mode (`Advisory` filter option in `TEST` mode only; `LIVE` remains locked).
   - Add Forecast & S7 Edge Runtime Diagnostics cards to `OverviewTabScreen.kt`.
   - Verify Python unit tests (`test_no_leakage.py`, `test_feature_parity.py`, `test_label_generation.py`, `test_export_parity.py`) and Android JVM/Robolectric tests (`gradle :app:testDebugUnitTest`).
