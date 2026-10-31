package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.ai.AiIntelligenceGateway
import com.example.data.local.AiDecisionEntity
import com.example.data.local.AiRequestEntity
import com.example.data.local.AppSettingsEntity
import com.example.data.local.AstraDatabase
import com.example.data.local.CashflowEntity
import com.example.data.local.DailySnapshotEntity
import com.example.data.local.FillEntity
import com.example.data.local.MarketFeatureEntity
import com.example.data.local.OrderIntentEntity
import com.example.data.local.OrderRecordEntity
import com.example.data.local.PositionEntity
import com.example.data.local.ProviderConfigEntity
import com.example.data.local.RiskEventEntity
import com.example.data.local.SignalEntity
import com.example.data.local.SystemEventEntity
import com.example.domain.BotState
import com.example.domain.EnvironmentMode
import com.example.domain.NewsEventCategory
import com.example.domain.NewsVerificationStatus
import com.example.engine.AstraTradingCoordinator
import com.example.network.BinanceAdapter
import com.example.network.NetworkConnectivityMonitor
import com.example.network.NetworkConnectivityState
import com.example.security.SecretVault
import com.example.service.TradingForegroundService
import com.example.telegram.TelegramBotController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal

class AstraViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AstraDatabase.getInstance(application)
    private val dao = db.astraDao()
    val secretVault = SecretVault(application)
    val binanceAdapter = BinanceAdapter(secretVault)
    private val aiGateway = AiIntelligenceGateway(secretVault, binanceAdapter.httpClient)
    val telegramController = TelegramBotController(secretVault, dao, binanceAdapter.httpClient)

    val coordinator = AstraTradingCoordinator(
        context = application,
        dao = dao,
        secretVault = secretVault,
        binanceAdapter = binanceAdapter,
        aiGateway = aiGateway,
        telegramController = telegramController
    )

    val connectivityMonitor = NetworkConnectivityMonitor(application) { netState ->
        onNetworkConnectivityChanged(netState)
    }
    val connectivityState: StateFlow<NetworkConnectivityState> = connectivityMonitor.state

    val settingsFlow: StateFlow<AppSettingsEntity> = dao.observeSettings()
        .map { it ?: AppSettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsEntity())

    val providersFlow: StateFlow<List<ProviderConfigEntity>> = dao.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activePositionsFlow: StateFlow<List<PositionEntity>> = dao.observeActivePositions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val orderIntentsFlow: StateFlow<List<OrderIntentEntity>> = dao.observeOrderIntents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ordersFlow: StateFlow<List<OrderRecordEntity>> = dao.observeOrders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signalsFlow: StateFlow<List<SignalEntity>> = dao.observeRecentSignals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiDecisionsFlow: StateFlow<List<AiDecisionEntity>> = dao.observeRecentAiDecisions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fillsFlow: StateFlow<List<FillEntity>> = dao.observeFills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cashflowsFlow: StateFlow<List<CashflowEntity>> = dao.observeCashflows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailySnapshotsFlow: StateFlow<List<DailySnapshotEntity>> = dao.observeDailySnapshots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val riskEventsFlow: StateFlow<List<RiskEventEntity>> = dao.observeRiskEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val systemEventsFlow: StateFlow<List<SystemEventEntity>> = dao.observeSystemEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val marketFeaturesFlow: StateFlow<List<MarketFeatureEntity>> = dao.observeMarketFeatures()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiRequestsFlow: StateFlow<List<AiRequestEntity>> = dao.observeAiRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSymbol = MutableStateFlow("DOGEUSDT")
    val selectedSymbol: StateFlow<String> = _selectedSymbol.asStateFlow()

    private val _statusBanner = MutableStateFlow(
        "Astra Trader V5.1 · Khóa an toàn LIVE đang bật · Tài khoản Binance: UNVERIFIED/UNKNOWN"
    )
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _secretPreviewRefreshTick = MutableStateFlow(0)
    val secretPreviewRefreshTick: StateFlow<Int> = _secretPreviewRefreshTick.asStateFlow()

    init {
        TradingForegroundService.registerRuntimeCallbacks(
            onHeartbeat = {
                coordinator.refreshDeviceHealth()
            },
            onReconcile = {
                coordinator.runFullReconciliation()
            },
            onKeepAliveVerified = {
                val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
                val mode = coordinator.resolveApiEndpointMode(settings)
                val res = binanceAdapter.startOrKeepAliveListenKey(mode, existingListenKey = "KEEP_ALIVE")
                res.isSuccess && !res.data.isNullOrBlank()
            },
            onError = { errMsg ->
                dao.insertSystemEvent(
                    SystemEventEntity(
                        category = "RUNTIME",
                        level = "WARN",
                        titleVi = "Cảnh báo tiến trình ngầm TradingForegroundService",
                        detailVi = errMsg,
                        correlationId = "fgs-${System.currentTimeMillis()}"
                    )
                )
            }
        )
        viewModelScope.launch {
            seedDefaultDataIfEmpty()
            coordinator.refreshDeviceHealth()
            coordinator.refreshDeclaredCapitalEstimate()
            coordinator.refreshAiConfigurationStatus()
            coordinator.forecastCoordinator.ensureInitialized(cpuThreads = 2)
            coordinator.scanAndEvaluateSymbol(_selectedSymbol.value)
        }
    }

    private suspend fun seedDefaultDataIfEmpty() {
        val currentSettings = dao.getSettingsOnce()
        if (currentSettings == null) {
            val defaultSettings = AppSettingsEntity(
                environmentMode = "TEST",
                liveExplicitlyArmed = false
            )
            dao.upsertSettings(defaultSettings)
            coordinator.ensureBaselineDailySnapshot(defaultSettings)
        } else {
            // Ensure any legacy 5.8939 snapshot that was not verified from exchange is reset to UNVERIFIED
            val snap = dao.getLatestDailySnapshotOnce()
            if (snap != null && snap.currentEquityUsdt == "5.8939" &&
                coordinator.accountCapitalState.value.exchangeEquityUsdt == null
            ) {
                dao.upsertDailySnapshot(
                    snap.copy(
                        startEquityUsdt = "UNVERIFIED",
                        currentEquityUsdt = "UNVERIFIED",
                        availableBalanceUsdt = "UNVERIFIED",
                        peakEquityUsdt = "UNVERIFIED"
                    )
                )
            }
            coordinator.ensureBaselineDailySnapshot(currentSettings)
        }

        val existingProviders = dao.getProvidersOnce()
        if (existingProviders.isEmpty()) {
            // Requirement 4: Providers start disabled and UNCONFIGURED until verified by health check
            dao.upsertProvider(
                ProviderConfigEntity(
                    providerId = "gemini-fast-analyst",
                    displayName = "Google Gemini 2.5 Flash (FAST_ANALYST)",
                    apiType = "GEMINI",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    modelId = "gemini-2.5-flash",
                    role = "FAST_ANALYST",
                    secretSlot = SecretVault.SLOT_AI_PRIMARY_KEY,
                    timeoutMs = 12_000L,
                    enabled = false,
                    healthStatus = "UNCONFIGURED"
                )
            )
            dao.upsertProvider(
                ProviderConfigEntity(
                    providerId = "openai-skeptic",
                    displayName = "OpenAI / Groq Compatible (SKEPTIC)",
                    apiType = "OPENAI_COMPATIBLE",
                    baseUrl = "https://api.groq.com/openai",
                    modelId = "llama-3.3-70b-versatile",
                    role = "SKEPTIC",
                    secretSlot = SecretVault.SLOT_AI_SKEPTIC_KEY,
                    timeoutMs = 15_000L,
                    enabled = false,
                    healthStatus = "UNCONFIGURED"
                )
            )
        }
    }

    fun selectSymbol(symbol: String) {
        viewModelScope.launch {
            val (allowed, reasonVi) = coordinator.canSwitchActiveSymbol(symbol)
            if (!allowed) {
                _statusBanner.value = reasonVi
                return@launch
            }
            _selectedSymbol.value = symbol
            _isBusy.value = true
            val msg = coordinator.scanAndEvaluateSymbol(symbol)
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerScanAndEvaluate() {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.scanAndEvaluateSymbol(_selectedSymbol.value)
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerMultiSymbolScanner() {
        viewModelScope.launch {
            _isBusy.value = true
            val top5 = coordinator.runMultiSymbolCandidateScanner()
            _statusBanner.value = "Bộ quét đa cặp (MOD-06): Đã lọc Top ${top5.size} ứng viên thanh khoản phù hợp cỡ vốn (${top5.joinToString { it.symbol }})."
            _isBusy.value = false
        }
    }

    fun triggerMoveStopToNetBreakeven() {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.moveStopToNetBreakevenIfEligible()
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerInjectNewsEvent(
        title: String,
        sourceTier: Int,
        canonicalUrl: String,
        status: NewsVerificationStatus,
        category: NewsEventCategory,
        impactScore: Double
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.injectNewsEventForTesting(
                title = title,
                sourceTier = sourceTier,
                canonicalUrl = canonicalUrl,
                status = status,
                category = category,
                impactScore = impactScore
            )
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerFullReconciliation() {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.runFullReconciliation(_selectedSymbol.value)
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerBinanceAuthDiagnostics() {
        viewModelScope.launch {
            _isBusy.value = true
            val report = coordinator.runReadOnlyBinanceDiagnostics()
            val summary = if (report.futuresAccountAccepted) {
                "Chẩn đoán 6 bước THÀNH CÔNG trên ${report.fapiBaseUrl} · Outbound IPv4=${report.outboundNetwork.outboundIpv4 ?: "UNKNOWN"}."
            } else {
                "Chẩn đoán dừng tại ${report.failedStepCode ?: "N/A"} · Endpoint=${report.failedEndpoint ?: "N/A"} · Code=${report.failedBinanceErrorCode ?: "N/A"} · Msg=${report.failedRawServerMessage ?: "N/A"}"
            }
            _statusBanner.value = summary
            _isBusy.value = false
        }
    }

    fun triggerOutboundIpDiagnostics() {
        viewModelScope.launch {
            _isBusy.value = true
            val net = coordinator.runOutboundIpDiagnostics()
            _statusBanner.value = "Chẩn đoán IP Outbound (${net.transportTypeVi}): IPv4=${net.outboundIpv4 ?: "KHÔNG ĐO ĐƯỢC"} · IPv6=${net.outboundIpv6 ?: "KHÔNG CÓ"} · Cùng Android Network=${net.sameAndroidNetworkBound}"
            _isBusy.value = false
        }
    }

    fun setBinanceEndpointTarget(target: String) {
        viewModelScope.launch {
            val normalized = if (target.equals("TESTNET", ignoreCase = true)) "TESTNET" else "MAINNET"
            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            dao.upsertSettings(
                current.copy(
                    binanceEndpointTarget = normalized,
                    environmentMode = "TEST",
                    liveExplicitlyArmed = false,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            coordinator.onCredentialsUpdatedInVault()
            val url = if (normalized == "MAINNET") "https://fapi.binance.com" else "https://testnet.binancefuture.com"
            _statusBanner.value = "Đã chuyển Endpoint mục tiêu sang $normalized ($url). Khóa an toàn LIVE vẫn bật — hãy bấm 'Kiểm tra & Đối soát' để xác thực Read-Only."
        }
    }

    fun triggerExecuteCandidate() {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.executeApprovedCandidateIfEligible()
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerAiReview() {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.requestAiCandidateReview()
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun triggerPauseBot() {
        viewModelScope.launch {
            coordinator.transitionState(BotState.PAUSED_USER, "Người dùng bấm Tạm dừng mở vị thế mới")
            _statusBanner.value = "Đã tạm dừng mở lệnh mới (PAUSED_USER). Các lệnh bảo vệ SL phía sàn vẫn giữ nguyên."
        }
    }

    fun triggerDrainMode() {
        viewModelScope.launch {
            val activePos = dao.getActivePositionsOnce()
            if (activePos.isEmpty()) {
                coordinator.transitionState(BotState.PAUSED_FOR_CASHFLOW, "DRAIN hoàn tất: Không có vị thế mở, sẵn sàng nạp/rút")
                _statusBanner.value = "Chế độ DRAIN: Không có vị thế mở -> Chuyển sang PAUSED_FOR_CASHFLOW (Sẵn sàng nạp/rút tiền)."
            } else {
                coordinator.transitionState(BotState.DRAINING, "Chờ vị thế ${activePos.first().symbol} đóng để chuyển sang PAUSED_FOR_CASHFLOW")
                _statusBanner.value = "Đang ở chế độ DRAIN: Khóa mở lệnh mới, chờ vị thế ${activePos.first().symbol} đóng rồi dừng."
            }
        }
    }

    fun triggerResumeBot() {
        viewModelScope.launch {
            _isBusy.value = true
            val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
            dao.upsertSettings(settings.copy(activeCircuitBreakerReason = null, updatedAtMs = System.currentTimeMillis()))
            val recMsg = coordinator.runFullReconciliation(_selectedSymbol.value)
            _statusBanner.value = "Kết quả kiểm tra & đối soát khi Resume: $recMsg"
            _isBusy.value = false
        }
    }

    fun triggerPanicCloseConfirmed() {
        viewModelScope.launch {
            _isBusy.value = true
            val msg = coordinator.executePanicCloseVerifiedPosition("PANIC_CLOSE xác nhận 2 bước bởi người dùng")
            _statusBanner.value = msg
            _isBusy.value = false
        }
    }

    fun recordCashflowAction(type: String, amountUsdtStr: String, noteVi: String) {
        viewModelScope.launch {
            val amount = amountUsdtStr.toBigDecimalOrNull()
            if (amount == null || amount <= BigDecimal.ZERO) {
                _statusBanner.value = "Số tiền nạp/rút không hợp lệ."
                return@launch
            }
            val msg = coordinator.recordExternalCashflow(type, amount, noteVi)
            _statusBanner.value = msg
        }
    }

    fun updateRiskAndCapitalSettings(
        declaredVnd: Long,
        usdtVndRate: String,
        riskPerTradePct: String,
        dailyLossPct: String,
        maxDrawdownPct: String,
        leverageCap: Int,
        workingType: String
    ) {
        viewModelScope.launch {
            val activePos = dao.getActivePositionsOnce()
            if (activePos.isNotEmpty()) {
                _statusBanner.value = "Đang giữ vị thế mở (${activePos.first().symbol}): Không thay đổi cấu hình rủi ro/đòn bẩy giữa chừng (MOD-18)."
                return@launch
            }
            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            val updated = current.copy(
                configVersion = current.configVersion + 1,
                declaredCapitalVnd = declaredVnd,
                usdtVndRate = usdtVndRate,
                usdtVndUpdatedAtMs = System.currentTimeMillis(),
                riskPerTradePct = riskPerTradePct,
                dailyLossLimitPct = dailyLossPct,
                maxDrawdownLimitPct = maxDrawdownPct,
                defaultLeverageCap = leverageCap.coerceIn(1, 5),
                stopTriggerWorkingType = workingType,
                riskLimitsReviewed = true,
                updatedAtMs = System.currentTimeMillis()
            )
            dao.upsertSettings(updated)
            coordinator.refreshDeclaredCapitalEstimate()
            coordinator.updateLiveChecklist()
            val scanMsg = coordinator.scanAndEvaluateSymbol(_selectedSymbol.value)
            _statusBanner.value = "Đã lưu cấu hình Risk v${updated.configVersion} (Vốn khai báo không ghi đè Equity sàn). $scanMsg"
        }
    }

    /**
     * Requirement 2 & 6 · Sanitizes, encrypts, verifies round-trip decryption, validates format for -2014 before saving,
     * and strictly resets verification state to SAVED_IN_VAULT_UNVERIFIED / UNVERIFIED.
     */
    fun saveBinanceCredentials(apiKey: String, apiSecret: String, withdrawDisabledConfirmed: Boolean) {
        viewModelScope.launch {
            val cleanKey = SecretVault.sanitizeCredentialInput(apiKey)
            val cleanSecret = SecretVault.sanitizeCredentialInput(apiSecret)

            if (cleanKey.isNotEmpty() || cleanSecret.isNotEmpty()) {
                val targetKey = cleanKey.ifEmpty { secretVault.getSecret(SecretVault.SLOT_BINANCE_API_KEY).orEmpty() }
                val targetSecret = cleanSecret.ifEmpty { secretVault.getSecret(SecretVault.SLOT_BINANCE_API_SECRET).orEmpty() }

                val formatValidation = SecretVault.validateBinanceCredentialsFormat(targetKey, targetSecret)
                if (!formatValidation.isValid) {
                    _statusBanner.value = "Từ chối lưu khóa Binance: ${formatValidation.messageVi}"
                    return@launch
                }

                val keyOk = if (cleanKey.isNotEmpty()) secretVault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, cleanKey) else true
                val secretOk = if (cleanSecret.isNotEmpty()) secretVault.putSecret(SecretVault.SLOT_BINANCE_API_SECRET, cleanSecret) else true

                if (!keyOk || !secretOk) {
                    _statusBanner.value = "Lỗi kiểm tra round-trip giải mã trong SecretVault! Không lưu khóa."
                    return@launch
                }
            }

            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            dao.upsertSettings(
                current.copy(
                    environmentMode = "TEST",
                    liveExplicitlyArmed = false,
                    withdrawDisabledConfirmed = withdrawDisabledConfirmed,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            _secretPreviewRefreshTick.value++
            coordinator.onCredentialsUpdatedInVault()
            _statusBanner.value = "Đã lưu khóa mới vào SecretVault (Trạng thái Vault: ĐÃ LƯU · Trạng thái Binance: CHƯA XÁC MINH). Hãy bấm 'Kiểm tra & Đối soát' để xác thực Read-Only với Binance."
        }
    }

    fun clearBinanceCredentials() {
        viewModelScope.launch {
            secretVault.removeSecret(SecretVault.SLOT_BINANCE_API_KEY)
            secretVault.removeSecret(SecretVault.SLOT_BINANCE_API_SECRET)
            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            dao.upsertSettings(
                current.copy(
                    environmentMode = "TEST",
                    liveExplicitlyArmed = false,
                    withdrawDisabledConfirmed = false,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            _secretPreviewRefreshTick.value++
            coordinator.onCredentialsUpdatedInVault()
            _statusBanner.value = "Đã xóa ciphertext Binance API Key/Secret khỏi thiết bị và đặt Equity thực về UNVERIFIED/UNKNOWN."
        }
    }

    fun saveAiProviderKey(slot: String, apiKey: String) {
        viewModelScope.launch {
            val clean = SecretVault.sanitizeCredentialInput(apiKey)
            if (clean.isNotBlank()) {
                secretVault.putSecret(slot, clean)
            } else {
                secretVault.removeSecret(slot)
            }
            _secretPreviewRefreshTick.value++
            coordinator.refreshAiConfigurationStatus()
            _statusBanner.value = "Đã cập nhật khóa AI Provider ($slot). Hãy chạy Health Check / Phân tích thử trước khi bật công tắc Provider (Mục 4)."
        }
    }

    /**
     * Requirement 4 · Provider switch can only be turned ON after valid key configuration AND successful health check!
     */
    fun toggleProviderEnabled(provider: ProviderConfigEntity, requestedEnabled: Boolean) {
        viewModelScope.launch {
            if (!requestedEnabled) {
                dao.upsertProvider(provider.copy(enabled = false))
                coordinator.refreshAiConfigurationStatus()
                _statusBanner.value = "Đã tắt ${provider.displayName}."
                return@launch
            }

            val hasKey = secretVault.hasSecret(provider.secretSlot) ||
                AiIntelligenceGateway.isLocalHostUrl(provider.baseUrl)

            if (!hasKey) {
                dao.upsertProvider(provider.copy(enabled = false, healthStatus = "UNCONFIGURED"))
                coordinator.refreshAiConfigurationStatus()
                _statusBanner.value = "Từ chối bật ${provider.displayName} (Mục 4): Chưa cấu hình khóa API hợp lệ."
                return@launch
            }

            if (provider.healthStatus != "VERIFIED_HEALTHY") {
                dao.upsertProvider(provider.copy(enabled = false))
                coordinator.refreshAiConfigurationStatus()
                _statusBanner.value = "Từ chối bật ${provider.displayName} (Mục 4): Cần chạy '[2/3] Test Model' hoặc 'Kiểm tra Health Check & Phân tích AI' thành công trước khi kích hoạt công tắc Provider."
                return@launch
            }

            dao.upsertProvider(provider.copy(enabled = true))
            coordinator.refreshAiConfigurationStatus()
            _statusBanner.value = "Đã bật ${provider.displayName} (Health Check = VERIFIED_HEALTHY)."
        }
    }

    /**
     * MOD-07 & Mục 12.3 · Adds or updates a custom AI Provider after verifying HTTPS allowlist.
     */
    fun addOrUpdateCustomAiProvider(
        displayName: String,
        apiType: String,
        baseUrl: String,
        modelId: String,
        role: String
    ) {
        viewModelScope.launch {
            val cleanUrl = baseUrl.trim().removeSuffix("/")
            if (!AiIntelligenceGateway.isHostAllowed(cleanUrl)) {
                _statusBanner.value = "Từ chối thêm Provider (MOD-07 SSRF Guard): Base URL '$cleanUrl' không nằm trong danh sách HTTPS Allowlist."
                return@launch
            }
            if (displayName.isBlank() || modelId.isBlank()) {
                _statusBanner.value = "Tên hiển thị và Model ID không được để trống."
                return@launch
            }
            val providerId = "custom-" + displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val secretSlot = if (role == "SKEPTIC") SecretVault.SLOT_AI_SKEPTIC_KEY else SecretVault.SLOT_AI_PRIMARY_KEY
            dao.upsertProvider(
                ProviderConfigEntity(
                    providerId = providerId,
                    displayName = displayName.trim(),
                    apiType = apiType,
                    baseUrl = cleanUrl,
                    modelId = modelId.trim(),
                    role = role,
                    secretSlot = secretSlot,
                    timeoutMs = 15_000L,
                    enabled = false,
                    healthStatus = "UNCONFIGURED"
                )
            )
            coordinator.refreshAiConfigurationStatus()
            _statusBanner.value = "Đã lưu AI Provider '${displayName.trim()}' ($modelId · $cleanUrl) ở trạng thái OFF/UNCONFIGURED. Hãy cấu hình Key và chạy Health Check trước khi bật."
        }
    }

    fun applyTestedAiProviderToBot(
        providerKey: String,
        displayName: String,
        protocol: String,
        baseUrl: String,
        modelId: String,
        role: String,
        secretSlot: String,
        verifiedHealthy: Boolean,
        latencyMs: Long?
    ) {
        viewModelScope.launch {
            val cleanUrl = baseUrl.trim().removeSuffix("/")
            if (displayName.isBlank() || modelId.isBlank()) {
                _statusBanner.value = "Tên Provider và Model ID không được để trống."
                return@launch
            }
            val providerId = "aichecker-${providerKey.lowercase()}-${role.lowercase()}"
            val astraApiType = com.example.ai.AiApiCheckerEngine.toAstraApiType(protocol)
            val hasKey = secretVault.hasSecret(secretSlot) ||
                AiIntelligenceGateway.isLocalHostUrl(cleanUrl)

            val effectiveHealthy = verifiedHealthy && hasKey
            dao.upsertProvider(
                ProviderConfigEntity(
                    providerId = providerId,
                    displayName = displayName.trim(),
                    apiType = astraApiType,
                    baseUrl = cleanUrl,
                    modelId = modelId.trim(),
                    role = role,
                    secretSlot = secretSlot,
                    timeoutMs = 15_000L,
                    enabled = effectiveHealthy,
                    healthStatus = if (effectiveHealthy) "VERIFIED_HEALTHY" else if (hasKey) "CONFIGURED" else "UNCONFIGURED",
                    lastLatencyMs = latencyMs ?: 0L
                )
            )
            _secretPreviewRefreshTick.value++
            coordinator.refreshAiConfigurationStatus()
            _statusBanner.value = if (effectiveHealthy) {
                "Đã lưu & kích hoạt AI Provider '${displayName.trim()}' ($role · VERIFIED_HEALTHY · ${latencyMs ?: 0}ms)."
            } else {
                "Đã lưu AI Provider '${displayName.trim()}' ($role · $cleanUrl). Hãy chạy '[2/3] Test Model' hoặc 'Chạy phân tích AI' để xác minh Health Check."
            }
        }
    }

    fun saveTelegramConfig(botToken: String, chatIdWhitelist: String, userIdWhitelist: String) {
        viewModelScope.launch {
            if (botToken.isNotBlank()) {
                secretVault.putSecret(SecretVault.SLOT_TELEGRAM_BOT_TOKEN, botToken)
            }
            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            dao.upsertSettings(
                current.copy(
                    telegramChatIdWhitelist = chatIdWhitelist.trim(),
                    telegramUserIdWhitelist = userIdWhitelist.trim(),
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            _secretPreviewRefreshTick.value++
            _statusBanner.value = "Đã lưu cấu hình Telegram Bot & Whitelist User/Chat ID (MOD-14/15)."
        }
    }

    fun sendTestTelegramNotification() {
        viewModelScope.launch {
            val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
            val chatId = settings.telegramChatIdWhitelist.trim()
            if (!secretVault.hasSecret(SecretVault.SLOT_TELEGRAM_BOT_TOKEN) || chatId.isBlank()) {
                _statusBanner.value = "Chưa cấu hình Telegram Bot Token hoặc Chat ID Whitelist để gửi thông báo thực."
                return@launch
            }
            _isBusy.value = true
            val cap = coordinator.accountCapitalState.value
            val eqStr = cap.exchangeEquityUsdt?.toPlainString() ?: "UNVERIFIED/UNKNOWN"
            val sent = telegramController.sendVietnameseNotification(
                chatId = chatId,
                eventTag = "HEALTH_PING",
                bodyVi = "Kiểm tra kết nối từ Astra Trader V5.1 · Trạng thái=${coordinator.botState.value.name} · Equity sàn=$eqStr (${cap.verificationState.badgeVi})"
            )
            _statusBanner.value = if (sent) {
                "Đã gửi thông báo thử nghiệm tới Telegram Chat ID $chatId thành công."
            } else {
                "Không thể gửi thông báo tới Telegram (kiểm tra Bot Token, Chat ID hoặc kết nối mạng)."
            }
            _isBusy.value = false
        }
    }

    fun simulateTelegramCommand(rawCommandText: String, fromUserId: String, chatId: String) {
        viewModelScope.launch {
            val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
            val fakeUpdateId = System.currentTimeMillis()
            val outcome = telegramController.processIncomingUpdate(
                updateId = fakeUpdateId,
                fromUserId = fromUserId,
                chatId = chatId,
                text = rawCommandText,
                whitelistedUserId = settings.telegramUserIdWhitelist.ifBlank { fromUserId },
                whitelistedChatId = settings.telegramChatIdWhitelist.ifBlank { chatId },
                onAuthorizedCommand = { cmd ->
                    when (cmd) {
                        "/status" -> {
                            val cap = coordinator.accountCapitalState.value
                            val eqText = cap.exchangeEquityUsdt?.toPlainString() ?: "UNVERIFIED/UNKNOWN"
                            "Trạng thái: ${coordinator.botState.value.name} (${settings.environmentMode}) · Equity sàn: $eqText USDT (${cap.verificationState.badgeVi})"
                        }
                        "/pause" -> {
                            coordinator.transitionState(BotState.PAUSED_USER, "Lệnh /pause từ Telegram")
                            "Đã tạm dừng mở lệnh mới (PAUSED_USER)."
                        }
                        "/drain" -> {
                            triggerDrainMode()
                            "Đã kích hoạt DRAIN: Dừng tìm lệnh mới, chờ vị thế đóng."
                        }
                        "/resume" -> {
                            coordinator.runFullReconciliation(_selectedSymbol.value)
                        }
                        "/report" -> {
                            val snap = dao.getLatestDailySnapshotOnce()
                            val cap = coordinator.accountCapitalState.value
                            "Báo cáo PnL điều chỉnh dòng tiền (INV-18): ${snap?.cashflowAdjustedPnlUsdt ?: "0.0000"} USDT · Trạng thái tài khoản: ${cap.verificationState.badgeVi}"
                        }
                        "/panic_confirmed" -> {
                            coordinator.executePanicCloseVerifiedPosition("Telegram /panic_confirm 2 bước")
                        }
                        else -> "Danh sách lệnh: /status, /pause, /drain, /resume, /report, /panic"
                    }
                }
            )
            if (outcome.replyMessageVi.isNotBlank()) {
                _statusBanner.value = "[Telegram ${outcome.command}] ${outcome.replyMessageVi}"
            } else {
                _statusBanner.value = "[Telegram ${outcome.command}] Đã chặn/bỏ qua âm thầm theo quy tắc bảo mật."
            }
        }
    }

    fun switchEnvironmentMode(targetMode: EnvironmentMode, userConfirmedLiveDialog: Boolean) {
        viewModelScope.launch {
            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            if (targetMode == EnvironmentMode.TEST) {
                dao.upsertSettings(
                    current.copy(
                        environmentMode = "TEST",
                        liveExplicitlyArmed = false,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                coordinator.updateLiveChecklist()
                _statusBanner.value = "Đã chuyển về chế độ kiểm thử an toàn (TEST)."
                return@launch
            }

            // Requirement 1, 3, 6.9: Cannot enable LIVE unless safety lock is disengaged AND Binance account is verified
            val updated = current.copy(
                liveExplicitlyArmed = userConfirmedLiveDialog,
                riskLimitsReviewed = true,
                updatedAtMs = System.currentTimeMillis()
            )
            dao.upsertSettings(updated)
            val checklist = coordinator.updateLiveChecklist()
            if (checklist.canEnableLive) {
                dao.upsertSettings(updated.copy(environmentMode = "LIVE"))
                _statusBanner.value = "ĐÃ KÍCH HOẠT LIVE (Đã xác minh tài khoản Binance & mở khóa an toàn)."
            } else {
                dao.upsertSettings(updated.copy(environmentMode = "TEST", liveExplicitlyArmed = false))
                _statusBanner.value = "GIỮ KHÓA LIVE (Mục 1 & 3): Chưa đạt đủ điều kiện (Khóa an toàn Debug=${coordinator.safetyDebugLockActive.value}, Tài khoản xác minh=${checklist.accountVerifiedFromBinance}, ClockSync=${checklist.clockSyncHealthy})."
            }
        }
    }

    fun toggleForegroundService(enable: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, TradingForegroundService::class.java).apply {
            action = if (enable) {
                TradingForegroundService.ACTION_START_SERVICE
            } else {
                TradingForegroundService.ACTION_STOP_SERVICE
            }
        }
        try {
            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                _statusBanner.value = "Đã bật TradingForegroundService (MOD-01 · Thông báo không âm thanh, WakeLock có thời hạn)."
            } else {
                app.startService(intent)
                _statusBanner.value = "Đã dừng TradingForegroundService."
            }
        } catch (e: Exception) {
            _statusBanner.value = "Không thể đổi trạng thái ForegroundService: ${e.javaClass.simpleName}"
        }
    }

    fun buildSanitizedDiagnosticJson(): String {
        val s = settingsFlow.value
        val h = coordinator.deviceHealth.value
        val c = coordinator.checklist.value
        val cap = coordinator.accountCapitalState.value
        val diag = coordinator.authDiagnosticReport.value
        return JSONObject().apply {
            put("spec_version", "Astra Trader V5.1")
            put("environment_mode", s.environmentMode)
            put("binance_endpoint_target", s.binanceEndpointTarget)
            put("active_endpoint_target", cap.activeEndpointTarget)
            put("safety_debug_lock_active", coordinator.safetyDebugLockActive.value)
            put("bot_state", coordinator.botState.value.name)
            put("config_version", s.configVersion)
            put("declared_capital_vnd", cap.declaredCapitalVnd)
            put("estimated_capital_from_vnd_usdt", cap.estimatedCapitalFromVndUsdt.toPlainString())
            put("exchange_equity_usdt", cap.exchangeEquityUsdt?.toPlainString() ?: "UNVERIFIED/UNKNOWN")
            put("exchange_available_balance_usdt", cap.exchangeAvailableBalanceUsdt?.toPlainString() ?: "UNVERIFIED/UNKNOWN")
            put("vault_credential_state", cap.vaultCredentialState.name)
            put("account_verification_state", cap.verificationState.name)
            put("last_failed_step", cap.lastFailedStep ?: JSONObject.NULL)
            put("last_failed_endpoint", cap.lastFailedEndpoint ?: JSONObject.NULL)
            put("last_failed_binance_code", cap.lastFailedBinanceCode ?: JSONObject.NULL)
            put("last_raw_binance_error_msg", cap.lastRawBinanceErrorMsg ?: JSONObject.NULL)
            val net = coordinator.outboundNetworkIdentity.value
            put("outbound_ipv4", net.outboundIpv4 ?: diag?.outboundNetwork?.outboundIpv4 ?: JSONObject.NULL)
            put("outbound_ipv6", net.outboundIpv6 ?: diag?.outboundNetwork?.outboundIpv6 ?: JSONObject.NULL)
            put("outbound_default_ip", net.outboundDefaultIp ?: diag?.outboundNetwork?.outboundDefaultIp ?: JSONObject.NULL)
            put("same_android_network_bound", net.sameAndroidNetworkBound)
            put("network_transport", net.transportTypeVi)
            put("vpn_or_proxy_detected", net.vpnActive || net.systemProxy != "NONE")
            put("ai_module_status", coordinator.aiModuleStatus.value.name)
            put("risk_per_trade_pct", s.riskPerTradePct)
            put("daily_loss_limit_pct", s.dailyLossLimitPct)
            put("max_drawdown_limit_pct", s.maxDrawdownLimitPct)
            put("leverage_cap", s.defaultLeverageCap)
            put("binance_key_loaded_from_vault", secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY) && secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY))
            put("binance_key_attached_to_header", diag?.apiKeyAttachedToHeader ?: false)
            put("device_battery_temp_c", h.batteryTempCelsius)
            put("device_available_ram_mb", h.availableRamMb)
            put("device_kernel_total_ram_mb", h.kernelTotalRamMb)
            put("device_physical_nominal_ram_gb", h.physicalNominalRamGb)
            put("internet_connected", connectivityState.value.isConnected)
            put("internet_validated", connectivityState.value.isValidated)
            put("live_checklist_ready", c.canEnableLive)
            put("secrets_redacted", true)
        }.toString(2)
    }

    private fun onNetworkConnectivityChanged(netState: NetworkConnectivityState) {
        viewModelScope.launch {
            if (!netState.isConnected) {
                _statusBanner.value = netState.statusMessageVi
                coordinator.transitionState(
                    BotState.PAUSED_DATA_STALE,
                    "Mất kết nối Internet (${netState.transportLabelVi})"
                )
                dao.insertSystemEvent(
                    SystemEventEntity(
                        category = "NETWORK",
                        level = "ERROR",
                        titleVi = "Mất kết nối Internet (${netState.transportLabelVi})",
                        detailVi = netState.statusMessageVi,
                        correlationId = "net-lost-${System.currentTimeMillis()}"
                    )
                )
            } else {
                _statusBanner.value = netState.statusMessageVi
                dao.insertSystemEvent(
                    SystemEventEntity(
                        category = "NETWORK",
                        level = "INFO",
                        titleVi = "Khôi phục kết nối Internet (${netState.transportLabelVi})",
                        detailVi = netState.statusMessageVi,
                        correlationId = "net-ok-${System.currentTimeMillis()}"
                    )
                )
            }
        }
    }

    fun recheckNetworkConnectivity() {
        val current = connectivityMonitor.refreshNow()
        _statusBanner.value = current.statusMessageVi
    }

    fun toggleForecastOperatingMode() {
        viewModelScope.launch {
            val current = dao.getSettingsOnce() ?: AppSettingsEntity()
            val nextMode = if (current.forecastOperatingMode.equals("ADVISORY_TEST_ONLY", ignoreCase = true)) {
                "SHADOW"
            } else {
                "ADVISORY_TEST_ONLY"
            }
            dao.upsertSettings(current.copy(forecastOperatingMode = nextMode, updatedAtMs = System.currentTimeMillis()))
            val msg = coordinator.scanAndEvaluateSymbol(_selectedSymbol.value)
            _statusBanner.value = "Forecast Engine chuyển sang chế độ $nextMode · $msg"
        }
    }

    fun toggleForecastRollbackModel(enableRollback: Boolean) {
        viewModelScope.launch {
            coordinator.forecastCoordinator.setManualRollback(enableRollback)
            val msg = coordinator.scanAndEvaluateSymbol(_selectedSymbol.value)
            _statusBanner.value = if (enableRollback) {
                "Đã kích hoạt Rollback sang Baseline (${coordinator.forecastDiagnostics.value.activeModelId}) · $msg"
            } else {
                "Đã khôi phục Champion Model (${coordinator.forecastDiagnostics.value.activeModelId}) · $msg"
            }
        }
    }

    override fun onCleared() {
        connectivityMonitor.stopMonitoring()
        super.onCleared()
    }
}

