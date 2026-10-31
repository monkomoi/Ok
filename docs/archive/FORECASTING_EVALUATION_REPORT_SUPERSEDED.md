# ASTRA TRADER V5.1 — ON-DEVICE FORECASTING ENGINE EVALUATION REPORT (`FORECASTING_EVALUATION_REPORT.md`)

**Date:** 2026-10-30  
**Target Device Profile:** Samsung Galaxy S7 Edge (`4 GB RAM`, `64 GB ROM`, `LineageOS Android 11 / API 30`, `arm64-v8a`)  
**Champion Bundle ID:** `astra_gru_tcn_hybrid_v1_int8` (`v1.0.0-s7edge`)  
**Fallback / Rollback Model ID:** `astra_logistic_l2_baseline_v1`  
**Feature Schema Hash:** `d7da74c914044d8b` (`26` features, `lookback_length = 64`, `schema_version = "1.0.0"`)  
**Operational Safety Mode:** `SHADOW` (default) / `ADVISORY_TEST_ONLY` — **`LIVE` trading remains strictly locked (`safetyDebugLockActive = true`, `liveExplicitlyArmed = false`)**.

---

## 1. Walk-Forward Validation Protocol & Leakage Prevention

1. **Expanding Walk-Forward Splits (`ml/forecasting/src/split_walk_forward.py`)**:
   - **4 expanding folds** with explicit **16-bar (`4h`) purge window** and **8-bar (`2h`) embargo window** between every training, validation, and out-of-sample test partition.
   - Zero timestamp overlap between train, validation, and test partitions verified by `test_walk_forward_purge_and_embargo_ordering` (`ml/forecasting/tests/test_no_leakage.py`).
2. **Causal Feature & Robust Z-Score Scaling (`ml/forecasting/src/build_features.py`)**:
   - All 26 features (`ret_1`..`ret_16`, candle geometry, bounded 51-bar causal `ema20_dist`/`ema50_dist`/`ema20_slope`/`macd_hist_pct`, `rsi_14_norm`, Bollinger `%B` & width, `atr_14_pct`, `ewma_vol_20`, `rvol_20`, closed `htf_1h_ret` & `htf_4h_ret`, `tod_sin`/`tod_cos`, `order_book_imbalance`, `funding_rate_bps`) depend strictly on closed bars with `closeTime <= asOfCloseTimeMs`.
   - Future candle mutations at `t+1..N` produce `0.0` change at index `t` (`test_future_candle_mutation_does_not_change_features_at_t`).
   - `RobustZScoreScaler` (`median` and `1.4826 * MAD`, clipped to `[-6.0, +6.0]`) is fitted **exclusively on training partitions** (`test_scaler_fits_only_on_train`).
3. **Python-to-Kotlin Golden Feature & Output Parity**:
   - Verified across 3 multi-timeframe golden sequences (`80` closed `15m` bars + aligned `1h` and `4h` bars) in both `ml/forecasting/tests/test_feature_parity.py` and Android `ExampleUnitTest.Test 22` with maximum raw/scaled feature discrepancy `< 1e-5f`.

---

## 2. Out-of-Sample Walk-Forward Model Comparison (`15m`, `1h`, `4h`)

### 2.1 Primary Horizon: `15m` (Out-of-Sample Test Folds, `N = 48`)

| Model / Baseline | Role | Brier Score (↓) | Log Loss (↓) | ECE (↓) | MAE (↓) | Pinball Loss (↓) | Dir. Acc. (↑) | Abstain Rate | Net Expectancy (%) | Sharpe Ratio |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **`CHAMPION_ENSEMBLE_GRU_TCN_LOG`** | **On-Device Champion** | **0.06056** | **0.31032** | **0.09152** | **0.001186** | **0.000502** | **100.0%** | **70.83%** | **+0.1734%** | **6.508** |
| `LOGISTIC_L2_BASELINE` | On-Device Rollback | 0.06118 | 0.35819 | 0.13436 | 0.000625 | 0.000483 | 100.0% | 62.50% | +0.1730% | 8.288 |
| `GRU_V1_INT8` | Ensemble Member (0.50) | 0.07939 | 0.36734 | 0.12691 | 0.001165 | 0.000478 | 100.0% | 60.42% | +0.1290% | 3.940 |
| `TCN_V1_INT8` | Ensemble Member (0.35) | 0.16401 | 0.73052 | 0.20447 | 0.001820 | 0.000604 | 100.0% | 100.00% | 0.0000% | 0.000 |
| `LSTM_V1_CHALLENGER` | Offline Challenger | 0.11495 | 0.62501 | 0.26863 | 0.001829 | 0.000629 | 88.89% | 100.00% | 0.0000% | 0.000 |
| `TINY_TRANSFORMER_EXP` | Experimental Only | 0.06209 | 0.38036 | 0.14879 | 0.001373 | 0.000571 | 94.44% | 85.42% | +0.1964% | 9.032 |
| `EMA_ATR_RULE` | Rule Baseline | 0.16234 | 0.80120 | 0.17690 | 0.000933 | 0.000529 | 100.0% | 47.92% | +0.1002% | 3.538 |
| `NAIVE_PERSISTENCE` | Naive Baseline | 0.16370 | 0.81528 | 0.32003 | 0.000752 | 0.008659 | 100.0% | 60.42% | +0.1597% | 6.706 |

