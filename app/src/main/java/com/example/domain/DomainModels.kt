package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode

enum class EnvironmentMode(val labelVi: String) {
    TEST("TEST (Kiểm thử an toàn)"),
    LIVE("LIVE (Tài khoản thật)")
}

enum class DataTrustState(val badgeVi: String, val allowsPaperExecution: Boolean, val allowsLiveExecution: Boolean) {
    EMPTY("EMPTY · Trống xác thực (Khởi tạo mới)", false, false),
    BOOTSTRAPPING("BOOTSTRAPPING · Đang nạp dữ liệu Binance", false, false),
    VALIDATING("VALIDATING · Đang kiểm định chuỗi nến", false, false),
    SHADOW_READY("SHADOW_READY · Đủ dữ liệu chạy Shadow", false, false),
    PAPER_READY("PAPER_READY · Đủ điều kiện Paper/Demo", true, false),
    LIVE_ELIGIBLE("LIVE_ELIGIBLE · Đạt chuẩn dữ liệu thật", true, true),
    QUARANTINED("QUARANTINED · Cách ly dữ liệu lỗi/giả lập", false, false)
}

enum class SymbolModelEligibility(val badgeVi: String, val allowsNeuralAdvisory: Boolean) {
    FULL_SUPPORTED("FULL_SUPPORTED · Đã hiệu chuẩn riêng", true),
    POOLED_MODEL_ONLY("POOLED_MODEL_ONLY · Mô hình gộp đa cặp", true),
    BASELINE_ONLY("BASELINE_ONLY · Chỉ dùng chiến lược định lượng", false),
    UNSUPPORTED("UNSUPPORTED · Chưa hỗ trợ / OOD", false)
}

enum class MarketDataEndpoint {
    MAINNET_PUBLIC,
    TESTNET
}

enum class AccountReadEndpoint {
    DISABLED,
    MAINNET_READONLY,
    TESTNET_READONLY
}

enum class OrderExecutionEndpoint {
    SIMULATED_LOCAL,
    TESTNET_EXECUTION,
    MAINNET_LIVE_EXECUTION
}

data class ExecutionCapability(
    val mode: EnvironmentMode = EnvironmentMode.TEST,
    val marketDataEndpoint: MarketDataEndpoint = MarketDataEndpoint.MAINNET_PUBLIC,
    val accountReadEndpoint: AccountReadEndpoint = AccountReadEndpoint.MAINNET_READONLY,
    val orderExecutionEndpoint: OrderExecutionEndpoint = OrderExecutionEndpoint.SIMULATED_LOCAL,
    val canSendLiveOrders: Boolean = false,
    val safetyDebugLockActive: Boolean = true
)

