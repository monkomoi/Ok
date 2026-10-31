import os
import sys
import unittest

SRC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if SRC_DIR not in sys.path:
    sys.path.insert(0, SRC_DIR)

from fetch_klines import generate_symbol_candles_15m
from build_labels import compute_cost_threshold, build_labels_for_index


class TestLabelGeneration(unittest.TestCase):
    def test_cost_threshold_monotonic_across_horizons(self):
        t15 = compute_cost_threshold("15m")
        t1h = compute_cost_threshold("1h")
        t4h = compute_cost_threshold("4h")
        self.assertGreater(t15, 0.0015)
        self.assertGreater(t1h, t15)
        self.assertGreater(t4h, t1h)

    def test_cost_aware_directional_classification(self):
        candles = generate_symbol_candles_15m("DOGEUSDT", num_bars=120)
        lbl = build_labels_for_index(candles, 50)
        for h in ("15m", "1h", "4h"):
            self.assertIn(h, lbl)
            info = lbl[h]
            ret = info["forward_return"]
            tau = info["tau_cost"]
            y_dir = info["y_dir"]
            if ret > tau:
                self.assertEqual(y_dir, 2)
            elif ret < -tau:
                self.assertEqual(y_dir, 0)
            else:
                self.assertEqual(y_dir, 1)
            self.assertGreater(info["realized_vol"], 0.0)


if __name__ == "__main__":
    unittest.main()
