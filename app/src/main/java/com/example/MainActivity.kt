package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AstraViewModel
import com.example.ui.screens.ApiIntegrationTabScreen
import com.example.ui.screens.HistoryAnalyticsTabScreen
import com.example.ui.screens.OverviewTabScreen
import com.example.ui.screens.StrategyRiskTabScreen
import com.example.ui.screens.SystemLogsTabScreen
import com.example.ui.theme.AstraAmberContainer
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonContainer
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraEmeraldContainer
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceElevated
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AstraTraderApp()
            }
        }
    }
}

private data class AstraNavTab(
    val index: Int,
    val titleRes: Int,
    val tag: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun AstraTraderApp(
    viewModel: AstraViewModel = viewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // BackHandler for secondary tabs to return to Overview Tab 0
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val botState by viewModel.coordinator.botState.collectAsStateWithLifecycle()
    val safetyDebugLockActive by viewModel.coordinator.safetyDebugLockActive.collectAsStateWithLifecycle()
    val accountCapitalState by viewModel.coordinator.accountCapitalState.collectAsStateWithLifecycle()
    val authDiagnosticReport by viewModel.coordinator.authDiagnosticReport.collectAsStateWithLifecycle()
    val outboundNetworkIdentity by viewModel.coordinator.outboundNetworkIdentity.collectAsStateWithLifecycle()
    val aiModuleStatus by viewModel.coordinator.aiModuleStatus.collectAsStateWithLifecycle()
    val featureSnapshot by viewModel.coordinator.latestFeatureSnapshot.collectAsStateWithLifecycle()
    val evEstimate by viewModel.coordinator.latestEvEstimate.collectAsStateWithLifecycle()
    val skepticResult by viewModel.coordinator.latestSkepticResult.collectAsStateWithLifecycle()
    val sizingDecision by viewModel.coordinator.latestSizingDecision.collectAsStateWithLifecycle()
    val backtestSummary by viewModel.coordinator.latestBacktest.collectAsStateWithLifecycle()
    val deviceHealth by viewModel.coordinator.deviceHealth.collectAsStateWithLifecycle()
    val clockStatus by viewModel.binanceAdapter.clockState.collectAsStateWithLifecycle()
    val liveChecklist by viewModel.coordinator.checklist.collectAsStateWithLifecycle()
    val connectivityState by viewModel.connectivityState.collectAsStateWithLifecycle()

    val dailySnapshots by viewModel.dailySnapshotsFlow.collectAsStateWithLifecycle()
    val activePositions by viewModel.activePositionsFlow.collectAsStateWithLifecycle()
    val recentSignals by viewModel.signalsFlow.collectAsStateWithLifecycle()
    val providers by viewModel.providersFlow.collectAsStateWithLifecycle()
    val aiDecisions by viewModel.aiDecisionsFlow.collectAsStateWithLifecycle()
    val fills by viewModel.fillsFlow.collectAsStateWithLifecycle()
    val orderIntents by viewModel.orderIntentsFlow.collectAsStateWithLifecycle()
    val cashflows by viewModel.cashflowsFlow.collectAsStateWithLifecycle()
    val riskEvents by viewModel.riskEventsFlow.collectAsStateWithLifecycle()
    val systemEvents by viewModel.systemEventsFlow.collectAsStateWithLifecycle()
    val marketFeatures by viewModel.marketFeaturesFlow.collectAsStateWithLifecycle()
    val aiRequests by viewModel.aiRequestsFlow.collectAsStateWithLifecycle()

    val scannerCandidates by viewModel.coordinator.scannerCandidates.collectAsStateWithLifecycle()
    val newsEvents by viewModel.coordinator.newsEvents.collectAsStateWithLifecycle()
    val crowdProxy by viewModel.coordinator.crowdProxy.collectAsStateWithLifecycle()
    val performanceReport by viewModel.coordinator.performanceReport.collectAsStateWithLifecycle()
    val forecastReport by viewModel.coordinator.latestForecastReport.collectAsStateWithLifecycle()
    val forecastDiagnostics by viewModel.coordinator.forecastDiagnostics.collectAsStateWithLifecycle()

    val selectedSymbol by viewModel.selectedSymbol.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val secretRefreshTick by viewModel.secretPreviewRefreshTick.collectAsStateWithLifecycle()

    val tabs = listOf(
        AstraNavTab(0, R.string.tab_overview, "nav_tab_overview", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
        AstraNavTab(1, R.string.tab_strategy, "nav_tab_strategy", Icons.Filled.Tune, Icons.Outlined.Tune),
        AstraNavTab(2, R.string.tab_api, "nav_tab_api", Icons.Filled.Hub, Icons.Outlined.Hub),
        AstraNavTab(3, R.string.tab_history, "nav_tab_history", Icons.Filled.Analytics, Icons.Outlined.Analytics),
        AstraNavTab(4, R.string.tab_logs, "nav_tab_logs", Icons.Filled.History, Icons.Outlined.History)
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                color = AstraSurfaceElevated,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ASTRA TRADER V5.1",
                            style = MaterialTheme.typography.titleLarge,
                            color = AstraEmeraldGreen,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("app_header_title")
                        )
                        val pillBg = when {
                            !connectivityState.isConnected -> AstraCrimsonContainer
                            !connectivityState.isValidated -> AstraAmberContainer
                            else -> AstraEmeraldContainer
                        }
                        val pillFg = when {
                            !connectivityState.isConnected -> AstraCrimsonDanger
                            !connectivityState.isValidated -> AstraAmberWarn
                            else -> AstraEmeraldGreen
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(pillBg, RoundedCornerShape(6.dp))
                                .border(1.dp, pillFg.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("connectivity_status_badge")
                        ) {
                            Icon(
                                imageVector = if (connectivityState.isConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                                contentDescription = connectivityState.transportLabelVi,
                                tint = pillFg,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (connectivityState.isConnected) {
                                    connectivityState.transportLabelVi
                                } else {
                                    "OFFLINE"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = pillFg,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "USDⓈ-M FUTURES",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = statusBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("status_banner_text")
                    )
                    AnimatedVisibility(visible = !connectivityState.isConnected) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .background(AstraCrimsonContainer, RoundedCornerShape(8.dp))
                                .border(1.dp, AstraCrimsonDanger, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("connectivity_lost_banner"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WifiOff,
                                contentDescription = stringResource(R.string.connectivity_lost_title),
                                tint = AstraCrimsonDanger,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.connectivity_lost_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AstraCrimsonDanger,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = connectivityState.statusMessageVi,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = viewModel::recheckNetworkConnectivity,
                                modifier = Modifier.testTag("connectivity_recheck_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.connectivity_recheck_button),
                                    tint = AstraCrimsonDanger,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.connectivity_recheck_button),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AstraCrimsonDanger
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = AstraSurfaceElevated
            ) {
                tabs.forEach { tab ->
                    val selected = selectedTab == tab.index
                    val label = stringResource(tab.titleRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = tab.index },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = label
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.testTag(tab.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> OverviewTabScreen(
                    settings = settings,
                    botState = botState,
                    safetyDebugLockActive = safetyDebugLockActive,
                    accountCapitalState = accountCapitalState,
                    aiModuleStatus = aiModuleStatus,
                    dailySnapshot = dailySnapshots.firstOrNull(),
                    activePositions = activePositions,
                    featureSnapshot = featureSnapshot,
                    evEstimate = evEstimate,
                    skepticResult = skepticResult,
                    sizingDecision = sizingDecision,
                    deviceHealth = deviceHealth,
                    clockStatus = clockStatus,
                    liveChecklist = liveChecklist,
                    selectedSymbol = selectedSymbol,
                    isBusy = isBusy,
                    onSelectSymbol = viewModel::selectSymbol,
                    onScanAndEvaluate = viewModel::triggerScanAndEvaluate,
                    onReconcile = viewModel::triggerFullReconciliation,
                    onExecuteApprovedCandidate = viewModel::triggerExecuteCandidate,
                    onPause = viewModel::triggerPauseBot,
                    onDrain = viewModel::triggerDrainMode,
                    onResume = viewModel::triggerResumeBot,
                    onPanicCloseConfirmed = viewModel::triggerPanicCloseConfirmed,
                    onSwitchEnvironment = viewModel::switchEnvironmentMode,
                    onToggleForegroundService = viewModel::toggleForegroundService,
                    scannerCandidates = scannerCandidates,
                    onRunMultiSymbolScanner = viewModel::triggerMultiSymbolScanner,
                    onMoveStopToNetBreakeven = viewModel::triggerMoveStopToNetBreakeven,
                    forecastReport = forecastReport,
                    forecastDiagnostics = forecastDiagnostics,
                    onToggleForecastMode = viewModel::toggleForecastOperatingMode,
                    onToggleForecastRollback = viewModel::toggleForecastRollbackModel
                )
                1 -> StrategyRiskTabScreen(
                    settings = settings,
                    featureSnapshot = featureSnapshot,
                    evEstimate = evEstimate,
                    recentSignals = recentSignals,
                    onSaveRiskSettings = viewModel::updateRiskAndCapitalSettings,
                    newsEvents = newsEvents,
                    crowdProxy = crowdProxy,
                    onInjectNewsEvent = viewModel::triggerInjectNewsEvent,
                    accountCapitalState = accountCapitalState,
                    activePositions = activePositions,
                    sizingDecision = sizingDecision,
                    dailySnapshot = dailySnapshots.firstOrNull()
                )
                2 -> ApiIntegrationTabScreen(
                    settings = settings,
                    secretVault = viewModel.secretVault,
                    secretRefreshTick = secretRefreshTick,
                    providers = providers,
                    aiModuleStatus = aiModuleStatus,
                    recentAiDecisions = aiDecisions,
                    onSaveBinanceCredentials = viewModel::saveBinanceCredentials,
                    onClearBinanceCredentials = viewModel::clearBinanceCredentials,
                    onTestAndReconcileBinance = viewModel::triggerFullReconciliation,
                    onSaveAiKey = viewModel::saveAiProviderKey,
                    onToggleProvider = viewModel::toggleProviderEnabled,
                    onTriggerAiReview = viewModel::triggerAiReview,
                    onSaveTelegramConfig = viewModel::saveTelegramConfig,
                    onSimulateTelegramCommand = viewModel::simulateTelegramCommand,
                    onAddOrUpdateCustomProvider = viewModel::addOrUpdateCustomAiProvider,
                    onSendTestTelegramMessage = viewModel::sendTestTelegramNotification,
                    accountCapitalState = accountCapitalState,
                    authDiagnosticReport = authDiagnosticReport,
                    outboundNetworkIdentity = outboundNetworkIdentity,
                    onSelectEndpointTarget = viewModel::setBinanceEndpointTarget,
                    onRunAuthDiagnostics = viewModel::triggerBinanceAuthDiagnostics,
                    onRunOutboundIpDiagnostics = viewModel::triggerOutboundIpDiagnostics,
                    onApplyTestedProviderToBot = viewModel::applyTestedAiProviderToBot
                )
                3 -> HistoryAnalyticsTabScreen(
                    dailySnapshots = dailySnapshots,
                    fills = fills,
                    orderIntents = orderIntents,
                    cashflows = cashflows,
                    backtestSummary = backtestSummary,
                    onRecordCashflow = viewModel::recordCashflowAction,
                    onExportSanitizedDiagnostics = viewModel::buildSanitizedDiagnosticJson,
                    performanceReport = performanceReport
                )
                4 -> SystemLogsTabScreen(
                    riskEvents = riskEvents,
                    systemEvents = systemEvents,
                    marketFeatures = marketFeatures,
                    aiRequests = aiRequests
                )
            }
        }
    }
}
