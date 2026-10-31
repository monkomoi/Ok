#!/usr/bin/env python3
"""
Unit & Parity Test Suite for Astra Trader Off-Device Forecasting Pipeline.
Verifies:
  1. UTC 1h and 4h boundary alignment and provenance fields
  2. Dataset validation and synthetic test provider isolation (`SYNTHETIC_TEST_ONLY`)
  3. Causal 26-feature calculation without lookahead leakage
  4. Multi-horizon cost-aware label construction
  5. Regularized calibration & sample-size qualification (`INSUFFICIENT_SAMPLE_SHADOW_ONLY` when N < 200)
  6. Direction head vs Return/Quantile head semantic consistency across all horizons (`15m`, `1h`, `4h`)
  7. Single-trade barrier simulation & strict equity compounding only on traded events
  8. Bundle export integrity (`SHA256SUMS`, no fake `model.tflite`, `TEST_FIXTURE` tags)
"""
import hashlib
import json
import os
import sys
import unittest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(os.path.dirname(TESTS_DIR), "src")
if SRC_DIR not in sys.path:
    sys.path.insert(0, SRC_DIR)

from fetch_klines import (
    SyntheticTestProvider,
    BinancePublicKlineProvider,
    aggregate_higher_timeframe,
    STEP_1H_MS,
    STEP_4H_MS,
)
from validate_dataset import validate_candles
from build_features import (
    FEATURE_COUNT,
    LOOKBACK_LENGTH,
    compute_causal_feature_row,
    fit_scaler_on_training_rows,
    scale_feature_sequence,
)
from build_labels import build_labels_for_index
from calibrate import fit_horizon_calibration, check_head_consistency
from evaluate import simulate_single_trade_event, evaluate_predictions_chronological
from export_litert import ASSETS_BUNDLE_DIR


