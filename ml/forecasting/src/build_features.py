#!/usr/bin/env python3
"""
Causal 26-Feature Pipeline for Astra Trader V5.1 Forecasting Engine.
Strictly matches `ForecastFeaturePipeline.kt` on Android within 1e-5 float tolerance.
Zero future leakage: feature vector at index `t` depends ONLY on closed bars `<= t`
and higher-timeframe bars whose `closeTime <= closeTime_15m[t]`.
"""
import math
from typing import Dict, List, Any, Optional, Tuple

FEATURE_NAMES = [
    "ret_1",
    "ret_2",
    "ret_4",
    "ret_8",
    "ret_16",
    "hl_range_pct",
    "co_return_pct",
    "body_ratio",
    "upper_wick_ratio",
    "lower_wick_ratio",
    "ema20_dist",
    "ema50_dist",
    "ema20_slope",
    "rsi_14_norm",
    "macd_hist_pct",
    "bb_percent_b",
    "bb_width_pct",
    "atr_14_pct",
    "ewma_vol_20",
    "rvol_20",
    "htf_1h_ret",
    "htf_4h_ret",
    "tod_sin",
    "tod_cos",
    "order_book_imbalance",
    "funding_rate_bps",
]

LOOKBACK_LENGTH = 64
FEATURE_COUNT = 26
WARMUP_BARS_15M = 50


def safe_log_return(prev_val: float, curr_val: float) -> float:
    if prev_val <= 1e-12 or curr_val <= 1e-12:
        return 0.0
    return math.log(curr_val / prev_val)


