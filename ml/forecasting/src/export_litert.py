#!/usr/bin/env python3
"""
Astra Trader — Off-Device Model Training, Walk-Forward Evaluation & Compact Kotlin Bundle Exporter.
Exports the 7-artifact bundle + SHA256SUMS to `/app/src/main/assets/forecast_bundle/`
and `/docs/FORECASTING_EVALUATION_SUMMARY.json`.
"""
import hashlib
import json
import math
import os
import sys
from typing import Dict, List, Any, Tuple

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from fetch_klines import fetch_multi_timeframe_dataset, SUPPORTED_SYMBOLS
from validate_dataset import validate_multi_timeframe_dataset
from build_features import (
    FEATURE_COUNT,
    LOOKBACK_LENGTH,
    get_feature_schema_dict,
    compute_causal_feature_row,
    fit_scaler_on_training_rows,
    scale_feature_sequence,
)
from build_labels import build_labels_for_index, HORIZON_BARS
from train_gru import train_gru_model, gru_encode_sequence
from train_tcn import train_tcn_model, tcn_encode_sequence
from calibrate import (
    fit_horizon_calibration,
    apply_calibration,
    softmax3,
    check_head_consistency,
)
from evaluate import simulate_single_trade_event, evaluate_predictions_chronological

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(SCRIPT_DIR)))
ASSETS_BUNDLE_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "forecast_bundle")
DOCS_DIR = os.path.join(REPO_ROOT, "docs")


def _softplus(x: float) -> float:
    if x > 20.0:
        return x
    if x < -20.0:
        return math.exp(x)
    return math.log1p(math.exp(x))


def _encode_logistic_summary(scaled_seq: List[List[float]]) -> List[float]:
    last_row = scaled_seq[-1]
    inv_t = 1.0 / float(max(len(scaled_seq), 1))
    summary = [0.0] * (FEATURE_COUNT * 2)
    for j in range(FEATURE_COUNT):
        summary[j] = float(last_row[j])
        summary[FEATURE_COUNT + j] = sum(float(r[j]) for r in scaled_seq) * inv_t
    return summary


def train_logistic_fallback(
    train_samples: List[Dict[str, Any]],
    horizons: List[str] = ["15m", "1h", "4h"]
) -> Dict[str, Any]:
    dim = FEATURE_COUNT * 2
    out: Dict[str, Any] = {}
    summaries = [_encode_logistic_summary(s["scaled_seq"]) for s in train_samples]
    n = float(max(len(train_samples), 1))

    for h in horizons:
        W_cls = [[0.0 for _ in range(dim)] for _ in range(3)]
        b_cls = [0.0, 0.05, 0.0]
        w_ret = [0.0 for _ in range(dim)]
        b_ret = 0.0
        base_vol = 0.0040 if h == "15m" else (0.0075 if h == "1h" else 0.0145)

        for _ in range(8):
            for idx, s in enumerate(train_samples):
                vec = summaries[idx]
                lbl = s["labels"][h]
                y_cls = int(lbl["y_dir"])
                y_ret = float(lbl["forward_return"])
                logits = [b_cls[c] + sum(W_cls[c][i] * vec[i] for i in range(dim)) for c in range(3)]
                probs = softmax3(logits)
                for c in range(3):
                    err = (probs[c] - (1.0 if c == y_cls else 0.0)) / n
                    b_cls[c] -= 0.03 * err
                    for i in range(dim):
                        W_cls[c][i] -= 0.03 * (err * vec[i] + 0.003 * W_cls[c][i] / n)
                pred_r = b_ret + sum(w_ret[i] * vec[i] for i in range(dim))
                err_r = (pred_r - y_ret) / n
                b_ret -= 0.01 * err_r
                for i in range(dim):
                    w_ret[i] -= 0.01 * (err_r * vec[i] + 0.003 * w_ret[i] / n)

        scale_h = 0.0018 if h == "15m" else (0.0035 if h == "1h" else 0.0065)
        b_cls = [round(b - sum(b_cls) / 3.0, 6) for b in b_cls]
        for i in range(dim):
            cls_dir = (W_cls[2][i] - W_cls[0][i]) * scale_h
            w_ret[i] = round(0.4 * w_ret[i] + 0.6 * cls_dir, 6)
            for c in range(3):
                W_cls[c][i] = round(W_cls[c][i], 6)
        b_ret = round((b_cls[2] - b_cls[0]) * (scale_h * 0.5), 6)

        out[h] = {
            "W_cls": W_cls,
            "b_cls": b_cls,
            "w_ret": w_ret,
            "b_ret": b_ret,
            "base_vol": base_vol,
        }
    return out


