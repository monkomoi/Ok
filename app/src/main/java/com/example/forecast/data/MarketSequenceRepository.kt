package com.example.forecast.data

import com.example.domain.KlineBar
import java.util.concurrent.ConcurrentHashMap

data class MicrostructureSnapshot(
    val timestampMs: Long,
    val spreadBps: Double,
    val orderBookImbalanceTop5: Double
)

/**
 * Thread-safe bounded rolling sequence store per symbol for 15m, 1h, and 4h klines
 * plus microstructure snapshots. Avoids unbounded memory growth on 4GB RAM devices.
 */
class MarketSequenceRepository(
    private val maxBars15m: Int = 160,
    private val maxBarsHigherTf: Int = 80
) {
    private val bars15mBySymbol = ConcurrentHashMap<String, List<KlineBar>>()
    private val bars1hBySymbol = ConcurrentHashMap<String, List<KlineBar>>()
    private val bars4hBySymbol = ConcurrentHashMap<String, List<KlineBar>>()
    private val microBySymbol = ConcurrentHashMap<String, MicrostructureSnapshot>()

    fun updateSequences(
        symbol: String,
        klines15m: List<KlineBar>,
        klines1h: List<KlineBar>,
        klines4h: List<KlineBar>,
        spreadBps: Double,
        obiTop5: Double,
        nowMs: Long
    ) {
        if (klines15m.isNotEmpty()) {
            bars15mBySymbol[symbol] = klines15m.sortedBy { it.openTime }.takeLast(maxBars15m)
        }
        if (klines1h.isNotEmpty()) {
            bars1hBySymbol[symbol] = klines1h.sortedBy { it.openTime }.takeLast(maxBarsHigherTf)
        }
        if (klines4h.isNotEmpty()) {
            bars4hBySymbol[symbol] = klines4h.sortedBy { it.openTime }.takeLast(maxBarsHigherTf)
        }
        microBySymbol[symbol] = MicrostructureSnapshot(
            timestampMs = nowMs,
            spreadBps = spreadBps,
            orderBookImbalanceTop5 = obiTop5
        )
    }

    fun get15mBars(symbol: String): List<KlineBar> = bars15mBySymbol[symbol] ?: emptyList()
    fun get1hBars(symbol: String): List<KlineBar> = bars1hBySymbol[symbol] ?: emptyList()
    fun get4hBars(symbol: String): List<KlineBar> = bars4hBySymbol[symbol] ?: emptyList()
    fun getMicrostructure(symbol: String): MicrostructureSnapshot? = microBySymbol[symbol]
}
