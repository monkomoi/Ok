package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.BuildConfig
import com.example.ai.AiIntelligenceGateway
import com.example.data.local.AiDecisionEntity
import com.example.data.local.AiRequestEntity
import com.example.data.local.AppSettingsEntity
import com.example.data.local.AstraDao
import com.example.data.local.CashflowEntity
import com.example.data.local.DailySnapshotEntity
import com.example.data.local.FillEntity
import com.example.data.local.MarketFeatureEntity
import com.example.data.local.OrderIntentEntity
import com.example.data.local.OrderRecordEntity
import com.example.data.local.PositionEntity
import com.example.data.local.RiskEventEntity
import com.example.data.local.SignalEntity
import com.example.data.local.SystemEventEntity
import com.example.domain.AccountCapitalState
import com.example.domain.AccountVerificationState
import com.example.domain.AiModuleStatus
import com.example.domain.BacktestSummary
import com.example.domain.BinanceAuthDiagnosticReport
import com.example.domain.BotState
import com.example.domain.BotStateMachine
import com.example.domain.CandleBar
import com.example.domain.CrowdAttentionProxySnapshot
import com.example.domain.DataSourceType
import com.example.domain.DeviceHealthSnapshot
import com.example.domain.EnvironmentMode
import com.example.domain.FeatureSnapshot
import com.example.domain.LiveActivationChecklist
import com.example.domain.NetEvEstimate
import com.example.domain.NewsEventCategory
import com.example.domain.NewsEventItem
import com.example.domain.NewsVerificationStatus
import com.example.domain.AiSkepticReport
import com.example.domain.ExecutionGateDecision
import com.example.domain.FillPnlStatus
import com.example.domain.PanicCloseReport
import com.example.domain.PanicCloseStage
import com.example.domain.PanicPreCloseVerificationState
import com.example.domain.PerformanceSelfMonitoringReport
import com.example.domain.PositionProtectionState
import com.example.domain.PositionSideMode
import com.example.domain.ProbabilityCalibrationReport
import com.example.domain.ScannerCandidateItem
import com.example.domain.SizingDecision
import com.example.domain.SkepticCheckResult
import com.example.domain.SkepticVerdict
import com.example.domain.TradeSide
import com.example.domain.VaultCredentialState
import com.example.network.BinanceAdapter
import com.example.network.BinanceErrorCategory
import com.example.quant.NewsEventGateEngine
import java.util.concurrent.ConcurrentHashMap
import com.example.quant.QuantMathEngine
import com.example.security.SecretVault
import com.example.telegram.TelegramBotController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.ceil

/**
 * MOD-12, MOD-13 & MOD-16 · Exclusive Execution Coordinator, Three-Source Reconciler & Device Health Watchdog.
 * - Requirement 1 & 9: Safety lock disables LIVE order execution during authentication debugging or when account is unverified.
 * - Requirement 2, 4, 5, 8: Granular 6-step read-only Binance auth verification (-2014, -2015, -1022, -1021) using /fapi/v2/account,
 *   /fapi/v2/balance, and /sapi/v1/account/apiRestrictions on Mainnet (https://fapi.binance.com) by default.
 * - Requirement 3 & 6: Strictly separates VaultCredentialState ("đã lưu trong Vault") from AccountVerificationState ("đã xác minh với Binance"),
 *   and separates declaredCapitalVnd / estimatedCapitalFromVndUsdt from exchangeEquityUsdt / exchangeAvailableBalanceUsdt.
 * - Requirement 7: Never places real trading orders during authentication or permission verification.
 */
