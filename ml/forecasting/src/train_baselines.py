#!/usr/bin/env python3
"""
Statistical & Linear Baselines for Astra Trader V5.1 Forecasting Engine (Section 4.1):
1. Naive / Persistence Baseline
2. EMA-ATR Rule Baseline
3. L2-Regularized Multinomial Logistic + Linear Quantile Baseline
"""
import math
from typing import Dict, List, Any, Tuple


def softmax_3(logits: List[float], temperature: float = 1.0) -> List[float]:
    t = max(temperature, 1e-4)
    scaled = [x / t for x in logits]
    m = max(scaled)
    exps = [math.exp(x - m) for x in scaled]
    s = sum(exps)
    return [e / s for e in exps]


def predict_naive_persistence(scaled_seq: List[List[float]], horizon: str) -> Dict[str, Any]:
    """
    Naive persistence baseline: extrapolates recent return sign and EWMA volatility.
    """
    last = scaled_seq[-1]
    ret_z = 0.5 * last[0] + 0.3 * last[2] + 0.2 * last[4]
    vol_z = max(0.1, abs(last[18]) * 0.004 + 0.0035)
    h_mult = 1.0 if horizon == "15m" else (2.0 if horizon == "1h" else 4.0)
    exp_ret = ret_z * 0.0018 * h_mult
    logits = [-ret_z * 0.7, 0.25, ret_z * 0.7]
    probs = softmax_3(logits)
    sig = vol_z * math.sqrt(h_mult)
    return {
        "logits": [round(x, 6) for x in logits],
        "probs": [round(x, 6) for x in probs],
        "expected_return": round(exp_ret, 6),
        "q10": round(exp_ret - 1.28 * sig, 6),
        "q50": round(exp_ret, 6),
        "q90": round(exp_ret + 1.28 * sig, 6),
        "sigma": round(sig, 6),
    }


def predict_ema_atr_baseline(scaled_seq: List[List[float]], horizon: str) -> Dict[str, Any]:
    """
    EMA-ATR trend/momentum baseline using EMA20/50 distance, EMA slope, RSI-14, and RVOL.
    """
    last = scaled_seq[-1]
    ema_signal = 0.40 * last[10] + 0.25 * last[12] + 0.20 * last[14] + 0.15 * last[19]
    h_mult = 1.0 if horizon == "15m" else (1.8 if horizon == "1h" else 3.2)
    exp_ret = ema_signal * 0.0022 * h_mult
    logits = [-ema_signal * 0.85, 0.30 - 0.10 * abs(ema_signal), ema_signal * 0.85]
    probs = softmax_3(logits)
    sig = max(0.0025, (0.0040 + 0.0012 * max(0.0, last[17])) * math.sqrt(h_mult))
    return {
        "logits": [round(x, 6) for x in logits],
        "probs": [round(x, 6) for x in probs],
        "expected_return": round(exp_ret, 6),
        "q10": round(exp_ret - 1.28 * sig, 6),
        "q50": round(exp_ret, 6),
        "q90": round(exp_ret + 1.28 * sig, 6),
        "sigma": round(sig, 6),
    }


def summary_features_from_sequence(scaled_seq: List[List[float]]) -> List[float]:
    """
    Pools `[L, F]` into `[2 * F]`: latest step `x[T]` concatenated with exponential moving mean over lookback.
    """
    f_dim = len(scaled_seq[0])
    last_step = list(scaled_seq[-1])
    ema_step = list(scaled_seq[0])
    alpha = 0.12
    for row in scaled_seq[1:]:
        for j in range(f_dim):
            ema_step[j] = alpha * row[j] + (1.0 - alpha) * ema_step[j]
    return last_step + ema_step


def train_logistic_baseline(
    train_sequences: List[List[List[float]]],
    train_labels: List[Dict[str, Dict[str, Any]]],
    horizons: Tuple[str, ...] = ("15m", "1h", "4h"),
    epochs: int = 28,
    lr: float = 0.035,
    l2_reg: float = 0.008
) -> Dict[str, Any]:
    """
    Trains an L2-regularized multinomial logistic + ridge return/volatility head per horizon.
    """
    dim = len(train_sequences[0][0]) * 2
    pooled_X = [summary_features_from_sequence(seq) for seq in train_sequences]
    models: Dict[str, Any] = {}

    for h in horizons:
        # 3 classes: 0=DOWN, 1=FLAT, 2=UP
        W = [[0.0 for _ in range(dim)] for _ in range(3)]
        b = [0.0, 0.15, 0.0]
        w_ret = [0.0 for _ in range(dim)]
        b_ret = 0.0

        n = float(len(pooled_X))
        for ep in range(epochs):
            step_lr = lr / (1.0 + 0.06 * ep)
            grad_W = [[0.0 for _ in range(dim)] for _ in range(3)]
            grad_b = [0.0, 0.0, 0.0]
            grad_w_ret = [0.0 for _ in range(dim)]
            grad_b_ret = 0.0

            for x, lbl_map in zip(pooled_X, train_labels):
                lbl = lbl_map[h]
                y_cls = int(lbl["y_dir"])
                y_ret = float(lbl["forward_return"])

                logits = [
                    sum(W[c][j] * x[j] for j in range(dim)) + b[c]
                    for c in range(3)
                ]
                probs = softmax_3(logits)
                for c in range(3):
                     target = 1.0 if c == y_cls else 0.0
                     err = probs[c] - target
                     grad_b[c] += err / n
                     for j in range(dim):
                         grad_W[c][j] += (err * x[j]) / n

                pred_ret = sum(w_ret[j] * x[j] for j in range(dim)) + b_ret
                err_ret = pred_ret - y_ret
                grad_b_ret += err_ret / n
                for j in range(dim):
                    grad_w_ret[j] += (err_ret * x[j]) / n

            for c in range(3):
                b[c] -= step_lr * grad_b[c]
                for j in range(dim):
                    W[c][j] -= step_lr * (grad_W[c][j] + l2_reg * W[c][j])

            b_ret -= step_lr * 0.5 * grad_b_ret
            for j in range(dim):
                w_ret[j] -= step_lr * 0.5 * (grad_w_ret[j] + l2_reg * w_ret[j])

        models[h] = {
            "W": [[round(v, 6) for v in row] for row in W],
            "b": [round(v, 6) for v in b],
            "w_ret": [round(v, 6) for v in w_ret],
            "b_ret": round(b_ret, 6),
        }

    return models


def predict_logistic_baseline(
    model: Dict[str, Any],
    scaled_seq: List[List[float]],
    horizon: str
) -> Dict[str, Any]:
    hm = model[horizon]
    x = summary_features_from_sequence(scaled_seq)
    dim = len(x)
    logits = [
        sum(hm["W"][c][j] * x[j] for j in range(dim)) + hm["b"][c]
        for c in range(3)
    ]
    probs = softmax_3(logits)
    exp_ret = sum(hm["w_ret"][j] * x[j] for j in range(dim)) + hm["b_ret"]
    h_mult = 1.0 if horizon == "15m" else (1.9 if horizon == "1h" else 3.5)
    base_vol = max(0.0025, (0.0038 + 0.0010 * abs(scaled_seq[-1][18])) * h_mult)
    return {
        "logits": [round(v, 6) for v in logits],
        "probs": [round(v, 6) for v in probs],
        "expected_return": round(exp_ret, 6),
        "q10": round(exp_ret - 1.2815 * base_vol, 6),
        "q50": round(exp_ret, 6),
        "q90": round(exp_ret + 1.2815 * base_vol, 6),
        "sigma": round(base_vol, 6),
    }
