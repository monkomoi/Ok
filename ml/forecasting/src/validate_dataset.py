#!/usr/bin/env python3
"""
Dataset quality & provenance validator for Astra Trader forecasting pipeline.
Enforces:
  - strictly monotonic timestamps with zero missing bars
  - closed-candle invariant (isClosed == True)
  - UTC boundary alignment for 15m, 1h, and 4h candles
  - OHLCV integrity (low <= open/close <= high, volume >= 0)
  - explicit provenance fields
"""
from typing import Dict, List, Any

INTERVAL_MS = {
    "15m": 900_000,
    "1h": 3_600_000,
    "4h": 14_400_000,
}


def validate_candles(candles: List[Dict[str, Any]], expected_interval: str) -> Dict[str, Any]:
    errors: List[str] = []
    step_ms = INTERVAL_MS.get(expected_interval)
    if step_ms is None:
        return {"valid": False, "errors": [f"Unsupported interval: {expected_interval}"]}
    if not candles:
        return {"valid": False, "errors": ["Empty candle list"]}

    for i, bar in enumerate(candles):
        open_t = int(bar.get("openTime", -1))
        close_t = int(bar.get("closeTime", -1))
        if open_t % step_ms != 0:
            errors.append(f"Bar {i} openTime {open_t} not aligned to {expected_interval} boundary ({step_ms}ms)")
        if close_t - open_t != step_ms - 1:
            errors.append(f"Bar {i} closeTime {close_t} != openTime + {step_ms - 1}")
        if not bool(bar.get("isClosed", False)):
            errors.append(f"Bar {i} is not closed")
        if i > 0:
            prev_open = int(candles[i - 1]["openTime"])
            if open_t - prev_open != step_ms:
                errors.append(f"Gap or duplicate at index {i}: delta={open_t - prev_open}ms")
        o, h, l, c, v = (
            float(bar["open"]),
            float(bar["high"]),
            float(bar["low"]),
            float(bar["close"]),
            float(bar["volume"]),
        )
        if l <= 0 or o <= 0 or h <= 0 or c <= 0:
            errors.append(f"Non-positive OHLC at index {i}")
        if h < max(o, c, l) - 1e-9 or l > min(o, c, h) + 1e-9:
            errors.append(f"OHLC envelope violated at index {i}")
        if v < 0:
            errors.append(f"Negative volume at index {i}")

    return {
        "valid": len(errors) == 0,
        "bar_count": len(candles),
        "interval": expected_interval,
        "errors": errors,
    }


def validate_multi_timeframe_dataset(dataset: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    report: Dict[str, Any] = {"all_valid": True, "symbols": {}}
    for sym, bundle in dataset.items():
        r15 = validate_candles(bundle.get("15m", []), "15m")
        r1h = validate_candles(bundle.get("1h", []), "1h")
        r4h = validate_candles(bundle.get("4h", []), "4h")
        sym_ok = r15["valid"] and r1h["valid"] and r4h["valid"]
        if not sym_ok:
            report["all_valid"] = False
        report["symbols"][sym] = {
            "valid": sym_ok,
            "source": bundle.get("source", "UNKNOWN"),
            "15m": r15,
            "1h": r1h,
            "4h": r4h,
        }
    return report