enum class BotState(val labelVi: String, val isErrorOrPaused: Boolean = false) {
    UNINITIALIZED("Chưa khởi tạo", true),
    CONFIG_REQUIRED("Cần cấu hình API", true),
    CONNECTIVITY_CHECK("Kiểm tra kết nối", false),
    ACCOUNT_RECONCILIATION("Đang đối soát tài khoản", false),
    READY_TEST("Sẵn sàng chế độ TEST", false),
    LIVE_ARMING("Đang kiểm tra mở khóa LIVE", false),
    SCANNING("Đang quét thị trường", false),
    CANDIDATE_REVIEW("Đang đánh giá ứng viên", false),
    RISK_CHECK("Đang kiểm tra Risk Gate", false),
    ORDER_INTENT_PERSISTED("Đã lưu Order Intent bền vững", false),
    ENTRY_PENDING("Đang chờ phản hồi lệnh Entry", false),
    UNKNOWN_ORDER_STATUS("Trạng thái lệnh UNKNOWN - Chờ đối soát", true),
    PARTIALLY_FILLED("Khớp một phần - Đặt SL ngay", false),
    FILLED("Đã khớp Entry", false),
    PROTECTION_PENDING("Đang đặt lệnh bảo vệ SL/TP trên sàn", false),
    POSITION_PROTECTED("Vị thế đã có SL bảo vệ trên sàn", false),
    PROTECTION_FAILURE("Lỗi đặt SL bảo vệ - Đóng khẩn cấp!", true),
    POSITION_MANAGEMENT("Đang quản lý vị thế mở", false),
    EXIT_PENDING("Đang đóng vị thế", false),
    POSITION_CLOSED_RECONCILIATION("Đối soát sau khi đóng vị thế", false),
    CLEANUP("Dọn dẹp lệnh mồ côi", false),
    PAUSED_USER("Tạm dừng bởi người dùng", true),
    PAUSED_RISK_LIMIT("Tạm dừng do chạm ngưỡng rủi ro", true),
    PAUSED_API_LIMIT("Tạm dừng do giới hạn Rate Limit/418", true),
    PAUSED_DATA_STALE("Tạm dừng do dữ liệu trễ/cũ", true),
    PAUSED_DEVICE_HEALTH("Tạm dừng do nhiệt độ/RAM S7 Edge", true),
    PAUSED_PROVIDER_FAILURE("Tạm dừng do lỗi AI Provider", true),
    PAUSED_FOR_CASHFLOW("Tạm dừng đối soát nạp/rút", true),
    RECONCILIATION_REQUIRED("Yêu cầu đối soát 3 nguồn", true),
    DRAINING("Draining: Hủy chờ, giữ SL vị thế mở", true),
    HALTED_REVIEW("Dừng khẩn cấp chờ kiểm tra", true),
    STOPPED("Đã dừng dịch vụ", true)
}

object BotStateMachine {
    private val globalFallbackTargets = setOf(
        BotState.PAUSED_USER,
        BotState.PAUSED_RISK_LIMIT,
        BotState.PAUSED_API_LIMIT,
        BotState.PAUSED_DATA_STALE,
        BotState.PAUSED_DEVICE_HEALTH,
        BotState.PAUSED_PROVIDER_FAILURE,
        BotState.PAUSED_FOR_CASHFLOW,
        BotState.RECONCILIATION_REQUIRED,
        BotState.DRAINING,
        BotState.HALTED_REVIEW,
        BotState.STOPPED,
        BotState.ACCOUNT_RECONCILIATION,
        BotState.READY_TEST
    )

    fun canTransition(from: BotState, to: BotState): Boolean {
        if (from == to) return true
        if (to in globalFallbackTargets) return true
        return when (from) {
            BotState.UNINITIALIZED -> true
            BotState.READY_TEST, BotState.SCANNING, BotState.CANDIDATE_REVIEW -> true
            BotState.RISK_CHECK -> to in setOf(BotState.ORDER_INTENT_PERSISTED, BotState.READY_TEST, BotState.SCANNING)
            BotState.ORDER_INTENT_PERSISTED -> to in setOf(BotState.ENTRY_PENDING, BotState.UNKNOWN_ORDER_STATUS, BotState.FILLED, BotState.PARTIALLY_FILLED)
            BotState.ENTRY_PENDING -> to in setOf(BotState.FILLED, BotState.PARTIALLY_FILLED, BotState.UNKNOWN_ORDER_STATUS, BotState.READY_TEST)
            BotState.FILLED, BotState.PARTIALLY_FILLED -> to in setOf(BotState.PROTECTION_PENDING, BotState.POSITION_PROTECTED, BotState.PROTECTION_FAILURE)
            BotState.PROTECTION_PENDING -> to in setOf(BotState.POSITION_PROTECTED, BotState.PROTECTION_FAILURE)
            BotState.POSITION_PROTECTED, BotState.POSITION_MANAGEMENT -> to in setOf(BotState.POSITION_MANAGEMENT, BotState.EXIT_PENDING, BotState.POSITION_CLOSED_RECONCILIATION)
            else -> true
        }
    }
}

enum class TradeSide {
    LONG,
    SHORT,
    NONE
}

enum class PositionSideMode {
    ONE_WAY,
    HEDGE
}

enum class PositionProtectionState {
    NONE,
    PENDING,
    VERIFIED_ON_EXCHANGE,
    FAILED_EMERGENCY_CLOSE
}

