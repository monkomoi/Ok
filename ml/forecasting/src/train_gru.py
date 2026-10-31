#!/usr/bin/env python3
"""
Compact GRU Encoder + Multi-Horizon Heads Trainer for Astra Trader V5.1.
Couples the regression head and classification head on shared temporal representations
so direction probabilities and expected returns remain semantically consistent.
"""
import hashlib
import math
from typing import Dict, List, Any
from build_features import FEATURE_COUNT

HIDDEN_DIM = 16


def _det_weight(tag: str, scale: float = 0.14) -> float:
    h = int(hashlib.sha256(tag.encode("utf-8")).hexdigest()[:8], 16)
    u = ((h % 20001) / 10000.0) - 1.0
    return round(u * scale, 6)


def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-max(-20.0, min(20.0, x))))


def init_gru_weights() -> Dict[str, Any]:
    return {
        "hidden_dim": HIDDEN_DIM,
        "feature_count": FEATURE_COUNT,
        "W_z": [[_det_weight(f"gru_wz_{i}_{j}", 0.12) for j in range(FEATURE_COUNT)] for i in range(HIDDEN_DIM)],
        "U_z": [[_det_weight(f"gru_uz_{i}_{j}", 0.10) for j in range(HIDDEN_DIM)] for i in range(HIDDEN_DIM)],
        "b_z": [round(0.15 + _det_weight(f"gru_bz_{i}", 0.05), 6) for i in range(HIDDEN_DIM)],
        "W_r": [[_det_weight(f"gru_wr_{i}_{j}", 0.12) for j in range(FEATURE_COUNT)] for i in range(HIDDEN_DIM)],
        "U_r": [[_det_weight(f"gru_ur_{i}_{j}", 0.10) for j in range(HIDDEN_DIM)] for i in range(HIDDEN_DIM)],
        "b_r": [round(_det_weight(f"gru_br_{i}", 0.05), 6) for i in range(HIDDEN_DIM)],
        "W_n": [[_det_weight(f"gru_wn_{i}_{j}", 0.16) for j in range(FEATURE_COUNT)] for i in range(HIDDEN_DIM)],
        "U_n": [[_det_weight(f"gru_un_{i}_{j}", 0.12) for j in range(HIDDEN_DIM)] for i in range(HIDDEN_DIM)],
        "b_n": [0.0 for _ in range(HIDDEN_DIM)],
    }


def gru_encode_sequence(core: Dict[str, Any], seq: List[List[float]]) -> List[float]:
    h = [0.0] * HIDDEN_DIM
    h_mean = [0.0] * HIDDEN_DIM
    inv_t = 1.0 / float(max(len(seq), 1))
    W_z, U_z, b_z = core["W_z"], core["U_z"], core["b_z"]
    W_r, U_r, b_r = core["W_r"], core["U_r"], core["b_r"]
    W_n, U_n, b_n = core["W_n"], core["U_n"], core["b_n"]

    for x in seq:
        h_next = [0.0] * HIDDEN_DIM
        for i in range(HIDDEN_DIM):
            z_lin = b_z[i] + sum(W_z[i][j] * x[j] for j in range(FEATURE_COUNT)) + sum(U_z[i][k] * h[k] for k in range(HIDDEN_DIM))
            r_lin = b_r[i] + sum(W_r[i][j] * x[j] for j in range(FEATURE_COUNT)) + sum(U_r[i][k] * h[k] for k in range(HIDDEN_DIM))
            z_i = _sigmoid(z_lin)
            r_i = _sigmoid(r_lin)
            n_lin = b_n[i] + sum(W_n[i][j] * x[j] for j in range(FEATURE_COUNT)) + r_i * sum(U_n[i][k] * h[k] for k in range(HIDDEN_DIM))
            n_i = math.tanh(max(-15.0, min(15.0, n_lin)))
            h_i = (1.0 - z_i) * h[k if False else i] + z_i * n_i
            h_next[i] = h_i
            h_mean[i] += h_i * inv_t
        h = h_next
    return [round(0.70 * h[i] + 0.30 * h_mean[i], 7) for i in range(HIDDEN_DIM)]


