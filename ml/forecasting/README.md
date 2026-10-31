# Astra Trader V5.1 — Off-Device Time-Series Forecasting Pipeline (`ml/forecasting`)

This directory contains the reproducible off-device training, walk-forward validation, probability calibration, quantization, and LiteRT/TFLite export pipeline for Astra Trader's on-device Forecasting Engine targeting **Samsung Galaxy S7 Edge (4 GB RAM, 64 GB ROM, LineageOS Android 11 / API 30)**.

## Directory Structure
- `configs/`
  - `feature_schema.json`: Versioned 26-feature specification (`schema_version = "1.0.0"`, `lookback_length = 64`).
  - `gru_v1.yaml`: Compact GRU v1 champion configuration (`hidden_size = 48`, multi-horizon heads `15m`, `1h`, `4h`).
  - `tcn_v1.yaml`: Compact Dilated Causal TCN v1 configuration (`channels = 32`, dilations `[1, 2, 4, 8]`).
  - `lstm_v1.yaml`: Compact LSTM v1 challenger configuration (`hidden_size = 48`).
  - `tiny_transformer_v1.yaml`: Offline experimental causal Tiny Transformer (`d_model = 32`, `nhead = 2`, 1 layer).
- `src/`
  - `fetch_klines.py`: Deterministic historical multi-timeframe (`15m`, `1h`, `4h`) candle generator/fetcher.
  - `validate_dataset.py`: Strict validation of monotonic timestamps, closed-bar integrity, and zero NaN/Inf.
  - `build_features.py`: Exact 26-feature causal tensor computation (`[N, 64, 26]`) with zero future leakage.
  - `build_labels.py`: Cost-aware directional (`DOWN`, `FLAT`, `UP`), return, quantile (`q10`, `q50`, `q90`), and realized volatility labels across `15m`, `1h`, `4h`.
  - `split_walk_forward.py`: Chronological expanding/rolling walk-forward splitter with purge + embargo windows.
  - `train_baselines.py`: Naive persistence, EMA-ATR rule baseline, and L2 Logistic Regression baseline.
  - `train_gru.py`: Compact GRU v1 trainer (champion sequence model).
  - `train_tcn.py`: Compact Causal Dilated TCN v1 trainer (ensemble partner).
  - `train_lstm.py`: Compact LSTM v1 challenger trainer.
  - `train_transformer.py`: Experimental Tiny Causal Transformer trainer (offline evaluation).
  - `calibrate.py`: Out-of-sample Temperature Scaling + Platt/Isotonic calibration and Brier/ECE verifier.
  - `evaluate.py`: Multi-fold walk-forward evaluation across statistical and cost-aware economic metrics.
  - `export_litert.py`: Exports verified model bundle (`manifest.json`, `model.tflite`, `weights_compact.json`, `feature_schema.json`, `scaler.json`, `calibration.json`, `golden_feature_vectors.json`, `golden_outputs.json`, `SHA256SUMS`) to `app/src/main/assets/forecast_bundle/`.
  - `compare_float_int8.py`: Audits FP32 vs. INT8/FP16 quantization parity on golden sequences.
- `tests/`
  - `test_no_leakage.py`: Verifies mutating future candles `(t+1..t+H)` has zero effect on features at `t`.
  - `test_feature_parity.py`: Verifies `golden_feature_vectors.json` against schema and deterministic formulas.
  - `test_label_generation.py`: Verifies cost-aware label thresholds (`tau_cost`) and horizon alignment.
  - `test_export_parity.py`: Verifies exported bundle checksums, TFLite flatbuffer header, and FP32 vs. quantized inference parity.

## Running Pipeline & Tests
```bash
python3 ml/forecasting/src/export_litert.py
python3 -m unittest discover -s ml/forecasting/tests -p "test_*.py" -v
```
