#!/usr/bin/env python3
"""
Deterministic multi-timeframe (15m, 1h, 4h) closed-candle dataset generator & loader
for Astra Trader V5.1 Forecasting Engine off-device training and parity verification.
"""
import math
from typing import Dict, List, Any

INTERVAL_MS = {
    "15m": 15 * 60 * 1000,
    "1h": 60 * 60 * 1000,
    "4h": 4 * 60 * 60 * 1000,
}

BASE_PRICES = {
    "BTCUSDT": 64200.0,
    "ETHUSDT": 2640.0,
    "SOLUSDT": 176.5,
    "DOGEUSDT": 0.1445,
}


def generate_symbol_candles_15m(
    symbol: str = "DOGEUSDT",
    num_bars: int = 960,
    start_open_time_ms: int = 1710000000000,
    seed_offset: int = 0
) -> List[Dict[str, Any]]:
    """
    Generates a deterministic, regime-rich (TREND_UP, RANGE, BREAKOUT, HIGH_VOL, TREND_DOWN)
    series of closed 15m candles with realistic microstructure (spread/imbalance/funding).
    """
    base_price = BASE_PRICES.get(symbol, 100.0)
    sym_hash = sum(ord(ch) * (i + 1) for i, ch in enumerate(symbol)) + seed_offset
    step_ms = INTERVAL_MS["15m"]
    candles: List[Dict[str, Any]] = []
    price = base_price

    for idx in range(num_bars):
        open_time = start_open_time_ms + idx * step_ms
        close_time = open_time + step_ms - 1

        # Deterministic regime cycle across 240-bar macro blocks
        phase = (idx + sym_hash) % 240
        if phase < 60:
            drift = 0.0014
            vol_scale = 0.0042
        elif phase < 120:
            drift = -0.0001
            vol_scale = 0.0026
        elif phase < 180:
            drift = -0.0015
            vol_scale = 0.0048
        else:
            drift = 0.0021 if (phase % 20 < 12) else -0.0008
            vol_scale = 0.0065

        wave1 = math.sin(0.17 * idx + sym_hash * 0.03)
        wave2 = math.cos(0.053 * idx + sym_hash * 0.07)
        autocorr = 0.35 * math.sin(0.09 * (idx - 1) + sym_hash * 0.03)
        ret = drift + vol_scale * (0.55 * wave1 + 0.30 * wave2 + 0.15 * autocorr)

        open_p = price
        close_p = max(open_p * math.exp(ret), base_price * 0.15)
        intrabar_span = abs(close_p - open_p) + open_p * vol_scale * (0.65 + 0.25 * abs(wave1))
        upper_wick = intrabar_span * (0.35 + 0.15 * (1.0 + wave2) * 0.5)
        lower_wick = intrabar_span * (0.35 + 0.15 * (1.0 - wave2) * 0.5)
        high_p = max(open_p, close_p) + upper_wick
        low_p = max(min(open_p, close_p) - lower_wick, base_price * 0.05)

        base_vol = 125000.0 if symbol == "DOGEUSDT" else 1850.0
        vol_mult = 1.0 + 0.65 * abs(ret) / max(vol_scale, 1e-6) + 0.25 * max(0.0, wave1)
        volume = round(base_vol * vol_mult, 4)
        quote_volume = round(volume * close_p, 4)

        bid_qty = round(base_vol * (0.12 + 0.04 * max(0.0, wave1 + drift * 120.0)), 4)
        ask_qty = round(base_vol * (0.12 + 0.04 * max(0.0, -wave1 - drift * 120.0)), 4)
        funding_rate = round(0.0001 + 0.00015 * math.tanh(drift * 400.0), 6)

        candles.append({
            "symbol": symbol,
            "interval": "15m",
            "openTime": open_time,
            "closeTime": close_time,
            "open": round(open_p, 6),
            "high": round(high_p, 6),
            "low": round(low_p, 6),
            "close": round(close_p, 6),
            "volume": volume,
            "quoteVolume": quote_volume,
            "isClosed": True,
            "bidQty": bid_qty,
            "askQty": ask_qty,
            "fundingRate": funding_rate,
        })
        price = close_p

    return candles


def aggregate_higher_timeframe(
    candles_15m: List[Dict[str, Any]],
    factor: int,
    interval_label: str
) -> List[Dict[str, Any]]:
    """
    Aggregates closed 15m candles into closed higher-timeframe (1h = 4 bars, 4h = 16 bars)
    strictly using completed blocks so no partial bar is ever marked closed.
    """
    aggregated: List[Dict[str, Any]] = []
    n = len(candles_15m)
    for start in range(0, n - factor + 1, factor):
        chunk = candles_15m[start:start + factor]
        if not all(c.get("isClosed", False) for c in chunk):
            continue
        aggregated.append({
            "symbol": chunk[0]["symbol"],
            "interval": interval_label,
            "openTime": chunk[0]["openTime"],
            "closeTime": chunk[-1]["closeTime"],
            "open": chunk[0]["open"],
            "high": max(c["high"] for c in chunk),
            "low": min(c["low"] for c in chunk),
            "close": chunk[-1]["close"],
            "volume": round(sum(c["volume"] for c in chunk), 4),
            "quoteVolume": round(sum(c["quoteVolume"] for c in chunk), 4),
            "isClosed": True,
        })
    return aggregated


def load_multi_timeframe_dataset(
    symbol: str = "DOGEUSDT",
    num_bars_15m: int = 960,
    start_open_time_ms: int = 1710000000000
) -> Dict[str, List[Dict[str, Any]]]:
    c15 = generate_symbol_candles_15m(symbol=symbol, num_bars=num_bars_15m, start_open_time_ms=start_open_time_ms)
    c1h = aggregate_higher_timeframe(c15, factor=4, interval_label="1h")
    c4h = aggregate_higher_timeframe(c15, factor=16, interval_label="4h")
    return {
        "15m": c15,
        "1h": c1h,
        "4h": c4h,
    }
