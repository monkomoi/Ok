#!/usr/bin/env python3
"""
Chronological Walk-Forward & Cost-Aware Backtest Evaluator for Astra Trader V5.1.
Includes:
  - `simulate_single_trade_event`: deterministic single-candle SL/TP barrier resolver
    with conservative same-candle SL priority (`SL_AND_TP_SAME_CANDLE_CONSERVATIVE_SL`)
  - Strict equity compounding ONLY when `trade_event is not None and trade_event["traded"]`
  - Sample-size warnings when trade count or fold size is below statistical qualification thresholds
"""
import math
from typing import Dict, List, Any, Optional
from calibrate import compute_brier_and_ece


def simulate_single_trade_event(
    direction: str,
    entry_price: float,
    stop_price: float,
    target_price: float,
    future_bars: List[Dict[str, Any]],
    round_trip_cost_pct: float = 0.0018
) -> Dict[str, Any]:
    if direction not in ("LONG", "SHORT") or not future_bars or entry_price <= 0.0:
        return {
            "traded": False,
            "outcome_reason": "NO_TRADE",
            "gross_return_pct": 0.0,
            "net_return_pct": 0.0,
        }

    for idx, bar in enumerate(future_bars):
        high_p = float(bar["high"])
        low_p = float(bar["low"])
        if direction == "LONG":
            hit_sl = low_p <= stop_price
            hit_tp = high_p >= target_price
            if hit_sl and hit_tp:
                gross = (stop_price - entry_price) / entry_price
                return {
                    "traded": True,
                    "exit_bar_offset": idx + 1,
                    "outcome_reason": "SL_AND_TP_SAME_CANDLE_CONSERVATIVE_SL",
                    "gross_return_pct": round(gross * 100.0, 5),
                    "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
                }
            if hit_sl:
                gross = (stop_price - entry_price) / entry_price
                return {
                    "traded": True,
                    "exit_bar_offset": idx + 1,
                    "outcome_reason": "STOP_LOSS_HIT",
                    "gross_return_pct": round(gross * 100.0, 5),
                    "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
                }
            if hit_tp:
                gross = (target_price - entry_price) / entry_price
                return {
                    "traded": True,
                    "exit_bar_offset": idx + 1,
                    "outcome_reason": "TAKE_PROFIT_HIT",
                    "gross_return_pct": round(gross * 100.0, 5),
                    "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
                }
        else:
            hit_sl = high_p >= stop_price
            hit_tp = low_p <= target_price
            if hit_sl and hit_tp:
                gross = (entry_price - stop_price) / entry_price
                return {
                    "traded": True,
                    "exit_bar_offset": idx + 1,
                    "outcome_reason": "SL_AND_TP_SAME_CANDLE_CONSERVATIVE_SL",
                    "gross_return_pct": round(gross * 100.0, 5),
                    "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
                }
            if hit_sl:
                gross = (entry_price - stop_price) / entry_price
                return {
                    "traded": True,
                    "exit_bar_offset": idx + 1,
                    "outcome_reason": "STOP_LOSS_HIT",
                    "gross_return_pct": round(gross * 100.0, 5),
                    "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
                }
            if hit_tp:
                gross = (entry_price - target_price) / entry_price
                return {
                    "traded": True,
                    "exit_bar_offset": idx + 1,
                    "outcome_reason": "TAKE_PROFIT_HIT",
                    "gross_return_pct": round(gross * 100.0, 5),
                    "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
                }

    last_close = float(future_bars[-1]["close"])
    gross = (
        (last_close - entry_price) / entry_price
        if direction == "LONG"
        else (entry_price - last_close) / entry_price
    )
    return {
        "traded": True,
        "exit_bar_offset": len(future_bars),
        "outcome_reason": "HORIZON_EXPIRY_CLOSE",
        "gross_return_pct": round(gross * 100.0, 5),
        "net_return_pct": round((gross - round_trip_cost_pct) * 100.0, 5),
    }


def evaluate_predictions_chronological(
    records: List[Dict[str, Any]],
    round_trip_cost_pct: float = 0.0018
) -> Dict[str, Any]:
    if not records:
        return {
            "sample_count": 0,
            "sample_qualification": "INSUFFICIENT_SAMPLE",
            "brier_score": 0.25,
            "log_loss": 1.0986,
            "ece": 0.15,
            "directional_accuracy": 0.0,
            "mae_return_pct": 0.0,
            "trade_count": 0,
            "win_rate": 0.0,
            "net_expectancy_pct": 0.0,
            "max_drawdown_pct": 0.0,
            "final_equity_multiplier": 1.0,
        }

    probs_list = [r["probs"] for r in records]
    y_true = [int(r["y_dir"]) for r in records]
    cal_metrics = compute_brier_and_ece(probs_list, y_true)

    correct = 0
    abs_err_sum = 0.0
    equity = 1.0
    peak_equity = 1.0
    max_dd = 0.0
    trade_returns: List[float] = []

    for r in records:
        probs = r["probs"]
        pred_cls = max(range(3), key=lambda k: probs[k])
        if pred_cls == int(r["y_dir"]):
            correct += 1
        pred_ret_pct = float(r.get("expected_return_pct", 0.0))
        real_ret_pct = float(r.get("realized_return_pct", 0.0))
        abs_err_sum += abs(pred_ret_pct - real_ret_pct)

        trade_event: Optional[Dict[str, Any]] = r.get("trade_event")
        if trade_event is not None and bool(trade_event.get("traded", False)):
            net_ret_frac = float(trade_event["net_return_pct"]) / 100.0
            trade_returns.append(net_ret_frac * 100.0)
            equity *= max(0.05, 1.0 + net_ret_frac)
            if equity > peak_equity:
                peak_equity = equity
            dd = (peak_equity - equity) / max(peak_equity, 1e-9)
            if dd > max_dd:
                max_dd = dd

    n = len(records)
    t_cnt = len(trade_returns)
    wins = sum(1 for tr in trade_returns if tr > 0.0)
    win_rate = (float(wins) / float(t_cnt)) if t_cnt > 0 else 0.0
    net_exp = (sum(trade_returns) / float(t_cnt)) if t_cnt > 0 else 0.0

    return {
        "sample_count": n,
        "sample_qualification": "QUALIFIED" if (n >= 200 and t_cnt >= 30) else "INSUFFICIENT_SAMPLE",
        "brier_score": cal_metrics["brier_score"],
        "log_loss": cal_metrics["log_loss"],
        "ece": cal_metrics["ece"],
        "directional_accuracy": round(float(correct) / float(n), 4),
        "mae_return_pct": round(abs_err_sum / float(n), 4),
        "trade_count": t_cnt,
        "win_rate": round(win_rate, 4),
        "net_expectancy_pct": round(net_exp, 5),
        "max_drawdown_pct": round(max_dd * 100.0, 4),
        "final_equity_multiplier": round(equity, 5),
    }
