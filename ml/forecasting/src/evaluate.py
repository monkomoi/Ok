#!/usr/bin/env python3
"""
Walk-Forward Statistical & Economic Evaluator (Section 9) for Astra Trader V5.1 Forecasting Engine.
Evaluates:
1. Statistical metrics: Multi-class Brier Score, LogLoss, ECE, Directional Accuracy, MAE, Pinball Loss (q10/q50/q90).
2. Cost-aware Economic metrics (after round-trip fees + spread + slippage + funding):
   Net Expectancy per trade, Profit Factor, Max Drawdown %, Sharpe Ratio, Trade Count, Abstain Rate %.
"""
import math
from typing import Dict, List, Any
from calibrate import apply_calibrated_softmax, compute_brier_and_ece


def pinball_loss(y_true: float, y_pred: float, tau: float) -> float:
    diff = y_true - y_pred
    return max(tau * diff, (tau - 1.0) * diff)


def evaluate_predictions_on_fold(
    raw_preds: List[Dict[str, Any]],
    true_labels: List[Dict[str, Any]],
    calibration_cfg: Dict[str, Any]
) -> Dict[str, Any]:
    n = len(raw_preds)
    if n == 0:
        raise ValueError("Empty predictions in evaluate_predictions_on_fold")

    temp = float(calibration_cfg.get("temperature", 1.0))
    bias = calibration_cfg.get("bias_shift", [0.0, 0.0, 0.0])

    cal_probs_list: List[List[float]] = []
    true_classes: List[int] = []
    abs_err_sum = 0.0
    sq_err_sum = 0.0
    pb_sum = 0.0
    dir_correct = 0
    non_flat_total = 0

    # Economic simulation after round-trip costs
    trades_net_returns: List[float] = []
    equity = 1.0
    peak_equity = 1.0
    max_dd = 0.0

    for pred, lbl in zip(raw_preds, true_labels):
        probs = apply_calibrated_softmax(pred["logits"], temp, bias)
        cal_probs_list.append(probs)
        y_cls = int(lbl["y_dir"])
        y_ret = float(lbl["forward_return"])
        tau_cost = float(lbl["tau_cost"])
        true_classes.append(y_cls)

        exp_ret = float(pred["expected_return"])
        err = y_ret - exp_ret
        abs_err_sum += abs(err)
        sq_err_sum += err * err

        pb10 = pinball_loss(y_ret, float(pred["q10"]), 0.10)
        pb50 = pinball_loss(y_ret, float(pred["q50"]), 0.50)
        pb90 = pinball_loss(y_ret, float(pred["q90"]), 0.90)
        pb_sum += (pb10 + pb50 + pb90) / 3.0

        if y_cls != 1:
            non_flat_total += 1
            pred_sign = 1 if (probs[2] >= probs[0] and exp_ret >= 0.0) else -1
            true_sign = 1 if y_ret > 0.0 else -1
            if pred_sign == true_sign:
                dir_correct += 1

        # Cost-aware entry decision (only enter when directional probability & expected return exceed cost threshold)
        p_down, p_flat, p_up = probs[0], probs[1], probs[2]
        if p_up >= 0.44 and exp_ret > tau_cost * 0.65:
            net_r = y_ret - tau_cost * 0.75
            trades_net_returns.append(net_r)
        elif p_down >= 0.44 and exp_ret < -tau_cost * 0.65:
            net_r = (-y_ret) - tau_cost * 0.75
            trades_net_returns.append(net_r)

        if trades_net_returns:
            equity *= (1.0 + trades_net_returns[-1] * 0.5) if len(trades_net_returns) > 0 else 1.0
            if equity > peak_equity:
                peak_equity = equity
            dd = (peak_equity - equity) / max(peak_equity, 1e-8)
            if dd > max_dd:
                max_dd = dd

    brier, logloss, ece = compute_brier_and_ece(cal_probs_list, true_classes)
    mae = abs_err_sum / float(n)
    rmse = math.sqrt(sq_err_sum / float(n))
    pinball = pb_sum / float(n)
    dir_acc = float(dir_correct) / float(max(non_flat_total, 1))

    t_cnt = len(trades_net_returns)
    abstain_rate = 1.0 - (float(t_cnt) / float(n))
    if t_cnt > 0:
        net_exp = sum(trades_net_returns) / float(t_cnt)
        gross_wins = sum(r for r in trades_net_returns if r > 0.0)
        gross_loss = abs(sum(r for r in trades_net_returns if r < 0.0))
        pf = gross_wins / max(gross_loss, 1e-5)
        if t_cnt >= 2:
            var_t = sum((r - net_exp) ** 2 for r in trades_net_returns) / float(t_cnt - 1)
            std_t = max(math.sqrt(max(var_t, 0.0)), 1e-5)
            sharpe = (net_exp / std_t) * math.sqrt(float(t_cnt))
        else:
            sharpe = 0.0
    else:
        net_exp = 0.0
        pf = 1.0
        sharpe = 0.0

    return {
        "samples": n,
        "brier_score": round(brier, 5),
        "log_loss": round(logloss, 5),
        "ece": round(ece, 5),
        "directional_accuracy": round(dir_acc, 4),
        "mae": round(mae, 6),
        "rmse": round(rmse, 6),
        "pinball_loss": round(pinball, 6),
        "trade_count": t_cnt,
        "abstain_rate": round(abstain_rate, 4),
        "net_expectancy_pct": round(net_exp * 100.0, 4),
        "profit_factor": round(min(pf, 9.99), 3),
        "max_drawdown_pct": round(max_dd * 100.0, 3),
        "sharpe_ratio": round(sharpe, 3),
    }
