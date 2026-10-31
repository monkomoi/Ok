package com.example.forecast.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Throttles on-device forecast inference on 4GB RAM / Exynos 8890 devices so full
 * sequence inference only runs on new 15m bar boundaries or when explicitly forced,
 * avoiding redundant CPU cycles on every 15-second ticker poll.
 */
class ForecastScheduler(
    private val minIntervalBetweenRunsMs: Long = 30_000L
) {
    private val lastEvaluatedBarCloseBySymbol = ConcurrentHashMap<String, Long>()
    private val lastWallClockEvalBySymbol = ConcurrentHashMap<String, Long>()

    fun shouldRunForecast(
        symbol: String,
        latestBarCloseTimeMs: Long,
        nowWallClockMs: Long = System.currentTimeMillis(),
        forceRefresh: Boolean = false
    ): Boolean {
        if (forceRefresh) return true
        val prevBarClose = lastEvaluatedBarCloseBySymbol[symbol]
        val prevWall = lastWallClockEvalBySymbol[symbol] ?: 0L
        if (prevBarClose == null || latestBarCloseTimeMs > prevBarClose) {
            return true
        }
        return (nowWallClockMs - prevWall) >= minIntervalBetweenRunsMs
    }

    fun markEvaluated(symbol: String, latestBarCloseTimeMs: Long, nowWallClockMs: Long = System.currentTimeMillis()) {
        lastEvaluatedBarCloseBySymbol[symbol] = latestBarCloseTimeMs
        lastWallClockEvalBySymbol[symbol] = nowWallClockMs
    }
}
