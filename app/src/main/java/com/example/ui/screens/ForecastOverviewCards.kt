package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.forecast.domain.ForecastDiagnosticsSnapshot
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.HorizonForecast
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline

@Composable
fun ForecastOverviewSection(
    selectedSymbol: String,
    forecastReport: MultiHorizonForecastReport?,
    forecastDiagnostics: ForecastDiagnosticsSnapshot?,
    operatingMode: String,
    isBusy: Boolean,
    onCycleForecastMode: () -> Unit,
    onForceRefreshForecast: () -> Unit
) {
    val symResult = forecastReport?.symbolReports?.get(selectedSymbol)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("forecast_overview_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-Device Multi-Horizon Forecast (15m / 1h / 4h)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Model: ${forecastDiagnostics?.modelId ?: "astra_gru_tcn_hybrid_v1"} (${forecastDiagnostics?.artifactFormat ?: "WEIGHTS_COMPACT_JSON_INT8"})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (symResult?.headConsistent == false) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = operatingMode,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (symResult?.headConsistent == false) AstraCrimsonDanger else AstraCyanAccent,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("forecast_mode_badge")
                    )
                }
            }

            if (symResult != null) {
                val statusColor = when (symResult.status) {
                    ForecastStatus.VALID -> AstraEmeraldGreen
                    ForecastStatus.HEAD_INCONSISTENT,
                    ForecastStatus.OUT_OF_DISTRIBUTION,
                    ForecastStatus.MODEL_MISMATCH -> AstraCrimsonDanger
                    else -> AstraAmberWarn
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$selectedSymbol · Eligibility: ${symResult.symbolEligibility.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AstraCyanAccent
                    )
                    Text(
                        text = "Status: ${symResult.status.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.testTag("forecast_status_text")
                    )
                }

                Text(
                    text = symResult.statusReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Multi-Horizon Rows (15m, 60m, 240m)
                listOf(15, 60, 240).forEach { hMin ->
                    val h = symResult.horizons[hMin]
                    if (h != null) {
                        HorizonForecastRow(hMin = hMin, horizon = h)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ForecastMetricPill(
                        label = "Dir Score",
                        value = "%+.2f".format(symResult.compositeDirectionalScore),
                        modifier = Modifier.weight(1f)
                    )
                    ForecastMetricPill(
                        label = "Confidence",
                        value = "${"%.0f".format(symResult.compositeConfidence * 100)}%",
                        modifier = Modifier.weight(1f)
                    )
                    ForecastMetricPill(
                        label = "Head Sync",
                        value = if (symResult.headConsistent) "CONSISTENT" else "CONTRADICTION",
                        valueColor = if (symResult.headConsistent) AstraEmeraldGreen else AstraCrimsonDanger,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    text = "Awaiting sequence evaluation for $selectedSymbol...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Diagnostics Telemetry Bar (S7 Edge Latency, Brier, ECE, PSI)
            if (forecastDiagnostics != null) {
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
                        text = "S7 Edge CPU Telemetry & Calibration Health",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraCyanAccent
                    )
                    Text(
                        text = "Latency p50/p95/p99: ${forecastDiagnostics.p50LatencyMs}/${forecastDiagnostics.p95LatencyMs}/${forecastDiagnostics.p99LatencyMs} ms · Inferences: ${forecastDiagnostics.totalInferences}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("forecast_latency_text")
                    )
                    Text(
                        text = "Rolling 15m Brier: ${"%.3f".format(forecastDiagnostics.rollingBrier15m)} · ECE: ${"%.3f".format(forecastDiagnostics.rollingEce15m)} · Drift PSI: ${"%.3f".format(forecastDiagnostics.featureDriftPsi)} · Trust: ${forecastDiagnostics.dataTrustState.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCycleForecastMode,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cycle_forecast_mode_button")
                ) {
                    Text(
                        text = "Mode: $operatingMode",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onForceRefreshForecast,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("refresh_forecast_button")
                ) {
                    Text(
                        text = "Re-run Inference",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun HorizonForecastRow(hMin: Int, horizon: HorizonForecast) {
    val label = when (hMin) {
        15 -> "15m"
        60 -> "1h"
        240 -> "4h"
        else -> "${hMin}m"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Horizon $label (N=${horizon.calibrationSampleSize} · ${horizon.calibrationQualificationStatus})",
                style = MaterialTheme.typography.labelSmall,
                color = AstraCyanAccent
            )
            Text(
                text = "E[r]=${"%+.3f".format(horizon.expectedReturnPct)}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (horizon.expectedReturnPct >= 0.0) AstraEmeraldGreen else AstraCrimsonDanger
            )
        }
        Text(
            text = "P(Down)=${"%.1f".format(horizon.pDown * 100)}% · P(Flat)=${"%.1f".format(horizon.pFlat * 100)}% · P(Up)=${"%.1f".format(horizon.pUp * 100)}% | Q10/Q50/Q90: ${"%+.2f".format(horizon.q10Pct)}% / ${"%+.2f".format(horizon.q50Pct)}% / ${"%+.2f".format(horizon.q90Pct)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ForecastMetricPill(
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
