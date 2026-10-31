# Data Schema, Feature Contract & API Specification

## 1. 26-Feature Causal Sequence Schema (`feature_schema.json`)
- **Sequence Length (`L`)**: `64` bars (16 hours on `15m` primary timeframe)
- **Feature Count (`F`)**: `26`
- **Alignment Rule**: Strictly causal (`t <= current_bar_close`). Higher-timeframe returns (`1h`, `4h`) are only updated at closed UTC `1h` and `4h` boundaries.

| Index | Feature Name | Group | Causal Formula | Heavy-Tailed | Clip Range |
|---|---|---|---|---|---|
| 0 | `ret_1` | Returns | `ln(C_t / C_{t-1})` | No | `[-0.15, 0.15]` |
| 1 | `ret_2` | Returns | `ln(C_t / C_{t-2})` | No | `[-0.20, 0.20]` |
| 2 | `ret_4` | Returns | `ln(C_t / C_{t-4})` | No | `[-0.25, 0.25]` |
| 3 | `ret_8` | Returns | `ln(C_t / C_{t-8})` | No | `[-0.30, 0.30]` |
| 4 | `ret_16` | Returns | `ln(C_t / C_{t-16})` | No | `[-0.40, 0.40]` |
| 5 | `ret_1h` | Multi-TF | `ln(C_t / C_{t-4})` aligned | No | `[-0.25, 0.25]` |
| 6 | `ret_4h` | Multi-TF | `ln(C_t / C_{t-16})` aligned | No | `[-0.40, 0.40]` |
| 7 | `hl_spread` | Candle | `(H_t - L_t) / C_t` | Yes | `[0.0, 0.25]` |
| 8 | `co_body` | Candle | `(C_t - O_t) / O_t` | No | `[-0.20, 0.20]` |
| 9 | `upper_wick` | Candle | `(H_t - max(O_t, C_t)) / (H_t - L_t + 1e-8)` | No | `[0.0, 1.0]` |
| 10 | `lower_wick` | Candle | `(min(O_t, C_t) - L_t) / (H_t - L_t + 1e-8)` | No | `[0.0, 1.0]` |
| 11 | `rv_8` | Volatility | `std(ret_1, 8)` | Yes | `[0.0, 0.15]` |
| 12 | `rv_32` | Volatility | `std(ret_1, 32)` | Yes | `[0.0, 0.15]` |
| 13 | `atr_pct_14` | Volatility | `ATR_14 / C_t` | Yes | `[0.0, 0.20]` |
| 14 | `ema_dist_20` | Trend | `(C_t - EMA_20) / C_t` | No | `[-0.25, 0.25]` |
| 15 | `ema_dist_50` | Trend | `(C_t - EMA_50) / C_t` | No | `[-0.35, 0.35]` |
| 16 | `rsi_14_norm` | Momentum | `(RSI_14 - 50.0) / 50.0` | No | `[-1.0, 1.0]` |
| 17 | `adx_14_norm` | Regime | `ADX_14 / 100.0` | No | `[0.0, 1.0]` |
| 18 | `bb_zscore_20` | MeanRev | `(C_t - SMA_20) / (2 * STD_20 + 1e-8)` | No | `[-4.0, 4.0]` |
| 19 | `rvol_20` | Volume | `V_t / (SMA(V, 20) + 1e-8)` | Yes | `[0.0, 15.0]` |
| 20 | `taker_buy_imbalance` | OrderFlow | `(2 * V_taker_buy - V_t) / (V_t + 1e-8)` | No | `[-1.0, 1.0]` |
| 21 | `cvd_slope_8` | OrderFlow | `sum(delta_V, 8) / (sum(V, 8) + 1e-8)` | No | `[-1.0, 1.0]` |
| 22 | `spread_bps_norm` | Microstructure | `spread_bps / 10.0` | Yes | `[0.0, 10.0]` |
| 23 | `obi_top5` | Microstructure | `(BidVol_5 - AskVol_5) / (BidVol_5 + AskVol_5 + 1e-8)` | No | `[-1.0, 1.0]` |
| 24 | `hour_sin` | Seasonality | `sin(2 * pi * utc_day_fraction)` | No | `[-1.0, 1.0]` |
| 25 | `hour_cos` | Seasonality | `cos(2 * pi * utc_day_fraction)` | No | `[-1.0, 1.0]` |

---

## 2. Room SQLite Entities (`AstraDatabase` v3)
- `strategy_config`: Single-row configuration (`environmentMode`, `forecastOperatingMode`, `safetyDebugLockActive`, `maxLeverage`, `riskPerTradePct`, `dailyLossCapPct`, `killSwitchLevel`).
- `positions`: Exchange-verified open positions (`symbol`, `positionSide`, `positionAmt`, `entryPrice`, `stopLossPrice`, `takeProfitPrice`, `protectiveSlOrderId`).
- `trade_orders`: Durable order & fill ledger (`clientOrderId`, `orderId`, `symbol`, `side`, `type`, `status`, `realizedPnl`, `isTestFixture`).
- `forecast_evaluations`: Multi-horizon (`15m`, `60m`, `240m`) forecast tracking with unique index `(symbol, horizonMinutes, forecastTimestampMs)`, storing `pDown`, `pFlat`, `pUp`, `expectedReturnPct`, `q10Pct`, `q50Pct`, `q90Pct`, `headConsistent`, `resolved`, `realizedReturnPct`, `brierScore`, `isTestFixture`, and `dataTrustTag`.
- `signal_logs`, `risk_logs`, `system_logs`: Immutable audit trails for signal gating, kill-switch triggers, and runtime diagnostics.

---

## 3. Binance USDⓈ-M Futures API Contract (`BinanceV5RetrofitService`)
- **Mainnet Base URL**: `https://fapi.binance.com`
- **Testnet Base URL**: `https://testnet.binancefuture.com`
- **Public Endpoints**: `/fapi/v1/ping`, `/fapi/v1/time`, `/fapi/v1/exchangeInfo`, `/fapi/v1/klines`, `/fapi/v1/depth`, `/fapi/v1/premiumIndex`, `/fapi/v1/openInterest`, `/fapi/v1/ticker/bookTicker`
- **Signed Endpoints (`X-MBX-APIKEY` + `HMAC-SHA256`)**: `/fapi/v2/account`, `/fapi/v2/balance`, `/fapi/v2/positionRisk`, `/fapi/v1/positionSide/dual`, `/fapi/v1/leverage`, `/fapi/v1/marginType`, `/fapi/v1/order`, `/fapi/v1/allOpenOrders`, `/fapi/v1/algoOrder`, `/fapi/v1/openAlgoOrders`
