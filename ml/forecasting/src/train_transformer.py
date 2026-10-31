#!/usr/bin/env python3
"""
Experimental Tiny Causal Transformer (`d_model=16`, `nhead=2`, 1 causal self-attention layer).
Evaluated offline per Section 4.1 & 4.2 to verify whether causal self-attention beats compact
GRU v1 / TCN v1 on noisy 15m crypto klines under S7 Edge resource constraints.
"""
import math
from typing import Dict, List, Any, Tuple
from train_baselines import softmax_3


def causal_attention_encode(weights: Dict[str, Any], scaled_seq: List[List[float]]) -> List[float]:
    in_dim = weights["input_dim"]
    d_model = weights["d_model"]
    W_q = weights["W_q"]
    W_k = weights["W_k"]
    W_v = weights["W_v"]

    # Use last 16 steps for compact causal self-attention
    window = scaled_seq[-16:]
    T = len(window)
    Q = [[sum(W_q[d][f] * window[t][f] for f in range(in_dim)) for d in range(d_model)] for t in range(T)]
    K = [[sum(W_k[d][f] * window[t][f] for f in range(in_dim)) for d in range(d_model)] for t in range(T)]
    V = [[sum(W_v[d][f] * window[t][f] for f in range(in_dim)) for d in range(d_model)] for t in range(T)]

    scale = 1.0 / math.sqrt(float(d_model))
    # Causal attention at final step t = T - 1 attends over s = 0..T-1
    q_last = Q[-1]
    scores = [sum(q_last[d] * K[s][d] for d in range(d_model)) * scale for s in range(T)]
    m = max(scores)
    exps = [math.exp(sc - m) for sc in scores]
    denom = sum(exps)
    attn = [e / denom for e in exps]

    out = [0.0 for _ in range(d_model)]
    for s in range(T):
        for d in range(d_model):
            out[d] += attn[s] * V[s][d]
    return out + V[-1]


def train_tiny_transformer_model(
    train_sequences: List[List[List[float]]],
    train_labels: List[Dict[str, Dict[str, Any]]],
    horizons: Tuple[str, ...] = ("15m", "1h", "4h"),
    d_model: int = 16,
    epochs: int = 24,
    lr: float = 0.03,
    l2_reg: float = 0.01
) -> Dict[str, Any]:
    in_dim = len(train_sequences[0][0])
    core = {
        "input_dim": in_dim,
        "d_model": d_model,
        "W_q": [[round(0.15 * math.sin((d + 1) * 0.5 + (f + 1) * 0.2), 6) for f in range(in_dim)] for d in range(d_model)],
        "W_k": [[round(0.15 * math.cos((d + 1) * 0.4 + (f + 1) * 0.3), 6) for f in range(in_dim)] for d in range(d_model)],
        "W_v": [[round(0.18 * math.sin((d + 1) * 0.6 + (f + 1) * 0.4), 6) for f in range(in_dim)] for d in range(d_model)],
    }
    encoded_X = [causal_attention_encode(core, seq) for seq in train_sequences]
    feat_dim = len(encoded_X[0])
    heads: Dict[str, Any] = {}

    for h in horizons:
        W_cls = [[0.0 for _ in range(feat_dim)] for _ in range(3)]
        b_cls = [0.0, 0.15, 0.0]
        w_ret = [0.0 for _ in range(feat_dim)]
        b_ret = 0.0
        n = float(len(encoded_X))
        for ep in range(epochs):
            step_lr = lr / (1.0 + 0.06 * ep)
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
            b_ret -= step_lr * 0.4 * gb_ret
            for j in range(feat_dim):
                w_ret[j] -= step_lr * 0.4 * (gw_ret[j] + l2_reg * w_ret[j])

        heads[h] = {
            "W_cls": [[round(v, 6) for v in row] for row in W_cls],
            "b_cls": [round(v, 6) for v in b_cls],
            "w_ret": [round(v, 6) for v in w_ret],
            "b_ret": round(b_ret, 6),
        }

    return {
        "architecture": "TINY_TRANSFORMER",
        "model_id": "astra_tiny_transformer_v1",
        "core": core,
        "heads": heads,
    }


def predict_tiny_transformer(
    tf_model: Dict[str, Any],
    scaled_seq: List[List[float]],
    horizon: str
) -> Dict[str, Any]:
    enc = causal_attention_encode(tf_model["core"], scaled_seq)
    head = tf_model["heads"][horizon]
    feat_dim = len(enc)
    logits = [sum(head["W_cls"][c][j] * enc[j] for j in range(feat_dim)) + head["b_cls"][c] for c in range(3)]
    probs = softmax_3(logits)
    exp_ret = sum(head["w_ret"][j] * enc[j] for j in range(feat_dim)) + head["b_ret"]
    sigma = 0.0040 if horizon == "15m" else (0.0075 if horizon == "1h" else 0.0140)
    return {
        "logits": [round(v, 6) for v in logits],
        "probs": [round(v, 6) for v in probs],
        "expected_return": round(exp_ret, 6),
        "q10": round(exp_ret - 1.28155 * sigma, 6),
        "q50": round(exp_ret, 6),
        "q90": round(exp_ret + 1.28155 * sigma, 6),
        "sigma": round(sigma, 6),
    }
