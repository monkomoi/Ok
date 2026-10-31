package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AppSettingsEntity
import com.example.data.local.DailySnapshotEntity
import com.example.data.local.PositionEntity
import com.example.domain.AccountCapitalState
import com.example.domain.SizingDecision
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Quantitative Risk & Exposure Summary UI Component (MOD-11 / Section 6).
 * Uses Jetpack Compose Canvas charts (radial exposure gauges + stacked cost/risk decomposition bar chart)
 * to visualize real-time position exposure, stop-loss risk budget utilization, leverage cap, and drawdown limits.
 */
@Composable
fun QuantitativeRiskSummaryCard(
    settings: AppSettingsEntity,
    accountCapitalState: AccountCapitalState,
    activePositions: List<PositionEntity>,
    sizingDecision: SizingDecision?,
    dailySnapshot: DailySnapshotEntity?,
    modifier: Modifier = Modifier
) {
    val riskPerTradeLimitPct = (settings.riskPerTradePct.toDoubleOrNull() ?: 0.005) * 100.0
    val dailyLossLimitPct = (settings.dailyLossLimitPct.toDoubleOrNull() ?: 0.015) * 100.0
    val maxDrawdownLimitPct = (settings.maxDrawdownLimitPct.toDoubleOrNull() ?: 0.05) * 100.0
    val leverageCap = settings.defaultLeverageCap.coerceIn(1, 5)

    // Reference equity: verified exchange equity if available, otherwise sizing decision equity or declared estimate
    val referenceEquity = accountCapitalState.exchangeEquityUsdt
        ?: sizingDecision?.equityUsdt
        ?: accountCapitalState.estimatedCapitalFromVndUsdt

    val activePos = activePositions.firstOrNull()
    val activeLeverage = activePos?.leverage ?: (sizingDecision?.leverage ?: 1)
    val leverageRatio = (activeLeverage.toFloat() / leverageCap.toFloat()).coerceIn(0f, 1f)

    // Risk budget utilization (Estimated Loss at Stop / Risk Budget R)
    val riskBudgetUsdt = sizingDecision?.riskBudgetUsdt ?: referenceEquity.multiply(
        settings.riskPerTradePct.toBigDecimalOrNull() ?: BigDecimal("0.005")
    ).setScale(4, RoundingMode.HALF_UP)

    val estLossAtStopUsdt = sizingDecision?.totalEstimatedLossAtStopUsdt ?: BigDecimal.ZERO
    val riskUtilizationRatio = if (riskBudgetUsdt > BigDecimal.ZERO) {
        estLossAtStopUsdt.divide(riskBudgetUsdt, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1.25f)
    } else {
        0f
    }

    // Current drawdown from DailySnapshotEntity (peakEquityUsdt vs currentEquityUsdt)
    val currentDrawdownPct = run {
        val peak = dailySnapshot?.peakEquityUsdt?.toBigDecimalOrNull()
        val curr = dailySnapshot?.currentEquityUsdt?.toBigDecimalOrNull()
        if (peak != null && curr != null && peak > BigDecimal.ZERO && curr < peak) {
            peak.subtract(curr)
                .divide(peak, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
                .toDouble()
        } else {
            0.0
        }
    }
    val drawdownRatio = if (maxDrawdownLimitPct > 0.0) {
        (currentDrawdownPct / maxDrawdownLimitPct).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    // Margin & Notional exposure metrics
    val notionalUsdt = activePos?.let {
        val qty = it.signedQuantity.toBigDecimalOrNull()?.abs() ?: BigDecimal.ZERO
        val mark = it.markPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
        qty.multiply(mark).setScale(4, RoundingMode.HALF_UP)
    } ?: (sizingDecision?.notionalUsdt ?: BigDecimal.ZERO)

    val initialMarginUsdt = activePos?.isolatedMarginUsdt?.toBigDecimalOrNull()
        ?: (sizingDecision?.initialMarginUsdt ?: BigDecimal.ZERO)

    val marginExposurePct = if (referenceEquity > BigDecimal.ZERO) {
        initialMarginUsdt.divide(referenceEquity, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(2, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }

    // Decomposition of Estimated Loss at Stop: Price Stop Distance vs Fees vs Slippage vs Funding
    val feeUsdt = sizingDecision?.estimatedFeesUsdt ?: BigDecimal.ZERO
    val slippageUsdt = sizingDecision?.estimatedSlippageUsdt ?: BigDecimal.ZERO
    val fundingUsdt = sizingDecision?.estimatedFundingUsdt ?: BigDecimal.ZERO
    val frictionTotalUsdt = feeUsdt.add(slippageUsdt).add(fundingUsdt)
    val pureStopPriceLossUsdt = estLossAtStopUsdt.subtract(frictionTotalUsdt).max(BigDecimal.ZERO)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("quant_risk_summary_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
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
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Kiểm soát rủi ro định lượng",
                        tint = AstraCyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Biểu đồ Kiểm soát Rủi ro & Phơi nhiễm (MOD-11)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = " Vốn tính toán: ${referenceEquity.setScale(4, RoundingMode.HALF_UP)} USDT · Vị thế mở: ${activePositions.size}/1 · Cơ sở SL: ${settings.stopTriggerWorkingType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val gateApproved = sizingDecision?.approved == true
                Surface(
                    color = if (gateApproved) {
                        AstraEmeraldGreen.copy(alpha = 0.16f)
                    } else {
                        AstraAmberWarn.copy(alpha = 0.16f)
                    },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.testTag("quant_risk_gate_status_badge")
                ) {
                    Text(
                        text = if (gateApproved) "RISK GATE: PASS" else "RISK GATE: GUARDED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (gateApproved) AstraEmeraldGreen else AstraAmberWarn,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 1. Three Radial Arc Gauges Row (Compose Canvas)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(vertical = 12.dp, horizontal = 8.dp)
                    .testTag("quant_risk_radial_gauges_row"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RiskRadialGaugeItem(
                    title = "Ngân sách lỗ (R)",
                    valueText = "${(riskUtilizationRatio * 100f).toInt()}%",
                    subtitle = "${estLossAtStopUsdt.setScale(3, RoundingMode.HALF_UP)} / ${riskBudgetUsdt.setScale(3, RoundingMode.HALF_UP)}U",
                    progress = riskUtilizationRatio.coerceIn(0f, 1f),
                    accentColor = when {
                        riskUtilizationRatio > 1.0f -> AstraCrimsonDanger
                        riskUtilizationRatio > 0.8f -> AstraAmberWarn
                        else -> AstraEmeraldGreen
                    },
                    modifier = Modifier.weight(1f)
                )

                RiskRadialGaugeItem(
                    title = "Đòn bẩy / Trần",
                    valueText = "${activeLeverage}x / ${leverageCap}x",
                    subtitle = "Margin: $marginExposurePct%",
                    progress = leverageRatio,
                    accentColor = when {
                        leverageRatio >= 1.0f -> AstraAmberWarn
                        else -> AstraCyanAccent
                    },
                    modifier = Modifier.weight(1f)
                )

                RiskRadialGaugeItem(
                    title = "Drawdown / Trần",
                    valueText = String.format("%.2f%%", currentDrawdownPct),
                    subtitle = "Max: ${String.format("%.1f%%", maxDrawdownLimitPct)} (Ngày ${String.format("%.1f%%", dailyLossLimitPct)})",
                    progress = drawdownRatio,
                    accentColor = when {
                        drawdownRatio >= 0.85f -> AstraCrimsonDanger
                        drawdownRatio >= 0.50f -> AstraAmberWarn
                        else -> AstraEmeraldGreen
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // 2. Stacked Risk & Friction Cost Breakdown Bar Chart (Compose Canvas)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .testTag("quant_risk_cost_breakdown_chart"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phân rã Rủi ro Dừng lỗ & Chi phí Ma sát (Stop-Loss + Fee + Slippage + Funding)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AstraCyanAccent,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Notional: ${notionalUsdt.setScale(2, RoundingMode.HALF_UP)} USDT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val totalDecomp = pureStopPriceLossUsdt
                    .add(feeUsdt)
                    .add(slippageUsdt)
                    .add(fundingUsdt)
                    .max(BigDecimal("0.0001"))

                val stopShare = pureStopPriceLossUsdt.divide(totalDecomp, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                val feeShare = feeUsdt.divide(totalDecomp, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                val slipShare = slippageUsdt.divide(totalDecomp, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                val fundShare = fundingUsdt.divide(totalDecomp, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                ) {
                    val barWidth = size.width
                    val barHeight = size.height
                    // Background track
                    drawRoundRect(
                        color = AstraSurfaceOutline.copy(alpha = 0.45f),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(9f, 9f)
                    )

                    var currentX = 0f
                    val segments = listOf(
                        stopShare to AstraEmeraldGreen,
                        feeShare to AstraCyanAccent,
                        slipShare to AstraAmberWarn,
                        fundShare to AstraCrimsonDanger
                    )
                    segments.forEach { (fraction, segColor) ->
                        val segW = (barWidth * fraction).coerceAtMost(barWidth - currentX)
                        if (segW > 0.5f) {
                            drawRect(
                                color = segColor,
                                topLeft = Offset(currentX, 0f),
                                size = Size(segW, barHeight)
                            )
                            currentX += segW
                        }
                    }
                }

                // Legend Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LegendDotItem(
                        color = AstraEmeraldGreen,
                        label = "Biên độ SL: ${pureStopPriceLossUsdt.setScale(4, RoundingMode.HALF_UP)}U"
                    )
                    LegendDotItem(
                        color = AstraCyanAccent,
                        label = "Phí: ${feeUsdt.setScale(4, RoundingMode.HALF_UP)}U"
                    )
                    LegendDotItem(
                        color = AstraAmberWarn,
                        label = "Trượt giá: ${slippageUsdt.setScale(4, RoundingMode.HALF_UP)}U"
                    )
                    LegendDotItem(
                        color = AstraCrimsonDanger,
                        label = "Funding: ${fundingUsdt.setScale(4, RoundingMode.HALF_UP)}U"
                    )
                }

                // Exposure comparison horizontal bars (Notional vs Equity & Risk Per Trade vs Daily Limit)
                ExposureComparisonBar(
                    label = "Tỷ lệ Rủi ro / Lệnh so với Trần lỗ ngày (${String.format("%.2f%%", riskPerTradeLimitPct)} / ${String.format("%.2f%%", dailyLossLimitPct)})",
                    ratio = if (dailyLossLimitPct > 0.0) (riskPerTradeLimitPct / dailyLossLimitPct).toFloat().coerceIn(0f, 1f) else 0.33f,
                    barColor = AstraCyanAccent
                )
            }
        }
    }
}

@Composable
private fun RiskRadialGaugeItem(
    title: String,
    valueText: String,
    subtitle: String,
    progress: Float,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(76.dp)
        ) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val strokeWidth = 7.dp.toPx()
                val arcDiameter = size.minDimension - strokeWidth
                val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                val arcSize = Size(arcDiameter, arcDiameter)

                // Background arc (240 degrees sweep from 150 deg)
                drawArc(
                    color = AstraSurfaceOutline,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground active arc
                drawArc(
                    color = accentColor,
                    startAngle = 150f,
                    sweepAngle = 240f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LegendDotItem(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ExposureComparisonBar(
    label: String,
    ratio: Float,
    barColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${(ratio * 100f).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            drawRoundRect(
                color = AstraSurfaceOutline.copy(alpha = 0.45f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = barColor,
                size = Size(size.width * ratio.coerceIn(0f, 1f), size.height),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}
