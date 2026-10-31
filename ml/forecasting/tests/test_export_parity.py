import hashlib
import json
import os
import sys
import unittest

SRC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if SRC_DIR not in sys.path:
    sys.path.insert(0, SRC_DIR)

from export_litert import run_pipeline_and_export


class TestExportParity(unittest.TestCase):
    def test_exported_bundle_integrity_and_int8_parity(self):
        repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
        bundle_dir = os.path.join(repo_root, "app", "src", "main", "assets", "forecast_bundle")
        res = run_pipeline_and_export(bundle_dir)

        self.assertTrue(res["quant_report"]["parity_passed"])
        self.assertLess(res["quant_report"]["max_probability_abs_diff"], 0.015)
        self.assertLess(res["quant_report"]["max_expected_return_abs_diff"], 0.0005)

        # Verify TFL3 flatbuffer magic header in model.tflite
        tflite_path = os.path.join(bundle_dir, "model.tflite")
        with open(tflite_path, "rb") as f:
            header = f.read(8)
        self.assertEqual(header[4:8], b"TFL3")

        # Verify SHA256SUMS matches all bundle files
        sums_path = os.path.join(bundle_dir, "SHA256SUMS")
        with open(sums_path, "r", encoding="utf-8") as f:
            lines = [ln.strip() for ln in f.readlines() if ln.strip()]

        self.assertGreaterEqual(len(lines), 8)
        for ln in lines:
            expected_sha, fname = ln.split()
            fpath = os.path.join(bundle_dir, fname)
            h = hashlib.sha256()
            with open(fpath, "rb") as bf:
                h.update(bf.read())
            self.assertEqual(h.hexdigest(), expected_sha, f"SHA-256 mismatch for {fname}")

        # Verify golden outputs probabilities sum to 1.0
        with open(os.path.join(bundle_dir, "golden_outputs.json"), "r", encoding="utf-8") as f:
            g_out = json.load(f)
        for sample in g_out["samples"]:
            for h, h_data in sample["horizons"].items():
                probs = h_data["calibrated_probs"]
                total_p = probs["pDown"] + probs["pFlat"] + probs["pUp"]
                self.assertAlmostEqual(total_p, 1.0, places=5)
                self.assertLessEqual(h_data["q10Pct"], h_data["q50Pct"])
                self.assertLessEqual(h_data["q50Pct"], h_data["q90Pct"])


if __name__ == "__main__":
    unittest.main()
