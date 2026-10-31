# Changelog

## [2.0.0] — Blank-Slate Research & Remediation Release
### Added
- **Binance V5 / USDⓈ-M Futures Retrofit Service (`BinanceV5RetrofitService.kt`)**: Full public market data and authenticated HMAC-SHA256 order & conditional Algo Order (`/fapi/v1/algoOrder`) interface.
- **Blank-Slate Data Trust State Machine (`DataTrustState`)**: Quarantines all bundled golden vectors and offline warmup bars with `isTestFixture = true` and `data_trust_tag = "TEST_FIXTURE"`.
- **Cross-Head Consistency Gate (`ForecastCalibrationEngine.checkHeadConsistency`)**: Detects contradictions between classification probabilities (`pDown`, `pFlat`, `pUp`) and expected return / median quantile heads (`expectedReturnPct`, `q50Pct`), setting `ForecastStatus.HEAD_INCONSISTENT` and blocking execution.
- **Per-Symbol Model Eligibility (`SymbolModelEligibility`)**: Added 4-symbol golden vectors (`BTCUSDT`, `ETHUSDT`, `SOLUSDT`, `DOGEUSDT`) and explicit eligibility tiers (`FULL_SUPPORTED`, `POOLED_MODEL_ONLY`, `BASELINE_ONLY`, `UNSUPPORTED`).
- **Small-Capital Loss-Budget Position Sizing (`QuantMathEngine`)**: Added strict `minNotional` rejection when `notional < max(minNotional, 5.0) * 1.05`.
- **Comprehensive Audit & Engineering Documentation (`/docs/`)**: Added architecture audit, defect register, schema contract, backtest report, S7 Edge benchmark, and mode-transition checklist.

### Fixed
- **Manifest Integrity**: Replaced misleading `model_tflite_sha256` with `weights_compact_sha256` and `"artifact_format": "WEIGHTS_COMPACT_JSON_INT8"`.
- **Small-Sample 4h Calibration Collapse**: Bounded temperature scaling to `[0.75, 2.50]`, shrank prior bias shifts by `n / 200` (`|bias_shift| <= 0.25`), and marked `N < 200` splits as `INSUFFICIENT_SAMPLE_SHADOW_ONLY`.
- **Backtest Equity Accounting**: Fixed `ml/forecasting/src/evaluate.py` so non-traded (`HOLD`) bars never alter or compound equity and same-bar SL/TP collisions resolve conservatively to `STOP_LOSS`.