def train_gru_model(
    train_samples: List[Dict[str, Any]],
    horizons: List[str] = ["15m", "1h", "4h"],
    epochs: int = 10,
    lr: float = 0.035,
    l2_reg: float = 0.002
) -> Dict[str, Any]:
    core = init_gru_weights()
    heads: Dict[str, Dict[str, Any]] = {}
    for h in horizons:
        heads[h] = {
            "W_cls": [[_det_weight(f"gru_wcls_{h}_{c}_{i}", 0.08) for i in range(HIDDEN_DIM)] for c in range(3)],
            "b_cls": [0.0, 0.05, 0.0],
            "w_ret": [_det_weight(f"gru_wret_{h}_{i}", 0.002) for i in range(HIDDEN_DIM)],
            "b_ret": 0.0,
            "w_vol": [_det_weight(f"gru_wvol_{h}_{i}", 0.05) for i in range(HIDDEN_DIM)],
            "b_vol": -4.8 if h == "15m" else (-4.2 if h == "1h" else -3.6),
        }

    if not train_samples:
        return {"core": core, "heads": heads}

    encoded_cache = [gru_encode_sequence(core, s["scaled_seq"]) for s in train_samples]
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
                    head["b_cls"][c] + sum(head["W_cls"][c][i] * enc[i] for i in range(HIDDEN_DIM))
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
                    for i in range(HIDDEN_DIM):
                        grad = err * enc[i] + l2_reg * head["W_cls"][c][i] / n_samples
                        head["W_cls"][c][i] -= lr * grad

                pred_ret = head["b_ret"] + sum(head["w_ret"][i] * enc[i] for i in range(HIDDEN_DIM))
                # Couple regression target with directional probability sign so heads align
                err_ret = (pred_ret - y_ret) / n_samples
                head["b_ret"] -= lr * 0.25 * err_ret
                for i in range(HIDDEN_DIM):
                    # Align w_ret with (W_cls[UP] - W_cls[DOWN]) direction
                    cls_dir_prior = (head["W_cls"][2][i] - head["W_cls"][0][i]) * 0.0025
                    grad_r = err_ret * enc[i] + l2_reg * (head["w_ret"][i] - cls_dir_prior) / n_samples
                    head["w_ret"][i] -= lr * grad_r

                if update_core and h == "15m":
                    last_x = s["scaled_seq"][-1]
                    dir_err = ((probs[2] - probs[0]) - (1.0 if y_cls == 2 else (-1.0 if y_cls == 0 else 0.0))) / n_samples
                    for i in range(HIDDEN_DIM):
                        w_diff = head["W_cls"][2][i] - head["W_cls"][0][i]
                        core["b_n"][i] -= lr * 0.15 * dir_err * w_diff
                        for j in range(FEATURE_COUNT):
                            core["W_n"][i][j] -= lr * 0.15 * dir_err * w_diff * last_x[j]

        if update_core:
            encoded_cache = [gru_encode_sequence(core, s["scaled_seq"]) for s in train_samples]

    # Enforce regression bias & weight alignment with classification head
    for h in horizons:
        head = heads[h]
        scale_h = 0.0022 if h == "15m" else (0.0042 if h == "1h" else 0.0078)
        for i in range(HIDDEN_DIM):
            cls_dir = (head["W_cls"][2][i] - head["W_cls"][0][i]) * scale_h
            head["w_ret"][i] = round(0.45 * head["w_ret"][i] + 0.55 * cls_dir, 6)
            for c in range(3):
                head["W_cls"][c][i] = round(head["W_cls"][c][i], 6)
        head["b_cls"] = [round(b - sum(head["b_cls"]) / 3.0, 6) for b in head["b_cls"]]
        head["b_ret"] = round((head["b_cls"][2] - head["b_cls"][0]) * (scale_h * 0.5), 6)

    return {"core": core, "heads": heads}
