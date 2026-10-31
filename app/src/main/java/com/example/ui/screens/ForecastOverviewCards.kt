package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.forecast.domain.ForecastDiagnosticsSnapshot
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Section 12 UI/UX Integration for the On-Device Time-Series Forecasting Engine
 * and S7 Edge Runtime Diagnostics.
 * Designed specifically for 360dp narrow screens (max 2 metrics per row, no text clipping).
 */
@Composable
fun ForecastOverviewSection(
    selectedSymbol: String,
    forecastReport: MultiHorizonForecastReport?,
    forecastDiagnostics: ForecastDiagnosticsSnapshot,
    operatingMode: String,
    isBusy: Boolean,
    onToggleForecastMode: () -> Unit,
    onToggleForecastRollback: (Boolean) -> Unit
) {
    var selectedHorizon by rememberSaveable { mutableStateOf("15m") }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }

    val horizonResult = forecastReport?.horizonResults?.get(selectedHorizon)
        ?: forecastReport?.primaryResult15m

    // 1. On-Device Time-Series Forecast Card (Section 12.1)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("forecast_engine_card"),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Dự báo chuỗi thời gian",
                        tint = AstraCyanAccent
                    )
                    Text(
                        text = "Dự báo On-Device ($selectedSymbol)",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onToggleForecastMode,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("toggle_forecast_mode_button")
                ) {
                    Text(
                        text = if (operatingMode == ForecastOperatingMode.ADVISORY_TEST_ONLY.name) {
                            "Chế độ: ADVISORY"
                        } else {
                            "Chế độ: SHADOW"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Horizon Selector Chips (15m | 1h | 4h)
            val horizons = listOf("15m", "1h", "4h")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                horizons.forEach { h ->
                    FilterChip(
                        selected = selectedHorizon == h,
                        onClick = { selectedHorizon = h },
                        label = {
                            Text(
                                text = "Horizon $h",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.testTag("forecast_horizon_chip_$h")
                    )
                }
            }

            // Status Badge
            val status = horizonResult?.status ?: forecastReport?.overallStatus ?: ForecastStatus.INSUFFICIENT_DATA
            val statusColor = when (status) {
                ForecastStatus.READY_VALIDATED -> AstraEmeraldGreen
                ForecastStatus.UNCALIBRATED, ForecastStatus.STALE -> AstraAmberWarn
                else -> AstraCrimsonDanger
            }
            Surface(
                color = statusColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Trạng thái Forecast: ${status.badgeVi}",
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("forecast_status_badge")
                )
            }

            if (horizonResult == null) {
                Text(
                    text = "Chưa có kết quả dự báo cho $selectedSymbol. Nhấn 'Quét & Tính EV' để chạy pipeline 64 nến đóng trên thiết bị.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("forecast_empty_state_text")
                )
            } else {
                // Probability Distribution Bar & Values (pUp / pFlat / pDown)
                val pUpVal = horizonResult.pUp
                val pFlatVal = horizonResult.pFlat
                val pDownVal = horizonResult.pDown

                if (pUpVal != null && pFlatVal != null && pDownVal != null) {
                    val pUpPct = pUpVal.multiply(BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                    val pFlatPct = pFlatVal.multiply(BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                    val pDownPct = pDownVal.multiply(BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Xác suất sau hiệu chuẩn (Cost-Threshold ≥ ${horizonResult.costThresholdPct}%):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                        ) {
                            val upW = pUpVal.toFloat().coerceAtLeast(0.02f)
                            val flatW = pFlatVal.toFloat().coerceAtLeast(0.02f)
                            val downW = pDownVal.toFloat().coerceAtLeast(0.02f)
                            Box(
                                modifier = Modifier
                                    .weight(upW)
                                    .fillMaxHeight()
                                    .background(AstraEmeraldGreen)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(flatW)
                                    .fillMaxHeight()
                                    .background(AstraCyanAccent.copy(alpha = 0.6f))
                            )
                            Box(
                                modifier = Modifier
                                    .weight(downW)
                                    .fillMaxHeight()
                                    .background(AstraCrimsonDanger)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "pUp: $pUpPct%",
                                style = MaterialTheme.typography.labelMedium,
                                color = AstraEmeraldGreen,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("forecast_p_up_text")
                            )
                            Text(
                                text = "pFlat: $pFlatPct%",
                                style = MaterialTheme.typography.labelMedium,
                                color = AstraCyanAccent,
                                modifier = Modifier.testTag("forecast_p_flat_text")
                            )
                            Text(
                                text = "pDown: $pDownPct%",
                                style = MaterialTheme.typography.labelMedium,
                                color = AstraCrimsonDanger,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("forecast_p_down_text")
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Xác suất bị ẩn do Fail-Closed (${status.name}) — Tuyệt đối không sinh xác suất giả 0.5/0.3/0.2.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstraAmberWarn,
                        modifier = Modifier.testTag("forecast_fail_closed_prob_text")
                    )
                }

                // Expected Return & Quantiles (Max 2 metrics per row)
                val expRetStr = horizonResult.expectedReturnPct?.let { "${if (it >= BigDecimal.ZERO) "+" else ""}$it%" } ?: "N/A"
                val quantilesStr = if (horizonResult.q10Pct != null && horizonResult.q50Pct != null && horizonResult.q90Pct != null) {
                    "${horizonResult.q10Pct}% / ${horizonResult.q50Pct}% / ${horizonResult.q90Pct}%"
                } else {
                    "N/A"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricMiniBox(
                        label = "Kỳ vọng lợi nhuận (${horizonResult.horizon})",
                        value = expRetStr,
                        valueColor = when {
                            horizonResult.expectedReturnPct == null -> AstraAmberWarn
                            horizonResult.expectedReturnPct > BigDecimal.ZERO -> AstraEmeraldGreen
                            horizonResult.expectedReturnPct < BigDecimal.ZERO -> AstraCrimsonDanger
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniBox(
                        label = "Phân vị Q10 / Q50 / Q90",
                        value = quantilesStr,
                        valueColor = AstraCyanAccent,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Volatility, Uncertainty & OOD Score (Max 2 metrics per row)
                val volStr = horizonResult.predictedVolatilityPct?.let { "$it%" } ?: "N/A"
                val uncOodStr = if (horizonResult.uncertainty != null && horizonResult.outOfDistributionScore != null) {
                    "Unc=${horizonResult.uncertainty} · OOD=${horizonResult.outOfDistributionScore}"
                } else {
                    "OOD=${horizonResult.outOfDistributionScore ?: "N/A"}"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricMiniBox(
                        label = "Biến động dự báo (Sigma)",
                        value = volStr,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniBox(
                        label = "Bất định & Điểm OOD",
                        value = uncOodStr,
                        valueColor = if ((horizonResult.outOfDistributionScore ?: BigDecimal.ZERO) > BigDecimal("4.20")) {
                            AstraCrimsonDanger
                        } else {
                            AstraEmeraldGreen
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Calibration, Model Version, Schema Hash & Timestamps
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
                        text = "Hiệu chuẩn: ${if (horizonResult.calibrationApplied) "ĐÃ HIỆU CHUẨN" else "CHƯA HIỆU CHUẨN"} (${horizonResult.calibrationMethod} · N=${horizonResult.calibrationSampleSize}) · Brier=${horizonResult.brierScore ?: "N/A"} · ECE=${horizonResult.expectedCalibrationError ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (horizonResult.calibrationApplied) AstraEmeraldGreen else AstraAmberWarn,
                        modifier = Modifier.testTag("forecast_calibration_text")
                    )
                    Text(
                        text = "Model: ${horizonResult.modelId} (v${horizonResult.modelVersion}) · SchemaHash=${horizonResult.featureSchemaHash}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstraCyanAccent,
                        modifier = Modifier.testTag("forecast_model_version_text")
                    )
                    Text(
                        text = "Tạo lúc: ${timeFormat.format(Date(horizonResult.generatedAtMs))} · Hết hạn: ${timeFormat.format(Date(horizonResult.validUntilMs))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Quyết định Quality Gate [${horizonResult.advisoryAction}]: ${horizonResult.reasonVi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (horizonResult.passesQualityGate) AstraEmeraldGreen else AstraAmberWarn,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("forecast_gate_reason_text")
                    )
                }
            }
        }
    }

    // 2. S7 Edge Runtime Diagnostics Card (Section 12.2)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s7_edge_forecast_diagnostics_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Chẩn đoán Runtime S7 Edge",
                        tint = AstraEmeraldGreen
                    )
                    Text(
                        text = "Chẩn đoán Runtime Dự báo (S7 Edge)",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = { onToggleForecastRollback(!forecastDiagnostics.isRollbackActive) },
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("toggle_forecast_rollback_button")
                ) {
                    Text(
                        text = if (forecastDiagnostics.isRollbackActive) "Khôi phục Champion" else "Rollback Baseline",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricMiniBox(
                    label = "Model Đang Chạy",
                    value = if (forecastDiagnostics.isRollbackActive) {
                        "ROLLBACK: ${forecastDiagnostics.rollbackModelId}"
                    } else {
                        forecastDiagnostics.activeModelId
                    },
                    valueColor = if (forecastDiagnostics.isRollbackActive) AstraAmberWarn else AstraEmeraldGreen,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniBox(
                    label = "Backend & CPU Threads",
                    value = "${forecastDiagnostics.runtimeBackend} (${forecastDiagnostics.cpuThreadsConfigured}T)",
                    valueColor = AstraCyanAccent,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricMiniBox(
                    label = "Load / Latency P50 · P95",
                    value = "${forecastDiagnostics.modelLoadTimeMs}ms / ${forecastDiagnostics.inferenceLatencyP50Ms}ms · ${forecastDiagnostics.inferenceLatencyP95Ms}ms",
                    valueColor = if (forecastDiagnostics.inferenceLatencyP95Ms <= 120L) AstraEmeraldGreen else AstraAmberWarn,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniBox(
                    label = "Native Heap / RAM",
                    value = "+${forecastDiagnostics.nativeHeapDeltaKb} KB · ${forecastDiagnostics.availableRamMb} MB",
                    valueColor = if (forecastDiagnostics.throttledDueToThermalOrRam) AstraCrimsonDanger else AstraEmeraldGreen,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricMiniBox(
                    label = "Nhiệt độ / Thermal Gate",
                    value = "${forecastDiagnostics.batteryTempCelsius}°C (${forecastDiagnostics.thermalStatusLabel})",
                    valueColor = if (forecastDiagnostics.throttledDueToThermalOrRam) AstraCrimsonDanger else AstraEmeraldGreen,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniBox(
                    label = "Tổng dự báo / Stale / OOD",
                    value = "${forecastDiagnostics.totalPredictionsGenerated} / ${forecastDiagnostics.staleOrMissingCount} / ${forecastDiagnostics.oodRejectedCount}",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "ABI: ${forecastDiagnostics.primaryAbi} · SHA-256 Bundle: ${if (forecastDiagnostics.bundleChecksumVerified) "VERIFIED" else "UNVERIFIED"} · Golden Self-Test: ${if (forecastDiagnostics.goldenSelfTestPassed) "PASSED" else "FAILED"} · Lỗi liên tiếp: ${forecastDiagnostics.consecutiveErrors}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("s7_edge_diagnostics_summary_text")
            )
        }
    }
}
