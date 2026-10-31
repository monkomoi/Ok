package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.StrategyConfigEntity
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import kotlin.math.roundToInt

@Composable
fun StrategyRiskTabScreen(
    config: StrategyConfigEntity,
    accountBalanceUsd: Double = 150.0,
    selectedSymbol: String = "DOGEUSDT",
    onUpdateStrategyConfig: (StrategyConfigEntity) -> Unit
) {
    var leverage by remember(config.maxLeverage) { mutableIntStateOf(config.maxLeverage.coerceIn(1, 5)) }
    var riskPerTradePct by remember(config.riskPerTradePct) { mutableFloatStateOf(config.riskPerTradePct.toFloat().coerceIn(0.20f, 1.50f)) }
    var dailyLossCapPct by remember(config.dailyLossCapPct) { mutableFloatStateOf(config.dailyLossCapPct.toFloat().coerceIn(0.50f, 3.00f)) }
    var atrStopMult by remember(config.atrStopMultiplier) { mutableFloatStateOf(config.atrStopMultiplier.toFloat().coerceIn(1.20f, 3.00f)) }
    var atrTpMult by remember(config.atrTakeProfitMultiplier) { mutableFloatStateOf(config.atrTakeProfitMultiplier.toFloat().coerceIn(2.00f, 5.00f)) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Interactive Position Sizing & Stop-Loss Percentage Calculator Component
        PositionSizingCalculatorCard(
            initialSymbol = selectedSymbol,
            initialBalanceUsd = accountBalanceUsd,
            initialRiskPerTradePct = config.riskPerTradePct,
            initialStopLossPct = 1.20,
            initialMaxLeverage = config.maxLeverage,
            onApplyRiskParameters = { appliedRiskPct, appliedLeverage ->
                riskPerTradePct = appliedRiskPct.toFloat()
                leverage = appliedLeverage
                onUpdateStrategyConfig(
                    config.copy(
                        maxLeverage = appliedLeverage,
                        riskPerTradePct = appliedRiskPct
                    )
                )
            }
        )

        // 2. Global Strategy & Daily Drawdown Guardrails Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("strategy_risk_config_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Small-Capital Risk Guardrails ($50–$500 Profile)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Isolated margin enforced. Leverage is hard-capped at 5x and risk per trade is bounded in [0.20%, 1.50%].",
                    style = MaterialTheme.typography.bodySmall,
                    color = AstraCyanAccent
                )

                // Max Leverage Slider (1x .. 5x)
                Column {
                    Text(
                        text = "Max Isolated Leverage: ${leverage}x (Hard Cap: 5x)",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = leverage.toFloat(),
                        onValueChange = { leverage = it.roundToInt().coerceIn(1, 5) },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.testTag("slider_max_leverage")
                    )
                }

                // Per-Trade Risk Budget %
                Column {
                    Text(
                        text = "Per-Trade Loss Budget (r): ${"%.2f".format(riskPerTradePct)}% of Equity",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = riskPerTradePct,
                        onValueChange = { riskPerTradePct = it },
                        valueRange = 0.20f..1.50f,
                        modifier = Modifier.testTag("slider_risk_per_trade")
                    )
                }

                // Daily Loss Cap %
                Column {
                    Text(
                        text = "Daily Realized + Unrealized Loss Cap: ${"%.2f".format(dailyLossCapPct)}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = dailyLossCapPct,
                        onValueChange = { dailyLossCapPct = it },
                        valueRange = 0.50f..3.00f,
                        modifier = Modifier.testTag("slider_daily_loss_cap")
                    )
                }

                // ATR Stop Loss & Take Profit Multipliers
                Column {
                    Text(
                        text = "ATR(14) Stop-Loss Multiplier: ${"%.2f".format(atrStopMult)}x ATR",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = atrStopMult,
                        onValueChange = { atrStopMult = it },
                        valueRange = 1.20f..3.00f
                    )
                }

                Column {
                    Text(
                        text = "ATR(14) Take-Profit Multiplier: ${"%.2f".format(atrTpMult)}x ATR",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = atrTpMult,
                        onValueChange = { atrTpMult = it },
                        valueRange = 2.00f..5.00f
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep Safety Debug Lock Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = AstraEmeraldGreen
                        )
                        Text(
                            text = "Blocks live real-money order placement until all mode-transition criteria pass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.safetyDebugLockActive,
                        onCheckedChange = { locked ->
                            onUpdateStrategyConfig(config.copy(safetyDebugLockActive = locked))
                        },
                        modifier = Modifier.testTag("switch_safety_debug_lock")
                    )
                }

                Button(
                    onClick = {
                        onUpdateStrategyConfig(
                            config.copy(
                                maxLeverage = leverage,
                                riskPerTradePct = riskPerTradePct.toDouble(),
                                dailyLossCapPct = dailyLossCapPct.toDouble(),
                                atrStopMultiplier = atrStopMult.toDouble(),
                                atrTakeProfitMultiplier = atrTpMult.toDouble()
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_strategy_config_button")
                ) {
                    Text("Save Risk & Strategy Parameters")
                }
            }
        }
    }
}
