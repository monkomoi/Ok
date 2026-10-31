package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.PositionSizingResult
import com.example.domain.SymbolFilter
import com.example.quant.QuantMathEngine
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PositionSizingPreviewState(
    val symbol: String,
    val side: String,
    val accountBalanceUsd: Double,
    val riskPerTradePct: Double,
    val stopLossPct: Double,
    val takeProfitPct: Double,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val maxLeverage: Int,
    val dollarRiskBudgetUsd: Double,
    val symbolFilter: SymbolFilter,
    val sizingResult: PositionSizingResult
)

/**
 * Pure helper function for computing the full interactive position sizing preview from
 * account balance and stop-loss percentage so both the Compose UI and unit tests share
 * the exact same deterministic calculation.
 */
fun calculateInteractivePositionSizingPreview(
    symbol: String,
    side: String,
    accountBalanceUsd: Double,
    riskPerTradePct: Double, // e.g., 0.50 means 0.50%
    stopLossPct: Double,     // e.g., 1.20 means 1.20%
    rewardMultiple: Double,  // e.g., 2.20 means TP% = 2.20 * SL%
    entryPrice: Double,
    maxLeverage: Int,
    remainingDailyRiskBudgetUsd: Double = accountBalanceUsd * 0.03
): PositionSizingPreviewState {
    val filter = when (symbol.uppercase()) {
        "BTCUSDT" -> SymbolFilter("BTCUSDT", tickSize = 0.10, stepSize = 0.001, minQty = 0.001, minNotional = 100.0)
        "ETHUSDT" -> SymbolFilter("ETHUSDT", tickSize = 0.01, stepSize = 0.001, minQty = 0.001, minNotional = 20.0)
        "SOLUSDT" -> SymbolFilter("SOLUSDT", tickSize = 0.01, stepSize = 0.1, minQty = 0.1, minNotional = 5.0)
        "DOGEUSDT" -> SymbolFilter("DOGEUSDT", tickSize = 0.00001, stepSize = 1.0, minQty = 1.0, minNotional = 5.0)
        else -> SymbolFilter(symbol.uppercase(), tickSize = 0.01, stepSize = 0.01, minQty = 0.01, minNotional = 5.0)
    }

    val safeEntry = max(entryPrice, filter.tickSize)
    val safeSlPct = max(0.15, min(8.00, stopLossPct))
    val safeTpPct = max(safeSlPct * 1.5, safeSlPct * rewardMultiple)
    val isLong = side.uppercase() != "SHORT"

    val rawStopPrice = if (isLong) {
        safeEntry * (1.0 - safeSlPct / 100.0)
    } else {
        safeEntry * (1.0 + safeSlPct / 100.0)
    }
    val rawTpPrice = if (isLong) {
        safeEntry * (1.0 + safeTpPct / 100.0)
    } else {
        safeEntry * (1.0 - safeTpPct / 100.0)
    }

    val stopLossPrice = QuantMathEngine.roundToTickSize(
        price = rawStopPrice,
        tickSize = filter.tickSize,
        roundingMode = if (isLong) RoundingMode.FLOOR else RoundingMode.CEILING
    )
    val takeProfitPrice = QuantMathEngine.roundToTickSize(
        price = rawTpPrice,
        tickSize = filter.tickSize,
        roundingMode = if (isLong) RoundingMode.CEILING else RoundingMode.FLOOR
    )

    val riskFraction = max(0.001, min(0.02, riskPerTradePct / 100.0))
    val dollarRiskBudget = min(accountBalanceUsd * riskFraction, remainingDailyRiskBudgetUsd)

    val sizingResult = QuantMathEngine.calculatePositionSizing(
        symbolFilter = filter,
        equityUsd = accountBalanceUsd,
        availableMarginUsd = accountBalanceUsd,
        riskPerTradePct = riskFraction,
        remainingDailyRiskBudgetUsd = remainingDailyRiskBudgetUsd,
        entryPrice = safeEntry,
        stopLossPrice = stopLossPrice,
        takeProfitPrice = takeProfitPrice,
        maxAllowedLeverage = maxLeverage.coerceIn(1, 5)
    )

    return PositionSizingPreviewState(
        symbol = filter.symbol,
        side = if (isLong) "LONG" else "SHORT",
        accountBalanceUsd = accountBalanceUsd,
        riskPerTradePct = riskPerTradePct,
        stopLossPct = safeSlPct,
        takeProfitPct = safeTpPct,
        entryPrice = safeEntry,
        stopLossPrice = stopLossPrice,
        takeProfitPrice = takeProfitPrice,
        maxLeverage = maxLeverage.coerceIn(1, 5),
        dollarRiskBudgetUsd = dollarRiskBudget,
        symbolFilter = filter,
        sizingResult = sizingResult
    )
}

