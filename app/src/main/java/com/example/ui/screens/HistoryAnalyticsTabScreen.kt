package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.ForecastEvaluationEntity
import com.example.data.local.SignalLogEntity
import com.example.data.local.TradeOrderEntity
import com.example.domain.DataTrustState
import com.example.domain.QuantRiskSummary
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline

@Composable
fun HistoryAnalyticsTabScreen(
    dataTrustState: DataTrustState,
    riskSummary: QuantRiskSummary,
    tradeOrders: List<TradeOrderEntity>,
    signalLogs: List<SignalLogEntity>,
    forecastEvaluations: List<ForecastEvaluationEntity>
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Blank-Slate Performance & Trust Quarantine Summary
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_analytics_summary_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Blank-Slate Performance Ledger (Trust: ${dataTrustState.name})",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Only verified paper/live fills are counted here. Bundled golden vectors and offline warmup bars are strictly quarantined as TEST_FIXTURE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalyticsMetricCell(
                        label = "Closed Trades",
                        value = "${riskSummary.sampleTradeCount}",
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsMetricCell(
                        label = "Win Rate",
                        value = if (riskSummary.sampleTradeCount > 0) "${"%.1f".format(riskSummary.winRatePct)}%" else "N/A (0 trades)",
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsMetricCell(
                        label = "Profit Factor",
                        value = if (riskSummary.sampleTradeCount > 0) "%.2f".format(riskSummary.profitFactor) else "N/A",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalyticsMetricCell(
                        label = "Sharpe (Ann.)",
                        value = if (riskSummary.sampleTradeCount >= 5) "%.2f".format(riskSummary.sharpeAnnualized) else "N/A (<5 trades)",
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsMetricCell(
                        label = "Max Drawdown",
                        value = "${"%.2f".format(riskSummary.maxDrawdownPct)}%",
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsMetricCell(
                        label = "Half-Kelly",
                        value = "${"%.2f".format(riskSummary.kellyHalfFractionPct)}%",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. Trade Orders Ledger
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Order & Execution Ledger (${tradeOrders.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                if (tradeOrders.isEmpty()) {
                    Text(
                        text = "No trade orders recorded yet (Blank-Slate database).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("empty_trade_ledger_text")
                    )
                } else {
                    tradeOrders.take(15).forEach { ord ->
                        Text(
                            text = "${ord.symbol} · ${ord.side} ${ord.quantity} @ $${ord.price} · Status=${ord.status} · PnL=$${"%.2f".format(ord.realizedPnl)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ord.realizedPnl >= 0) AstraEmeraldGreen else AstraCrimsonDanger
                        )
                    }
                }
            }
        }

        // 3. Multi-Horizon Shadow Forecast Evaluation Log
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Multi-Horizon Forecast Observations (${forecastEvaluations.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                if (forecastEvaluations.isEmpty()) {
                    Text(
                        text = "No real-time forecast evaluations persisted yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    forecastEvaluations.take(10).forEach { ev ->
                        Text(
                            text = "${ev.symbol} ${ev.horizonMinutes}m · P(D/F/U)=${"%.0f".format(ev.pDown * 100)}/${"%.0f".format(ev.pFlat * 100)}/${"%.0f".format(ev.pUp * 100)}% · E[r]=${"%+.2f".format(ev.expectedReturnPct)}% · Sync=${ev.headConsistent} · Tag=${ev.dataTrustTag}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ev.headConsistent) AstraCyanAccent else AstraAmberWarn
                        )
                    }
                }
            }
        }

        // 4. Signal & AI Vetting Audit Log
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Signal & Dual-Agent Vetting Log (${signalLogs.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                if (signalLogs.isEmpty()) {
                    Text(
                        text = "No signal candidates logged yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    signalLogs.take(10).forEach { sig ->
                        Text(
                            text = "${sig.symbol} [${sig.regime}] · ${sig.finalAction} · Score=${"%.1f".format(sig.technicalScore)} · ${sig.rationale}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsMetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
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
            color = AstraCyanAccent,
            maxLines = 1
        )
    }
}
