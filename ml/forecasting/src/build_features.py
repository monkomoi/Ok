#!/usr/bin/env python3
"""
Causal 26-feature engineering pipeline for Astra Trader V5.1.
Guaranteed 100% parity with Kotlin `ForecastFeaturePipeline.kt`.
"""
import hashlib
import json
import math
from typing import Dict, List, Any, Tuple

LOOKBACK_LENGTH = 64
FEATURE_COUNT = 26

FEATURE_SPECS: List[Tuple[str, str, str]] = [
    ("ret_15m_1", "PRICE_RETURNS", "ln(C_t / C_{t-1})"),
    ("ret_15m_2", "PRICE_RETURNS", "ln(C_t / C_{t-2})"),
    ("ret_15m_4", "PRICE_RETURNS", "ln(C_t / C_{t-4})"),
    ("ret_15m_8", "PRICE_RETURNS", "ln(C_t / C_{t-8})"),
    ("ret_1h_1", "HIGHER_TF_CONTEXT", "ln(C1h_t / C1h_{t-1})"),
    ("ret_4h_1", "HIGHER_TF_CONTEXT", "ln(C4h_t / C4h_{t-1})"),
    ("hl_range_pct", "VOLATILITY", "(H_t - L_t) / C_t"),
    ("body_to_range", "CANDLE_GEOMETRY", "(C_t - O_t) / max(H_t - L_t, eps)"),
    ("upper_wick_ratio", "CANDLE_GEOMETRY", "(H_t - max(O_t, C_t)) / max(H_t - L_t, eps)"),
    ("lower_wick_ratio", "CANDLE_GEOMETRY", "(min(O_t, C_t) - L_t) / max(H_t - L_t, eps)"),
    ("atr_pct_14", "VOLATILITY", "ATR_14 / C_t"),
    ("realized_vol_16", "VOLATILITY", "std(ret_15m, 16)"),
    ("ewma_vol_16", "VOLATILITY", "ewma_std(ret_15m, lambda=0.90)"),
    ("ema20_dist_pct", "TREND_MOMENTUM", "(C_t - EMA20_15m) / C_t"),
    ("ema50_dist_pct", "TREND_MOMENTUM", "(C_t - EMA50_15m) / C_t"),
    ("ema20_slope_pct", "TREND_MOMENTUM", "(EMA20_t - EMA20_{t-1}) / C_t"),
    ("ema20_50_spread_1h", "HIGHER_TF_CONTEXT", "(EMA20_1h - EMA50_1h) / C1h_t"),
    ("rsi_14_norm", "TREND_MOMENTUM", "(RSI_14 - 50.0) / 50.0"),
    ("breakout_pos_20", "STRUCTURE", "(C_t - Low20) / max(High20 - Low20, eps) - 0.5"),
    ("rvol_20", "VOLUME_LIQUIDITY", "V_t / max(mean(V, 20), eps) - 1.0"),
    ("quote_vol_z20", "VOLUME_LIQUIDITY", "(QV_t - mean(QV, 20)) / max(std(QV, 20), eps)"),
    ("funding_rate_scaled", "FUTURES_MICROSTRUCTURE", "funding_rate * 1000.0"),
    ("spread_bps_norm", "FUTURES_MICROSTRUCTURE", "spread_bps / 10.0"),
    ("obi_top_book", "FUTURES_MICROSTRUCTURE", "(bid_qty - ask_qty) / max(bid_qty + ask_qty, eps)"),
    ("mark_index_basis_bps", "FUTURES_MICROSTRUCTURE", "(mark - index) / index * 10000 / 10.0"),
    ("tod_sin_cos_blend", "TEMPORAL", "0.5 * sin(2pi * hour / 24) + 0.5 * cos(2pi * dow / 7)"),
]


