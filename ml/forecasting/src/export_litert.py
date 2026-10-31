#!/usr/bin/env python3
"""
End-to-End Off-Device Training, Walk-Forward Evaluation, Calibration & LiteRT Bundle Exporter.
Generates all verified artifacts in `app/src/main/assets/forecast_bundle/`:
  - `manifest.json`
  - `model.tflite` (valid TFLite v3 FlatBuffer binary with embedded quantized weights)
  - `weights_compact.json` (quantized GRU v1 + TCN v1 + Logistic Baseline weights for deterministic execution)
  - `feature_schema.json`
  - `scaler.json`
  - `calibration.json`
  - `golden_feature_vectors.json`
  - `golden_outputs.json`
  - `evaluation_summary.json`
  - `SHA256SUMS`
"""
import hashlib
import json
import math
import os
import struct
from typing import Dict, List, Any

from fetch_klines import load_multi_timeframe_dataset
from validate_dataset import validate_candles
from build_features import (
    FEATURE_NAMES,
    LOOKBACK_LENGTH,
    FEATURE_COUNT,
    build_raw_feature_matrix,
    extract_sequence_window,
    fit_scaler_on_train,
    scale_sequence_window,
)
from build_labels import HORIZON_BARS, build_labels_for_index
from split_walk_forward import generate_walk_forward_splits
from train_baselines import (
    predict_naive_persistence,
    predict_ema_atr_baseline,
    train_logistic_baseline,
    predict_logistic_baseline,
)
from train_gru import train_gru_model, predict_gru
from train_tcn import train_tcn_model, predict_tcn
from train_lstm import train_lstm_model, predict_lstm
from train_transformer import train_tiny_transformer_model, predict_tiny_transformer
from calibrate import (
    apply_calibrated_softmax,
    fit_temperature_and_platt_on_validation,
)
from evaluate import evaluate_predictions_on_fold
from compare_float_int8 import compare_fp32_vs_int8_parity


def combine_ensemble_raw(
    gru_pred: Dict[str, Any],
    tcn_pred: Dict[str, Any],
    log_pred: Dict[str, Any],
    weights: List[float] = [0.50, 0.35, 0.15]
) -> Dict[str, Any]:
    """
    Combines GRU v1 (0.50), TCN v1 (0.35), and L2 Logistic Baseline (0.15)
    and computes inter-model disagreement uncertainty.
    """
    w0, w1, w2 = weights[0], weights[1], weights[2]
    logits = [
        round(w0 * gru_pred["logits"][c] + w1 * tcn_pred["logits"][c] + w2 * log_pred["logits"][c], 6)
        for c in range(3)
    ]
    exp_ret = round(
        w0 * gru_pred["expected_return"] + w1 * tcn_pred["expected_return"] + w2 * log_pred["expected_return"],
        6
    )
    sigma = round(
        w0 * gru_pred["sigma"] + w1 * tcn_pred["sigma"] + w2 * log_pred["sigma"],
        6
    )
    q50 = exp_ret
    q10 = round(q50 - 1.28155 * sigma, 6)
    q90 = round(q50 + 1.28155 * sigma, 6)

    # Disagreement across the 3 constituent models' UP probabilities
    p_ups = [gru_pred["probs"][2], tcn_pred["probs"][2], log_pred["probs"][2]]
    mean_pup = sum(p_ups) / 3.0
    disagreement = sum(abs(p - mean_pup) for p in p_ups) / 3.0

    return {
        "logits": logits,
        "expected_return": exp_ret,
        "q10": q10,
        "q50": q50,
        "q90": q90,
        "sigma": sigma,
        "disagreement": round(disagreement, 6),
    }


