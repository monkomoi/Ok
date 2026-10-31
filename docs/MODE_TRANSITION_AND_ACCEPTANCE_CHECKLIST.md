# Mode-Transition Criteria & Acceptance Checklist

Astra Trader enforces a strict, multi-stage progression before any real-capital order execution can ever be unlocked. **By default, the app boots into `TEST` environment and `SHADOW` forecast mode with `safetyDebugLockActive = true`.**

---

## Stage 0 -> Stage 1: `EMPTY` / `BOOTSTRAPPING` to `SHADOW_READY`
- [x] `ForecastModelLoader` verifies `manifest.json`, `feature_schema.json`, `scaler.json`, `calibration.json`, and `weights_compact.json` SHA-256 checksums.
- [x] Golden input/output parity (`maxAbsDiff <= 1e-4`) verified across `BTCUSDT`, `ETHUSDT`, `SOLUSDT`, and `DOGEUSDT`.
- [x] All 3 horizons (`15m`, `1h`, `4h`) pass `ForecastCalibrationEngine.checkHeadConsistency`.
- [x] Public Binance USDⓈ-M Futures klines (`15m`, `1h`, `4h`) and bookTicker fetched from real REST endpoints without gaps.

## Stage 1 -> Stage 2: `SHADOW_READY` to `PAPER_READY` (Testnet Execution)
- [ ] At least `200` out-of-sample walk-forward validation observations per horizon (`15m`, `1h`, `4h`) collected from real Binance USDⓈ-M historical klines (`qualification_status == "QUALIFIED"`).
- [ ] At least `50` real-time shadow forecast evaluations resolved on-device with 15m Brier score `<= 0.23` and ECE `<= 0.06`.
- [ ] Binance Testnet API Key & Secret verified via 6-step diagnostic (`verifyAuthenticatedAccount`).

## Stage 2 -> Stage 3: `PAPER_READY` to `LIVE_ELIGIBLE` (Micro-Capital Mainnet)
- [ ] Minimum `30` closed Testnet/Paper trades with net positive expectancy after fees and slippage, Profit Factor `>= 1.25`, and Max Drawdown `<= 6.0%`.
- [ ] Zero unresolved `UNKNOWN` order states and 100% protective stop (`STOP_MARKET` / `/fapi/v1/algoOrder`) verification rate.
- [ ] Mainnet API Key verified with **Withdraw permission disabled**, Isolated Margin (`<= 5x`), and explicit operator disengagement of `safetyDebugLockActive`.