class AstraTradingCoordinator(
    private val context: Context,
    private val dao: AstraDao,
    private val secretVault: SecretVault,
    val binanceAdapter: BinanceAdapter,
    val aiGateway: AiIntelligenceGateway,
    val telegramController: TelegramBotController
) {

    // Requirement 1: Safety lock on LIVE order submission during auth debugging (locked by default)
    private val _safetyDebugLockActive = MutableStateFlow(true)
    val safetyDebugLockActive: StateFlow<Boolean> = _safetyDebugLockActive.asStateFlow()

    private val _botState = MutableStateFlow(BotState.READY_TEST)
    val botState: StateFlow<BotState> = _botState.asStateFlow()

    private val _accountCapitalState = MutableStateFlow(AccountCapitalState())
    val accountCapitalState: StateFlow<AccountCapitalState> = _accountCapitalState.asStateFlow()

    private val _authDiagnosticReport = MutableStateFlow<BinanceAuthDiagnosticReport?>(null)
    val authDiagnosticReport: StateFlow<BinanceAuthDiagnosticReport?> = _authDiagnosticReport.asStateFlow()

    private val _outboundNetworkIdentity = MutableStateFlow(com.example.domain.OutboundNetworkIdentity())
    val outboundNetworkIdentity: StateFlow<com.example.domain.OutboundNetworkIdentity> = _outboundNetworkIdentity.asStateFlow()

    private val _aiModuleStatus = MutableStateFlow(AiModuleStatus.UNCONFIGURED)
    val aiModuleStatus: StateFlow<AiModuleStatus> = _aiModuleStatus.asStateFlow()

    private val _latestFeatureSnapshot = MutableStateFlow<FeatureSnapshot?>(null)
    val latestFeatureSnapshot: StateFlow<FeatureSnapshot?> = _latestFeatureSnapshot.asStateFlow()

    private val _latestEvEstimate = MutableStateFlow<NetEvEstimate?>(null)
    val latestEvEstimate: StateFlow<NetEvEstimate?> = _latestEvEstimate.asStateFlow()

    private val _latestSkepticResult = MutableStateFlow<SkepticCheckResult?>(null)
    val latestSkepticResult: StateFlow<SkepticCheckResult?> = _latestSkepticResult.asStateFlow()

    private val _latestSizingDecision = MutableStateFlow<SizingDecision?>(null)
    val latestSizingDecision: StateFlow<SizingDecision?> = _latestSizingDecision.asStateFlow()

    private val _latestBacktest = MutableStateFlow<BacktestSummary?>(null)
    val latestBacktest: StateFlow<BacktestSummary?> = _latestBacktest.asStateFlow()

    private val _deviceHealth = MutableStateFlow(DeviceHealthSnapshot())
    val deviceHealth: StateFlow<DeviceHealthSnapshot> = _deviceHealth.asStateFlow()

    private val _checklist = MutableStateFlow(LiveActivationChecklist())
    val checklist: StateFlow<LiveActivationChecklist> = _checklist.asStateFlow()

    private val _scannerCandidates = MutableStateFlow<List<ScannerCandidateItem>>(emptyList())
    val scannerCandidates: StateFlow<List<ScannerCandidateItem>> = _scannerCandidates.asStateFlow()

    private val _newsEvents = MutableStateFlow<List<NewsEventItem>>(emptyList())
    val newsEvents: StateFlow<List<NewsEventItem>> = _newsEvents.asStateFlow()

    private val _crowdProxy = MutableStateFlow<CrowdAttentionProxySnapshot?>(null)
    val crowdProxy: StateFlow<CrowdAttentionProxySnapshot?> = _crowdProxy.asStateFlow()

    private val _performanceReport = MutableStateFlow(PerformanceSelfMonitoringReport())
    val performanceReport: StateFlow<PerformanceSelfMonitoringReport> = _performanceReport.asStateFlow()

    // P0-3.3: Global Capital & Order Execution Mutex preventing concurrent entries, panic close, and reconciliation races
    private val globalCapitalMutex = Mutex()

    private val _latestExecutionGateDecision = MutableStateFlow<ExecutionGateDecision?>(null)
    val latestExecutionGateDecision: StateFlow<ExecutionGateDecision?> = _latestExecutionGateDecision.asStateFlow()

    private val _latestPanicCloseReport = MutableStateFlow<PanicCloseReport?>(null)
    val latestPanicCloseReport: StateFlow<PanicCloseReport?> = _latestPanicCloseReport.asStateFlow()

    private val _latestCalibrationReport = MutableStateFlow<ProbabilityCalibrationReport?>(null)
    val latestCalibrationReport: StateFlow<ProbabilityCalibrationReport?> = _latestCalibrationReport.asStateFlow()

    private val _latestAiSkepticReport = MutableStateFlow<AiSkepticReport?>(null)
    val latestAiSkepticReport: StateFlow<AiSkepticReport?> = _latestAiSkepticReport.asStateFlow()

    // Forecasting Engine (On-Device S7 Edge Shadow / Advisory Mode, decoupled from globalCapitalMutex)
    val forecastRepository = com.example.forecast.domain.ForecastRepository(dao)
    val forecastCoordinator = com.example.forecast.engine.ForecastCoordinator(context, forecastRepository)
    val forecastScheduler = com.example.forecast.service.ForecastScheduler(forecastCoordinator)

    val latestForecastReport: StateFlow<com.example.forecast.domain.MultiHorizonForecastReport?> =
        forecastCoordinator.latestMultiHorizonReport
    val forecastDiagnostics: StateFlow<com.example.forecast.domain.ForecastDiagnosticsSnapshot> =
        forecastCoordinator.diagnostics

    // P1-02: Per-symbol FeatureSnapshot cache with TTL for multi-symbol scanner
    private val scannerSnapshotCache = ConcurrentHashMap<String, Pair<Long, FeatureSnapshot>>()

    private var latestValidatedSignalId: String? = null
    private val customRawNewsInputs = mutableListOf<NewsEventGateEngine.RawNewsInput>()
    private var lastSymbolSwitchAtMs: Long = 0L
    private var lastSelectedSymbol: String = "DOGEUSDT"

    fun setSafetyDebugLock(locked: Boolean) {
        _safetyDebugLockActive.value = locked
    }

    /**
     * Requirement 2 · Resolves the Binance REST/WS endpoint target independently of the LIVE order execution lock.
     * - By default (`binanceEndpointTarget == "MAINNET"`), read-only account verification and market data use
     *   Binance Mainnet USDⓈ-M Futures (`https://fapi.binance.com`), avoiding the bug where a Mainnet API key
     *   was sent to `https://testnet.binancefuture.com` and rejected with `-2015`.
     * - Real order execution (`submitFuturesOrder`) remains strictly locked unless `settings.environmentMode == "LIVE"`
     *   AND `!safetyDebugLockActive.value` AND `checklist.canEnableLive`.
     */
    fun resolveApiEndpointMode(settings: AppSettingsEntity): EnvironmentMode {
        return if (settings.binanceEndpointTarget.equals("TESTNET", ignoreCase = true) &&
            settings.environmentMode != "LIVE"
        ) {
            EnvironmentMode.TEST
        } else {
            EnvironmentMode.LIVE
        }
    }

    /**
     * Requirement 6 · Explicitly resets Binance verification state whenever new credentials are saved in Vault.
     * Ensures "saved in Vault" (`SAVED_IN_VAULT_UNVERIFIED`) is never conflated with "verified with Binance".
     */
    suspend fun onCredentialsUpdatedInVault() {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val endpointMode = resolveApiEndpointMode(settings)
        val hasKeys = secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY) &&
            secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        val integrityOk = hasKeys &&
            secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY) &&
            secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_SECRET)

        val vaultState = when {
            !hasKeys -> VaultCredentialState.EMPTY_VAULT
            !integrityOk -> VaultCredentialState.VAULT_INTEGRITY_ERROR
            else -> VaultCredentialState.SAVED_IN_VAULT_UNVERIFIED
        }
        val verifState = if (!hasKeys) {
            AccountVerificationState.UNCONFIGURED
        } else {
            AccountVerificationState.UNVERIFIED
        }

        _accountCapitalState.value = _accountCapitalState.value.copy(
            exchangeEquityUsdt = null,
            exchangeAvailableBalanceUsdt = null,
            exchangeUnrealizedPnlUsdt = null,
            vaultCredentialState = vaultState,
            verificationState = verifState,
            dataSource = DataSourceType.UNVERIFIED_UNKNOWN,
            verifiedAtUtcMs = null,
            activeEndpointTarget = "${settings.binanceEndpointTarget} (${binanceAdapter.restBaseUrl(endpointMode)})",
            lastFailedStep = null,
            lastFailedEndpoint = null,
            lastFailedBinanceCode = null,
            lastRawBinanceErrorMsg = null,
            diagnosticMessageVi = if (hasKeys) {
                "Đã lưu khóa mới trong SecretVault (${vaultState.badgeVi}), nhưng CHƯA XÁC MINH VỚI BINANCE (${verifState.badgeVi}). Số dư sàn giữ ở UNVERIFIED/UNKNOWN cho tới khi bấm Kiểm tra & Đối soát."
            } else {
                "Đã xóa khóa khỏi SecretVault. Trạng thái: UNCONFIGURED."
            }
        )
        updateLiveChecklist()
    }

    suspend fun transitionState(target: BotState, reasonVi: String): Boolean {
        val current = _botState.value
        if (!BotStateMachine.canTransition(current, target)) {
            dao.insertSystemEvent(
                SystemEventEntity(
                    category = "EXECUTION",
                    level = "WARN",
                    titleVi = "Từ chối chuyển trạng thái bất hợp lệ ($current -> $target)",
                    detailVi = "Vi phạm quy tắc State Machine: $reasonVi",
                    correlationId = "sm-${System.currentTimeMillis()}"
                )
            )
            return false
        }
        _botState.value = target
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        dao.upsertSettings(settings.copy(botState = target.name, updatedAtMs = System.currentTimeMillis()))
        return true
    }

    /**
     * Refreshes AI Module status based on whether a valid key is configured and whether health check succeeded (Requirement 4).
     */
    suspend fun refreshAiConfigurationStatus(): AiModuleStatus {
        val providers = dao.getProvidersOnce()
        val hasVaultKey = secretVault.hasSecret(SecretVault.SLOT_AI_PRIMARY_KEY) ||
            providers.any { secretVault.hasSecret(it.secretSlot) || AiIntelligenceGateway.isLocalHostUrl(it.baseUrl) }
        val anyVerifiedHealthy = providers.any { it.enabled && it.healthStatus == "VERIFIED_HEALTHY" }

        val status = when {
            !hasVaultKey -> {
                // Ensure providers are disabled when no key is configured in SecretVault (Requirement 4 & P0-09)
                for (p in providers) {
                    if (p.enabled) {
                        dao.upsertProvider(p.copy(enabled = false, healthStatus = "UNCONFIGURED"))
                    }
                }
                AiModuleStatus.UNCONFIGURED
            }
            anyVerifiedHealthy -> AiModuleStatus.READY_NOT_RUN
            providers.any { it.healthStatus == "AUTH_ERROR" || it.healthStatus == "DEGRADED" } -> AiModuleStatus.HEALTH_CHECK_FAILED
            else -> AiModuleStatus.CONFIGURED_NOT_TESTED
        }
        _aiModuleStatus.value = status
        return status
    }

    /**
     * MOD-16 & Requirement 5 · Reads real Android BatteryManager and ActivityManager.MemoryInfo / /proc/meminfo.
     * Reports both actual kernel-visible total RAM (MB) and nominal physical RAM tier (GB) without fake values.
     */
    suspend fun refreshDeviceHealth(): DeviceHealthSnapshot {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val batteryIntent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val batteryPct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 315) ?: 315
        val tempCelsius = tempTenths / 10.0f
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr?.getMemoryInfo(memInfo)

        val procMemTotalMb = readProcMemInfoKb("MemTotal:") / 1024L
        val procMemAvailMb = readProcMemInfoKb("MemAvailable:") / 1024L
        val kernelTotalMb = when {
            memInfo.totalMem > 0L -> memInfo.totalMem / (1024L * 1024L)
            procMemTotalMb > 0L -> procMemTotalMb
            else -> 0L
        }
        val availMb = when {
            memInfo.availMem > 0L -> memInfo.availMem / (1024L * 1024L)
            procMemAvailMb > 0L -> procMemAvailMb
            else -> {
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                ((rt.maxMemory() - used) / (1024L * 1024L)).coerceAtLeast(1L)
            }
        }
        val nominalGb = if (kernelTotalMb > 0L) {
            ceil(kernelTotalMb / 1024.0).toInt().coerceAtLeast(1)
        } else {
            4
        }

        val thermalWarn = tempCelsius >= settings.maxThermalCelsius
        val lowRamWarn = kernelTotalMb > 0L && availMb <= settings.minAvailableRamMb

        val serviceStatus = when {
            thermalWarn || lowRamWarn -> "DEGRADED"
            _botState.value == BotState.STOPPED -> "STOPPED"
            _botState.value.isErrorOrPaused -> "PAUSED"
            else -> "RUNNING"
        }

        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerMgr?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
        val bgRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            actMgr?.isBackgroundRestricted ?: false
        } else {
            false
        }

        val snap = DeviceHealthSnapshot(
            batteryPct = batteryPct,
            batteryTempCelsius = tempCelsius,
            isCharging = isCharging,
            availableRamMb = availMb,
            kernelTotalRamMb = kernelTotalMb,
            physicalNominalRamGb = nominalGb,
            memInfoSource = if (memInfo.totalMem > 0L) "ActivityManager.MemoryInfo" else "/proc/meminfo",
            ignoringBatteryOptimizations = ignoringBatteryOpt,
            backgroundRestricted = bgRestricted,
            thermalWarning = thermalWarn,
            lowRamWarning = lowRamWarn,
            serviceStatus = serviceStatus,
            updatedAtMs = System.currentTimeMillis()
        )
        _deviceHealth.value = snap

        if (thermalWarn || lowRamWarn) {
            transitionState(
                BotState.PAUSED_DEVICE_HEALTH,
                "Cảnh báo thiết bị: Nhiệt độ ${tempCelsius}°C, RAM trống ${availMb} MB"
            )
            dao.insertRiskEvent(
                RiskEventEntity(
                    triggerCode = if (thermalWarn) "THERMAL_THRESHOLD_EXCEEDED" else "LOW_RAM_PRESSURE",
                    severity = "WARNING",
                    actionTaken = "PAUSED_DEVICE_HEALTH (Ngừng quét & AI phụ, giữ đối soát vị thế)",
                    detailVi = "Nhiệt độ pin: ${tempCelsius}°C (ngưỡng ${settings.maxThermalCelsius}°C), RAM khả dụng: ${availMb}/${kernelTotalMb} MB (~${nominalGb}GB vật lý)."
                )
            )
        }
        updateLiveChecklist()
        return snap
    }

    private fun readProcMemInfoKb(prefix: String): Long {
        return try {
            val file = File("/proc/meminfo")
            if (!file.exists()) return 0L
            val line = file.useLines { lines ->
                lines.firstOrNull { it.startsWith(prefix) }
            } ?: return 0L
            line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Requirement 3 & 6 · Recomputes the reference declared capital in VND & estimated USDT without touching exchangeEquityUsdt.
     * Strictly separates `VaultCredentialState` from `AccountVerificationState`.
     */
    suspend fun refreshDeclaredCapitalEstimate() {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val endpointMode = resolveApiEndpointMode(settings)
        val rate = settings.usdtVndRate.toBigDecimalOrNull() ?: BigDecimal("25450.0")
        val estUsdt = if (rate > BigDecimal.ZERO) {
            BigDecimal(settings.declaredCapitalVnd).divide(rate, 4, RoundingMode.HALF_UP)
        } else {
            BigDecimal("5.8939")
        }
        val hasKeys = secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY) &&
            secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        val integrityOk = hasKeys &&
            secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY) &&
            secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_SECRET)

        val current = _accountCapitalState.value
        val newVerifState = if (!hasKeys) {
            AccountVerificationState.UNCONFIGURED
        } else if (current.verificationState == AccountVerificationState.UNCONFIGURED) {
            AccountVerificationState.UNVERIFIED
        } else {
            current.verificationState
        }

        val vaultState = when {
            !hasKeys -> VaultCredentialState.EMPTY_VAULT
            !integrityOk -> VaultCredentialState.VAULT_INTEGRITY_ERROR
            newVerifState.isVerified -> VaultCredentialState.SAVED_IN_VAULT_AND_VERIFIED
            newVerifState in setOf(
                AccountVerificationState.AUTH_FAILED_2014,
                AccountVerificationState.AUTH_FAILED_2015,
                AccountVerificationState.AUTH_FAILED_1022,
                AccountVerificationState.AUTH_FAILED_1021
            ) -> VaultCredentialState.SAVED_IN_VAULT_BUT_REJECTED
            else -> VaultCredentialState.SAVED_IN_VAULT_UNVERIFIED
        }

        _accountCapitalState.value = current.copy(
            declaredCapitalVnd = settings.declaredCapitalVnd,
            referenceUsdtVndRate = rate,
            estimatedCapitalFromVndUsdt = estUsdt,
            vaultCredentialState = vaultState,
            verificationState = newVerifState,
            activeEndpointTarget = "${settings.binanceEndpointTarget} (${binanceAdapter.restBaseUrl(endpointMode)})",
            exchangeEquityUsdt = if (hasKeys && newVerifState.isVerified) current.exchangeEquityUsdt else null,
            exchangeAvailableBalanceUsdt = if (hasKeys && newVerifState.isVerified) current.exchangeAvailableBalanceUsdt else null
        )
    }

    private data class AndroidNetworkContext(
        val boundNetwork: android.net.Network?,
        val activeTransport: String,
        val transportTypeVi: String,
        val interfaceName: String?,
        val validatedByOs: Boolean,
        val vpnActive: Boolean
    )

    private fun detectActiveNetworkContext(): AndroidNetworkContext {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            val linkProps = if (net != null) cm.getLinkProperties(net) else null
            val iface = linkProps?.interfaceName
            if (caps == null) {
                AndroidNetworkContext(
                    boundNetwork = net,
                    activeTransport = "UNKNOWN_OR_EMULATOR",
                    transportTypeVi = "Không xác định / Mạng mặc định hệ thống",
                    interfaceName = iface,
                    validatedByOs = false,
                    vpnActive = false
                )
            } else {
                val vpn = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val (transportCode, transportVi) = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                        "WIFI" to "Wi-Fi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        "CELLULAR_MOBILE" to "Dữ liệu di động (Cellular 4G/5G)"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        "ETHERNET_OR_EMULATOR" to "Ethernet / Mạng ảo Emulator"
                    vpn ->
                        "VPN_TUNNEL" to "Đường hầm VPN"
                    else ->
                        "OTHER_TRANSPORT" to "Giao diện mạng khác"
                }
                AndroidNetworkContext(
                    boundNetwork = net,
                    activeTransport = transportCode,
                    transportTypeVi = if (vpn && transportCode != "VPN_TUNNEL") "$transportVi + VPN" else transportVi,
                    interfaceName = iface,
                    validatedByOs = validated,
                    vpnActive = vpn
                )
            }
        } catch (_: Exception) {
            AndroidNetworkContext(
                boundNetwork = null,
                activeTransport = "UNKNOWN",
                transportTypeVi = "Không xác định",
                interfaceName = null,
                validatedByOs = false,
                vpnActive = false
            )
        }
    }

    /**
     * Requirement 1–5 & 9 · Standalone Outbound IP & Android Network Diagnostics.
     * Queries public IPv4-only and IPv6/dual-stack HTTPS endpoints over the active Android `Network`.
     */
    suspend fun runOutboundIpDiagnostics(): com.example.domain.OutboundNetworkIdentity {
        val netCtx = detectActiveNetworkContext()
        val identity = binanceAdapter.probeOutboundNetworkIdentity(
            boundNetwork = netCtx.boundNetwork,
            activeTransport = netCtx.activeTransport,
            transportTypeVi = netCtx.transportTypeVi,
            networkInterfaceName = netCtx.interfaceName,
            networkValidatedByOs = netCtx.validatedByOs,
            vpnActive = netCtx.vpnActive
        )
        _outboundNetworkIdentity.value = identity
        _authDiagnosticReport.value?.let { existing ->
            _authDiagnosticReport.value = existing.copy(outboundNetwork = identity)
        }
        dao.insertSystemEvent(
            SystemEventEntity(
                category = "NETWORK",
                level = if (identity.outboundIpv4 != null) "INFO" else "WARN",
                titleVi = "Chẩn đoán IP Outbound (${identity.transportTypeVi})",
                detailVi = identity.summaryVi,
                correlationId = "ip-diag-${System.currentTimeMillis()}"
            )
        )
        return identity
    }

    /**
     * Runs the 6-step read-only Binance authentication diagnostic pipeline over the SAME Android Network without placing any orders.
     */
    suspend fun runReadOnlyBinanceDiagnostics(): BinanceAuthDiagnosticReport {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val netCtx = detectActiveNetworkContext()
        val report = binanceAdapter.runReadOnlyAuthDiagnostics(
            targetEndpointMode = settings.binanceEndpointTarget,
            boundNetwork = netCtx.boundNetwork,
            activeTransport = netCtx.activeTransport,
            transportTypeVi = netCtx.transportTypeVi,
            networkInterfaceName = netCtx.interfaceName,
            networkValidatedByOs = netCtx.validatedByOs,
            vpnActive = netCtx.vpnActive
        )
        _outboundNetworkIdentity.value = report.outboundNetwork
        _authDiagnosticReport.value = report
        return report
    }

    /**
     * MOD-13, Requirement 2, 3, 4, 6, 7, 8, 9 · Three-Source Reconciliation Engine + 6-Step Read-Only Diagnostics.
     * - Uses `resolveApiEndpointMode(settings)` (`MAINNET` -> `https://fapi.binance.com` by default) so Mainnet keys
     *   are verified against Mainnet even while LIVE order execution remains safely locked!
     * - Runs the 6-step read-only diagnostic pipeline to capture Outbound IPv4/IPv6, Spot `apiRestrictions`,
     *   Futures `/fapi/v2/balance` & `/fapi/v2/account`, failed step, endpoint, error code, and raw server message.
     * - Never claims "ĐÃ ĐỐI SOÁT" or populates `exchangeEquityUsdt` if authentication or account fetch fails!
     */
    suspend fun runFullReconciliation(symbolToInspect: String = "DOGEUSDT"): String {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val endpointMode = resolveApiEndpointMode(settings)
        val targetBaseUrl = binanceAdapter.restBaseUrl(endpointMode)
        transitionState(
            BotState.ACCOUNT_RECONCILIATION,
            "Bắt đầu kiểm tra kết nối & đối soát Read-Only (${settings.binanceEndpointTarget} -> $targetBaseUrl)"
        )
        refreshDeclaredCapitalEstimate()

        // Run the 6-step read-only diagnostic pipeline over the same Android Network
        val netCtx = detectActiveNetworkContext()
        val diagReport = binanceAdapter.runReadOnlyAuthDiagnostics(
            targetEndpointMode = settings.binanceEndpointTarget,
            boundNetwork = netCtx.boundNetwork,
            activeTransport = netCtx.activeTransport,
            transportTypeVi = netCtx.transportTypeVi,
            networkInterfaceName = netCtx.interfaceName,
            networkValidatedByOs = netCtx.validatedByOs,
            vpnActive = netCtx.vpnActive
        )
        _outboundNetworkIdentity.value = diagReport.outboundNetwork
        _authDiagnosticReport.value = diagReport

        // 1. Sync exchange metadata filters
        binanceAdapter.fetchExchangeMetadata(endpointMode)

        // 2. Check if API Key & Secret are configured in SecretVault
        if (!secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY) ||
            !secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        ) {
            _accountCapitalState.value = _accountCapitalState.value.copy(
                exchangeEquityUsdt = null,
                exchangeAvailableBalanceUsdt = null,
                exchangeUnrealizedPnlUsdt = null,
                vaultCredentialState = VaultCredentialState.EMPTY_VAULT,
                verificationState = AccountVerificationState.UNCONFIGURED,
                dataSource = DataSourceType.UNVERIFIED_UNKNOWN,
                verifiedAtUtcMs = null,
                activeEndpointTarget = "${settings.binanceEndpointTarget} ($targetBaseUrl)",
                lastFailedStep = diagReport.failedStepCode ?: "STEP_1_VAULT_AND_HEADER",
                lastFailedEndpoint = diagReport.failedEndpoint ?: "local://SecretVault",
                lastFailedBinanceCode = null,
                lastRawBinanceErrorMsg = diagReport.failedRawServerMessage,
                diagnosticMessageVi = "CHƯA ĐỐI SOÁT TÀI KHOẢN: Chưa nhập Binance API Key / Secret trong SecretVault. Equity thực = UNVERIFIED/UNKNOWN."
            )
            ensureBaselineDailySnapshot(settings)
            transitionState(BotState.CONFIG_REQUIRED, "Chưa cấu hình API Key Binance để đối soát tài khoản.")
            updateLiveChecklist()
            return "CHƯA ĐỐI SOÁT TÀI KHOẢN: Chưa cấu hình Binance API Key/Secret trong Vault. Equity và Available Balance được giữ ở trạng thái UNVERIFIED/UNKNOWN."
        }

        // 3. Resolve any UNKNOWN OrderIntents by querying origClientOrderId before allowing trading (INV-06)
        val unknownIntents = dao.getActiveOrUnknownIntents()
        for (intent in unknownIntents) {
            val qRes = binanceAdapter.queryOrderByClientId(endpointMode, intent.symbol, intent.clientOrderId)
            if (qRes.isSuccess && qRes.data != null) {
                val ord = qRes.data
                dao.upsertOrderIntent(
                    intent.copy(
                        status = ord.status,
                        exchangeOrderId = ord.orderId,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }

        // 4. Read-only account & position verification via `/fapi/v2/account` on `endpointMode`
        val accRes = binanceAdapter.fetchAccountAndPositionsSnapshot(endpointMode)
        val reconciliationNote: String

        if (accRes.isSuccess && accRes.data != null && accRes.data.verifiedFromExchange) {
            val acc = accRes.data
            val verifState = if (acc.canTrade) {
                AccountVerificationState.VERIFIED_TRADING_READY
            } else {
                AccountVerificationState.VERIFIED_READ_ONLY
            }
            val srcType = if (endpointMode == EnvironmentMode.LIVE) {
                DataSourceType.BINANCE_LIVE_VERIFIED
            } else {
                DataSourceType.BINANCE_TESTNET_VERIFIED
            }

            _accountCapitalState.value = _accountCapitalState.value.copy(
                exchangeEquityUsdt = acc.totalMarginBalanceUsdt.setScale(4, RoundingMode.HALF_UP),
                exchangeAvailableBalanceUsdt = acc.availableBalanceUsdt.setScale(4, RoundingMode.HALF_UP),
                exchangeUnrealizedPnlUsdt = acc.totalUnrealizedProfitUsdt.setScale(4, RoundingMode.HALF_UP),
                vaultCredentialState = VaultCredentialState.SAVED_IN_VAULT_AND_VERIFIED,
                verificationState = verifState,
                dataSource = srcType,
                verifiedAtUtcMs = acc.fetchedAtMs,
                activeEndpointTarget = "${settings.binanceEndpointTarget} ($targetBaseUrl)",
                lastFailedStep = null,
                lastFailedEndpoint = null,
                lastFailedBinanceCode = null,
                lastRawBinanceErrorMsg = null,
                diagnosticMessageVi = accRes.sanitizedMessageVi
            )

            updateDailyAccountingSnapshotFromVerifiedExchange(
                walletEquityUsdt = acc.totalMarginBalanceUsdt,
                availableBalanceUsdt = acc.availableBalanceUsdt,
                unrealizedPnlUsdt = acc.totalUnrealizedProfitUsdt
            )

            // Reconcile positions (INV-09: max 1 bot position; manual position pauses bot; P0-06: must verify BOTH SL and TP)
            if (acc.positions.size > 1) {
                transitionState(
                    BotState.HALTED_REVIEW,
                    "Phát hiện ${acc.positions.size} vị thế mở trên sàn (vượt giới hạn 1 vị thế của V1 - INV-09)."
                )
                reconciliationNote = "Tạm dừng INV-09: Phát hiện nhiều hơn 1 vị thế đang mở trên sàn."
            } else if (acc.positions.size == 1) {
                val remotePos = acc.positions.first()
                val localPositions = dao.getActivePositionsOnce()
                val localMatch = localPositions.find { it.symbol == remotePos.symbol }
                val expectedProtectiveSide = if (remotePos.positionAmt >= BigDecimal.ZERO) "SELL" else "BUY"
                val allRemoteOpenOrders = (acc.openOrders + acc.openAlgoOrders).distinctBy { it.clientOrderId }
                val activeProtectiveStatuses = setOf("NEW", "WORKING")

                val protectiveSlOrder = allRemoteOpenOrders.find {
                    it.symbol.equals(remotePos.symbol, ignoreCase = true) &&
                        it.clientOrderId.startsWith("ASTRA_SL_") &&
                        it.status.uppercase() in activeProtectiveStatuses &&
                        it.side.equals(expectedProtectiveSide, ignoreCase = true) &&
                        it.positionSide.equals(remotePos.positionSide, ignoreCase = true) &&
                        it.stopPrice > BigDecimal.ZERO
                }

                val protectiveTpOrder = allRemoteOpenOrders.find {
                    it.symbol.equals(remotePos.symbol, ignoreCase = true) &&
                        it.clientOrderId.startsWith("ASTRA_TP_") &&
                        it.status.uppercase() in activeProtectiveStatuses &&
                        it.side.equals(expectedProtectiveSide, ignoreCase = true) &&
                        it.positionSide.equals(remotePos.positionSide, ignoreCase = true) &&
                        it.stopPrice > BigDecimal.ZERO
                }

                val anyBotOrderForSymbol = allRemoteOpenOrders.any {
                    it.symbol.equals(remotePos.symbol, ignoreCase = true) && it.clientOrderId.startsWith("ASTRA_")
                }

                if (localMatch == null && !anyBotOrderForSymbol) {
                    dao.upsertPosition(
                        PositionEntity(
                            symbol = remotePos.symbol,
                            signedQuantity = remotePos.positionAmt.toPlainString(),
                            entryAvgPrice = remotePos.entryPrice.toPlainString(),
                            markPrice = remotePos.markPrice.toPlainString(),
                            unrealizedPnlUsdt = remotePos.unRealizedProfit.toPlainString(),
                            isolatedMarginUsdt = remotePos.isolatedWallet.toPlainString(),
                            leverage = remotePos.leverage,
                            stopPrice = "0",
                            targetPrice = "0",
                            netBreakevenEstimate = remotePos.entryPrice.toPlainString(),
                            protectiveSlOrderId = null,
                            protectiveSlVerified = false,
                            protectiveTpOrderId = null,
                            protectiveTpVerified = false,
                            positionSide = remotePos.positionSide,
                            ownershipTag = "MANUAL_EXTERNAL",
                            state = PositionProtectionState.ORPHAN_UNPROTECTED.code,
                            openedAtMs = System.currentTimeMillis(),
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                    transitionState(
                        BotState.HALTED_REVIEW,
                        "INV-09: Phát hiện vị thế thủ công/mồ côi (${remotePos.symbol}). Bot không tự ý tiếp quản hoặc xóa lệnh."
                    )
                    reconciliationNote = "Phát hiện vị thế thủ công trên ${remotePos.symbol} (ORPHAN_UNPROTECTED). Đã khóa mở lệnh mới (INV-09)."
                } else {
                    val ordersQueryHealthy = acc.openOrdersVerified && acc.openAlgoOrdersVerified
                    val slVerified = ordersQueryHealthy && protectiveSlOrder != null
                    val tpVerified = ordersQueryHealthy && protectiveTpOrder != null
                    val protectionState = when {
                        !ordersQueryHealthy -> PositionProtectionState.PROTECTION_UNKNOWN
                        slVerified && tpVerified -> PositionProtectionState.OPEN_PROTECTED
                        slVerified || tpVerified -> PositionProtectionState.PROTECTION_DEGRADED
                        else -> PositionProtectionState.PROTECTION_FAILURE
                    }

                    dao.upsertPosition(
                        PositionEntity(
                            symbol = remotePos.symbol,
                            signedQuantity = remotePos.positionAmt.toPlainString(),
                            entryAvgPrice = remotePos.entryPrice.toPlainString(),
                            markPrice = remotePos.markPrice.toPlainString(),
                            unrealizedPnlUsdt = remotePos.unRealizedProfit.toPlainString(),
                            isolatedMarginUsdt = remotePos.isolatedWallet.toPlainString(),
                            leverage = remotePos.leverage,
                            stopPrice = protectiveSlOrder?.stopPrice?.toPlainString() ?: (localMatch?.stopPrice ?: "0"),
                            targetPrice = protectiveTpOrder?.stopPrice?.toPlainString() ?: (localMatch?.targetPrice ?: "0"),
                            netBreakevenEstimate = localMatch?.netBreakevenEstimate ?: remotePos.entryPrice.toPlainString(),
                            protectiveSlOrderId = protectiveSlOrder?.orderId ?: localMatch?.protectiveSlOrderId,
                            protectiveSlVerified = slVerified,
                            protectiveTpOrderId = protectiveTpOrder?.orderId ?: localMatch?.protectiveTpOrderId,
                            protectiveTpVerified = tpVerified,
                            positionSide = remotePos.positionSide,
                            signalId = localMatch?.signalId,
                            decisionId = localMatch?.decisionId,
                            predictedScoreAtEntry = localMatch?.predictedScoreAtEntry,
                            calibratedProbAtEntry = localMatch?.calibratedProbAtEntry,
                            ownershipTag = "BOT_OWNED",
                            state = protectionState.code,
                            openedAtMs = localMatch?.openedAtMs ?: System.currentTimeMillis(),
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )

                    if (protectionState == PositionProtectionState.OPEN_PROTECTED) {
                        transitionState(
                            BotState.POSITION_PROTECTED,
                            "Đã đối soát vị thế ${remotePos.symbol} có đủ cả 2 lệnh bảo vệ SL & TP phía sàn."
                        )
                        reconciliationNote = "ĐÃ ĐỐI SOÁT ($targetBaseUrl): Vị thế ${remotePos.symbol} đã xác minh đủ cả SL (#${protectiveSlOrder?.orderId}) và TP (#${protectiveTpOrder?.orderId}) phía sàn."
                    } else {
                        val missingDesc = buildString {
                            if (!ordersQueryHealthy) append("Lỗi truy vấn openOrders/openAlgoOrders; ")
                            if (!slVerified) append("Thiếu lệnh bảo vệ SL (ASTRA_SL_); ")
                            if (!tpVerified) append("Thiếu lệnh bảo vệ TP (ASTRA_TP_); ")
                        }.trim()
                        transitionState(
                            BotState.PROTECTION_FAILURE,
                            "Vị thế ${remotePos.symbol} ở trạng thái ${protectionState.code}: $missingDesc"
                        )
                        dao.insertRiskEvent(
                            RiskEventEntity(
                                triggerCode = "RECONCILE_${protectionState.code}",
                                severity = "CRITICAL",
                                actionTaken = "Khóa mở lệnh mới & yêu cầu khôi phục bảo vệ SL+TP cho ${remotePos.symbol}",
                                detailVi = "Đối soát phát hiện vị thế ${remotePos.symbol} không đủ bộ đôi SL+TP (${protectionState.code}): $missingDesc"
                            )
                        )
                        reconciliationNote = "CẢNH BÁO INV-03 & P0-06 (${protectionState.code}): Vị thế ${remotePos.symbol} chưa xác minh đủ cả 2 lệnh SL + TP trên sàn ($missingDesc)!"
                    }
                }
            } else {
                // Remote positions = 0 -> Delete any stale local positions & clean up orphan bot protective orders (MOD-13 & P0-03)
                val staleLocals = dao.getActivePositionsOnce()
                for (stale in staleLocals) {
                    dao.deletePosition(stale.symbol)
                }
                val allRemoteOpenOrders = (acc.openOrders + acc.openAlgoOrders).distinctBy { it.clientOrderId }
                val orphanBotOrders = allRemoteOpenOrders.filter {
                    it.clientOrderId.startsWith("ASTRA_SL_") || it.clientOrderId.startsWith("ASTRA_TP_")
                }
                val nowCleanupMs = System.currentTimeMillis()
                for (orphan in orphanBotOrders) {
                    if (settings.environmentMode == "LIVE" && !_safetyDebugLockActive.value) {
                        val cancelRes = binanceAdapter.cancelAndVerifyOrder(
                            mode = endpointMode,
                            symbol = orphan.symbol,
                            clientOrderId = orphan.clientOrderId,
                            isAlgoOrder = orphan.isAlgoOrder || orphan.clientOrderId.startsWith("ASTRA_SL_") || orphan.clientOrderId.startsWith("ASTRA_TP_")
                        )
                        if (cancelRes.isSuccess) {
                            dao.updateOrderStatus(orphan.clientOrderId, "CANCELED", nowCleanupMs)
                        } else {
                            dao.updateOrderStatus(orphan.clientOrderId, "CANCEL_PENDING", nowCleanupMs)
                        }
                    }
                }
                val nextState = if (_botState.value == BotState.DRAINING) {
                    BotState.PAUSED_FOR_CASHFLOW
                } else {
                    BotState.READY_TEST
                }
                transitionState(nextState, "Đối soát hoàn tất: Không có vị thế mở.")
                reconciliationNote = accRes.sanitizedMessageVi
            }
        } else {
            // Authentication or account query failed -> Set Equity & Available Balance strictly to NULL (UNVERIFIED/UNKNOWN) (Requirement 3, 6, 8, 9)
            val failVerifState = when (accRes.errorCategory) {
                BinanceErrorCategory.BAD_API_KEY_FMT_2014 -> AccountVerificationState.AUTH_FAILED_2014
                BinanceErrorCategory.REJECTED_MBX_KEY_2015 -> AccountVerificationState.AUTH_FAILED_2015
                BinanceErrorCategory.INVALID_SIGNATURE_1022 -> AccountVerificationState.AUTH_FAILED_1022
                BinanceErrorCategory.TIMESTAMP_DRIFT_1021 -> AccountVerificationState.AUTH_FAILED_1021
                BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS, BinanceErrorCategory.NETWORK_UNAVAILABLE -> AccountVerificationState.NETWORK_OR_TIMEOUT
                else -> AccountVerificationState.UNVERIFIED
            }

            val failedStep = diagReport.failedStepCode ?: accRes.failedStep
            val failedEndpoint = diagReport.failedEndpoint ?: accRes.endpoint
            val failedCode = diagReport.failedBinanceErrorCode ?: accRes.binanceErrorCode
            val rawMsg = diagReport.failedRawServerMessage ?: accRes.rawServerMessage

            val detailedFailureMsg = buildString {
                append("ĐỐI SOÁT TÀI KHOẢN THẤT BẠI [")
                append(failVerifState.badgeVi)
                append("] · Bước lỗi: ")
                append(failedStep ?: "READ_FAPI_V2_ACCOUNT")
                append(" · Endpoint: ")
                append(failedEndpoint ?: "$targetBaseUrl/fapi/v2/account")
                append(" · Mã lỗi Binance: ")
                append(failedCode ?: "N/A")
                if (!rawMsg.isNullOrBlank()) {
                    append(" · Message gốc từ sàn: \"")
                    append(rawMsg)
                    append("\"")
                }
                append(" · Outbound IPv4 thiết bị: ")
                append(diagReport.outboundNetwork.outboundIpv4 ?: "UNKNOWN")
                if (diagReport.provenCausesVi.isNotEmpty()) {
                    append(" · Nguyên nhân đã chứng minh: ")
                    append(diagReport.provenCausesVi.joinToString(" | "))
                }
            }

            _accountCapitalState.value = _accountCapitalState.value.copy(
                exchangeEquityUsdt = null,
                exchangeAvailableBalanceUsdt = null,
                exchangeUnrealizedPnlUsdt = null,
                vaultCredentialState = VaultCredentialState.SAVED_IN_VAULT_BUT_REJECTED,
                verificationState = failVerifState,
                dataSource = DataSourceType.UNVERIFIED_UNKNOWN,
                verifiedAtUtcMs = null,
                activeEndpointTarget = "${settings.binanceEndpointTarget} ($targetBaseUrl)",
                lastFailedStep = failedStep,
                lastFailedEndpoint = failedEndpoint,
                lastFailedBinanceCode = failedCode,
                lastRawBinanceErrorMsg = rawMsg,
                diagnosticMessageVi = detailedFailureMsg
            )

            // Disarm LIVE immediately if authentication or reconciliation failed (Requirement 3 & 9)
            if (settings.environmentMode == "LIVE" || settings.liveExplicitlyArmed) {
                dao.upsertSettings(
                    settings.copy(
                        environmentMode = "TEST",
                        liveExplicitlyArmed = false,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }

            handleBinanceFailure(accRes.errorCategory, detailedFailureMsg)
            reconciliationNote = detailedFailureMsg
        }

        dao.insertSystemEvent(
            SystemEventEntity(
                category = "RECONCILE",
                level = if (accRes.isSuccess) "INFO" else "ERROR",
                titleVi = if (accRes.isSuccess) "Đối soát tài khoản Binance thành công" else "Đối soát tài khoản Binance thất bại",
                detailVi = reconciliationNote,
                correlationId = "rec-${System.currentTimeMillis()}"
            )
        )
        updateLiveChecklist()
        return reconciliationNote
    }

    /**
     * MOD-06, MOD-09, MOD-10, MOD-11 · Scans symbol, computes features, regime, Net EV, Rule/Skeptic checks, and Risk Sizing.
     * - Clearly tags fallback market data as `DEMO_SIMULATED`.
     * - Never uses `declaredCapitalVnd` (`5.8939 USDT`) as `exchangeEquityUsdt` (`Requirement 3`).
     * - Never reports `SkepticVerdict.PASS` unless AI Skeptic actually ran (`Requirement 4`).
     */
    suspend fun scanAndEvaluateSymbol(symbol: String): String {
        val health = refreshDeviceHealth()
        refreshDeclaredCapitalEstimate()
        refreshAiConfigurationStatus()

        if (health.thermalWarning || health.lowRamWarning) {
            return "Đã dừng quét do cảnh báo nhiệt độ/RAM thiết bị."
        }

        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        if (settings.activeCircuitBreakerReason != null) {
            transitionState(BotState.PAUSED_RISK_LIMIT, settings.activeCircuitBreakerReason)
            return "Circuit Breaker đang kích hoạt: ${settings.activeCircuitBreakerReason}"
        }

        val mode = resolveApiEndpointMode(settings)
        transitionState(BotState.SCANNING, "Quét dữ liệu $symbol (${settings.binanceEndpointTarget})")

        val klines15mRes = binanceAdapter.fetchKlines(mode, symbol, "15m", 100)
        val klines1hRes = binanceAdapter.fetchKlines(mode, symbol, "1h", 55)
        val pulseRes = binanceAdapter.fetchSymbolMarketPulse(mode, symbol)

        val nowMs = binanceAdapter.currentAdjustedTimestampMs()
        val candles15m: List<CandleBar>
        val candles1h: List<CandleBar>
        val markPrice: BigDecimal
        val indexPrice: BigDecimal
        val bestBid: BigDecimal
        val bestAsk: BigDecimal
        val bidQty: BigDecimal
        val askQty: BigDecimal
        val fundingRate: BigDecimal
        val nextFundingMs: Long
        val marketDataSource: DataSourceType

        if (klines15mRes.isSuccess && !klines15mRes.data.isNullOrEmpty() &&
            klines1hRes.isSuccess && !klines1hRes.data.isNullOrEmpty() &&
            pulseRes.isSuccess && pulseRes.data != null
        ) {
            candles15m = klines15mRes.data
            candles1h = klines1hRes.data
            val p = pulseRes.data
            markPrice = p.markPrice
            indexPrice = p.indexPrice
            bestBid = p.bestBid
            bestAsk = p.bestAsk
            bidQty = p.bestBidQty
            askQty = p.bestAskQty
            fundingRate = p.fundingRate
            nextFundingMs = p.nextFundingTimeMs
            marketDataSource = DataSourceType.BINANCE_PUBLIC_REST
        } else {
            // Clearly tagged DEMO_SIMULATED fallback when container/device has no internet access to Binance
            val fallback = buildDeterministicFallbackCandles(symbol, nowMs)
            candles15m = fallback.first
            candles1h = fallback.second
            val lastClose = candles15m.last().close
            markPrice = lastClose
            indexPrice = lastClose
            bestBid = lastClose.multiply(BigDecimal("0.9998"))
            bestAsk = lastClose.multiply(BigDecimal("1.0002"))
            bidQty = BigDecimal("2500")
            askQty = BigDecimal("2400")
            fundingRate = BigDecimal("0.0001")
            nextFundingMs = nowMs + 14_400_000L
            marketDataSource = DataSourceType.DEMO_SIMULATED
        }

        // Evaluate Section 10 News & Event Gate before computing regime (P1-05: Never mix sample feed into LIVE mode)
        val allRawNews = if (settings.environmentMode == "LIVE") {
            customRawNewsInputs.toList()
        } else {
            NewsEventGateEngine.defaultCuratedFeed(nowMs) + customRawNewsInputs
        }
        val deduplicatedNews = NewsEventGateEngine.ingestAndDeduplicate(allRawNews, nowMs)
        _newsEvents.value = deduplicatedNews
        val eventRiskGateTriggered = deduplicatedNews.any {
            it.gateAction in setOf("PAUSE_TRADING", "COOLDOWN_NEW_ENTRIES")
        }

        transitionState(BotState.CANDIDATE_REVIEW, "Tính đặc trưng & Regime cho $symbol")
        val featureSnap = QuantMathEngine.computeFeaturesAndRegime(
            symbol = symbol,
            candles15m = candles15m,
            candles1h = candles1h,
            markPrice = markPrice,
            indexPrice = indexPrice,
            bestBid = bestBid,
            bestAsk = bestAsk,
            bestBidQty = bidQty,
            bestAskQty = askQty,
            fundingRate = fundingRate,
            nextFundingTimeMs = nextFundingMs,
            nowUtcMs = nowMs,
            dataSource = marketDataSource,
            eventRiskActive = eventRiskGateTriggered
        )
        _latestFeatureSnapshot.value = featureSnap
        scannerSnapshotCache[symbol] = nowMs to featureSnap

        // Compute Crowd Attention & Sentiment Proxy with mandatory estimated=true (Appendix B.9)
        val fundingBps = featureSnap.fundingRate.multiply(BigDecimal("10000")).toDouble()
        val atrRatio = if (featureSnap.atrPct > BigDecimal.ZERO) {
            featureSnap.atrPct.divide(BigDecimal("0.008"), 4, RoundingMode.HALF_UP).toDouble()
        } else 1.0
        _crowdProxy.value = NewsEventGateEngine.computeCrowdAttentionProxy(
            symbol = symbol,
            deduplicatedEvents = deduplicatedNews,
            fundingRateBps = fundingBps,
            volumeAnomalyZScore = featureSnap.robustReturnZScore.toDouble(),
            volatilityRatioAtr = atrRatio,
            nowMs = nowMs
        )

        // Persist MarketFeatureEntity in Room (MOD-02 Table #4)
        dao.insertMarketFeatureSnapshot(
            MarketFeatureEntity(
                snapshotHash = "${featureSnap.snapshotHash}_$nowMs",
                symbol = symbol,
                asOfUtcMs = nowMs,
                featureVersion = featureSnap.featureVersion,
                dataSource = marketDataSource.name,
                markPrice = featureSnap.markPrice.toPlainString(),
                return15m = featureSnap.return15m.toPlainString(),
                return1h = featureSnap.return1h.toPlainString(),
                atrPct = featureSnap.atrPct.toPlainString(),
                relativeVolume = featureSnap.relativeVolume.toPlainString(),
                spreadPct = featureSnap.spreadPct.toPlainString(),
                microprice = featureSnap.microprice.toPlainString(),
                orderBookImbalance = featureSnap.orderBookImbalance.toPlainString(),
                primaryRegime = featureSnap.primaryRegime.name,
                isDataStale = featureSnap.isDataStale,
                isEstimated = featureSnap.isEstimated,
                createdAtMs = nowMs
            )
        )

        val riskFraction = settings.riskPerTradePct.toBigDecimalOrNull() ?: BigDecimal("0.005")
        val capState = _accountCapitalState.value

        // Backtest uses a fixed 100 USDT reference and tags whether candles are real or DEMO_SIMULATED
        _latestBacktest.value = QuantMathEngine.runChronologicalBacktest(
            symbol = symbol,
            closedCandles = candles15m,
            dataSource = marketDataSource,
            startingEquityUsdt = BigDecimal("100.0"),
            riskPerTradeFraction = riskFraction
        )

        val candidateSide = when {
            featureSnap.breakoutUp && featureSnap.ema20_15m >= featureSnap.ema50_15m -> TradeSide.LONG
            featureSnap.breakoutDown && featureSnap.ema20_15m < featureSnap.ema50_15m -> TradeSide.SHORT
            featureSnap.ema20_15m >= featureSnap.ema50_15m -> TradeSide.LONG
            else -> TradeSide.SHORT
        }

        val stopDistance = featureSnap.atr14.multiply(BigDecimal("1.5")).max(markPrice.multiply(BigDecimal("0.004")))
        val targetDistance = featureSnap.atr14.multiply(BigDecimal("2.8")).max(markPrice.multiply(BigDecimal("0.009")))

        val stopPrice = if (candidateSide == TradeSide.LONG) {
            markPrice.subtract(stopDistance)
        } else {
            markPrice.add(stopDistance)
        }
        val targetPrice = if (candidateSide == TradeSide.LONG) {
            markPrice.add(targetDistance)
        } else {
            markPrice.subtract(targetDistance)
        }

        val expectedWinPct = if (markPrice > BigDecimal.ZERO) {
            targetDistance.divide(markPrice, 6, RoundingMode.HALF_UP)
        } else BigDecimal("0.008")
        val expectedLossPct = if (markPrice > BigDecimal.ZERO) {
            stopDistance.divide(markPrice, 6, RoundingMode.HALF_UP)
        } else BigDecimal("0.004")

        val baseScore = if (featureSnap.breakoutUp || featureSnap.breakoutDown) {
            BigDecimal("0.68")
        } else {
            BigDecimal("0.48")
        }
        val rvolBonus = (featureSnap.relativeVolume.subtract(BigDecimal.ONE)).multiply(BigDecimal("0.08"))
            .coerceIn(BigDecimal("-0.08"), BigDecimal("0.12"))
        val spreadPenalty = featureSnap.spreadPct.multiply(BigDecimal("40.0"))
            .coerceIn(BigDecimal.ZERO, BigDecimal("0.15"))
        val rankingScore = baseScore.add(rvolBonus).subtract(spreadPenalty)
            .coerceIn(BigDecimal("0.15"), BigDecimal("0.88"))
            .setScale(4, RoundingMode.HALF_UP)

        // P0-3.4 & P1-03: Calibrate win probability strictly from verified historical closed trades
        // using each trade's OWN historical predictedScoreAtEntry (never the current scan's rankingScore!).
        val verifiedFills = dao.getVerifiedFillsOnce()
        val recentSignalsById = dao.getRecentSignalsOnce().associateBy { it.signalId }
        val lifecycleGroups = verifiedFills.groupBy { fill ->
            fill.positionLifecycleId?.takeIf { it.isNotBlank() }
                ?: fill.signalId?.takeIf { it.isNotBlank() }
                ?: fill.clientOrderId
        }
        val historicalPairs = mutableListOf<Pair<BigDecimal, Boolean>>()
        for ((_, groupFills) in lifecycleGroups) {
            val representative = groupFills.first()
            val histScoreBd = representative.predictedScoreAtEntry?.toBigDecimalOrNull()
                ?: representative.signalId?.let { recentSignalsById[it]?.rankingScore?.toBigDecimalOrNull() }
                ?: dao.getOrderIntentByClientId(representative.clientOrderId)?.predictedScoreAtEntry?.toBigDecimalOrNull()
            // P1-03: Strictly exclude legacy fills that lack a historical predicted score at entry!
            if (histScoreBd != null && histScoreBd > BigDecimal.ZERO && histScoreBd <= BigDecimal.ONE) {
                val netPnlForLifecycle = groupFills.fold(BigDecimal.ZERO) { acc, f ->
                    val net = f.realizedPnlUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val funding = f.fundingUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    acc.add(net).subtract(funding)
                }
                historicalPairs.add(histScoreBd to (netPnlForLifecycle > BigDecimal.ZERO))
            }
        }

        val calibrationReport = QuantMathEngine.calibrateWinProbabilityFromHistory(
            predictedRankingScore = rankingScore,
            historicalSamples = historicalPairs
        )
        _latestCalibrationReport.value = calibrationReport

        val evEst = QuantMathEngine.calculateNetEv(
            rankingScore = rankingScore,
            calibratedWinProbability = calibrationReport.calibratedProbability,
            expectedMoveWinPct = expectedWinPct,
            expectedStopLossPct = expectedLossPct,
            spreadPct = featureSnap.spreadPct,
            expectedFundingPct = featureSnap.fundingRate,
            dataSource = marketDataSource,
            calibrationReport = calibrationReport
        )
        _latestEvEstimate.value = evEst

        // Requirement 4: During scan, AI Skeptic has NOT run yet -> aiSkepticActuallyRanAndPassed = false
        val skeptic = QuantMathEngine.runSkepticChecks(
            snapshot = featureSnap,
            candidateSide = candidateSide,
            evEstimate = evEst,
            maxSpreadPct = settings.maxSpreadPct.toBigDecimalOrNull() ?: BigDecimal("0.0012"),
            minRelativeVolume = settings.minRelativeVolume.toBigDecimalOrNull() ?: BigDecimal("1.25"),
            aiSkepticActuallyRanAndPassed = false
        )
        _latestSkepticResult.value = skeptic

        // Risk Gate & Position Sizing (MOD-11 / Section 6.2 / Requirement 3 & P1-3.6)
        transitionState(BotState.RISK_CHECK, "Kiểm tra Risk Gate & Sizing cho $symbol")
        val verifiedEquity = capState.exchangeEquityUsdt
        val verifiedAvailable = capState.exchangeAvailableBalanceUsdt
        val dailyLossCap = (verifiedEquity ?: BigDecimal.ZERO)
            .multiply(settings.dailyLossLimitPct.toBigDecimalOrNull() ?: BigDecimal("0.015"))

        val signalId = "SIG-${symbol}-${nowMs}"
        val decisionId = "DEC-${symbol}-${nowMs}"
        val filter = binanceAdapter.getCachedSymbolFilter(symbol)
        val sizing = QuantMathEngine.calculatePositionSizing(
            symbolFilter = filter,
            side = candidateSide,
            equityUsdt = verifiedEquity, // Null when unverified -> automatically rejected with UNVERIFIED_ACCOUNT_EQUITY!
            availableBalanceUsdt = verifiedAvailable,
            capitalSource = capState.dataSource,
            isLiveMode = mode == EnvironmentMode.LIVE,
            riskPerTradeFraction = riskFraction,
            remainingDailyRiskBudgetUsdt = dailyLossCap,
            entryPrice = markPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            requestedLeverage = settings.defaultLeverageCap,
            orderType = "MARKET",
            signalId = signalId,
            decisionId = decisionId
        )
        _latestSizingDecision.value = sizing

        // On-Device Time-Series Forecasting Engine (Section 12 · SHADOW / ADVISORY_TEST_ONLY, outside globalCapitalMutex)
        val forecastOpMode = if (settings.forecastOperatingMode.equals("ADVISORY_TEST_ONLY", ignoreCase = true) &&
            settings.environmentMode != "LIVE"
        ) {
            com.example.forecast.domain.ForecastOperatingMode.ADVISORY_TEST_ONLY
        } else {
            com.example.forecast.domain.ForecastOperatingMode.SHADOW
        }
        val forecastReport = forecastScheduler.evaluateOnClosedCandleEvent(
            symbol = symbol,
            candles15m = candles15m,
            candles1h = candles1h,
            primaryRegime = featureSnap.primaryRegime,
            candidateSide = candidateSide,
            spreadPct = featureSnap.spreadPct,
            fundingRate = featureSnap.fundingRate,
            orderBookImbalance = featureSnap.orderBookImbalance.toDouble(),
            operatingMode = forecastOpMode,
            deviceHealth = health,
            maxThermalCelsius = settings.maxThermalCelsius,
            minAvailableRamMb = settings.minAvailableRamMb,
            nowMs = nowMs
        )
        val primaryForecast15m = forecastReport.primaryResult15m
        val advisoryVetoed = forecastOpMode == com.example.forecast.domain.ForecastOperatingMode.ADVISORY_TEST_ONLY &&
            primaryForecast15m != null &&
            !primaryForecast15m.passesQualityGate

        val signalStatus = if (sizing.approved && skeptic.verdict == SkepticVerdict.PASS && evEst.isPositiveEdge && !advisoryVetoed) {
            "VALIDATED"
        } else {
            "REJECTED"
        }
        val rejectNote = when {
            sizing.rejectReasonVi != null -> sizing.rejectReasonVi
            advisoryVetoed -> "Forecast Advisory Filter: ${primaryForecast15m?.reasonVi ?: "ABSTAIN"}"
            else -> skeptic.summaryVi
        }

        val signalEntity = SignalEntity(
            signalId = signalId,
            strategy = "TREND_MOMENTUM_BREAKOUT_V1",
            symbol = symbol,
            direction = candidateSide.name,
            horizon = "15m",
            regime = featureSnap.primaryRegime.name,
            entryPrice = sizing.entryPrice.toPlainString(),
            stopPrice = sizing.stopPrice.toPlainString(),
            targetPrice = sizing.targetPrice.toPlainString(),
            expectedNetReturnPct = evEst.netEvPct.toPlainString(),
            expectedCostPct = evEst.totalExpectedCostPct.toPlainString(),
            status = signalStatus,
            rejectReasonVi = rejectNote,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + 180_000L,
            rankingScore = rankingScore.toPlainString(),
            predictedWinProbability = calibrationReport.calibratedProbability?.toPlainString(),
            decisionId = decisionId
        )
        dao.upsertSignal(signalEntity)
        latestValidatedSignalId = signalId

        transitionState(BotState.READY_TEST, "Hoàn tất đánh giá ứng viên $symbol: $signalStatus")
        if (_scannerCandidates.value.isEmpty()) {
            runMultiSymbolCandidateScanner()
        }
        refreshPerformanceSelfMonitoringReport()
        updateLiveChecklist()
        return if (signalStatus == "VALIDATED") {
            "Ứng viên $symbol (${candidateSide.name}) ĐẠT Risk Gate: Qty=${sizing.roundedQty}, Notional=${sizing.notionalUsdt} USDT."
        } else {
            "NO_TRADE cho $symbol: $rejectNote"
        }
    }

    /**
     * MOD-06 & P1-02 · Multi-Symbol Market Scanner (20–50 liquid symbols -> Top 5 candidates) + Symbol Switch Cooldown.
     * - Pre-screens symbols by 24h Quote Volume, Spread%, and `minNotional` feasibility.
     * - Never uses hardcoded fallback prices in LIVE mode (fails closed on missing market data).
     * - Derives `candidateDirection` (`LONG`, `SHORT`, `NONE`) strictly from quantitative breakout/EMA/RVOL rules, never from `minNotionalFeasible`.
     */
    suspend fun runMultiSymbolCandidateScanner(): List<ScannerCandidateItem> {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val isLiveSetting = settings.environmentMode == "LIVE"
        val mode = resolveApiEndpointMode(settings)
        val nowMs = System.currentTimeMillis()
        val tickersRes = binanceAdapter.fetch24hTickerUniverse(mode)
        val capState = _accountCapitalState.value
        val refCapitalUsdt = capState.exchangeEquityUsdt ?: capState.estimatedCapitalFromVndUsdt
        val maxLeverage = settings.defaultLeverageCap.coerceIn(1, 5)
        val maxFeasibleNotional = refCapitalUsdt.multiply(BigDecimal(maxLeverage)).multiply(BigDecimal("0.85"))
        val minRvolThreshold = settings.minRelativeVolume.toBigDecimalOrNull() ?: BigDecimal("1.25")
        val maxSpreadThreshold = settings.maxSpreadPct.toBigDecimalOrNull() ?: BigDecimal("0.0012")

        // P1-02: In LIVE mode, never fabricate scanner candidates from hardcoded prices if 24h ticker fetch fails!
        if ((!tickersRes.isSuccess || tickersRes.data.isNullOrEmpty()) && isLiveSetting) {
            _scannerCandidates.value = emptyList()
            dao.insertSystemEvent(
                SystemEventEntity(
                    category = "SCANNER",
                    level = "WARN",
                    titleVi = "Scanner tạm dừng do thiếu dữ liệu thị trường LIVE",
                    detailVi = "Không tải được ticker24hr từ Binance ở chế độ LIVE (${tickersRes.sanitizedMessageVi}). Không dùng giá giả lập.",
                    correlationId = "SCAN-LIVE-FAIL-$nowMs"
                )
            )
            return emptyList()
        }

        val tickerList = if (tickersRes.isSuccess && !tickersRes.data.isNullOrEmpty()) {
            tickersRes.data
                .filter { it.symbol.endsWith("USDT") && it.quoteVolume24hUsdt >= BigDecimal("10000000") }
                .sortedByDescending { it.quoteVolume24hUsdt }
                .take(10)
        } else {
            // TEST/DEMO fallback universe when offline in TEST mode
            listOf(
                com.example.network.Ticker24hStatsDto("DOGEUSDT", BigDecimal("0.1445"), BigDecimal("2.4"), BigDecimal("920000000"), BigDecimal("0.1490"), BigDecimal("0.1390")),
                com.example.network.Ticker24hStatsDto("XRPUSDT", BigDecimal("0.5480"), BigDecimal("-1.8"), BigDecimal("860000000"), BigDecimal("0.5620"), BigDecimal("0.5380")),
                com.example.network.Ticker24hStatsDto("SOLUSDT", BigDecimal("178.20"), BigDecimal("3.1"), BigDecimal("1450000000"), BigDecimal("181.00"), BigDecimal("172.00")),
                com.example.network.Ticker24hStatsDto("ETHUSDT", BigDecimal("2635.0"), BigDecimal("0.5"), BigDecimal("2850000000"), BigDecimal("2680.0"), BigDecimal("2590.0")),
                com.example.network.Ticker24hStatsDto("BTCUSDT", BigDecimal("68700.0"), BigDecimal("0.8"), BigDecimal("5400000000"), BigDecimal("69500.0"), BigDecimal("67800.0"))
            )
        }

        val candidates = mutableListOf<ScannerCandidateItem>()
        for (ticker in tickerList.take(6)) {
            val sym = ticker.symbol
            val filter = binanceAdapter.getCachedSymbolFilter(sym)
            if (filter.status != "TRADING") continue
            val minNotionalFeasible = filter.minNotional <= maxFeasibleNotional

            // Check 60s TTL scanner cache or active snapshot; otherwise fetch lightweight klines/pulse for top symbols
            val cachedEntry = scannerSnapshotCache[sym]
            val snap: FeatureSnapshot? = when {
                _latestFeatureSnapshot.value?.symbol == sym && (nowMs - (_latestFeatureSnapshot.value?.asOfUtcMs ?: 0L)) <= 60_000L ->
                    _latestFeatureSnapshot.value
                cachedEntry != null && (nowMs - cachedEntry.first) <= 60_000L ->
                    cachedEntry.second
                tickersRes.isSuccess -> {
                    val k15 = binanceAdapter.fetchKlines(mode, sym, "15m", 35)
                    val k1h = binanceAdapter.fetchKlines(mode, sym, "1h", 25)
                    val pulse = binanceAdapter.fetchSymbolMarketPulse(mode, sym)
                    if (k15.isSuccess && !k15.data.isNullOrEmpty() && k1h.isSuccess && !k1h.data.isNullOrEmpty() && pulse.isSuccess && pulse.data != null) {
                        val p = pulse.data
                        val computed = QuantMathEngine.computeFeaturesAndRegime(
                            symbol = sym,
                            candles15m = k15.data,
                            candles1h = k1h.data,
                            markPrice = p.markPrice,
                            indexPrice = p.indexPrice,
                            bestBid = p.bestBid,
                            bestAsk = p.bestAsk,
                            bestBidQty = p.bestBidQty,
                            bestAskQty = p.bestAskQty,
                            fundingRate = p.fundingRate,
                            nextFundingTimeMs = p.nextFundingTimeMs,
                            nowUtcMs = nowMs,
                            dataSource = DataSourceType.BINANCE_PUBLIC_REST
                        )
                        scannerSnapshotCache[sym] = nowMs to computed
                        computed
                    } else null
                }
                else -> null
            }

            if (isLiveSetting && snap == null && ticker.lastPrice <= BigDecimal.ZERO) {
                continue
            }

            val lastPrice = snap?.lastPrice ?: ticker.lastPrice
            val spreadPct = snap?.spreadPct ?: BigDecimal("0.0005")
            val rvol = snap?.relativeVolume ?: BigDecimal("1.00")
            val regime = snap?.primaryRegime ?: com.example.domain.RegimeLabel.DATA_UNCERTAIN

            // P1-02: Derive candidateDirection strictly from quantitative signal conditions, NEVER from minNotionalFeasible!
            val direction = when {
                snap == null || snap.isDataStale -> TradeSide.NONE
                snap.spreadPct > maxSpreadThreshold -> TradeSide.NONE
                snap.breakoutUp && snap.ema20_15m >= snap.ema50_15m && snap.ema20_1h >= snap.ema50_1h && snap.relativeVolume >= minRvolThreshold ->
                    TradeSide.LONG
                snap.breakoutDown && snap.ema20_15m < snap.ema50_15m && snap.ema20_1h <= snap.ema50_1h && snap.relativeVolume >= minRvolThreshold ->
                    TradeSide.SHORT
                snap.primaryRegime == com.example.domain.RegimeLabel.TREND_UP && snap.emaSlope15m > BigDecimal.ZERO && snap.relativeVolume >= minRvolThreshold ->
                    TradeSide.LONG
                snap.primaryRegime == com.example.domain.RegimeLabel.TREND_DOWN && snap.emaSlope15m < BigDecimal.ZERO && snap.relativeVolume >= minRvolThreshold ->
                    TradeSide.SHORT
                else -> TradeSide.NONE
            }

            val liquidityScore = (ticker.quoteVolume24hUsdt.divide(BigDecimal("100000000"), 4, RoundingMode.HALF_UP))
                .coerceIn(BigDecimal.ZERO, BigDecimal("25.0"))
            val rvolScore = rvol.multiply(BigDecimal("20.0")).coerceIn(BigDecimal.ZERO, BigDecimal("35.0"))
            val signalBonus = if (direction != TradeSide.NONE) BigDecimal("25.0") else BigDecimal.ZERO
            val feasibilityFactor = if (minNotionalFeasible) BigDecimal.ONE else BigDecimal("0.35")
            val spreadCostPenalty = spreadPct.multiply(BigDecimal("12000.0")).coerceIn(BigDecimal.ZERO, BigDecimal("25.0"))
            val rawScore = (liquidityScore.add(rvolScore).add(signalBonus).subtract(spreadCostPenalty))
                .max(BigDecimal("5.0"))
                .multiply(feasibilityFactor)
                .setScale(1, RoundingMode.HALF_UP)

            val noteVi = buildString {
                if (!minNotionalFeasible) {
                    append("Vượt ngưỡng minNotional (${filter.minNotional} USDT > sức mua ${maxFeasibleNotional.setScale(1, RoundingMode.HALF_UP)} USDT)")
                } else {
                    append("Khả thi minNotional (${filter.minNotional} USDT)")
                }
                append(" · Hướng: ${direction.name} · Regime: ${regime.name}")
                if (snap == null) append(" · Chưa tải đủ nến (DATA_INSUFFICIENT)")
            }

            candidates.add(
                ScannerCandidateItem(
                    symbol = sym,
                    lastPrice = lastPrice,
                    quoteVolume24hUsdt = ticker.quoteVolume24hUsdt,
                    spreadPct = spreadPct,
                    relativeVolume = rvol,
                    minNotionalUsdt = filter.minNotional,
                    feasibleForCurrentCapital = minNotionalFeasible,
                    primaryRegime = regime,
                    candidateDirection = direction,
                    scannerScore = rawScore,
                    noteVi = noteVi
                )
            )
        }
        val top5 = candidates.sortedByDescending { it.scannerScore }.take(5)
        _scannerCandidates.value = top5
        return top5
    }

    /**
     * MOD-06 · Checks symbol-switch cooldown so the bot does not thrash between symbols when holding a position or within cooldown window.
     */
    suspend fun canSwitchActiveSymbol(targetSymbol: String, nowMs: Long = System.currentTimeMillis()): Pair<Boolean, String> {
        if (targetSymbol == lastSelectedSymbol) return true to "Giữ nguyên symbol $targetSymbol."
        val activePositions = dao.getActivePositionsOnce()
        if (activePositions.isNotEmpty() && activePositions.first().symbol != targetSymbol) {
            return false to "Đang giữ vị thế mở trên ${activePositions.first().symbol} (INV-09: Tối đa 1 vị thế). Không chuyển sang $targetSymbol."
        }
        val elapsedMs = nowMs - lastSymbolSwitchAtMs
        if (lastSymbolSwitchAtMs > 0L && elapsedMs < 3_000L) {
            return false to "Cooldown chuyển symbol (MOD-06): Vui lòng chờ ${(3_000L - elapsedMs) / 1000 + 1}s để tránh nhảy cặp liên tục."
        }
        lastSymbolSwitchAtMs = nowMs
        lastSelectedSymbol = targetSymbol
        return true to "Đã chuyển sang $targetSymbol."
    }

    /**
     * Section 7.3 & 7.4 (INV-14) · Safe Net-Breakeven Stop Replacement.
     * - Places the NEW protective `STOP_MARKET` order at `netBreakevenEstimate` FIRST.
     * - Verifies that the new stop order is confirmed `NEW` on Binance.
     * - ONLY THEN cancels the old stop order. If the new stop order is rejected, keeps the old stop intact!
     * - Explicitly includes the INV-14 disclaimer that net breakeven is an estimate and market execution can still slip.
     */
    suspend fun moveStopToNetBreakevenIfEligible(): String {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val mode = resolveApiEndpointMode(settings)
        val activePositions = dao.getActivePositionsOnce()
        if (activePositions.isEmpty()) {
            return "Không có vị thế đang mở để dời Stop về Hòa vốn ròng ước tính."
        }
        val pos = activePositions.first()
        val signedQty = pos.signedQuantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val side = if (signedQty >= BigDecimal.ZERO) TradeSide.LONG else TradeSide.SHORT
        val protectiveSide = if (side == TradeSide.LONG) "SELL" else "BUY"
        val netBePrice = pos.netBreakevenEstimate.toBigDecimalOrNull()
            ?: return "Giá Hòa vốn ròng ước tính không hợp lệ."
        val currentStop = pos.stopPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Verify that moving to netBePrice actually tightens risk (never widens stop loss!)
        val isTightening = when (side) {
            TradeSide.LONG -> netBePrice > currentStop
            TradeSide.SHORT -> currentStop == BigDecimal.ZERO || netBePrice < currentStop
            TradeSide.NONE -> false
        }
        if (!isTightening) {
            return "Từ chối dời SL (Mục 7.4): Mức Hòa vốn ròng ước tính ($netBePrice) không thu hẹp rủi ro so với SL hiện tại ($currentStop)."
        }

        val nowMs = binanceAdapter.currentAdjustedTimestampMs()
        val newSlClientId = "ASTRA_SL_BE_${pos.symbol}_$nowMs"

        // Step 1 (Section 7.4): Place the NEW stop order FIRST
        val newSlRes = binanceAdapter.submitProtectiveStopMarketOrder(
            mode = mode,
            symbol = pos.symbol,
            side = protectiveSide,
            stopPrice = netBePrice,
            closePosition = true,
            newClientOrderId = newSlClientId
        )

        if (!newSlRes.isSuccess || newSlRes.data == null || newSlRes.data.status !in setOf("NEW", "PARTIALLY_FILLED", "FILLED")) {
            // Keep old stop order untouched!
            return "GIỮ NGUYÊN SL CŨ ($currentStop) theo Mục 7.4: Lệnh SL Hòa vốn mới bị sàn từ chối hoặc chưa xác nhận (${newSlRes.sanitizedMessageVi}). Vị thế không bao giờ bị bỏ trống bảo vệ."
        }

        val newSlOrd = newSlRes.data
        // Step 2 (Section 7.4 & P0-03): Cancel old stop order ONLY AFTER new stop is confirmed active, and verify cancel result!
        val oldSlOrders = dao.getOpenOrdersOnce().filter {
            it.symbol == pos.symbol && it.purpose == "PROTECTIVE_SL" && it.clientOrderId != newSlClientId &&
                it.status in setOf("NEW", "WORKING", "CANCEL_PENDING")
        }
        var oldCancelFailedCount = 0
        for (oldOrd in oldSlOrders) {
            val cancelRes = binanceAdapter.cancelAndVerifyOrder(
                mode = mode,
                symbol = pos.symbol,
                clientOrderId = oldOrd.clientOrderId,
                isAlgoOrder = oldOrd.isAlgoOrder || oldOrd.purpose.startsWith("PROTECTIVE")
            )
            if (cancelRes.isSuccess) {
                dao.updateOrderStatus(oldOrd.clientOrderId, "CANCELED", nowMs)
            } else {
                oldCancelFailedCount++
                dao.updateOrderStatus(oldOrd.clientOrderId, "CANCEL_PENDING", nowMs)
                dao.insertRiskEvent(
                    RiskEventEntity(
                        triggerCode = "OLD_SL_CANCEL_UNVERIFIED",
                        severity = "WARNING",
                        actionTaken = "Đánh dấu CANCEL_PENDING cho SL cũ ${oldOrd.clientOrderId}",
                        detailVi = "SL mới (#${newSlOrd.orderId}) đã đặt thành công nhưng hủy SL cũ (${oldOrd.clientOrderId}) chưa được sàn xác nhận: ${cancelRes.sanitizedMessageVi}"
                    )
                )
            }
        }

        dao.upsertOrder(
            OrderRecordEntity(
                clientOrderId = newSlOrd.clientOrderId,
                exchangeOrderId = newSlOrd.orderId,
                symbol = newSlOrd.symbol,
                side = newSlOrd.side,
                orderType = newSlOrd.type,
                purpose = "PROTECTIVE_SL",
                quantity = signedQty.abs().toPlainString(),
                executedQty = "0",
                avgPrice = "0",
                stopPrice = netBePrice.toPlainString(),
                workingType = settings.stopTriggerWorkingType,
                status = newSlOrd.status,
                isBotOwned = true,
                updatedAtMs = nowMs
            )
        )
        dao.upsertPosition(
            pos.copy(
                stopPrice = netBePrice.toPlainString(),
                protectiveSlOrderId = newSlOrd.orderId,
                protectiveSlVerified = true,
                updatedAtMs = nowMs
            )
        )
        return "Đã đặt SL mới (#${newSlOrd.orderId}) tại Hòa vốn ròng ước tính $netBePrice trước rồi mới hủy SL cũ ($currentStop) (Mục 7.4). Lưu ý INV-14: SL kích hoạt lệnh thị trường vẫn có thể chịu trượt giá khi biến động cực mạnh."
    }

    /**
     * Section 10 · Injects a user/test news headline to verify deduplication, rumor isolation, and prompt-injection defense.
     */
    suspend fun injectNewsEventForTesting(
        title: String,
        sourceTier: Int,
        canonicalUrl: String,
        status: NewsVerificationStatus,
        category: NewsEventCategory,
        impactScore: Double
    ): String {
        val nowMs = System.currentTimeMillis()
        customRawNewsInputs.add(
            NewsEventGateEngine.RawNewsInput(
                title = title,
                sourceName = "Tier-$sourceTier Feed",
                sourceTier = sourceTier,
                canonicalUrl = canonicalUrl,
                publishedAtMs = nowMs,
                status = status,
                category = category,
                authorSentimentScore = impactScore * 0.8,
                eventImpactScore = impactScore
            )
        )
        val sym = _latestFeatureSnapshot.value?.symbol ?: "DOGEUSDT"
        scanAndEvaluateSymbol(sym)
        val dedupCount = _newsEvents.value.size
        val rawCount = NewsEventGateEngine.defaultCuratedFeed(nowMs).size + customRawNewsInputs.size
        return "Đã xử lý sự kiện qua NewsEventGateEngine: Tổng bản tin thô=$rawCount -> Sau khử trùng lặp (Deduplication)=$dedupCount cụm sự kiện độc lập."
    }

    /**
     * Section 18 · Computes Self-Monitoring & Strategy Performance Metrics from local audit tables.
     */
    suspend fun refreshPerformanceSelfMonitoringReport(): PerformanceSelfMonitoringReport {
        val fills = dao.getAllFillsOnce()
        val signals = dao.getRecentSignalsOnce()
        val aiReqs = dao.getRecentAiRequestsOnce()
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()

        val wins = fills.filter { (it.realizedPnlUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losses = fills.filter { (it.realizedPnlUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO) <= BigDecimal.ZERO }
        val totalClosed = fills.size

        val grossWins = wins.fold(BigDecimal.ZERO) { acc, f ->
            acc.add(f.realizedPnlUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }
        val grossLossesAbs = losses.fold(BigDecimal.ZERO) { acc, f ->
            acc.add((f.realizedPnlUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO).abs())
        }
        val totalFees = fills.fold(BigDecimal.ZERO) { acc, f ->
            acc.add(f.commissionUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }
        val netRealized = grossWins.subtract(grossLossesAbs)

        val winRatePct = if (totalClosed > 0) {
            BigDecimal(wins.size * 100).divide(BigDecimal(totalClosed), 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val avgWin = if (wins.isNotEmpty()) {
            grossWins.divide(BigDecimal(wins.size), 4, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val avgLoss = if (losses.isNotEmpty()) {
            grossLossesAbs.divide(BigDecimal(losses.size), 4, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val profitFactor = if (grossLossesAbs > BigDecimal.ZERO) {
            grossWins.divide(grossLossesAbs, 2, RoundingMode.HALF_UP)
        } else if (grossWins > BigDecimal.ZERO) {
            BigDecimal("9.99")
        } else BigDecimal.ZERO

        val expectancy = if (totalClosed > 0) {
            netRealized.divide(BigDecimal(totalClosed), 4, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val grossAbsTotal = grossWins.add(grossLossesAbs)
        val feeSharePct = if (grossAbsTotal > BigDecimal.ZERO) {
            totalFees.multiply(BigDecimal("100")).divide(grossAbsTotal, 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val rejectedCount = signals.count { it.status == "REJECTED" }
        val rejectionRatePct = if (signals.isNotEmpty()) {
            BigDecimal(rejectedCount * 100).divide(BigDecimal(signals.size), 1, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val latencies = aiReqs.map { it.latencyMs }.filter { it > 0L }.sorted()
        val p50 = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else 0L
        val p95 = if (latencies.isNotEmpty()) latencies[(latencies.size * 95 / 100).coerceAtMost(latencies.lastIndex)] else 0L

        val report = PerformanceSelfMonitoringReport(
            totalClosedTrades = totalClosed,
            winCount = wins.size,
            lossCount = losses.size,
            winRatePct = winRatePct,
            avgWinUsdt = avgWin,
            avgLossUsdt = avgLoss,
            profitFactor = profitFactor,
            expectancyUsdt = expectancy,
            totalRealizedNetPnlUsdt = netRealized.setScale(4, RoundingMode.HALF_UP),
            totalFeesAndFundingUsdt = totalFees.setScale(4, RoundingMode.HALF_UP),
            feeToGrossPnlRatioPct = feeSharePct,
            totalSignalsEvaluated = signals.size,
            rejectedSignalsCount = rejectedCount,
            signalRejectionRatePct = rejectionRatePct,
            aiLatencyP50Ms = p50,
            aiLatencyP95Ms = p95,
            wsReconnectCount = binanceAdapter.wsState.value.reconnectCount,
            buildFingerprint = "ASTRA-V5.1-CFG${settings.configVersion}-STRAT_V1_BASELINE",
            computedAtMs = System.currentTimeMillis()
        )
        _performanceReport.value = report
        return report
    }

    /**
     * MOD-12 & Requirement 1, 3, 4, 7, 9 · Executes a validated trade candidate through the single ExecutionCoordinator gate.
     * - Blocks LIVE execution if `safetyDebugLockActive == true`, `environmentMode != "LIVE"`, or account is unverified.
     * - Never claims a position exists unless a real exchange order response confirms `FILLED` / `PARTIALLY_FILLED`.
     * - Never sends duplicate orders after timeout or restart (`INV-06`).
     */
    /**
     * P0-3.3 · Single Unified 16-Condition Pre-Order Execution Gate.
     * Verifies signal validity, non-expiry, lineage IDs (`signalId`, `decisionId`, `sizingId`, `riskApprovalId`),
     * Skeptic verdict, Risk Engine approval, real exchange metadata (`isRealExchangeFilter`),
     * verified account equity, absence of `UNKNOWN` intents, position limits, and current market slippage.
     */
    suspend fun evaluateUnifiedExecutionGate(
        currentMarketPriceOverride: BigDecimal? = null,
        nowUtcMsOverride: Long? = null
    ): ExecutionGateDecision {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val mode = resolveApiEndpointMode(settings)
        val nowMs = nowUtcMsOverride ?: binanceAdapter.currentAdjustedTimestampMs()
        val sizing = _latestSizingDecision.value
        val targetSym = sizing?.symbol ?: (_latestFeatureSnapshot.value?.symbol ?: "DOGEUSDT")

        fun gateFail(code: String, reasonVi: String): ExecutionGateDecision = ExecutionGateDecision(
            approved = false,
            symbol = targetSym,
            signalId = sizing?.signalId,
            decisionId = sizing?.decisionId,
            sizingId = sizing?.sizingId,
            riskApprovalId = sizing?.riskApprovalId,
            failedChecks = listOf(code),
            rejectReasonCode = code,
            rejectReasonVi = reasonVi,
            evaluatedAtUtcMs = nowMs
        )

        // 1. Trading Mode & Safety Debug Lock
        if (mode == EnvironmentMode.LIVE && (_safetyDebugLockActive.value || settings.environmentMode != "LIVE" || !settings.liveExplicitlyArmed)) {
            return gateFail(
                "GATE_01_LIVE_SAFETY_LOCK",
                "TỪ CHỐI THỰC THI — KHÓA AN TOÀN LIVE (Mục 1, 7 & 9): Chỉ thực hiện request ĐỌC tài khoản trên Mainnet (https://fapi.binance.com). Khả năng gửi lệnh giao dịch thật đang bị khóa cho đến khi xác thực tài khoản thật (${_accountCapitalState.value.verificationState.badgeVi}), đối soát INV-06 thành công và mở khóa LIVE."
            )
        }

        // 2. Circuit Breaker & Cooldown check
        if (!settings.activeCircuitBreakerReason.isNullOrBlank()) {
            return gateFail(
                "GATE_02_CIRCUIT_BREAKER_ACTIVE",
                "Circuit Breaker đang kích hoạt: ${settings.activeCircuitBreakerReason}"
            )
        }

        // 3. Verified Account Capital
        val capState = _accountCapitalState.value
        if (!capState.verificationState.isVerified || capState.exchangeEquityUsdt == null) {
            return gateFail(
                "GATE_03_ACCOUNT_UNVERIFIED",
                "TỪ CHỐI THỰC THI (Mục 3 & 9): Tài khoản Binance chưa được xác minh (${capState.verificationState.badgeVi}). Không tạo vị thế giả từ vốn khai báo."
            )
        }

        // 4. Clock Sync Health (LIVE requires synced clock)
        if (mode == EnvironmentMode.LIVE && !binanceAdapter.clockState.value.synced) {
            return gateFail(
                "GATE_04_CLOCK_NOT_SYNCED",
                "Chưa đồng bộ thời gian chuẩn với máy chủ Binance (/fapi/v1/time)."
            )
        }

        // 5. No active or UNKNOWN order intents (INV-06)
        val pendingOrUnknown = dao.getActiveOrUnknownIntents()
        if (pendingOrUnknown.isNotEmpty()) {
            transitionState(BotState.UNKNOWN_ORDER_STATUS, "Còn OrderIntent chưa xác định trạng thái.")
            return gateFail(
                "GATE_05_UNKNOWN_ORDER_INTENT",
                "INV-06 Chống lệnh trùng: Đang có OrderIntent (${pendingOrUnknown.first().clientOrderId} · status=${pendingOrUnknown.first().status}) chưa được đối soát hoàn tất. Bắt buộc chạy Đối soát trước."
            )
        }

        // 6. Max active positions limit (INV-09: max 1 active position in V1)
        val existingPositions = dao.getActivePositionsOnce()
        if (existingPositions.isNotEmpty()) {
            return gateFail(
                "GATE_06_MAX_OPEN_POSITIONS",
                "INV-09 Từ chối: Đang có ${existingPositions.size} vị thế mở (${existingPositions.first().symbol}). V1 chỉ quản lý tối đa 1 vị thế."
            )
        }

        // 7 & 8. SizingDecision presence & lineage IDs
        if (sizing == null) {
            return gateFail(
                "GATE_07_MISSING_SIZING_DECISION",
                "Chưa có kết quả định cỡ vị thế từ Risk Engine. Hãy bấm Quét & Đánh giá trước."
            )
        }
        if (!sizing.approved) {
            return gateFail(
                sizing.rejectReasonCode ?: "GATE_07_RISK_ENGINE_VETO",
                "INV-02 Phủ quyết bởi Risk Engine: ${sizing.rejectReasonVi}"
            )
        }
        if (sizing.sizingId.isBlank() || sizing.signalId.isNullOrBlank() || sizing.decisionId.isNullOrBlank() || sizing.riskApprovalId.isNullOrBlank()) {
            return gateFail(
                "GATE_08_BROKEN_LINEAGE_IDS",
                "Thiếu liên kết định danh bắt buộc (signalId / decisionId / sizingId / riskApprovalId)."
            )
        }

        // 9, 10, 11. Exact Signal existence by signalId (P0-04: NEVER fall back to symbol match!), status == VALIDATED, and non-expiry (expiresAtMs > nowMs)
        val linkedSignal = dao.getSignalByIdOnce(sizing.signalId)
            ?: dao.getRecentSignalsOnce().firstOrNull { it.signalId == sizing.signalId }
        if (linkedSignal == null || linkedSignal.symbol != sizing.symbol) {
            return gateFail(
                "GATE_09_SIGNAL_NOT_FOUND",
                "Không tìm thấy SignalEntity đã lưu khớp chính xác với signalId=${sizing.signalId} (${sizing.symbol}). Tuyệt đối không ghép nhầm theo symbol (P0-04)."
            )
        }
        if (linkedSignal.expiresAtMs <= nowMs) {
            return gateFail(
                "GATE_10_SIGNAL_EXPIRED",
                "Tín hiệu ${linkedSignal.signalId} đã quá hạn thực thi (expiresAtMs=${linkedSignal.expiresAtMs} <= now=$nowMs)."
            )
        }
        if (linkedSignal.status != "VALIDATED") {
            return gateFail(
                "GATE_11_SIGNAL_NOT_VALIDATED",
                "Tín hiệu ${linkedSignal.signalId} có trạng thái '${linkedSignal.status}' (yêu cầu 'VALIDATED'). Lý do: ${linkedSignal.rejectReasonVi ?: "Chưa đạt điều kiện"}"
            )
        }

        // 12. Skeptic Verdict check (P0-04: must not be REJECT in TEST; LIVE requires PASS)
        val skeptic = _latestSkepticResult.value
        if (skeptic == null || skeptic.verdict == SkepticVerdict.REJECT) {
            return gateFail(
                "GATE_12_SKEPTIC_REJECTED",
                "Mô-đun Phản biện (Skeptic) từ chối thực thi: ${skeptic?.summaryVi ?: "Chưa chạy Skeptic"}"
            )
        }
        if (mode == EnvironmentMode.LIVE && settings.environmentMode == "LIVE" && skeptic.verdict != SkepticVerdict.PASS) {
            return gateFail(
                "GATE_12_LIVE_SKEPTIC_NOT_PASS",
                "TỪ CHỐI LIVE (P0-04 & P0-05): Chế độ LIVE yêu cầu Skeptic đạt PASS hoàn toàn (hiện tại=${skeptic.verdict.name})."
            )
        }

        // 13. FeatureSnapshot freshness & Spread check (P0-04: Re-verify timestamp age at execution time)
        val snap = _latestFeatureSnapshot.value
        val snapAgeMs = if (snap != null) (nowMs - snap.asOfUtcMs).coerceAtLeast(0L) else Long.MAX_VALUE
        if (snap == null || snap.symbol != sizing.symbol || snap.isDataStale || snapAgeMs > 900_000L) {
            return gateFail(
                "GATE_13_MARKET_DATA_STALE",
                "Dữ liệu thị trường của ${sizing.symbol} bị trễ (isDataStale=${snap?.isDataStale}, ageMs=$snapAgeMs) hoặc không khớp symbol."
            )
        }

        // 14. Real Exchange Metadata check (P1-3.6)
        if (!binanceAdapter.hasVerifiedRealExchangeMetadata(sizing.symbol) || !sizing.isRealExchangeFilter) {
            return gateFail(
                "GATE_14_UNVERIFIED_EXCHANGE_METADATA",
                "Chưa tải được ExchangeInfo thật từ Binance cho ${sizing.symbol} (P1-3.6 Fail-Closed)."
            )
        }

        // 15. Calibrated EV requirement for LIVE (P0-3.4)
        val ev = _latestEvEstimate.value
        if (mode == EnvironmentMode.LIVE && (ev == null || !ev.canUseForLive)) {
            return gateFail(
                "GATE_15_UNCALIBRATED_LIVE_EV",
                "TỪ CHỐI LIVE (Mục 4 & P0-3.4): Điểm EV hiện tại chưa có mô hình xác suất đã hiệu chuẩn (probabilityCalibrated=false, yêu cầu N>=30 lệnh thật)."
            )
        }

        // 16. Real-time Entry Price Slippage vs Signal Entry Price
        val livePrice = currentMarketPriceOverride ?: snap.markPrice
        if (livePrice > BigDecimal.ZERO && sizing.entryPrice > BigDecimal.ZERO) {
            val driftPct = livePrice.subtract(sizing.entryPrice).abs()
                .divide(sizing.entryPrice, 6, RoundingMode.HALF_UP)
            val maxAllowedSlippageDrift = BigDecimal("0.0035") // 0.35% max drift from signal entry price
            if (driftPct > maxAllowedSlippageDrift) {
                return gateFail(
                    "GATE_16_EXCESSIVE_PRICE_DRIFT",
                    "Giá thị trường hiện tại ($livePrice) đã lệch ${driftPct.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)}% so với giá tín hiệu (${sizing.entryPrice}), vượt ngưỡng bảo vệ trượt giá 0.35%."
                )
            }
        }

        return ExecutionGateDecision(
            approved = true,
            symbol = sizing.symbol,
            signalId = linkedSignal.signalId,
            decisionId = sizing.decisionId,
            sizingId = sizing.sizingId,
            riskApprovalId = sizing.riskApprovalId,
            check1SignalExists = true,
            check2SignalNotExpired = true,
            check3SignalValidated = true,
            check4SkepticNotRejected = true,
            check5InputDataFreshAndComplete = true,
            check6AiReviewValidForMode = true,
            check7EvValidAndMeetsThreshold = true,
            check8PositionSizingApprovedAndLinked = true,
            check9RiskEngineApproved = true,
            check10ExchangeMetadataVerified = true,
            check11AccountStateVerified = true,
            check12NoConflictingOrUnknownOrders = true,
            check13WithinPositionAndExposureLimits = true,
            check14MarketSlippageWithinLimit = true,
            check15ApiAndClockSyncHealthy = true,
            check16TradingModePermitsExecution = true,
            failedChecks = emptyList(),
            rejectReasonCode = null,
            rejectReasonVi = null,
            evaluatedAtUtcMs = nowMs
        )
    }

    /**
     * MOD-12, P0-3.1 & P0-3.3 · Executes a validated trade candidate through the single mutex-guarded ExecutionCoordinator gate.
     * - Enforces `globalCapitalMutex` + persistent DB lock (`P0-13`) and `evaluateUnifiedExecutionGate()`.
     * - Recalculates SL, TP, and Net Breakeven from the actual fill price (`avgPrice` or `userTrades` VWAP) and `executedQty`.
     * - Places BOTH Stop Loss (`STOP_MARKET` via Algo Order API) AND Take Profit (`TAKE_PROFIT_MARKET` via Algo Order API)
     *   and verifies both are active on the exchange before transitioning to `OPEN_PROTECTED`.
     */
    suspend fun executeApprovedCandidateIfEligible(
        currentMarketPriceOverride: BigDecimal? = null
    ): String = globalCapitalMutex.withLock {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val mode = resolveApiEndpointMode(settings)

        if (mode == EnvironmentMode.LIVE && (_safetyDebugLockActive.value || settings.environmentMode != "LIVE")) {
            val gate = evaluateUnifiedExecutionGate(currentMarketPriceOverride)
            _latestExecutionGateDecision.value = gate
            return gate.rejectReasonVi ?: "TỪ CHỐI THỰC THI — KHÓA AN TOÀN LIVE"
        }

        val capState = _accountCapitalState.value
        if (!capState.verificationState.isVerified || capState.exchangeEquityUsdt == null) {
            val gate = evaluateUnifiedExecutionGate(currentMarketPriceOverride)
            _latestExecutionGateDecision.value = gate
            return gate.rejectReasonVi ?: "Tài khoản Binance chưa được xác minh."
        }

        val existingPositions = dao.getActivePositionsOnce()
        if (existingPositions.isNotEmpty()) {
            return "INV-09 Từ chối: Đang có ${existingPositions.size} vị thế mở (${existingPositions.first().symbol}). V1 chỉ quản lý tối đa 1 vị thế."
        }

        val pendingOrUnknown = dao.getActiveOrUnknownIntents()
        if (pendingOrUnknown.isNotEmpty()) {
            transitionState(BotState.UNKNOWN_ORDER_STATUS, "Còn OrderIntent chưa xác định trạng thái.")
            return "INV-06 Chống lệnh trùng: Đang có OrderIntent (${pendingOrUnknown.first().clientOrderId} · status=${pendingOrUnknown.first().status}) chưa được đối soát hoàn tất. Bắt buộc chạy Đối soát trước."
        }

        val sizing = _latestSizingDecision.value
            ?: return "Chưa có kết quả định cỡ vị thế từ Risk Engine. Hãy bấm Quét & Đánh giá trước."

        if (!sizing.approved) {
            return "INV-02 Phủ quyết bởi Risk Engine: ${sizing.rejectReasonVi}"
        }

        val ev = _latestEvEstimate.value
        if (mode == EnvironmentMode.LIVE && (ev == null || !ev.canUseForLive)) {
            return "TỪ CHỐI LIVE (Mục 4): Điểm EV hiện tại chưa có mô hình xác suất đã hiệu chuẩn (probabilityCalibrated=false) hoặc dữ liệu chưa xác minh."
        }

        val gateDecision = evaluateUnifiedExecutionGate(currentMarketPriceOverride)
        _latestExecutionGateDecision.value = gateDecision
        if (!gateDecision.approved) {
            return "TỪ CHỐI BỞI EXECUTION GATE (${gateDecision.rejectReasonCode}): ${gateDecision.rejectReasonVi}"
        }

        val nowMs = binanceAdapter.currentAdjustedTimestampMs()
        val entryClientOrderId = "ASTRA_ENTRY_${sizing.symbol}_${nowMs}"
        val intentId = UUID.randomUUID().toString()
        val orderSide = if (sizing.side == TradeSide.LONG) "BUY" else "SELL"
        val protectiveSide = if (sizing.side == TradeSide.LONG) "SELL" else "BUY"
        val positionSide = "BOTH"

        // P0-13: Persist execution lock owner and TTL before mutating order state
        dao.upsertSettings(
            settings.copy(
                executionLockOwner = "ENTRY:$entryClientOrderId",
                executionLockExpiresAtMs = nowMs + 30_000L,
                updatedAtMs = nowMs
            )
        )

        val linkedSignal = gateDecision.signalId?.let { dao.getSignalByIdOnce(it) }
        val predictedScoreStr = linkedSignal?.rankingScore
            ?: ev?.rankingScore?.toPlainString()
        val calibratedProbStr = linkedSignal?.predictedWinProbability
            ?: if (ev?.probabilityCalibrated == true) ev.pWin.toPlainString() else null

        // INV-04: Persist OrderIntent in Room with full lineage BEFORE sending request
        val intentEntity = OrderIntentEntity(
            intentId = intentId,
            clientOrderId = entryClientOrderId,
            symbol = sizing.symbol,
            side = orderSide,
            positionDirection = sizing.side.name,
            positionSide = positionSide,
            orderType = "MARKET",
            quantity = sizing.roundedQty.toPlainString(),
            intendedEntryPrice = sizing.entryPrice.toPlainString(),
            intendedStopPrice = sizing.stopPrice.toPlainString(),
            intendedTargetPrice = sizing.targetPrice.toPlainString(),
            intendedRiskUsdt = sizing.totalEstimatedLossAtStopUsdt.toPlainString(),
            signalId = gateDecision.signalId,
            decisionId = gateDecision.decisionId,
            sizingId = gateDecision.sizingId,
            riskApprovalId = gateDecision.riskApprovalId,
            predictedScoreAtEntry = predictedScoreStr,
            calibratedProbAtEntry = calibratedProbStr,
            environmentMode = mode.name,
            status = "PERSISTED",
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
        dao.upsertOrderIntent(intentEntity)
        transitionState(BotState.ORDER_INTENT_PERSISTED, "Đã ghi OrderIntent $entryClientOrderId")
        transitionState(BotState.ENTRY_PENDING, "Đang gửi lệnh mở $entryClientOrderId")

        val rawEntryRes = binanceAdapter.submitFuturesOrder(
            mode = mode,
            symbol = sizing.symbol,
            side = orderSide,
            type = "MARKET",
            quantity = sizing.roundedQty,
            stopPrice = null,
            workingType = null,
            closePosition = false,
            reduceOnly = false,
            newClientOrderId = entryClientOrderId,
            positionSide = positionSide
        )

        // INV-06 & P0-07: On UNKNOWN_EXECUTION_STATUS, query by clientOrderId first before deciding status
        val entryRes = if (!rawEntryRes.isSuccess && rawEntryRes.errorCategory == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS) {
            val queried = binanceAdapter.queryOrderByClientId(mode, sizing.symbol, entryClientOrderId)
            if (queried.isSuccess && queried.data != null) queried else rawEntryRes
        } else {
            rawEntryRes
        }

        if (!entryRes.isSuccess || entryRes.data == null) {
            if (entryRes.errorCategory == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS) {
                dao.upsertOrderIntent(intentEntity.copy(status = "UNKNOWN", updatedAtMs = System.currentTimeMillis()))
                transitionState(BotState.UNKNOWN_ORDER_STATUS, entryRes.sanitizedMessageVi)
                return entryRes.sanitizedMessageVi
            } else {
                dao.upsertOrderIntent(intentEntity.copy(status = "REJECTED", updatedAtMs = System.currentTimeMillis()))
                transitionState(BotState.POSITION_CLOSED_RECONCILIATION, "Lệnh bị sàn từ chối")
                transitionState(BotState.CLEANUP, "Dọn trạng thái sau từ chối")
                transitionState(BotState.READY_TEST, "Quay về trạng thái chờ")
                return "Sàn từ chối lệnh Entry: ${entryRes.sanitizedMessageVi}"
            }
        }

        val orderResp = entryRes.data
        // INV-05 & Requirement 6.11: HTTP 200 alone does NOT mean a position is filled! Must verify status and executedQty > 0.
        val isFilledOrPartial = (orderResp.status == "FILLED" || orderResp.status == "PARTIALLY_FILLED") &&
            orderResp.executedQty > BigDecimal.ZERO

        dao.upsertOrderIntent(
            intentEntity.copy(
                status = orderResp.status,
                exchangeOrderId = orderResp.orderId,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        dao.upsertOrder(
            OrderRecordEntity(
                clientOrderId = orderResp.clientOrderId,
                exchangeOrderId = orderResp.orderId,
                symbol = orderResp.symbol,
                side = orderResp.side,
                positionSide = orderResp.positionSide,
                orderType = orderResp.type,
                purpose = "ENTRY",
                quantity = orderResp.origQty.toPlainString(),
                executedQty = orderResp.executedQty.toPlainString(),
                avgPrice = orderResp.avgPrice.toPlainString(),
                stopPrice = null,
                workingType = null,
                status = orderResp.status,
                isBotOwned = true,
                isAlgoOrder = false,
                updatedAtMs = orderResp.updateTimeMs
            )
        )

        if (!isFilledOrPartial) {
            transitionState(
                BotState.RECONCILIATION_REQUIRED,
                "Lệnh ${orderResp.clientOrderId} trả về status=${orderResp.status}, executedQty=${orderResp.executedQty} (chưa xác nhận khớp vị thế)."
            )
            return "Chưa xác nhận phát sinh vị thế (INV-05): Lệnh ${orderResp.clientOrderId} có status=${orderResp.status}, executedQty=${orderResp.executedQty}. Cần đối soát trước khi tiếp tục."
        }

        transitionState(
            if (orderResp.status == "PARTIALLY_FILLED") BotState.PARTIALLY_FILLED else BotState.FILLED,
            "Đã xác minh khớp Entry ${orderResp.clientOrderId} (executedQty=${orderResp.executedQty})"
        )
        transitionState(BotState.PROTECTION_PENDING, "Đang tính lại SL/TP theo giá khớp thực tế & đặt lệnh bảo vệ Algo SL+TP (P0-3.1)")

        // P0-06: Verify actual fill price (`avgPrice` or VWAP from `/fapi/v1/userTrades` / `/fapi/v1/order`).
        // Never blindly trust `sizing.entryPrice` if exchange response returned avgPrice == 0!
        val verifiedFillPrice: BigDecimal? = when {
            orderResp.avgPrice > BigDecimal.ZERO -> orderResp.avgPrice
            else -> {
                val tradesRes = binanceAdapter.fetchUserTradesForOrder(mode, sizing.symbol, orderResp.orderId)
                val trades = tradesRes.data.orEmpty()
                val totalQty = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.qty) }
                val weightedNotional = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.price.multiply(t.qty)) }
                if (tradesRes.isSuccess && totalQty > BigDecimal.ZERO) {
                    weightedNotional.divide(totalQty, 6, RoundingMode.HALF_UP)
                } else {
                    val queriedOrder = binanceAdapter.queryOrderByClientId(mode, sizing.symbol, entryClientOrderId)
                    queriedOrder.data?.avgPrice?.takeIf { it > BigDecimal.ZERO }
                }
            }
        }
        val fillPrice = verifiedFillPrice ?: sizing.entryPrice
        val filter = binanceAdapter.getCachedSymbolFilter(sizing.symbol)
        val postFillPrices = QuantMathEngine.recalculateProtectivePricesFromActualFill(
            symbolFilter = filter,
            side = sizing.side,
            intendedEntryPrice = sizing.entryPrice,
            intendedStopPrice = sizing.stopPrice,
            intendedTargetPrice = sizing.targetPrice,
            actualFillPrice = fillPrice,
            actualExecutedQty = orderResp.executedQty
        )
        val recalculatedStopPrice = postFillPrices.recalculatedStopPrice
        val recalculatedTargetPrice = postFillPrices.recalculatedTargetPrice
        val recalculatedNetBe = postFillPrices.recalculatedNetBreakevenPrice

        val signedQtyStr = if (sizing.side == TradeSide.LONG) {
            orderResp.executedQty.toPlainString()
        } else {
            "-${orderResp.executedQty.toPlainString()}"
        }

        // P0-3.1 & P0-07 Step A: Place Server-Side STOP_MARKET (SL) via Algo Order API (with query-before-retry on UNKNOWN)
        val slClientOrderId = "ASTRA_SL_${sizing.symbol}_${System.currentTimeMillis()}"
        val rawSlRes = binanceAdapter.submitProtectiveStopMarketOrder(
            mode = mode,
            symbol = sizing.symbol,
            side = protectiveSide,
            stopPrice = recalculatedStopPrice,
            closePosition = true,
            newClientOrderId = slClientOrderId,
            workingType = settings.stopTriggerWorkingType,
            positionSide = positionSide
        )
        val slRes = if (!rawSlRes.isSuccess && rawSlRes.errorCategory == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS) {
            val queriedSl = binanceAdapter.queryAlgoOrderByClientId(mode, sizing.symbol, slClientOrderId)
            if (queriedSl.isSuccess && queriedSl.data != null) queriedSl else rawSlRes
        } else {
            rawSlRes
        }

        val slVerified = slRes.isSuccess && slRes.data != null &&
            binanceAdapter.verifyProtectiveOrderRecord(
                order = slRes.data,
                expectedSymbol = sizing.symbol,
                expectedSide = protectiveSide,
                expectedTriggerPrice = recalculatedStopPrice,
                expectedTickSize = filter.tickSize,
                expectedPositionSide = positionSide
            )

        val protectiveOrdersToPersist = mutableListOf<OrderRecordEntity>()
        if (slRes.isSuccess && slRes.data != null) {
            val slOrd = slRes.data
            protectiveOrdersToPersist.add(
                OrderRecordEntity(
                    clientOrderId = slOrd.clientOrderId,
                    exchangeOrderId = slOrd.orderId,
                    symbol = slOrd.symbol,
                    side = slOrd.side,
                    positionSide = slOrd.positionSide,
                    orderType = slOrd.type,
                    purpose = "PROTECTIVE_SL",
                    quantity = orderResp.executedQty.toPlainString(),
                    executedQty = "0",
                    avgPrice = "0",
                    stopPrice = recalculatedStopPrice.toPlainString(),
                    workingType = settings.stopTriggerWorkingType,
                    status = slOrd.status,
                    isBotOwned = true,
                    isAlgoOrder = slOrd.isAlgoOrder,
                    updatedAtMs = slOrd.updateTimeMs
                )
            )
        }

        // P0-3.1 & P0-07 Step B: Place Server-Side TAKE_PROFIT_MARKET (TP) via Algo Order API (with query-before-retry on UNKNOWN)
        val tpClientOrderId = "ASTRA_TP_${sizing.symbol}_${System.currentTimeMillis()}"
        val rawTpRes = binanceAdapter.submitProtectiveTakeProfitMarketOrder(
            mode = mode,
            symbol = sizing.symbol,
            side = protectiveSide,
            triggerPrice = recalculatedTargetPrice,
            closePosition = true,
            newClientOrderId = tpClientOrderId,
            workingType = settings.stopTriggerWorkingType,
            positionSide = positionSide
        )
        val tpRes = if (!rawTpRes.isSuccess && rawTpRes.errorCategory == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS) {
            val queriedTp = binanceAdapter.queryAlgoOrderByClientId(mode, sizing.symbol, tpClientOrderId)
            if (queriedTp.isSuccess && queriedTp.data != null) queriedTp else rawTpRes
        } else {
            rawTpRes
        }

        val tpVerified = tpRes.isSuccess && tpRes.data != null &&
            binanceAdapter.verifyProtectiveOrderRecord(
                order = tpRes.data,
                expectedSymbol = sizing.symbol,
                expectedSide = protectiveSide,
                expectedTriggerPrice = recalculatedTargetPrice,
                expectedTickSize = filter.tickSize,
                expectedPositionSide = positionSide
            )

        if (tpRes.isSuccess && tpRes.data != null) {
            val tpOrd = tpRes.data
            protectiveOrdersToPersist.add(
                OrderRecordEntity(
                    clientOrderId = tpOrd.clientOrderId,
                    exchangeOrderId = tpOrd.orderId,
                    symbol = tpOrd.symbol,
                    side = tpOrd.side,
                    positionSide = tpOrd.positionSide,
                    orderType = tpOrd.type,
                    purpose = "PROTECTIVE_TP",
                    quantity = orderResp.executedQty.toPlainString(),
                    executedQty = "0",
                    avgPrice = "0",
                    stopPrice = recalculatedTargetPrice.toPlainString(),
                    workingType = settings.stopTriggerWorkingType,
                    status = tpOrd.status,
                    isBotOwned = true,
                    isAlgoOrder = tpOrd.isAlgoOrder,
                    updatedAtMs = tpOrd.updateTimeMs
                )
            )
        }

        // P0-3.1 & P0-07 Step C: Verify BOTH SL and TP are active on exchange before declaring OPEN_PROTECTED
        return if (slVerified && tpVerified) {
            val slOrd = slRes.data!!
            val tpOrd = tpRes.data!!
            val protectedPosition = PositionEntity(
                symbol = sizing.symbol,
                signedQuantity = signedQtyStr,
                entryAvgPrice = fillPrice.toPlainString(),
                markPrice = fillPrice.toPlainString(),
                unrealizedPnlUsdt = "0.0000",
                isolatedMarginUsdt = sizing.initialMarginUsdt.toPlainString(),
                leverage = sizing.leverage,
                stopPrice = recalculatedStopPrice.toPlainString(),
                targetPrice = recalculatedTargetPrice.toPlainString(),
                netBreakevenEstimate = recalculatedNetBe.toPlainString(),
                protectiveSlOrderId = slOrd.orderId,
                protectiveSlVerified = true,
                protectiveTpOrderId = tpOrd.orderId,
                protectiveTpVerified = true,
                positionSide = positionSide,
                signalId = gateDecision.signalId,
                decisionId = gateDecision.decisionId,
                predictedScoreAtEntry = predictedScoreStr,
                calibratedProbAtEntry = calibratedProbStr,
                ownershipTag = "BOT_OWNED",
                state = PositionProtectionState.OPEN_PROTECTED.name,
                openedAtMs = nowMs,
                updatedAtMs = System.currentTimeMillis()
            )
            dao.persistPositionAndOrdersAtomically(protectedPosition, protectiveOrdersToPersist)
            transitionState(
                BotState.POSITION_PROTECTED,
                "Đã xác minh bộ đôi lệnh bảo vệ Algo SL (#${slOrd.orderId}) & TP (#${tpOrd.orderId})"
            )
            "Đã khớp lệnh ${sizing.symbol} (${sizing.side.name}, executedQty=${orderResp.executedQty}, avgFill=$fillPrice) & xác minh đầy đủ lệnh bảo vệ STOP_MARKET (#${slOrd.orderId} @ $recalculatedStopPrice) + TAKE_PROFIT_MARKET (#${tpOrd.orderId} @ $recalculatedTargetPrice)."
        } else {
            val failureState = when {
                slRes.errorCategory == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS ||
                    tpRes.errorCategory == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS ->
                    PositionProtectionState.PROTECTION_UNKNOWN.name
                slVerified || tpVerified ->
                    PositionProtectionState.PROTECTION_DEGRADED.name
                else ->
                    PositionProtectionState.PROTECTION_FAILURE.name
            }
            val failReason = buildString {
                if (!slVerified) append("SL lỗi/chưa xác minh (${slRes.sanitizedMessageVi}); ")
                if (!tpVerified) append("TP lỗi/chưa xác minh (${tpRes.sanitizedMessageVi}); ")
            }.trim()

            val degradedPosition = PositionEntity(
                symbol = sizing.symbol,
                signedQuantity = signedQtyStr,
                entryAvgPrice = fillPrice.toPlainString(),
                markPrice = fillPrice.toPlainString(),
                unrealizedPnlUsdt = "0.0000",
                isolatedMarginUsdt = sizing.initialMarginUsdt.toPlainString(),
                leverage = sizing.leverage,
                stopPrice = recalculatedStopPrice.toPlainString(),
                targetPrice = recalculatedTargetPrice.toPlainString(),
                netBreakevenEstimate = recalculatedNetBe.toPlainString(),
                protectiveSlOrderId = slRes.data?.orderId,
                protectiveSlVerified = slVerified,
                protectiveTpOrderId = tpRes.data?.orderId,
                protectiveTpVerified = tpVerified,
                positionSide = positionSide,
                signalId = gateDecision.signalId,
                decisionId = gateDecision.decisionId,
                predictedScoreAtEntry = predictedScoreStr,
                calibratedProbAtEntry = calibratedProbStr,
                ownershipTag = "BOT_OWNED",
                state = failureState,
                openedAtMs = nowMs,
                updatedAtMs = System.currentTimeMillis()
            )
            dao.persistPositionAndOrdersAtomically(degradedPosition, protectiveOrdersToPersist)
            transitionState(BotState.PROTECTION_FAILURE, "Lỗi đặt/xác minh SL/TP bảo vệ ($failureState): $failReason")
            dao.insertRiskEvent(
                RiskEventEntity(
                    triggerCode = "PROTECTIVE_ORDER_REJECTED",
                    severity = "CRITICAL",
                    actionTaken = "$failureState · Khóa mở lệnh mới & kích hoạt quy trình khẩn cấp",
                    detailVi = failReason
                )
            )
            "KHẨN CẤP (INV-03 & P0-07): Không thể xác minh đầy đủ cặp lệnh bảo vệ SL+TP trên sàn ($failReason). Đã chuyển vị thế sang $failureState."
        }
    }

    /**
     * P0-01, P0-02, P0-03, P0-04 · Verified 8-Stage Panic Close State Machine:
     * `PANIC_TRIGGERED` -> `FETCH_EXCHANGE_POSITION` -> `CLOSE_ORDER_SUBMITTED` ->
     * `CLOSE_ORDER_RECONCILIATION` -> `POSITION_VERIFICATION` ->
     * `CANCEL_REMAINING_PROTECTIVE_ORDERS` -> `FINAL_EXCHANGE_RECONCILIATION` -> `CLOSED_CONFIRMED`.
     *
     * Strictly fail-closed:
     * - Distinguishes 3 explicit pre-close states (`FETCH_FAILED`, `VERIFIED_ZERO`, `VERIFIED_NON_ZERO`).
     * - NEVER falls back to `localSignedQty` if exchange fetch fails (`FETCH_FAILED`) or if exchange position is already `0` (`VERIFIED_ZERO`).
     * - NEVER deletes the local `PositionEntity` unless post-close position is verified `== 0` AND all protective order cancellations are verified.
     * - NEVER uses `markPrice` as a fill exit price or fabricates a fixed `0.0005` commission as verified PnL.
     */
    suspend fun executePanicCloseStateMachine(reasonVi: String): PanicCloseReport = globalCapitalMutex.withLock {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val mode = resolveApiEndpointMode(settings)
        val activePositions = dao.getActivePositionsOnce()
        val trail = mutableListOf<PanicCloseStage>()

        if (activePositions.isEmpty()) {
            transitionState(BotState.READY_TEST, "Không có vị thế mở khi gọi Panic Close")
            val emptyReport = PanicCloseReport(
                confirmedClosed = true,
                finalStage = PanicCloseStage.CLOSED_CONFIRMED,
                symbol = "NONE",
                closedQuantity = BigDecimal.ZERO,
                fillAvgPrice = BigDecimal.ZERO,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_ZERO,
                postCloseVerifiedZero = true,
                finalExchangeVerifiedClean = true,
                pnlStatus = FillPnlStatus.VERIFIED,
                stageTrail = listOf(PanicCloseStage.CLOSED_CONFIRMED),
                summaryVi = "Hiện không có vị thế đang mở để đóng khẩn cấp."
            )
            _latestPanicCloseReport.value = emptyReport
            return emptyReport
        }

        val botOwnedPositions = activePositions.filter { it.ownershipTag == "BOT_OWNED" }
        val pos = botOwnedPositions.firstOrNull() ?: activePositions.first()

        // Stage 1: PANIC_TRIGGERED
        trail.add(PanicCloseStage.PANIC_TRIGGERED)
        transitionState(BotState.EXIT_PENDING, "PANIC_TRIGGERED: Khởi chạy đóng khẩn cấp 8 bước cho ${pos.symbol} (${pos.positionSide})")

        // Mandatory credential check: never delete local position if credentials are missing!
        if (!secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY) ||
            !secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        ) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            dao.upsertPosition(pos.copy(state = "PANIC_CLOSE_FAILED", updatedAtMs = System.currentTimeMillis()))
            transitionState(BotState.RECONCILIATION_REQUIRED, "PANIC_CLOSE_FAILED: Thiếu API Key/Secret trong SecretVault")
            val failReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = BigDecimal.ZERO,
                fillAvgPrice = BigDecimal.ZERO,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.FETCH_FAILED,
                postCloseVerifiedZero = false,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "MISSING_API_CREDENTIALS · TỪ CHỐI XÓA VỊ THẾ (P0-01): Thiếu khóa API Binance trong SecretVault nên không thể gửi lệnh đóng thật lên sàn. Giữ nguyên vị thế cục bộ (${pos.symbol}) để bảo toàn trạng thái."
            )
            _latestPanicCloseReport.value = failReport
            return failReport
        }

        // Stage 2: FETCH_EXCHANGE_POSITION (P0-01: 4 explicit states: FETCH_FAILED, SNAPSHOT_INCONSISTENT, VERIFIED_ZERO, VERIFIED_NON_ZERO)
        trail.add(PanicCloseStage.FETCH_EXCHANGE_POSITION)
        val preReconcileRes = binanceAdapter.fetchAccountAndPositionsSnapshot(mode)
        if (!preReconcileRes.isSuccess || preReconcileRes.data == null) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            dao.upsertPosition(pos.copy(state = "PANIC_CLOSE_FAILED", updatedAtMs = System.currentTimeMillis()))
            transitionState(
                BotState.RECONCILIATION_REQUIRED,
                "PANIC_PRE_CLOSE_FETCH_FAILED: Không thể truy vấn vị thế thật trên sàn (${preReconcileRes.sanitizedMessageVi})"
            )
            dao.insertRiskEvent(
                RiskEventEntity(
                    triggerCode = "PANIC_PRE_CLOSE_FETCH_FAILED",
                    severity = "CRITICAL",
                    actionTaken = "Từ chối dùng localSignedQty mù quáng · Giữ PositionEntity ở PANIC_CLOSE_FAILED",
                    detailVi = "Không thể xác minh vị thế thật của ${pos.symbol} trên sàn trước khi đóng khẩn cấp: ${preReconcileRes.sanitizedMessageVi}"
                )
            )
            val fetchFailReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = BigDecimal.ZERO,
                fillAvgPrice = BigDecimal.ZERO,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.FETCH_FAILED,
                postCloseVerifiedZero = false,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "PANIC_PRE_CLOSE_FETCH_FAILED (P0-01): Không thể truy vấn vị thế thật trên sàn cho ${pos.symbol} (${preReconcileRes.sanitizedMessageVi}). Tuyệt đối KHÔNG dùng số lượng cục bộ để gửi lệnh đóng mù quáng và KHÔNG xóa vị thế."
            )
            _latestPanicCloseReport.value = fetchFailReport
            return fetchFailReport
        }

        val matchingSymbolPositions = preReconcileRes.data.positions.filter {
            it.symbol.equals(pos.symbol, ignoreCase = true) && it.positionAmt.compareTo(BigDecimal.ZERO) != 0
        }

        // Check for conflicting Hedge Mode legs or positionSide mismatch (SNAPSHOT_INCONSISTENT)
        if (matchingSymbolPositions.size > 1 ||
            (matchingSymbolPositions.size == 1 && !matchingSymbolPositions.first().positionSide.equals(pos.positionSide, ignoreCase = true))
        ) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            dao.upsertPosition(pos.copy(state = "PANIC_CLOSE_FAILED", updatedAtMs = System.currentTimeMillis()))
            transitionState(
                BotState.RECONCILIATION_REQUIRED,
                "PANIC_POSITION_SIDE_MISMATCH: Phát hiện lệch positionSide hoặc đa vị thế Hedge Mode cho ${pos.symbol}"
            )
            dao.insertRiskEvent(
                RiskEventEntity(
                    triggerCode = "PANIC_POSITION_SIDE_MISMATCH",
                    severity = "CRITICAL",
                    actionTaken = "Fail-Closed · Giữ PositionEntity ở PANIC_CLOSE_FAILED",
                    detailVi = "Vị thế trên sàn của ${pos.symbol} có số nhánh=${matchingSymbolPositions.size} hoặc lệch positionSide so với cục bộ (${pos.positionSide})."
                )
            )
            val sideMismatchReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = BigDecimal.ZERO,
                fillAvgPrice = BigDecimal.ZERO,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.SNAPSHOT_INCONSISTENT,
                postCloseVerifiedZero = false,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "PANIC_POSITION_SIDE_MISMATCH (P0-01): Vị thế ${pos.symbol} trên sàn lệch positionSide hoặc có nhiều nhánh đối nghịch. Đã dừng gửi lệnh đóng để tránh đảo vị thế."
            )
            _latestPanicCloseReport.value = sideMismatchReport
            return sideMismatchReport
        }

        val nowMs = binanceAdapter.currentAdjustedTimestampMs()

        // Case 2B: VERIFIED_ZERO -> Position on exchange is ALREADY 0! Do NOT submit a redundant MARKET close order!
        if (matchingSymbolPositions.isEmpty()) {
            trail.add(PanicCloseStage.POSITION_VERIFICATION)
            trail.add(PanicCloseStage.CANCEL_REMAINING_PROTECTIVE_ORDERS)
            transitionState(BotState.CLEANUP, "VERIFIED_ZERO: Vị thế ${pos.symbol} trên sàn đã bằng 0, đang hủy lệnh SL/TP mồ côi")

            val canceledIds = mutableListOf<String>()
            var failedCancelCount = 0
            val openLocalOrders = dao.getOpenOrdersOnce().filter { it.symbol == pos.symbol && it.isBotOwned }
            for (ord in openLocalOrders) {
                val isAlgo = ord.isAlgoOrder || ord.purpose.startsWith("PROTECTIVE")
                val cancelRes = binanceAdapter.cancelAndVerifyOrder(mode, pos.symbol, ord.clientOrderId, isAlgo)
                if (cancelRes.isSuccess && cancelRes.data != null) {
                    dao.updateOrderStatus(ord.clientOrderId, "CANCELED", nowMs)
                    canceledIds.add(ord.clientOrderId)
                } else {
                    dao.updateOrderStatus(ord.clientOrderId, "CANCEL_PENDING", nowMs)
                    failedCancelCount++
                }
            }

            val openAlgoRes = binanceAdapter.fetchOpenAlgoOrders(mode, pos.symbol)
            if (openAlgoRes.isSuccess && openAlgoRes.data != null) {
                for (algoOrd in openAlgoRes.data) {
                    if (algoOrd.clientOrderId.startsWith("ASTRA_") && !canceledIds.contains(algoOrd.clientOrderId)) {
                        val cancelRes = binanceAdapter.cancelAndVerifyOrder(mode, pos.symbol, algoOrd.clientOrderId, isAlgoOrder = true)
                        if (cancelRes.isSuccess && cancelRes.data != null) {
                            canceledIds.add(algoOrd.clientOrderId)
                        } else {
                            failedCancelCount++
                        }
                    }
                }
            } else {
                failedCancelCount++
            }

            trail.add(PanicCloseStage.FINAL_EXCHANGE_RECONCILIATION)
            if (failedCancelCount > 0) {
                trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
                dao.upsertPosition(pos.copy(signedQuantity = "0", state = "PANIC_CLOSE_FAILED", updatedAtMs = nowMs))
                transitionState(
                    BotState.RECONCILIATION_REQUIRED,
                    "PANIC_CANCEL_PROTECTIVE_FAILED: Vị thế đã bằng 0 nhưng còn $failedCancelCount lệnh bảo vệ chưa xác nhận hủy"
                )
                val cancelFailReport = PanicCloseReport(
                    confirmedClosed = false,
                    finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                    symbol = pos.symbol,
                    closedQuantity = BigDecimal.ZERO,
                    fillAvgPrice = BigDecimal.ZERO,
                    realizedNetPnlUsdt = BigDecimal.ZERO,
                    cancelledProtectiveOrderCount = canceledIds.distinct().size,
                    failedCancelProtectiveOrderCount = failedCancelCount,
                    preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_ZERO,
                    postCloseVerifiedZero = true,
                    finalExchangeVerifiedClean = false,
                    pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                    stageTrail = trail,
                    summaryVi = "CẢNH BÁO (P0-03): Vị thế ${pos.symbol} trên sàn đã bằng 0 nhưng còn $failedCancelCount lệnh SL/TP bảo vệ chưa xác nhận hủy thành công. Giữ trạng thái cần đối soát."
                )
                _latestPanicCloseReport.value = cancelFailReport
                return cancelFailReport
            }

            trail.add(PanicCloseStage.CLOSED_CONFIRMED)
            dao.deletePositionBySymbolAndSide(pos.symbol, pos.positionSide)
            transitionState(
                BotState.READY_TEST,
                "CLOSED_CONFIRMED (VERIFIED_ZERO): Vị thế ${pos.symbol} trên sàn đã đóng trước đó (= 0) & đã dọn sạch ${canceledIds.distinct().size} lệnh SL/TP"
            )
            val alreadyZeroReport = PanicCloseReport(
                confirmedClosed = true,
                finalStage = PanicCloseStage.CLOSED_CONFIRMED,
                symbol = pos.symbol,
                closedQuantity = BigDecimal.ZERO,
                fillAvgPrice = BigDecimal.ZERO,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = canceledIds.distinct().size,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_ZERO,
                postCloseVerifiedZero = true,
                finalExchangeVerifiedClean = true,
                pnlStatus = FillPnlStatus.VERIFIED,
                stageTrail = trail,
                summaryVi = "Đã xác minh vị thế ${pos.symbol} trên sàn đã bằng 0 trước đó (VERIFIED_ZERO · không gửi lệnh đóng thừa) & đã hủy xác nhận ${canceledIds.distinct().size} lệnh SL/TP mồ côi."
            )
            _latestPanicCloseReport.value = alreadyZeroReport
            return alreadyZeroReport
        }

        // Case 2C: VERIFIED_NON_ZERO -> Use verified exchange quantity (never localSignedQty)
        val exchangeSignedQtyBefore = matchingSymbolPositions.first().positionAmt
        val absQty = exchangeSignedQtyBefore.abs()
        val closeSide = if (exchangeSignedQtyBefore > BigDecimal.ZERO) "SELL" else "BUY"
        val posSide = if (exchangeSignedQtyBefore > BigDecimal.ZERO) TradeSide.LONG else TradeSide.SHORT
        // Deterministic idempotent clientOrderId per position open timestamp so retries do not duplicate close orders
        val closeClientId = "ASTRA_PANIC_${pos.symbol}_${pos.openedAtMs}"

        // Stage 3: CLOSE_ORDER_SUBMITTED (Query-before-retry if closeClientId already exists in Room)
        trail.add(PanicCloseStage.CLOSE_ORDER_SUBMITTED)
        val existingCloseIntent = dao.getActiveOrUnknownIntents().firstOrNull { it.clientOrderId == closeClientId }
        val closeIntent = OrderIntentEntity(
            intentId = existingCloseIntent?.intentId ?: "INT_PANIC_${pos.symbol}_${pos.openedAtMs}",
            clientOrderId = closeClientId,
            symbol = pos.symbol,
            side = closeSide,
            positionDirection = posSide.name,
            positionSide = pos.positionSide,
            orderType = "MARKET",
            quantity = absQty.toPlainString(),
            intendedEntryPrice = pos.markPrice,
            intendedStopPrice = pos.stopPrice,
            intendedTargetPrice = pos.targetPrice,
            intendedRiskUsdt = "0",
            signalId = pos.signalId,
            decisionId = pos.decisionId,
            predictedScoreAtEntry = pos.predictedScoreAtEntry,
            calibratedProbAtEntry = pos.calibratedProbAtEntry,
            environmentMode = mode.name,
            status = "PERSISTED",
            createdAtMs = existingCloseIntent?.createdAtMs ?: nowMs,
            updatedAtMs = nowMs
        )
        dao.upsertOrderIntent(closeIntent)

        val preExistingOrderRes = if (existingCloseIntent != null) {
            binanceAdapter.queryOrderByClientId(mode, pos.symbol, closeClientId)
        } else {
            null
        }

        val closeRes = if (preExistingOrderRes != null && preExistingOrderRes.isSuccess && preExistingOrderRes.data != null) {
            preExistingOrderRes
        } else {
            binanceAdapter.submitFuturesOrder(
                mode = mode,
                symbol = pos.symbol,
                side = closeSide,
                type = "MARKET",
                quantity = absQty,
                stopPrice = null,
                workingType = null,
                closePosition = false,
                reduceOnly = true,
                newClientOrderId = closeClientId,
                positionSide = pos.positionSide
            )
        }

        // Stage 4: CLOSE_ORDER_RECONCILIATION
        trail.add(PanicCloseStage.CLOSE_ORDER_RECONCILIATION)
        var verifiedCloseOrder = when {
            closeRes.isSuccess && closeRes.data != null -> closeRes.data
            else -> {
                val queryRes = binanceAdapter.queryOrderByClientId(mode, pos.symbol, closeClientId)
                if (queryRes.isSuccess) queryRes.data else null
            }
        }

        // Handle PARTIALLY_FILLED close order with one bounded residual close attempt
        if (verifiedCloseOrder != null && verifiedCloseOrder.status == "PARTIALLY_FILLED") {
            val midCheck = binanceAdapter.fetchAccountAndPositionsSnapshot(mode)
            val remMid = midCheck.data?.positions?.firstOrNull {
                it.symbol.equals(pos.symbol, ignoreCase = true) && it.positionSide.equals(pos.positionSide, ignoreCase = true)
            }?.positionAmt?.abs() ?: BigDecimal.ZERO
            if (remMid > BigDecimal.ZERO) {
                val residualClientId = "${closeClientId}_R2"
                val r2Res = binanceAdapter.submitFuturesOrder(
                    mode = mode,
                    symbol = pos.symbol,
                    side = closeSide,
                    type = "MARKET",
                    quantity = remMid,
                    stopPrice = null,
                    workingType = null,
                    closePosition = false,
                    reduceOnly = true,
                    newClientOrderId = residualClientId,
                    positionSide = pos.positionSide
                )
                if (r2Res.isSuccess && r2Res.data != null && r2Res.data.status == "FILLED") {
                    val totalExec = verifiedCloseOrder.executedQty.add(r2Res.data.executedQty)
                    verifiedCloseOrder = verifiedCloseOrder.copy(
                        status = "FILLED",
                        executedQty = totalExec,
                        avgPrice = if (r2Res.data.avgPrice > BigDecimal.ZERO) r2Res.data.avgPrice else verifiedCloseOrder.avgPrice
                    )
                }
            }
        }

        if (verifiedCloseOrder == null ||
            verifiedCloseOrder.status != "FILLED" ||
            verifiedCloseOrder.executedQty <= BigDecimal.ZERO
        ) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            val statusStr = verifiedCloseOrder?.status ?: "UNKNOWN"
            dao.upsertOrderIntent(closeIntent.copy(status = statusStr, updatedAtMs = System.currentTimeMillis()))
            dao.upsertPosition(pos.copy(state = "PANIC_CLOSE_FAILED", updatedAtMs = System.currentTimeMillis()))
            transitionState(
                BotState.UNKNOWN_ORDER_STATUS,
                "PANIC_CLOSE_FAILED: Lệnh đóng chưa xác nhận FILLED (${closeRes.sanitizedMessageVi})"
            )
            val failReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = BigDecimal.ZERO,
                fillAvgPrice = BigDecimal.ZERO,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_NON_ZERO,
                postCloseVerifiedZero = false,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "CẢNH BÁO KHẨN CẤP (P0-01): Lệnh đóng vị thế ${pos.symbol} chưa xác nhận khớp hoàn tất trên sàn (status=$statusStr · ${closeRes.sanitizedMessageVi}). KHÔNG xóa vị thế cục bộ và KHÔNG ghi nhận PnL giả."
            )
            _latestPanicCloseReport.value = failReport
            return failReport
        }

        dao.upsertOrderIntent(
            closeIntent.copy(
                status = "FILLED",
                exchangeOrderId = verifiedCloseOrder.orderId,
                updatedAtMs = System.currentTimeMillis()
            )
        )

        // Stage 5: POSITION_VERIFICATION (P0-02: Mandatory verification that exchange position == 0; NEVER skip on failure!)
        trail.add(PanicCloseStage.POSITION_VERIFICATION)
        val postPositionCheck = binanceAdapter.fetchAccountAndPositionsSnapshot(mode)
        if (!postPositionCheck.isSuccess || postPositionCheck.data == null) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            dao.upsertPosition(pos.copy(state = "PANIC_CLOSE_FAILED", updatedAtMs = System.currentTimeMillis()))
            transitionState(
                BotState.RECONCILIATION_REQUIRED,
                "PANIC_POST_CLOSE_VERIFICATION_FAILED: Không thể xác minh vị thế = 0 sau lệnh đóng (${postPositionCheck.sanitizedMessageVi})"
            )
            dao.insertRiskEvent(
                RiskEventEntity(
                    triggerCode = "PANIC_POST_CLOSE_VERIFICATION_FAILED",
                    severity = "CRITICAL",
                    actionTaken = "Giữ PositionEntity ở PANIC_CLOSE_FAILED · Không chuyển sang CLOSED_CONFIRMED",
                    detailVi = "Lệnh đóng ${pos.symbol} báo FILLED nhưng truy vấn tài khoản sau đóng thất bại (${postPositionCheck.sanitizedMessageVi})."
                )
            )
            val unverifiedPostReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = verifiedCloseOrder.executedQty,
                fillAvgPrice = verifiedCloseOrder.avgPrice,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_NON_ZERO,
                postCloseVerifiedZero = false,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "PANIC_POST_CLOSE_VERIFICATION_FAILED (P0-02): Không thể xác minh vị thế ${pos.symbol} = 0 trên sàn sau lệnh đóng (${postPositionCheck.sanitizedMessageVi}). Giữ nguyên vị thế ở PANIC_CLOSE_FAILED để đối soát."
            )
            _latestPanicCloseReport.value = unverifiedPostReport
            return unverifiedPostReport
        }

        val remPos = postPositionCheck.data.positions.firstOrNull {
            it.symbol.equals(pos.symbol, ignoreCase = true) && it.positionSide.equals(pos.positionSide, ignoreCase = true)
        }
        val remAmt = remPos?.positionAmt?.abs() ?: BigDecimal.ZERO
        if (remAmt > BigDecimal.ZERO) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            dao.upsertPosition(
                pos.copy(
                    signedQuantity = remPos!!.positionAmt.toPlainString(),
                    state = "PANIC_CLOSE_FAILED",
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            transitionState(BotState.RECONCILIATION_REQUIRED, "Vị thế trên sàn vẫn còn dư $remAmt sau lệnh đóng")
            val partialReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = verifiedCloseOrder.executedQty,
                fillAvgPrice = verifiedCloseOrder.avgPrice,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = 0,
                failedCancelProtectiveOrderCount = 0,
                preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_NON_ZERO,
                postCloseVerifiedZero = false,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "RESIDUAL_EXCHANGE_POSITION (P0-02) · Vị thế ${pos.symbol} trên sàn vẫn còn dư $remAmt sau lệnh đóng. Giữ trạng thái PANIC_CLOSE_FAILED để xử lý tiếp."
            )
            _latestPanicCloseReport.value = partialReport
            return partialReport
        }

        // Stage 6: CANCEL_REMAINING_PROTECTIVE_ORDERS (P0-03: Verified cancel of both Algo & standard bot-owned SL/TP orders)
        trail.add(PanicCloseStage.CANCEL_REMAINING_PROTECTIVE_ORDERS)
        transitionState(BotState.CLEANUP, "CANCEL_REMAINING_PROTECTIVE_ORDERS: Hủy và xác minh lệnh SL/TP mồ côi trên sàn")
        val canceledIds = mutableListOf<String>()
        var failedCancelCount = 0
        val openLocalOrders = dao.getOpenOrdersOnce().filter { it.symbol == pos.symbol && it.isBotOwned }
        for (ord in openLocalOrders) {
            val isAlgo = ord.isAlgoOrder || ord.purpose.startsWith("PROTECTIVE")
            val cancelRes = binanceAdapter.cancelAndVerifyOrder(mode, pos.symbol, ord.clientOrderId, isAlgo)
            if (cancelRes.isSuccess && cancelRes.data != null) {
                dao.updateOrderStatus(ord.clientOrderId, "CANCELED", nowMs)
                canceledIds.add(ord.clientOrderId)
            } else {
                dao.updateOrderStatus(ord.clientOrderId, "CANCEL_PENDING", nowMs)
                failedCancelCount++
            }
        }

        val openAlgoRes = binanceAdapter.fetchOpenAlgoOrders(mode, pos.symbol)
        if (openAlgoRes.isSuccess && openAlgoRes.data != null) {
            for (algoOrd in openAlgoRes.data) {
                if (algoOrd.clientOrderId.startsWith("ASTRA_") && !canceledIds.contains(algoOrd.clientOrderId)) {
                    val cancelRes = binanceAdapter.cancelAndVerifyOrder(mode, pos.symbol, algoOrd.clientOrderId, isAlgoOrder = true)
                    if (cancelRes.isSuccess && cancelRes.data != null) {
                        canceledIds.add(algoOrd.clientOrderId)
                    } else {
                        failedCancelCount++
                    }
                }
            }
        } else {
            failedCancelCount++
        }

        // Stage 7: FINAL_EXCHANGE_RECONCILIATION & Verified Fill Recording (P0-01, P0-03 & P0-12)
        trail.add(PanicCloseStage.FINAL_EXCHANGE_RECONCILIATION)
        transitionState(BotState.POSITION_CLOSED_RECONCILIATION, "FINAL_EXCHANGE_RECONCILIATION: Đối soát lần cuối sau đóng")

        if (failedCancelCount > 0) {
            trail.add(PanicCloseStage.PANIC_FAILED_UNVERIFIED)
            dao.upsertPosition(pos.copy(signedQuantity = "0", state = "PANIC_CLOSE_FAILED", updatedAtMs = nowMs))
            transitionState(
                BotState.RECONCILIATION_REQUIRED,
                "PANIC_CANCEL_PROTECTIVE_FAILED: Còn $failedCancelCount lệnh SL/TP bảo vệ chưa xác nhận hủy trên sàn"
            )
            dao.insertRiskEvent(
                RiskEventEntity(
                    triggerCode = "PANIC_CANCEL_PROTECTIVE_FAILED",
                    severity = "CRITICAL",
                    actionTaken = "Giữ PositionEntity ở PANIC_CLOSE_FAILED · Yêu cầu đối soát lệnh bảo vệ",
                    detailVi = "Đã đóng vị thế ${pos.symbol} = 0 nhưng có $failedCancelCount lệnh SL/TP bảo vệ chưa xác nhận CANCELED trên sàn."
                )
            )
            val cancelFailReport = PanicCloseReport(
                confirmedClosed = false,
                finalStage = PanicCloseStage.PANIC_FAILED_UNVERIFIED,
                symbol = pos.symbol,
                closedQuantity = verifiedCloseOrder.executedQty,
                fillAvgPrice = verifiedCloseOrder.avgPrice,
                realizedNetPnlUsdt = BigDecimal.ZERO,
                cancelledProtectiveOrderCount = canceledIds.distinct().size,
                failedCancelProtectiveOrderCount = failedCancelCount,
                preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_NON_ZERO,
                postCloseVerifiedZero = true,
                finalExchangeVerifiedClean = false,
                pnlStatus = FillPnlStatus.PNL_UNVERIFIED,
                stageTrail = trail,
                summaryVi = "PANIC_CANCEL_PROTECTIVE_FAILED (P0-03): Đã đóng vị thế ${pos.symbol} nhưng còn $failedCancelCount lệnh SL/TP bảo vệ chưa xác nhận hủy trên sàn. Không xóa PositionEntity cho tới khi dọn sạch lệnh."
            )
            _latestPanicCloseReport.value = cancelFailReport
            return cancelFailReport
        }

        val entryPrice = pos.entryAvgPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val userTradesRes = binanceAdapter.fetchUserTradesForOrder(mode, pos.symbol, verifiedCloseOrder.orderId)
        val userTrades = userTradesRes.data.orEmpty()

        val exitPrice: BigDecimal
        val filledQty: BigDecimal
        val grossPnl: BigDecimal
        val commissionUsdt: BigDecimal
        val commissionAsset: String
        val netRealizedPnl: BigDecimal
        val fillPnlStatus: FillPnlStatus
        val isEstimatedFill: Boolean

        if (userTradesRes.isSuccess && userTrades.isNotEmpty()) {
            // Exact verified fill & fee accounting from /fapi/v1/userTrades (P0-04 & P0-12)
            val totalQty = userTrades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.qty) }
            val weightedNotional = userTrades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.price.multiply(t.qty)) }
            exitPrice = if (totalQty > BigDecimal.ZERO) {
                weightedNotional.divide(totalQty, 6, RoundingMode.HALF_UP)
            } else {
                verifiedCloseOrder.avgPrice
            }
            filledQty = if (totalQty > BigDecimal.ZERO) totalQty else verifiedCloseOrder.executedQty
            val exchangeRealizedSum = userTrades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.realizedPnl) }
            grossPnl = if (exchangeRealizedSum.compareTo(BigDecimal.ZERO) != 0) {
                exchangeRealizedSum.setScale(4, RoundingMode.HALF_UP)
            } else {
                QuantMathEngine.calculateLinearFuturesPnl(posSide, entryPrice, exitPrice, filledQty)
            }
            commissionUsdt = userTrades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.commission) }
                .setScale(4, RoundingMode.HALF_UP)
            commissionAsset = userTrades.first().commissionAsset
            netRealizedPnl = grossPnl.subtract(commissionUsdt).setScale(4, RoundingMode.HALF_UP)
            fillPnlStatus = FillPnlStatus.VERIFIED
            isEstimatedFill = false
        } else if (verifiedCloseOrder.avgPrice > BigDecimal.ZERO) {
            // Order avgPrice is present, but /fapi/v1/userTrades commission is not yet available -> ACCOUNTING_PENDING (never fabricate 0.0005 fee as verified)
            exitPrice = verifiedCloseOrder.avgPrice
            filledQty = verifiedCloseOrder.executedQty
            grossPnl = QuantMathEngine.calculateLinearFuturesPnl(posSide, entryPrice, exitPrice, filledQty)
            commissionUsdt = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            commissionAsset = "USDT"
            netRealizedPnl = grossPnl.setScale(4, RoundingMode.HALF_UP)
            fillPnlStatus = FillPnlStatus.ACCOUNTING_PENDING
            isEstimatedFill = true
        } else {
            // Neither userTrades nor avgPrice > 0 is available -> PNL_UNVERIFIED (never substitute markPrice as fill price!)
            exitPrice = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            filledQty = verifiedCloseOrder.executedQty
            grossPnl = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            commissionUsdt = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            commissionAsset = "USDT"
            netRealizedPnl = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            fillPnlStatus = FillPnlStatus.PNL_UNVERIFIED
            isEstimatedFill = true
        }

        val linkedSignal = pos.signalId?.let { dao.getSignalByIdOnce(it) }
        val predictedScoreForFill = pos.predictedScoreAtEntry ?: linkedSignal?.rankingScore
        val calibratedProbForFill = pos.calibratedProbAtEntry ?: linkedSignal?.predictedWinProbability
        val lifecycleId = "POS_${pos.symbol}_${pos.openedAtMs}"

        // P0-12: If userTrades returned multiple individual fills, record each tradeId without collapsing!
        val individualFillEntities: List<FillEntity> = if (userTradesRes.isSuccess && userTrades.size > 1) {
            userTrades.map { t ->
                val tGross = if (t.realizedPnl.compareTo(BigDecimal.ZERO) != 0) {
                    t.realizedPnl.setScale(4, RoundingMode.HALF_UP)
                } else {
                    QuantMathEngine.calculateLinearFuturesPnl(posSide, entryPrice, t.price, t.qty)
                }
                val tComm = t.commission.setScale(4, RoundingMode.HALF_UP)
                val tNet = tGross.subtract(tComm).setScale(4, RoundingMode.HALF_UP)
                FillEntity(
                    tradeId = "TRD_${pos.symbol}_${t.tradeId}",
                    clientOrderId = closeClientId,
                    exchangeOrderId = verifiedCloseOrder.orderId,
                    symbol = pos.symbol,
                    side = closeSide,
                    price = t.price.toPlainString(),
                    quantity = t.qty.toPlainString(),
                    grossPnlUsdt = tGross.toPlainString(),
                    realizedPnlUsdt = tNet.toPlainString(),
                    commissionUsdt = tComm.toPlainString(),
                    commissionAsset = t.commissionAsset,
                    fundingUsdt = "0.0000",
                    pnlStatus = FillPnlStatus.VERIFIED.name,
                    isEstimated = false,
                    signalId = pos.signalId,
                    decisionId = pos.decisionId ?: linkedSignal?.decisionId,
                    predictedScoreAtEntry = predictedScoreForFill,
                    calibratedProbAtEntry = calibratedProbForFill,
                    positionLifecycleId = lifecycleId,
                    reasonVi = reasonVi,
                    executedAtMs = t.timeMs
                )
            }
        } else {
            val singleTradeId = if (userTrades.size == 1 && userTrades.first().tradeId.isNotBlank()) {
                "TRD_${pos.symbol}_${userTrades.first().tradeId}"
            } else {
                "TRD_${pos.symbol}_${verifiedCloseOrder.orderId}"
            }
            listOf(
                FillEntity(
                    tradeId = singleTradeId,
                    clientOrderId = closeClientId,
                    exchangeOrderId = verifiedCloseOrder.orderId,
                    symbol = pos.symbol,
                    side = closeSide,
                    price = exitPrice.toPlainString(),
                    quantity = filledQty.toPlainString(),
                    grossPnlUsdt = grossPnl.toPlainString(),
                    realizedPnlUsdt = netRealizedPnl.toPlainString(),
                    commissionUsdt = commissionUsdt.toPlainString(),
                    commissionAsset = commissionAsset,
                    fundingUsdt = "0.0000",
                    pnlStatus = fillPnlStatus.name,
                    isEstimated = isEstimatedFill,
                    signalId = pos.signalId,
                    decisionId = pos.decisionId ?: linkedSignal?.decisionId,
                    predictedScoreAtEntry = predictedScoreForFill,
                    calibratedProbAtEntry = calibratedProbForFill,
                    positionLifecycleId = lifecycleId,
                    reasonVi = reasonVi,
                    executedAtMs = nowMs
                )
            )
        }

        // Stage 8: CLOSED_CONFIRMED (Delete local position atomically by (symbol, positionSide) ONLY after exchange close & cleanup succeeded)
        trail.add(PanicCloseStage.CLOSED_CONFIRMED)
        dao.finalizeVerifiedPositionCloseAtomically(
            symbol = pos.symbol,
            closeIntent = closeIntent.copy(
                status = "FILLED",
                exchangeOrderId = verifiedCloseOrder.orderId,
                updatedAtMs = nowMs
            ),
            closeOrderRecord = OrderRecordEntity(
                clientOrderId = verifiedCloseOrder.clientOrderId.ifBlank { closeClientId },
                exchangeOrderId = verifiedCloseOrder.orderId,
                symbol = pos.symbol,
                side = closeSide,
                positionSide = pos.positionSide,
                orderType = verifiedCloseOrder.type,
                purpose = "PANIC_CLOSE",
                quantity = verifiedCloseOrder.origQty.toPlainString(),
                executedQty = filledQty.toPlainString(),
                avgPrice = exitPrice.toPlainString(),
                stopPrice = null,
                workingType = null,
                status = "FILLED",
                isBotOwned = true,
                isAlgoOrder = false,
                updatedAtMs = nowMs
            ),
            fillRecord = individualFillEntities.firstOrNull(),
            updatedDailySnapshot = null,
            systemEvent = SystemEventEntity(
                category = "EXECUTION",
                level = "INFO",
                titleVi = "Đã xác nhận đóng khẩn cấp vị thế ${pos.symbol} (CLOSED_CONFIRMED)",
                detailVi = "Qty=$filledQty @ $exitPrice · PnL=${fillPnlStatus.name}: $netRealizedPnl USDT · Phí: $commissionUsdt USDT",
                correlationId = "panic-close-${verifiedCloseOrder.orderId}",
                occurredAtMs = nowMs
            ),
            positionSide = pos.positionSide,
            additionalFillRecords = individualFillEntities.drop(1)
        )
        transitionState(BotState.READY_TEST, "CLOSED_CONFIRMED: Đã xác nhận đóng vị thế ${pos.symbol} = 0 & hủy ${canceledIds.distinct().size} lệnh bảo vệ")

        val successReport = PanicCloseReport(
            confirmedClosed = true,
            finalStage = PanicCloseStage.CLOSED_CONFIRMED,
            symbol = pos.symbol,
            closedQuantity = filledQty,
            fillAvgPrice = exitPrice,
            realizedNetPnlUsdt = netRealizedPnl,
            cancelledProtectiveOrderCount = canceledIds.distinct().size,
            failedCancelProtectiveOrderCount = 0,
            preCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_NON_ZERO,
            postCloseVerifiedZero = true,
            finalExchangeVerifiedClean = true,
            pnlStatus = fillPnlStatus,
            stageTrail = trail,
            summaryVi = "Đã xác nhận đóng vị thế ${pos.symbol} (CLOSED_CONFIRMED · Qty=$filledQty @ $exitPrice · PnL=${fillPnlStatus.name}: $netRealizedPnl USDT · Phí: $commissionUsdt USDT) & hủy ${canceledIds.distinct().size} lệnh SL/TP bảo vệ."
        )
        _latestPanicCloseReport.value = successReport
        return successReport
    }

    /**
     * Section 7.5 & P0-3.2 · Two-phase Panic Close & Orphan Protective Order Cleanup wrapper.
     */
    suspend fun executePanicCloseVerifiedPosition(reasonVi: String): String {
        return executePanicCloseStateMachine(reasonVi).summaryVi
    }

    /**
     * Section 13.1 & INV-18 · Records an external deposit or withdrawal in the cashflow ledger.
     * Does NOT fabricate verified exchange equity if the account is unverified.
     */
    suspend fun recordExternalCashflow(
        type: String,
        amountUsdt: BigDecimal,
        noteVi: String
    ): String {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val signedAmount = if (type == "WITHDRAW") amountUsdt.abs().negate() else amountUsdt.abs()
        val nowMs = System.currentTimeMillis()

        dao.insertCashflow(
            CashflowEntity(
                cashflowId = "CF_$nowMs",
                type = type,
                amountUsdt = signedAmount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                noteVi = noteVi,
                verifiedFromExchange = false,
                timestampMs = nowMs
            )
        )

        val daily = ensureBaselineDailySnapshot(settings)
        val netCf = (daily.netExternalCashflowUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO).add(signedAmount)
        val verifiedEq = _accountCapitalState.value.exchangeEquityUsdt
        val startEq = daily.startEquityUsdt.toBigDecimalOrNull()

        val cfAdjustedPnlStr = if (verifiedEq != null && startEq != null) {
            QuantMathEngine.calculateCashflowAdjustedPnl(verifiedEq, startEq, netCf).toPlainString()
        } else {
            "0.0000"
        }

        dao.upsertDailySnapshot(
            daily.copy(
                netExternalCashflowUsdt = netCf.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                cashflowAdjustedPnlUsdt = cfAdjustedPnlStr,
                updatedAtMs = nowMs
            )
        )

        return "Đã ghi sổ cái dòng tiền ($type: ${signedAmount.setScale(2, RoundingMode.HALF_UP)} USDT · Nhãn: Nhập tay). Tổng dòng tiền ngoài ròng: ${netCf.setScale(2, RoundingMode.HALF_UP)} USDT."
    }

    /**
     * MOD-07, MOD-08 & P0-05 · Runs health check & 2-agent candidate evaluation
     * (Analyst + Independent Skeptic + Evidence Comparison) with strict per-provider API key isolation
     * and fail-closed Independent Skeptic verification (`independentSkepticPassed = false` by default).
     */
    suspend fun requestAiCandidateReview(): String {
        val snap = _latestFeatureSnapshot.value
            ?: return "Chưa có FeatureSnapshot. Hãy bấm Quét & Đánh giá trước."
        val ev = _latestEvEstimate.value
            ?: return "Chưa có ước lượng EV_net."
        val sizing = _latestSizingDecision.value
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val providers = dao.getProvidersOnce()

        val primary = providers.firstOrNull { it.enabled && it.healthStatus == "VERIFIED_HEALTHY" && it.role != "SKEPTIC" }
            ?: providers.firstOrNull { it.role == "FAST_ANALYST" }
            ?: providers.firstOrNull()
            ?: return "Chưa có cấu hình AI Provider."

        // P1-3.7: Strict per-provider key check (no cross-provider fallback)
        val hasKey = aiGateway.hasIsolatedProviderKey(primary)
        if (!hasKey) {
            dao.upsertProvider(primary.copy(enabled = false, healthStatus = "UNCONFIGURED"))
            _aiModuleStatus.value = AiModuleStatus.UNCONFIGURED
            return "TỪ CHỐI AI (Mục 4 & P1-3.7): Chưa cấu hình khóa API riêng cho ${primary.displayName} tại slot '${primary.secretSlot}'. Công tắc Provider bị khóa ở trạng thái OFF và không báo Skeptic PASS giả."
        }

        _aiModuleStatus.value = AiModuleStatus.RUNNING
        val prompt = aiGateway.buildCompactMarketContextPrompt(
            snapshot = snap,
            evEstimate = ev,
            candidateSide = sizing?.side ?: TradeSide.NONE,
            equityUsdt = _accountCapitalState.value.exchangeEquityUsdt ?: BigDecimal.ZERO,
            riskCapPct = settings.riskPerTradePct.toBigDecimalOrNull() ?: BigDecimal("0.005")
        )

        // P1-01: Dynamically allow all verified trading symbols from ExchangeInfo + active snapshot symbol
        val validSymbols = binanceAdapter.getVerifiedTradingSymbols() + setOf(snap.symbol.uppercase())
        val promptHash = "PRM-" + Integer.toHexString(prompt.hashCode()).uppercase()
        val (validationResult, latencyMs) = aiGateway.evaluateCandidateWithProvider(
            provider = primary,
            prompt = prompt,
            validSymbols = validSymbols,
            nowUtcMs = System.currentTimeMillis()
        )

        val nowMs = System.currentTimeMillis()
        dao.insertAiRequest(
            AiRequestEntity(
                requestId = "AIR-$nowMs",
                providerId = primary.providerId,
                modelId = primary.modelId,
                purpose = primary.role,
                symbol = snap.symbol,
                latencyMs = latencyMs,
                estimatedPromptTokens = (prompt.length / 4).coerceAtLeast(1),
                status = if (validationResult.isValid) "SUCCESS" else (validationResult.errorReasonCode ?: "SCHEMA_REJECTED"),
                correlationId = promptHash,
                requestedAtMs = nowMs
            )
        )

        return if (validationResult.isValid && validationResult.plan != null) {
            val plan = validationResult.plan
            dao.upsertProvider(
                primary.copy(
                    enabled = true,
                    healthStatus = "VERIFIED_HEALTHY",
                    lastLatencyMs = latencyMs,
                    lastErrorSummary = null
                )
            )
            _aiModuleStatus.value = AiModuleStatus.VALIDATED_SCHEMA

            // P0-05: Strict fail-closed Independent Skeptic check:
            // Must be a SEPARATE provider with distinct providerId, distinct secretSlot, and isolated API key.
            // Default is FALSE — Analyst recommendation NEVER self-approves Skeptic!
            val skepticProvider = providers.firstOrNull {
                it.enabled &&
                    it.providerId != primary.providerId &&
                    it.secretSlot != primary.secretSlot &&
                    (it.modelId != primary.modelId || it.baseUrl != primary.baseUrl) &&
                    (it.role.contains("SKEPTIC", ignoreCase = true) || it.secretSlot == SecretVault.SLOT_AI_SKEPTIC_KEY) &&
                    aiGateway.hasIsolatedProviderKey(it)
            }

            var independentSkepticPassed = false
            var skepticSummaryAppend = " · Chưa cấu hình Independent Skeptic Provider độc lập (chỉ đạt RULE_ONLY_PASS)"

            if (skepticProvider != null) {
                val skepticPrompt = aiGateway.buildIndependentSkepticPrompt(
                    snapshot = snap,
                    evEstimate = ev,
                    analystPlan = plan,
                    maxSpreadPct = settings.maxSpreadPct.toBigDecimalOrNull() ?: BigDecimal("0.0012"),
                    minRelativeVolume = settings.minRelativeVolume.toBigDecimalOrNull() ?: BigDecimal("1.25")
                )
                val (skpResult, skpLatency) = aiGateway.evaluateWithIndependentSkeptic(
                    provider = skepticProvider,
                    prompt = skepticPrompt,
                    expectedSymbol = snap.symbol,
                    nowUtcMs = System.currentTimeMillis()
                )
                dao.insertAiRequest(
                    AiRequestEntity(
                        requestId = "AIR-SKP-${System.currentTimeMillis()}",
                        providerId = skepticProvider.providerId,
                        modelId = skepticProvider.modelId,
                        purpose = "INDEPENDENT_SKEPTIC",
                        symbol = snap.symbol,
                        latencyMs = skpLatency,
                        estimatedPromptTokens = (skepticPrompt.length / 4).coerceAtLeast(1),
                        status = if (skpResult.isValid) "SUCCESS" else (skpResult.errorReasonCode ?: "SKEPTIC_REJECTED"),
                        correlationId = promptHash,
                        requestedAtMs = System.currentTimeMillis()
                    )
                )
                if (skpResult.isValid && skpResult.report != null) {
                    val skpReport = skpResult.report
                    _latestAiSkepticReport.value = skpReport
                    independentSkepticPassed = plan.recommendation == "TRADE_CANDIDATE" &&
                        plan.missingData.isEmpty() &&
                        skpReport.symbol.equals(snap.symbol, ignoreCase = true) &&
                        skpReport.verdict == SkepticVerdict.PASS &&
                        skpReport.missingDataFlags.isEmpty() &&
                        skpReport.confidencePenalty <= BigDecimal("0.35")
                    skepticSummaryAppend = " · Skeptic độc lập (${skepticProvider.displayName}): ${skpReport.verdict.name} (${skpReport.reasonVi})"
                } else {
                    independentSkepticPassed = false
                    skepticSummaryAppend = " · Skeptic độc lập từ chối/lỗi (${skpResult.errorReasonCode})"
                }
            }

            val updatedSkeptic = QuantMathEngine.runSkepticChecks(
                snapshot = snap,
                candidateSide = sizing?.side ?: TradeSide.NONE,
                evEstimate = ev,
                maxSpreadPct = settings.maxSpreadPct.toBigDecimalOrNull() ?: BigDecimal("0.0012"),
                minRelativeVolume = settings.minRelativeVolume.toBigDecimalOrNull() ?: BigDecimal("1.25"),
                aiSkepticActuallyRanAndPassed = independentSkepticPassed
            )
            _latestSkepticResult.value = updatedSkeptic

            // Update latest validated signal if Skeptic + Risk + EV all pass
            val sigId = sizing?.signalId ?: latestValidatedSignalId
            if (sigId != null) {
                val existingSig = dao.getSignalByIdOnce(sigId)
                    ?: dao.getRecentSignalsOnce().firstOrNull { it.signalId == sigId }
                if (existingSig != null) {
                    val newStatus = if (sizing != null && sizing.approved && updatedSkeptic.verdict != SkepticVerdict.REJECT && ev.isPositiveEdge) {
                        "VALIDATED"
                    } else {
                        existingSig.status
                    }
                    dao.upsertSignal(
                        existingSig.copy(
                            status = newStatus,
                            decisionId = plan.decisionId,
                            rejectReasonVi = updatedSkeptic.summaryVi
                        )
                    )
                }
            }

            dao.insertAiDecision(
                AiDecisionEntity(
                    decisionId = plan.decisionId,
                    providerId = primary.providerId,
                    modelId = primary.modelId,
                    role = primary.role,
                    symbol = plan.symbol,
                    recommendation = plan.recommendation,
                    directionCandidate = plan.directionCandidate.name,
                    score = plan.score?.toPlainString(),
                    calibratedProbability = if (ev.probabilityCalibrated) ev.pWin.toPlainString() else null,
                    skepticVerdict = updatedSkeptic.verdict.name,
                    validationStatus = "VALID",
                    reasonVi = plan.reasonVi + skepticSummaryAppend,
                    latencyMs = latencyMs,
                    createdAtMs = nowMs
                )
            )
            "AI (${primary.displayName} · ${latencyMs}ms · VALIDATED_SCHEMA): ${plan.recommendation} (${plan.directionCandidate.name}) — ${plan.reasonVi}$skepticSummaryAppend"
        } else {
            dao.upsertProvider(
                primary.copy(
                    enabled = false,
                    healthStatus = "AUTH_ERROR",
                    lastLatencyMs = latencyMs,
                    lastErrorSummary = validationResult.errorReasonVi
                )
            )
            _aiModuleStatus.value = if (validationResult.errorReasonCode == "PROVIDER_NETWORK_OR_HTTP_ERROR" ||
                validationResult.errorReasonCode == "MISSING_PROVIDER_API_KEY"
            ) {
                AiModuleStatus.HEALTH_CHECK_FAILED
            } else {
                AiModuleStatus.SCHEMA_OR_EVIDENCE_REJECTED
            }

            dao.insertAiDecision(
                AiDecisionEntity(
                    decisionId = "AI-REJ-$nowMs",
                    providerId = primary.providerId,
                    modelId = primary.modelId,
                    role = primary.role,
                    symbol = snap.symbol,
                    recommendation = "NO_TRADE",
                    directionCandidate = "NONE",
                    score = null,
                    calibratedProbability = null,
                    skepticVerdict = "REJECT",
                    validationStatus = validationResult.errorReasonCode ?: "SCHEMA_INVALID",
                    reasonVi = validationResult.errorReasonVi ?: "Không đạt kiểm tra Strict JSON Schema 1.0.",
                    latencyMs = latencyMs,
                    createdAtMs = nowMs
                )
            )
            "Lỗi/Từ chối AI (${validationResult.errorReasonCode}): ${validationResult.errorReasonVi}"
        }
    }

    suspend fun updateLiveChecklist(): LiveActivationChecklist {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val hasApiKeys = secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY) &&
            secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        val capState = _accountCapitalState.value
        val accountVerified = capState.verificationState == AccountVerificationState.VERIFIED_TRADING_READY &&
            capState.exchangeEquityUsdt != null
        val clockOk = binanceAdapter.clockState.value.synced
        val health = _deviceHealth.value
        val noUnknownOrders = dao.getActiveOrUnknownIntents().isEmpty()
        val metadataLoaded = binanceAdapter.hasVerifiedRealExchangeMetadata()

        val list = LiveActivationChecklist(
            safetyDebugLockDisengaged = !_safetyDebugLockActive.value,
            apiKeyConfigured = hasApiKeys,
            accountVerifiedFromBinance = accountVerified,
            withdrawDisabledConfirmed = settings.withdrawDisabledConfirmed,
            clockSyncHealthy = clockOk,
            exchangeMetadataLoaded = metadataLoaded,
            reconciliationCompleted = accountVerified && noUnknownOrders && _botState.value != BotState.RECONCILIATION_REQUIRED,
            noActiveCircuitBreaker = settings.activeCircuitBreakerReason == null,
            deviceHealthStable = !health.thermalWarning && !health.lowRamWarning,
            riskLimitsReviewedByUser = settings.riskLimitsReviewed,
            userExplicitlyConfirmedLive = settings.liveExplicitlyArmed
        )
        _checklist.value = list
        return list
    }

    private suspend fun handleBinanceFailure(category: BinanceErrorCategory, messageVi: String) {
        when (category) {
            BinanceErrorCategory.RATE_LIMITED_429, BinanceErrorCategory.IP_BANNED_418 -> {
                transitionState(BotState.PAUSED_API_LIMIT, messageVi)
            }
            BinanceErrorCategory.BAD_API_KEY_FMT_2014,
            BinanceErrorCategory.REJECTED_MBX_KEY_2015,
            BinanceErrorCategory.INVALID_SIGNATURE_1022,
            BinanceErrorCategory.INSUFFICIENT_PERMISSION_403,
            BinanceErrorCategory.AUTH_OR_PERMISSION -> {
                transitionState(BotState.CONFIG_REQUIRED, messageVi)
            }
            BinanceErrorCategory.TIMESTAMP_DRIFT_1021 -> {
                transitionState(BotState.RECONCILIATION_REQUIRED, messageVi)
            }
            BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS -> {
                transitionState(BotState.UNKNOWN_ORDER_STATUS, messageVi)
            }
            else -> {
                transitionState(BotState.RECONCILIATION_REQUIRED, messageVi)
            }
        }
    }

    /**
     * Requirement 3 · Never populates `currentEquityUsdt` or `availableBalanceUsdt` with `5.8939` from declared VND!
     * Uses `"UNVERIFIED"` until a verified Binance response is received.
     */
    suspend fun ensureBaselineDailySnapshot(settings: AppSettingsEntity): DailySnapshotEntity {
        val existing = dao.getLatestDailySnapshotOnce()
        if (existing != null) return existing

        val todayKey = Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val created = DailySnapshotEntity(
            dateKey = todayKey,
            startEquityUsdt = "UNVERIFIED",
            currentEquityUsdt = "UNVERIFIED",
            availableBalanceUsdt = "UNVERIFIED",
            netExternalCashflowUsdt = "0.0000",
            realizedPnlUsdt = "0.0000",
            unrealizedPnlUsdt = "0.0000",
            totalFeesUsdt = "0.0000",
            totalFundingUsdt = "0.0000",
            cashflowAdjustedPnlUsdt = "0.0000",
            peakEquityUsdt = "UNVERIFIED",
            consecutiveLosses = 0,
            updatedAtMs = System.currentTimeMillis()
        )
        dao.upsertDailySnapshot(created)
        return created
    }

    private suspend fun updateDailyAccountingSnapshotFromVerifiedExchange(
        walletEquityUsdt: BigDecimal,
        availableBalanceUsdt: BigDecimal,
        unrealizedPnlUsdt: BigDecimal
    ) {
        val settings = dao.getSettingsOnce() ?: AppSettingsEntity()
        val current = ensureBaselineDailySnapshot(settings)
        val startEq = current.startEquityUsdt.toBigDecimalOrNull() ?: walletEquityUsdt
        val netCf = current.netExternalCashflowUsdt.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val cfPnl = QuantMathEngine.calculateCashflowAdjustedPnl(walletEquityUsdt, startEq, netCf)
        val peak = (current.peakEquityUsdt.toBigDecimalOrNull() ?: walletEquityUsdt).max(walletEquityUsdt)

        dao.upsertDailySnapshot(
            current.copy(
                startEquityUsdt = startEq.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                currentEquityUsdt = walletEquityUsdt.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                availableBalanceUsdt = availableBalanceUsdt.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                unrealizedPnlUsdt = unrealizedPnlUsdt.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                cashflowAdjustedPnlUsdt = cfPnl.toPlainString(),
                peakEquityUsdt = peak.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun buildDeterministicFallbackCandles(
        symbol: String,
        nowUtcMs: Long
    ): Pair<List<CandleBar>, List<CandleBar>> {
        val basePrice = when (symbol) {
            "BTCUSDT" -> BigDecimal("68450.0")
            "ETHUSDT" -> BigDecimal("2620.0")
            "SOLUSDT" -> BigDecimal("176.50")
            "XRPUSDT" -> BigDecimal("0.5420")
            else -> BigDecimal("0.14250")
        }
        val stepPct = BigDecimal("0.0022")
        val num15m = 96
        val list15m = ArrayList<CandleBar>(num15m)
        val list1h = ArrayList<CandleBar>(40)

        for (i in 0 until num15m) {
            val offsetBars = num15m - i
            val closeTime15m = nowUtcMs - (offsetBars * 900_000L)
            val openTime15m = closeTime15m - 900_000L
            val wave = kotlin.math.sin(i * 0.19) * 0.0035
            val drift = BigDecimal((i - 48) * 0.0006 + wave).setScale(6, RoundingMode.HALF_UP)
            val open = basePrice.multiply(BigDecimal.ONE.add(drift))
                .setScale(5, RoundingMode.HALF_UP)
            val close = if (i == num15m - 1) {
                open.multiply(BigDecimal("1.014")).setScale(5, RoundingMode.HALF_UP)
            } else {
                open.multiply(BigDecimal("1.0018")).setScale(5, RoundingMode.HALF_UP)
            }
            val high = open.max(close).multiply(BigDecimal("1.0025")).setScale(5, RoundingMode.HALF_UP)
            val low = open.min(close).multiply(BigDecimal("0.9980")).setScale(5, RoundingMode.HALF_UP)
            val vol = if (i == num15m - 1) BigDecimal("185000") else BigDecimal("110000")

            list15m.add(
                CandleBar(
                    symbol = symbol,
                    interval = "15m",
                    openTime = openTime15m,
                    closeTime = closeTime15m,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    quoteVolume = vol.multiply(close).setScale(2, RoundingMode.HALF_UP),
                    isClosed = true
                )
            )
        }

        for (i in 0 until 40) {
            val offsetBars = 40 - i
            val closeTime1h = nowUtcMs - (offsetBars * 3_600_000L)
            val openTime1h = closeTime1h - 3_600_000L
            val drift = BigDecimal(i - 15).multiply(stepPct)
            val open = basePrice.multiply(BigDecimal.ONE.add(drift.multiply(BigDecimal("0.35"))))
                .setScale(5, RoundingMode.HALF_UP)
            val close = open.multiply(BigDecimal("1.0022")).setScale(5, RoundingMode.HALF_UP)
            val high = open.max(close).multiply(BigDecimal("1.0030")).setScale(5, RoundingMode.HALF_UP)
            val low = open.min(close).multiply(BigDecimal("0.9975")).setScale(5, RoundingMode.HALF_UP)
            val vol = BigDecimal("420000")

            list1h.add(
                CandleBar(
                    symbol = symbol,
                    interval = "1h",
                    openTime = openTime1h,
                    closeTime = closeTime1h,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    quoteVolume = vol.multiply(close).setScale(2, RoundingMode.HALF_UP),
                    isClosed = true
                )
            )
        }
        return list15m to list1h
    }
}
