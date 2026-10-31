#!/usr/bin/env python3
"""
FP32 vs. INT8 Dynamic-Range Quantization Parity Verifier (Section 10.3 & Phase 2).
Quantizes model weight matrices into symmetric INT8 (`[-127, 127]`) with per-tensor FP32 scale factors,
runs dequantized INT8 inference on golden sequences, and asserts that:
  - Max probability deviation |p_fp32 - p_int8| < 0.015
  - Max expected return deviation |r_fp32 - r_int8| < 0.0005
"""
from typing import Dict, List, Any, Tuple
from train_gru import predict_gru
from train_tcn import predict_tcn


def quantize_dequantize_matrix(mat: List[List[float]]) -> Tuple[List[List[int]], float, List[List[float]]]:
    max_abs = max((abs(v) for row in mat for v in row), default=1e-6)
    scale = max(max_abs / 127.0, 1e-7)
    q_mat = [[max(-127, min(127, int(round(v / scale)))) for v in row] for row in mat]
    dq_mat = [[round(q * scale, 6) for q in row] for row in q_mat]
    return q_mat, round(scale, 8), dq_mat


def quantize_gru_model_int8(gru_model: Dict[str, Any]) -> Dict[str, Any]:
    import copy
    q_model = copy.deepcopy(gru_model)
    core = q_model["core"]
    scales = {}
    for k in ("W_z", "U_z", "W_r", "U_r", "W_n", "U_n"):
        _, sc, dq = quantize_dequantize_matrix(core[k])
        core[k] = dq
        scales[f"core_{k}"] = sc
    for h, head in q_model["heads"].items():
        _, sc, dq = quantize_dequantize_matrix(head["W_cls"])
        head["W_cls"] = dq
        scales[f"head_{h}_W_cls"] = sc
    q_model["quantization_scales"] = scales
    return q_model


def quantize_tcn_model_int8(tcn_model: Dict[str, Any]) -> Dict[str, Any]:
    import copy
    q_model = copy.deepcopy(tcn_model)
    core = q_model["core"]
    _, _, core["W_proj"] = quantize_dequantize_matrix(core["W_proj"])
    for blk in core["blocks"]:
        _, _, blk["taps"] = quantize_dequantize_matrix(blk["taps"])
    for h, head in q_model["heads"].items():
        _, _, head["W_cls"] = quantize_dequantize_matrix(head["W_cls"])
    return q_model


def compare_fp32_vs_int8_parity(
    gru_fp32: Dict[str, Any],
    tcn_fp32: Dict[str, Any],
    scaled_sequences: List[List[List[float]]],
    horizons: Tuple[str, ...] = ("15m", "1h", "4h")
) -> Dict[str, Any]:
    gru_int8 = quantize_gru_model_int8(gru_fp32)
    tcn_int8 = quantize_tcn_model_int8(tcn_fp32)

    max_prob_diff = 0.0
    max_ret_diff = 0.0

    for seq in scaled_sequences:
        for h in horizons:
            g_f = predict_gru(gru_fp32, seq, h)
            g_q = predict_gru(gru_int8, seq, h)
            t_f = predict_tcn(tcn_fp32, seq, h)
            t_q = predict_tcn(tcn_int8, seq, h)

            for p1, p2 in zip(g_f["probs"], g_q["probs"]):
                max_prob_diff = max(max_prob_diff, abs(p1 - p2))
            for p1, p2 in zip(t_f["probs"], t_q["probs"]):
                max_prob_diff = max(max_prob_diff, abs(p1 - p2))

            max_ret_diff = max(
                max_ret_diff,
                abs(g_f["expected_return"] - g_q["expected_return"]),
                abs(t_f["expected_return"] - t_q["expected_return"]),
            )

    passed = (max_prob_diff < 0.015) and (max_ret_diff < 0.0005)
    return {
        "parity_passed": passed,
        "max_probability_abs_diff": round(max_prob_diff, 6),
        "max_expected_return_abs_diff": round(max_ret_diff, 6),
        "prob_tolerance": 0.015,
        "return_tolerance": 0.0005,
        "gru_int8_model": gru_int8,
        "tcn_int8_model": tcn_int8,
    }
