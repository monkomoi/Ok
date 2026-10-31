#!/usr/bin/env python3
"""
Causal Dilated TCN Encoder + Multi-Horizon Heads Trainer for Astra Trader V5.1.
"""
import hashlib
import math
from typing import Dict, List, Any
from build_features import FEATURE_COUNT

CHANNELS = 16
DILATIONS = [1, 2, 4, 8]


def _det_weight(tag: str, scale: float = 0.12) -> float:
    h = int(hashlib.sha256(tag.encode("utf-8")).hexdigest()[:8], 16)
    u = ((h % 20001) / 10000.0) - 1.0
    return round(u * scale, 6)


def _gelu(x: float) -> float:
    xc = max(-10.0, min(10.0, x))
    return 0.5 * xc * (1.0 + math.tanh(0.79788456 * (xc + 0.044715 * xc * xc * xc)))


def init_tcn_weights() -> Dict[str, Any]:
    blocks = []
    for b_idx, dil in enumerate(DILATIONS):
        blocks.append({
            "dilation": dil,
            "taps": [
                [[_det_weight(f"tcn_b{b_idx}_t{tap}_{o}_{i}", 0.08) for i in range(CHANNELS)] for o in range(CHANNELS)]
                for tap in range(3)
            ],
            "bias": [0.0 for _ in range(CHANNELS)],
            "res_scale": 0.35,
        })
    return {
        "channels": CHANNELS,
        "feature_count": FEATURE_COUNT,
        "W_proj": [[_det_weight(f"tcn_proj_{c}_{f}", 0.14) for f in range(FEATURE_COUNT)] for c in range(CHANNELS)],
        "b_proj": [0.0 for _ in range(CHANNELS)],
        "blocks": blocks,
    }


def tcn_encode_sequence(core: Dict[str, Any], seq: List[List[float]]) -> List[float]:
    T = len(seq)
    W_proj, b_proj = core["W_proj"], core["b_proj"]
    buf_a = [
        [
            _gelu(b_proj[c] + sum(W_proj[c][f] * seq[t][f] for f in range(FEATURE_COUNT)))
            for c in range(CHANNELS)
        ]
        for t in range(T)
    ]

    for blk in core["blocks"]:
        dil = int(blk["dilation"])
        taps = blk["taps"]
        bias = blk["bias"]
        res_scale = float(blk["res_scale"])
        buf_b = [[0.0] * CHANNELS for _ in range(T)]
        for t in range(T):
            in0 = buf_a[t]
            in1 = buf_a[t - dil] if t - dil >= 0 else None
            in2 = buf_a[t - 2 * dil] if t - 2 * dil >= 0 else None
            for o in range(CHANNELS):
                acc = bias[o] + sum(taps[0][o][i] * in0[i] for i in range(CHANNELS))
                if in1 is not None:
                    acc += sum(taps[1][o][i] * in1[i] for i in range(CHANNELS))
                if in2 is not None:
                    acc += sum(taps[2][o][i] * in2[i] for i in range(CHANNELS))
                buf_b[t][o] = buf_a[t][o] + res_scale * _gelu(acc)
        buf_a = buf_b

    last_step = buf_a[-1]
    inv_t = 1.0 / float(max(T, 1))
    out = []
    for c in range(CHANNELS):
        mean_c = sum(buf_a[t][c] for t in range(T)) * inv_t
        out.append(round(0.65 * last_step[c] + 0.35 * mean_c, 7))
    return out


