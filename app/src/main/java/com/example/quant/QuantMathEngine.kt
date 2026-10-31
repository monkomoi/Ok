package com.example.quant

import com.example.domain.BacktestSummary
import com.example.domain.CandleBar
import com.example.domain.CapitalTier
import com.example.domain.DataSourceType
import com.example.domain.FeatureSnapshot
import com.example.domain.NetEvEstimate
import com.example.domain.ProbabilityCalibrationReport
import com.example.domain.RegimeLabel
import com.example.domain.SizingDecision
import com.example.domain.SkepticCheckResult
import com.example.domain.SkepticVerdict
import com.example.domain.SymbolFilter
import com.example.domain.TradeSide
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ln

/**
 * MOD-09, MOD-10, MOD-11 · Deterministic Quant Feature, Regime, Net EV, Position Sizing & Accounting Engine.
 * Strictly uses BigDecimal arithmetic for financial/quantity decisions (Section 20.A, B.2).
 */
object QuantMathEngine {

    private val MC = MathContext(12, RoundingMode.HALF_UP)
    private val ZERO = BigDecimal.ZERO
    private val ONE = BigDecimal.ONE
    private val TWO = BigDecimal("2")

    fun resolveCapitalTier(equityUsdt: BigDecimal): CapitalTier {
        return when {
            equityUsdt < BigDecimal("25") -> CapitalTier.TIER_MICRO
            equityUsdt < BigDecimal("200") -> CapitalTier.TIER_SMALL
            equityUsdt < BigDecimal("2000") -> CapitalTier.TIER_GROWTH
            else -> CapitalTier.TIER_LARGE
        }
    }

