#!/usr/bin/env python3
"""
Compact LSTM v1 Challenger Trainer & Inference Engine (Section 4.1).
Evaluated against GRU v1 across walk-forward splits; only promoted over GRU if it beats
GRU on OOS Net Sharpe + Calibration without exceeding +20% latency/size on S7 Edge.
"""
import math
from typing import Dict, List, Any, Tuple
from train_baselines import softmax_3
from train_gru import sigmoid


def init_lstm_weights(input_dim: int = 26, hidden_dim: int = 16) -> Dict[str, Any]:
    W_i = [[round(0.14 * math.sin((h + 1) * 0.5 + (f + 1) * 0.3), 6) for f in range(input_dim)] for h in range(hidden_dim)]
    W_f = [[round(0.12 * math.cos((h + 1) * 0.4 + (f + 1) * 0.3), 6) for f in range(input_dim)] for h in range(hidden_dim)]
    W_o = [[round(0.14 * math.sin((h + 1) * 0.6 + (f + 1) * 0.2), 6) for f in range(input_dim)] for h in range(hidden_dim)]
    W_g = [[round(0.20 * math.cos((h + 1) * 0.7 + (f + 1) * 0.4), 6) for f in range(input_dim)] for h in range(hidden_dim)]
    return {
        "input_dim": input_dim,
        "hidden_dim": hidden_dim,
        "W_i": W_i,
        "W_f": W_f,
        "W_o": W_o,
        "W_g": W_g,
        "u_diag_i": [0.25 for _ in range(hidden_dim)],
        "u_diag_f": [0.55 for _ in range(hidden_dim)],
        "u_diag_o": [0.30 for _ in range(hidden_dim)],
        "u_diag_g": [0.35 for _ in range(hidden_dim)],
        "b_f": [0.65 for _ in range(hidden_dim)],
    }


def lstm_encode_sequence(lstm_weights: Dict[str, Any], scaled_seq: List[List[float]]) -> List[float]:
    in_dim = lstm_weights["input_dim"]
    h_dim = lstm_weights["hidden_dim"]
    h = [0.0 for _ in range(h_dim)]
    c = [0.0 for _ in range(h_dim)]
    h_mean = [0.0 for _ in range(h_dim)]
    seq_len = float(len(scaled_seq))

    for x in scaled_seq:
        for i in range(h_dim):
            i_gate = sigmoid(sum(lstm_weights["W_i"][i][j] * x[j] for j in range(in_dim)) + lstm_weights["u_diag_i"][i] * h[i])
            f_gate = sigmoid(sum(lstm_weights["W_f"][i][j] * x[j] for j in range(in_dim)) + lstm_weights["u_diag_f"][i] * h[i] + lstm_weights["b_f"][i])
            o_gate = sigmoid(sum(lstm_weights["W_o"][i][j] * x[j] for j in range(in_dim)) + lstm_weights["u_diag_o"][i] * h[i])
            g_cand = math.tanh(max(-15.0, min(15.0, sum(lstm_weights["W_g"][i][j] * x[j] for j in range(in_dim)) + lstm_weights["u_diag_g"][i] * h[i])))
            c[i] = f_gate * c[i] + i_gate * g_cand
            h[i] = o_gate * math.tanh(c[i])
            h_mean[i] += h[i] / seq_len

    return h + h_mean


def train_lstm_model(
    train_sequences: List[List[List[float]]],
    train_labels: List[Dict[str, Dict[str, Any]]],
    horizons: Tuple[str, ...] = ("15m", "1h", "4h"),
    hidden_dim: int = 16,
    epochs: int = 28,
    lr: float = 0.038,
    l2_reg: float = 0.006
) -> Dict[str, Any]:
    core = init_lstm_weights(input_dim=len(train_sequences[0][0]), hidden_dim=hidden_dim)
    encoded_X = [lstm_encode_sequence(core, seq) for seq in train_sequences]
    feat_dim = len(encoded_X[0])
    heads: Dict[str, Any] = {}

    for h in horizons:
        W_cls = [[0.0 for _ in range(feat_dim)] for _ in range(3)]
        b_cls = [0.0, 0.12, 0.0]
        w_ret = [0.0 for _ in range(feat_dim)]
        b_ret = 0.0
        n = float(len(encoded_X))

        for ep in range(epochs):
            step_lr = lr / (1.0 + 0.05 * ep)
            gW = [[0.0 for _ in range(feat_dim)] for _ in range(3)]
            gb = [0.0, 0.0, 0.0]
            gw_ret = [0.0 for _ in range(feat_dim)]
            gb_ret = 0.0

            for x, lbl_map in zip(encoded_X, train_labels):
                lbl = lbl_map[h]
                y_cls = int(lbl["y_dir"])
                y_ret = float(lbl["forward_return"])
                logits = [sum(W_cls[c][j] * x[j] for j in range(feat_dim)) + b_cls[c] for c in range(3)]
                probs = softmax_3(logits)
                for c in range(3):
                    err = probs[c] - (1.0 if c == y_cls else 0.0)
                    gb[c] += err / n
                    for j in range(feat_dim):
                        gW[c][j] += (err * x[j]) / n
                err_ret = (sum(w_ret[j] * x[j] for j in range(feat_dim)) + b_ret) - y_ret
                gb_ret += err_ret / n
                for j in range(feat_dim):
                    gw_ret[j] += (err_ret * x[j]) / n

            for c in range(3):
                b_cls[c] -= step_lr * gb[c]
                for j in range(feat_dim):
                    W_cls[c][j] -= step_lr * (gW[c][j] + l2_reg * W_cls[c][j])
            b_ret -= step_lr * 0.45 * gb_ret
            for j in range(feat_dim):
                w_ret[j] -= step_lr * 0.45 * (gw_ret[j] + l2_reg * w_ret[j])

        heads[h] = {
            "W_cls": [[round(v, 6) for v in row] for row in W_cls],
            "b_cls": [round(v, 6) for v in b_cls],
            "w_ret": [round(v, 6) for v in w_ret],
            "b_ret": round(b_ret, 6),
        }

    return {
        "architecture": "LSTM",
        "model_id": "astra_lstm_v1",
        "core": core,
        "heads": heads,
    }


def predict_lstm(
    lstm_model: Dict[str, Any],
    scaled_seq: List[List[float]],
    horizon: str
) -> Dict[str, Any]:
    enc = lstm_encode_sequence(lstm_model["core"], scaled_seq)
    head = lstm_model["heads"][horizon]
    feat_dim = len(enc)
    logits = [sum(head["W_cls"][c][j] * enc[j] for j in range(feat_dim)) + head["b_cls"][c] for c in range(3)]
    probs = softmax_3(logits)
    exp_ret = sum(head["w_ret"][j] * enc[j] for j in range(feat_dim)) + head["b_ret"]
    sigma = 0.0038 if horizon == "15m" else (0.0072 if horizon == "1h" else 0.0135)
    return {
        "logits": [round(v, 6) for v in logits],
        "probs": [round(v, 6) for v in probs],
        "expected_return": round(exp_ret, 6),
        "q10": round(exp_ret - 1.28155 * sigma, 6),
        "q50": round(exp_ret, 6),
        "q90": round(exp_ret + 1.28155 * sigma, 6),
        "sigma": round(sigma, 6),
    }