### 2.2 Secondary Horizon: `1h` (`N = 48`)

| Model / Baseline | Brier Score (↓) | Log Loss (↓) | ECE (↓) | MAE (↓) | Pinball Loss (↓) | Dir. Acc. (↑) | Abstain Rate | Net Expectancy (%) | Sharpe Ratio |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **`CHAMPION_ENSEMBLE_GRU_TCN_LOG`** | **0.10597** | **0.58949** | **0.19222** | **0.005516** | **0.001748** | **90.70%** | **20.83%** | **+0.6437%** | **6.467** |
| `LOGISTIC_L2_BASELINE` | 0.08773 | 0.47769 | 0.13188 | 0.003359 | 0.001280 | 93.02% | 18.75% | +0.7148% | 8.572 |
| `GRU_V1_INT8` | 0.10670 | 0.57248 | 0.16426 | 0.005293 | 0.001741 | 90.70% | 8.33% | +0.5515% | 5.660 |
| `TCN_V1_INT8` | 0.16918 | 0.85973 | 0.16832 | 0.007337 | 0.002507 | 62.79% | 68.75% | +1.0535% | 8.229 |
| `LSTM_V1_CHALLENGER` | 0.17707 | 0.90657 | 0.26963 | 0.007349 | 0.002287 | 74.42% | 77.08% | +1.1392% | 10.007 |
| `TINY_TRANSFORMER_EXP` | 0.13540 | 0.71235 | 0.19861 | 0.005834 | 0.001845 | 81.40% | 37.50% | +0.6845% | 6.574 |
| `EMA_ATR_RULE` | 0.15433 | 0.79436 | 0.17740 | 0.005733 | 0.001769 | 86.05% | 35.42% | +0.6865% | 5.992 |
| `NAIVE_PERSISTENCE` | 0.15104 | 0.77761 | 0.28591 | 0.005460 | 0.012978 | 93.02% | 31.25% | +0.7348% | 7.411 |

### 2.3 Secondary Horizon: `4h` (`N = 48`)

| Model / Baseline | Brier Score (↓) | Log Loss (↓) | ECE (↓) | MAE (↓) | Pinball Loss (↓) | Dir. Acc. (↑) | Abstain Rate | Net Expectancy (%) | Sharpe Ratio |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **`CHAMPION_ENSEMBLE_GRU_TCN_LOG`** | **0.20496** | **1.01364** | **0.23971** | **0.025768** | **0.010059** | **68.29%** | **0.00%** | **+1.1896%** | **2.819** |
| `LOGISTIC_L2_BASELINE` | 0.17724 | 0.88052 | 0.07929 | 0.022852 | 0.007666 | 73.17% | 22.92% | +2.1404% | 4.900 |
| `GRU_V1_INT8` | 0.21595 | 1.03753 | 0.35798 | 0.027347 | 0.011052 | 73.17% | 12.50% | +1.6847% | 3.913 |
| `TCN_V1_INT8` | 0.20441 | 1.08274 | 0.20652 | 0.024798 | 0.010152 | 53.66% | 0.00% | +1.1896% | 2.819 |
| `LSTM_V1_CHALLENGER` | 0.19805 | 0.98403 | 0.11230 | 0.024863 | 0.009220 | 53.66% | 60.42% | +1.6321% | 2.672 |
| `TINY_TRANSFORMER_EXP` | 0.17329 | 0.87560 | 0.11274 | 0.023568 | 0.008670 | 68.29% | 14.58% | +0.8933% | 2.107 |
| `EMA_ATR_RULE` | 0.20716 | 1.07525 | 0.08716 | 0.024151 | 0.009911 | 70.73% | 35.42% | +1.2795% | 2.784 |
| `NAIVE_PERSISTENCE` | 0.19120 | 0.98151 | 0.13350 | 0.023143 | 0.020924 | 78.05% | 31.25% | +1.5504% | 3.589 |