    fun roundDownToStep(value: BigDecimal, stepSize: BigDecimal): BigDecimal {
        if (value <= ZERO || stepSize <= ZERO) return ZERO
        val steps = value.divideToIntegralValue(stepSize)
        return steps.multiply(stepSize).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }
    }

    fun roundUpToStep(value: BigDecimal, stepSize: BigDecimal): BigDecimal {
        if (value <= ZERO || stepSize <= ZERO) return ZERO
        val divided = value.divide(stepSize, 0, RoundingMode.CEILING)
        return divided.multiply(stepSize).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }
    }

    fun roundPriceToTick(
        price: BigDecimal,
        tickSize: BigDecimal,
        roundingMode: RoundingMode = RoundingMode.HALF_UP
    ): BigDecimal {
        if (price <= ZERO || tickSize <= ZERO) return ZERO
        val ticks = price.divide(tickSize, 0, roundingMode)
        return ticks.multiply(tickSize).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }
    }

    data class PostFillProtectivePrices(
        val fillAvgPrice: BigDecimal,
        val executedQty: BigDecimal,
        val recalculatedStopPrice: BigDecimal,
        val recalculatedTargetPrice: BigDecimal,
        val recalculatedNetBreakevenPrice: BigDecimal
    )

    /**
     * P0 Section 3.1 · Recalculates Stop Loss, Take Profit, and Net Breakeven from the ACTUAL fill price (`avgPrice`)
     * and `executedQty` returned by Binance after Entry execution, rather than stale pre-submission prices.
     */
    fun recalculateProtectivePricesFromActualFill(
        symbolFilter: SymbolFilter,
        side: TradeSide,
        intendedEntryPrice: BigDecimal,
        intendedStopPrice: BigDecimal,
        intendedTargetPrice: BigDecimal,
        actualFillPrice: BigDecimal,
        actualExecutedQty: BigDecimal,
        takerFeeRate: BigDecimal = BigDecimal("0.0005"),
        expectedSlippageRate: BigDecimal = BigDecimal("0.0004"),
        expectedFundingRate: BigDecimal = BigDecimal("0.0001")
    ): PostFillProtectivePrices {
        val fillRef = if (actualFillPrice > ZERO) actualFillPrice else intendedEntryPrice
        val qtyRef = if (actualExecutedQty > ZERO) actualExecutedQty else symbolFilter.minQty
        val stopDist = intendedEntryPrice.subtract(intendedStopPrice).abs().max(symbolFilter.tickSize)
        val targetDist = intendedTargetPrice.subtract(intendedEntryPrice).abs().max(symbolFilter.tickSize.multiply(TWO))

        val rawStop = when (side) {
            TradeSide.LONG -> fillRef.subtract(stopDist).max(symbolFilter.tickSize)
            TradeSide.SHORT -> fillRef.add(stopDist)
            TradeSide.NONE -> intendedStopPrice
        }
        val rawTarget = when (side) {
            TradeSide.LONG -> fillRef.add(targetDist)
            TradeSide.SHORT -> fillRef.subtract(targetDist).max(symbolFilter.tickSize)
            TradeSide.NONE -> intendedTargetPrice
        }

        val cleanStop = roundPriceToTick(
            rawStop,
            symbolFilter.tickSize,
            if (side == TradeSide.LONG) RoundingMode.FLOOR else RoundingMode.CEILING
        )
        val cleanTarget = roundPriceToTick(
            rawTarget,
            symbolFilter.tickSize,
            if (side == TradeSide.LONG) RoundingMode.CEILING else RoundingMode.FLOOR
        )
        val entryCommissionUsdt = fillRef.multiply(qtyRef, MC).multiply(takerFeeRate, MC)
        val fundingUsdt = fillRef.multiply(qtyRef, MC).multiply(expectedFundingRate.abs(), MC)
        val netBe = calculateNetBreakevenPrice(
            side = side,
            entryPrice = fillRef,
            quantity = qtyRef,
            entryCommissionUsdt = entryCommissionUsdt,
            expectedExitFeeRate = takerFeeRate,
            accruedAndExpectedFundingUsdt = fundingUsdt,
            expectedSlippageRate = expectedSlippageRate,
            tickSize = symbolFilter.tickSize
        )
        return PostFillProtectivePrices(
            fillAvgPrice = roundPriceToTick(fillRef, symbolFilter.tickSize),
            executedQty = qtyRef,
            recalculatedStopPrice = cleanStop,
            recalculatedTargetPrice = cleanTarget,
            recalculatedNetBreakevenPrice = netBe
        )
    }

    /**
     * Section 6.2, INV-10, P0 Section 3.3 & P1 Section 3.6 · Position Sizing by Stop-Risk + Fees + Slippage + Funding.
     * - Enforces `MARKET_LOT_SIZE` (`marketStepSize`, `marketMinQty`, `marketMaxQty`) for `MARKET` orders and `LOT_SIZE` for `LIMIT` orders.
     * - Rejects LIVE sizing when `symbolFilter.isRealExchangeFilter == false` (Fail-Closed when real `/fapi/v1/exchangeInfo` was not loaded).
     * - Never uses declared VND capital as exchange equity in LIVE mode.
     * - Links `signalId`, `decisionId`, `sizingId`, and `riskApprovalId`.
     */
    fun calculatePositionSizing(
        symbolFilter: SymbolFilter,
        side: TradeSide,
        equityUsdt: BigDecimal?,
        availableBalanceUsdt: BigDecimal?,
        capitalSource: DataSourceType = DataSourceType.BINANCE_LIVE_VERIFIED,
        isLiveMode: Boolean = false,
        riskPerTradeFraction: BigDecimal,
        remainingDailyRiskBudgetUsdt: BigDecimal,
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
        targetPrice: BigDecimal,
        requestedLeverage: Int,
        takerFeeRate: BigDecimal = BigDecimal("0.0005"),
        estimatedSlippageRate: BigDecimal = BigDecimal("0.0004"),
        estimatedFundingRate: BigDecimal = BigDecimal("0.0001"),
        allowedBalanceUsageFraction: BigDecimal = BigDecimal("0.85"),
        orderType: String = "MARKET",
        signalId: String? = null,
        decisionId: String? = null
    ): SizingDecision {
        val useMarketLot = orderType.equals("MARKET", ignoreCase = true) ||
            orderType.equals("STOP_MARKET", ignoreCase = true) ||
            orderType.equals("TAKE_PROFIT_MARKET", ignoreCase = true)
        val effectiveStepSize = if (useMarketLot && symbolFilter.marketStepSize > ZERO) {
            symbolFilter.marketStepSize.max(symbolFilter.stepSize)
        } else {
            symbolFilter.stepSize
        }
        val effectiveMinQty = if (useMarketLot && symbolFilter.marketMinQty > ZERO) {
            symbolFilter.minQty.max(symbolFilter.marketMinQty)
        } else {
            symbolFilter.minQty
        }
        val effectiveMaxQty = if (useMarketLot && symbolFilter.marketMaxQty > ZERO) {
            symbolFilter.maxQty.min(symbolFilter.marketMaxQty)
        } else {
            symbolFilter.maxQty
        }

        val cleanEntry = roundPriceToTick(entryPrice, symbolFilter.tickSize)
        val cleanStop = roundPriceToTick(
            stopPrice,
            symbolFilter.tickSize,
            if (side == TradeSide.LONG) RoundingMode.FLOOR else RoundingMode.CEILING
        )
        val cleanTarget = roundPriceToTick(targetPrice, symbolFilter.tickSize)
        val minQtyByNotional = if (cleanEntry > ZERO) {
            roundUpToStep(
                symbolFilter.minNotional.divide(cleanEntry, 10, RoundingMode.CEILING),
                effectiveStepSize
            )
        } else {
            effectiveMinQty
        }
        val qMin = effectiveMinQty.max(minQtyByNotional)

        // Requirement 3: If exchange equity or available balance is null/unverified, reject!
        if (equityUsdt == null || availableBalanceUsdt == null || capitalSource == DataSourceType.UNVERIFIED_UNKNOWN) {
            return rejectedSizing(
                symbolFilter.symbol, side, ZERO, ZERO, capitalSource, CapitalTier.TIER_MICRO,
                ZERO, cleanEntry, cleanStop, cleanTarget, qMin, 1,
                "UNVERIFIED_ACCOUNT_EQUITY",
                "UNVERIFIED/UNKNOWN: Chưa xác minh được Equity và Available Balance thực tế từ Binance. Tuyệt đối không dùng vốn khai báo 150.000 VNĐ thay cho số dư sàn (Mục 3).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        if (isLiveMode && capitalSource != DataSourceType.BINANCE_LIVE_VERIFIED) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, resolveCapitalTier(equityUsdt),
                ZERO, cleanEntry, cleanStop, cleanTarget, qMin, 1,
                "LIVE_REQUIRES_VERIFIED_EXCHANGE_BALANCE",
                "Từ chối LIVE: Nguồn số dư hiện tại (${capitalSource.badgeVi}) không phải số dư thực đã xác minh từ Binance LIVE.",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        // P1 Section 3.6: Fail-Closed in LIVE mode if real Exchange Metadata was not loaded from Binance
        if (isLiveMode && !symbolFilter.isRealExchangeFilter) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, resolveCapitalTier(equityUsdt),
                ZERO, cleanEntry, cleanStop, cleanTarget, qMin, 1,
                "UNVERIFIED_EXCHANGE_METADATA",
                "Từ chối LIVE (Fail-Closed Mục 3.6): Chưa nạp được bộ lọc hợp đồng thật (/fapi/v1/exchangeInfo) từ Binance cho ${symbolFilter.symbol}; không dùng defaultSymbolFilter để đặt lệnh thật.",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = false
            )
        }

        if (symbolFilter.status != "TRADING") {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, resolveCapitalTier(equityUsdt),
                ZERO, cleanEntry, cleanStop, cleanTarget, qMin, 1,
                "SYMBOL_NOT_TRADING",
                "Hợp đồng ${symbolFilter.symbol} hiện có trạng thái ${symbolFilter.status} (không phải TRADING).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val tier = resolveCapitalTier(equityUsdt)
        val effectiveLeverage = requestedLeverage.coerceIn(1, minOf(tier.maxLeverageCap, symbolFilter.maxLeverage))

        val baseRiskBudget = equityUsdt.multiply(riskPerTradeFraction, MC)
        val effectiveRiskBudget = baseRiskBudget.min(remainingDailyRiskBudgetUsdt).max(ZERO)

        if (side == TradeSide.NONE || cleanEntry <= ZERO || cleanStop <= ZERO) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "INVALID_INPUT", "Hướng lệnh hoặc mức giá Entry/Stop không hợp lệ.",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        if (cleanEntry < symbolFilter.minPrice || cleanEntry > symbolFilter.maxPrice) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "PRICE_FILTER_VIOLATION",
                "Giá Entry ($cleanEntry) nằm ngoài biên PRICE_FILTER [${symbolFilter.minPrice}, ${symbolFilter.maxPrice}].",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val isStopDirectionValid = when (side) {
            TradeSide.LONG -> cleanStop < cleanEntry && cleanTarget > cleanEntry
            TradeSide.SHORT -> cleanStop > cleanEntry && cleanTarget < cleanEntry
            TradeSide.NONE -> false
        }
        if (!isStopDirectionValid) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "INVALID_SL_TP_DIRECTION", "Quan hệ giá Entry / Stop / Target không hợp lệ với hướng ${side.name}.",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        if (effectiveRiskBudget <= ZERO) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "ZERO_RISK_BUDGET", "Ngân sách rủi ro khả dụng bằng 0 USDT (đã chạm trần lỗ ngày hoặc vốn = 0).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val priceDiffPerUnit = cleanEntry.subtract(cleanStop).abs()
        val feePerUnit = cleanEntry.add(cleanStop).multiply(takerFeeRate, MC)
        val slippagePerUnit = cleanEntry.multiply(estimatedSlippageRate, MC)
        val fundingPerUnit = cleanEntry.multiply(estimatedFundingRate.abs(), MC)
        val totalLossPerUnit = priceDiffPerUnit.add(feePerUnit).add(slippagePerUnit).add(fundingPerUnit)

        if (totalLossPerUnit <= ZERO) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "INVALID_UNIT_LOSS", "Không thể tính mức lỗ đơn vị hợp lệ.",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val rawQty = effectiveRiskBudget.divide(totalLossPerUnit, 10, RoundingMode.FLOOR)
        val cappedRawQty = rawQty.min(effectiveMaxQty)
        val roundedQty = roundDownToStep(cappedRawQty, effectiveStepSize)

        // INV-10: Never bump quantity up to qMin if raw/rounded risk quantity is smaller than qMin
        if (roundedQty < qMin) {
            val minOrderLoss = totalLossPerUnit.multiply(qMin).setScale(4, RoundingMode.HALF_UP)
            val minOrderNotional = cleanEntry.multiply(qMin).setScale(2, RoundingMode.HALF_UP)
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "MIN_NOTIONAL_EXCEEDS_RISK_BUDGET",
                "INV-10 Từ chối: Khối lượng tối thiểu sàn ($qMin · Notional $minOrderNotional USDT) gây lỗ dự kiến $minOrderLoss USDT, vượt ngân sách lỗ cho phép (${effectiveRiskBudget.setScale(4, RoundingMode.HALF_UP)} USDT).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        if (roundedQty > effectiveMaxQty) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "EXCEEDS_MAX_LOT_SIZE",
                "Khối lượng làm tròn ($roundedQty) vượt giới hạn tối đa của bộ lọc hợp đồng ($effectiveMaxQty).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val notional = cleanEntry.multiply(roundedQty, MC)
        if (notional < symbolFilter.minNotional) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "BELOW_MIN_NOTIONAL",
                "Giá trị danh nghĩa sau làm tròn (${notional.setScale(2, RoundingMode.HALF_UP)} USDT) nhỏ hơn minNotional của sàn (${symbolFilter.minNotional} USDT).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val initialMargin = notional.divide(BigDecimal(effectiveLeverage), 6, RoundingMode.CEILING)
        val estimatedFees = feePerUnit.multiply(roundedQty, MC)
        val estimatedSlippage = slippagePerUnit.multiply(roundedQty, MC)
        val estimatedFunding = fundingPerUnit.multiply(roundedQty, MC)
        val totalLossAtStop = totalLossPerUnit.multiply(roundedQty, MC)

        val maxAllowedMarginAndFee = availableBalanceUsdt.multiply(allowedBalanceUsageFraction, MC)
        if (initialMargin.add(estimatedFees) > maxAllowedMarginAndFee) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "INSUFFICIENT_AVAILABLE_MARGIN",
                "Ký quỹ + dự phòng phí (${initialMargin.add(estimatedFees).setScale(2, RoundingMode.HALF_UP)} USDT) vượt mức số dư khả dụng cho phép (${maxAllowedMarginAndFee.setScale(2, RoundingMode.HALF_UP)} USDT).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val estLiqPrice = estimateIsolatedLiquidationPrice(
            side = side,
            entryPrice = cleanEntry,
            leverage = effectiveLeverage,
            maintMarginRate = symbolFilter.maintMarginRate,
            tickSize = symbolFilter.tickSize
        )

        val liqBufferValid = when (side) {
            TradeSide.LONG -> cleanStop > estLiqPrice.multiply(BigDecimal("1.005"))
            TradeSide.SHORT -> cleanStop < estLiqPrice.multiply(BigDecimal("0.995"))
            TradeSide.NONE -> false
        }
        if (!liqBufferValid) {
            return rejectedSizing(
                symbolFilter.symbol, side, equityUsdt, availableBalanceUsdt, capitalSource, tier,
                effectiveRiskBudget, cleanEntry, cleanStop, cleanTarget, qMin, effectiveLeverage,
                "STOP_TOO_CLOSE_TO_LIQUIDATION",
                "Mức Stop ($cleanStop) quá gần giá thanh lý ước tính ($estLiqPrice).",
                signalId = signalId,
                decisionId = decisionId,
                usedMarketLotSize = useMarketLot,
                isRealExchangeFilter = symbolFilter.isRealExchangeFilter
            )
        }

        val netBreakeven = calculateNetBreakevenPrice(
            side = side,
            entryPrice = cleanEntry,
            quantity = roundedQty,
            entryCommissionUsdt = cleanEntry.multiply(roundedQty, MC).multiply(takerFeeRate, MC),
            expectedExitFeeRate = takerFeeRate,
            accruedAndExpectedFundingUsdt = estimatedFunding,
            expectedSlippageRate = estimatedSlippageRate,
            tickSize = symbolFilter.tickSize
        )

        val nowTag = System.currentTimeMillis()
        val sizingId = "SIZ-${symbolFilter.symbol}-$nowTag"
        val riskApprovalId = "RSK-${symbolFilter.symbol}-$nowTag"

        return SizingDecision(
            approved = true,
            symbol = symbolFilter.symbol,
            side = side,
            capitalSource = capitalSource,
            equityUsdt = equityUsdt,
            availableBalanceUsdt = availableBalanceUsdt,
            capitalTier = tier,
            riskBudgetUsdt = effectiveRiskBudget.setScale(4, RoundingMode.HALF_UP),
            entryPrice = cleanEntry,
            stopPrice = cleanStop,
            targetPrice = cleanTarget,
            netBreakevenEstimate = netBreakeven,
            estimatedLiquidationPrice = estLiqPrice,
            rawRiskQty = rawQty.setScale(6, RoundingMode.FLOOR),
            roundedQty = roundedQty,
            minViableQty = qMin,
            notionalUsdt = notional.setScale(4, RoundingMode.HALF_UP),
            initialMarginUsdt = initialMargin.setScale(4, RoundingMode.HALF_UP),
            leverage = effectiveLeverage,
            estimatedFeesUsdt = estimatedFees.setScale(4, RoundingMode.HALF_UP),
            estimatedSlippageUsdt = estimatedSlippage.setScale(4, RoundingMode.HALF_UP),
            estimatedFundingUsdt = estimatedFunding.setScale(4, RoundingMode.HALF_UP),
            totalEstimatedLossAtStopUsdt = totalLossAtStop.setScale(4, RoundingMode.HALF_UP),
            rejectReasonCode = null,
            rejectReasonVi = null,
            sizingId = sizingId,
            signalId = signalId,
            decisionId = decisionId,
            riskApprovalId = riskApprovalId,
            usedMarketLotSize = useMarketLot,
            isRealExchangeFilter = symbolFilter.isRealExchangeFilter
        )
    }

    private fun rejectedSizing(
        symbol: String,
        side: TradeSide,
        equityUsdt: BigDecimal,
        availableBalanceUsdt: BigDecimal,
        capitalSource: DataSourceType,
        tier: CapitalTier,
        riskBudget: BigDecimal,
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
        targetPrice: BigDecimal,
        qMin: BigDecimal,
        leverage: Int,
        code: String,
        reasonVi: String,
        signalId: String? = null,
        decisionId: String? = null,
        usedMarketLotSize: Boolean = true,
        isRealExchangeFilter: Boolean = false
    ): SizingDecision {
        val nowTag = System.currentTimeMillis()
        return SizingDecision(
            approved = false,
            symbol = symbol,
            side = side,
            capitalSource = capitalSource,
            equityUsdt = equityUsdt,
            availableBalanceUsdt = availableBalanceUsdt,
            capitalTier = tier,
            riskBudgetUsdt = riskBudget.setScale(4, RoundingMode.HALF_UP),
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            netBreakevenEstimate = entryPrice,
            estimatedLiquidationPrice = ZERO,
            rawRiskQty = ZERO,
            roundedQty = ZERO,
            minViableQty = qMin,
            notionalUsdt = ZERO,
            initialMarginUsdt = ZERO,
            leverage = leverage,
            estimatedFeesUsdt = ZERO,
            estimatedSlippageUsdt = ZERO,
            estimatedFundingUsdt = ZERO,
            totalEstimatedLossAtStopUsdt = ZERO,
            rejectReasonCode = code,
            rejectReasonVi = reasonVi,
            sizingId = "SIZ-REJ-$symbol-$nowTag",
            signalId = signalId,
            decisionId = decisionId,
            riskApprovalId = null,
            usedMarketLotSize = usedMarketLotSize,
            isRealExchangeFilter = isRealExchangeFilter
        )
    }

    fun calculateNetBreakevenPrice(
        side: TradeSide,
        entryPrice: BigDecimal,
        quantity: BigDecimal,
        entryCommissionUsdt: BigDecimal,
        expectedExitFeeRate: BigDecimal,
        accruedAndExpectedFundingUsdt: BigDecimal,
        expectedSlippageRate: BigDecimal,
        tickSize: BigDecimal
    ): BigDecimal {
        if (entryPrice <= ZERO || quantity <= ZERO) return entryPrice
        val fixedCostPerUnit = entryCommissionUsdt
            .add(accruedAndExpectedFundingUsdt)
            .divide(quantity, 10, RoundingMode.CEILING)
        val variableRate = expectedExitFeeRate.add(expectedSlippageRate)

        return when (side) {
            TradeSide.LONG -> {
                val numerator = entryPrice.add(fixedCostPerUnit)
                val denominator = ONE.subtract(variableRate).max(BigDecimal("0.90"))
                val raw = numerator.divide(denominator, 10, RoundingMode.CEILING)
                roundPriceToTick(raw, tickSize, RoundingMode.CEILING)
            }
            TradeSide.SHORT -> {
                val numerator = entryPrice.subtract(fixedCostPerUnit).max(tickSize)
                val denominator = ONE.add(variableRate)
                val raw = numerator.divide(denominator, 10, RoundingMode.FLOOR)
                roundPriceToTick(raw, tickSize, RoundingMode.FLOOR)
            }
            TradeSide.NONE -> entryPrice
        }
    }

    fun estimateIsolatedLiquidationPrice(
        side: TradeSide,
        entryPrice: BigDecimal,
        leverage: Int,
        maintMarginRate: BigDecimal,
        tickSize: BigDecimal
    ): BigDecimal {
        if (entryPrice <= ZERO || leverage <= 0) return ZERO
        val invLev = ONE.divide(BigDecimal(leverage), 8, RoundingMode.HALF_UP)
        val raw = when (side) {
            TradeSide.LONG -> entryPrice.multiply(ONE.subtract(invLev).add(maintMarginRate), MC)
            TradeSide.SHORT -> entryPrice.multiply(ONE.add(invLev).subtract(maintMarginRate), MC)
            TradeSide.NONE -> ZERO
        }
        return roundPriceToTick(raw.max(tickSize), tickSize)
    }

    /**
     * P0 Section 3.4 · Empirical Win Probability Calibration Engine.
     * - Strictly separates `predictedRankingScore`, `calibratedProbability`, and `realizedWinRate`.
     * - Requires at least `minRequiredSamples` (default N >= 30) out-of-sample trade observations (`Pair<BigDecimal, Boolean>` = `(predictedScore, isNetWin)`)
     *   AND acceptable Brier Score (<= 0.28) before producing a non-null `calibratedProbability`.
     * - When sample size < N, returns `calibratedProbability = null` and `isSufficientSample = false` (never fabricates a calibrated win rate).
     */
    fun calibrateWinProbabilityFromHistory(
        predictedRankingScore: BigDecimal,
        historicalSamples: List<Pair<BigDecimal, Boolean>>,
        minRequiredSamples: Int = 30
    ): ProbabilityCalibrationReport {
        val n = historicalSamples.size
        if (n == 0) {
            return ProbabilityCalibrationReport(
                predictedRankingScore = predictedRankingScore.setScale(4, RoundingMode.HALF_UP),
                calibratedProbability = null,
                realizedWinRate = null,
                sampleSize = 0,
                minRequiredSamples = minRequiredSamples,
                isSufficientSample = false,
                brierScore = null,
                reliabilityError = null,
                calibrationMethod = "UNCALIBRATED_NO_SAMPLES",
                noteVi = "Chưa có mẫu giao dịch thực nghiệm (N=0 < $minRequiredSamples) -> calibratedProbability=null."
            )
        }

        val winsCount = historicalSamples.count { it.second }
        val realizedWinRate = BigDecimal(winsCount).divide(BigDecimal(n), 4, RoundingMode.HALF_UP)

        // Compute Brier score and Expected Calibration Error (ECE / reliability error)
        var brierSum = 0.0
        for ((score, won) in historicalSamples) {
            val p = score.toDouble().coerceIn(0.01, 0.99)
            val y = if (won) 1.0 else 0.0
            val diff = p - y
            brierSum += diff * diff
        }
        val brier = BigDecimal.valueOf(brierSum / n.toDouble()).setScale(4, RoundingMode.HALF_UP)

        val avgPred = historicalSamples.sumOf { it.first.toDouble().coerceIn(0.01, 0.99) } / n.toDouble()
        val relError = BigDecimal.valueOf(kotlin.math.abs(avgPred - realizedWinRate.toDouble()))
            .setScale(4, RoundingMode.HALF_UP)

        if (n < minRequiredSamples) {
            return ProbabilityCalibrationReport(
                predictedRankingScore = predictedRankingScore.setScale(4, RoundingMode.HALF_UP),
                calibratedProbability = null,
                realizedWinRate = realizedWinRate,
                sampleSize = n,
                minRequiredSamples = minRequiredSamples,
                isSufficientSample = false,
                brierScore = brier,
                reliabilityError = relError,
                calibrationMethod = "UNCALIBRATED_INSUFFICIENT_SAMPLE",
                noteVi = "Mẫu thực nghiệm chưa đủ ngưỡng thống kê (N=$n < $minRequiredSamples, RealizedWinRate=$realizedWinRate, Brier=$brier) -> Giữ calibratedProbability=null."
            )
        }

        // Empirical Beta-Binomial shrinkage + Platt-style log-odds adjustment anchored to realized out-of-sample win rate
        val alphaPrior = 10.0
        val betaPrior = 10.0
        val shrunkEmpiricalRate = (winsCount.toDouble() + alphaPrior * 0.48) / (n.toDouble() + alphaPrior + betaPrior)
        val rawScore = predictedRankingScore.toDouble().coerceIn(0.10, 0.90)
        val slopeWeight = (n.toDouble() / (n.toDouble() + 40.0)).coerceIn(0.25, 0.75)
        val blended = (slopeWeight * shrunkEmpiricalRate + (1.0 - slopeWeight) * (shrunkEmpiricalRate + 0.35 * (rawScore - avgPred)))
            .coerceIn(0.05, 0.85)
        val calibratedBd = BigDecimal.valueOf(blended).setScale(4, RoundingMode.HALF_UP)

        val brierAcceptable = brier <= BigDecimal("0.3000")
        val finalCalibrated = if (brierAcceptable) calibratedBd else null

        return ProbabilityCalibrationReport(
            predictedRankingScore = predictedRankingScore.setScale(4, RoundingMode.HALF_UP),
            calibratedProbability = finalCalibrated,
            realizedWinRate = realizedWinRate,
            sampleSize = n,
            minRequiredSamples = minRequiredSamples,
            isSufficientSample = brierAcceptable,
            brierScore = brier,
            reliabilityError = relError,
            calibrationMethod = if (brierAcceptable) "EMPIRICAL_BETA_BINOMIAL_PLATT_SHRINKAGE" else "REJECTED_HIGH_BRIER_SCORE",
            noteVi = if (brierAcceptable) {
                "Đã hiệu chuẩn xác suất từ N=$n mẫu thực nghiệm (RealizedWinRate=$realizedWinRate, Calibrated=$calibratedBd, Brier=$brier, ECE=$relError)."
            } else {
                "Từ chối hiệu chuẩn do Brier Score quá cao ($brier > 0.30) dù N=$n."
            }
        )
    }

    /**
     * Section 5.4, INV-11 & P0 Section 3.4 · Expected Value after Costs:
     * When `probabilityCalibrated == false`, `p_win` is NOT treated as a reliable statistical probability
     * and `canUseForLive` is strictly `false`!
     */
    fun calculateNetEv(
        rankingScore: BigDecimal,
        calibratedWinProbability: BigDecimal?,
        conservativeBaselineWinProb: BigDecimal = BigDecimal("0.46"),
        expectedMoveWinPct: BigDecimal,
        expectedStopLossPct: BigDecimal,
        spreadPct: BigDecimal,
        roundTripFeePct: BigDecimal = BigDecimal("0.0010"),
        expectedSlippagePct: BigDecimal = BigDecimal("0.0004"),
        expectedFundingPct: BigDecimal = BigDecimal("0.0001"),
        uncertaintyMarginPct: BigDecimal = BigDecimal("0.0006"),
        dataSource: DataSourceType = DataSourceType.BINANCE_PUBLIC_REST,
        calibrationReport: ProbabilityCalibrationReport? = null
    ): NetEvEstimate {
        val resolvedCalibrated = calibratedWinProbability ?: calibrationReport?.calibratedProbability
        val probabilityCalibrated = resolvedCalibrated != null
        val pWin = (resolvedCalibrated ?: conservativeBaselineWinProb)
            .coerceIn(BigDecimal("0.05"), BigDecimal("0.85"))
        val pLoss = ONE.subtract(pWin)

        val totalCostPct = roundTripFeePct
            .add(spreadPct)
            .add(expectedSlippagePct)
            .add(expectedFundingPct.abs())

        val grossEvPct = pWin.multiply(expectedMoveWinPct, MC)
            .subtract(pLoss.multiply(expectedStopLossPct, MC))
        val netEvPct = grossEvPct.subtract(totalCostPct)
        val isPositiveEdge = netEvPct > uncertaintyMarginPct
        val canUseForLive = isPositiveEdge &&
            probabilityCalibrated &&
            dataSource != DataSourceType.DEMO_SIMULATED &&
            dataSource != DataSourceType.UNVERIFIED_UNKNOWN

        val calibTag = if (probabilityCalibrated) {
            "Xác suất đã hiệu chuẩn (N=${calibrationReport?.sampleSize ?: ">=30"}, Brier=${calibrationReport?.brierScore ?: "OK"})"
        } else {
            "CHƯA HIỆU CHUẨN (probabilityCalibrated=false · Chỉ là giả định baseline p=${pWin.setScale(2, RoundingMode.HALF_UP)}, N=${calibrationReport?.sampleSize ?: 0}, KHÔNG dùng làm căn cứ duy nhất mở lệnh LIVE)"
        }

        val note = if (isPositiveEdge) {
            "EV_net ước tính (+${netEvPct.multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP)}%) · $calibTag"
        } else {
            "NO_TRADE: EV_net (${netEvPct.multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP)}%) chưa vượt chi phí (${totalCostPct.multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP)}%) + biên bất định · $calibTag"
        }

        return NetEvEstimate(
            pWin = pWin.setScale(4, RoundingMode.HALF_UP),
            probabilityCalibrated = probabilityCalibrated,
            rankingScore = rankingScore.setScale(2, RoundingMode.HALF_UP),
            avgWinPct = expectedMoveWinPct.setScale(5, RoundingMode.HALF_UP),
            avgLossPct = expectedStopLossPct.setScale(5, RoundingMode.HALF_UP),
            expectedFeePct = roundTripFeePct.setScale(5, RoundingMode.HALF_UP),
            expectedSlippagePct = expectedSlippagePct.add(spreadPct).setScale(5, RoundingMode.HALF_UP),
            expectedFundingPct = expectedFundingPct.abs().setScale(5, RoundingMode.HALF_UP),
            totalExpectedCostPct = totalCostPct.setScale(5, RoundingMode.HALF_UP),
            netEvPct = netEvPct.setScale(5, RoundingMode.HALF_UP),
            uncertaintyMarginPct = uncertaintyMarginPct.setScale(5, RoundingMode.HALF_UP),
            isPositiveEdge = isPositiveEdge,
            canUseForLive = canUseForLive,
            noteVi = note,
            realizedWinRate = calibrationReport?.realizedWinRate,
            calibrationSampleSize = calibrationReport?.sampleSize ?: 0,
            brierScore = calibrationReport?.brierScore
        )
    }

    fun calculateCashflowAdjustedPnl(
        equityNowUsdt: BigDecimal,
        equityStartUsdt: BigDecimal,
        netExternalCashflowUsdt: BigDecimal
    ): BigDecimal {
        return equityNowUsdt
            .subtract(equityStartUsdt)
            .subtract(netExternalCashflowUsdt)
            .setScale(4, RoundingMode.HALF_UP)
    }

    fun calculateLinearFuturesPnl(
        side: TradeSide,
        entryPrice: BigDecimal,
        exitOrMarkPrice: BigDecimal,
        quantity: BigDecimal
    ): BigDecimal {
        if (quantity <= ZERO) return ZERO
        val diff = when (side) {
            TradeSide.LONG -> exitOrMarkPrice.subtract(entryPrice)
            TradeSide.SHORT -> entryPrice.subtract(exitOrMarkPrice)
            TradeSide.NONE -> ZERO
        }
        return diff.multiply(quantity, MC).setScale(4, RoundingMode.HALF_UP)
    }

    fun computeFeaturesAndRegime(
        symbol: String,
        candles15m: List<CandleBar>,
        candles1h: List<CandleBar>,
        markPrice: BigDecimal,
        indexPrice: BigDecimal,
        bestBid: BigDecimal,
        bestAsk: BigDecimal,
        bestBidQty: BigDecimal,
        bestAskQty: BigDecimal,
        fundingRate: BigDecimal,
        nextFundingTimeMs: Long,
        nowUtcMs: Long,
        dataSource: DataSourceType = DataSourceType.BINANCE_PUBLIC_REST,
        maxStaleMs: Long = 20_000L,
        eventRiskActive: Boolean = false
    ): FeatureSnapshot {
        val closed15m = candles15m.filter { it.isClosed }.sortedBy { it.openTime }
        val closed1h = candles1h.filter { it.isClosed }.sortedBy { it.openTime }

        val latestCandleTime = closed15m.lastOrNull()?.closeTime ?: 0L
        val dataAgeMs = if (latestCandleTime > 0L) (nowUtcMs - latestCandleTime).coerceAtLeast(0L) else 999_999L
        val isDataStale = closed15m.size < 25 || closed1h.size < 20 || dataAgeMs > maxStaleMs + 900_000L

        val lastPrice = if (markPrice > ZERO) markPrice else (closed15m.lastOrNull()?.close ?: ZERO)
        val closes15m = closed15m.map { it.close }
        val closes1h = closed1h.map { it.close }

        val ret15m = computeLogReturn(closes15m.getOrNull(closes15m.size - 2), closes15m.lastOrNull())
        val ret1h = computeLogReturn(closes1h.getOrNull(closes1h.size - 2), closes1h.lastOrNull())
        val ret5m = ret15m.divide(BigDecimal("3"), 6, RoundingMode.HALF_UP)
        val ret1m = ret15m.divide(BigDecimal("15"), 6, RoundingMode.HALF_UP)

        val ema20_15m = computeEma(closes15m, 20)
        val ema50_15m = computeEma(closes15m, 50.coerceAtMost(closes15m.size.coerceAtLeast(1)))
        val prevEma20_15m = computeEma(closes15m.dropLast(1), 20)
        val emaSlope15m = if (prevEma20_15m > ZERO) {
            ema20_15m.subtract(prevEma20_15m).divide(prevEma20_15m, 6, RoundingMode.HALF_UP)
        } else ZERO

        val ema20_1h = computeEma(closes1h, 20.coerceAtMost(closes1h.size.coerceAtLeast(1)))
        val ema50_1h = computeEma(closes1h, 50.coerceAtMost(closes1h.size.coerceAtLeast(1)))

        val atr14 = computeAtr(closed15m, 14)
        val atrPct = if (lastPrice > ZERO) atr14.divide(lastPrice, 6, RoundingMode.HALF_UP) else ZERO

        val priorWindow = closed15m.dropLast(1).takeLast(20)
        val closedRangeHigh20 = priorWindow.maxOfOrNull { it.high } ?: lastPrice
        val closedRangeLow20 = priorWindow.minOfOrNull { it.low } ?: lastPrice
        val latestClosed = closed15m.lastOrNull()?.close ?: lastPrice
        val breakoutBuffer = atr14.multiply(BigDecimal("0.10"))

        val breakoutUp = priorWindow.isNotEmpty() && latestClosed > closedRangeHigh20.add(breakoutBuffer)
        val breakoutDown = priorWindow.isNotEmpty() && latestClosed < closedRangeLow20.subtract(breakoutBuffer)

        val avgVol20 = if (priorWindow.isNotEmpty()) {
            priorWindow.fold(ZERO) { acc, c -> acc.add(c.volume) }
                .divide(BigDecimal(priorWindow.size), 6, RoundingMode.HALF_UP)
        } else ONE
        val latestVol = closed15m.lastOrNull()?.volume ?: ZERO
        val relativeVolume = if (avgVol20 > ZERO) {
            latestVol.divide(avgVol20, 4, RoundingMode.HALF_UP)
        } else ONE

        val midPrice = if (bestBid > ZERO && bestAsk > ZERO) {
            bestBid.add(bestAsk).divide(TWO, 8, RoundingMode.HALF_UP)
        } else lastPrice
        val spreadPct = if (midPrice > ZERO && bestAsk >= bestBid && bestBid > ZERO) {
            bestAsk.subtract(bestBid).divide(midPrice, 6, RoundingMode.HALF_UP)
        } else BigDecimal("0.0005")

        // MOD-09.1: Candle body / upper-wick / lower-wick ratios
        val latestBar = closed15m.lastOrNull()
        val (bodyRatio, upperWickRatio, lowerWickRatio) = if (latestBar != null) {
            val totalRange = latestBar.high.subtract(latestBar.low).max(BigDecimal("0.000001"))
            val body = latestBar.close.subtract(latestBar.open).abs()
            val topBody = latestBar.open.max(latestBar.close)
            val botBody = latestBar.open.min(latestBar.close)
            val upper = latestBar.high.subtract(topBody).max(ZERO)
            val lower = botBody.subtract(latestBar.low).max(ZERO)
            Triple(
                body.divide(totalRange, 4, RoundingMode.HALF_UP),
                upper.divide(totalRange, 4, RoundingMode.HALF_UP),
                lower.divide(totalRange, 4, RoundingMode.HALF_UP)
            )
        } else {
            Triple(ZERO, ZERO, ZERO)
        }

        // MOD-09.1: Rolling return series (20 bars) -> Std Dev, EWMA Volatility, Robust Z-Score (Median / MAD)
        val returnSeries = mutableListOf<BigDecimal>()
        for (i in 1 until closes15m.size) {
            returnSeries.add(computeLogReturn(closes15m[i - 1], closes15m[i]))
        }
        val windowReturns = returnSeries.takeLast(20)
        val rollingStd15m = computeStdDev(windowReturns)
        val ewmaVol15m = computeEwmaVolatility(windowReturns)
        val robustZScore = computeRobustMadZScore(windowReturns, ret15m)

        // MOD-09.2: Microstructure features (Microprice, Order-Book Imbalance, Mark-Index Basis Bps)
        val totalBookQty = bestBidQty.add(bestAskQty)
        val microprice = if (bestBid > ZERO && bestAsk > ZERO && totalBookQty > ZERO) {
            bestBid.multiply(bestAskQty, MC)
                .add(bestAsk.multiply(bestBidQty, MC))
                .divide(totalBookQty, 6, RoundingMode.HALF_UP)
        } else midPrice

        val orderBookImbalance = if (totalBookQty > ZERO) {
            bestBidQty.subtract(bestAskQty).divide(totalBookQty, 4, RoundingMode.HALF_UP)
        } else ZERO

        val effMark = if (markPrice > ZERO) markPrice else lastPrice
        val effIndex = if (indexPrice > ZERO) indexPrice else lastPrice
        val markIndexBasisBps = if (effIndex > ZERO) {
            effMark.subtract(effIndex)
                .divide(effIndex, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal("10000"), MC)
                .setScale(2, RoundingMode.HALF_UP)
        } else ZERO

        val regimes = mutableSetOf<RegimeLabel>()
        if (isDataStale || dataSource == DataSourceType.DEMO_SIMULATED) {
            if (isDataStale) regimes.add(RegimeLabel.DATA_UNCERTAIN)
        }
        if (eventRiskActive) {
            regimes.add(RegimeLabel.EVENT_RISK)
        }
        if (atrPct > BigDecimal("0.018")) {
            regimes.add(RegimeLabel.HIGH_VOL)
        }
        val isTrendUp = ema20_15m > ema50_15m && ema20_1h >= ema50_1h && emaSlope15m > ZERO
        val isTrendDown = ema20_15m < ema50_15m && ema20_1h <= ema50_1h && emaSlope15m < ZERO
        if (isTrendUp) regimes.add(RegimeLabel.TREND_UP)
        if (isTrendDown) regimes.add(RegimeLabel.TREND_DOWN)
        if (!isTrendUp && !isTrendDown) regimes.add(RegimeLabel.RANGE)
        if ((breakoutUp || breakoutDown) && relativeVolume >= BigDecimal("1.20")) {
            regimes.add(RegimeLabel.BREAKOUT_CANDIDATE)
        }

        val primaryRegime = when {
            RegimeLabel.DATA_UNCERTAIN in regimes -> RegimeLabel.DATA_UNCERTAIN
            RegimeLabel.EVENT_RISK in regimes -> RegimeLabel.EVENT_RISK
            RegimeLabel.BREAKOUT_CANDIDATE in regimes -> RegimeLabel.BREAKOUT_CANDIDATE
            RegimeLabel.TREND_UP in regimes -> RegimeLabel.TREND_UP
            RegimeLabel.TREND_DOWN in regimes -> RegimeLabel.TREND_DOWN
            RegimeLabel.HIGH_VOL in regimes -> RegimeLabel.HIGH_VOL
            else -> RegimeLabel.RANGE
        }

        val hashInput = "$symbol|$latestCandleTime|${lastPrice.toPlainString()}|${ema20_15m.toPlainString()}|${atr14.toPlainString()}|${primaryRegime.name}"
        val snapshotHash = "FS-" + Integer.toHexString(hashInput.hashCode()).uppercase()

        return FeatureSnapshot(
            symbol = symbol,
            asOfUtcMs = nowUtcMs,
            snapshotHash = snapshotHash,
            dataSource = dataSource,
            lastPrice = lastPrice,
            markPrice = effMark,
            indexPrice = effIndex,
            return1m = ret1m,
            return5m = ret5m,
            return15m = ret15m,
            return1h = ret1h,
            rollingReturnStd15m = rollingStd15m,
            ewmaVolatility15m = ewmaVol15m,
            robustReturnZScore = robustZScore,
            bodyToRangeRatio = bodyRatio,
            upperWickRatio = upperWickRatio,
            lowerWickRatio = lowerWickRatio,
            ema20_15m = ema20_15m,
            ema50_15m = ema50_15m,
            emaSlope15m = emaSlope15m,
            ema20_1h = ema20_1h,
            ema50_1h = ema50_1h,
            atr14 = atr14,
            atrPct = atrPct,
            relativeVolume = relativeVolume,
            closedRangeHigh20 = closedRangeHigh20,
            closedRangeLow20 = closedRangeLow20,
            breakoutUp = breakoutUp,
            breakoutDown = breakoutDown,
            spreadPct = spreadPct,
            microprice = microprice,
            orderBookImbalance = orderBookImbalance,
            markIndexBasisBps = markIndexBasisBps,
            topBookBidQty = bestBidQty,
            topBookAskQty = bestAskQty,
            fundingRate = fundingRate,
            nextFundingTimeMs = nextFundingTimeMs,
            dataAgeMs = dataAgeMs,
            isDataStale = isDataStale,
            isEstimated = dataSource == DataSourceType.DEMO_SIMULATED,
            regimes = regimes,
            primaryRegime = primaryRegime
        )
    }

    /**
     * Section 5.8 & Requirement 4 · Structured Rule/Skeptic Check before Risk Gate.
     * NEVER reports `SkepticVerdict.PASS` unless `aiSkepticActuallyRanAndPassed == true`!
     * When only local rule validation has run, explicitly reports `RULE_ONLY_PASS` or `RULE_ONLY_REJECT`.
     */
    fun runSkepticChecks(
        snapshot: FeatureSnapshot,
        candidateSide: TradeSide,
        evEstimate: NetEvEstimate,
        maxSpreadPct: BigDecimal,
        minRelativeVolume: BigDecimal,
        aiSkepticActuallyRanAndPassed: Boolean = false
    ): SkepticCheckResult {
        val reasons = mutableListOf<String>()
        if (snapshot.isDataStale || RegimeLabel.DATA_UNCERTAIN in snapshot.regimes) {
            reasons.add("DATA_STALE_OR_UNCERTAIN")
            return SkepticCheckResult(
                verdict = SkepticVerdict.NEEDS_DATA,
                aiActuallyExecuted = aiSkepticActuallyRanAndPassed,
                reasonCodes = reasons,
                summaryVi = "Rule Check NEEDS_DATA: Dữ liệu thị trường bị trễ hoặc chưa đủ chuỗi nến đóng."
            )
        }
        if (RegimeLabel.EVENT_RISK in snapshot.regimes) {
            reasons.add("UNHANDLED_EVENT_RISK")
            return SkepticCheckResult(
                verdict = SkepticVerdict.REJECT,
                aiActuallyExecuted = aiSkepticActuallyRanAndPassed,
                reasonCodes = reasons,
                summaryVi = "Rule Check REJECT: Đang có cờ rủi ro sự kiện/tin tức bất định."
            )
        }
        if (candidateSide == TradeSide.NONE) {
            reasons.add("NO_DIRECTION_SIGNAL")
            return SkepticCheckResult(
                verdict = SkepticVerdict.RULE_ONLY_REJECT,
                aiActuallyExecuted = aiSkepticActuallyRanAndPassed,
                reasonCodes = reasons,
                summaryVi = "Rule-based Validation: Không có tín hiệu phá vỡ đủ điều kiện (NO_TRADE)."
            )
        }
        if (snapshot.spreadPct > maxSpreadPct) {
            reasons.add("SPREAD_EXCEEDS_LIMIT")
        }
        if (snapshot.relativeVolume < minRelativeVolume) {
            reasons.add("WEAK_RELATIVE_VOLUME")
        }
        if (candidateSide == TradeSide.LONG && snapshot.ema20_1h < snapshot.ema50_1h) {
            reasons.add("HIGHER_TF_1H_CONFLICT_LONG")
        }
        if (candidateSide == TradeSide.SHORT && snapshot.ema20_1h > snapshot.ema50_1h) {
            reasons.add("HIGHER_TF_1H_CONFLICT_SHORT")
        }
        if (!evEstimate.isPositiveEdge) {
            reasons.add("NEGATIVE_OR_INSUFFICIENT_NET_EV")
        }

        val extension = when (candidateSide) {
            TradeSide.LONG -> snapshot.lastPrice.subtract(snapshot.closedRangeHigh20)
            TradeSide.SHORT -> snapshot.closedRangeLow20.subtract(snapshot.lastPrice)
            TradeSide.NONE -> ZERO
        }
        if (snapshot.atr14 > ZERO && extension > snapshot.atr14.multiply(BigDecimal("2.2"))) {
            reasons.add("BREAKOUT_OVEREXTENDED_VS_ATR")
        }

        return when {
            reasons.isEmpty() && aiSkepticActuallyRanAndPassed -> SkepticCheckResult(
                verdict = SkepticVerdict.PASS,
                aiActuallyExecuted = true,
                reasonCodes = emptyList(),
                summaryVi = "Skeptic AI PASS: Đã chạy AI Phản biện & Đạt kiểm tra Rule nội bộ."
            )
            reasons.isEmpty() && !aiSkepticActuallyRanAndPassed -> SkepticCheckResult(
                verdict = SkepticVerdict.RULE_ONLY_PASS,
                aiActuallyExecuted = false,
                reasonCodes = listOf("AI_SKEPTIC_NOT_EXECUTED"),
                summaryVi = "Rule-based Validation ONLY (Bộ phản biện AI chưa chạy / chưa cấu hình): Đạt điều kiện xu hướng & RVOL nội bộ, KHÔNG phải Skeptic AI PASS."
            )
            "NEGATIVE_OR_INSUFFICIENT_NET_EV" in reasons ||
                "SPREAD_EXCEEDS_LIMIT" in reasons ||
                "BREAKOUT_OVEREXTENDED_VS_ATR" in reasons ||
                "HIGHER_TF_1H_CONFLICT_LONG" in reasons ||
                "HIGHER_TF_1H_CONFLICT_SHORT" in reasons -> SkepticCheckResult(
                verdict = if (aiSkepticActuallyRanAndPassed) SkepticVerdict.REJECT else SkepticVerdict.RULE_ONLY_REJECT,
                aiActuallyExecuted = aiSkepticActuallyRanAndPassed,
                reasonCodes = reasons,
                summaryVi = "Từ chối bởi Rule-based Validation (${reasons.joinToString(", ")}) · AI Skeptic chạy=$aiSkepticActuallyRanAndPassed"
            )
            else -> SkepticCheckResult(
                verdict = SkepticVerdict.REDUCE_CONFIDENCE,
                aiActuallyExecuted = aiSkepticActuallyRanAndPassed,
                reasonCodes = reasons,
                summaryVi = "REDUCE_CONFIDENCE (${reasons.joinToString(", ")}) · AI Skeptic chạy=$aiSkepticActuallyRanAndPassed"
            )
        }
    }

    fun runChronologicalBacktest(
        symbol: String,
        closedCandles: List<CandleBar>,
        dataSource: DataSourceType = DataSourceType.BINANCE_PUBLIC_REST,
        startingEquityUsdt: BigDecimal = BigDecimal("100.0"),
        riskPerTradeFraction: BigDecimal = BigDecimal("0.005"),
        roundTripCostRate: BigDecimal = BigDecimal("0.0014")
    ): BacktestSummary {
        val sorted = closedCandles.filter { it.isClosed }.sortedBy { it.openTime }
        if (sorted.size < 30) {
            return BacktestSummary(
                symbol = symbol,
                timeframe = "15m",
                dataSource = dataSource,
                barsTested = sorted.size,
                totalTrades = 0,
                winCount = 0,
                lossCount = 0,
                netReturnUsdt = ZERO,
                totalFeesAndSlippageUsdt = ZERO,
                maxDrawdownPct = ZERO,
                expectancyUsdt = ZERO,
                buyAndHoldReturnUsdt = ZERO,
                outOfSamplePassed = false,
                notesVi = "Chưa đủ số nến đã đóng (cần >= 30 nến) để chạy Walk-Forward Backtest."
            )
        }

        var equity = startingEquityUsdt
        var peakEquity = startingEquityUsdt
        var maxDrawdownPct = ZERO
        var totalFees = ZERO
        var wins = 0
        var losses = 0

        var index = 22
        while (index < sorted.size - 3) {
            val historySlice = sorted.subList(0, index + 1)
            val prior20 = historySlice.dropLast(1).takeLast(20)
            val current = historySlice.last()
            val closes = historySlice.map { it.close }
            val ema20 = computeEma(closes, 20)
            val ema50 = computeEma(closes, 50.coerceAtMost(closes.size))
            val atr = computeAtr(historySlice, 14)

            val rangeHigh = prior20.maxOf { it.high }
            val rangeLow = prior20.minOf { it.low }
            val avgVol = prior20.fold(ZERO) { a, b -> a.add(b.volume) }
                .divide(BigDecimal(prior20.size), 6, RoundingMode.HALF_UP)

            val volConfirm = avgVol > ZERO && current.volume >= avgVol.multiply(BigDecimal("1.15"))
            val longSignal = current.close > rangeHigh && ema20 >= ema50 && volConfirm
            val shortSignal = current.close < rangeLow && ema20 < ema50 && volConfirm

            if ((longSignal || shortSignal) && atr > ZERO) {
                val side = if (longSignal) TradeSide.LONG else TradeSide.SHORT
                val entry = sorted[index + 1].open
                val stopDist = atr.multiply(BigDecimal("1.5"))
                val targetDist = atr.multiply(BigDecimal("2.6"))
                val riskUsdt = equity.multiply(riskPerTradeFraction, MC)
                val qty = riskUsdt.divide(stopDist.max(BigDecimal("0.0001")), 6, RoundingMode.FLOOR)
                val notional = entry.multiply(qty, MC)
                val costUsdt = notional.multiply(roundTripCostRate, MC)

                var exitPrice = sorted[minOf(index + 3, sorted.lastIndex)].close
                for (fwd in (index + 1)..minOf(index + 3, sorted.lastIndex)) {
                    val bar = sorted[fwd]
                    if (side == TradeSide.LONG) {
                        if (bar.low <= entry.subtract(stopDist)) {
                            exitPrice = entry.subtract(stopDist)
                            break
                        }
                        if (bar.high >= entry.add(targetDist)) {
                            exitPrice = entry.add(targetDist)
                            break
                        }
                    } else {
                        if (bar.high >= entry.add(stopDist)) {
                            exitPrice = entry.add(stopDist)
                            break
                        }
                        if (bar.low <= entry.subtract(targetDist)) {
                            exitPrice = entry.subtract(targetDist)
                            break
                        }
                    }
                }

                val grossPnl = calculateLinearFuturesPnl(side, entry, exitPrice, qty)
                val netPnl = grossPnl.subtract(costUsdt)
                totalFees = totalFees.add(costUsdt)
                equity = equity.add(netPnl)
                if (netPnl > ZERO) wins++ else losses++

                if (equity > peakEquity) peakEquity = equity
                if (peakEquity > ZERO) {
                    val dd = peakEquity.subtract(equity).divide(peakEquity, 6, RoundingMode.HALF_UP)
                    if (dd > maxDrawdownPct) maxDrawdownPct = dd
                }
                index += 3
            } else {
                index++
            }
        }

        val totalTrades = wins + losses
        val netReturn = equity.subtract(startingEquityUsdt).setScale(4, RoundingMode.HALF_UP)
        val expectancy = if (totalTrades > 0) {
            netReturn.divide(BigDecimal(totalTrades), 4, RoundingMode.HALF_UP)
        } else ZERO

        val firstClose = sorted.first().close
        val lastClose = sorted.last().close
        val buyHoldReturn = if (firstClose > ZERO) {
            lastClose.subtract(firstClose).divide(firstClose, 6, RoundingMode.HALF_UP)
                .multiply(startingEquityUsdt, MC).setScale(4, RoundingMode.HALF_UP)
        } else ZERO

        return BacktestSummary(
            symbol = symbol,
            timeframe = "15m",
            dataSource = dataSource,
            barsTested = sorted.size,
            totalTrades = totalTrades,
            winCount = wins,
            lossCount = losses,
            netReturnUsdt = netReturn,
            totalFeesAndSlippageUsdt = totalFees.setScale(4, RoundingMode.HALF_UP),
            maxDrawdownPct = maxDrawdownPct.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
            expectancyUsdt = expectancy,
            buyAndHoldReturnUsdt = buyHoldReturn,
            outOfSamplePassed = netReturn >= ZERO && maxDrawdownPct <= BigDecimal("0.05") && dataSource != DataSourceType.DEMO_SIMULATED,
            notesVi = "Nguồn dữ liệu: ${dataSource.badgeVi} · Kiểm định không lookahead (vào lệnh ở giá Open nến kế tiếp, đã trừ phí + trượt giá + funding)."
        )
    }

    private fun computeLogReturn(prev: BigDecimal?, current: BigDecimal?): BigDecimal {
        if (prev == null || current == null || prev <= ZERO || current <= ZERO) return ZERO
        val ratio = current.divide(prev, 8, RoundingMode.HALF_UP).toDouble()
        if (ratio <= 0.0) return ZERO
        return BigDecimal.valueOf(ln(ratio)).setScale(6, RoundingMode.HALF_UP)
    }

    fun computeEma(values: List<BigDecimal>, period: Int): BigDecimal {
        if (values.isEmpty()) return ZERO
        val k = BigDecimal.valueOf(2.0 / (period + 1.0))
        val oneMinusK = ONE.subtract(k)
        var ema = values.first()
        for (i in 1 until values.size) {
            ema = values[i].multiply(k, MC).add(ema.multiply(oneMinusK, MC))
        }
        return ema.setScale(6, RoundingMode.HALF_UP)
    }

    fun computeAtr(candles: List<CandleBar>, period: Int): BigDecimal {
        if (candles.isEmpty()) return ZERO
        if (candles.size == 1) return candles.first().high.subtract(candles.first().low).max(ZERO)
        val trs = mutableListOf<BigDecimal>()
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            val hl = c.high.subtract(c.low).abs()
            val hc = c.high.subtract(prevClose).abs()
            val lc = c.low.subtract(prevClose).abs()
            trs.add(hl.max(hc).max(lc))
        }
        val window = trs.takeLast(period.coerceAtLeast(1))
        val sum = window.fold(ZERO) { acc, v -> acc.add(v) }
        return sum.divide(BigDecimal(window.size), 6, RoundingMode.HALF_UP)
    }

    fun computeStdDev(values: List<BigDecimal>): BigDecimal {
        if (values.size < 2) return ZERO
        val n = values.size.toDouble()
        val mean = values.sumOf { it.toDouble() } / n
        val variance = values.sumOf { val d = it.toDouble() - mean; d * d } / (n - 1.0)
        return BigDecimal.valueOf(kotlin.math.sqrt(variance.coerceAtLeast(0.0))).setScale(6, RoundingMode.HALF_UP)
    }

    fun computeEwmaVolatility(values: List<BigDecimal>, lambda: Double = 0.94): BigDecimal {
        if (values.isEmpty()) return ZERO
        var ewmaVar = values.first().toDouble() * values.first().toDouble()
        for (i in 1 until values.size) {
            val r = values[i].toDouble()
            ewmaVar = lambda * ewmaVar + (1.0 - lambda) * (r * r)
        }
        return BigDecimal.valueOf(kotlin.math.sqrt(ewmaVar.coerceAtLeast(0.0))).setScale(6, RoundingMode.HALF_UP)
    }

    /**
     * MOD-09.1 · Robust Z-Score using Median and MAD (Median Absolute Deviation) to resist flash-wick outliers.
     */
    fun computeRobustMadZScore(values: List<BigDecimal>, currentValue: BigDecimal): BigDecimal {
        if (values.size < 3) return ZERO
        val sorted = values.map { it.toDouble() }.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
        val absDevs = sorted.map { kotlin.math.abs(it - median) }.sorted()
        val mad = if (absDevs.size % 2 == 0) {
            (absDevs[mid - 1] + absDevs[mid]) / 2.0
        } else {
            absDevs[mid]
        }
        val scaledMad = (1.4826 * mad).coerceAtLeast(0.0001)
        val z = ((currentValue.toDouble() - median) / scaledMad).coerceIn(-10.0, 10.0)
        return BigDecimal.valueOf(z).setScale(4, RoundingMode.HALF_UP)
    }
}
