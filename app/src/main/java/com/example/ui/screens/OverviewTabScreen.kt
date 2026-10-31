package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AppSettingsEntity
import com.example.data.local.DailySnapshotEntity
import com.example.data.local.PositionEntity
import com.example.domain.AccountCapitalState
import com.example.domain.AiModuleStatus
import com.example.domain.BotState
import com.example.domain.DataSourceType
import com.example.domain.DeviceHealthSnapshot
import com.example.domain.EnvironmentMode
import com.example.domain.FeatureSnapshot
import com.example.domain.LiveActivationChecklist
import com.example.domain.NetEvEstimate
import com.example.domain.ScannerCandidateItem
import com.example.domain.SizingDecision
import com.example.domain.SkepticCheckResult
import com.example.domain.SkepticVerdict
import com.example.forecast.domain.ForecastDiagnosticsSnapshot
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.network.ClockSyncStatus
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OverviewTabScreen(
    settings: AppSettingsEntity,
    safetyDebugLockActive: Boolean,
    botState: BotState,
    accountCapitalState: AccountCapitalState,
    aiModuleStatus: AiModuleStatus,
    dailySnapshot: DailySnapshotEntity?,
    activePositions: List<PositionEntity>,
    featureSnapshot: FeatureSnapshot?,
    evEstimate: NetEvEstimate?,
    skepticResult: SkepticCheckResult?,
    sizingDecision: SizingDecision?,
    deviceHealth: DeviceHealthSnapshot,
    clockStatus: ClockSyncStatus,
    liveChecklist: LiveActivationChecklist,
    selectedSymbol: String,
    isBusy: Boolean,
    onSelectSymbol: (String) -> Unit,
    onScanAndEvaluate: () -> Unit,
    onReconcile: () -> Unit,
    onExecuteApprovedCandidate: () -> Unit,
    onPause: () -> Unit,
    onDrain: () -> Unit,
    onResume: () -> Unit,
    onPanicCloseConfirmed: () -> Unit,
    onSwitchEnvironment: (EnvironmentMode, Boolean) -> Unit,
    onToggleForegroundService: (Boolean) -> Unit,
    scannerCandidates: List<ScannerCandidateItem> = emptyList(),
    onRunMultiSymbolScanner: () -> Unit = {},
    onMoveStopToNetBreakeven: () -> Unit = {},
    forecastReport: MultiHorizonForecastReport? = null,
    forecastDiagnostics: ForecastDiagnosticsSnapshot = ForecastDiagnosticsSnapshot(),
    onToggleForecastMode: () -> Unit = {},
    onToggleForecastRollback: (Boolean) -> Unit = {}
) {
    var showPanicDialog by remember { mutableStateOf(false) }
    var showLiveChecklistDialog by remember { mutableStateOf(false) }
    var serviceEnabled by remember { mutableStateOf(false) }

    val isLive = settings.environmentMode == "LIVE" && !safetyDebugLockActive
    val vndFormat = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Environment Mode, Safety Debug Lock & Bot State Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (isLive) AstraCrimsonDanger else AstraEmeraldGreen.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp)
                ),
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
                    Surface(
                        color = if (isLive) AstraCrimsonDanger else AstraEmeraldGreen,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isLive) "LIVE · TÀI KHOẢN THẬT" else "TEST · KIỂM THỬ AN TOÀN",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("mode_badge")
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (isLive) {
                                onSwitchEnvironment(EnvironmentMode.TEST, false)
                            } else {
                                showLiveChecklistDialog = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("toggle_live_mode_button")
                    ) {
                        Text(
                            text = if (isLive) "Về TEST" else "Checklist LIVE",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Requirement 1: Safety Lock Banner during Auth Debug
                if (safetyDebugLockActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Khóa an toàn LIVE",
                                tint = AstraAmberWarn
                            )
                            Text(
                                text = "KHÓA AN TOÀN LIVE ĐANG BẬT (Mục 1): Tắt khả năng gửi lệnh LIVE trong lúc kiểm tra lỗi xác thực & khi tài khoản chưa xác minh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.testTag("safety_lock_banner")
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Trạng thái State Machine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${botState.name} · ${botState.labelVi}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (botState.isErrorOrPaused) AstraAmberWarn else AstraEmeraldGreen,
                        modifier = Modifier.testTag("bot_state_text")
                    )
                }

                if (settings.activeCircuitBreakerReason != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Circuit Breaker",
                                tint = AstraCrimsonDanger
                            )
                            Text(
                                text = "CIRCUIT BREAKER: ${settings.activeCircuitBreakerReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TradingForegroundService (MOD-01)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Thông báo không âm thanh · WakeLock giới hạn 10 phút",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = { enabled ->
                            serviceEnabled = enabled
                            onToggleForegroundService(enabled)
                        },
                        modifier = Modifier.testTag("foreground_service_switch")
                    )
                }
            }
        }

        // 2. Strictly Separated Exchange Equity vs Declared VND Capital Card (Requirement 3)
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    Text(
                        text = "SỐ DƯ TÀI KHOẢN BINANCE (MỤC 3)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = if (accountCapitalState.verificationState.isVerified) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = accountCapitalState.verificationState.badgeVi,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (accountCapitalState.verificationState.isVerified) {
                                AstraEmeraldGreen
                            } else {
                                AstraCrimsonDanger
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("account_verification_badge")
                        )
                    }
                }

                // Exchange Equity (Strictly NULL / UNVERIFIED until verified from Binance)
                val exchangeEqText = accountCapitalState.exchangeEquityUsdt?.let {
                    "${it.setScale(4, RoundingMode.HALF_UP)} USDT"
                } ?: "UNVERIFIED / UNKNOWN"
                val exchangeAvailText = accountCapitalState.exchangeAvailableBalanceUsdt?.let {
                    "${it.setScale(4, RoundingMode.HALF_UP)} USDT"
                } ?: "UNVERIFIED / UNKNOWN"

                Text(
                    text = "Equity sàn: $exchangeEqText",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (accountCapitalState.exchangeEquityUsdt != null) AstraEmeraldGreen else AstraAmberWarn,
                    modifier = Modifier.testTag("equity_usdt_text")
                )

                val verifiedTimeStr = accountCapitalState.verifiedAtUtcMs?.let {
                    "Cập nhật từ sàn lúc ${timeFormat.format(Date(it))}"
                } ?: "Chưa có phản hồi xác thực từ /fapi/v2/account"

                Text(
                    text = "Nguồn: ${accountCapitalState.dataSource.badgeVi} · $verifiedTimeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Vault: ${accountCapitalState.vaultCredentialState.badgeVi} · Endpoint: ${accountCapitalState.activeEndpointTarget}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AstraCyanAccent,
                    modifier = Modifier.testTag("overview_vault_and_endpoint_text")
                )

                if (accountCapitalState.lastFailedStep != null || accountCapitalState.lastFailedBinanceCode != null) {
                    Text(
                        text = "Lỗi xác thực gần nhất: Bước=${accountCapitalState.lastFailedStep ?: "N/A"} · Endpoint=${accountCapitalState.lastFailedEndpoint ?: "N/A"} · Code=${accountCapitalState.lastFailedBinanceCode ?: "N/A"} · Msg=\"${accountCapitalState.lastRawBinanceErrorMsg ?: "N/A"}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraCrimsonDanger,
                        modifier = Modifier.testTag("overview_failed_auth_summary_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricMiniBox(
                        label = "Available Balance Sàn",
                        value = exchangeAvailText,
                        valueColor = if (accountCapitalState.exchangeAvailableBalanceUsdt != null) AstraEmeraldGreen else AstraAmberWarn,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniBox(
                        label = "PnL tách nạp/rút",
                        value = if (accountCapitalState.exchangeEquityUsdt != null) {
                            "${dailySnapshot?.cashflowAdjustedPnlUsdt ?: "0.0000"} USDT"
                        } else {
                            "UNVERIFIED"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Separated Reference Declared Capital Box (Never mixed with Exchange Equity!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "VỐN KHAI BÁO THAM KHẢO (TÁCH BIỆT HOÀN TOÀN VỚI EQUITY SÀN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraCyanAccent
                    )
                    Text(
                        text = "Khai báo: ${vndFormat.format(accountCapitalState.declaredCapitalVnd)} VNĐ · Ước tính quy đổi: ~${accountCapitalState.estimatedCapitalFromVndUsdt} USDT (Tỷ giá tham khảo: ${vndFormat.format(accountCapitalState.referenceUsdtVndRate)}đ/USDT · KHÔNG dùng thay cho số dư Binance)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("declared_capital_reference_text")
                    )
                }
            }
        }

        // 3. Active Bot Position & Protective Order Verification Card (INV-03, INV-09, INV-14)
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                            imageVector = Icons.Default.Security,
                            contentDescription = "Bảo vệ vị thế",
                            tint = AstraEmeraldGreen
                        )
                        Text(
                            text = "Vị thế xác minh trên sàn (INV-09)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = "${activePositions.size}/1",
                        style = MaterialTheme.typography.labelLarge,
                        color = AstraCyanAccent
                    )
                }

                if (activePositions.isEmpty()) {
                    Text(
                        text = "Không có vị thế được xác nhận khớp (0 vị thế). Ứng dụng tuyệt đối không tuyên bố có vị thế khi chưa có phản hồi khớp thực tế từ sàn (Mục 6.11).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("no_active_position_text")
                    )
                } else {
                    val pos = activePositions.first()
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${pos.symbol} · Qty: ${pos.signedQuantity} (${pos.leverage}x)",
                                style = MaterialTheme.typography.labelLarge,
                                color = AstraEmeraldGreen
                            )
                            Text(
                                text = if (pos.protectiveSlVerified) "SL SÀN ĐÃ XÁC MINH (#${pos.protectiveSlOrderId})" else "THIẾU SL BẢO VỆ!",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pos.protectiveSlVerified) AstraEmeraldGreen else AstraCrimsonDanger
                            )
                            Text(
                                text = "Giá vào TB: ${pos.entryAvgPrice} · Stop SL: ${pos.stopPrice} · Target TP: ${pos.targetPrice}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Giá hòa vốn ròng ước tính (INV-14): ${pos.netBreakevenEstimate} (Chỉ là ước tính bao gồm phí mở/đóng + funding + trượt giá dự kiến; lệnh thị trường khi kích hoạt vẫn có thể trượt giá)",
                                style = MaterialTheme.typography.bodySmall,
                                color = AstraAmberWarn
                            )
                            OutlinedButton(
                                onClick = onMoveStopToNetBreakeven,
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("move_sl_net_be_button")
                            ) {
                                Text(
                                    text = "Dời SL về Hòa vốn ròng (Mục 7.4: Đặt SL mới trước)",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3B. Quantitative Risk & Exposure Summary Charts (MOD-11 / Section 6)
        QuantitativeRiskSummaryCard(
            settings = settings,
            accountCapitalState = accountCapitalState,
            activePositions = activePositions,
            sizingDecision = sizingDecision,
            dailySnapshot = dailySnapshot
        )

        // 4. Symbol Candidate Scanner, Data Source Badge, AI/Skeptic Status & Risk Gate (Requirement 4 & 5)
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    Text(
                        text = "Quét ứng viên & Risk Gate (MOD-06 / MOD-11)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onRunMultiSymbolScanner,
                        enabled = !isBusy,
                        modifier = Modifier.testTag("run_multi_symbol_scanner_button")
                    ) {
                        Text("Quét Top 5", style = MaterialTheme.typography.labelMedium)
                    }
                }

                val symbols = listOf("DOGEUSDT", "XRPUSDT", "SOLUSDT", "ETHUSDT", "BTCUSDT")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    symbols.forEach { sym ->
                        FilterChip(
                            selected = selectedSymbol == sym,
                            onClick = { onSelectSymbol(sym) },
                            label = {
                                Text(
                                    text = sym,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            modifier = Modifier.testTag("chip_symbol_$sym")
                        )
                    }
                }

                if (scannerCandidates.isNotEmpty()) {
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
                            text = "Xếp hạng Bộ quét Đa cặp (MOD-06 · Lọc theo 24h Vol, Spread & minNotional):",
                            style = MaterialTheme.typography.labelSmall,
                            color = AstraCyanAccent
                        )
                        scannerCandidates.take(3).forEach { cand ->
                            Text(
                                text = "• ${cand.symbol} (Score ${cand.scannerScore}) · minNotional=${cand.minNotionalUsdt} USDT · ${if (cand.feasibleForCurrentCapital) "KHẢ THI MICRO" else "VƯỢT VỐN MICRO"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cand.feasibleForCurrentCapital) AstraEmeraldGreen else AstraAmberWarn
                            )
                        }
                    }
                }

                if (featureSnapshot != null && sizingDecision != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Data Source Badge (Requirement 5: Explicitly label DEMO/SIMULATED vs Public REST)
                            val isDemo = featureSnapshot.dataSource == DataSourceType.DEMO_SIMULATED
                            Surface(
                                color = if (isDemo) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Nguồn nến: ${featureSnapshot.dataSource.badgeVi}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDemo) AstraAmberWarn else AstraCyanAccent,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("market_data_source_badge")
                                )
                            }

                            // Stacked Symbol/Mark Price and Regime Badge to prevent any horizontal overlap on S7 Edge (Requirement 5)
                            Text(
                                text = "${featureSnapshot.symbol} · Mark Price: ${featureSnapshot.markPrice}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Regime: ${featureSnapshot.primaryRegime.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AstraEmeraldGreen,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            Text(
                                text = "ATR%: ${featureSnapshot.atrPct.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)}% · RVOL: ${featureSnapshot.relativeVolume}x · Spread: ${featureSnapshot.spreadPct.multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP)}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // AI & Skeptic Honest Status (Requirement 4)
                            Text(
                                text = "Trạng thái AI: ${aiModuleStatus.labelVi}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AstraCyanAccent,
                                modifier = Modifier.testTag("ai_module_status_text")
                            )

                            if (skepticResult != null) {
                                Text(
                                    text = skepticResult.summaryVi,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (skepticResult.verdict == SkepticVerdict.PASS) AstraEmeraldGreen else AstraAmberWarn,
                                    modifier = Modifier.testTag("skeptic_status_text")
                                )
                            }

                            if (evEstimate != null) {
                                Text(
                                    text = evEstimate.noteVi,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (evEstimate.canUseForLive) AstraEmeraldGreen else AstraAmberWarn
                                )
                            }

                            if (sizingDecision.approved) {
                                Text(
                                    text = "RISK ENGINE APPROVED (${sizingDecision.capitalSource.badgeVi}): Hướng ${sizingDecision.side.name} · Qty=${sizingDecision.roundedQty} (Notional=${sizingDecision.notionalUsdt} USDT) · Lỗ dự kiến=${sizingDecision.totalEstimatedLossAtStopUsdt} USDT <= R (${sizingDecision.riskBudgetUsdt} USDT).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AstraEmeraldGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "RISK ENGINE REJECTED (${sizingDecision.rejectReasonCode}): ${sizingDecision.rejectReasonVi}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AstraCrimsonDanger,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("risk_reject_reason_text")
                                )
                            }
                        }
                    }
                }

                // Action Control Buttons (Formatted for 360dp width without wrapping or overlapping)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onScanAndEvaluate,
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scan_evaluate_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Quét & Tính EV",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onExecuteApprovedCandidate,
                        enabled = !isBusy &&
                            sizingDecision?.approved == true &&
                            activePositions.isEmpty() &&
                            accountCapitalState.verificationState.isVerified,
                        colors = ButtonDefaults.buttonColors(containerColor = AstraEmeraldGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("execute_candidate_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Gửi Entry",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Pause / Drain / Resume Row — clean single-line labels without icons crowding narrow widths (Requirement 5)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPause,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_bot_button")
                    ) {
                        Text(
                            text = "Pause",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = onDrain,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("drain_bot_button")
                    ) {
                        Text(
                            text = "Drain",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = onResume,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("resume_bot_button")
                    ) {
                        Text(
                            text = "Resume",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onReconcile,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reconcile_button")
                    ) {
                        Text(
                            text = "Đối soát 3 nguồn",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = { showPanicDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AstraCrimsonDanger),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("panic_close_button")
                    ) {
                        Text(
                            text = "Panic Close",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 4B. On-Device Time-Series Forecast Card & S7 Edge Runtime Diagnostics Card (Section 12)
        ForecastOverviewSection(
            selectedSymbol = selectedSymbol,
            forecastReport = forecastReport,
            forecastDiagnostics = forecastDiagnostics,
            operatingMode = settings.forecastOperatingMode,
            isBusy = isBusy,
            onToggleForecastMode = onToggleForecastMode,
            onToggleForecastRollback = onToggleForecastRollback
        )

        // 5. Device Health & Clock Sync Telemetry (MOD-04, MOD-16 · S7 Edge RAM Verification)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Thiết bị",
                        tint = AstraCyanAccent
                    )
                    Text(
                        text = "Sức khỏe Thiết bị & Bộ nhớ Thực tế (MOD-16)",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricMiniBox(
                        label = "Pin / Nhiệt độ",
                        value = "${deviceHealth.batteryPct}% · ${deviceHealth.batteryTempCelsius}°C",
                        valueColor = if (deviceHealth.thermalWarning) AstraCrimsonDanger else AstraEmeraldGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniBox(
                        label = "RAM (${deviceHealth.physicalNominalRamGb}GB danh định)",
                        value = "${deviceHealth.availableRamMb}/${deviceHealth.kernelTotalRamMb} MB",
                        valueColor = if (deviceHealth.lowRamWarning) AstraCrimsonDanger else AstraEmeraldGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Nguồn đo RAM: ${deviceHealth.memInfoSource} (Tổng RAM Kernel khả dụng: ${deviceHealth.kernelTotalRamMb} MB / Danh định vật lý ~${deviceHealth.physicalNominalRamGb} GB LPDDR4) · Clock Sync: ${if (clockStatus.synced) "Offset ${clockStatus.offsetMs}ms (RTT ${clockStatus.bestRttMs}ms)" else "Chưa đồng bộ"} · Tối ưu pin LineageOS (IgnoreDoze=${deviceHealth.ignoringBatteryOptimizations}, BgRestricted=${deviceHealth.backgroundRestricted})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("device_ram_summary_text")
                )
            }
        }
    }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text("Xác nhận Đóng Khẩn Cấp (PANIC_CLOSE · Bước 2/2)") },
            text = {
                Text(
                    "Hành động này sẽ khóa mở lệnh mới, gửi lệnh đóng vị thế theo khối lượng thật trên sàn (reduceOnly), đối soát số dư và hủy các lệnh bảo vệ SL/TP mồ côi thuộc bot.\n\nLưu ý: Lệnh đóng khẩn cấp có thể chịu trượt giá bất lợi."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPanicDialog = false
                        onPanicCloseConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AstraCrimsonDanger),
                    modifier = Modifier.testTag("confirm_panic_dialog_button")
                ) {
                    Text("Xác nhận đóng phẳng ngay")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showLiveChecklistDialog) {
        AlertDialog(
            onDismissRequest = { showLiveChecklistDialog = false },
            title = { Text("Checklist Kích Hoạt Chế Độ LIVE (Mục 16.3)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "LIVE luôn bị khóa khi xác thực hoặc đối soát chưa đạt (Mục 1 & 6.9):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ChecklistRow("Đã mở Khóa an toàn Debug (Mục 1)", liveChecklist.safetyDebugLockDisengaged)
                    ChecklistRow("Đã nhập API Key & Secret hợp lệ trong SecretVault", liveChecklist.apiKeyConfigured)
                    ChecklistRow("Đã xác minh tài khoản Binance (/fapi/v2/account)", liveChecklist.accountVerifiedFromBinance)
                    ChecklistRow("Đã xác nhận tắt quyền Rút tiền (Withdraw OFF)", liveChecklist.withdrawDisabledConfirmed)
                    ChecklistRow("Đồng bộ đồng hồ Binance (Clock Sync RTT)", liveChecklist.clockSyncHealthy)
                    ChecklistRow("Đối soát hoàn tất & Không có lệnh UNKNOWN", liveChecklist.reconciliationCompleted)
                    ChecklistRow("Không có Circuit Breaker đang kích hoạt", liveChecklist.noActiveCircuitBreaker)
                    ChecklistRow("Nhiệt độ & RAM thiết bị ổn định", liveChecklist.deviceHealthStable)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLiveChecklistDialog = false
                        onSwitchEnvironment(EnvironmentMode.LIVE, true)
                    },
                    enabled = liveChecklist.canEnableLive,
                    modifier = Modifier.testTag("confirm_live_activation_button")
                ) {
                    Text("Kích hoạt LIVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLiveChecklistDialog = false }) {
                    Text("Đóng (Giữ TEST)")
                }
            }
        )
    }
}

@Composable
private fun ChecklistRow(label: String, passed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (passed) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (passed) AstraEmeraldGreen else AstraAmberWarn
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (passed) MaterialTheme.colorScheme.onSurface else AstraAmberWarn
        )
    }
}

@Composable
fun MetricMiniBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