---

## 3. Out-of-Sample Calibration & Float32-vs-Int8 Quantization Parity

### 3.1 Post-Calibration Quality (`app/src/main/assets/forecast_bundle/calibration.json`)

| Horizon | Calibration Method | Sample Size (`N >= 30`) | Temperature (`T`) | Bias Shift `[Down, Flat, Up]` | Post-Cal Brier Score (`<= 0.30`) | Post-Cal ECE (`<= 0.12`) | Post-Cal Log Loss | Calibrated Status |
|---|---|---:|---:|---|---:|---:|---:|---|
| **`15m`** | `TEMPERATURE_AND_PLATT_PRIOR_SCALING` | `48` | `0.40` | `[-0.13577, -0.00454, +0.07706]` | **`0.11940`** | **`0.00945`** | `0.64910` | **`calibrated = true`** |
| **`1h`** | `TEMPERATURE_AND_PLATT_PRIOR_SCALING` | `48` | `0.60` | `[-0.02380, -0.17950, +0.06276]` | **`0.12111`** | **`0.04287`** | `0.65487` | **`calibrated = true`** |
| **`4h`** | `TEMPERATURE_AND_PLATT_PRIOR_SCALING` | `48` | `0.60` | `[-0.00560, -0.51980, +0.09395]` | **`0.10169`** | **`0.08695`** | `0.54792` | **`calibrated = true`** |

### 3.2 Float32 vs. Int8 Dynamic Range Quantization Parity (`compare_float_int8.py`)

- **Max Probability Absolute Difference (`|p_float32 - p_int8|`)**: **`0.00113`** (Target `< 0.015` — **PASS**)
- **Max Expected Return Absolute Difference**: **`0.000022`** (`0.0022%`, Target `< 0.0005` — **PASS**)
- **Bundle Size (`app/src/main/assets/forecast_bundle/`)**:
  - `model.tflite`: `235 KB` (Target `<= 8 MB` per model — **PASS**)
  - Total `forecast_bundle/` directory: `~620 KB` (Target `<= 25 MB` — **PASS**)

---

## 4. Physical Device Validation Status (`Samsung Galaxy S7 Edge`)

Because this cloud execution environment does not have a physical Samsung Galaxy S7 Edge (`SM-G935F` / `SM-G935FD`) connected over USB/ADB, local JVM/Robolectric tests (`w360dp-h640dp-xxhdpi`) and Python parity tests have been executed and verified (`100% PASS`), while physical hardware telemetry items are explicitly marked `DEVICE_VALIDATION_PENDING` per Section 17 of the Runbook:

| Verification Item | Cloud / Local JVM Verification | Physical S7 Edge (`SM-G935F` / API 30) Status |
|---|---|---|
| Python -> LiteRT/Compact Int8 Export & `SHA256SUMS` | **VERIFIED (`test_export_parity.py`)** | Ready for APK install |
| Python-to-Kotlin Feature & Output Golden Parity (`< 1e-5f`) | **VERIFIED (`ExampleUnitTest.Test 22`)** | Ready for APK install |
| Room `MIGRATION_6_7` Non-Destructive Upgrade & `PRAGMA integrity_check` | **VERIFIED (`ExampleRobolectricTest`)** | Ready for APK install |
| 360dp Narrow Viewport UI Rendering (`ForecastOverviewSection`) | **VERIFIED (`ExampleRobolectricTest`, `w360dp-h640dp-xxhdpi`)** | Ready for APK install |
| Physical Exynos 8890 / Snapdragon 820 2-Thread CPU P50/P95 Latency (`<= 45ms / 120ms`) | Measured `< 5ms` on JVM (`ForecastDiagnosticsSnapshot`) | **`DEVICE_VALIDATION_PENDING`** |
| Physical S7 Edge Native Heap Delta (`<= 35 MB`) & 30-Minute Thermal Soak (`< 41°C`) | Throttling gate (`>= 41°C` or `< 450 MB` free RAM) verified in code | **`DEVICE_VALIDATION_PENDING`** |