def compute_causal_feature_row_at(
    candles_15m_prefix: List[Dict[str, Any]],
    candles_1h_all: List[Dict[str, Any]],
    candles_4h_all: List[Dict[str, Any]],
    order_book_imbalance_override: Optional[float] = None,
    funding_rate_override: Optional[float] = None,
) -> List[float]:
    """
    Computes the 26-feature vector for the LAST bar of `candles_15m_prefix`.
    Because only `candles_15m_prefix` (bars `0..t`) is passed in, and higher-timeframe
    candles are filtered strictly by `closeTime <= current_close_time`, future leakage is
    mathematically impossible.
    """
    t = len(candles_15m_prefix) - 1
    curr = candles_15m_prefix[t]
    curr_close_time = int(curr["closeTime"])

    closes = [float(c["close"]) for c in candles_15m_prefix]
    opens = [float(c["open"]) for c in candles_15m_prefix]
    highs = [float(c["high"]) for c in candles_15m_prefix]
    lows = [float(c["low"]) for c in candles_15m_prefix]
    vols = [float(c["volume"]) for c in candles_15m_prefix]

    c_t = max(closes[t], 1e-8)
    o_t = max(opens[t], 1e-8)
    h_t = highs[t]
    l_t = lows[t]

    # 0..4: Multi-step log returns
    ret_1 = safe_log_return(closes[t - 1], c_t) if t >= 1 else 0.0
    ret_2 = safe_log_return(closes[t - 2], c_t) if t >= 2 else 0.0
    ret_4 = safe_log_return(closes[t - 4], c_t) if t >= 4 else 0.0
    ret_8 = safe_log_return(closes[t - 8], c_t) if t >= 8 else 0.0
    ret_16 = safe_log_return(closes[t - 16], c_t) if t >= 16 else 0.0

    # 5..9: Candle geometry
    span = max(h_t - l_t, 1e-8)
    hl_range_pct = (h_t - l_t) / c_t
    co_return_pct = (c_t - o_t) / o_t
    body_ratio = abs(c_t - o_t) / span
    upper_wick_ratio = max(0.0, h_t - max(o_t, c_t)) / span
    lower_wick_ratio = max(0.0, min(o_t, c_t) - l_t) / span

    # 10..12: EMA20, EMA50, EMA20 slope (computed causally across prefix)
    k20 = 2.0 / 21.0
    k50 = 2.0 / 51.0
    k12 = 2.0 / 13.0
    k26 = 2.0 / 27.0
    k9 = 2.0 / 10.0

    ema_start = max(0, t - 50)
    ema20 = closes[ema_start]
    prev_ema20 = closes[ema_start]
    ema50 = closes[ema_start]
    ema12 = closes[ema_start]
    ema26 = closes[ema_start]
    macd_sig = 0.0

    for i in range(ema_start + 1, t + 1):
        prev_ema20 = ema20
        px = closes[i]
        ema20 = px * k20 + ema20 * (1.0 - k20)
        ema50 = px * k50 + ema50 * (1.0 - k50)
        ema12 = px * k12 + ema12 * (1.0 - k12)
        ema26 = px * k26 + ema26 * (1.0 - k26)
        macd_line = ema12 - ema26
        if i == ema_start + 1:
            macd_sig = macd_line
        else:
            macd_sig = macd_line * k9 + macd_sig * (1.0 - k9)

    ema20_dist = (c_t - ema20) / c_t
    ema50_dist = (c_t - ema50) / c_t
    ema20_slope = (ema20 - prev_ema20) / max(prev_ema20, 1e-8)

    # 13: RSI-14 normalized to [-1, 1] using causal 14-bar average gain/loss
    rsi_win = min(14, t)
    if rsi_win >= 1:
        gains = 0.0
        losses = 0.0
        for i in range(t - rsi_win + 1, t + 1):
            diff = closes[i] - closes[i - 1]
            if diff > 0.0:
                gains += diff
            else:
                losses -= diff
        avg_gain = gains / float(rsi_win)
        avg_loss = losses / float(rsi_win)
        if avg_loss <= 1e-12 and avg_gain <= 1e-12:
            rsi = 50.0
        elif avg_loss <= 1e-12:
            rsi = 100.0
        else:
            rs = avg_gain / avg_loss
            rsi = 100.0 - (100.0 / (1.0 + rs))
    else:
        rsi = 50.0
    rsi_14_norm = (rsi - 50.0) / 50.0

    # 14: MACD histogram normalized by close
    macd_hist_pct = ((ema12 - ema26) - macd_sig) / c_t

    # 15..16: Bollinger %B (centered around 0) and Bollinger Width % (20-bar window)
    bb_slice = closes[max(0, t - 19):t + 1]
    sma20 = sum(bb_slice) / float(len(bb_slice))
    if len(bb_slice) >= 2:
        var20 = sum((x - sma20) ** 2 for x in bb_slice) / float(len(bb_slice) - 1)
        std20 = math.sqrt(max(var20, 0.0))
    else:
        std20 = 0.0
    band_span = max(4.0 * std20, 1e-8)
    bb_lower = sma20 - 2.0 * std20
    bb_percent_b = ((c_t - bb_lower) / band_span) - 0.5
    bb_width_pct = (4.0 * std20) / max(sma20, 1e-8)

    # 17: ATR-14 %
    atr_win = min(14, t)
    if atr_win >= 1:
        trs = []
        for i in range(t - atr_win + 1, t + 1):
            hl = abs(highs[i] - lows[i])
            hc = abs(highs[i] - closes[i - 1])
            lc = abs(lows[i] - closes[i - 1])
            trs.append(max(hl, hc, lc))
        atr14 = sum(trs) / float(len(trs))
    else:
        atr14 = max(h_t - l_t, 0.0)
    atr_14_pct = atr14 / c_t

    # 18: EWMA Volatility (20-bar return window, lambda = 0.94)
    ret_win_start = max(1, t - 19)
    rets_20 = [safe_log_return(closes[i - 1], closes[i]) for i in range(ret_win_start, t + 1)]
    if rets_20:
        ewma_var = rets_20[0] * rets_20[0]
        lam = 0.94
        for r in rets_20[1:]:
            ewma_var = lam * ewma_var + (1.0 - lam) * (r * r)
        ewma_vol_20 = math.sqrt(max(ewma_var, 0.0))
    else:
        ewma_vol_20 = 0.0

    # 19: Log Relative Volume (vs prior 20 closed bars, or inclusive 20 bars if <20)
    vol_slice = vols[max(0, t - 20):t] if t >= 1 else vols[0:1]
    sma_vol20 = sum(vol_slice) / float(len(vol_slice)) if vol_slice else 1.0
    rvol_ratio = vols[t] / max(sma_vol20, 1e-8)
    rvol_20 = math.log(max(rvol_ratio, 1e-4))

    # 20..21: Higher-timeframe closed returns strictly with closeTime <= curr_close_time
    closed_1h = [c for c in candles_1h_all if c.get("isClosed", False) and int(c["closeTime"]) <= curr_close_time]
    if len(closed_1h) >= 2:
        htf_1h_ret = safe_log_return(float(closed_1h[-2]["close"]), float(closed_1h[-1]["close"]))
    else:
        htf_1h_ret = ret_4

    closed_4h = [c for c in candles_4h_all if c.get("isClosed", False) and int(c["closeTime"]) <= curr_close_time]
    if len(closed_4h) >= 2:
        htf_4h_ret = safe_log_return(float(closed_4h[-2]["close"]), float(closed_4h[-1]["close"]))
    else:
        htf_4h_ret = ret_16

    # 22..23: Cyclical UTC time of day
    minute_of_day = ((curr_close_time // 60000) % 1440)
    angle = 2.0 * math.pi * (float(minute_of_day) / 1440.0)
    tod_sin = math.sin(angle)
    tod_cos = math.cos(angle)

    # 24: Order book imbalance (OPTIONAL_MARKET_MICROSTRUCTURE)
    if order_book_imbalance_override is not None:
        obi = float(order_book_imbalance_override)
    elif "bidQty" in curr and "askQty" in curr:
        bq = float(curr["bidQty"])
        aq = float(curr["askQty"])
        obi = (bq - aq) / max(bq + aq, 1e-8)
    else:
        obi = 0.0

    # 25: Funding rate in deca-bps (fundingRate * 1000.0)
    if funding_rate_override is not None:
        fr_scaled = float(funding_rate_override) * 1000.0
    elif "fundingRate" in curr:
        fr_scaled = float(curr["fundingRate"]) * 1000.0
    else:
        fr_scaled = 0.0

    return [
        round(ret_1, 7),
        round(ret_2, 7),
        round(ret_4, 7),
        round(ret_8, 7),
        round(ret_16, 7),
        round(hl_range_pct, 7),
        round(co_return_pct, 7),
        round(body_ratio, 7),
        round(upper_wick_ratio, 7),
        round(lower_wick_ratio, 7),
        round(ema20_dist, 7),
        round(ema50_dist, 7),
        round(ema20_slope, 7),
        round(rsi_14_norm, 7),
        round(macd_hist_pct, 7),
        round(bb_percent_b, 7),
        round(bb_width_pct, 7),
        round(atr_14_pct, 7),
        round(ewma_vol_20, 7),
        round(rvol_20, 7),
        round(htf_1h_ret, 7),
        round(htf_4h_ret, 7),
        round(tod_sin, 7),
        round(tod_cos, 7),
        round(obi, 7),
        round(fr_scaled, 7),
    ]


def build_raw_feature_matrix(
    candles_15m: List[Dict[str, Any]],
    candles_1h: List[Dict[str, Any]],
    candles_4h: List[Dict[str, Any]],
) -> List[List[float]]:
    """
    Computes the causal feature matrix `[N, 26]` for every bar `t` in `candles_15m`.
    Each row `t` is computed strictly from `candles_15m[:t+1]`.
    """
    rows: List[List[float]] = []
    for t in range(len(candles_15m)):
        prefix = candles_15m[:t + 1]
        rows.append(compute_causal_feature_row_at(prefix, candles_1h, candles_4h))
    return rows


def extract_sequence_window(
    raw_feature_matrix: List[List[float]],
    end_index: int,
    lookback: int = LOOKBACK_LENGTH
) -> List[List[float]]:
    """
    Extracts a `[lookback, 26]` sequence ending at `end_index` (inclusive).
    Requires `end_index >= lookback - 1`.
    """
    if end_index < lookback - 1:
        raise ValueError(f"end_index={end_index} < lookback-1 ({lookback - 1})")
    start = end_index - lookback + 1
    return [list(row) for row in raw_feature_matrix[start:end_index + 1]]


def fit_scaler_on_train(
    train_rows: List[List[float]],
    clip_z: float = 5.0
) -> Dict[str, Any]:
    """
    Fits mean, std, median, and IQR/MAD per feature ONLY on the training split.
    Never touches validation or test splits.
    """
    if not train_rows:
        raise ValueError("Empty train_rows passed to fit_scaler_on_train")
    num_features = len(train_rows[0])
    means: List[float] = []
    stds: List[float] = []
    medians: List[float] = []
    iqrs: List[float] = []

    n = float(len(train_rows))
    for f in range(num_features):
        col = [r[f] for r in train_rows]
        mean_v = sum(col) / n
        var_v = sum((x - mean_v) ** 2 for x in col) / max(n - 1.0, 1.0)
        std_v = max(math.sqrt(max(var_v, 0.0)), 1e-5)

        sorted_col = sorted(col)
        mid = len(sorted_col) // 2
        med_v = sorted_col[mid]
        q25 = sorted_col[int(len(sorted_col) * 0.25)]
        q75 = sorted_col[int(len(sorted_col) * 0.75)]
        iqr_v = max(q75 - q25, std_v)

        means.append(round(mean_v, 7))
        stds.append(round(std_v, 7))
        medians.append(round(med_v, 7))
        iqrs.append(round(iqr_v, 7))

    return {
        "schema_version": "1.0.0",
        "scaler_type": "STANDARD_Z_WITH_CLIP",
        "feature_count": num_features,
        "clip_z": clip_z,
        "means": means,
        "stds": stds,
        "medians": medians,
        "iqrs": iqrs,
    }


def scale_sequence_window(
    seq_window: List[List[float]],
    scaler: Dict[str, Any]
) -> Tuple[List[List[float]], float]:
    """
    Applies the frozen training scaler to a `[L, F]` sequence window and computes
    the Out-of-Distribution (OOD) score (fraction of latest-bar features with |z| > 3.8
    combined with RMS z-distance).
    """
    means = scaler["means"]
    stds = scaler["stds"]
    clip_z = float(scaler.get("clip_z", 5.0))
    scaled_window: List[List[float]] = []

    for row in seq_window:
        s_row: List[float] = []
        for f, val in enumerate(row):
            z = (val - means[f]) / max(stds[f], 1e-6)
            z_clipped = max(-clip_z, min(clip_z, z))
            s_row.append(round(z_clipped, 6))
        scaled_window.append(s_row)

    # Compute OOD score on the latest step of the window
    last_raw = seq_window[-1]
    extreme_count = 0
    z_sq_sum = 0.0
    for f, val in enumerate(last_raw):
        raw_z = abs(val - means[f]) / max(stds[f], 1e-6)
        if raw_z > 3.8:
            extreme_count += 1
        z_sq_sum += min(raw_z, 10.0) ** 2
    rms_z = math.sqrt(z_sq_sum / float(len(last_raw)))
    extreme_frac = float(extreme_count) / float(len(last_raw))
    ood_score = round(min(1.0, 0.55 * extreme_frac * 4.0 + 0.45 * max(0.0, (rms_z - 1.5) / 3.5)), 4)

    return scaled_window, ood_score
