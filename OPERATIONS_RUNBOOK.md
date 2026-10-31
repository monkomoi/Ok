# ASTRA TRADER V5.1 — OPERATIONS & INCIDENT RUNBOOK (`OPERATIONS_RUNBOOK.md`)

**Operational Mode Default:** `TEST` (`safetyDebugLockActive = true`, `liveExplicitlyArmed = false`, `forecastOperatingMode = "SHADOW"`)  
**Emergency Principle:** **NEVER enable `LIVE` trading or unlock `safetyDebugLockActive` unless all 11 items in `LiveActivationChecklist` are green and verified.**

---

## 1. Standard Operating Procedures (SOP)

### 1.1 Credential Setup & Read-Only Verification (`ApiIntegrationTabScreen`)
1. Enter your Binance API Key and API Secret in the **Tích hợp API & Chẩn đoán** tab.
   - `SecretVault` automatically strips outer whitespace, CR/LF, quotes, and `Bearer ` prefixes, encrypts via Android Keystore AES-256-GCM, and verifies the per-slot SHA-256 hash.
2. Tap **Chẩn đoán IP Outbound** to inspect the device's active Android `Network` interface, IPv4 address (`api.ipify.org`), IPv6 address (`api64.ipify.org`), and transport type (Wi-Fi / 4G / VPN).
3. Tap **Kiểm tra & Đối soát Binance (Read-Only)** to run the 6-step read-only diagnostic pipeline:
   - Step 1: Local Vault SHA-256 & `X-MBX-APIKEY` header integrity check.
   - Step 2: Public connectivity & server clock sync (`GET /fapi/v1/time`).
   - Step 3: Exchange metadata & symbol filters (`GET /fapi/v1/exchangeInfo`).
   - Step 4: Spot API Key permission probe (`GET /api/v3/account`, if applicable).
   - Step 5: Futures balance probe (`GET /fapi/v2/balance`).
   - Step 6: Futures account & open positions/orders reconciliation (`GET /fapi/v2/account`, `/fapi/v1/openOrders`, `/fapi/v1/openAlgoOrders`).

### 1.2 On-Device Time-Series Forecasting Engine Operation & Rollback (`OverviewTabScreen`)
1. **Operating Modes (`SHADOW` vs. `ADVISORY_TEST_ONLY`)**:
   - **`SHADOW` (Default)**: Computes `15m`, `1h`, and `4h` multi-horizon forecasts on every closed `15m` bar (`64` steps x `26` features), persists predictions to Room (`forecast_predictions`), and displays calibrated probabilities (`pUp`, `pFlat`, `pDown`), quantiles (`Q10`/`Q50`/`Q90`), uncertainty, and OOD score in the Overview tab without blocking or modifying trade candidates.
   - **`ADVISORY_TEST_ONLY`**: Uses the primary `15m` forecast in `TEST` mode only as a risk-reducing filter (vetoing opposite-direction setups or shrinking risk fraction by `0.75x` when uncertainty is elevated). It **never** auto-opens trades, **never** overrides `RiskEngine`, and **never** unlocks `LIVE` trading.
2. **Automatic & Manual Model Rollback**:
   - If the Champion model (`astra_gru_tcn_hybrid_v1_int8`) encounters `>= 3` consecutive inference errors, `ForecastModelRegistry` automatically rolls back to the verified `astra_logistic_l2_baseline_v1` model.
   - Operators can also manually toggle between **Rollback Baseline** and **Khôi phục Champion** at any time on the **Chẩn đoán Runtime Dự báo (S7 Edge)** card in the Overview tab.
3. **S7 Edge Thermal & Memory Throttling Gate**:
   - When battery temperature reaches `>= 41.0°C` (`thermalWarning`) or free system RAM drops below `450 MB` (`lowRamWarning`), `ForecastScheduler` automatically pauses background multi-symbol inference and serves cached/fail-closed status to protect foreground order and SL/TP supervision.

### 1.3 Handling Common Incident States

