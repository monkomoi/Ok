# ASTRA TRADER V5.1 — AUTOMATED TEST & VERIFICATION REPORT (`TEST_REPORT.md`)

**Date:** 2026-10-30  
**Test Runners:**
- **Python Forecasting Pipeline:** `python3 -m unittest discover -s ml/forecasting/tests -p "test_*.py" -v`
- **Android JUnit 4 + Robolectric (`sdk = [36]`, `qualifiers = "w360dp-h640dp-xxhdpi"`) + Compose UI Test:** `gradle :app:testDebugUnitTest`

---

## 1. Test Suite Summary

| Test Suite / Class | Test Count | Scope | Status |
|---|---|---|---|
| `ml/forecasting/tests/test_*.py` | 8 tests | Walk-forward purge (`16` bars) & embargo (`8` bars) ordering, future candle mutation zero-leakage invariance, train-only `RobustZScoreScaler` fitting, 26-feature schema & causal pipeline parity (`< 1e-5`), cost-aware 3-class directional labeling & monotonic horizon cost thresholds, `SHA256SUMS` bundle verification, `TFL3` header check, and Float32-vs-Int8 quantization parity (`max_prob_diff = 0.00113`). | **100% PASS** |
| `com.example.ExampleUnitTest` | 17 tests | API Key sanitization (`-2014`), `-2015` 5-cause classification, HMAC-SHA256 signing (`-1022`), `UNKNOWN_EXECUTION_STATUS` (`502`), VND vs Exchange Equity separation (`INV-01`), Unconfigured AI Skeptic (`RULE_ONLY_PASS`), `LiveActivationChecklist` lock, `QuantMathEngine` rounding & `INV-18` PnL, `NewsEventGateEngine` 50-repost deduplication & prompt injection, Algo Order parsing & post-fill SL/TP recalculation (`P0-06`), empirical probability calibration (`N >= 30`), `MARKET_LOT_SIZE` enforcement, `TradingForegroundService` backoff & verified ListenKey keep-alive, `userTrades` multi-fill parsing, fail-closed `fetchSymbolMarketPulse` (`P0-07`), `ExecutionCapability` adapter lock (`P0-08`, `P1-01`), **Python-to-Kotlin Golden Feature & Calibrated Output Parity (`Test 22`, `< 1e-5f`)**, and **Forecasting Engine fail-closed statuses (`INSUFFICIENT_DATA`, `FEATURE_INVALID`, `MODEL_INCOMPATIBLE`, `OUT_OF_DISTRIBUTION`) + 3-error automatic rollback to `astra_logistic_l2_baseline_v1` (`Test 23`)**. | **100% PASS** |
| `com.example.ExampleRobolectricTest` | 13 tests | `SecretVault` AES-256-GCM round-trip & missing hash fail-closed (`P1-12`), reconciliation failure keeping equity `null`, `UNKNOWN` order intent blocking duplicate entry (`INV-06`), compact 360dp Compose UI rendering (`OverviewTabScreen`, `StrategyRiskTabScreen`, `ApiIntegrationTabScreen`), Room fill deduplication, 17-table Room persistence, Outbound IP & 6-step read-only Binance diagnostics, 22-provider `AiApiCheckerEngine` presets & strict per-provider key isolation (`P0-09`, `P1-02`), 8-stage Panic Close state machine (`FETCH_FAILED`, `VERIFIED_ZERO`, `POST_CLOSE_FAIL`, `CANCEL_FAIL`, `FULL_SUCCESS`), Independent Skeptic separation (`P1-03`), SL+TP reconciliation (`P0-05`), Telegram `IN_PROGRESS` crash safety & `/panic` nonce binding (`P1-04`), Room v6 composite key `(symbol, positionSide)` for Hedge Mode (`P0-02`), `PRAGMA integrity_check` (`P0-03`), multi-fill individual `FillEntity` persistence (`P0-12`), and **non-destructive Room `MIGRATION_6_7` (`6 -> 7`) + `ForecastCoordinator` end-to-end persistence + `360dp` `ForecastOverviewSection` UI rendering**. | **100% PASS** |
| **Total** | **38 tests** | **Full V5.1 Remediation + On-Device Time-Series Forecasting Engine Coverage** | **38 / 38 PASS (0 Failures)** |

---

## 2. Key Verification Highlights

1. **Python-to-Kotlin Feature & Output Parity (`Test 22`)**: Verified across all 3 multi-timeframe golden samples (`80` closed `15m` bars + `1h` and `4h` bars) in `golden_feature_vectors.json` that Kotlin `ForecastFeaturePipeline.buildSequenceTensor` matches Python `build_features.py` within `< 1e-5f` for all 26 raw and scaled features, and `ForecastCalibrationEngine.calibrateLogits` produces calibrated probabilities summing to `1.000000`.
2. **Fail-Closed & Fault-Injection Safety (`Test 23`)**: Verified that `< 80` bars returns `ForecastStatus.INSUFFICIENT_DATA` (`tensorInput == null`), `close == 0` returns `ForecastStatus.FEATURE_INVALID`, corrupted `model.tflite` bytes return `ForecastStatus.MODEL_INCOMPATIBLE`, `OOD >= 0.65` returns `ForecastStatus.OUT_OF_DISTRIBUTION`, and 3 consecutive runtime errors automatically roll back to `astra_logistic_l2_baseline_v1`.
3. **Non-Destructive Room `MIGRATION_6_7` & Coordinator Integration (`ExampleRobolectricTest`)**: Verified that migrating a v6 database preserves existing `app_settings` rows with `forecastOperatingMode = 'SHADOW'`, creates all 4 forecasting tables (`forecast_predictions`, `forecast_models`, `forecast_outcomes`, `forecast_metrics`), passes `PRAGMA integrity_check`, persists `15m`/`1h`/`4h` predictions during `scanAndEvaluateSymbol`, keeps `LIVE` mode locked, and renders `forecast_engine_card` & `s7_edge_forecast_diagnostics_card` cleanly on `360dp` width.
