#!/usr/bin/env python3
"""
Regularized Temperature & Prior-Shrinkage Calibration for Astra Trader V5.1.
Fixes the Blank-Slate Audit findings:
  1. Prevents temperature collapse (bounds T in [0.75, 2.50]) and bounds class prior shifts
     (|bias_shift| <= 0.25 with Dirichlet shrinkage when sample_size < 200).
  2. Explicitly marks `qualification_status = "INSUFFICIENT_SAMPLE_SHADOW_ONLY"` whenever
     `sample_size < min_required_samples_for_live` (200), preventing small validation samples
     from claiming production calibration qualification.
  3. Checks classification-head vs regression-head sign/magnitude consistency.
"""
import math
from typing import Dict, List, Any

MIN_SAMPLES_FOR_LIVE_QUALIFICATION = 200


def softmax3(logits: List[float]) -> List[float]:
    m = max(logits)
    exps = [math.exp(max(-30.0, min(30.0, x - m))) for x in logits]
    s = max(sum(exps), 1e-12)
    return [e / s for e in exps]


def apply_calibration(
    raw_logits: List[float],
    temperature: float,
    bias_shift: List[float]
) -> List[float]:
    temp = max(0.75, min(2.50, float(temperature)))
    adj = [(float(raw_logits[i]) + float(bias_shift[i])) / temp for i in range(3)]
    return softmax3(adj)


def compute_brier_and_ece(
    probs_list: List[List[float]],
    y_true: List[int],
    n_bins: int = 10
) -> Dict[str, float]:
    n = len(probs_list)
    if n == 0:
        return {"brier_score": 0.25, "log_loss": 1.0986, "ece": 0.15}

    brier_sum = 0.0
    nll_sum = 0.0
    bin_counts = [0] * n_bins
    bin_conf = [0.0] * n_bins
    bin_acc = [0.0] * n_bins

    for probs, y in zip(probs_list, y_true):
        for c in range(3):
            target = 1.0 if y == c else 0.0
            brier_sum += (probs[c] - target) ** 2
        p_true = max(min(probs[y], 1.0 - 1e-7), 1e-7)
        nll_sum -= math.log(p_true)

        pred_c = max(range(3), key=lambda k: probs[k])
        conf = probs[pred_c]
        acc = 1.0 if pred_c == y else 0.0
        b_idx = min(int(conf * n_bins), n_bins - 1)
        bin_counts[b_idx] += 1
        bin_conf[b_idx] += conf
        bin_acc[b_idx] += acc

    ece = 0.0
    for b in range(n_bins):
        if bin_counts[b] > 0:
            avg_c = bin_conf[b] / float(bin_counts[b])
            avg_a = bin_acc[b] / float(bin_counts[b])
            ece += (float(bin_counts[b]) / float(n)) * abs(avg_a - avg_c)

    return {
        "brier_score": round(brier_sum / (3.0 * n), 5),
        "log_loss": round(nll_sum / float(n), 5),
        "ece": round(ece, 5),
    }


def fit_horizon_calibration(
    raw_logits_list: List[List[float]],
    y_true: List[int]
) -> Dict[str, Any]:
    n = len(raw_logits_list)
    if n < 10:
        return {
            "calibrated": False,
            "qualification_status": "INSUFFICIENT_SAMPLE_SHADOW_ONLY",
            "min_required_samples": 20,
            "min_required_samples_for_live": MIN_SAMPLES_FOR_LIVE_QUALIFICATION,
            "sample_size": n,
            "method": "TEMPERATURE_AND_REGULARIZED_PRIOR_SCALING",
            "temperature": 1.0,
            "bias_shift": [0.0, 0.0, 0.0],
            "brier_score": 0.25,
            "log_loss": 1.0986,
            "ece": 0.15,
        }

    # Regularized prior shift with strong shrinkage toward [0,0,0] on small samples
    counts = [1.0, 1.0, 1.0]
    for y in y_true:
        if 0 <= y < 3:
            counts[y] += 1.0
    emp = [c / sum(counts) for c in counts]
    mean_probs = [0.0, 0.0, 0.0]
    for lg in raw_logits_list:
        p = softmax3(lg)
        for k in range(3):
            mean_probs[k] += p[k] / float(n)

    shrinkage = min(0.35, float(n) / float(MIN_SAMPLES_FOR_LIVE_QUALIFICATION))
    raw_shift = [
        shrinkage * math.log(max(emp[k], 0.15) / max(mean_probs[k], 0.15))
        for k in range(3)
    ]
    mean_shift = sum(raw_shift) / 3.0
    bias_shift = [round(max(-0.22, min(0.22, s - mean_shift)), 5) for s in raw_shift]

    # Grid search temperature in [0.80, 2.00] minimizing regularized Brier + NLL
    best_temp = 1.0
    best_loss = 1e9
    for t_step in [0.85, 0.95, 1.0, 1.10, 1.25, 1.45, 1.70]:
        probs = [apply_calibration(lg, t_step, bias_shift) for lg in raw_logits_list]
        m = compute_brier_and_ece(probs, y_true)
        score = m["log_loss"] + 1.5 * m["brier_score"] + 0.5 * m["ece"]
        if score < best_loss:
            best_loss = score
            best_temp = t_step

    cal_probs = [apply_calibration(lg, best_temp, bias_shift) for lg in raw_logits_list]
    metrics = compute_brier_and_ece(cal_probs, y_true)
    live_qualified = n >= MIN_SAMPLES_FOR_LIVE_QUALIFICATION and metrics["ece"] <= 0.10

    return {
        "calibrated": True,
        "live_qualified": live_qualified,
        "qualification_status": (
            "QUALIFIED_CHRONOLOGICAL_HOLDOUT"
            if live_qualified
            else "INSUFFICIENT_SAMPLE_SHADOW_ONLY"
        ),
        "min_required_samples": 20,
        "min_required_samples_for_live": MIN_SAMPLES_FOR_LIVE_QUALIFICATION,
        "sample_size": n,
        "method": "TEMPERATURE_AND_REGULARIZED_PRIOR_SCALING",
        "temperature": best_temp,
        "bias_shift": bias_shift,
        "brier_score": metrics["brier_score"],
        "log_loss": metrics["log_loss"],
        "ece": metrics["ece"],
    }


def check_head_consistency(
    probs: Dict[str, float],
    expected_return_pct: float,
    q50_pct: float,
    tau_cost_pct: float = 0.18
) -> Dict[str, Any]:
    """
    Verifies semantic consistency between the direction probability head (pDown, pFlat, pUp)
    and the expected return / median quantile head (expectedReturnPct, q50Pct).
    """
    p_down = float(probs.get("pDown", 0.0))
    p_up = float(probs.get("pUp", 0.0))
    contradiction_reasons: List[str] = []

    if p_down - p_up > 0.25 and (expected_return_pct > tau_cost_pct or q50_pct > tau_cost_pct):
        contradiction_reasons.append(
            f"Direction head favors DOWN (pDown={p_down:.3f} > pUp={p_up:.3f}) while return head is strongly positive ({expected_return_pct:.4f}%)"
        )
    if p_up - p_down > 0.25 and (expected_return_pct < -tau_cost_pct or q50_pct < -tau_cost_pct):
        contradiction_reasons.append(
            f"Direction head favors UP (pUp={p_up:.3f} > pDown={p_down:.3f}) while return head is strongly negative ({expected_return_pct:.4f}%)"
        )

    return {
        "consistent": len(contradiction_reasons) == 0,
        "reasons": contradiction_reasons,
    }
