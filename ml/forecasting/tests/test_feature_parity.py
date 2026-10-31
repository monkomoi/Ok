import json
import os
import sys
import unittest

SRC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if SRC_DIR not in sys.path:
    sys.path.insert(0, SRC_DIR)

from build_features import FEATURE_NAMES, FEATURE_COUNT, LOOKBACK_LENGTH, compute_causal_feature_row_at


class TestFeatureParity(unittest.TestCase):
    def test_schema_matches_feature_pipeline(self):
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        schema_path = os.path.join(base_dir, "configs", "feature_schema.json")
        with open(schema_path, "r", encoding="utf-8") as f:
            schema = json.load(f)

        self.assertEqual(schema["lookback_length"], LOOKBACK_LENGTH)
        self.assertEqual(schema["feature_count"], FEATURE_COUNT)
        schema_names = [item["name"] for item in schema["features"]]
        self.assertEqual(schema_names, FEATURE_NAMES)

    def test_golden_feature_vectors_reproducibility(self):
        repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
        golden_path = os.path.join(
            repo_root, "app", "src", "main", "assets", "forecast_bundle", "golden_feature_vectors.json"
        )
        if not os.path.exists(golden_path):
            from export_litert import run_pipeline_and_export
            run_pipeline_and_export(os.path.dirname(golden_path))

        with open(golden_path, "r", encoding="utf-8") as f:
            golden = json.load(f)

        self.assertEqual(golden["feature_names"], FEATURE_NAMES)
        for sample in golden["samples"]:
            recomputed = compute_causal_feature_row_at(
                sample["candles_15m"],
                sample["candles_1h"],
                sample["candles_4h"],
            )
            expected = sample["expected_raw_last_row"]
            self.assertEqual(len(recomputed), FEATURE_COUNT)
            for idx, (r_val, e_val) in enumerate(zip(recomputed, expected)):
                self.assertAlmostEqual(
                    r_val,
                    e_val,
                    places=5,
                    msg=f"Mismatch in {sample['sample_id']} feature[{idx}] ({FEATURE_NAMES[idx]})",
                )


if __name__ == "__main__":
    unittest.main()
