package com.example.quant

import com.example.domain.KlineBar
import com.example.domain.MarketRegime
import com.example.domain.PositionSizingResult
import com.example.domain.QuantRiskSummary
import com.example.domain.SymbolFilter
import com.example.domain.TechnicalSignalResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic quantitative mathematics, multi-timeframe regime classification (4h / 1h / 15m),
 * volatility-adjusted breakout & pullback entry band calculation, and loss-budget position sizing
 * for small-capital USDⓈ-M Futures accounts ($50 - $500 USDT).
 */
object QuantMathEngine {

    /**
     * Rounds a raw price to the exchange tickSize increment.
     * For BUY limit orders we round down (`FLOOR`), for SELL limit orders we round up (`CEILING`),
     * and for neutral / stop trigger reference prices we round `HALF_UP`.
     */
    fun roundToTickSize(
        price: Double,
        tickSize: Double,
        roundingMode: RoundingMode = RoundingMode.HALF_UP
    ): Double {
        if (tickSize <= 0.0 || price <= 0.0) return price
        val priceBd = BigDecimal.valueOf(price)
        val tickBd = BigDecimal.valueOf(tickSize)
        val steps = priceBd.divide(tickBd, 0, roundingMode)
        return steps.multiply(tickBd).toDouble()
    }

    /**
     * Rounds a raw quantity strictly DOWN (`RoundingMode.DOWN`) to the exchange stepSize
     * so we never exceed the authorized loss budget R or margin allocation.
     */
    fun roundToStepSize(quantity: Double, stepSize: Double): Double {
        if (stepSize <= 0.0 || quantity <= 0.0) return 0.0
        val qtyBd = BigDecimal.valueOf(quantity)
        val stepBd = BigDecimal.valueOf(stepSize)
        val steps = qtyBd.divide(stepBd, 0, RoundingMode.DOWN)
        return steps.multiply(stepBd).toDouble()
    }