/**
 * Interactive Risk Management & Position Sizing UI Component that calculates
 * loss-budgeted position size, notional value, stop-loss / take-profit levels,
 * liquidation buffer, and exchange `minNotional` feasibility from account balance
 * and stop-loss percentage.
 */
@Composable
fun PositionSizingCalculatorCard(
    initialSymbol: String = "DOGEUSDT",
    initialBalanceUsd: Double = 150.0,
    initialRiskPerTradePct: Double = 0.50,
    initialStopLossPct: Double = 1.20,
    initialMaxLeverage: Int = 5,
    onApplyRiskParameters: ((riskPerTradePct: Double, maxLeverage: Int) -> Unit)? = null
) {
    var selectedSymbol by remember(initialSymbol) { mutableStateOf(initialSymbol.uppercase()) }
    var selectedSide by remember { mutableStateOf("LONG") }
    var balanceInput by remember(initialBalanceUsd) {
        mutableStateOf("%.2f".format(initialBalanceUsd))
    }
    var entryPriceInput by remember(selectedSymbol) {
        mutableStateOf(defaultReferencePriceString(selectedSymbol))
    }
    var riskPctSlider by remember(initialRiskPerTradePct) {
        mutableFloatStateOf(initialRiskPerTradePct.toFloat().coerceIn(0.20f, 1.50f))
    }
    var stopLossPctSlider by remember(initialStopLossPct) {
        mutableFloatStateOf(initialStopLossPct.toFloat().coerceIn(0.25f, 5.00f))
    }
    var stopLossPctInput by remember(initialStopLossPct) {
        mutableStateOf("%.2f".format(initialStopLossPct))
    }
    var rewardMultipleSlider by remember { mutableFloatStateOf(2.20f) }
    var leverageSlider by remember(initialMaxLeverage) {
        mutableIntStateOf(initialMaxLeverage.coerceIn(1, 5))
    }

    val preview by remember(
        selectedSymbol,
        selectedSide,
        balanceInput,
        entryPriceInput,
        riskPctSlider,
        stopLossPctSlider,
        rewardMultipleSlider,
        leverageSlider
    ) {
        derivedStateOf {
            val balance = balanceInput.toDoubleOrNull()?.coerceAtLeast(0.0) ?: initialBalanceUsd
            val entry = entryPriceInput.toDoubleOrNull()?.takeIf { it > 0.0 }
                ?: defaultReferencePrice(selectedSymbol)
            calculateInteractivePositionSizingPreview(
                symbol = selectedSymbol,
                side = selectedSide,
                accountBalanceUsd = balance,
                riskPerTradePct = riskPctSlider.toDouble(),
                stopLossPct = stopLossPctSlider.toDouble(),
                rewardMultiple = rewardMultipleSlider.toDouble(),
                entryPrice = entry,
                maxLeverage = leverageSlider
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("position_sizing_calculator_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Calculate,
                        contentDescription = "Position Sizing Calculator",
                        tint = AstraCyanAccent
                    )
                    Column {
                        Text(
                            text = "Position Sizing & Stop-Loss Calculator",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Loss-budget sizing: N = R / (SL% + Fee% + Slippage%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val approved = preview.sizingResult.approved
                Surface(
                    color = if (approved) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("calculator_approval_badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (approved) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (approved) AstraEmeraldGreen else AstraCrimsonDanger
                        )
                        Text(
                            text = if (approved) "APPROVED" else "REJECTED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (approved) AstraEmeraldGreen else AstraCrimsonDanger
                        )
                    }
                }
            }

            // Symbol & Side Selector
            val symbols = listOf("DOGEUSDT", "SOLUSDT", "ETHUSDT", "BTCUSDT")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                symbols.forEach { sym ->
                    FilterChip(
                        selected = selectedSymbol == sym,
                        onClick = {
                            selectedSymbol = sym
                            entryPriceInput = defaultReferencePriceString(sym)
                        },
                        label = {
                            Text(
                                text = sym,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.testTag("calc_symbol_chip_$sym")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("LONG", "SHORT").forEach { sideOption ->
                    FilterChip(
                        selected = selectedSide == sideOption,
                        onClick = { selectedSide = sideOption },
                        label = {
                            Text(
                                text = "$sideOption Position",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("calc_side_chip_$sideOption")
                    )
                }
            }

            // Account Balance & Quick Capital Presets ($50 / $100 / $250 / $500)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = balanceInput,
                    onValueChange = { balanceInput = it },
                    label = { Text("Account Balance (USDT)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("calc_balance_input")
                )

                OutlinedTextField(
                    value = entryPriceInput,
                    onValueChange = { entryPriceInput = it },
                    label = { Text("Entry Price (USDT)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("calc_entry_price_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(50, 100, 250, 500).forEach { presetUsd ->
                    OutlinedButton(
                        onClick = { balanceInput = "$presetUsd.00" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("calc_preset_balance_$presetUsd")
                    ) {
                        Text(
                            text = "$$presetUsd",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }

            // Stop-Loss Percentage Input & Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stop-Loss Distance (%): ${"%.2f".format(stopLossPctSlider)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = AstraAmberWarn
                    )
                    Text(
                        text = "SL Price: $${formatPriceForTick(preview.stopLossPrice, preview.symbolFilter.tickSize)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("calc_stop_price_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = stopLossPctSlider,
                        onValueChange = {
                            stopLossPctSlider = it
                            stopLossPctInput = "%.2f".format(it)
                        },
                        valueRange = 0.25f..5.00f,
                        modifier = Modifier
                            .weight(1.6f)
                            .testTag("calc_stop_loss_slider")
                    )
                    OutlinedTextField(
                        value = stopLossPctInput,
                        onValueChange = { text ->
                            stopLossPctInput = text
                            text.toFloatOrNull()?.let { parsed ->
                                stopLossPctSlider = parsed.coerceIn(0.25f, 5.00f)
                            }
                        },
                        label = { Text("SL %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("calc_stop_loss_pct_input")
                    )
                }
            }

            // Risk Per Trade (%) & Dollar Risk Budget (R)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Account Risk Per Trade: ${"%.2f".format(riskPctSlider)}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "Risk Budget R = $${"%.2f".format(preview.dollarRiskBudgetUsd)} USDT",
                        style = MaterialTheme.typography.labelMedium,
                        color = AstraCyanAccent,
                        modifier = Modifier.testTag("calc_risk_budget_text")
                    )
                }
                Slider(
                    value = riskPctSlider,
                    onValueChange = { riskPctSlider = it },
                    valueRange = 0.20f..1.50f,
                    modifier = Modifier.testTag("calc_risk_pct_slider")
                )
            }

            // Reward Multiple & Max Leverage Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Target TP Multiple: ${"%.1f".format(rewardMultipleSlider)}x SL (${"%.2f".format(preview.takeProfitPct)}%)",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = rewardMultipleSlider,
                        onValueChange = { rewardMultipleSlider = it },
                        valueRange = 1.6f..4.0f,
                        modifier = Modifier.testTag("calc_reward_multiple_slider")
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Max Leverage Cap: ${leverageSlider}x Isolated",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = leverageSlider.toFloat(),
                        onValueChange = { leverageSlider = it.roundToInt().coerceIn(1, 5) },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.testTag("calc_leverage_slider")
                    )
                }
            }

            // Calculated Position Output Summary Grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = if (preview.sizingResult.approved) AstraEmeraldGreen.copy(alpha = 0.6f) else AstraCrimsonDanger.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Calculated Position Sizing Output (${preview.symbol} · minNotional $${"%.0f".format(preview.symbolFilter.minNotional)} · stepSize ${preview.symbolFilter.stepSize})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AstraCyanAccent
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalcOutputCell(
                        label = "Position Size (Qty)",
                        value = "${preview.sizingResult.quantity} ${preview.symbol.removeSuffix("USDT")}",
                        valueColor = if (preview.sizingResult.approved) AstraEmeraldGreen else AstraAmberWarn,
                        testTag = "calc_output_qty_text",
                        modifier = Modifier.weight(1f)
                    )
                    CalcOutputCell(
                        label = "Position Notional",
                        value = "$${"%.2f".format(preview.sizingResult.notionalUsd)} USDT",
                        valueColor = if (preview.sizingResult.approved) AstraEmeraldGreen else AstraAmberWarn,
                        testTag = "calc_output_notional_text",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalcOutputCell(
                        label = "Est. Loss @ Stop (incl. Fees)",
                        value = "$${"%.2f".format(preview.sizingResult.estimatedMaxLossUsd)} / $${"%.2f".format(preview.dollarRiskBudgetUsd)}",
                        valueColor = if (preview.sizingResult.estimatedMaxLossUsd <= preview.dollarRiskBudgetUsd + 0.02) AstraEmeraldGreen else AstraCrimsonDanger,
                        testTag = "calc_output_max_loss_text",
                        modifier = Modifier.weight(1f)
                    )
                    CalcOutputCell(
                        label = "Eff. Lev / Net R:R",
                        value = "${preview.sizingResult.effectiveLeverage}x · ${"%.2f".format(preview.sizingResult.rewardToRiskRatio)}R",
                        valueColor = AstraCyanAccent,
                        testTag = "calc_output_rr_text",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalcOutputCell(
                        label = "Take-Profit Price",
                        value = "$${formatPriceForTick(preview.takeProfitPrice, preview.symbolFilter.tickSize)}",
                        valueColor = AstraEmeraldGreen,
                        modifier = Modifier.weight(1f)
                    )
                    CalcOutputCell(
                        label = "Est. Liquidation Price",
                        value = if (preview.sizingResult.estimatedLiquidationPrice > 0.0) {
                            "$${formatPriceForTick(preview.sizingResult.estimatedLiquidationPrice, preview.symbolFilter.tickSize)} (${"%.1f".format(preview.sizingResult.liquidationBufferPct)}%)"
                        } else {
                            "N/A"
                        },
                        valueColor = AstraAmberWarn,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Stop Distance vs. Liquidation Buffer Safety Bar (Must be <= 35% of Liq Buffer)
                if (preview.sizingResult.liquidationBufferPct > 0.0) {
                    val ratioToLiq = (preview.sizingResult.stopDistancePct / preview.sizingResult.liquidationBufferPct)
                        .toFloat()
                        .coerceIn(0f, 1f)
                    val safeRatio = ratioToLiq <= 0.35f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Stop / Liquidation Buffer Ratio: ${"%.1f".format(ratioToLiq * 100f)}% (Ceiling: 35.0%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (safeRatio) AstraEmeraldGreen else AstraCrimsonDanger
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(AstraSurfaceOutline)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratioToLiq)
                                    .fillMaxHeight()
                                    .background(if (safeRatio) AstraEmeraldGreen else AstraCrimsonDanger)
                            )
                        }
                    }
                }

                if (!preview.sizingResult.approved && preview.sizingResult.rejectionReason != null) {
                    Text(
                        text = "Rejection Reason: ${preview.sizingResult.rejectionReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstraCrimsonDanger,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("calc_rejection_reason_text")
                    )
                }
            }

            if (onApplyRiskParameters != null) {
                Button(
                    onClick = {
                        onApplyRiskParameters(riskPctSlider.toDouble(), leverageSlider)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apply_calculator_to_strategy_button")
                ) {
                    Text(
                        text = "Apply Risk (${"%.2f".format(riskPctSlider)}%) & Leverage (${leverageSlider}x) to Strategy",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CalcOutputCell(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AstraSurfaceCard)
            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

private fun defaultReferencePrice(symbol: String): Double = when (symbol.uppercase()) {
    "BTCUSDT" -> 68450.0
    "ETHUSDT" -> 2640.0
    "SOLUSDT" -> 158.50
    "DOGEUSDT" -> 0.14250
    else -> 100.0
}

private fun defaultReferencePriceString(symbol: String): String = when (symbol.uppercase()) {
    "BTCUSDT" -> "68450.0"
    "ETHUSDT" -> "2640.00"
    "SOLUSDT" -> "158.50"
    "DOGEUSDT" -> "0.14250"
    else -> "100.00"
}

private fun formatPriceForTick(price: Double, tickSize: Double): String {
    return when {
        tickSize <= 0.00001 -> "%.5f".format(price)
        tickSize <= 0.001 -> "%.4f".format(price)
        tickSize <= 0.01 -> "%.2f".format(price)
        else -> "%.1f".format(price)
    }
}
