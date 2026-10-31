package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.KillSwitchLevel
import com.example.ui.AstraViewModel
import com.example.ui.screens.ApiIntegrationTabScreen
import com.example.ui.screens.HistoryAnalyticsTabScreen
import com.example.ui.screens.OverviewTabScreen
import com.example.ui.screens.StrategyRiskTabScreen
import com.example.ui.screens.SystemLogsTabScreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraTraderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AstraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstraTraderTheme {
                AstraTraderRootApp(viewModel = viewModel)
            }
        }
    }
}

private data class NavTabItem(
    val index: Int,
    val label: String,
    val tag: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstraTraderRootApp(viewModel: AstraViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val config by viewModel.strategyConfig.collectAsStateWithLifecycle()
    val dataTrustState by viewModel.dataTrustState.collectAsStateWithLifecycle()
    val selectedSymbol by viewModel.selectedSymbol.collectAsStateWithLifecycle()
    val accountVerified by viewModel.accountVerified.collectAsStateWithLifecycle()
    val equityUsd by viewModel.equityUsd.collectAsStateWithLifecycle()
    val availableMarginUsd by viewModel.availableMarginUsd.collectAsStateWithLifecycle()
    val activePositions by viewModel.activePositions.collectAsStateWithLifecycle()
    val cycleSnapshot by viewModel.cycleSnapshot.collectAsStateWithLifecycle()
    val riskSummary by viewModel.riskSummary.collectAsStateWithLifecycle()
    val latestSizing by viewModel.latestSizing.collectAsStateWithLifecycle()
    val forecastReport by viewModel.forecastReport.collectAsStateWithLifecycle()
    val forecastDiagnostics by viewModel.forecastDiagnostics.collectAsStateWithLifecycle()
    val foregroundServiceActive by viewModel.foregroundServiceActive.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val tradeOrders by viewModel.tradeOrders.collectAsStateWithLifecycle()
    val signalLogs by viewModel.signalLogs.collectAsStateWithLifecycle()
    val riskLogs by viewModel.riskLogs.collectAsStateWithLifecycle()
    val systemLogs by viewModel.systemLogs.collectAsStateWithLifecycle()
    val forecastEvaluations by viewModel.forecastEvaluations.collectAsStateWithLifecycle()

    val binanceAuthDiag by viewModel.binanceAuthDiagnostic.collectAsStateWithLifecycle()
    val telegramStatus by viewModel.telegramStatusMessage.collectAsStateWithLifecycle()
    val aiProviderHealth by viewModel.aiProviderHealthList.collectAsStateWithLifecycle()

    // Ensure hardware back button on secondary tabs returns to Overview tab (Tab 0)
    if (selectedTab != 0) {
        BackHandler {
            selectedTab = 0
        }
    }

    val tabs = listOf(
        NavTabItem(0, "Overview", "nav_tab_overview", Icons.Default.Dashboard),
        NavTabItem(1, "API & AI", "nav_tab_api", Icons.Default.Key),
        NavTabItem(2, "Risk", "nav_tab_strategy", Icons.Default.Tune),
        NavTabItem(3, "Analytics", "nav_tab_analytics", Icons.Default.Analytics),
        NavTabItem(4, "Logs", "nav_tab_logs", Icons.Default.ListAlt)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Astra Trader · Small-Cap Futures (${config.environmentMode}/${config.forecastOperatingMode})",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("top_app_bar_title")
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AstraSurfaceCard
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = AstraSurfaceCard) {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = selectedTab == item.index,
                        onClick = { selectedTab = item.index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.testTag(item.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> OverviewTabScreen(
                    config = config,
                    dataTrustState = dataTrustState,
                    selectedSymbol = selectedSymbol,
                    accountVerified = accountVerified,
                    equityUsd = equityUsd,
                    availableMarginUsd = availableMarginUsd,
                    activePositions = activePositions,
                    cycleSnapshot = cycleSnapshot,
                    riskSummary = riskSummary,
                    latestSizing = latestSizing,
                    forecastReport = forecastReport,
                    forecastDiagnostics = forecastDiagnostics,
                    foregroundServiceActive = foregroundServiceActive,
                    isBusy = isBusy,
                    onSelectSymbol = viewModel::selectSymbol,
                    onRunEvaluationCycle = { viewModel.runEvaluationCycle(true) },
                    onCycleForecastMode = viewModel::cycleForecastOperatingMode,
                    onTriggerKillSwitch = viewModel::triggerKillSwitch,
                    onToggleForegroundService = viewModel::toggleForegroundService
                )

                1 -> ApiIntegrationTabScreen(
                    hasBinanceCredentials = viewModel.secretVault.hasBinanceCredentials(),
                    binanceAuthDiagnostic = binanceAuthDiag,
                    telegramConfigured = viewModel.secretVault.getTelegramBotToken().isNotBlank(),
                    telegramStatusMessage = telegramStatus,
                    aiProviderHealthList = aiProviderHealth,
                    isBusy = isBusy,
                    onSaveBinanceCredentials = viewModel::saveBinanceCredentials,
                    onVerifyBinanceConnection = viewModel::verifyBinanceConnection,
                    onSaveTelegramCredentials = viewModel::saveTelegramCredentials,
                    onTestTelegramAlert = viewModel::sendTestTelegramAlert,
                    onSaveAiKeys = viewModel::saveAiKeys,
                    onVerifyAiProviders = viewModel::verifyAiProviders,
                    onCredentialLockdown = {
                        viewModel.triggerKillSwitch(KillSwitchLevel.CREDENTIAL_LOCKDOWN)
                    }
                )

                2 -> StrategyRiskTabScreen(
                    config = config,
                    accountBalanceUsd = equityUsd,
                    selectedSymbol = selectedSymbol,
                    onUpdateStrategyConfig = viewModel::updateStrategyConfig
                )

                3 -> HistoryAnalyticsTabScreen(
                    dataTrustState = dataTrustState,
                    riskSummary = riskSummary,
                    tradeOrders = tradeOrders,
                    signalLogs = signalLogs,
                    forecastEvaluations = forecastEvaluations
                )

                4 -> SystemLogsTabScreen(
                    systemLogs = systemLogs,
                    riskLogs = riskLogs,
                    onPruneLogs = viewModel::pruneOldLogs
                )
            }
        }
    }
}