def train_tcn_model(
    train_samples: List[Dict[str, Any]],
    horizons: List[str] = ["15m", "1h", "4h"],
    epochs: int = 10,
    lr: float = 0.035,
    l2_reg: float = 0.002
) -> Dict[str, Any]:
    core = init_tcn_weights()
    heads: Dict[str, Dict[str, Any]] = {}
    for h in horizons:
        heads[h] = {
            "W_cls": [[_det_weight(f"tcn_wcls_{h}_{c}_{i}", 0.08) for i in range(CHANNELS)] for c in range(3)],
            "b_cls": [0.0, 0.05, 0.0],
            "w_ret": [_det_weight(f"tcn_wret_{h}_{i}", 0.002) for i in range(CHANNELS)],
            "b_ret": 0.0,
            "w_vol": [_det_weight(f"tcn_wvol_{h}_{i}", 0.05) for i in range(CHANNELS)],
            "b_vol": -4.8 if h == "15m" else (-4.2 if h == "1h" else -3.6),
        }

    if not train_samples:
        return {"core": core, "heads": heads}

    encoded_cache = [tcn_encode_sequence(core, s["scaled_seq"]) for s in train_samples]
    n_samples = float(len(train_samples))

    for ep in range(epochs):
        update_core = (ep == epochs // 2)
        for s_idx, s in enumerate(train_samples):
            enc = encoded_cache[s_idx]
            for h in horizons:
                lbl = s["labels"][h]
                y_cls = int(lbl["y_dir"])
                y_ret = float(lbl["forward_return"])
                head = heads[h]

                logits = [
                    head["b_cls"][c] + sum(head["W_cls"][c][i] * enc[i] for i in range(CHANNELS))
                    for c in range(3)
                ]
                m_lg = max(logits)
                exps = [math.exp(max(-20.0, min(20.0, lg - m_lg))) for lg in logits]
                s_exp = max(sum(exps), 1e-9)
                probs = [e / s_exp for e in exps]

                for c in range(3):
                    target = 1.0 if c == y_cls else 0.0
                    err = (probs[c] - target) / n_samples
                    head["b_cls"][c] -= lr * err
                    for i in range(CHANNELS):
                        grad = err * enc[i] + l2_reg * head["W_cls"][c][i] / n_samples
                        head["W_cls"][c][i] -= lr * grad

                pred_ret = head["b_ret"] + sum(head["w_ret"][i] * enc[i] for i in range(CHANNELS))
                err_ret = (pred_ret - y_ret) / n_samples
                head["b_ret"] -= lr * 0.25 * err_ret
                for i in range(CHANNELS):
                    cls_dir_prior = (head["W_cls"][2][i] - head["W_cls"][0][i]) * 0.0025
                    grad_r = err_ret * enc[i] + l2_reg * (head["w_ret"][i] - cls_dir_prior) / n_samples
                    head["w_ret"][i] -= lr * grad_r

                if update_core and h == "15m":
                    last_x = s["scaled_seq"][-1]
                    dir_err = ((probs[2] - probs[0]) - (1.0 if y_cls == 2 else (-1.0 if y_cls == 0 else 0.0))) / n_samples
                    for i in range(CHANNELS):
                        w_diff = head["W_cls"][2][i] - head["W_cls"][0][i]
                        for f in range(FEATURE_COUNT):
                            core["W_proj"][i][f] -= lr * 0.12 * dir_err * w_diff * last_x[f]

        if update_core:
            encoded_cache = [tcn_encode_sequence(core, s["scaled_seq"]) for s in train_samples]

    for h in horizons:
        head = heads[h]
        scale_h = 0.0022 if h == "15m" else (0.0042 if h == "1h" else 0.0078)
        for i in range(CHANNELS):
            cls_dir = (head["W_cls"][2][i] - head["W_cls"][0][i]) * scale_h
            head["w_ret"][i] = round(0.45 * head["w_ret"][i] + 0.55 * cls_dir, 6)
            for c in range(3):
                head["W_cls"][c][i] = round(head["W_cls"][c][i], 6)
        head["b_cls"] = [round(b - sum(head["b_cls"]) / 3.0, 6) for b in head["b_cls"]]
        head["b_ret"] = round((head["b_cls"][2] - head["b_cls"][0]) * (scale_h * 0.5), 6)

    return {"core": core, "heads": heads}
