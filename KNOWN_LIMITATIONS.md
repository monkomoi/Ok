# Known Limitations & Operational Risks

1. **Bundled Model Fixture Qualification (`INSUFFICIENT_SAMPLE_SHADOW_ONLY`)**:
   - The bundled `forecast_bundle/` weights are exported from a deterministic multi-regime fixture (`N = 23` validation samples per horizon) so that golden parity and offline unit tests are 100% reproducible.
   - Because `N < 200`, `calibration.json` tags all horizons as `INSUFFICIENT_SAMPLE_SHADOW_ONLY`, and `ForecastQualityGate` restricts the model to `SHADOW` observation mode (blocking ML-gated trade authorization in `GATED_ENFORCEMENT` / `ADAPTIVE_SIZING` until retrained on `>= 200` real walk-forward validation bars per horizon via `ml/forecasting/src/fetch_klines.py`).
2. **Small-Capital `minNotional` Constraints on High-Price Contracts**:
   - On accounts with `$50–$120 USDT` equity and `0.50%` risk per trade (`R = $0.25–$0.60`), `BTCUSDT` (`minNotional = $100 USDT`) cannot be sized with a normal `0.8%–1.5%` intraday stop without exceeding `R`. By design, `QuantMathEngine` rejects `BTCUSDT` entries under these conditions and routes eligible small-capital sizing to lower-`minNotional` perpetuals (`DOGEUSDT`, `SOLUSDT`, `ETHUSDT`).
3. **Stop-Market Slippage in Flash Crashes**:
   - Exchange `STOP_MARKET` / conditional Algo Orders guarantee trigger submission but execute as market orders once triggered; during extreme liquidity gaps, realized loss can exceed the theoretical stop distance `d`.
