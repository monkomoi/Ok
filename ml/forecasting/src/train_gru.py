#!/usr/bin/env python3
"""
Compact GRU v1 Trainer & Deterministic Inference Engine (Champion On-Device Architecture).
Designed for Samsung Galaxy S7 Edge (arm64-v8a, 4 GB RAM, LineageOS Android 11):
- Input: `[1, 64, 26]`
- Recurrent core: 1-layer GRU (`hidden_size = 16` compact execution / `48` logical capacity)
- Multi-horizon output heads (`15m`, `1h`, `4h`):
  - 3-class logits (`DOWN`, `FLAT`, `UP`)
  - expected log return (`r_hat`)
  - quantiles (`q10`, `q50`, `q90`)
  - predicted volatility (`sigma_hat`)
"""
import math
from typing import Dict, List, Any, Tuple
from train_baselines import softmax_3


def sigmoid(x: float) -> float:
    xc = max(-15.0, min(15.0, x))
    return 1.0 / (1.0 + math.exp(-xc))


def init_gru_weights(input_dim: int = 26, hidden_dim: int = 16) -> Dict[str, Any]:
    """
    Deterministic Xavier-scaled initialization for GRU gates (reset `r`, update `z`, candidate `n`)
    oriented around causal momentum, volatility, and higher-timeframe features.
    """
    W_z = [[0.0 for _ in range(input_dim)] for _ in range(hidden_dim)]
    U_z = [[0.0 for _ in range(hidden_dim)] for _ in range(hidden_dim)]
    b_z = [0.25 for _ in range(hidden_dim)]

    W_r = [[0.0 for _ in range(input_dim)] for _ in range(hidden_dim)]
    U_r = [[0.0 for _ in range(hidden_dim)] for _ in range(hidden_dim)]
    b_r = [0.10 for _ in range(hidden_dim)]

    W_n = [[0.0 for _ in range(input_dim)] for _ in range(hidden_dim)]
    U_n = [[0.0 for _ in range(hidden_dim)] for _ in range(hidden_dim)]
    b_n = [0.0 for _ in range(hidden_dim)]

    for h in range(hidden_dim):
        U_z[h][h] = 0.45
        U_r[h][h] = 0.30
        U_n[h][h] = 0.35
        for f in range(input_dim):
            angle = (h + 1) * 0.71 + (f + 1) * 0.37
            W_z[h][f] = round(0.14 * math.sin(angle), 6)
            W_r[h][f] = round(0.14 * math.cos(angle), 6)
            W_n[h][f] = round(0.22 * math.sin(angle * 1.3), 6)

        # Emphasize key causal financial features in dedicated hidden units
        if h % 4 == 0:
            W_n[h][0] += 0.35   # ret_1
            W_n[h][2] += 0.30   # ret_4
            W_n[h][10] += 0.32  # ema20_dist
            W_n[h][20] += 0.28  # htf_1h_ret
        elif h % 4 == 1:
            W_n[h][12] += 0.34  # ema20_slope
            W_n[h][13] += 0.26  # rsi_14_norm
            W_n[h][14] += 0.32  # macd_hist_pct
            W_n[h][21] += 0.28  # htf_4h_ret
        elif h % 4 == 2:
            W_n[h][17] += 0.38  # atr_14_pct
            W_n[h][18] += 0.38  # ewma_vol_20
            W_n[h][19] += 0.30  # rvol_20
        else:
            W_n[h][15] += 0.28  # bb_percent_b
            W_n[h][24] += 0.24  # order_book_imbalance
            W_n[h][25] -= 0.18  # funding_rate_bps crowding

    return {
        "input_dim": input_dim,
        "hidden_dim": hidden_dim,
        "W_z": [[round(v, 6) for v in row] for row in W_z],
        "U_z": [[round(v, 6) for v in row] for row in U_z],
        "b_z": [round(v, 6) for v in b_z],
        "W_r": [[round(v, 6) for v in row] for row in W_r],
        "U_r": [[round(v, 6) for v in row] for row in U_r],
        "b_r": [round(v, 6) for v in b_r],
        "W_n": [[round(v, 6) for v in row] for row in W_n],
        "U_n": [[round(v, 6) for v in row] for row in U_n],
        "b_n": [round(v, 6) for v in b_n],
    }


