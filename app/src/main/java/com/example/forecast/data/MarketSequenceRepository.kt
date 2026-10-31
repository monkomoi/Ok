package com.example.forecast.data

import com.example.domain.CandleBar
import com.example.forecast.domain.ForecastStatus

/**
 * Validates and aligns multi-timeframe closed candles (`15m`, `1h`, `4h`) for the
 * On-Device Time-Series Forecasting Engine (Section 5 & Section 11.2).
 * - Filters strictly for `isClosed == true` and `closeTime <= asOfCloseTimeMs`.
 * - Rejects duplicate or non-monotonic timestamps, large timestamp gaps, non-positive OHLC, or insufficient bars.
 */
object MarketSequenceRepository {

    const val STEP_15M_MS = 15L * 60L * 1000L
    const val MIN_REQUIRED_15M_BARS = 80 // 64 lookback + >=16 warmup

    data class AlignedMultiTimeframeWindow(
        val isValid: Boolean,
        val failureStatus: ForecastStatus?,
        val failureReasonVi: String?,
        val closed15m: List<CandleBar>,
        val closed1h: List<CandleBar>,
        val closed4h: List<CandleBar>,
        val asOfCloseTimeMs: Long
    )

    fun validateAndAlignClosedCandles(
        candles15m: List<CandleBar>,
        candles1h: List<CandleBar>,
        candles4h: List<CandleBar> = emptyList(),
        asOfCloseTimeMs: Long? = null
    ): AlignedMultiTimeframeWindow {
        val rawClosed15m = candles15m
            .filter { it.isClosed && (asOfCloseTimeMs == null || it.closeTime <= asOfCloseTimeMs) }
            .sortedBy { it.openTime }

        if (rawClosed15m.size < MIN_REQUIRED_15M_BARS) {
            return AlignedMultiTimeframeWindow(
                isValid = false,
                failureStatus = ForecastStatus.INSUFFICIENT_DATA,
                failureReasonVi = "Chưa đủ chuỗi nến đóng 15m (${rawClosed15m.size}/$MIN_REQUIRED_15M_BARS nến) để tạo tensor [1, 64, 26].",
                closed15m = emptyList(),
                closed1h = emptyList(),
                closed4h = emptyList(),
                asOfCloseTimeMs = 0L
            )
        }

        // Verify monotonic timestamps, no duplicates, no gaps in the active 80-bar tail, and valid OHLCV
        val activeTail15m = rawClosed15m.takeLast(maxOf(rawClosed15m.size, MIN_REQUIRED_15M_BARS))
        var prevOpenTime: Long? = null
        for (i in activeTail15m.indices) {
            val bar = activeTail15m[i]
            if (bar.closeTime <= bar.openTime) {
                return AlignedMultiTimeframeWindow(
                    isValid = false,
                    failureStatus = ForecastStatus.FEATURE_INVALID,
                    failureReasonVi = "Nến 15m tại chỉ số $i có closeTime <= openTime (${bar.openTime}..${bar.closeTime}).",
                    closed15m = emptyList(),
                    closed1h = emptyList(),
                    closed4h = emptyList(),
                    asOfCloseTimeMs = 0L
                )
            }
            val o = bar.open.toDouble()
            val h = bar.high.toDouble()
            val l = bar.low.toDouble()
            val c = bar.close.toDouble()
            val v = bar.volume.toDouble()
            if (o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN() || v.isNaN() ||
                o.isInfinite() || h.isInfinite() || l.isInfinite() || c.isInfinite() || v.isInfinite() ||
                o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || v < 0.0 ||
                h < maxOf(o, c) - 1e-8 || l > minOf(o, c) + 1e-8
            ) {
                return AlignedMultiTimeframeWindow(
                    isValid = false,
                    failureStatus = ForecastStatus.FEATURE_INVALID,
                    failureReasonVi = "Nến 15m tại chỉ số $i có giá trị OHLCV không hợp lệ hoặc NaN/Inf.",
                    closed15m = emptyList(),
                    closed1h = emptyList(),
                    closed4h = emptyList(),
                    asOfCloseTimeMs = 0L
                )
            }
            if (prevOpenTime != null) {
                if (bar.openTime <= prevOpenTime) {
                    return AlignedMultiTimeframeWindow(
                        isValid = false,
                        failureStatus = ForecastStatus.FEATURE_INVALID,
                        failureReasonVi = "Phát hiện timestamp nến 15m trùng lặp hoặc không tăng dần tại $i.",
                        closed15m = emptyList(),
                        closed1h = emptyList(),
                        closed4h = emptyList(),
                        asOfCloseTimeMs = 0L
                    )
                }
                // Check gap in the last 65 bars required for the 64-step tensor
                if (i >= activeTail15m.size - 65 && (bar.openTime - prevOpenTime) != STEP_15M_MS) {
                    return AlignedMultiTimeframeWindow(
                        isValid = false,
                        failureStatus = ForecastStatus.INSUFFICIENT_DATA,
                        failureReasonVi = "Chuỗi nến 15m bị khuyết nến (gap=${bar.openTime - prevOpenTime}ms != ${STEP_15M_MS}ms).",
                        closed15m = emptyList(),
                        closed1h = emptyList(),
                        closed4h = emptyList(),
                        asOfCloseTimeMs = 0L
                    )
                }
            }
            prevOpenTime = bar.openTime
        }

        val effectiveAsOfClose = activeTail15m.last().closeTime
        val aligned1h = candles1h
            .filter { it.isClosed && it.closeTime <= effectiveAsOfClose }
            .sortedBy { it.openTime }

        val aligned4h = if (candles4h.isNotEmpty()) {
            candles4h
                .filter { it.isClosed && it.closeTime <= effectiveAsOfClose }
                .sortedBy { it.openTime }
        } else {
            deriveClosed4hFrom15m(activeTail15m, effectiveAsOfClose)
        }

        return AlignedMultiTimeframeWindow(
            isValid = true,
            failureStatus = null,
            failureReasonVi = null,
            closed15m = activeTail15m,
            closed1h = aligned1h,
            closed4h = aligned4h,
            asOfCloseTimeMs = effectiveAsOfClose
        )
    }

    /**
     * Aggregates completed 16-bar (`4h`) blocks from closed `15m` candles when explicit `4h` klines
     * are not passed in, strictly guaranteeing `closeTime <= asOfCloseTimeMs`.
     */
    fun deriveClosed4hFrom15m(closed15m: List<CandleBar>, maxCloseTimeMs: Long): List<CandleBar> {
        val out = mutableListOf<CandleBar>()
        val factor = 16
        var start = 0
        while (start + factor <= closed15m.size) {
            val chunk = closed15m.subList(start, start + factor)
            val closeTime = chunk.last().closeTime
            if (closeTime <= maxCloseTimeMs && chunk.all { it.isClosed }) {
                out.add(
                    CandleBar(
                        symbol = chunk.first().symbol,
                        interval = "4h",
                        openTime = chunk.first().openTime,
                        closeTime = closeTime,
                        open = chunk.first().open,
                        high = chunk.maxOf { it.high },
                        low = chunk.minOf { it.low },
                        close = chunk.last().close,
                        volume = chunk.fold(java.math.BigDecimal.ZERO) { acc, c -> acc.add(c.volume) },
                        quoteVolume = chunk.fold(java.math.BigDecimal.ZERO) { acc, c -> acc.add(c.quoteVolume) },
                        isClosed = true
                    )
                )
            }
            start += factor
        }
        return out
    }
}