class TestForecastingPipeline(unittest.TestCase):
    def setUp(self) -> None:
        self.synth_provider = SyntheticTestProvider()
        self.bars_15m = self.synth_provider.fetch_closed_15m_klines("DOGEUSDT", limit=200)

    def test_01_utc_boundary_alignment_and_provenance(self) -> None:
        for b in self.bars_15m:
            self.assertTrue(b["is_synthetic"])
            self.assertTrue(b["is_fixture"])
            self.assertEqual(b["source"], "SYNTHETIC_TEST_ONLY")
        bars_1h = aggregate_higher_timeframe(self.bars_15m, 4, "1h")
        bars_4h = aggregate_higher_timeframe(self.bars_15m, 16, "4h")
        self.assertGreater(len(bars_1h), 10)
        self.assertGreater(len(bars_4h), 4)
        for b1 in bars_1h:
            self.assertEqual(int(b1["openTime"]) % STEP_1H_MS, 0)
        for b4 in bars_4h:
            self.assertEqual(int(b4["openTime"]) % STEP_4H_MS, 0)

    def test_02_dataset_validation_rejects_gaps(self) -> None:
        res = validate_candles(self.bars_15m, "15m")
        self.assertTrue(res["valid"])
        corrupted = [dict(x) for x in self.bars_15m[:20]]
        corrupted[10]["openTime"] += 900_000
        bad_res = validate_candles(corrupted, "15m")
        self.assertFalse(bad_res["valid"])

    def test_03_causal_features_no_lookahead_leakage(self) -> None:
        bars_1h = aggregate_higher_timeframe(self.bars_15m, 4, "1h")
        bars_4h = aggregate_higher_timeframe(self.bars_15m, 16, "4h")
        row_before = compute_causal_feature_row(self.bars_15m, 80, bars_1h, bars_4h)
        self.assertEqual(len(row_before), FEATURE_COUNT)
        mutated = [dict(b) for b in self.bars_15m]
        for k in range(81, len(mutated)):
            mutated[k]["close"] = mutated[k]["close"] * 5.0
            mutated[k]["high"] = mutated[k]["high"] * 5.0
        bars_1h_m = aggregate_higher_timeframe(mutated, 4, "1h")
        bars_4h_m = aggregate_higher_timeframe(mutated, 16, "4h")
        row_after = compute_causal_feature_row(mutated, 80, bars_1h_m, bars_4h_m)
        self.assertEqual(row_before, row_after)

    def test_04_cost_aware_multi_horizon_labels(self) -> None:
        lbls = build_labels_for_index(self.bars_15m, 70)
        for h in ("15m", "1h", "4h"):
            self.assertIn(h, lbls)
            self.assertIn(lbls[h]["y_dir"], (0, 1, 2))
            self.assertGreater(lbls[h]["tau_cost"], 0.001)

    def test_05_regularized_calibration_and_sample_qualification(self) -> None:
        logits = [[-0.1, 0.2, 0.1] for _ in range(30)]
        y_true = [0] * 30  # extreme single-class small sample
        cal = fit_horizon_calibration(logits, y_true)
        self.assertFalse(cal["live_qualified"])
        self.assertEqual(cal["qualification_status"], "INSUFFICIENT_SAMPLE_SHADOW_ONLY")
        self.assertGreaterEqual(cal["temperature"], 0.75)
        for b in cal["bias_shift"]:
            self.assertLessEqual(abs(b), 0.25)

    def test_06_head_consistency_detector(self) -> None:
        inconsistent = check_head_consistency(
            {"pDown": 0.99, "pFlat": 0.01, "pUp": 0.0},
            expected_return_pct=0.25,
            q50_pct=0.25,
            tau_cost_pct=0.18,
        )
        self.assertFalse(inconsistent["consistent"])
        consistent = check_head_consistency(
            {"pDown": 0.25, "pFlat": 0.50, "pUp": 0.25},
            expected_return_pct=0.02,
            q50_pct=0.02,
            tau_cost_pct=0.18,
        )
        self.assertTrue(consistent["consistent"])

    def test_07_same_candle_sl_tp_conservative_resolution(self) -> None:
        res = simulate_single_trade_event(
            direction="LONG",
            entry_price=100.0,
            stop_price=98.0,
            target_price=103.0,
            future_bars=[{"high": 105.0, "low": 97.0, "close": 104.0}],
        )
        self.assertTrue(res["traded"])
        self.assertEqual(res["outcome_reason"], "SL_AND_TP_SAME_CANDLE_CONSERVATIVE_SL")
        self.assertLess(res["net_return_pct"], 0.0)

    def test_08_non_traded_bars_never_compound_equity(self) -> None:
        records = [
            {
                "probs": [0.2, 0.6, 0.2],
                "y_dir": 1,
                "expected_return_pct": 0.01,
                "realized_return_pct": 0.05,
                "trade_event": None,
            }
            for _ in range(25)
        ]
        ev = evaluate_predictions_chronological(records)
        self.assertEqual(ev["trade_count"], 0)
        self.assertEqual(ev["final_equity_multiplier"], 1.0)
        self.assertEqual(ev["sample_qualification"], "INSUFFICIENT_SAMPLE")

    def test_09_bundle_integrity_and_fixture_tags(self) -> None:
        manifest_path = os.path.join(ASSETS_BUNDLE_DIR, "manifest.json")
        golden_out_path = os.path.join(ASSETS_BUNDLE_DIR, "golden_outputs.json")
        sha_path = os.path.join(ASSETS_BUNDLE_DIR, "SHA256SUMS")
        self.assertTrue(os.path.exists(manifest_path))
        self.assertFalse(os.path.exists(os.path.join(ASSETS_BUNDLE_DIR, "model.tflite")))

        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        self.assertTrue(manifest["is_test_fixture_bundle"])
        self.assertNotIn("model_tflite_sha256", manifest)
        self.assertIn("weights_compact_sha256", manifest)

        with open(golden_out_path, "r", encoding="utf-8") as f:
            golden_out = json.load(f)
        self.assertTrue(golden_out["is_test_fixture"])
        self.assertEqual(len(golden_out["samples"]), 4)
        for sample in golden_out["samples"]:
            self.assertTrue(sample["is_test_fixture"])
            for h, hout in sample["horizons"].items():
                self.assertTrue(hout["headConsistent"], f"Inconsistent head for {sample['symbol']} {h}: {hout}")

        with open(sha_path, "r", encoding="utf-8") as f:
            for line in f.read().strip().splitlines():
                expected_hash, fname = line.split()
                with open(os.path.join(ASSETS_BUNDLE_DIR, fname), "rb") as bf:
                    actual_hash = hashlib.sha256(bf.read()).hexdigest()
                self.assertEqual(expected_hash, actual_hash)


if __name__ == "__main__":
    unittest.main()