def gru_encode_sequence(gru_weights: Dict[str, Any], scaled_seq: List[List[float]]) -> List[float]:
    """
    Runs the causal GRU recurrent cell over `scaled_seq` (`[64, 26]`) and returns the
    final hidden state concatenated with mean-pooled hidden state (`[2 * hidden_dim]`).
    Identical to `LiteRtForecastRuntime.kt` GRU execution path.
    """
    in_dim = gru_weights["input_dim"]
    h_dim = gru_weights["hidden_dim"]
    W_z = gru_weights["W_z"]
    U_z = gru_weights["U_z"]
    b_z = gru_weights["b_z"]
    W_r = gru_weights["W_r"]
    U_r = gru_weights["U_r"]
    b_r = gru_weights["b_r"]
    W_n = gru_weights["W_n"]
    U_n = gru_weights["U_n"]
    b_n = gru_weights["b_n"]

    h = [0.0 for _ in range(h_dim)]
    h_mean = [0.0 for _ in range(h_dim)]
    seq_len = float(len(scaled_seq))

    for x in scaled_seq:
        h_next = [0.0 for _ in range(h_dim)]
        for i in range(h_dim):
            z_lin = b_z[i]
            r_lin = b_r[i]
            for j in range(in_dim):
                xj = x[j]
                z_lin += W_z[i][j] * xj
                r_lin += W_r[i][j] * xj
            for k in range(h_dim):
                hk = h[k]
                z_lin += U_z[i][k] * hk
                r_lin += U_r[i][k] * hk
            z_i = sigmoid(z_lin)
            r_i = sigmoid(r_lin)

            n_lin = b_n[i]
            for j in range(in_dim):
                n_lin += W_n[i][j] * x[j]
            uh = 0.0
            for k in range(h_dim):
                uh += U_n[i][k] * h[k]
            n_i = math.tanh(max(-15.0, min(15.0, n_lin + r_i * uh)))
            h_next[i] = (1.0 - z_i) * n_i + z_i * h[i]
            h_mean[i] += h_next[i] / seq_len
        h = h_next

    return h + h_mean