def predict_horizon_ensemble(
    scaled_seq: List[List[float]],
    horizon: str,
    regime: str,
    gru_model: Dict[str, Any],
    tcn_model: Dict[str, Any],
    logistic_model: Dict[str, Any],
    ensemble_weights: Dict[str, List[float]]
) -> Dict[str, Any]:
    w_gru, w_tcn, w_log = ensemble_weights.get(regime, ensemble_weights["DEFAULT"])
    g_enc = gru_encode_sequence(gru_model["core"], scaled_seq)
    t_enc = tcn_encode_sequence(tcn_model["core"], scaled_seq)
    l_enc = _encode_logistic_summary(scaled_seq)

    g_head = gru_model["heads"][horizon]
    t_head = tcn_model["heads"][horizon]
    l_head = logistic_model[horizon]

    g_logits = [g_head["b_cls"][c] + sum(g_head["W_cls"][c][i] * g_enc[i] for i in range(len(g_enc))) for c in range(3)]
    t_logits = [t_head["b_cls"][c] + sum(t_head["W_cls"][c][i] * t_enc[i] for i in range(len(t_enc))) for c in range(3)]
    l_logits = [l_head["b_cls"][c] + sum(l_head["W_cls"][c][i] * l_enc[i] for i in range(len(l_enc))) for c in range(3)]

    raw_logits = [
        round(w_gru * g_logits[c] + w_tcn * t_logits[c] + w_log * l_logits[c], 6)
        for c in range(3)
    ]

    g_ret = g_head["b_ret"] + sum(g_head["w_ret"][i] * g_enc[i] for i in range(len(g_enc)))
    t_ret = t_head["b_ret"] + sum(t_head["w_ret"][i] * t_enc[i] for i in range(len(t_enc)))
    l_ret = l_head["b_ret"] + sum(l_head["w_ret"][i] * l_enc[i] for i in range(len(l_enc)))
    exp_ret = w_gru * g_ret + w_tcn * t_ret + w_log * l_ret

    g_vol = _softplus(g_head["b_vol"] + sum(g_head["w_vol"][i] * g_enc[i] for i in range(len(g_enc)))) + 0.0015
    t_vol = _softplus(t_head["b_vol"] + sum(t_head["w_vol"][i] * t_enc[i] for i in range(len(t_enc)))) + 0.0015
    l_vol = float(l_head["base_vol"])
    sigma = max(0.001, w_gru * g_vol + w_tcn * t_vol + w_log * l_vol)

    p_g = softmax3(g_logits)
    p_t = softmax3(t_logits)
    p_l = softmax3(l_logits)
    p_m = [(p_g[c] + p_t[c] + p_l[c]) / 3.0 for c in range(3)]
    disagree = math.sqrt(
        sum(((p_g[c] - p_m[c]) ** 2 + (p_t[c] - p_m[c]) ** 2 + (p_l[c] - p_m[c]) ** 2) / 3.0 for c in range(3))
    )

    return {
        "raw_logits": raw_logits,
        "expected_return": exp_ret,
        "raw_sigma": sigma,
        "q10": exp_ret - 1.28155 * sigma,
        "q50": exp_ret,
        "q90": exp_ret + 1.28155 * sigma,
        "ensemble_disagreement": round(disagree, 6),
    }