enum class RegimeLabel {
    TREND_UP,
    TREND_DOWN,
    RANGE_CHOP,
    HIGH_VOLATILITY,
    BREAKOUT_EXPANSION,
    LOW_LIQUIDITY,
    EVENT_ALERT,
    COOLDOWN
}

enum class DataSourceType(val badgeVi: String, val isTrustedExchangeFeed: Boolean) {
    BINANCE_PUBLIC_REST("Binance USDⓈ-M Public REST (Thật)", true),
    BINANCE_FUTURES_WS("Binance USDⓈ-M WebSocket (Thật)", true),
    BINANCE_AUTH_ACCOUNT("Binance Signed /fapi/v2/account (Thật)", true),
    REFERENCE_DECLARED_CAPITAL("Vốn khai báo tham khảo (Chỉ tính thử TEST)", false),
    DEMO_SIMULATED("TEST_FIXTURE / Giả lập kiểm thử (Cách ly khỏi LIVE)", false),
    UNVERIFIED("Chưa xác minh (UNVERIFIED)", false)
}

enum class AccountVerificationState(val badgeVi: String, val isVerified: Boolean) {
    UNVERIFIED("UNVERIFIED · Chưa xác minh sàn", false),
    VERIFYING("Đang kiểm tra /fapi/v2/account...", false),
    VERIFIED_OK("VERIFIED · Đã xác minh từ Binance", true),
    AUTH_FAILED("Lỗi xác thực API (-2015/-1022)", false),
    CLOCK_DRIFT_FAILED("Lỗi lệch đồng hồ (-1021)", false),
    NETWORK_FAILED("Lỗi mạng khi gọi Binance", false)
}

enum class VaultCredentialState(val badgeVi: String) {
    EMPTY("Chưa lưu API Key"),
    SAVED_VERIFIED("Đã lưu & khớp SHA-256 trong Vault"),
    DIGEST_MISMATCH("Lỗi lệch SHA-256 trong Vault"),
    INVALID_FORMAT("Định dạng Key/Secret không hợp lệ")
}

