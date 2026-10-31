#!/usr/bin/env python3
"""
Out-of-Sample Probability Calibration Engine (Section 8) for Astra Trader V5.1 Forecasting Engine.
Fits Temperature Scaling + Platt class-prior shifts strictly on Validation fold (never Train or Test).
Computes Multi-Class Brier Score, LogLoss, and 10-bin Expected Calibration Error (ECE).
"""
import math
from typing import Dict, List, Any, Tuple
from train_baselines import softmax_3


def apply_calibrated_softmax(
    raw_logits: List[float],
    temperature: float,
    bias_shift: List[float]
) -> List[float]:
    t = max(float(temperature), 0.15)
    shifted = [(raw_logits[i] + bias_shift[i]) / t for i in range(3)]
    m = max(shifted)
    exps = [math.exp(x - m) for x in shifted]
    s = sum(exps)
    probs = [e / s for e in exps]
    # Normalize so pDown + pFlat + pUp == 1.0 within 1e-6
    p0 = round(probs[0], 6)
    p1 = round(probs[1], 6)
    p2 = round(max(0.0, 1.0 - p0 - p1), 6)
    return [p0, p1, p2]


def compute_brier_and_ece(
    probs_list: List[List[float]],
    true_classes: List[int],
    num_bins: int = 10
) -> Tuple[float, float, float]:
    """
    Returns (multiclass_brier_score, log_loss, expected_calibration_error).
    """
    n = len(probs_list)
    if n == 0:
        return 1.0, 2.0, 1.0

    brier_sum = 0.0
    logloss_sum = 0.0
    bins_conf = [[] for _ in range(num_bins)]
    bins_acc = [[] for _ in range(num_bins)]

    for probs, y in zip(probs_list, true_classes):
        for c in range(3):
            target = 1.0 if c == y else 0.0
            diff = probs[c] - target
            brier_sum += (diff * diff) / 3.0

        p_true = max(min(probs[y], 0.9999), 1e-4)
        logloss_sum += -math.log(p_true)

        max_p = max(probs)
        pred_c = probs.index(max_p)
        b_idx = min(num_bins - 1, int(max_p * num_bins))
        bins_conf[b_idx].append(max_p)
        bins_acc[b_idx].append(1.0 if pred_c == y else 0.0)

    brier = brier_sum / float(n)
    logloss = logloss_sum / float(n)

    ece = 0.0
    for b_idx in range(num_bins):
        cnt = len(bins_conf[b_idx])
        if cnt > 0:
            avg_conf = sum(bins_conf[b_idx]) / float(cnt)
            avg_acc = sum(bins_acc[b_idx]) / float(cnt)
            ece += (float(cnt) / float(n)) * abs(avg_conf - avg_acc)

    return round(brier, 5), round(logloss, 5), round(ece, 5)


def fit_temperature_and_platt_on_validation(
    val_raw_logits: List[List[float]],
    val_true_classes: List[int],
    min_required_samples: int = 30
) -> Dict[str, Any]:
    """
    Grid-searches optimal Temperature T in [0.65, 2.40] and class prior log-odds shift
    strictly on validation samples to minimize LogLoss + Brier + ECE.
    """
    n = len(val_raw_logits)
    if n < min_required_samples:
        return {
            "calibrated": False,
            "method": "UNCALIBRATED_INSUFFICIENT_VAL_SAMPLES",
            "sample_size": n,
            "min_required_samples": min_required_samples,
            "temperature": 1.0,
            "bias_shift": [0.0, 0.0, 0.0],
            "brier_score": None,
            "log_loss": None,
            "ece": None,
        }

    # Empirical class prior alignment on validation fold
    counts = [1.0, 1.0, 1.0]
    for y in val_true_classes:
        counts[y] += 1.0
    emp_priors = [c / sum(counts) for c in counts]

    avg_uncal = [0.0, 0.0, 0.0]
    for lg in val_raw_logits:
        pr = softmax_3(lg)
        for c in range(3):
            avg_uncal[c] += pr[c] / float(n)

    bias_shift = [
        round(0.85 * math.log(max(emp_priors[c], 1e-3) / max(avg_uncal[c], 1e-3)), 5)
        for c in range(3)
    ]

    best_temp = 1.0
    best_obj = 999.0
    best_metrics = (1.0, 2.0, 1.0)
    best_bias = list(bias_shift)

    for bias_scale in (0.5, 0.85, 1.15):
        cand_bias = [round(b * bias_scale / 0.85, 5) for b in bias_shift]
        for step in range(44):
            cand_t = round(0.25 + step * 0.05, 2)
            cand_probs = [apply_calibrated_softmax(lg, cand_t, cand_bias) for lg in val_raw_logits]
            brier, logloss, ece = compute_brier_and_ece(cand_probs, val_true_classes, num_bins=5)
            obj = brier + 0.25 * logloss + 1.50 * ece
            if obj < best_obj:
                best_obj = obj
                best_temp = cand_t
                best_metrics = (brier, logloss, ece)
                best_bias = cand_bias

    brier_f, logloss_f, ece_f = best_metrics
    is_valid = (n >= min_required_samples) and (brier_f <= 0.30) and (ece_f <= 0.12)

    return {
        "calibrated": is_valid,
        "method": "TEMPERATURE_AND_PLATT_PRIOR_SCALING" if is_valid else "UNCALIBRATED_THRESHOLD_EXCEEDED",
        "sample_size": n,
        "min_required_samples": min_required_samples,
        "temperature": best_temp,
        "bias_shift": best_bias,
        "brier_score": brier_f,
        "log_loss": logloss_f,
        "ece": ece_f,
    }
