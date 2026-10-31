#!/usr/bin/env python3
"""
Compact Causal Dilated TCN v1 Trainer & Deterministic Inference Engine.
Dilations: [1, 2, 4, 8], Kernel Size: 3, Channels: 16.
Strictly causal: output at step `t` only reads `t`, `t - d`, `t - 2*d`.
"""
import math
from typing import Dict, List, Any, Tuple
from train_baselines import softmax_3


def gelu_approx(x: float) -> float:
    xc = max(-10.0, min(10.0, x))
    return 0.5 * xc * (1.0 + math.tanh(0.79788456 * (xc + 0.044715 * xc * xc * xc)))


def init_tcn_weights(input_dim: int = 26, channels: int = 16) -> Dict[str, Any]:
    """
    Initializes a causal dilated Depthwise-Separable TCN bank with dilations [1, 2, 4, 8].
    """
    dilations = [1, 2, 4, 8]
    # Project input_dim -> channels
    W_proj = [[0.0 for _ in range(input_dim)] for _ in range(channels)]
    b_proj = [0.0 for _ in range(channels)]
    for c in range(channels):
        for f in range(input_dim):
            W_proj[c][f] = round(0.18 * math.cos((c + 1) * 0.63 + (f + 1) * 0.41), 6)
        if c % 3 == 0:
            W_proj[c][0] += 0.30
            W_proj[c][10] += 0.28
        elif c % 3 == 1:
            W_proj[c][14] += 0.30
            W_proj[c][17] += 0.25
        else:
            W_proj[c][19] += 0.26
            W_proj[c][20] += 0.28

    # Causal kernel weights per dilation block: shape [channels, 3] for taps (t - 2d, t - d, t)
    blocks = []
    for b_idx, d in enumerate(dilations):
        k_taps = []
        for c in range(channels):
            w0 = round(0.16 * math.sin((b_idx + 1) * (c + 1) * 0.3), 6)
            w1 = round(0.28 * math.cos((b_idx + 1) * (c + 1) * 0.5), 6)
            w2 = round(0.56 + 0.08 * math.sin(c * 0.7), 6)
            k_taps.append([w0, w1, w2])
        blocks.append({
            "dilation": d,
            "taps": k_taps,
        })

    return {
        "input_dim": input_dim,
        "channels": channels,
        "W_proj": W_proj,
        "b_proj": b_proj,
        "blocks": blocks,
    }


def tcn_encode_sequence(tcn_weights: Dict[str, Any], scaled_seq: List[List[float]]) -> List[float]:
    """
    Runs causal dilated 1D convolutions across `[L, F]` and returns `[2 * channels]`
    (final causal step + global causal average pool).
    """
    in_dim = tcn_weights["input_dim"]
    ch = tcn_weights["channels"]
    W_proj = tcn_weights["W_proj"]
    b_proj = tcn_weights["b_proj"]
    seq_len = len(scaled_seq)

    # Initial 1x1 projection: [seq_len, ch]
    hidden = []
    for t in range(seq_len):
        x = scaled_seq[t]
        row = []
        for c in range(ch):
            s = b_proj[c]
            for f in range(in_dim):
                s += W_proj[c][f] * x[f]
            row.append(gelu_approx(s))
        hidden.append(row)

    # Causal dilated residual blocks
    for blk in tcn_weights["blocks"]:
        d = int(blk["dilation"])
        taps = blk["taps"]
        next_hidden = []
        for t in range(seq_len):
            row = []
            for c in range(ch):
                v_t = hidden[t][c]
                v_t1 = hidden[t - d][c] if t - d >= 0 else 0.0
                v_t2 = hidden[t - 2 * d][c] if t - 2 * d >= 0 else 0.0
                w0, w1, w2 = taps[c]
                conv_val = w0 * v_t2 + w1 * v_t1 + w2 * v_t
                # Residual connection
                row.append(0.5 * v_t + 0.5 * gelu_approx(conv_val))
            next_hidden.append(row)
        hidden = next_hidden

    last_step = list(hidden[-1])
    avg_step = [sum(hidden[t][c] for t in range(seq_len)) / float(seq_len) for c in range(ch)]
    return last_step + avg_step


def train_tcn_model(
    train_sequences: List[List[List[float]]],
    train_labels: List[Dict[str, Dict[str, Any]]],
    horizons: Tuple[str, ...] = ("15m", "1h", "4h"),
    channels: int = 16,
    epochs: int = 42,
    lr: float = 0.18,
    l2_reg: float = 0.002
) -> Dict[str, Any]:
    tcn_core = init_tcn_weights(input_dim=len(train_sequences[0][0]), channels=channels)
    encoded_X = [tcn_encode_sequence(tcn_core, seq) for seq in train_sequences]
    feat_dim = len(encoded_X[0])
    heads: Dict[str, Any] = {}

    for h in horizons:
        W_cls = [[0.0 for _ in range(feat_dim)] for _ in range(3)]
        b_cls = [0.0, 0.10, 0.0]
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

            for x, lbl_map in zip(encoded_X, train_labels):
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
        "architecture": "TCN",
        "model_id": "astra_tcn_v1",
        "core": tcn_core,
        "heads": heads,
    }


def predict_tcn(
    tcn_model: Dict[str, Any],
    scaled_seq: List[List[float]],
    horizon: str
) -> Dict[str, Any]:
    enc = tcn_encode_sequence(tcn_model["core"], scaled_seq)
    head = tcn_model["heads"][horizon]
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