data class AccountCapitalState(
    val exchangeEquityUsdt: BigDecimal? = null,
    val exchangeAvailableBalanceUsdt: BigDecimal? = null,
    val exchangeUnrealizedPnlUsdt: BigDecimal? = null,
    val verificationState: AccountVerificationState = AccountVerificationState.UNVERIFIED,
    val dataSource: DataSourceType = DataSourceType.UNVERIFIED,
    val verifiedAtUtcMs: Long? = null,
    val declaredCapitalVnd: Long = 500_000L,
    val referenceUsdtVndRate: BigDecimal = BigDecimal("25500"),
    val vaultCredentialState: VaultCredentialState = VaultCredentialState.EMPTY,
    val activeEndpointTarget: String = "MAINNET (fapi.binance.com)",
    val lastFailedStep: String? = null,
    val lastFailedEndpoint: String? = null,
    val lastFailedBinanceCode: Int? = null,
    val lastRawBinanceErrorMsg: String? = null,
    val dataTrustState: DataTrustState = DataTrustState.EMPTY
) {
    val estimatedCapitalFromVndUsdt: BigDecimal
        get() = if (referenceUsdtVndRate > BigDecimal.ZERO) {
            BigDecimal.valueOf(declaredCapitalVnd).divide(referenceUsdtVndRate, 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal("19.61")
        }
}

enum class CapitalTier(val labelVi: String) {
    TIER_MICRO("Tier Micro (< 50 USDT)"),
    TIER_SMALL("Tier Small (50 - 250 USDT)"),
    TIER_GROWTH("Tier Growth (> 250 USDT)")
}

enum class AiModuleStatus(val labelVi: String) {
    NOT_CONFIGURED("Chưa cấu hình AI Provider (Đang chạy Deterministic Baseline)"),
    READY_VERIFIED("AI Provider đã xác minh Schema JSON"),
    DEGRADED_FALLBACK("AI lỗi/timeout · Đã chuyển về Deterministic Baseline"),
    DISABLED("AI đang tắt · Chỉ dùng Deterministic Rules")
}

data class CandleBar(
    val symbol: String,
    val interval: String,
    val openTime: Long,
    val closeTime: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val quoteVolume: BigDecimal,
    val isClosed: Boolean = true
)

data class SymbolFilter(
    val symbol: String,
    val status: String = "TRADING",
    val contractType: String = "PERPETUAL",
    val quoteAsset: String = "USDT",
    val tickSize: BigDecimal,
    val stepSize: BigDecimal,
    val minQty: BigDecimal,
    val maxQty: BigDecimal = BigDecimal("1000000"),
    val marketStepSize: BigDecimal = stepSize,
    val marketMinQty: BigDecimal = minQty,
    val minNotional: BigDecimal,
    val maxLeverage: Int = 20,
    val maintMarginRatio: BigDecimal = BigDecimal("0.025")
) {
    val isEligiblePerpetual: Boolean
        get() = status == "TRADING" &&
            contractType == "PERPETUAL" &&
            quoteAsset == "USDT" &&
            tickSize > BigDecimal.ZERO &&
            stepSize > BigDecimal.ZERO &&
            minQty > BigDecimal.ZERO &&
            minNotional > BigDecimal.ZERO
}

data class FeatureSnapshot(
    val symbol: String,
    val asOfUtcMs: Long,
    val featureVersion: String = "v5.2-blankslate",
    val snapshotHash: String,
    val dataSource: DataSourceType,
    val lastPrice: BigDecimal,
    val markPrice: BigDecimal,
    val indexPrice: BigDecimal,
    val spreadPct: BigDecimal,
    val return1m: BigDecimal,
    val return5m: BigDecimal,
    val return15m: BigDecimal,
    val return1h: BigDecimal,
    val ema20_15m: BigDecimal,
    val ema50_15m: BigDecimal,
    val ema20_1h: BigDecimal,
    val ema50_1h: BigDecimal,
    val emaSlope15m: BigDecimal,
    val atr14: BigDecimal,
    val atrPct: BigDecimal,
    val rollingReturnStd15m: BigDecimal,
    val ewmaVolatility15m: BigDecimal,
    val robustReturnZScore: BigDecimal,
    val relativeVolume: BigDecimal,
    val upperWickRatio: BigDecimal,
    val lowerWickRatio: BigDecimal,
    val bodyToRangeRatio: BigDecimal,
    val closedRangeHigh20: BigDecimal,
    val breakoutUp: Boolean,
    val breakoutDown: Boolean,
    val closedRangeLow20: BigDecimal,
    val topBookBidQty: BigDecimal,
    val topBookAskQty: BigDecimal,
    val orderBookImbalance: BigDecimal,
    val microprice: BigDecimal,
    val markIndexBasisBps: BigDecimal,
    val fundingRate: BigDecimal,
    val nextFundingTimeMs: Long,
    val dataAgeMs: Long,
    val isDataStale: Boolean,
    val isEstimated: Boolean,
    val regimes: Set<RegimeLabel>,
    val primaryRegime: RegimeLabel
)

data class NetEvEstimate(
    val pWinCalibrated: BigDecimal,
    val avgWinPct: BigDecimal,
    val avgLossPct: BigDecimal,
    val entryFeePct: BigDecimal,
    val exitFeePct: BigDecimal,
    val expectedSlippagePct: BigDecimal,
    val expectedFundingPct: BigDecimal,
    val safetyBufferPct: BigDecimal,
    val netEvPct: BigDecimal,
    val netEvR: BigDecimal = BigDecimal.ZERO,
    val isEstimatedPrior: Boolean,
    val sampleCount: Int,
    val canUseForLive: Boolean,
    val noteVi: String
)

data class SizingDecision(
    val approved: Boolean,
    val symbol: String,
    val side: TradeSide,
    val capitalSource: DataSourceType,
    val capitalTier: CapitalTier,
    val equityUsedUsdt: BigDecimal,
    val riskFraction: BigDecimal,
    val riskBudgetUsdt: BigDecimal,
    val entryPrice: BigDecimal,
    val stopPrice: BigDecimal,
    val targetPrice: BigDecimal,
    val rawQty: BigDecimal,
    val roundedQty: BigDecimal,
    val notionalUsdt: BigDecimal,
    val recommendedLeverage: Int,
    val estimatedInitialMarginUsdt: BigDecimal,
    val estimatedLossAtStopUsdt: BigDecimal,
    val estimatedFeeAndSlippageUsdt: BigDecimal,
    val totalEstimatedLossAtStopUsdt: BigDecimal,
    val estimatedLiquidationBufferPct: BigDecimal,
    val netBreakevenPrice: BigDecimal,
    val rejectReasonCode: String,
    val rejectReasonVi: String
)

enum class SkepticVerdict {
    PASS,
    CAUTION_REDUCE_SIZE,
    REJECT
}

data class SkepticCheckResult(
    val verdict: SkepticVerdict,
    val objections: List<String>,
    val riskReductionFactor: BigDecimal,
    val summaryVi: String
)

data class AiCandidatePlan(
    val decisionId: String,
    val symbol: String,
    val direction: TradeSide,
    val confidenceScore: BigDecimal,
    val recommendation: String,
    val reasonCodes: List<String>,
    val supportingEvidenceIds: List<String>,
    val validUntilUtcMs: Long
)

data class AiSkepticReport(
    val decisionId: String,
    val symbol: String,
    val verdict: SkepticVerdict,
    val contradictions: List<String>,
    val missingData: List<String>,
    val validUntilUtcMs: Long
)

data class ExecutionGateDecision(
    val approved: Boolean,
    val decisionId: String,
    val signalId: String,
    val sizingId: String,
    val riskApprovalId: String,
    val symbol: String,
    val check1SignalExists: Boolean = false,
    val check2SignalNotExpired: Boolean = false,
    val check3SignalValidated: Boolean = false,
    val check4SkepticNotRejected: Boolean = false,
    val check5InputDataFreshAndComplete: Boolean = false,
    val check6AiReviewValidForMode: Boolean = false,
    val check7EvValidAndMeetsThreshold: Boolean = false,
    val check8PositionSizingApprovedAndLinked: Boolean = false,
    val check9RiskEngineApproved: Boolean = false,
    val check10ExchangeMetadataVerified: Boolean = false,
    val check11AccountStateVerified: Boolean = false,
    val check12NoConflictingOrUnknownOrders: Boolean = false,
    val check13WithinPositionAndExposureLimits: Boolean = false,
    val check14MarketSlippageWithinLimit: Boolean = false,
    val check15ApiAndClockSyncHealthy: Boolean = false,
    val check16TradingModePermitsExecution: Boolean = false,
    val failedChecks: List<String> = emptyList(),
    val rejectReasonCode: String = "NONE",
    val rejectReasonVi: String = "",
    val evaluatedAtUtcMs: Long = System.currentTimeMillis()
)

data class LiveActivationChecklist(
    val buildFingerprint: String = "astra-v5.2-blankslate",
    val safetyDebugLockDisengaged: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val accountVerifiedFromBinance: Boolean = false,
    val withdrawDisabledConfirmed: Boolean = false,
    val exchangeMetadataLoaded: Boolean = false,
    val clockSyncHealthy: Boolean = false,
    val reconciliationCompleted: Boolean = false,
    val noActiveCircuitBreaker: Boolean = true,
    val deviceHealthStable: Boolean = true,
    val riskLimitsReviewedByUser: Boolean = false,
    val userExplicitlyConfirmedLive: Boolean = false
) {
    val canEnableLive: Boolean
        get() = safetyDebugLockDisengaged &&
            apiKeyConfigured &&
            accountVerifiedFromBinance &&
            withdrawDisabledConfirmed &&
            exchangeMetadataLoaded &&
            clockSyncHealthy &&
            reconciliationCompleted &&
            noActiveCircuitBreaker &&
            deviceHealthStable &&
            riskLimitsReviewedByUser &&
            userExplicitlyConfirmedLive
}

data class ScannerCandidateItem(
    val symbol: String,
    val quoteVolume24hUsdt: BigDecimal,
    val spreadPct: BigDecimal,
    val atrPct: BigDecimal,
    val relativeVolume: BigDecimal,
    val minNotionalUsdt: BigDecimal,
    val scannerScore: BigDecimal,
    val feasibleForCurrentCapital: Boolean,
    val modelEligibility: SymbolModelEligibility = SymbolModelEligibility.BASELINE_ONLY,
    val noteVi: String
)

data class BacktestSummary(
    val symbol: String,
    val totalCandles: Int,
    val totalTrades: Int,
    val winCount: Int,
    val lossCount: Int,
    val winRatePct: BigDecimal,
    val grossReturnPct: BigDecimal,
    val totalFeesAndFundingPct: BigDecimal,
    val netReturnPct: BigDecimal,
    val maxDrawdownPct: BigDecimal,
    val profitFactor: BigDecimal,
    val brierScore: BigDecimal,
    val sampleQualification: String = if (totalTrades >= 30) "QUALIFIED" else "INSUFFICIENT_SAMPLE",
    val isFixtureData: Boolean = true,
    val summaryVi: String
)

data class ProbabilityCalibrationReport(
    val sampleCount: Int,
    val brierScore: BigDecimal,
    val empiricalWinRate: BigDecimal,
    val calibratedPWin: BigDecimal,
    val isStatisticallyQualified: Boolean,
    val noteVi: String
)

data class PerformanceSelfMonitoringReport(
    val evaluatedTradesCount: Int,
    val recentWinRatePct: BigDecimal,
    val averageSlippageBps: BigDecimal,
    val feeToGrossPnlRatioPct: BigDecimal,
    val expectancyDriftTriggered: Boolean,
    val recommendedActionVi: String
)

enum class NewsEventCategory(val labelVi: String) {
    EXCHANGE_RISK("Rủi ro sàn / Bảo trì / Delist"),
    SECURITY_HACK("Sự cố bảo mật / Exploit"),
    MACRO_RELEASE("Tin vĩ mô (CPI / FOMC)"),
    REGULATORY("Pháp lý / Cơ quan quản lý"),
    MARKET_RUMOR("Tin đồn chưa xác minh"),
    GENERAL_CRYPTO("Tin tức thị trường chung")
}

enum class NewsVerificationStatus(val labelVi: String) {
    VERIFIED_OFFICIAL("Đã xác minh từ nguồn chính thức"),
    CORROBORATED_MULTI_SOURCE("Nhiều nguồn uy tín xác nhận"),
    UNVERIFIED_RUMOR("Tin đồn chưa kiểm chứng (UNVERIFIED)")
}

data class NewsEventItem(
    val eventId: String,
    val symbol: String,
    val headline: String,
    val sourceName: String,
    val canonicalUrl: String = "",
    val severityScore: Int,
    val verificationStatus: NewsVerificationStatus,
    val category: NewsEventCategory,
    val sentimentPolarity: Double,
    val publishedAtUtcMs: Long
)

data class CrowdAttentionProxySnapshot(
    val symbol: String,
    val volumeSpikeZScore: BigDecimal,
    val fundingExtremeFlag: Boolean,
    val basisDivergenceFlag: Boolean,
    val attentionLevel: String,
    val gateDecisionVi: String
)

data class DeviceHealthSnapshot(
    val batteryPct: Int = 85,
    val isCharging: Boolean = true,
    val batteryTempCelsius: Float = 32.0f,
    val availableRamMb: Long = 1480L,
    val kernelTotalRamMb: Long = 3720L,
    val physicalNominalRamGb: Int = 4,
    val memInfoSource: String = "ActivityManager + /proc/meminfo",
    val thermalWarning: Boolean = false,
    val lowRamWarning: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = true,
    val backgroundRestricted: Boolean = false,
    val storageFreeMb: Long = 24_500L
)

enum class PanicCloseStage {
    IDLE,
    LOCKING_NEW_ENTRIES,
    VERIFYING_EXCHANGE_EXPOSURE,
    SUBMITTING_REDUCE_ONLY_CLOSE,
    CANCELLING_ORPHAN_ORDERS,
    RECONCILING_POST_CLOSE,
    COMPLETED,
    FAILED_MANUAL_INTERVENTION_REQUIRED
}

enum class PanicPreCloseVerificationState {
    VERIFIED_OPEN_EXPOSURE,
    VERIFIED_ALREADY_FLAT,
    EXCHANGE_QUERY_FAILED
}

data class PanicCloseReport(
    val executedAtUtcMs: Long,
    val finalStage: PanicCloseStage,
    val preCloseVerification: PanicPreCloseVerificationState,
    val closedSymbols: List<String>,
    val cancelledOrderIds: List<String>,
    val verifiedFlatAfterClose: Boolean,
    val summaryVi: String
)

enum class FillPnlStatus(val code: String, val labelVi: String) {
    VERIFIED("VERIFIED", "Đã xác minh từ userTrades Binance"),
    ESTIMATED("ESTIMATED", "Ước tính cục bộ (TEST/DEMO)"),
    ACCOUNTING_PENDING("ACCOUNTING_PENDING", "Đang chờ đồng bộ PnL"),
    PNL_UNVERIFIED("PNL_UNVERIFIED", "Chưa xác minh được PnL từ sàn")
}

data class FundingIncomeRecord(
    val tranId: String,
    val symbol: String,
    val incomeType: String = "FUNDING_FEE",
    val incomeUsdt: BigDecimal,
    val asset: String = "USDT",
    val timeMs: Long
)

data class ExchangeUserTradeRecord(
    val tradeId: String,
    val orderId: String,
    val symbol: String,
    val side: String,
    val price: BigDecimal,
    val qty: BigDecimal,
    val realizedPnl: BigDecimal,
    val commission: BigDecimal,
    val commissionAsset: String,
    val timeMs: Long
)

data class OutboundNetworkIdentity(
    val activeTransport: String = "WIFI",
    val publicIpv4: String? = null,
    val publicIpv6: String? = null,
    val natDetected: Boolean = false,
    val vpnActive: Boolean = false,
    val proxyConfigured: Boolean = false,
    val dnsServers: List<String> = emptyList(),
    val resolvedBinanceIps: List<String> = emptyList(),
    val checkedAtUtcMs: Long = System.currentTimeMillis(),
    val summaryVi: String = "Chưa kiểm tra IP outbound"
)

data class BinanceRequestInspectionRecord(
    val product: String,
    val environment: String,
    val host: String,
    val httpMethod: String,
    val endpointPath: String,
    val apiKeyLoadedFromVault: Boolean,
    val apiKeyAttachedToHeader: Boolean,
    val hmacSignatureAttachedToQuery: Boolean,
    val boundToSameAndroidNetwork: Boolean,
    val httpStatus: Int,
    val binanceErrorCode: Int?,
    val rawServerMessage: String,
    val checkedAtUtcMs: Long = System.currentTimeMillis()
)

data class BinanceAuthDiagnosticStepResult(
    val stepIndex: Int,
    val stepCode: String,
    val endpointCalled: String,
    val passed: Boolean,
    val httpCode: Int = 200,
    val binanceErrorCode: Int? = null,
    val rawServerMessage: String = "",
    val detailVi: String
)

data class BinanceAuthDiagnosticReport(
    val ranAtUtcMs: Long = System.currentTimeMillis(),
    val targetEndpointMode: String = "MAINNET",
    val fapiBaseUrl: String = "https://fapi.binance.com",
    val vaultCredentialState: VaultCredentialState = VaultCredentialState.EMPTY,
    val accountVerificationState: AccountVerificationState = AccountVerificationState.UNVERIFIED,
    val keySavedAtUtcMs: Long = 0L,
    val keyVaultDigestMatch: Boolean = false,
    val secretVaultDigestMatch: Boolean = false,
    val headerExactMatchVerified: Boolean = false,
    val futuresBalanceAccepted: Boolean = false,
    val futuresAccountAccepted: Boolean = false,
    val outboundNetwork: OutboundNetworkIdentity = OutboundNetworkIdentity(),
    val clockOffsetMs: Long = 0L,
    val clockRttMs: Long = 0L,
    val spotKeyAcceptedOnMainnet: Boolean? = null,
    val spotApiRestrictionsIpRestrict: Boolean? = null,
    val spotApiRestrictionsEnableReading: Boolean? = null,
    val spotApiRestrictionsEnableFutures: Boolean? = null,
    val spotApiKeyCreatedAtMs: Long? = null,
    val testnetKeyAccepted: Boolean? = null,
    val ipProbeAndBinanceOnSameNetwork: Boolean = true,
    val ipv4PreferredEnforced: Boolean = true,
    val failedStepCode: String? = null,
    val failedEndpoint: String? = null,
    val failedHttpStatus: Int? = null,
    val failedBinanceErrorCode: Int? = null,
    val failedRawServerMessage: String? = null,
    val provenCausesVi: List<String> = emptyList(),
    val remainingHypothesesVi: List<String> = emptyList(),
    val steps: List<BinanceAuthDiagnosticStepResult> = emptyList(),
    val requestInspections: List<BinanceRequestInspectionRecord> = emptyList()
)

enum class CapabilityPriority(val badge: String) {
    V1_BASELINE("P0 · Bắt buộc V1"),
    OPTIONAL_VALIDATED("P1 · Đã kiểm증"),
    RESEARCH_ONLY("P2 · Chỉ nghiên cứu"),
    NOT_SUPPORTED_BY_EVIDENCE("Loại bỏ")
}

enum class ImplementationStatus(val badgeVi: String) {
    IMPLEMENTED("IMPLEMENTED_AND_TESTED"),
    PARTIAL("PARTIAL"),
    NOT_IMPLEMENTED("DEFERRED_P2")
}

data class CapabilityEntry(
    val methodId: String,
    val titleVi: String,
    val categoryVi: String,
    val moduleOwner: String,
    val priority: CapabilityPriority,
    val status: ImplementationStatus,
    val enabledInV1: Boolean,
    val descriptionVi: String
)

object AstraCapabilityCatalog {
    val entries: List<CapabilityEntry> = listOf(
        CapabilityEntry(
            methodId = "DATA-01",
            titleVi = "Blank-Slate Data Trust & Quarantine",
            categoryVi = "Data Provenance",
            moduleOwner = "DataTrustManager",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Cách ly toàn bộ dữ liệu mẫu/golden_fixture khỏi lịch sử giao dịch và hiệu chuẩn LIVE."
        ),
        CapabilityEntry(
            methodId = "STRAT-01",
            titleVi = "1h Regime + 15m Breakout + RVOL + Retest",
            categoryVi = "Quantitative Strategy",
            moduleOwner = "QuantMathEngine",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Chiến lược cơ sở duy nhất: lọc xu hướng 1h/4h, xác nhận đóng nến vượt biên 20 nến kèm RVOL và kiểm tra dải giá vào."
        ),
        CapabilityEntry(
            methodId = "RISK-01",
            titleVi = "Deterministic Risk Sizing & Portfolio Risk Manager",
            categoryVi = "Risk Management",
            moduleOwner = "PortfolioRiskManager",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Tính khối lượng từ ngân sách lỗ R = E * r; từ chối lệnh khi minNotional vượt R; kiểm soát rủi ro danh mục."
        ),
        CapabilityEntry(
            methodId = "FCST-01",
            titleVi = "Compact Kotlin INT8 GRU+TCN Shadow Forecast & Head Gate",
            categoryVi = "On-Device Forecast",
            moduleOwner = "CompactKotlinForecastRuntime",
            priority = CapabilityPriority.OPTIONAL_VALIDATED,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Suy luận đa khung 15m/1h/4h với kiểm tra nhất quán giữa đầu phân loại hướng và đầu hồi quy lợi nhuận."
        )
    )
}