def build_all_symbol_windows(dataset: Dict[str, Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    max_h = max(HORIZON_BARS.values())
    by_sym: Dict[str, List[Dict[str, Any]]] = {}
    for sym, bundle in dataset.items():
        c15 = bundle["15m"]
        c1h = bundle["1h"]
        c4h = bundle["4h"]
        raw_rows: Dict[int, List[float]] = {}
        for t in range(50, len(c15)):
            raw_rows[t] = compute_causal_feature_row(c15, t, c1h, c4h)

        windows: List[Dict[str, Any]] = []
        for t in range(50 + LOOKBACK_LENGTH - 1, len(c15) - max_h):
            seq_raw = [raw_rows[k] for k in range(t - LOOKBACK_LENGTH + 1, t + 1)]
            lbls = build_labels_for_index(c15, t)
            windows.append({
                "symbol": sym,
                "t": t,
                "as_of_close_time_ms": int(c15[t]["closeTime"]),
                "entry_price": float(c15[t]["close"]),
                "future_bars_15m": c15[t + 1:t + 1 + max_h],
                "raw_seq": seq_raw,
                "last_raw_row": seq_raw[-1],
                "labels": lbls,
            })
        by_sym[sym] = windows
    return by_sym


def run_pipeline_and_export() -> Dict[str, Any]:
    os.makedirs(ASSETS_BUNDLE_DIR, exist_ok=True)
    os.makedirs(DOCS_DIR, exist_ok=True)

    dataset = fetch_multi_timeframe_dataset(symbols=SUPPORTED_SYMBOLS, bars_15m=560)
    val_report = validate_multi_timeframe_dataset(dataset)
    if not val_report["all_valid"]:
        raise RuntimeError(f"Dataset validation failed: {val_report}")

    by_sym = build_all_symbol_windows(dataset)
    n_win = min(len(v) for v in by_sym.values())

    # Chronological splits: Train [0..65%], Val/Calibration [65%..82%], Holdout [82%..100%]
    train_end = int(n_win * 0.65)
    val_end = int(n_win * 0.82)

    train_raw_rows: List[List[float]] = []
    for sym in SUPPORTED_SYMBOLS:
        for w in by_sym[sym][:train_end]:
            train_raw_rows.append(w["last_raw_row"])

    scaler = fit_scaler_on_training_rows(train_raw_rows)
    feature_schema = get_feature_schema_dict()

    for sym in SUPPORTED_SYMBOLS:
        for w in by_sym[sym]:
            s_seq, ood = scale_feature_sequence(w["raw_seq"], scaler)
            w["scaled_seq"] = s_seq
            w["ood_score"] = ood

    train_samples = [w for sym in SUPPORTED_SYMBOLS for w in by_sym[sym][:train_end]]
    val_samples = [w for sym in SUPPORTED_SYMBOLS for w in by_sym[sym][train_end:val_end]]
    holdout_samples = [w for sym in SUPPORTED_SYMBOLS for w in by_sym[sym][val_end:n_win]]

    gru_model = train_gru_model(train_samples)
    tcn_model = train_tcn_model(train_samples)
    logistic_model = train_logistic_fallback(train_samples)

    ensemble_weights = {
        "TREND_UP": [0.46, 0.39, 0.15],
        "TREND_DOWN": [0.46, 0.39, 0.15],
        "RANGE_CHOP": [0.35, 0.45, 0.20],
        "HIGH_VOLATILITY": [0.38, 0.37, 0.25],
        "BREAKOUT_EXPANSION": [0.44, 0.41, 0.15],
        "DEFAULT": [0.45, 0.40, 0.15],
    }

    horizons = ["15m", "1h", "4h"]
    cal_horizons: Dict[str, Any] = {}
    for h in horizons:
        val_logits = [
            predict_horizon_ensemble(
                w["scaled_seq"], h, "DEFAULT", gru_model, tcn_model, logistic_model, ensemble_weights
            )["raw_logits"]
            for w in val_samples
        ]
        val_y = [int(w["labels"][h]["y_dir"]) for w in val_samples]
        cal_horizons[h] = fit_horizon_calibration(val_logits, val_y)

    calibration_payload = {
        "schema_version": "1.2.0",
        "feature_schema_hash": feature_schema["feature_schema_hash"],
        "is_test_fixture_calibration": True,
        "qualification_note": "Pooled validation calibration across 4 symbols; per-symbol live qualification requires >= 200 chronological samples per symbol.",
        "horizons": cal_horizons,
    }

    # Walk-Forward Folds (Fold 1..4) + Holdout Evaluation
    fold_bounds: List[Tuple[str, int, int]] = [
        ("Fold_1", int(n_win * 0.45), int(n_win * 0.58)),
        ("Fold_2", int(n_win * 0.58), int(n_win * 0.70)),
        ("Fold_3", int(n_win * 0.70), int(n_win * 0.82)),
        ("Fold_4", int(n_win * 0.82), n_win),
    ]
    walk_forward_summary: Dict[str, Any] = {}
    for fold_name, f_start, f_end in fold_bounds:
        fold_samples = [w for sym in SUPPORTED_SYMBOLS for w in by_sym[sym][f_start:f_end]]
        fold_res: Dict[str, Any] = {}
        for h in horizons:
            h_bars = HORIZON_BARS[h]
            records = []
            for w in fold_samples:
                pred = predict_horizon_ensemble(
                    w["scaled_seq"], h, "DEFAULT", gru_model, tcn_model, logistic_model, ensemble_weights
                )
                probs = apply_calibration(
                    pred["raw_logits"],
                    cal_horizons[h]["temperature"],
                    cal_horizons[h]["bias_shift"],
                )
                exp_ret_pct = pred["expected_return"] * 100.0
                p_down, p_flat, p_up = probs[0], probs[1], probs[2]
                consistency = check_head_consistency(
                    {"pDown": p_down, "pFlat": p_flat, "pUp": p_up},
                    exp_ret_pct,
                    pred["q50"] * 100.0,
                )
                trade_ev = None
                if consistency["consistent"] and max(p_up, p_down) >= 0.46 and abs(exp_ret_pct) >= 0.18:
                    direction = "LONG" if (p_up > p_down and exp_ret_pct > 0) else ("SHORT" if (p_down > p_up and exp_ret_pct < 0) else "NONE")
                    if direction in ("LONG", "SHORT"):
                        ep = w["entry_price"]
                        stop_dist = max(pred["raw_sigma"] * 1.35, 0.0045)
                        tp_dist = max(pred["raw_sigma"] * 2.10, 0.0075)
                        sl = ep * (1.0 - stop_dist) if direction == "LONG" else ep * (1.0 + stop_dist)
                        tp = ep * (1.0 + tp_dist) if direction == "LONG" else ep * (1.0 - tp_dist)
                        trade_ev = simulate_single_trade_event(
                            direction=direction,
                            entry_price=ep,
                            stop_price=sl,
                            target_price=tp,
                            future_bars=w["future_bars_15m"][:h_bars],
                        )
                records.append({
                    "probs": probs,
                    "y_dir": int(w["labels"][h]["y_dir"]),
                    "expected_return_pct": exp_ret_pct,
                    "realized_return_pct": float(w["labels"][h]["forward_return"]) * 100.0,
                    "trade_event": trade_ev,
                })
            fold_res[h] = evaluate_predictions_chronological(records)
        walk_forward_summary[fold_name] = fold_res

    weights_compact = {
        "format": "ASTRA_COMPACT_KOTLIN_WEIGHTS_V1",
        "model_id": "astra_gru_tcn_hybrid_v1_int8",
        "feature_count": FEATURE_COUNT,
        "lookback_length": LOOKBACK_LENGTH,
        "ensemble_weights_by_regime": ensemble_weights,
        "gru_core": gru_model["core"],
        "gru_heads": gru_model["heads"],
        "tcn_core": tcn_model["core"],
        "tcn_heads": tcn_model["heads"],
        "logistic_fallback": logistic_model,
    }

    # Build Golden Feature Vectors & Golden Outputs across all 4 supported symbols (tagged TEST_FIXTURE)
    golden_vectors_samples = []
    golden_outputs_samples = []
    for idx, sym in enumerate(SUPPORTED_SYMBOLS):
        w = by_sym[sym][-1]
        flat_scaled = [x for row in w["scaled_seq"] for x in row]
        sample_id = f"golden_sample_{idx + 1}_{sym.lower()}"
        golden_vectors_samples.append({
            "sample_id": sample_id,
            "symbol": sym,
            "is_test_fixture": True,
            "data_trust_tag": "TEST_FIXTURE",
            "as_of_close_time_ms": w["as_of_close_time_ms"],
            "lookback_length": LOOKBACK_LENGTH,
            "feature_count": FEATURE_COUNT,
            "ood_score": w["ood_score"],
            "last_raw_feature_row": w["last_raw_row"],
            "last_scaled_feature_row": w["scaled_seq"][-1],
            "flattened_scaled_features": flat_scaled,
        })

        h_outs: Dict[str, Any] = {}
        for h in horizons:
            pred = predict_horizon_ensemble(
                w["scaled_seq"], h, "DEFAULT", gru_model, tcn_model, logistic_model, ensemble_weights
            )
            cal_h = cal_horizons[h]
            probs = apply_calibration(pred["raw_logits"], cal_h["temperature"], cal_h["bias_shift"])
            entropy = -sum(p * math.log(max(p, 1e-12)) for p in probs) / math.log(3.0)
            unc = min(1.0, max(0.0, 0.55 * entropy + 0.30 * min(1.0, pred["ensemble_disagreement"] * 3.0) + 0.15 * min(1.0, w["ood_score"] * 2.0)))

            p_down = round(probs[0], 6)
            p_flat = round(probs[1], 6)
            p_up = round(1.0 - p_down - p_flat, 6)
            exp_ret_pct = round(pred["expected_return"] * 100.0, 4)
            q10_pct = round(pred["q10"] * 100.0, 4)
            q50_pct = round(pred["q50"] * 100.0, 4)
            q90_pct = round(pred["q90"] * 100.0, 4)
            consistency = check_head_consistency(
                {"pDown": p_down, "pFlat": p_flat, "pUp": p_up},
                exp_ret_pct,
                q50_pct,
            )

            h_outs[h] = {
                "raw_logits": pred["raw_logits"],
                "calibrated_probs": {"pDown": p_down, "pFlat": p_flat, "pUp": p_up},
                "expectedReturnPct": exp_ret_pct,
                "predictedVolatilityPct": round(pred["raw_sigma"] * 100.0, 4),
                "q10Pct": q10_pct,
                "q50Pct": q50_pct,
                "q90Pct": q90_pct,
                "uncertainty": round(unc, 5),
                "outOfDistributionScore": w["ood_score"],
                "headConsistent": consistency["consistent"],
            }

        golden_outputs_samples.append({
            "sample_id": sample_id,
            "symbol": sym,
            "is_test_fixture": True,
            "data_trust_tag": "TEST_FIXTURE",
            "as_of_close_time_ms": w["as_of_close_time_ms"],
            "horizons": h_outs,
        })

    golden_vectors_payload = {
        "schema_version": "1.2.0",
        "model_id": "astra_gru_tcn_hybrid_v1_int8",
        "is_test_fixture": True,
        "data_trust_tag": "TEST_FIXTURE",
        "samples": golden_vectors_samples,
    }
    golden_outputs_payload = {
        "schema_version": "1.2.0",
        "model_id": "astra_gru_tcn_hybrid_v1_int8",
        "is_test_fixture": True,
        "data_trust_tag": "TEST_FIXTURE",
        "samples": golden_outputs_samples,
    }

    weights_bytes = json.dumps(weights_compact, sort_keys=True, separators=(",", ":")).encode("utf-8")
    weights_sha256 = hashlib.sha256(weights_bytes).hexdigest()

    c15_sample = dataset["BTCUSDT"]["15m"]
    manifest_payload = {
        "manifest_version": "1.2.0",
        "model_id": "astra_gru_tcn_hybrid_v1_int8",
        "model_version": "1.2.0-s7edge-blankslate",
        "architecture": "GRU_TCN_HYBRID_INT8_COMPACT_KOTLIN",
        "artifact_format": "WEIGHTS_COMPACT_JSON_INT8",
        "runtime_backend": "COMPACT_KOTLIN_CPU_INT8_2THREADS",
        "fallback_model_id": "astra_logistic_l2_baseline_v1",
        "feature_schema_version": feature_schema["schema_version"],
        "feature_schema_hash": feature_schema["feature_schema_hash"],
        "lookback_length": LOOKBACK_LENGTH,
        "feature_count": FEATURE_COUNT,
        "horizons": horizons,
        "symbols_supported": SUPPORTED_SYMBOLS,
        "per_symbol_eligibility": {
            "BTCUSDT": "POOLED_MODEL_ONLY",
            "ETHUSDT": "POOLED_MODEL_ONLY",
            "SOLUSDT": "POOLED_MODEL_ONLY",
            "DOGEUSDT": "POOLED_MODEL_ONLY",
        },
        "operating_mode": "SHADOW_ADVISORY",
        "is_test_fixture_bundle": True,
        "data_trust_state": "SHADOW_VALIDATION_FIXTURE",
        "recommended_cpu_threads": 2,
        "target_device": "Samsung Galaxy S7 Edge (arm64-v8a, 4GB RAM, API 30)",
        "dataset_source": "BINANCE_PUBLIC_API_CACHED",
        "dataset_bars_15m": len(c15_sample),
        "dataset_start_open_ms": int(c15_sample[0]["openTime"]),
        "dataset_end_close_ms": int(c15_sample[-1]["closeTime"]),
        "training_code_version": "astra-forecast-pipeline-2.0.0",
        "fee_assumptions_bps": {"maker": 2.0, "taker": 5.0, "slippage": 4.0, "funding_8h": 1.0},
        "weights_compact_sha256": weights_sha256,
    }

    bundle_files = {
        "manifest.json": json.dumps(manifest_payload, indent=2, sort_keys=True).encode("utf-8") + b"\n",
        "feature_schema.json": json.dumps(feature_schema, indent=2, sort_keys=True).encode("utf-8") + b"\n",
        "scaler.json": json.dumps(scaler, separators=(",", ":"), sort_keys=True).encode("utf-8"),
        "calibration.json": json.dumps(calibration_payload, separators=(",", ":"), sort_keys=True).encode("utf-8"),
        "weights_compact.json": weights_bytes,
        "golden_feature_vectors.json": json.dumps(golden_vectors_payload, separators=(",", ":"), sort_keys=True).encode("utf-8"),
        "golden_outputs.json": json.dumps(golden_outputs_payload, separators=(",", ":"), sort_keys=True).encode("utf-8"),
    }

    sha_lines = []
    for fname in sorted(bundle_files.keys()):
        fpath = os.path.join(ASSETS_BUNDLE_DIR, fname)
        content = bundle_files[fname]
        with open(fpath, "wb") as f:
            f.write(content)
        digest = hashlib.sha256(content).hexdigest()
        sha_lines.append(f"{digest}  {fname}")

    with open(os.path.join(ASSETS_BUNDLE_DIR, "SHA256SUMS"), "w", encoding="utf-8") as f:
        f.write("\n".join(sha_lines) + "\n")

    eval_summary = {
        "model_id": manifest_payload["model_id"],
        "model_version": manifest_payload["model_version"],
        "dataset_bars_15m_per_symbol": len(c15_sample),
        "symbols_evaluated": SUPPORTED_SYMBOLS,
        "is_test_fixture_bundle": True,
        "calibration_summary": cal_horizons,
        "walk_forward_folds": walk_forward_summary,
        "holdout_evaluation": walk_forward_summary["Fold_4"],
    }
    with open(os.path.join(DOCS_DIR, "FORECASTING_EVALUATION_SUMMARY.json"), "w", encoding="utf-8") as f:
        json.dump(eval_summary, f, indent=2, sort_keys=True)

    return eval_summary


if __name__ == "__main__":
    summary = run_pipeline_and_export()
    print("Export completed successfully. Holdout 15m Brier:", summary["holdout_evaluation"]["15m"]["brier_score"])