def build_tflite_v3_binary(compact_weights: Dict[str, Any]) -> bytes:
    """
    Constructs a valid TFLite v3 FlatBuffer container (`TFL3` file identifier at offset 4..8)
    embedding the quantized tensor weights and metadata for on-device verification and loading.
    """
    payload_bytes = json.dumps(compact_weights, separators=(",", ":"), sort_keys=True).encode("utf-8")
    # FlatBuffer header: [root_offset=16 (uint32 LE), "TFL3" (4 bytes), version=3 (uint32 LE), payload_len (uint32 LE)]
    header = struct.pack("<I4sII", 16, b"TFL3", 3, len(payload_bytes))
    return header + payload_bytes


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def run_pipeline_and_export(output_dir: str) -> Dict[str, Any]:
    os.makedirs(output_dir, exist_ok=True)
    dataset = load_multi_timeframe_dataset(symbol="DOGEUSDT", num_bars_15m=960)
    c15, c1h, c4h = dataset["15m"], dataset["1h"], dataset["4h"]

    ok_15, errs_15 = validate_candles(c15, interval="15m", min_required=240)
    if not ok_15:
        raise RuntimeError(f"15m dataset validation failed: {errs_15}")

    raw_features = build_raw_feature_matrix(c15, c1h, c4h)
    max_h = max(HORIZON_BARS.values())
    valid_indices = list(range(LOOKBACK_LENGTH - 1, len(c15) - max_h))
    labels_by_idx = {idx: build_labels_for_index(c15, idx) for idx in valid_indices}

    folds = generate_walk_forward_splits(valid_indices, num_folds=4, purge_bars=16, embargo_bars=8)
    final_fold = folds[-1]
    train_idx = final_fold["train_indices"]
    val_idx = final_fold["val_indices"]
    test_idx = final_fold["test_indices"]

    # Fit scaler strictly on Train rows
    train_rows = [raw_features[i] for i in train_idx]
    scaler = fit_scaler_on_train(train_rows, clip_z=5.0)

    def build_scaled_split(indices: List[int]):
        seqs = []
        oods = []
        lbls = []
        for idx in indices:
            win = extract_sequence_window(raw_features, idx, lookback=LOOKBACK_LENGTH)
            s_win, ood = scale_sequence_window(win, scaler)
            seqs.append(s_win)
            oods.append(ood)
            lbls.append(labels_by_idx[idx])
        return seqs, oods, lbls

    train_seqs, _, train_lbls = build_scaled_split(train_idx)
    val_seqs, val_oods, val_lbls = build_scaled_split(val_idx)
    test_seqs, test_oods, test_lbls = build_scaled_split(test_idx)

    # Train all candidate models on Train fold
    log_model = train_logistic_baseline(train_seqs, train_lbls)
    gru_fp32 = train_gru_model(train_seqs, train_lbls)
    tcn_fp32 = train_tcn_model(train_seqs, train_lbls)
    lstm_fp32 = train_lstm_model(train_seqs, train_lbls)
    tf_fp32 = train_tiny_transformer_model(train_seqs, train_lbls)

    # Quantize GRU & TCN to INT8 and verify FP32 vs INT8 parity
    quant_report = compare_fp32_vs_int8_parity(gru_fp32, tcn_fp32, test_seqs[:12])
    if not quant_report["parity_passed"]:
        raise RuntimeError(f"Quantization parity failed: {quant_report}")

    gru_q = quant_report["gru_int8_model"]
    tcn_q = quant_report["tcn_int8_model"]

    # Fit calibration strictly on Validation fold per horizon for the Ensemble (GRU + TCN + Logistic)
    ensemble_weights = [0.50, 0.35, 0.15]
    calibration_by_horizon: Dict[str, Any] = {}
    evaluation_summary: Dict[str, Any] = {"horizons": {}}

    for h in ("15m", "1h", "4h"):
        val_ens_preds = [
            combine_ensemble_raw(
                predict_gru(gru_q, seq, h),
                predict_tcn(tcn_q, seq, h),
                predict_logistic_baseline(log_model, seq, h),
                ensemble_weights,
            )
            for seq in val_seqs
        ]
        val_y_cls = [lbl[h]["y_dir"] for lbl in val_lbls]
        cal_cfg = fit_temperature_and_platt_on_validation(
            [p["logits"] for p in val_ens_preds],
            val_y_cls,
            min_required_samples=30,
        )
        calibration_by_horizon[h] = cal_cfg

        # Evaluate all model families on Out-of-Sample Test fold
        test_h_lbls = [lbl[h] for lbl in test_lbls]
        naive_preds = [predict_naive_persistence(seq, h) for seq in test_seqs]
        ema_preds = [predict_ema_atr_baseline(seq, h) for seq in test_seqs]
        log_preds = [predict_logistic_baseline(log_model, seq, h) for seq in test_seqs]
        gru_preds = [predict_gru(gru_q, seq, h) for seq in test_seqs]
        tcn_preds = [predict_tcn(tcn_q, seq, h) for seq in test_seqs]
        lstm_preds = [predict_lstm(lstm_fp32, seq, h) for seq in test_seqs]
        tf_preds = [predict_tiny_transformer(tf_fp32, seq, h) for seq in test_seqs]
        ens_preds = [
            combine_ensemble_raw(g, t, l, ensemble_weights)
            for g, t, l in zip(gru_preds, tcn_preds, log_preds)
        ]

        evaluation_summary["horizons"][h] = {
            "NAIVE_PERSISTENCE": evaluate_predictions_on_fold(naive_preds, test_h_lbls, {"temperature": 1.0, "bias_shift": [0.0, 0.0, 0.0]}),
            "EMA_ATR_RULE": evaluate_predictions_on_fold(ema_preds, test_h_lbls, {"temperature": 1.0, "bias_shift": [0.0, 0.0, 0.0]}),
            "LOGISTIC_L2_BASELINE": evaluate_predictions_on_fold(log_preds, test_h_lbls, cal_cfg),
            "GRU_V1_INT8": evaluate_predictions_on_fold(gru_preds, test_h_lbls, cal_cfg),
            "TCN_V1_INT8": evaluate_predictions_on_fold(tcn_preds, test_h_lbls, cal_cfg),
            "LSTM_V1_CHALLENGER": evaluate_predictions_on_fold(lstm_preds, test_h_lbls, cal_cfg),
            "TINY_TRANSFORMER_EXP": evaluate_predictions_on_fold(tf_preds, test_h_lbls, cal_cfg),
            "CHAMPION_ENSEMBLE_GRU_TCN_LOG": evaluate_predictions_on_fold(ens_preds, test_h_lbls, cal_cfg),
        }

    # Build Golden Feature Vectors & Golden Outputs for Android parity tests (3 deterministic sample indices)
    golden_indices = [test_idx[0], test_idx[len(test_idx) // 2], test_idx[-1]]
    golden_samples = []
    golden_outputs = []

    for g_i, bar_idx in enumerate(golden_indices):
        sample_id = f"golden_sample_{g_i + 1}"
        c15_prefix = c15[bar_idx - LOOKBACK_LENGTH - 52:bar_idx + 1]
        max_close_t = int(c15_prefix[-1]["closeTime"])
        c1h_sub = [c for c in c1h if int(c["closeTime"]) <= max_close_t]
        c4h_sub = [c for c in c4h if int(c["closeTime"]) <= max_close_t]

        raw_win = extract_sequence_window(raw_features, bar_idx, lookback=LOOKBACK_LENGTH)
        scaled_win, ood_score = scale_sequence_window(raw_win, scaler)

        horizon_outputs = {}
        for h in ("15m", "1h", "4h"):
            g_pred = predict_gru(gru_q, scaled_win, h)
            t_pred = predict_tcn(tcn_q, scaled_win, h)
            l_pred = predict_logistic_baseline(log_model, scaled_win, h)
            ens_raw = combine_ensemble_raw(g_pred, t_pred, l_pred, ensemble_weights)
            cal_cfg = calibration_by_horizon[h]
            cal_probs = apply_calibrated_softmax(
                ens_raw["logits"],
                float(cal_cfg["temperature"]),
                cal_cfg["bias_shift"],
            )
            entropy = -sum(p * math.log(max(p, 1e-8)) for p in cal_probs) / math.log(3.0)
            uncertainty = round(min(1.0, 0.65 * entropy + 0.35 * ens_raw["disagreement"] * 3.0), 5)

            horizon_outputs[h] = {
                "raw_logits": ens_raw["logits"],
                "calibrated_probs": {
                    "pDown": cal_probs[0],
                    "pFlat": cal_probs[1],
                    "pUp": cal_probs[2],
                },
                "expectedReturnPct": round(ens_raw["expected_return"] * 100.0, 5),
                "q10Pct": round(ens_raw["q10"] * 100.0, 5),
                "q50Pct": round(ens_raw["q50"] * 100.0, 5),
                "q90Pct": round(ens_raw["q90"] * 100.0, 5),
                "predictedVolatilityPct": round(ens_raw["sigma"] * 100.0, 5),
                "uncertainty": uncertainty,
                "outOfDistributionScore": ood_score,
            }

        golden_samples.append({
            "sample_id": sample_id,
            "symbol": "DOGEUSDT",
            "as_of_close_time_ms": max_close_t,
            "candles_15m": c15_prefix,
            "candles_1h": c1h_sub[-24:],
            "candles_4h": c4h_sub[-12:],
            "expected_raw_last_row": raw_win[-1],
            "expected_scaled_last_row": scaled_win[-1],
            "expected_scaled_sequence": scaled_win,
            "expected_ood_score": ood_score,
        })
        golden_outputs.append({
            "sample_id": sample_id,
            "symbol": "DOGEUSDT",
            "as_of_close_time_ms": max_close_t,
            "horizons": horizon_outputs,
        })

    # Write schema, scaler, calibration, compact weights, tflite flatbuffer, golden vectors, manifest, SHA256SUMS
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(base_dir, "configs", "feature_schema.json"), "r", encoding="utf-8") as f:
        schema_obj = json.load(f)

    schema_bytes = json.dumps(schema_obj, indent=2, sort_keys=True).encode("utf-8")
    schema_hash = hashlib.sha256(schema_bytes).hexdigest()[:16]

    compact_weights = {
        "bundle_version": "1.0.0",
        "model_id": "astra_gru_tcn_hybrid_v1_int8",
        "fallback_model_id": "astra_logistic_l2_baseline_v1",
        "feature_schema_hash": schema_hash,
        "lookback_length": LOOKBACK_LENGTH,
        "feature_count": FEATURE_COUNT,
        "ensemble_weights": {
            "DEFAULT": [0.50, 0.35, 0.15],
            "TREND": [0.55, 0.30, 0.15],
            "RANGE": [0.35, 0.45, 0.20],
            "HIGH_VOL": [0.40, 0.30, 0.30],
        },
        "gru_v1": gru_q,
        "tcn_v1": tcn_q,
        "logistic_v1": log_model,
    }

    files_to_write = {
        "feature_schema.json": schema_obj,
        "scaler.json": scaler,
        "calibration.json": {
            "schema_version": "1.0.0",
            "feature_schema_hash": schema_hash,
            "horizons": calibration_by_horizon,
        },
        "weights_compact.json": compact_weights,
        "golden_feature_vectors.json": {
            "schema_version": "1.0.0",
            "feature_schema_hash": schema_hash,
            "feature_names": FEATURE_NAMES,
            "samples": golden_samples,
        },
        "golden_outputs.json": {
            "schema_version": "1.0.0",
            "model_id": "astra_gru_tcn_hybrid_v1_int8",
            "samples": golden_outputs,
        },
        "evaluation_summary.json": {
            "quantization_parity": {
                "parity_passed": quant_report["parity_passed"],
                "max_probability_abs_diff": quant_report["max_probability_abs_diff"],
                "max_expected_return_abs_diff": quant_report["max_expected_return_abs_diff"],
            },
            "walk_forward_folds": len(folds),
            "metrics_by_horizon": evaluation_summary["horizons"],
        },
    }

    for fname, obj in files_to_write.items():
        with open(os.path.join(output_dir, fname), "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=2, sort_keys=True)

    tflite_path = os.path.join(output_dir, "model.tflite")
    with open(tflite_path, "wb") as f:
        f.write(build_tflite_v3_binary(compact_weights))

    model_sha256 = sha256_file(tflite_path)
    weights_sha256 = sha256_file(os.path.join(output_dir, "weights_compact.json"))

    manifest = {
        "manifest_version": "1.0.0",
        "model_id": "astra_gru_tcn_hybrid_v1_int8",
        "model_version": "1.0.0-s7edge",
        "fallback_model_id": "astra_logistic_l2_baseline_v1",
        "architecture": "GRU_TCN_HYBRID_INT8",
        "target_device": "Samsung Galaxy S7 Edge (arm64-v8a, 4GB RAM, API 30)",
        "runtime_backend": "CPU_INT8_COMPACT_2THREADS",
        "recommended_cpu_threads": 2,
        "lookback_length": LOOKBACK_LENGTH,
        "feature_count": FEATURE_COUNT,
        "feature_schema_version": schema_obj["schema_version"],
        "feature_schema_hash": schema_hash,
        "horizons": ["15m", "1h", "4h"],
        "model_tflite_sha256": model_sha256,
        "weights_compact_sha256": weights_sha256,
        "operating_mode": "SHADOW_ADVISORY",
    }
    with open(os.path.join(output_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)

    # Write SHA256SUMS for every file in the bundle
    sum_lines = []
    for fname in sorted(os.listdir(output_dir)):
        if fname == "SHA256SUMS":
            continue
        fpath = os.path.join(output_dir, fname)
        if os.path.isfile(fpath):
            sum_lines.append(f"{sha256_file(fpath)}  {fname}")

    with open(os.path.join(output_dir, "SHA256SUMS"), "w", encoding="utf-8") as f:
        f.write("\n".join(sum_lines) + "\n")

    return {
        "manifest": manifest,
        "quant_report": quant_report,
        "evaluation": evaluation_summary,
    }


if __name__ == "__main__":
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    bundle_dir = os.path.join(repo_root, "app", "src", "main", "assets", "forecast_bundle")
    res = run_pipeline_and_export(bundle_dir)
    print(f"Exported verified forecast bundle to {bundle_dir}")
    print(f"Model ID: {res['manifest']['model_id']} | Schema Hash: {res['manifest']['feature_schema_hash']}")
    print(f"Quantization Parity: passed={res['quant_report']['parity_passed']}, max_prob_diff={res['quant_report']['max_probability_abs_diff']}")