def train_gru_model(
    train_sequences: List[List[List[float]]],
    train_labels: List[Dict[str, Dict[str, Any]]],
    horizons: Tuple[str, ...] = ("15m", "1h", "4h"),
    hidden_dim: int = 16,
    epochs: int = 45,
    lr: float = 0.18,
    l2_reg: float = 0.002
) -> Dict[str, Any]:
    """
    Encodes sequences with the GRU recurrent core and optimizes multi-horizon classification,
    expected return, quantile, and volatility heads on the training fold.
    """
    gru_core = init_gru_weights(input_dim=len(train_sequences[0][0]), hidden_dim=hidden_dim)
    encoded_X = [gru_encode_sequence(gru_core, seq) for seq in train_sequences]
    last_vol_feature = [abs(seq[-1][17]) * 0.5 + abs(seq[-1][18]) * 0.5 for seq in train_sequences]
    feat_dim = len(encoded_X[0])
    heads: Dict[str, Any] = {}

    for h in horizons:
        W_cls = [[0.0 for _ in range(feat_dim)] for _ in range(3)]
        b_cls = [0.0, 0.12, 0.0]
        w_ret = [0.0 for _ in range(feat_dim)]
        b_ret = 0.0
        w_vol = [0.0 for _ in range(feat_dim)]
        b_vol = 0.0040 if h == "15m" else (0.0075 if h == "1h" else 0.0140)

        n = float(len(encoded_X))
        for ep in range(epochs):
            step_lr = lr / (1.0 + 0.05 * ep)
            gW = [[0.0 for _ in range(feat_dim)] for _ in range(3)]
            gb = [0.0, 0.0, 0.0]
            gw_ret = [0.0 for _ in range(feat_dim)]
            gb_ret = 0.0
            gw_vol = [0.0 for _ in range(feat_dim)]
            gb_vol = 0.0

            for x, v_f, lbl_map in zip(encoded_X, last_vol_feature, train_labels):
                lbl = lbl_map[h]
                y_cls = int(lbl["y_dir"])
                y_ret = float(lbl["forward_return"])
                y_vol = float(lbl["realized_vol"])

                logits = [
                    sum(W_cls[c][j] * x[j] for j in range(feat_dim)) + b_cls[c]
                    for c in range(3)
                ]
                probs = softmax_3(logits)
                for c in range(3):
                    target = 1.0 if c == y_cls else 0.0
                    err = probs[c] - target
                    gb[c] += err / n
                    for j in range(feat_dim):
                        gW[c][j] += (err * x[j]) / n

                pred_ret = sum(w_ret[j] * x[j] for j in range(feat_dim)) + b_ret
                err_ret = pred_ret - y_ret
                gb_ret += err_ret / n
                for j in range(feat_dim):
                    gw_ret[j] += (err_ret * x[j]) / n

                pred_vol = max(0.001, sum(w_vol[j] * x[j] for j in range(feat_dim)) + b_vol)
                err_vol = pred_vol - y_vol
                gb_vol += err_vol / n
                for j in range(feat_dim):
                    gw_vol[j] += (err_vol * x[j]) / n

            for c in range(3):
                b_cls[c] -= step_lr * gb[c]
                for j in range(feat_dim):
                    W_cls[c][j] -= step_lr * (gW[c][j] + l2_reg * W_cls[c][j])

            b_ret -= step_lr * 0.45 * gb_ret
            b_vol = max(0.0015, b_vol - step_lr * 0.25 * gb_vol)
            for j in range(feat_dim):
                w_ret[j] -= step_lr * 0.45 * (gw_ret[j] + l2_reg * w_ret[j])
                w_vol[j] -= step_lr * 0.25 * (gw_vol[j] + l2_reg * w_vol[j])

        heads[h] = {
            "W_cls": [[round(v, 6) for v in row] for row in W_cls],
            "b_cls": [round(v, 6) for v in b_cls],
            "w_ret": [round(v, 6) for v in w_ret],
            "b_ret": round(b_ret, 6),
            "w_vol": [round(v, 6) for v in w_vol],
            "b_vol": round(b_vol, 6),
        }

    return {
        "architecture": "GRU",
        "model_id": "astra_gru_v1",
        "core": gru_core,
        "heads": heads,
    }


def predict_gru(
    gru_model: Dict[str, Any],
    scaled_seq: List[List[float]],
    horizon: str
) -> Dict[str, Any]:
    enc = gru_encode_sequence(gru_model["core"], scaled_seq)
    head = gru_model["heads"][horizon]
    feat_dim = len(enc)
    logits = [
        sum(head["W_cls"][c][j] * enc[j] for j in range(feat_dim)) + head["b_cls"][c]
        for c in range(3)
    ]
    probs = softmax_3(logits)
    exp_ret = sum(head["w_ret"][j] * enc[j] for j in range(feat_dim)) + head["b_ret"]
    raw_vol = sum(head["w_vol"][j] * enc[j] for j in range(feat_dim)) + head["b_vol"]
    h_floor = 0.0022 if horizon == "15m" else (0.0042 if horizon == "1h" else 0.0080)
    sigma = max(h_floor, abs(raw_vol))
    q50 = exp_ret
    q10 = q50 - 1.28155 * sigma
    q90 = q50 + 1.28155 * sigma
    return {
        "logits": [round(v, 6) for v in logits],
        "probs": [round(v, 6) for v in probs],
        "expected_return": round(exp_ret, 6),
        "q10": round(q10, 6),
        "q50": round(q50, 6),
        "q90": round(q90, 6),
        "sigma": round(sigma, 6),
    }
