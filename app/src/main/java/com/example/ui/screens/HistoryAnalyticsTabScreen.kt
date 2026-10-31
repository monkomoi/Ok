package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.CashflowEntity
import com.example.data.local.DailySnapshotEntity
import com.example.data.local.FillEntity
import com.example.data.local.OrderIntentEntity
import com.example.domain.BacktestSummary
import com.example.domain.DataSourceType
import com.example.domain.PerformanceSelfMonitoringReport
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import java.math.BigDecimal

@Composable
fun HistoryAnalyticsTabScreen(
    dailySnapshots: List<DailySnapshotEntity>,
    fills: List<FillEntity>,
    orderIntents: List<OrderIntentEntity>,
    cashflows: List<CashflowEntity>,
    backtestSummary: BacktestSummary?,
    onRecordCashflow: (String, String, String) -> Unit,
    onExportSanitizedDiagnostics: () -> String,
    performanceReport: PerformanceSelfMonitoringReport = PerformanceSelfMonitoringReport()
) {
    var cashflowAmountInput by remember { mutableStateOf("2.00") }
    var cashflowNoteInput by remember { mutableStateOf("Nạp/rút kiểm tra sổ cái INV-18") }
    var exportedBundleJson by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Out-of-Sample Walk-Forward Backtest Card (Section 12.4 & 16.2 — strictly separated from live results)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Kiểm định Ngoài mẫu (Out-of-Sample Walk-Forward · Tách biệt với LIVE)",
                    style = MaterialTheme.typography.titleMedium,
                    color = AstraCyanAccent
                )
                if (backtestSummary == null) {
                    Text(
                        text = "Chưa có dữ liệu kiểm định ngoài mẫu.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Surface(
                        color = if (backtestSummary.dataSource == DataSourceType.DEMO_SIMULATED) {
                            AstraAmberWarn.copy(alpha = 0.16f)
                        } else {
                            AstraEmeraldGreen.copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Nguồn nến Backtest: ${backtestSummary.dataSource.badgeVi} (Tách biệt hoàn toàn khỏi LIVE)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (backtestSummary.dataSource == DataSourceType.DEMO_SIMULATED) AstraAmberWarn else AstraEmeraldGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "Symbol: ${backtestSummary.symbol} (${backtestSummary.timeframe} · ${backtestSummary.barsTested} nến đã đóng)\n" +
                            "• Tổng số lệnh: ${backtestSummary.totalTrades} (Thắng: ${backtestSummary.winCount} | Thua: ${backtestSummary.lossCount})\n" +
                            "• Lợi nhuận ròng sau phí & trượt giá: ${backtestSummary.netReturnUsdt} USDT (Phí+Slippage: ${backtestSummary.totalFeesAndSlippageUsdt} USDT)\n" +
                            "• Expectancy/lệnh: ${backtestSummary.expectancyUsdt} USDT · Max Drawdown: ${backtestSummary.maxDrawdownPct}%\n" +
                            "• So sánh Buy & Hold cùng kỳ: ${backtestSummary.buyAndHoldReturnUsdt} USDT",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = backtestSummary.notesVi,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 1B. Section 18 · Self-Monitoring & Strategy Performance Report + Build Fingerprint
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Báo cáo Tự Giám sát & Hiệu năng Chiến lược (Mục 18)",
                    style = MaterialTheme.typography.titleMedium,
                    color = AstraEmeraldGreen
                )
                Text(
                    text = "Build Fingerprint: ${performanceReport.buildFingerprint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AstraCyanAccent,
                    modifier = Modifier.testTag("build_fingerprint_text")
                )
                Text(
                    text = "• Win Rate: ${performanceReport.winRatePct}% (${performanceReport.winCount}W / ${performanceReport.lossCount}L trên ${performanceReport.totalClosedTrades} lệnh đóng)\n" +
                        "• Profit Factor: ${performanceReport.profitFactor} | Expectancy/lệnh: ${performanceReport.expectancyUsdt} USDT\n" +
                        "• Avg Win: ${performanceReport.avgWinUsdt} USDT | Avg Loss: ${performanceReport.avgLossUsdt} USDT\n" +
                        "• Tổng Phí & Funding: ${performanceReport.totalFeesAndFundingUsdt} USDT (${performanceReport.feeToGrossPnlRatioPct}% Gross PnL)\n" +
                        "• Tỷ lệ từ chối tín hiệu (Rejection Rate): ${performanceReport.signalRejectionRatePct}% (${performanceReport.rejectedSignalsCount}/${performanceReport.totalSignalsEvaluated} tín hiệu)\n" +
                        "• Độ trễ AI (p50 / p95): ${performanceReport.aiLatencyP50Ms}ms / ${performanceReport.aiLatencyP95Ms}ms · WS Reconnects: ${performanceReport.wsReconnectCount}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 2. Cashflow Ledger & PnL Adjustment (Section 13.1 & INV-18)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Sổ cái Dòng tiền Nạp/Rút & Tách PnL (Mục 13.1 · INV-18)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Công thức: cashflow_adjusted_pnl = equity_now - equity_start - net_external_cashflow. Khi tài khoản Binance chưa xác minh (UNVERIFIED), hệ thống không tự tính PnL từ vốn khai báo VNĐ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = cashflowAmountInput,
                        onValueChange = { cashflowAmountInput = it },
                        label = { Text("Số tiền (USDT)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_cashflow_amount")
                    )
                    OutlinedTextField(
                        value = cashflowNoteInput,
                        onValueChange = { cashflowNoteInput = it },
                        label = { Text("Ghi chú sổ cái") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onRecordCashflow("DEPOSIT", cashflowAmountInput, cashflowNoteInput) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("deposit_cashflow_button")
                    ) {
                        Text("+ Ghi nhận Nạp USDT")
                    }
                    OutlinedButton(
                        onClick = { onRecordCashflow("WITHDRAW", cashflowAmountInput, cashflowNoteInput) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("withdraw_cashflow_button")
                    ) {
                        Text("- Ghi nhận Rút USDT")
                    }
                }

                if (cashflows.isNotEmpty()) {
                    cashflows.take(4).forEach { cf ->
                        Text(
                            text = "• [${cf.type}] ${cf.amountUsdt} USDT — ${cf.noteVi}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (cf.amountUsdt.startsWith("-")) AstraAmberWarn else AstraEmeraldGreen
                        )
                    }
                }
            }
        }

        // 3. Equity Curve Canvas & Trade Fills Ledger (Section 12.4)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Biểu đồ PnL Ròng & Sổ cái Khớp lệnh (Fills Ledger)",
                    style = MaterialTheme.typography.titleMedium
                )

                val pnlSeries = remember(fills) {
                    val chronological = fills.reversed()
                    val points = mutableListOf(0f)
                    var running = 0f
                    chronological.forEach { f ->
                        running += f.realizedPnlUsdt.toFloatOrNull() ?: 0f
                        points.add(running)
                    }
                    if (points.size == 1) listOf(0f, 0.01f, 0.02f) else points
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    val minVal = pnlSeries.minOrNull() ?: 0f
                    val maxVal = pnlSeries.maxOrNull() ?: 1f
                    val span = (maxVal - minVal).coerceAtLeast(0.001f)
                    val path = Path()
                    pnlSeries.forEachIndexed { idx, v ->
                        val x = (idx.toFloat() / (pnlSeries.size - 1).coerceAtLeast(1)) * size.width
                        val y = size.height - (((v - minVal) / span) * size.height)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = AstraEmeraldGreen,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawLine(
                        color = AstraSurfaceOutline,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                if (fills.isEmpty()) {
                    Text(
                        text = "Chưa có giao dịch hoàn tất trong sổ cái Fills.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    fills.take(8).forEach { fill ->
                        val pnlBd = fill.realizedPnlUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${fill.symbol} · ${fill.side} (Qty: ${fill.quantity} @ ${fill.price})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "${fill.realizedPnlUsdt} USDT",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (pnlBd >= BigDecimal.ZERO) AstraEmeraldGreen else AstraCrimsonDanger,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Phí: ${fill.commissionUsdt} ${fill.commissionAsset} · Lý do: ${fill.reasonVi}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 4. Persisted OrderIntents (INV-04) & Sanitized Diagnostic Bundle Export (MOD-18)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Nhật ký OrderIntent (INV-04) & Xuất Chẩn đoán Đã Che Bí mật (MOD-18)",
                    style = MaterialTheme.typography.titleMedium
                )
                orderIntents.take(4).forEach { intent ->
                    Text(
                        text = "• [${intent.status}] ${intent.clientOrderId} · ${intent.symbol} ${intent.positionDirection} Qty=${intent.quantity} (Risk=${intent.intendedRiskUsdt} USDT)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(
                    onClick = { exportedBundleJson = onExportSanitizedDiagnostics() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_diagnostics_button")
                ) {
                    Text("Xuất Gói Chẩn đoán JSON (Đã loại bỏ API Secret)")
                }

                if (exportedBundleJson != null) {
                    Text(
                        text = exportedBundleJson!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraCyanAccent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}
