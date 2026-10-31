package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiApiCheckerEngine
import com.example.data.local.AstraDatabase
import com.example.data.local.ForecastEvaluationEntity
import com.example.data.local.PositionEntity
import com.example.data.local.RiskLogEntity
import com.example.data.local.SignalLogEntity
import com.example.data.local.StrategyConfigEntity
import com.example.data.local.SystemLogEntity
import com.example.data.local.TradeOrderEntity
import com.example.domain.AiProviderHealth
import com.example.domain.DataTrustState
import com.example.domain.KillSwitchLevel
import com.example.domain.PositionSizingResult
import com.example.domain.QuantRiskSummary
import com.example.engine.AstraTradingCoordinator
import com.example.engine.CycleEvaluationSnapshot
import com.example.forecast.domain.ForecastDiagnosticsSnapshot
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.MultiHorizonForecastReport
import com.example.forecast.engine.ForecastCoordinator
import com.example.forecast.runtime.ForecastModelRegistry
import com.example.network.BinanceAdapter
import com.example.quant.QuantMathEngine
import com.example.security.SecretVault
import com.example.service.TradingForegroundService
import com.example.telegram.TelegramBotController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AstraViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val database = AstraDatabase.getInstance(appContext)
    val dao = database.astraDao()
    val secretVault = SecretVault(appContext)
    val binanceAdapter = BinanceAdapter(secretVault)
    val binanceRetrofitModule = com.example.network.BinanceV5RetrofitModule(secretVault, binanceAdapter)
    val binanceFuturesRepository = com.example.network.BinanceFuturesRepository(secretVault, binanceRetrofitModule)
    val modelRegistry = ForecastModelRegistry.createDefault(appContext)
    val forecastCoordinator = ForecastCoordinator(dao = dao, modelRegistry = modelRegistry)
    val coordinator = AstraTradingCoordinator(
        context = appContext,
        dao = dao,
        binanceAdapter = binanceAdapter,
        forecastCoordinator = forecastCoordinator
    )
    private val aiChecker = AiApiCheckerEngine()
    val telegramController = TelegramBotController(dao = dao)

    val strategyConfig: StateFlow<StrategyConfigEntity> = dao.observeStrategyConfig()
        .map { it ?: StrategyConfigEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StrategyConfigEntity())

    val activePositions: StateFlow<List<PositionEntity>> = dao.observePositions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tradeOrders: StateFlow<List<TradeOrderEntity>> = dao.observeTradeOrders(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val signalLogs: StateFlow<List<SignalLogEntity>> = dao.observeSignalLogs(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val riskLogs: StateFlow<List<RiskLogEntity>> = dao.observeRiskLogs(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val systemLogs: StateFlow<List<SystemLogEntity>> = dao.observeSystemLogs(150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val forecastEvaluations: StateFlow<List<ForecastEvaluationEntity>> =
        forecastCoordinator.observeEvaluations(100)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val forecastReport: StateFlow<MultiHorizonForecastReport?> = forecastCoordinator.latestReport
    val forecastDiagnostics: StateFlow<ForecastDiagnosticsSnapshot?> = forecastCoordinator.diagnostics

    private val _selectedSymbol = MutableStateFlow("BTCUSDT")
    val selectedSymbol: StateFlow<String> = _selectedSymbol.asStateFlow()

    private val _dataTrustState = MutableStateFlow(DataTrustState.EMPTY)
    val dataTrustState: StateFlow<DataTrustState> = _dataTrustState.asStateFlow()

    private val _accountVerified = MutableStateFlow(false)
    val accountVerified: StateFlow<Boolean> = _accountVerified.asStateFlow()

    private val _equityUsd = MutableStateFlow(150.0) // Small-capital paper reference ($150 USDT)
    val equityUsd: StateFlow<Double> = _equityUsd.asStateFlow()

    private val _availableMarginUsd = MutableStateFlow(150.0)
    val availableMarginUsd: StateFlow<Double> = _availableMarginUsd.asStateFlow()

    private val _cycleSnapshot = MutableStateFlow<CycleEvaluationSnapshot?>(null)
    val cycleSnapshot: StateFlow<CycleEvaluationSnapshot?> = _cycleSnapshot.asStateFlow()

    private val _latestSizing = MutableStateFlow<PositionSizingResult?>(null)
    val latestSizing: StateFlow<PositionSizingResult?> = _latestSizing.asStateFlow()

    private val _riskSummary = MutableStateFlow(
        QuantMathEngine.computePortfolioRiskSummary(
            equityUsd = 150.0,
            dailyRealizedLossUsd = 0.0,
            dailyLossCapPct = 0.015,
            riskPerTradePct = 0.005,
            closedTradeReturnsPct = emptyList(),
            consecutiveLosses = 0,
            cooldownUntilMs = 0L
        )
    )
    val riskSummary: StateFlow<QuantRiskSummary> = _riskSummary.asStateFlow()

    private val _binanceAuthDiagnostic = MutableStateFlow("Vault status: awaiting 6-step verification")
    val binanceAuthDiagnostic: StateFlow<String> = _binanceAuthDiagnostic.asStateFlow()

    private val _telegramStatusMessage = MutableStateFlow("Telegram controller ready (replay guard active)")
    val telegramStatusMessage: StateFlow<String> = _telegramStatusMessage.asStateFlow()

    private val _aiProviderHealthList = MutableStateFlow<List<AiProviderHealth>>(emptyList())
    val aiProviderHealthList: StateFlow<List<AiProviderHealth>> = _aiProviderHealthList.asStateFlow()

    private val _foregroundServiceActive = MutableStateFlow(false)
    val foregroundServiceActive: StateFlow<Boolean> = _foregroundServiceActive.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            seedBlankSlateConfigIfEmpty()
            coordinator.initializeSubsystems()
            runEvaluationCycle(forceRefresh = true)
        }
    }

    /**
     * Blank-Slate initialization: ONLY ensures the default `StrategyConfigEntity` row exists
     * (`environmentMode = "TEST"`, `forecastOperatingMode = "SHADOW"`, `safetyDebugLockActive = true`).
     * Never inserts fake trades, positions, or synthetic PnL.
     */
    private suspend fun seedBlankSlateConfigIfEmpty() {
        val existing = dao.getStrategyConfigOnce()
        if (existing == null) {
            dao.upsertStrategyConfig(
                StrategyConfigEntity(
                    id = 1,
                    strategyName = "ASTRA_SMALL_CAP_HYBRID_V2",
                    maxLeverage = 5,
                    riskPerTradePct = 0.50,
                    dailyLossCapPct = 1.50,
                    atrStopMultiplier = 1.65,
                    atrTakeProfitMultiplier = 3.10,
                    autoTradingEnabled = false,
                    safetyDebugLockActive = true,
                    environmentMode = "TEST",
                    forecastOperatingMode = "SHADOW",
                    killSwitchLevel = "NONE"
                )
            )
        }
    }

    fun selectSymbol(symbol: String) {
        _selectedSymbol.value = symbol.uppercase()
        runEvaluationCycle(forceRefresh = false)
    }

    fun runEvaluationCycle(forceRefresh: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val cfg = dao.getStrategyConfigOnce() ?: StrategyConfigEntity()
                val sym = _selectedSymbol.value
                val snap = coordinator.runEvaluationCycle(
                    symbol = sym,
                    config = cfg,
                    equityUsd = _equityUsd.value,
                    availableMarginUsd = _availableMarginUsd.value,
                    dailyRealizedLossUsd = 0.0,
                    closedTradeReturnsPct = emptyList(),
                    consecutiveLosses = 0,
                    geminiKey = secretVault.getGeminiApiKey(),
                    openAiKey = secretVault.getOpenAiApiKey(),
                    claudeKey = secretVault.getClaudeApiKey(),
                    forceRefresh = forceRefresh
                )
                _cycleSnapshot.value = snap
                _dataTrustState.value = snap.dataTrustState
                _riskSummary.value = snap.riskSummary

                val filter = coordinator.getSymbolFilter(sym)
                val sizing = QuantMathEngine.calculatePositionSizing(
                    symbolFilter = filter,
                    equityUsd = _equityUsd.value,
                    availableMarginUsd = _availableMarginUsd.value,
                    riskPerTradePct = cfg.riskPerTradePct / 100.0,
                    remainingDailyRiskBudgetUsd = snap.riskSummary.remainingDailyLossBudgetUsd,
                    entryPrice = snap.markPrice,
                    stopLossPrice = snap.technicalSignal.stopLossPrice,
                    takeProfitPrice = snap.technicalSignal.takeProfitPrice,
                    maxAllowedLeverage = cfg.maxLeverage
                )
                _latestSizing.value = sizing
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun cycleForecastOperatingMode() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = dao.getStrategyConfigOnce() ?: StrategyConfigEntity()
            val modes = ForecastOperatingMode.values().map { it.name }
            val currentIdx = modes.indexOf(cfg.forecastOperatingMode).coerceAtLeast(0)
            val nextMode = modes[(currentIdx + 1) % modes.size]
            dao.upsertStrategyConfig(cfg.copy(forecastOperatingMode = nextMode))
            runEvaluationCycle(forceRefresh = true)
        }
    }

    fun triggerKillSwitch(level: KillSwitchLevel) {
        viewModelScope.launch(Dispatchers.IO) {
            if (level == KillSwitchLevel.CREDENTIAL_LOCKDOWN) {
                secretVault.clearAllCredentials()
                _accountVerified.value = false
            }
            coordinator.executeKillSwitch(level, "Operator triggered ${level.name} from UI")
            runEvaluationCycle(forceRefresh = false)
        }
    }

    fun updateStrategyConfig(updated: StrategyConfigEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Enforce hard cap of 5x leverage and TEST mode when safetyDebugLockActive is true
            val safeConfig = updated.copy(
                maxLeverage = updated.maxLeverage.coerceIn(1, 5),
                riskPerTradePct = updated.riskPerTradePct.coerceIn(0.10, 1.50),
                dailyLossCapPct = updated.dailyLossCapPct.coerceIn(0.50, 5.00),
                environmentMode = if (updated.safetyDebugLockActive) "TEST" else updated.environmentMode
            )
            dao.upsertStrategyConfig(safeConfig)
            runEvaluationCycle(forceRefresh = false)
        }
    }

    fun saveBinanceCredentials(apiKey: String, secretKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            secretVault.saveBinanceCredentials(apiKey, secretKey)
            _binanceAuthDiagnostic.value = "Encrypted Binance credentials saved to Android Keystore. Ready for 6-step verification."
        }
    }

    fun verifyBinanceConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val cfg = dao.getStrategyConfigOnce() ?: StrategyConfigEntity()
                val isTestnet = cfg.environmentMode != "LIVE"
                val apiKey = secretVault.getBinanceApiKey()
                val secretKey = secretVault.getBinanceSecretKey()
                val result = binanceAdapter.verifyAuthenticatedAccount(apiKey, secretKey, isTestnet)
                result.fold(
                    onSuccess = { acc ->
                        _accountVerified.value = true
                        _equityUsd.value = acc.totalWalletBalance
                        _availableMarginUsd.value = acc.availableBalance
                        _binanceAuthDiagnostic.value = "VERIFIED (${if (isTestnet) "TESTNET" else "MAINNET"}): Wallet=$${"%.2f".format(acc.totalWalletBalance)} USDT, Available=$${"%.2f".format(acc.availableBalance)} USDT"
                    },
                    onFailure = { err ->
                        _accountVerified.value = false
                        _binanceAuthDiagnostic.value = "AUTH CHECK FAILED: ${err.message}"
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun saveTelegramCredentials(botToken: String, chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            secretVault.saveTelegramCredentials(botToken, chatId)
            _telegramStatusMessage.value = "Telegram Bot Token & allowlisted Chat ID ($chatId) saved to SecretVault."
        }
    }

    fun sendTestTelegramAlert() {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val res = telegramController.sendAlertMessage(
                    botToken = secretVault.getTelegramBotToken(),
                    chatId = secretVault.getTelegramChatId(),
                    messageText = "[Astra Trader] Blank-Slate Shadow/Test diagnostic ping. Safety Lock: ENGAGED."
                )
                _telegramStatusMessage.value = res.fold(
                    onSuccess = { "Telegram ping delivered to authorized chat." },
                    onFailure = { "Telegram send failed: ${it.message}" }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun saveAiKeys(gemini: String, openAi: String, claude: String) {
        viewModelScope.launch(Dispatchers.IO) {
            secretVault.saveAiProviderKeys(gemini, openAi, claude)
            verifyAiProviders()
        }
    }

    fun verifyAiProviders() {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val results = aiChecker.checkAllProviders(
                    geminiKey = secretVault.getGeminiApiKey(),
                    openAiKey = secretVault.getOpenAiApiKey(),
                    claudeKey = secretVault.getClaudeApiKey()
                )
                _aiProviderHealthList.value = results
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleForegroundService(enable: Boolean) {
        _foregroundServiceActive.value = enable
        val intent = Intent(appContext, TradingForegroundService::class.java).apply {
            action = if (enable) {
                TradingForegroundService.ACTION_START_MONITORING
            } else {
                TradingForegroundService.ACTION_STOP_MONITORING
            }
        }
        runCatching {
            if (enable) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.stopService(intent)
            }
        }
    }

    fun pruneOldLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 7L * 86_400_000L
            dao.pruneOldSystemLogs(cutoff)
        }
    }
}
