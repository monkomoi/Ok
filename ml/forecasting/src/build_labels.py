#!/usr/bin/env python3
"""
Multi-horizon cost-aware label builder for Astra Trader V5.1 Forecasting Engine.
Horizons:
  - 15m (H = 1 bar on 15m grid)
  - 1h  (H = 4 bars on 15m grid)
  - 4h  (H = 16 bars on 15m grid)
Labels at index `t` use strictly future bars `(t+1 .. t+H)` and are NEVER exposed as inputs.
"""
import math
from typing import Dict, List, Any

HORIZON_BARS = {
    "15m": 1,
    "1h": 4,
    "4h": 16,
}


def compute_cost_threshold(
    horizon: str,
    taker_fee_rate: float = 0.0005,
    spread_est: float = 0.0004,
    slippage_est: float = 0.0004,
    funding_rate_per_8h: float = 0.0001,
    safety_margin: float = 0.0004
) -> float:
    """
    Section 6.2:
    tau_cost = 2 * fee_rate + spread_est + slippage_est + funding_est + safety_margin
    Scaled slightly by horizon to reflect holding period uncertainty.
    """
    bars = HORIZON_BARS[horizon]
    funding_est = abs(funding_rate_per_8h) * (float(bars) / 32.0)
    horizon_scale = 1.0 if horizon == "15m" else (1.35 if horizon == "1h" else 1.85)
    base_cost = 2.0 * taker_fee_rate + spread_est + slippage_est + funding_est
    return round(base_cost + safety_margin * horizon_scale, 6)


def build_labels_for_index(
    candles_15m: List[Dict[str, Any]],
    t: int,
    taker_fee_rate: float = 0.0005,
    spread_est: float = 0.0004,
    slippage_est: float = 0.0004,
) -> Dict[str, Dict[str, Any]]:
    """
    Builds ground-truth labels for index `t` across horizons `15m`, `1h`, `4h`.
    Requires `t + 16 < len(candles_15m)`.
    Class encoding:
      0 = DOWN (r_{t->t+H} < -tau_cost)
      1 = FLAT (|r_{t->t+H}| <= tau_cost)
      2 = UP   (r_{t->t+H} > +tau_cost)
    """
    max_h = max(HORIZON_BARS.values())
    if t + max_h >= len(candles_15m):
        raise ValueError(f"Index {t} lacks future bars for max horizon {max_h}")

    c_t = float(candles_15m[t]["close"])
    labels: Dict[str, Dict[str, Any]] = {}

    for h_name, h_bars in HORIZON_BARS.items():
        future_slice = candles_15m[t + 1:t + h_bars + 1]
        c_end = float(future_slice[-1]["close"])
        fwd_ret = math.log(max(c_end, 1e-8) / max(c_t, 1e-8))

        # Intrabar path quantiles & realized volatility across horizon
        step_rets = []
        prev_c = c_t
        for b in future_slice:
            bc = float(b["close"])
            step_rets.append(math.log(max(bc, 1e-8) / max(prev_c, 1e-8)))
            prev_c = bc

        max_high = max(float(b["high"]) for b in future_slice)
        min_low = min(float(b["low"]) for b in future_slice)
        mfe_ret = math.log(max(max_high, 1e-8) / max(c_t, 1e-8))
        mae_ret = math.log(max(min_low, 1e-8) / max(c_t, 1e-8))

        if len(step_rets) >= 2:
            mean_r = sum(step_rets) / float(len(step_rets))
            var_r = sum((r - mean_r) ** 2 for r in step_rets) / float(len(step_rets) - 1)
            realized_vol = math.sqrt(max(var_r, 0.0)) * math.sqrt(float(h_bars))
        else:
            realized_vol = max(abs(fwd_ret), (mfe_ret - mae_ret) * 0.5)

        tau_cost = compute_cost_threshold(
            horizon=h_name,
            taker_fee_rate=taker_fee_rate,
            spread_est=spread_est,
            slippage_est=slippage_est,
            funding_rate_per_8h=float(candles_15m[t].get("fundingRate", 0.0001)),
        )

        if fwd_ret > tau_cost:
            y_dir = 2  # UP
        elif fwd_ret < -tau_cost:
            y_dir = 0  # DOWN
        else:
            y_dir = 1  # FLAT

        labels[h_name] = {
            "y_dir": y_dir,
            "forward_return": round(fwd_ret, 6),
            "tau_cost": tau_cost,
            "mfe_return": round(mfe_ret, 6),
            "mae_return": round(mae_ret, 6),
            "realized_vol": round(max(realized_vol, 1e-5), 6),
        }

    return labels
