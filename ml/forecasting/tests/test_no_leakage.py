import copy
import os
import sys
import unittest

SRC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if SRC_DIR not in sys.path:
    sys.path.insert(0, SRC_DIR)

from fetch_klines import load_multi_timeframe_dataset
from build_features import build_raw_feature_matrix, fit_scaler_on_train
from split_walk_forward import generate_walk_forward_splits


class TestNoLeakage(unittest.TestCase):
    def test_future_candle_mutation_does_not_change_features_at_t(self):
        ds = load_multi_timeframe_dataset(symbol="DOGEUSDT", num_bars_15m=200)
        c15_orig = copy.deepcopy(ds["15m"])
        c1h_orig = copy.deepcopy(ds["1h"])
        c4h_orig = copy.deepcopy(ds["4h"])

        feats_orig = build_raw_feature_matrix(c15_orig, c1h_orig, c4h_orig)

        # Mutate all candles strictly after index t = 110
        t = 110
        c15_mut = copy.deepcopy(c15_orig)
        for future_idx in range(t + 1, len(c15_mut)):
            c15_mut[future_idx]["open"] *= 3.5
            c15_mut[future_idx]["high"] *= 4.0
            c15_mut[future_idx]["low"] *= 0.2
            c15_mut[future_idx]["close"] *= 3.8
            c15_mut[future_idx]["volume"] *= 50.0

        # Also mutate any 1h / 4h candle whose closeTime > closeTime_15m[t]
        t_close_ms = int(c15_orig[t]["closeTime"])
        c1h_mut = copy.deepcopy(c1h_orig)
        for c in c1h_mut:
            if int(c["closeTime"]) > t_close_ms:
                c["close"] *= 9.9
        c4h_mut = copy.deepcopy(c4h_orig)
        for c in c4h_mut:
            if int(c["closeTime"]) > t_close_ms:
                c["close"] *= 9.9

        feats_mut = build_raw_feature_matrix(c15_mut, c1h_mut, c4h_mut)

        for idx in range(0, t + 1):
            self.assertEqual(
                feats_orig[idx],
                feats_mut[idx],
                f"Leakage detected at bar {idx} <= {t}",
            )

    def test_walk_forward_purge_and_embargo_ordering(self):
        valid_indices = list(range(63, 700))
        folds = generate_walk_forward_splits(valid_indices, num_folds=4, purge_bars=16, embargo_bars=8)
        self.assertGreaterEqual(len(folds), 3)
        for fold in folds:
            tr_max = max(fold["train_indices"])
            val_min = min(fold["val_indices"])
            val_max = max(fold["val_indices"])
            te_min = min(fold["test_indices"])
            self.assertLessEqual(tr_max + 16, val_min)
            self.assertLessEqual(val_max + 16, te_min)

    def test_scaler_fits_only_on_train(self):
        train_rows = [[1.0, 2.0], [3.0, 4.0], [5.0, 6.0]]
        scaler = fit_scaler_on_train(train_rows)
        self.assertAlmostEqual(scaler["means"][0], 3.0, places=5)
        self.assertAlmostEqual(scaler["means"][1], 4.0, places=5)


if __name__ == "__main__":
    unittest.main()