| Bot State / Error Code | Root Cause | Operator Action |
|---|---|---|
| `AUTH_FAILED_2014` (`-2014`) | Malformed API Key format or invisible whitespace/characters. | Re-paste raw API Key and Secret into Vault and run Step 1 integrity check. |
| `AUTH_FAILED_2015` (`-2015`) | One of 5 causes: (1) Wrong key for target environment (Mainnet vs Testnet), (2) Outbound IPv4 not whitelisted on Binance, (3) "Enable Futures" checkbox not ticked on Binance API settings, (4) Futures account not yet activated, or (5) Key revoked/expired. | Inspect the 6-step diagnostic card and Outbound IPv4 address; update Binance API restrictions and re-run **Kiểm tra & Đối soát**. |
| `UNKNOWN_ORDER_STATUS` | Network timeout or HTTP 5XX occurred during order submission (`INV-06`). | Do **NOT** place manual duplicate orders. Tap **Đối soát 3 nguồn** so the coordinator queries `origClientOrderId` and resolves exact exchange status. |
| `PROTECTION_FAILURE` | An open position on the exchange is missing a verified `STOP_MARKET` (SL) or `TAKE_PROFIT_MARKET` (TP) conditional order. | Tap **Đối soát 3 nguồn** or execute **Panic Close** to flatten the unprotected position safely. |
| `PANIC_FAILED_UNVERIFIED` | Emergency close encountered a pre-close fetch error, Hedge Mode mismatch (`SNAPSHOT_INCONSISTENT`), post-close verification error, or protective cancel failure. | Local position is intentionally retained in DB (`PANIC_CLOSE_FAILED`). Verify network connectivity and re-trigger **Panic Close** or close directly on Binance Futures interface, then run **Đối soát 3 nguồn**. |
| `ForecastStatus.OUT_OF_DISTRIBUTION` / `MODEL_INCOMPATIBLE` | Market features exceeded the training distribution (`OOD > 4.20`) or bundle checksum/schema check failed. | Engine fails closed (no fabricated probabilities). Keep bot in `SHADOW` or `TEST` mode; tap **Rollback Baseline** if needed. |

---

## 2. Final Implementation & Verification Matrix (Section 17 Deliverable)

| Category | Status | Scope & Evidence |
|---|---|---|
| **Implemented** | **COMPLETE** | 1. `/FORECASTING_GAP_ANALYSIS.md` with exact file/method/line evidence.<br>2. Full Python pipeline (`/ml/forecasting/`) with 26 causal features, cost-aware labels, 4-fold walk-forward validation with purge+embargo, Naive/EMA-ATR/Logistic baselines, GRU v1, TCN v1, LSTM v1 challenger, Tiny Transformer experimental, out-of-sample Temperature+Platt calibration, Int8 quantization parity check, and LiteRT bundle export.<br>3. Verified asset bundle in `app/src/main/assets/forecast_bundle/` with `SHA256SUMS` and golden vectors.<br>4. 15-file Android package `com.example.forecast` (`domain`, `data`, `runtime`, `engine`, `service`).<br>5. Non-destructive Room migration `MIGRATION_6_7` (`version = 7`) adding 4 forecasting tables.<br>6. `ForecastOverviewSection` UI cards (`OverviewTabScreen.kt` & `MainActivity.kt`) formatted for `360dp` S7 Edge screen width. |
| **Tested** | **COMPLETE (100% PASS)** | 1. 8 Python unit & parity tests (`ml/forecasting/tests/test_*.py`).<br>2. 23 JUnit/Robolectric unit & golden parity tests (`ExampleUnitTest.kt`).<br>3. 11 Robolectric integration, Room `MIGRATION_6_7`, and `360dp` Compose UI tests (`ExampleRobolectricTest.kt`).<br>4. Full Android debug APK compilation (`compile_applet`). |
| **Not Tested** | `DEVICE_VALIDATION_PENDING` | Physical USB/ADB execution on a hardware Samsung Galaxy S7 Edge (`SM-G935F` / `SM-G935FD`, LineageOS 18.1 / API 30) for 30-minute physical battery thermal soak (`< 41°C`) and hardware Cortex-A53/Exynos M1 CPU latency profiling (cannot be run inside the cloud container without physical ADB hardware attached). |
| **Deferred** | None | All software, ML pipeline, database migration, UI, and test deliverables in the Runbook are implemented. |
| **Blocked** | None | Zero software or build blockers. |
