#!/usr/bin/env python3
"""
Strict dataset validator for Astra Trader V5.1 Forecasting Engine.
Rejects non-monotonic timestamps, unclosed bars, timestamp gaps, non-positive OHLC, or NaN/Inf.
"""
import math
from typing import Dict, List, Any, Tuple

EXPECTED_STEP_MS = {
    "15m": 15 * 60 * 1000,
    "1h": 60 * 60 * 1000,
    "4h": 4 * 60 * 60 * 1000,
}


def validate_candles(
    candles: List[Dict[str, Any]],
    interval: str = "15m",
    min_required: int = 80
) -> Tuple[bool, List[str]]:
    errors: List[str] = []
    if len(candles) < min_required:
        errors.append(f"INSUFFICIENT_DATA: got {len(candles)} bars, need >= {min_required}")
        return False, errors

    step_ms = EXPECTED_STEP_MS.get(interval, 15 * 60 * 1000)
    prev_open = None

    for idx, c in enumerate(candles):
        if not c.get("isClosed", False):
            errors.append(f"UNCLOSED_BAR at index {idx}")
        open_t = int(c["openTime"])
        close_t = int(c["closeTime"])
        if close_t <= open_t:
            errors.append(f"INVALID_BAR_TIME at index {idx}: openTime={open_t}, closeTime={close_t}")
        if prev_open is not None:
            if open_t <= prev_open:
                errors.append(f"NON_MONOTONIC_OR_DUPLICATE_TIME at index {idx}: {open_t} <= {prev_open}")
            elif (open_t - prev_open) != step_ms:
                errors.append(f"TIMESTAMP_GAP at index {idx}: delta={open_t - prev_open} != {step_ms}")
        prev_open = open_t

        for field in ("open", "high", "low", "close", "volume"):
            val = float(c[field])
            if math.isnan(val) or math.isinf(val):
                errors.append(f"NAN_OR_INF_{field.upper()} at index {idx}")
            if field != "volume" and val <= 0.0:
                errors.append(f"NON_POSITIVE_{field.upper()} at index {idx}: {val}")
            if field == "volume" and val < 0.0:
                errors.append(f"NEGATIVE_VOLUME at index {idx}: {val}")

        if float(c["high"]) < max(float(c["open"]), float(c["close"])) - 1e-9:
            errors.append(f"HIGH_BELOW_BODY at index {idx}")
        if float(c["low"]) > min(float(c["open"]), float(c["close"])) + 1e-9:
            errors.append(f"LOW_ABOVE_BODY at index {idx}")

    return len(errors) == 0, errors
