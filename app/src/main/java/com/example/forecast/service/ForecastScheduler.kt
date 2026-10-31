package com.example.forecast.service

import com.example.domain.CandleBar
import com.example.domain.DeviceHealthSnapshot
import com.example.domain.RegimeLabel
import com.example.domain.TradeSide
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.forecast.engine.ForecastCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-Driven Closed-Candle Forecast Scheduler (`ForecastScheduler.kt`, Section 10.2 & 12.1).
 * - Triggered ONLY when a new closed `15m` candle arrives (`(symbol, closeTimeMs)` deduplication)
 *   or when the user explicitly switches symbol / requests a refresh.
 * - Executes on a dedicated single-thread background dispatcher (`Dispatchers.Default.limitedParallelism(1)`)
 *   completely outside `AstraTradingCoordinator.globalCapitalMutex`.
 * - Automatically throttles/skips secondary inference if S7 Edge battery temperature `>= maxThermalCelsius`
 *   or available RAM `< minAvailableRamMb`.
 */
class ForecastScheduler(
    private val forecastCoordinator: ForecastCoordinator,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) {

    private val lastEvaluatedCandleCloseBySymbol = ConcurrentHashMap<String, Long>()
    private val cachedReportBySymbol = ConcurrentHashMap<String, MultiHorizonForecastReport>()

    suspend fun evaluateOnClosedCandleEvent(
        symbol: String,
        candles15m: List<CandleBar>,
        candles1h: List<CandleBar>,
        candles4h: List<CandleBar> = emptyList(),
        primaryRegime: RegimeLabel? = null,
        candidateSide: TradeSide = TradeSide.NONE,
        spreadPct: BigDecimal = BigDecimal("0.0004"),
        fundingRate: BigDecimal = BigDecimal("0.0001"),
        orderBookImbalance: Double? = null,
        operatingMode: ForecastOperatingMode = ForecastOperatingMode.SHADOW,
        deviceHealth: DeviceHealthSnapshot? = null,
        maxThermalCelsius: Float = 41.0f,
        minAvailableRamMb: Long = 450L,
        forceRefresh: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): MultiHorizonForecastReport = withContext(inferenceDispatcher) {
        val latestClosedTime = candles15m.filter { it.isClosed }.maxOfOrNull { it.closeTime } ?: 0L

        // Deduplicate on (symbol, latestClosedTime) unless forceRefresh is true or cached report expired
        if (!forceRefresh && latestClosedTime > 0L) {
            val prevClose = lastEvaluatedCandleCloseBySymbol[symbol]
            val cached = cachedReportBySymbol[symbol]
            val primary15m = cached?.primaryResult15m
            if (prevClose == latestClosedTime &&
                cached != null &&
                cached.operatingMode == operatingMode &&
                primary15m != null &&
                !primary15m.isExpired(nowMs)
            ) {
                return@withContext cached
            }
        }

        // S7 Edge Thermal & Memory Pressure Guard
        if (deviceHealth != null) {
            val isOverheated = deviceHealth.batteryTempCelsius >= maxThermalCelsius || deviceHealth.thermalWarning
            val isLowRam = deviceHealth.availableRamMb in 1 until minAvailableRamMb || deviceHealth.lowRamWarning
            if (isOverheated || isLowRam) {
                forecastCoordinator.markThrottledByDeviceHealth(deviceHealth)
                val cached = cachedReportBySymbol[symbol]
                if (cached != null) {
                    return@withContext cached
                }
            }
        }

        val report = forecastCoordinator.evaluateSymbolForecast(
            symbol = symbol,
            candles15m = candles15m,
            candles1h = candles1h,
            candles4h = candles4h,
            primaryRegime = primaryRegime,
            candidateSide = candidateSide,
            spreadPct = spreadPct,
            fundingRate = fundingRate,
            orderBookImbalance = orderBookImbalance,
            operatingMode = operatingMode,
            deviceHealth = deviceHealth,
            nowMs = nowMs
        )

        if (latestClosedTime > 0L) {
            lastEvaluatedCandleCloseBySymbol[symbol] = latestClosedTime
        }
        cachedReportBySymbol[symbol] = report
        report
    }
}
