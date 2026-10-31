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
import androidx.compose.material.icons.filled.Lock
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
import com.example.data.local.PositionEntity
import com.example.data.local.StrategyConfigEntity
import com.example.domain.DataTrustState
import com.example.domain.KillSwitchLevel
import com.example.domain.PositionSizingResult
import com.example.domain.QuantRiskSummary
import com.example.engine.CycleEvaluationSnapshot
import com.example.forecast.domain.ForecastDiagnosticsSnapshot
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline

@Composable
fun OverviewTabScreen(
    config: StrategyConfigEntity,
    dataTrustState: DataTrustState,
    selectedSymbol: String,
    accountVerified: Boolean,
    equityUsd: Double,
    availableMarginUsd: Double,
    activePositions: List<PositionEntity>,
    cycleSnapshot: CycleEvaluationSnapshot?,
    riskSummary: QuantRiskSummary,
    latestSizing: PositionSizingResult?,
    forecastReport: MultiHorizonForecastReport?,
    forecastDiagnostics: ForecastDiagnosticsSnapshot?,
    foregroundServiceActive: Boolean,
    isBusy: Boolean,
    onSelectSymbol: (String) -> Unit,
    onRunEvaluationCycle: () -> Unit,
    onCycleForecastMode: () -> Unit,
    onTriggerKillSwitch: (KillSwitchLevel) -> Unit,
    onToggleForegroundService: (Boolean) -> Unit
) {
    var showPanicDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Blank-Slate Data Trust & Safety Lock Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = AstraEmeraldGreen.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp)
                )
                .testTag("overview_status_card"),
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
                        color = AstraEmeraldGreen,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${config.environmentMode} · ${config.forecastOperatingMode}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("mode_badge")
                        )
                    }

                    val trustColor = when (dataTrustState) {
                        DataTrustState.SHADOW_READY,
                        DataTrustState.PAPER_READY,
                        DataTrustState.LIVE_ELIGIBLE -> AstraEmeraldGreen
                        DataTrustState.QUARANTINED -> AstraCrimsonDanger
                        else -> AstraAmberWarn
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.border(1.dp, trustColor, RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = "TRUST: ${dataTrustState.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = trustColor,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("data_trust_badge")
                        )
                    }
                }

                if (config.safetyDebugLockActive) {
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
                                contentDescription = "Safety Lock Active",
                                tint = AstraAmberWarn
                            )
                            Text(
                                text = "SAFETY LOCK ENGAGED: Live order execution disabled. Running in Blank-Slate Shadow/Test evaluation mode (zero synthetic PnL).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.testTag("safety_lock_banner")
                            )
                        }
                    }
                }

                if (config.killSwitchLevel != "NONE") {
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
                                contentDescription = "Kill Switch Active",
                                tint = AstraCrimsonDanger
                            )
                            Text(
                                text = "KILL SWITCH ACTIVE: ${config.killSwitchLevel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.testTag("kill_switch_banner")
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
                            text = "TradingForegroundService (S7 Edge / API 30)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Low-overhead background market & stop reconciliation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = foregroundServiceActive,
                        onCheckedChange = onToggleForegroundService,
                        modifier = Modifier.testTag("foreground_service_switch")
                    )
                }
            }
        }

        // 2. Account Balance & Blank-Slate Verification Card
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
                    Text(
                        text = "BINANCE USDⓈ-M FUTURES CAPITAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (accountVerified) "EXCHANGE VERIFIED" else "UNVERIFIED (PAPER REFERENCE)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (accountVerified) AstraEmeraldGreen else AstraAmberWarn,
                        modifier = Modifier.testTag("account_verification_badge")
                    )
                }

                Text(
                    text = if (accountVerified) {
                        "Verified Equity: $${"%.2f".format(equityUsd)} USDT"
                    } else {
                        "Reference Paper Capital: $${"%.2f".format(equityUsd)} USDT (Exchange Unverified)"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (accountVerified) AstraEmeraldGreen else AstraCyanAccent,
                    modifier = Modifier.testTag("equity_usdt_text")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OverviewMiniMetric(
                        label = "Available Margin",
                        value = "$${"%.2f".format(availableMarginUsd)} USDT",
                        modifier = Modifier.weight(1f)
                    )
                    OverviewMiniMetric(
                        label = "Realized PnL (Blank-Slate)",
                        value = "$0.00 USDT (${riskSummary.sampleTradeCount} trades)",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Symbol Selector & Multi-Timeframe Regime Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "USDⓈ-M Perpetual Scanner & Technical Regime",
                    style = MaterialTheme.typography.titleMedium
                )

                val symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT")
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
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag("chip_symbol_$sym")
                        )
                    }
                }

                if (cycleSnapshot != null) {
                    val tech = cycleSnapshot.technicalSignal
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${cycleSnapshot.symbol} · Mark: $${"%.4f".format(cycleSnapshot.markPrice)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Regime: ${tech.regime.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = AstraCyanAccent
                            )
                        }
                        Text(
                            text = "Signal: ${tech.side} (Score ${"%.1f".format(tech.technicalScore)}) · RSI14: ${"%.1f".format(tech.rsi14)} · ADX14: ${"%.1f".format(tech.adx14)} · ATR%: ${"%.2f".format(tech.atrPct)}% · RVOL: ${"%.2f".format(tech.relativeVolume)}x",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Spread: ${"%.2f".format(cycleSnapshot.spreadBps)} bps · Funding: ${"%+.4f".format(cycleSnapshot.fundingRatePct)}% · News Gate: ${cycleSnapshot.newsGateState.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Cycle Decision: ${cycleSnapshot.cycleDecisionSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AstraEmeraldGreen,
                            modifier = Modifier.testTag("cycle_decision_summary_text")
                        )
                    }
                }

                // Control Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRunEvaluationCycle,
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scan_evaluate_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isBusy) "Evaluating..." else "Scan & Evaluate",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { showPanicDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AstraCrimsonDanger),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("panic_close_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Panic Kill-Switch",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 4-Level Kill Switch Quick Controls (PAUSE / DRAIN / RESUME)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onTriggerKillSwitch(KillSwitchLevel.PAUSE) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_bot_button")
                    ) {
                        Text("Pause", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onTriggerKillSwitch(KillSwitchLevel.DRAIN) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("drain_bot_button")
                    ) {
                        Text("Drain", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onTriggerKillSwitch(KillSwitchLevel.NONE) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("resume_bot_button")
                    ) {
                        Text("Resume", maxLines = 1)
                    }
                }
            }
        }

        // 4. Quantitative Loss-Budget Risk Card
        QuantitativeRiskSummaryCard(
            config = config,
            equityUsd = equityUsd,
            availableMarginUsd = availableMarginUsd,
            activePositions = activePositions,
            riskSummary = riskSummary,
            latestSizing = latestSizing
        )

        // 5. On-Device Multi-Horizon Forecast Card & S7 Edge Runtime Diagnostics
        ForecastOverviewSection(
            selectedSymbol = selectedSymbol,
            forecastReport = forecastReport,
            forecastDiagnostics = forecastDiagnostics,
            operatingMode = config.forecastOperatingMode,
            isBusy = isBusy,
            onCycleForecastMode = onCycleForecastMode,
            onForceRefreshForecast = onRunEvaluationCycle
        )

        // 6. Active Positions Verification Card
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
                        imageVector = Icons.Default.Security,
                        contentDescription = "Verified Positions",
                        tint = AstraEmeraldGreen
                    )
                    Text(
                        text = "Exchange-Verified Open Positions (${activePositions.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (activePositions.isEmpty()) {
                    Text(
                        text = "0 active positions. Blank-slate safety rule: no synthetic or unverified positions are ever seeded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("no_active_position_text")
                    )
                } else {
                    activePositions.forEach { pos ->
                        Text(
                            text = "${pos.symbol} · ${pos.positionSide} · Qty=${pos.positionAmt} @ $${pos.entryPrice} · SL=${pos.stopLossPrice} · TP=${pos.takeProfitPrice}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AstraEmeraldGreen
                        )
                    }
                }
            }
        }
    }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text("Confirm PANIC_CLOSE (2-Step Verification)") },
            text = {
                Text(
                    "This immediately triggers Level 3 PANIC_CLOSE: blocks all new entries, cancels working orders, and flattens any open positions."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPanicDialog = false
                        onTriggerKillSwitch(KillSwitchLevel.PANIC_CLOSE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AstraCrimsonDanger),
                    modifier = Modifier.testTag("confirm_panic_dialog_button")
                ) {
                    Text("Execute PANIC_CLOSE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun OverviewMiniMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
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
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