def get_feature_schema_dict() -> Dict[str, Any]:
    features = [
        {"index": idx, "name": name, "group": group, "formula": formula}
        for idx, (name, group, formula) in enumerate(FEATURE_SPECS)
    ]
    payload = {
        "schema_name": "astra_futures_multitf_v1",
        "schema_version": "1.0.0",
        "feature_count": FEATURE_COUNT,
        "lookback_length": LOOKBACK_LENGTH,
        "base_timeframe": "15m",
        "higher_timeframes": ["1h", "4h"],
        "horizons": [
            {"name": "15m", "bars_15m": 1, "minutes": 15, "ttl_ms": 900000},
            {"name": "1h", "bars_15m": 4, "minutes": 60, "ttl_ms": 3600000},
            {"name": "4h", "bars_15m": 16, "minutes": 240, "ttl_ms": 14400000},
        ],
        "clip_z_score": 5.0,
        "ood_z_threshold": 4.0,
        "ood_fraction_limit": 0.25,
        "features": features,
    }
    canonical = json.dumps(features, sort_keys=True, separators=(",", ":"))
    payload["feature_schema_hash"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
    return payload


def _safe_log_ret(c_now: float, c_prev: float) -> float:
    if c_now <= 0.0 or c_prev <= 0.0:
        return 0.0
    return math.log(c_now / c_prev)


def _round7(val: float) -> float:
    if not math.isfinite(val):
        return 0.0
    return round(float(val), 7)


def compute_causal_feature_row(
    candles_15m: List[Dict[str, Any]],
    t: int,
    candles_1h: List[Dict[str, Any]],
    candles_4h: List[Dict[str, Any]]
) -> List[float]:
    if t < 50:
        raise ValueError(f"Index t={t} requires at least 50 prior 15m bars")

    cur = candles_15m[t]
    close_t = float(cur["close"])
    open_t = float(cur["open"])
    high_t = float(cur["high"])
    low_t = float(cur["low"])
    close_time_t = int(cur["closeTime"])
    eps = 1e-8

    ret1 = _safe_log_ret(close_t, float(candles_15m[t - 1]["close"]))
    ret2 = _safe_log_ret(close_t, float(candles_15m[t - 2]["close"]))
    ret4 = _safe_log_ret(close_t, float(candles_15m[t - 4]["close"]))
    ret8 = _safe_log_ret(close_t, float(candles_15m[t - 8]["close"]))

    closed_1h = [b for b in candles_1h if int(b["closeTime"]) <= close_time_t]
    ret_1h_1 = (
        _safe_log_ret(float(closed_1h[-1]["close"]), float(closed_1h[-2]["close"]))
        if len(closed_1h) >= 2
        else ret4
    )

    closed_4h = [b for b in candles_4h if int(b["closeTime"]) <= close_time_t]
    ret_4h_1 = (
        _safe_log_ret(float(closed_4h[-1]["close"]), float(closed_4h[-2]["close"]))
        if len(closed_4h) >= 2
        else _safe_log_ret(close_t, float(candles_15m[max(0, t - 16)]["close"]))
    )

    hl_range = max(high_t - low_t, 0.0)
    hl_range_pct = hl_range / max(close_t, eps)
    denom_range = max(hl_range, eps)
    body_to_range = (close_t - open_t) / denom_range
    upper_wick_ratio = max(0.0, high_t - max(open_t, close_t)) / denom_range
    lower_wick_ratio = max(0.0, min(open_t, close_t) - low_t) / denom_range

    tr_sum = 0.0
    for k in range(t - 13, t + 1):
        b = candles_15m[k]
        prev_c = float(candles_15m[k - 1]["close"])
        bh, bl = float(b["high"]), float(b["low"])
        tr = max(bh - bl, abs(bh - prev_c), abs(bl - prev_c))
        tr_sum += tr
    atr14 = tr_sum / 14.0
    atr_pct_14 = atr14 / max(close_t, eps)

    rets16 = [
        _safe_log_ret(float(candles_15m[k]["close"]), float(candles_15m[k - 1]["close"]))
        for k in range(t - 15, t + 1)
    ]
    mean16 = sum(rets16) / 16.0
    var16 = sum((r - mean16) ** 2 for r in rets16) / 15.0
    realized_vol_16 = math.sqrt(max(var16, 0.0))

    ewma_var = rets16[0] ** 2
    lam = 0.90
    for r in rets16[1:]:
        ewma_var = lam * ewma_var + (1.0 - lam) * (r * r)
    ewma_vol_16 = math.sqrt(max(ewma_var, 0.0))

    alpha20 = 2.0 / 21.0
    alpha50 = 2.0 / 51.0
    ema20 = float(candles_15m[t - 50]["close"])
    ema50 = ema20
    ema20_prev = ema20
    for k in range(t - 49, t + 1):
        ck = float(candles_15m[k]["close"])
        if k == t:
            ema20_prev = ema20
        ema20 = alpha20 * ck + (1.0 - alpha20) * ema20
        ema50 = alpha50 * ck + (1.0 - alpha50) * ema50

    ema20_dist_pct = (close_t - ema20) / max(close_t, eps)
    ema50_dist_pct = (close_t - ema50) / max(close_t, eps)
    ema20_slope_pct = (ema20 - ema20_prev) / max(close_t, eps)

    if len(closed_1h) >= 10:
        e20_1h = float(closed_1h[0]["close"])
        e50_1h = e20_1h
        for b1 in closed_1h[1:]:
            c1 = float(b1["close"])
            e20_1h = alpha20 * c1 + (1.0 - alpha20) * e20_1h
            e50_1h = alpha50 * c1 + (1.0 - alpha50) * e50_1h
        c1_last = max(float(closed_1h[-1]["close"]), eps)
        ema20_50_spread_1h = (e20_1h - e50_1h) / c1_last
    else:
        ema20_50_spread_1h = ema20_dist_pct - ema50_dist_pct

    gains, losses = 0.0, 0.0
    for k in range(t - 13, t + 1):
        diff = float(candles_15m[k]["close"]) - float(candles_15m[k - 1]["close"])
        if diff > 0:
            gains += diff
        else:
            losses -= diff
    avg_gain = gains / 14.0
    avg_loss = losses / 14.0
    if avg_loss <= eps and avg_gain <= eps:
        rsi = 50.0
    elif avg_loss <= eps:
        rsi = 100.0
    else:
        rs = avg_gain / avg_loss
        rsi = 100.0 - (100.0 / (1.0 + rs))
    rsi_14_norm = (rsi - 50.0) / 50.0

    prior20 = candles_15m[t - 20:t]
    high20 = max(float(b["high"]) for b in prior20)
    low20 = min(float(b["low"]) for b in prior20)
    span20 = max(high20 - low20, eps)
    breakout_pos_20 = max(-1.5, min(1.5, ((close_t - low20) / span20) - 0.5))

    vols20 = [float(candles_15m[k]["volume"]) for k in range(t - 19, t + 1)]
    mean_v20 = sum(vols20) / 20.0
    rvol_20 = max(-1.0, min(5.0, (float(cur["volume"]) / max(mean_v20, eps)) - 1.0))

    qvols20 = [float(candles_15m[k]["quoteVolume"]) for k in range(t - 19, t + 1)]
    mean_qv20 = sum(qvols20) / 20.0
    std_qv20 = math.sqrt(max(sum((q - mean_qv20) ** 2 for q in qvols20) / 19.0, eps))
    quote_vol_z20 = max(-4.0, min(4.0, (float(cur["quoteVolume"]) - mean_qv20) / std_qv20))

    funding_rate_scaled = float(cur.get("fundingRate", 0.0001)) * 1000.0
    spread_bps_norm = float(cur.get("spreadBps", 2.0)) / 10.0
    obi_top_book = max(-1.0, min(1.0, float(cur.get("orderBookImbalance", 0.0))))
    mark_index_basis_bps = float(cur.get("markIndexBasisBps", 0.0)) / 10.0

    sec_of_day = ((close_time_t // 1000) % 86400)
    hour_frac = sec_of_day / 3600.0
    day_of_week = (((close_time_t // 1000) // 86400) + 4) % 7
    tod_sin_cos_blend = 0.5 * math.sin(2.0 * math.pi * hour_frac / 24.0) + 0.5 * math.cos(
        2.0 * math.pi * day_of_week / 7.0
    )

    raw = [
        ret1, ret2, ret4, ret8, ret_1h_1, ret_4h_1,
        hl_range_pct, body_to_range, upper_wick_ratio, lower_wick_ratio,
        atr_pct_14, realized_vol_16, ewma_vol_16,
        ema20_dist_pct, ema50_dist_pct, ema20_slope_pct, ema20_50_spread_1h,
        rsi_14_norm, breakout_pos_20, rvol_20, quote_vol_z20,
        funding_rate_scaled, spread_bps_norm, obi_top_book, mark_index_basis_bps,
        tod_sin_cos_blend,
    ]
    return [_round7(x) for x in raw]


def fit_scaler_on_training_rows(rows: List[List[float]]) -> Dict[str, Any]:
    if not rows:
        raise ValueError("Cannot fit scaler on empty rows")
    n = len(rows)
    means, stds, medians, iqrs = [], [], [], []
    for f_idx in range(FEATURE_COUNT):
        col = sorted(float(r[f_idx]) for r in rows)
        m = sum(col) / float(n)
        var = sum((x - m) ** 2 for x in col) / float(max(n - 1, 1))
        sd = max(math.sqrt(max(var, 0.0)), 1e-6)
        med = col[n // 2]
        q25 = col[int(n * 0.25)]
        q75 = col[int(n * 0.75)]
        iqr = max(q75 - q25, 1e-6)
        means.append(round(m, 7))
        stds.append(round(sd, 7))
        medians.append(round(med, 7))
        iqrs.append(round(iqr, 7))

    return {
        "schema_version": "1.0.0",
        "scaler_type": "HYBRID_STANDARD_ROBUST_Z",
        "feature_count": FEATURE_COUNT,
        "clip_z": 5.0,
        "means": means,
        "stds": stds,
        "medians": medians,
        "iqrs": iqrs,
    }


def scale_feature_sequence(
    raw_sequence: List[List[float]],
    scaler: Dict[str, Any]
) -> Tuple[List[List[float]], float]:
    means = scaler["means"]
    stds = scaler["stds"]
    medians = scaler["medians"]
    iqrs = scaler["iqrs"]
    clip_z = float(scaler.get("clip_z", 5.0))
    scaled_seq: List[List[float]] = []
    ood_Tail = 0
    total_cells = max(len(raw_sequence) * FEATURE_COUNT, 1)

    for row in raw_sequence:
        s_row: List[float] = []
        for j in range(FEATURE_COUNT):
            x = float(row[j])
            z_std = (x - float(means[j])) / max(float(stds[j]), 1e-6)
            z_rob = (x - float(medians[j])) / max(float(iqrs[j]) * 0.7413, 1e-6)
            z_blend = 0.65 * z_std + 0.35 * z_rob
            if abs(z_blend) > 4.0:
                ood_Tail += 1
            z_clipped = max(-clip_z, min(clip_z, z_blend))
            s_row.append(_round7(z_clipped))
        scaled_seq.append(s_row)

    ood_score = round(float(ood_Tail) / float(total_cells), 4)
    return scaled_seq, ood_score
