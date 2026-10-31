package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.PositionEntity
import com.example.data.local.StrategyConfigEntity
import com.example.domain.PositionSizingResult
import com.example.domain.QuantRiskSummary
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline

@Composable
fun QuantitativeRiskSummaryCard(
    config: StrategyConfigEntity,
    equityUsd: Double,
    availableMarginUsd: Double,
    activePositions: List<PositionEntity>,
    riskSummary: QuantRiskSummary,
    latestSizing: PositionSizingResult?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quant_risk_summary_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Small-Capital Loss-Budget Risk Engine ($50–$500 Profile)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Formula: R = min(Equity × ${"%.2f".format(config.riskPerTradePct)}%, Remaining Daily Loss Cap) · Max Lev: ${config.maxLeverage}x ISOLATED",
                style = MaterialTheme.typography.bodySmall,
                color = AstraCyanAccent
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RiskMetricBox(
                    label = "Per-Trade Budget (R)",
                    value = "$${"%.2f".format(riskSummary.perTradeRiskBudgetUsd)}",
                    valueColor = AstraEmeraldGreen,
                    modifier = Modifier.weight(1f)
                )
                RiskMetricBox(
                    label = "Daily Loss Rem.",
                    value = "$${"%.2f".format(riskSummary.remainingDailyLossBudgetUsd)}",
                    valueColor = if (riskSummary.remainingDailyLossBudgetUsd > 0.5) AstraEmeraldGreen else AstraCrimsonDanger,
                    modifier = Modifier.weight(1f)
                )
                RiskMetricBox(
                    label = "Open Positions",
                    value = "${activePositions.size}/2",
                    valueColor = AstraCyanAccent,
                    modifier = Modifier.weight(1f)
                )
            }

            if (latestSizing != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (latestSizing.approved) {
                            "Sizing Gate: APPROVED (Qty=${latestSizing.quantity}, Notional=$${"%.2f".format(latestSizing.notionalUsd)}, Lev=${latestSizing.effectiveLeverage}x)"
                        } else {
                            "Sizing Gate: REJECTED — ${latestSizing.rejectionReason}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (latestSizing.approved) AstraEmeraldGreen else AstraAmberWarn,
                        modifier = Modifier.testTag("sizing_gate_status_text")
                    )
                    Text(
                        text = "Est. Max Loss @ Stop: $${"%.2f".format(latestSizing.estimatedMaxLossUsd)} · Stop Dist: ${"%.2f".format(latestSizing.stopDistancePct)}% · Liq Buffer: ${"%.2f".format(latestSizing.liquidationBufferPct)}% · Net R:R: ${"%.2f".format(latestSizing.rewardToRiskRatio)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskMetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            maxLines = 1
        )
    }
}