    /**
     * Loss-budget position sizing for small-capital accounts:
     *   R = min(equity * riskPerTradePct, remainingDailyRiskBudgetUsd)
     *   d = |entryPrice - stopLossPrice| / entryPrice
     *   N_risk = R / (d + roundTripFeeRate + expectedSlippageRate)
     *   N_lev  = availableMarginUsd * min(maxAllowedLeverage, 5) * 0.90
     *   N      = min(N_risk, N_lev)
     *
     * CRITICAL RULE: If the resulting notional N < minNotional (with 5% buffer),
     * the trade is REJECTED rather than rounding up to minNotional and violating R.
     */
    fun calculatePositionSizing(
        symbolFilter: SymbolFilter,
        equityUsd: Double,
        availableMarginUsd: Double,
        riskPerTradePct: Double, // e.g. 0.005 for 0.50%
        remainingDailyRiskBudgetUsd: Double,
        entryPrice: Double,
        stopLossPrice: Double,
        takeProfitPrice: Double,
        maxAllowedLeverage: Int = 5,
        roundTripFeeRate: Double = 0.0009,     // 9 bps (maker/taker blended)
        expectedSlippageRate: Double = 0.0005, // 5 bps
        maintenanceMarginRate: Double = 0.005, // 0.5% MMR
        adaptiveRiskMultiplier: Double = 1.0
    ): PositionSizingResult {
        if (!symbolFilter.isEligiblePerpetual()) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Symbol ${symbolFilter.symbol} is not an eligible USDⓈ-M Perpetual contract",
                quantity = 0.0,
                notionalUsd = 0.0,
                effectiveLeverage = 1,
                estimatedMaxLossUsd = 0.0,
                estimatedLiquidationPrice = 0.0,
                stopDistancePct = 0.0,
                liquidationBufferPct = 0.0,
                rewardToRiskRatio = 0.0
            )
        }

        if (equityUsd <= 10.0 || availableMarginUsd <= 5.0 || entryPrice <= 0.0) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Insufficient account equity ($${"%.2f".format(equityUsd)}) or free margin ($${"%.2f".format(availableMarginUsd)})",
                quantity = 0.0,
                notionalUsd = 0.0,
                effectiveLeverage = 1,
                estimatedMaxLossUsd = 0.0,
                estimatedLiquidationPrice = 0.0,
                stopDistancePct = 0.0,
                liquidationBufferPct = 0.0,
                rewardToRiskRatio = 0.0
            )
        }

        val clampedRiskPct = max(0.001, min(0.015, riskPerTradePct)) * max(0.25, min(1.0, adaptiveRiskMultiplier))
        val riskBudgetUsd = min(equityUsd * clampedRiskPct, remainingDailyRiskBudgetUsd)
        if (riskBudgetUsd <= 0.05) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Daily or per-trade risk budget exhausted ($${"%.2f".format(riskBudgetUsd)} remaining)",
                quantity = 0.0,
                notionalUsd = 0.0,
                effectiveLeverage = 1,
                estimatedMaxLossUsd = 0.0,
                estimatedLiquidationPrice = 0.0,
                stopDistancePct = 0.0,
                liquidationBufferPct = 0.0,
                rewardToRiskRatio = 0.0
            )
        }

        val stopDistFraction = abs(entryPrice - stopLossPrice) / entryPrice
        val tpDistFraction = abs(takeProfitPrice - entryPrice) / entryPrice
        if (stopDistFraction < 0.0015 || stopDistFraction > 0.08) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Stop distance ${"%.2f".format(stopDistFraction * 100.0)}% outside safe intraday bounds [0.15%, 8.00%]",
                quantity = 0.0,
                notionalUsd = 0.0,
                effectiveLeverage = 1,
                estimatedMaxLossUsd = 0.0,
                estimatedLiquidationPrice = 0.0,
                stopDistancePct = stopDistFraction * 100.0,
                liquidationBufferPct = 0.0,
                rewardToRiskRatio = 0.0
            )
        }

        val totalCostFraction = roundTripFeeRate + expectedSlippageRate
        val netTpFraction = max(0.0, tpDistFraction - totalCostFraction)
        val grossRiskFraction = stopDistFraction + totalCostFraction
        val netRewardToRisk = netTpFraction / grossRiskFraction

        val cappedLeverage = max(1, min(5, maxAllowedLeverage))
        val notionalByRisk = riskBudgetUsd / grossRiskFraction
        val notionalByMargin = availableMarginUsd * cappedLeverage.toDouble() * 0.85
        val targetNotional = min(notionalByRisk, notionalByMargin)

        val rawQty = targetNotional / entryPrice
        val roundedQty = roundToStepSize(rawQty, symbolFilter.stepSize)
        val finalNotional = roundedQty * entryPrice

        val requiredMinNotional = max(symbolFilter.minNotional, 5.0) * 1.05
        if (roundedQty < symbolFilter.minQty || finalNotional < requiredMinNotional) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Sized notional $${"%.2f".format(finalNotional)} (qty=$roundedQty) is below exchange minNotional $${"%.2f".format(requiredMinNotional)} for risk budget R=$${"%.2f".format(riskBudgetUsd)} — trade rejected to prevent over-risking",
                quantity = roundedQty,
                notionalUsd = finalNotional,
                effectiveLeverage = cappedLeverage,
                estimatedMaxLossUsd = finalNotional * grossRiskFraction,
                estimatedLiquidationPrice = 0.0,
                stopDistancePct = stopDistFraction * 100.0,
                liquidationBufferPct = 0.0,
                rewardToRiskRatio = netRewardToRisk
            )
        }

        val isLong = stopLossPrice < entryPrice
        val effLeverageDouble = max(1.0, finalNotional / max(availableMarginUsd, 1.0))
        val effLeverageInt = max(1, min(cappedLeverage, kotlin.math.ceil(effLeverageDouble).toInt()))

        // Approximate isolated liquidation distance: (1 / effLeverage) - maintenanceMarginRate
        val liqDistFraction = max(0.01, (1.0 / effLeverageInt.toDouble()) - maintenanceMarginRate)
        val estLiqPrice = if (isLong) {
            entryPrice * (1.0 - liqDistFraction)
        } else {
            entryPrice * (1.0 + liqDistFraction)
        }
        val liqBufferFraction = abs(entryPrice - estLiqPrice) / entryPrice

        // Safety gate: stop distance must be <= 35% of liquidation distance
        if (stopDistFraction > liqBufferFraction * 0.35) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Stop distance (${"%.2f".format(stopDistFraction * 100)}%) exceeds 35% of liquidation buffer (${"%.2f".format(liqBufferFraction * 100)}%)",
                quantity = roundedQty,
                notionalUsd = finalNotional,
                effectiveLeverage = effLeverageInt,
                estimatedMaxLossUsd = finalNotional * grossRiskFraction,
                estimatedLiquidationPrice = estLiqPrice,
                stopDistancePct = stopDistFraction * 100.0,
                liquidationBufferPct = liqBufferFraction * 100.0,
                rewardToRiskRatio = netRewardToRisk
            )
        }

        if (netRewardToRisk < 1.50) {
            return PositionSizingResult(
                approved = false,
                rejectionReason = "Net reward-to-risk after fees (${"%.2f".format(netRewardToRisk)}R) is below minimum 1.50R",
                quantity = roundedQty,
                notionalUsd = finalNotional,
                effectiveLeverage = effLeverageInt,
                estimatedMaxLossUsd = finalNotional * grossRiskFraction,
                estimatedLiquidationPrice = estLiqPrice,
                stopDistancePct = stopDistFraction * 100.0,
                liquidationBufferPct = liqBufferFraction * 100.0,
                rewardToRiskRatio = netRewardToRisk
            )
        }

        return PositionSizingResult(
            approved = true,
            rejectionReason = null,
            quantity = roundedQty,
            notionalUsd = finalNotional,
            effectiveLeverage = effLeverageInt,
            estimatedMaxLossUsd = finalNotional * grossRiskFraction,
            estimatedLiquidationPrice = roundToTickSize(estLiqPrice, symbolFilter.tickSize),
            stopDistancePct = stopDistFraction * 100.0,
            liquidationBufferPct = liqBufferFraction * 100.0,
            rewardToRiskRatio = netRewardToRisk
        )
    }

    /**
     * Evaluates multi-timeframe regime (4h / 1h) and 15m volatility-scaled breakout / pullback
     * entry bands using causal indicators (EMA20, EMA50, ATR14, RSI14, ADX14, RVOL20).
     */
    fun evaluateMultiTimeframeSignal(
        symbol: String,
        klines15m: List<KlineBar>,
        klines1h: List<KlineBar>,
        klines4h: List<KlineBar>,
        spreadBps: Double,
        fundingRatePct: Double,
        tickSize: Double = 0.1
    ): TechnicalSignalResult {
        if (klines15m.size < 55) {
            return TechnicalSignalResult(
                symbol = symbol,
                regime = MarketRegime.RANGE_CHOP,
                side = "NONE",
                technicalScore = 0.0,
                ema20 = 0.0,
                ema50 = 0.0,
                rsi14 = 50.0,
                adx14 = 0.0,
                atr14 = 0.0,
                atrPct = 0.0,
                relativeVolume = 1.0,
                entryBandLow = 0.0,
                entryBandHigh = 0.0,
                stopLossPrice = 0.0,
                takeProfitPrice = 0.0,
                rationale = "Insufficient 15m bars (${klines15m.size}/55) for indicator warmup"
            )
        }

        val closes15 = klines15m.map { it.close }.toDoubleArray()
        val highs15 = klines15m.map { it.high }.toDoubleArray()
        val lows15 = klines15m.map { it.low }.toDoubleArray()
        val vols15 = klines15m.map { it.volume }.toDoubleArray()

        val lastIdx = closes15.lastIndex
        val price = closes15[lastIdx]
        val ema20 = emaLast(closes15, 20)
        val ema50 = emaLast(closes15, 50)
        val atr14 = atrLast(highs15, lows15, closes15, 14)
        val atrPct = if (price > 0) (atr14 / price) * 100.0 else 0.0
        val rsi14 = rsiLast(closes15, 14)
        val adx14 = adxLast(highs15, lows15, closes15, 14)

        val avgVol20 = vols15.takeLast(21).dropLast(1).average().coerceAtLeast(1e-8)
        val rvol = vols15[lastIdx] / avgVol20

        // Higher-timeframe trend bias (1h and 4h)
        val htf1hBull = if (klines1h.size >= 25) {
            val c1h = klines1h.map { it.close }.toDoubleArray()
            c1h.last() >= emaLast(c1h, 20)
        } else {
            price >= ema50
        }
        val htf4hBull = if (klines4h.size >= 20) {
            val c4h = klines4h.map { it.close }.toDoubleArray()
            c4h.last() >= emaLast(c4h, 20)
        } else {
            ema20 >= ema50
        }

        // Classify regime
        val regime = when {
            spreadBps > 8.0 -> MarketRegime.ILLIQUID_FREEZE
            atrPct > 2.40 -> MarketRegime.HIGH_VOL_SHOCK
            adx14 >= 22.0 && ema20 > ema50 && htf1hBull -> MarketRegime.TREND_UP
            adx14 >= 22.0 && ema20 < ema50 && !htf1hBull -> MarketRegime.TREND_DOWN
            else -> MarketRegime.RANGE_CHOP
        }

        // 20-bar Donchian channel (excluding current bar)
        val priorHigh20 = highs15.slice((lastIdx - 20).coerceAtLeast(0) until lastIdx).maxOrNull() ?: price
        val priorLow20 = lows15.slice((lastIdx - 20).coerceAtLeast(0) until lastIdx).minOrNull() ?: price

        var side = "NONE"
        var score = 35.0
        val reasons = mutableListOf<String>()

        when (regime) {
            MarketRegime.TREND_UP -> {
                val pullbackNearEma20 = abs(price - ema20) <= 0.85 * atr14 && price >= ema20 - 0.25 * atr14
                val breakoutUp = price >= priorHigh20 - 0.15 * atr14 && rvol >= 1.15
                if ((pullbackNearEma20 || breakoutUp) && rsi14 in 46.0..71.0 && fundingRatePct <= 0.05) {
                    side = "LONG"
                    score = 62.0 +
                        min(14.0, (adx14 - 20.0) * 0.6) +
                        min(12.0, (rvol - 1.0) * 10.0) +
                        (if (htf4hBull) 8.0 else 0.0)
                    reasons.add("15m/1h uptrend (ADX=${"%.1f".format(adx14)}, RVOL=${"%.2f".format(rvol)}x)")
                } else {
                    reasons.add("Uptrend active but awaiting pullback/breakout trigger or RSI reset (${"%.1f".format(rsi14)})")
                }
            }
            MarketRegime.TREND_DOWN -> {
                val pullbackNearEma20 = abs(price - ema20) <= 0.85 * atr14 && price <= ema20 + 0.25 * atr14
                val breakoutDown = price <= priorLow20 + 0.15 * atr14 && rvol >= 1.15
                if ((pullbackNearEma20 || breakoutDown) && rsi14 in 29.0..54.0 && fundingRatePct >= -0.05) {
                    side = "SHORT"
                    score = 62.0 +
                        min(14.0, (adx14 - 20.0) * 0.6) +
                        min(12.0, (rvol - 1.0) * 10.0) +
                        (if (!htf4hBull) 8.0 else 0.0)
                    reasons.add("15m/1h downtrend (ADX=${"%.1f".format(adx14)}, RVOL=${"%.2f".format(rvol)}x)")
                } else {
                    reasons.add("Downtrend active but awaiting pullback/breakdown trigger or RSI reset (${"%.1f".format(rsi14)})")
                }
            }
            MarketRegime.HIGH_VOL_SHOCK -> {
                reasons.add("High-volatility shock regime (ATR%=${"%.2f".format(atrPct)}%); standing aside")
            }
            MarketRegime.ILLIQUID_FREEZE -> {
                reasons.add("Spread ${"%.1f".format(spreadBps)} bps exceeds liquidity ceiling (8.0 bps)")
            }
            MarketRegime.RANGE_CHOP -> {
                reasons.add("Low directional conviction (ADX=${"%.1f".format(adx14)}); no trade in chop")
            }
        }

        val bandHalfWidth = max(tickSize, 0.18 * atr14)
        val entryBandLow = roundToTickSize(price - bandHalfWidth, tickSize, RoundingMode.FLOOR)
        val entryBandHigh = roundToTickSize(price + bandHalfWidth, tickSize, RoundingMode.CEILING)

        val stopMultiple = 1.65
        val tpMultiple = 3.10
        val stopPrice = when (side) {
            "LONG" -> roundToTickSize(price - stopMultiple * atr14, tickSize, RoundingMode.FLOOR)
            "SHORT" -> roundToTickSize(price + stopMultiple * atr14, tickSize, RoundingMode.CEILING)
            else -> roundToTickSize(price - stopMultiple * atr14, tickSize, RoundingMode.FLOOR)
        }
        val tpPrice = when (side) {
            "LONG" -> roundToTickSize(price + tpMultiple * atr14, tickSize, RoundingMode.CEILING)
            "SHORT" -> roundToTickSize(price - tpMultiple * atr14, tickSize, RoundingMode.FLOOR)
            else -> roundToTickSize(price + tpMultiple * atr14, tickSize, RoundingMode.CEILING)
        }

        return TechnicalSignalResult(
            symbol = symbol,
            regime = regime,
            side = side,
            technicalScore = min(98.0, max(0.0, score)),
            ema20 = ema20,
            ema50 = ema50,
            rsi14 = rsi14,
            adx14 = adx14,
            atr14 = atr14,
            atrPct = atrPct,
            relativeVolume = rvol,
            entryBandLow = entryBandLow,
            entryBandHigh = entryBandHigh,
            stopLossPrice = stopPrice,
            takeProfitPrice = tpPrice,
            rationale = reasons.joinToString("; ")
        )
    }

    /**
     * Computes portfolio-level empirical performance & risk summary from closed trade PnL percentages.
     * On a blank-slate installation (`closedTradeReturnsPct.isEmpty()`), returns zeroed metrics
     * without inventing synthetic Sharpe or profit factor numbers.
     */
    fun computePortfolioRiskSummary(
        equityUsd: Double,
        dailyRealizedLossUsd: Double,
        dailyLossCapPct: Double,
        riskPerTradePct: Double,
        closedTradeReturnsPct: List<Double>,
        consecutiveLosses: Int,
        cooldownUntilMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): QuantRiskSummary {
        val maxDailyLossUsd = equityUsd * dailyLossCapPct
        val remainingDailyUsd = max(0.0, maxDailyLossUsd - max(0.0, dailyRealizedLossUsd))
        val perTradeBudgetUsd = min(equityUsd * riskPerTradePct, remainingDailyUsd)

        if (closedTradeReturnsPct.isEmpty()) {
            return QuantRiskSummary(
                sampleTradeCount = 0,
                winRatePct = 0.0,
                profitFactor = 0.0,
                expectancyPct = 0.0,
                sharpeAnnualized = 0.0,
                sortinoAnnualized = 0.0,
                maxDrawdownPct = 0.0,
                cvar95Pct = 0.0,
                kellyHalfFractionPct = 0.0,
                perTradeRiskBudgetUsd = perTradeBudgetUsd,
                remainingDailyLossBudgetUsd = remainingDailyUsd,
                consecutiveLosses = consecutiveLosses,
                cooldownActive = cooldownUntilMs > nowMs
            )
        }

        val n = closedTradeReturnsPct.size
        val wins = closedTradeReturnsPct.filter { it > 0.0 }
        val losses = closedTradeReturnsPct.filter { it < 0.0 }
        val winRate = wins.size.toDouble() / n.toDouble()
        val grossWin = wins.sum()
        val grossLoss = abs(losses.sum())
        val profitFactor = if (grossLoss > 1e-8) grossWin / grossLoss else if (grossWin > 0) 3.0 else 0.0
        val meanRet = closedTradeReturnsPct.average()

        var varianceSum = 0.0
        var downsideSum = 0.0
        for (r in closedTradeReturnsPct) {
            val d = r - meanRet
            varianceSum += d * d
            if (r < 0.0) downsideSum += r * r
        }
        val std = sqrt(varianceSum / max(1, n - 1).toDouble()).coerceAtLeast(1e-6)
        val downsideStd = sqrt(downsideSum / n.toDouble()).coerceAtLeast(1e-6)

        // Annualized assuming ~4 intraday trades/day -> ~1460 trade opportunities/year
        val annualFactor = sqrt(min(365.0, n.toDouble() * 12.0))
        val sharpe = if (n >= 5) (meanRet / std) * annualFactor else 0.0
        val sortino = if (n >= 5) (meanRet / downsideStd) * annualFactor else 0.0

        // Max drawdown & CVaR 95%
        var equityCurve = 100.0
        var peak = 100.0
        var maxDdPct = 0.0
        for (r in closedTradeReturnsPct) {
            equityCurve *= (1.0 + r / 100.0)
            if (equityCurve > peak) peak = equityCurve
            val dd = ((peak - equityCurve) / peak) * 100.0
            if (dd > maxDdPct) maxDdPct = dd
        }

        val sortedAsc = closedTradeReturnsPct.sorted()
        val tailCount = max(1, floor(n * 0.05).toInt())
        val cvar95 = abs(sortedAsc.take(tailCount).average())

        val avgWin = if (wins.isNotEmpty()) wins.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) abs(losses.average()) else 1.0
        val b = if (avgLoss > 1e-6) avgWin / avgLoss else 1.0
        val rawKelly = if (b > 0) winRate - ((1.0 - winRate) / b) else 0.0
        val halfKellyPct = max(0.0, min(1.5, rawKelly * 0.5 * 100.0))

        return QuantRiskSummary(
            sampleTradeCount = n,
            winRatePct = winRate * 100.0,
            profitFactor = profitFactor,
            expectancyPct = meanRet,
            sharpeAnnualized = sharpe,
            sortinoAnnualized = sortino,
            maxDrawdownPct = maxDdPct,
            cvar95Pct = cvar95,
            kellyHalfFractionPct = halfKellyPct,
            perTradeRiskBudgetUsd = perTradeBudgetUsd,
            remainingDailyLossBudgetUsd = remainingDailyUsd,
            consecutiveLosses = consecutiveLosses,
            cooldownActive = cooldownUntilMs > nowMs
        )
    }

    private fun emaLast(values: DoubleArray, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var ema = values[0]
        for (i in 1 until values.size) {
            ema = values[i] * k + ema * (1.0 - k)
        }
        return ema
    }

    private fun atrLast(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int): Double {
        if (closes.isEmpty()) return 0.0
        var atr = max(highs[0] - lows[0], 1e-8)
        for (i in 1 until closes.size) {
            val tr = max(
                highs[i] - lows[i],
                max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
            )
            atr = ((atr * (period - 1)) + tr) / period.toDouble()
        }
        return atr
    }

    private fun rsiLast(closes: DoubleArray, period: Int): Double {
        if (closes.size <= 1) return 50.0
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            if (i <= period) {
                avgGain += gain / period.toDouble()
                avgLoss += loss / period.toDouble()
            } else {
                avgGain = (avgGain * (period - 1) + gain) / period.toDouble()
                avgLoss = (avgLoss * (period - 1) + loss) / period.toDouble()
            }
        }
        if (avgLoss <= 1e-12) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun adxLast(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int): Double {
        if (closes.size <= 1) return 20.0
        var smoothTr = max(highs[0] - lows[0], 1e-8)
        var smoothPlusDm = 0.0
        var smoothMinusDm = 0.0
        var adx = 20.0
        for (i in 1 until closes.size) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            val plusDm = if (upMove > downMove && upMove > 0) upMove else 0.0
            val minusDm = if (downMove > upMove && downMove > 0) downMove else 0.0
            val tr = max(
                highs[i] - lows[i],
                max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
            )
            smoothTr = smoothTr - (smoothTr / period.toDouble()) + tr
            smoothPlusDm = smoothPlusDm - (smoothPlusDm / period.toDouble()) + plusDm
            smoothMinusDm = smoothMinusDm - (smoothMinusDm / period.toDouble()) + minusDm

            val plusDi = if (smoothTr > 1e-8) 100.0 * (smoothPlusDm / smoothTr) else 0.0
            val minusDi = if (smoothTr > 1e-8) 100.0 * (smoothMinusDm / smoothTr) else 0.0
            val sumDi = plusDi + minusDi
            val dx = if (sumDi > 1e-8) 100.0 * abs(plusDi - minusDi) / sumDi else 0.0
            adx = ((adx * (period - 1)) + dx) / period.toDouble()
        }
        return adx
    }
}
