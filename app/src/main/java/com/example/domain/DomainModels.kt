package com.example.domain

import java.math.BigDecimal

enum class EnvironmentMode {
    TEST,
    LIVE
}

enum class DataSourceType(val badgeVi: String) {
    BINANCE_LIVE_VERIFIED("BINANCE LIVE (ĐÃ XÁC MINH)"),
    BINANCE_TESTNET_VERIFIED("BINANCE TESTNET (ĐÃ XÁC MINH)"),
    BINANCE_PUBLIC_REST("BINANCE PUBLIC REST"),
    DEMO_SIMULATED("DEMO / SIMULATED (GIẢ LẬP NỘI BỘ)"),
    UNVERIFIED_UNKNOWN("UNVERIFIED / UNKNOWN (CHƯA XÁC MINH)")
}

/**
 * Requirement 6 · Strictly separates local Vault storage status ("Đã lưu credential trong Vault")
 * from remote Binance authentication verification status ("Đã xác minh credential với Binance").
 */
enum class VaultCredentialState(val badgeVi: String, val isStoredAndValidInVault: Boolean) {
    EMPTY_VAULT("CHƯA LƯU CREDENTIAL TRONG VAULT", false),
    VAULT_INTEGRITY_ERROR("LỖI GIẢI MÃ / TOÀN VẸN TRONG VAULT", false),
    SAVED_IN_VAULT_UNVERIFIED("ĐÃ LƯU TRONG VAULT (SHA-256 OK) · CHƯA XÁC MINH VỚI BINANCE", true),
    SAVED_IN_VAULT_AND_VERIFIED("ĐÃ LƯU TRONG VAULT & ĐÃ XÁC MINH VỚI BINANCE", true),
    SAVED_IN_VAULT_BUT_REJECTED("ĐÃ LƯU TRONG VAULT NHƯNG BỊ BINANCE TỪ CHỐI", true)
}

enum class AccountVerificationState(val badgeVi: String, val isVerified: Boolean) {
    UNCONFIGURED("CHƯA CẤU HÌNH API KEY", false),
    UNVERIFIED("UNVERIFIED / CHƯA ĐỐI SOÁT VỚI BINANCE", false),
    AUTH_FAILED_2014("LỖI XÁC THỰC -2014 (SAI ĐỊNH DẠNG KEY)", false),
    AUTH_FAILED_2015("LỖI XÁC THỰC -2015 (KEY/IP/QUYỀN/MÔI TRƯỜNG/FUTURES)", false),
    AUTH_FAILED_1022("LỖI CHỮ KÝ -1022 (SAI SECRET/QUERY)", false),
    AUTH_FAILED_1021("LỖI THỜI GIAN -1021 (LỆCH TIMESTAMP)", false),
    NETWORK_OR_TIMEOUT("LỖI MẠNG / TIMEOUT (UNKNOWN)", false),
    VERIFIED_READ_ONLY("ĐÃ XÁC MINH ĐỌC TÀI KHOẢN (CHƯA BẬT QUYỀN GIAO DỊCH FUTURES)", true),
    VERIFIED_TRADING_READY("ĐÃ XÁC MINH TÀI KHOẢN & QUYỀN FUTURES", true)
}

enum class AiModuleStatus(val labelVi: String) {
    UNCONFIGURED("AI chưa cấu hình khóa API"),
    CONFIGURED_NOT_TESTED("Đã nhập khóa · Chưa kiểm tra Health Check"),
    HEALTH_CHECK_FAILED("Lỗi Health Check / Xác thực Provider"),
    READY_NOT_RUN("Đã kiểm tra kết nối · Chưa chạy phân tích"),
    RUNNING("Đang gọi AI Provider..."),
    VALIDATED_SCHEMA("Đã chạy & Đạt kiểm định Strict Schema 1.0"),
    SCHEMA_OR_EVIDENCE_REJECTED("Đã chạy nhưng bị Strict Parser từ chối")
}

enum class BotState(val labelVi: String, val isErrorOrPaused: Boolean = false) {
    UNINITIALIZED("Chưa khởi tạo"),
    CONFIG_REQUIRED("Cần cấu hình API"),
    CONNECTIVITY_CHECK("Kiểm tra kết nối"),
    ACCOUNT_RECONCILIATION("Đối soát tài khoản"),
    READY_TEST("Sẵn sàng (TEST)"),
    LIVE_ARMING("Chờ xác nhận LIVE"),
    SCANNING("Đang quét thị trường"),
    CANDIDATE_REVIEW("Đánh giá ứng viên"),
    RISK_CHECK("Kiểm tra Risk Gate"),
    ORDER_INTENT_PERSISTED("Đã lưu OrderIntent"),
    ENTRY_PENDING("Đang gửi lệnh mở"),
    PARTIALLY_FILLED("Khớp một phần"),
    FILLED("Đã khớp Entry"),
    PROTECTION_PENDING("Đang đặt SL/TP sàn"),
    POSITION_PROTECTED("Vị thế đã có SL bảo vệ"),
    POSITION_MANAGEMENT("Đang quản lý vị thế"),
    EXIT_PENDING("Đang đóng vị thế"),
    POSITION_CLOSED_RECONCILIATION("Đối soát sau đóng lệnh"),
    CLEANUP("Dọn lệnh bảo vệ dư"),
    PAUSED_USER("Tạm dừng bởi người dùng", true),
    DRAINING("Chờ đóng vị thế để rút/nạp (DRAIN)", true),
    PAUSED_FOR_CASHFLOW("Chờ nạp/rút tiền (Flat)", true),
    PAUSED_DATA_STALE("Dừng: Dữ liệu trễ/mất đồng bộ", true),
    PAUSED_API_LIMIT("Dừng: Giới hạn tần suất API (429/418)", true),
    PAUSED_PROVIDER_FAILURE("Dừng: Lỗi toàn bộ AI Provider", true),
    PAUSED_RISK_LIMIT("Dừng: Chạm trần rủi ro/Circuit Breaker", true),
    PAUSED_DEVICE_HEALTH("Dừng: Thiết bị quá nhiệt/thiếu RAM", true),
    UNKNOWN_ORDER_STATUS("Khóa: Trạng thái lệnh chưa xác định", true),
    PROTECTION_FAILURE("Khẩn cấp: Lỗi xác minh lệnh bảo vệ SL", true),
    RECONCILIATION_REQUIRED("Yêu cầu đối soát lại với sàn", true),
    HALTED_REVIEW("Ngừng khẩn cấp: Cần người dùng kiểm tra", true),
    STOPPED("Đã dừng dịch vụ", true)
}

object BotStateMachine {
    private val globalFallbackTargets = setOf(
        BotState.ACCOUNT_RECONCILIATION,
        BotState.RECONCILIATION_REQUIRED,
        BotState.CONFIG_REQUIRED,
        BotState.PAUSED_USER,
        BotState.DRAINING,
        BotState.PAUSED_FOR_CASHFLOW,
        BotState.PAUSED_DATA_STALE,
        BotState.PAUSED_API_LIMIT,
        BotState.PAUSED_PROVIDER_FAILURE,
        BotState.PAUSED_RISK_LIMIT,
        BotState.PAUSED_DEVICE_HEALTH,
        BotState.UNKNOWN_ORDER_STATUS,
        BotState.PROTECTION_FAILURE,
        BotState.HALTED_REVIEW,
        BotState.STOPPED
    )

    private val allowedTransitions: Map<BotState, Set<BotState>> = mapOf(
        BotState.UNINITIALIZED to setOf(BotState.CONFIG_REQUIRED, BotState.CONNECTIVITY_CHECK, BotState.READY_TEST),
        BotState.CONFIG_REQUIRED to setOf(BotState.CONNECTIVITY_CHECK, BotState.READY_TEST, BotState.ACCOUNT_RECONCILIATION),
        BotState.CONNECTIVITY_CHECK to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.CONFIG_REQUIRED, BotState.READY_TEST),
        BotState.ACCOUNT_RECONCILIATION to setOf(BotState.READY_TEST, BotState.SCANNING, BotState.POSITION_PROTECTED, BotState.PAUSED_FOR_CASHFLOW),
        BotState.READY_TEST to setOf(BotState.LIVE_ARMING, BotState.SCANNING, BotState.ACCOUNT_RECONCILIATION),
        BotState.LIVE_ARMING to setOf(BotState.READY_TEST, BotState.SCANNING),
        BotState.SCANNING to setOf(BotState.CANDIDATE_REVIEW, BotState.READY_TEST),
        BotState.CANDIDATE_REVIEW to setOf(BotState.RISK_CHECK, BotState.SCANNING, BotState.READY_TEST),
        BotState.RISK_CHECK to setOf(BotState.ORDER_INTENT_PERSISTED, BotState.SCANNING, BotState.READY_TEST),
        BotState.ORDER_INTENT_PERSISTED to setOf(BotState.ENTRY_PENDING, BotState.SCANNING),
        // INV-05 / Section 7.1: Cannot jump directly from ENTRY_PENDING to SCANNING without verifying order status
        BotState.ENTRY_PENDING to setOf(
            BotState.PARTIALLY_FILLED,
            BotState.FILLED,
            BotState.POSITION_CLOSED_RECONCILIATION
        ),
        BotState.PARTIALLY_FILLED to setOf(BotState.PROTECTION_PENDING, BotState.FILLED, BotState.EXIT_PENDING),
        BotState.FILLED to setOf(BotState.PROTECTION_PENDING),
        BotState.PROTECTION_PENDING to setOf(BotState.POSITION_PROTECTED, BotState.PROTECTION_FAILURE, BotState.EXIT_PENDING),
        BotState.POSITION_PROTECTED to setOf(BotState.POSITION_MANAGEMENT, BotState.EXIT_PENDING),
        BotState.POSITION_MANAGEMENT to setOf(BotState.EXIT_PENDING, BotState.POSITION_PROTECTED),
        BotState.EXIT_PENDING to setOf(BotState.POSITION_CLOSED_RECONCILIATION),
        BotState.POSITION_CLOSED_RECONCILIATION to setOf(BotState.CLEANUP),
        BotState.CLEANUP to setOf(BotState.SCANNING, BotState.READY_TEST, BotState.PAUSED_USER, BotState.PAUSED_FOR_CASHFLOW),
        BotState.DRAINING to setOf(BotState.POSITION_MANAGEMENT, BotState.EXIT_PENDING, BotState.POSITION_CLOSED_RECONCILIATION, BotState.CLEANUP, BotState.PAUSED_FOR_CASHFLOW),
        BotState.PAUSED_FOR_CASHFLOW to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST),
        BotState.PAUSED_USER to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST, BotState.SCANNING),
        BotState.PAUSED_DATA_STALE to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST),
        BotState.PAUSED_API_LIMIT to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST),
        BotState.PAUSED_PROVIDER_FAILURE to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST, BotState.POSITION_MANAGEMENT),
        BotState.PAUSED_RISK_LIMIT to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST),
        BotState.PAUSED_DEVICE_HEALTH to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST),
        BotState.UNKNOWN_ORDER_STATUS to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.PROTECTION_PENDING, BotState.CLEANUP),
        BotState.PROTECTION_FAILURE to setOf(BotState.EXIT_PENDING, BotState.ACCOUNT_RECONCILIATION, BotState.POSITION_PROTECTED),
        BotState.RECONCILIATION_REQUIRED to setOf(BotState.ACCOUNT_RECONCILIATION),
        BotState.HALTED_REVIEW to setOf(BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST),
        BotState.STOPPED to setOf(BotState.CONNECTIVITY_CHECK, BotState.ACCOUNT_RECONCILIATION, BotState.READY_TEST)
    )

    fun canTransition(from: BotState, to: BotState): Boolean {
        if (from == to) return true
        if (from == BotState.ENTRY_PENDING && to == BotState.SCANNING) return false
        if (to in globalFallbackTargets) return true
        return allowedTransitions[from]?.contains(to) == true
    }
}

enum class TradeSide {
    LONG,
    SHORT,
    NONE
}

enum class RegimeLabel(val titleVi: String) {
    TREND_UP("Xu hướng tăng (TREND_UP)"),
    TREND_DOWN("Xu hướng giảm (TREND_DOWN)"),
    RANGE("Đi ngang (RANGE)"),
    HIGH_VOL("Biến động cao (HIGH_VOL)"),
    BREAKOUT_CANDIDATE("Ứng viên phá vỡ (BREAKOUT_CANDIDATE)"),
    EVENT_RISK("Rủi ro sự kiện (EVENT_RISK)"),
    DATA_UNCERTAIN("Dữ liệu bất định (DATA_UNCERTAIN)")
}

enum class CapitalTier(val labelVi: String, val maxLeverageCap: Int) {
    TIER_MICRO("TIER_MICRO (< 25 USDT · Ưu tiên bảo toàn & lọc phí)", 2),
    TIER_SMALL("TIER_SMALL (25 – 200 USDT · 1 vị thế, giới hạn chặt)", 3),
    TIER_GROWTH("TIER_GROWTH (200 – 2.000 USDT · Đa dạng ứng viên, 1 vị thế)", 5),
    TIER_LARGE("TIER_LARGE (> 2.000 USDT · Kiểm soát market impact)", 5)
}

enum class ImplementationStatus(val badgeVi: String) {
    IMPLEMENTED("IMPLEMENTED"),
    PARTIAL("PARTIAL"),
    NOT_IMPLEMENTED("NOT_IMPLEMENTED")
}

enum class CapabilityPriority(val badge: String) {
    V1_BASELINE("V1_BASELINE"),
    OPTIONAL_VALIDATED("OPTIONAL_VALIDATED"),
    RESEARCH_ONLY("RESEARCH_ONLY"),
    NOT_SUPPORTED_BY_EVIDENCE("NOT_SUPPORTED_BY_EVIDENCE")
}

/**
 * Distinguishes whether Skeptic AI actually ran vs local rule-based validation only (Requirement 4).
 */
enum class SkepticVerdict(val labelVi: String) {
    NOT_RUN("SKEPTIC_NOT_RUN (Chưa chạy AI Skeptic)"),
    RULE_ONLY_PASS("RULE_VALIDATION_PASS (Chỉ mới kiểm tra Rule nội bộ · AI chưa chạy)"),
    RULE_ONLY_REJECT("RULE_VALIDATION_REJECT (Bị chặn bởi Rule nội bộ)"),
    PASS("SKEPTIC_AI_PASS (Đã chạy AI Phản biện & Đạt)"),
    REDUCE_CONFIDENCE("REDUCE_CONFIDENCE (Giảm độ tin cậy)"),
    REJECT("REJECT (Từ chối)"),
    NEEDS_DATA("NEEDS_DATA (Thiếu dữ liệu hợp lệ)")
}

/**
 * Strictly separates user-declared VND capital from verified Binance account equity (Requirement 3),
 * and strictly separates Vault storage state from Binance verification state (Requirement 6).
 */
data class AccountCapitalState(
    val declaredCapitalVnd: Long = 150_000L,
    val referenceUsdtVndRate: BigDecimal = BigDecimal("25450.0"),
    val estimatedCapitalFromVndUsdt: BigDecimal = BigDecimal("5.8939"),
    val exchangeEquityUsdt: BigDecimal? = null, // NULL until verified from Binance response
    val exchangeAvailableBalanceUsdt: BigDecimal? = null, // NULL until verified from Binance response
    val exchangeUnrealizedPnlUsdt: BigDecimal? = null,
    val vaultCredentialState: VaultCredentialState = VaultCredentialState.EMPTY_VAULT,
    val verificationState: AccountVerificationState = AccountVerificationState.UNVERIFIED,
    val activeEndpointTarget: String = "MAINNET (https://fapi.binance.com)",
    val dataSource: DataSourceType = DataSourceType.UNVERIFIED_UNKNOWN,
    val verifiedAtUtcMs: Long? = null,
    val lastFailedStep: String? = null,
    val lastFailedEndpoint: String? = null,
    val lastFailedBinanceCode: Int? = null,
    val lastRawBinanceErrorMsg: String? = null,
    val diagnosticMessageVi: String = "Chưa có phản hồi xác thực từ Binance USDⓈ-M Futures."
)

/**
 * Requirements 1–5 & 9 · Real Outbound IP & Network Transport Identity from Android runtime.
 * - Queries public IP via both IPv4-only and IPv6/dual-stack trusted HTTPS endpoints.
 * - Explicitly binds to the same Android Network (`ConnectivityManager.activeNetwork`) used for Binance requests when available,
 *   and explicitly documents architectural limitations (Requirement 3).
 * - Explicitly notes that the observed IP comes from an external probe service and does not claim Binance sees the exact same IP without evidence (Requirement 5).
 * - Tracks IP changes across mobile sessions and IPv6 presence to emit security warnings without altering whitelists or enabling withdrawals (Requirement 9).
 */
data class OutboundNetworkIdentity(
    val outboundIpv4: String? = null,
    val ipv4ProbeEndpointUsed: String? = null,
    val ipv4ProbeStatus: String = "NOT_RUN",
    val ipv4ProbeError: String? = null,
    val outboundIpv6: String? = null,
    val ipv6ProbeEndpointUsed: String? = null,
    val ipv6ProbeStatus: String = "NOT_RUN",
    val ipv6ProbeError: String? = null,
    val outboundDefaultIp: String? = null,
    val dualStackProbeEndpointUsed: String? = null,
    val dualStackProbeStatus: String = "NOT_RUN",
    val dualStackProbeError: String? = null,
    val isIpv6DefaultEgress: Boolean = false,
    val dnsStrategy: String = "IPV4_PREFERRED_DNS (Ưu tiên A record IPv4 trước AAAA IPv6)",
    val activeTransport: String = "UNKNOWN",
    val transportTypeVi: String = "Chưa xác định",
    val networkInterfaceName: String? = null,
    val androidNetworkHandle: Long? = null,
    val sameAndroidNetworkBound: Boolean = false,
    val networkValidatedByOs: Boolean = false,
    val vpnActive: Boolean = false,
    val systemProxy: String = "NONE",
    val connectionStatusVi: String = "Chưa thực hiện kiểm tra kết nối.",
    val previousObservedIpv4: String? = null,
    val ipChangedSinceLastCheck: Boolean = false,
    val networkBindingLimitationNoteVi: String = "Chưa kiểm tra gắn kết Android Network.",
    val observationDisclaimerVi: String = "LƯU Ý BẰNG CHỨNG KỸ THUẬT (Mục 5): Địa chỉ IP trên là IP nguồn quan sát được qua dịch vụ kiểm tra HTTPS bên thứ ba; KHÔNG mặc định khẳng định máy chủ Binance nhìn thấy cùng IP này nếu chưa có bằng chứng xác thực từ phản hồi của Binance.",
    val securityAndRoutingWarningsVi: List<String> = emptyList(),
    val probedAtUtcMs: Long = 0L,
    val summaryVi: String = "Chưa kiểm tra IP outbound từ Android."
)

/**
 * Requirement 6 & 7 · Actual Read-Only Binance Request Telemetry Record.
 * - Reports host, endpoint, HTTP status, Binance error code, message, environment (MAINNET/TESTNET), and product (SPOT_SAPI / USDM_FUTURES).
 * - Reports `apiKeyLoadedFromVault` and `apiKeyAttachedToHeader` strictly as `true`/`false` booleans, NEVER logging or exposing the API Key, Secret, HMAC signature, or private headers.
 */
data class BinanceRequestInspectionRecord(
    val environment: String, // "MAINNET" or "TESTNET"
    val product: String, // "USDM_FUTURES" or "SPOT_SAPI"
    val httpMethod: String = "GET",
    val host: String,
    val endpointPath: String,
    val apiKeyLoadedFromVault: Boolean,
    val apiKeyAttachedToHeader: Boolean,
    val hmacSignatureAttachedToQuery: Boolean,
    val boundToSameAndroidNetwork: Boolean,
    val httpStatus: Int,
    val binanceErrorCode: Int? = null,
    val rawServerMessage: String = "",
    val checkedAtUtcMs: Long = 0L
)

data class BinanceAuthDiagnosticStepResult(
    val stepIndex: Int,
    val stepCode: String,
    val endpointCalled: String,
    val passed: Boolean,
    val httpCode: Int = 0,
    val binanceErrorCode: Int? = null,
    val rawServerMessage: String = "",
    val detailVi: String
)

/**
 * Requirements 1–10 · End-to-End Read-Only Binance Authentication, Outbound IP & -2015 Root-Cause Diagnostic Report.
 * Never includes plaintext API Key, API Secret, or HMAC signature.
 */
data class BinanceAuthDiagnosticReport(
    val ranAtUtcMs: Long = 0L,
    val targetEndpointMode: String = "MAINNET",
    val fapiBaseUrl: String = "https://fapi.binance.com",
    val vaultCredentialState: VaultCredentialState = VaultCredentialState.EMPTY_VAULT,
    val accountVerificationState: AccountVerificationState = AccountVerificationState.UNVERIFIED,
    val keySavedAtUtcMs: Long = 0L,
    val apiKeyLoadedFromVault: Boolean = false,
    val apiKeyAttachedToHeader: Boolean = false,
    val keyVaultDigestMatch: Boolean = false,
    val secretVaultDigestMatch: Boolean = false,
    val headerExactMatchVerified: Boolean = false,
    val outboundNetwork: OutboundNetworkIdentity = OutboundNetworkIdentity(),
    val clockOffsetMs: Long = 0L,
    val clockRttMs: Long = 0L,
    val spotKeyAcceptedOnMainnet: Boolean? = null,
    val spotApiRestrictionsEnableReading: Boolean? = null,
    val spotApiRestrictionsEnableFutures: Boolean? = null,
    val spotApiRestrictionsIpRestrict: Boolean? = null,
    val spotApiKeyCreatedAtMs: Long? = null,
    val testnetKeyAccepted: Boolean? = null,
    val futuresBalanceAccepted: Boolean = false,
    val futuresAccountAccepted: Boolean = false,
    val failedStepCode: String? = null,
    val failedEndpoint: String? = null,
    val failedHttpCode: Int? = null,
    val failedBinanceErrorCode: Int? = null,
    val failedRawServerMessage: String? = null,
    val requestInspections: List<BinanceRequestInspectionRecord> = emptyList(),
    val provenCausesVi: List<String> = emptyList(),
    val remainingHypothesesVi: List<String> = emptyList(),
    val steps: List<BinanceAuthDiagnosticStepResult> = emptyList()
)

data class SymbolFilter(
    val symbol: String,
    val status: String = "TRADING",
    val quoteAsset: String = "USDT",
    val tickSize: BigDecimal = BigDecimal("0.01"),
    val minPrice: BigDecimal = tickSize,
    val maxPrice: BigDecimal = BigDecimal("1000000"),
    val stepSize: BigDecimal = BigDecimal("0.001"),
    val minQty: BigDecimal = BigDecimal("0.001"),
    val maxQty: BigDecimal = BigDecimal("1000000"),
    val marketStepSize: BigDecimal = stepSize,
    val marketMinQty: BigDecimal = minQty,
    val marketMaxQty: BigDecimal = maxQty,
    val minNotional: BigDecimal = BigDecimal("5.0"),
    val multiplierUp: BigDecimal = BigDecimal("1.10"),
    val multiplierDown: BigDecimal = BigDecimal("0.90"),
    val maxLeverage: Int = 20,
    val maintMarginRate: BigDecimal = BigDecimal("0.004"),
    val isRealExchangeFilter: Boolean = false,
    val fetchedAtMs: Long = 0L
)

enum class PositionSideMode(val labelVi: String) {
    ONE_WAY("One-Way Mode (positionSide=BOTH)"),
    HEDGE("Hedge Mode (positionSide=LONG/SHORT)")
}

enum class PanicPreCloseVerificationState(val labelVi: String) {
    FETCH_FAILED("Lỗi truy vấn vị thế sàn trước khi đóng (FETCH_FAILED)"),
    SNAPSHOT_INCONSISTENT("Dữ liệu vị thế sàn mâu thuẫn / lệch positionSide (SNAPSHOT_INCONSISTENT)"),
    VERIFIED_ZERO("Đã xác minh vị thế trên sàn = 0 trước khi đóng (VERIFIED_ZERO)"),
    VERIFIED_NON_ZERO("Đã xác minh vị thế đang mở trên sàn != 0 (VERIFIED_NON_ZERO)")
}

enum class PositionProtectionState(val code: String, val labelVi: String) {
    OPEN_PROTECTED("OPEN_PROTECTED", "Đã xác minh đủ cả 2 lệnh bảo vệ SL + TP trên sàn"),
    PROTECTION_DEGRADED("PROTECTION_DEGRADED", "Thiếu 1 trong 2 lệnh bảo vệ (chỉ có SL hoặc chỉ có TP)"),
    PROTECTION_FAILURE("PROTECTION_FAILURE", "Cả 2 lệnh bảo vệ SL và TP đều thiếu hoặc thất bại"),
    PROTECTION_UNKNOWN("PROTECTION_UNKNOWN", "Không thể xác minh trạng thái lệnh bảo vệ trên sàn"),
    ORPHAN_UNPROTECTED("ORPHAN_UNPROTECTED", "Vị thế mồ côi trên sàn chưa có lệnh bảo vệ của bot")
}

enum class FillPnlStatus(val code: String, val labelVi: String) {
    VERIFIED("VERIFIED", "PnL & phí thực tế đã xác minh từ sàn"),
    ESTIMATED("ESTIMATED", "PnL ước tính (chưa có dữ liệu fill/commission thật từ sàn)"),
    ACCOUNTING_PENDING("ACCOUNTING_PENDING", "Đang chờ đối soát giá khớp & phí giao dịch thực tế"),
    PNL_UNVERIFIED("PNL_UNVERIFIED", "Chưa xác minh được giá khớp thực tế (không dùng markPrice làm PnL chuẩn)")
}

data class ExchangeUserTradeRecord(
    val tradeId: String,
    val symbol: String,
    val orderId: String,
    val side: String,
    val price: BigDecimal,
    val qty: BigDecimal,
    val quoteQty: BigDecimal = BigDecimal.ZERO,
    val realizedPnl: BigDecimal,
    val commission: BigDecimal,
    val commissionAsset: String = "USDT",
    val positionSide: String = "BOTH",
    val timeMs: Long = System.currentTimeMillis()
)

enum class PanicCloseStage(val labelVi: String) {
    IDLE("Chưa kích hoạt"),
    PANIC_TRIGGERED("1. Đã kích hoạt Panic Close"),
    FETCH_EXCHANGE_POSITION("2. Truy vấn vị thế thực tế từ sàn"),
    CLOSE_ORDER_SUBMITTED("3. Đã gửi lệnh đóng vị thế"),
    CLOSE_ORDER_RECONCILIATION("4. Đối soát trạng thái lệnh đóng"),
    POSITION_VERIFICATION("5. Xác minh vị thế trên sàn = 0"),
    CANCEL_REMAINING_PROTECTIVE_ORDERS("6. Hủy toàn bộ lệnh SL/TP/Algo bảo vệ dư"),
    FINAL_EXCHANGE_RECONCILIATION("7. Đối soát cuối cùng sau khi đóng"),
    CLOSED_CONFIRMED("8. Đã xác nhận đóng hoàn toàn (CLOSED_CONFIRMED)"),
    PANIC_FAILED_UNVERIFIED("LỖI: Chưa xác minh đóng vị thế trên sàn (KHÔNG xóa vị thế cục bộ)")
}

data class PanicCloseReport(
    val confirmedClosed: Boolean,
    val finalStage: PanicCloseStage,
    val symbol: String,
    val closedQuantity: BigDecimal = BigDecimal.ZERO,
    val fillAvgPrice: BigDecimal = BigDecimal.ZERO,
    val realizedNetPnlUsdt: BigDecimal = BigDecimal.ZERO,
    val cancelledProtectiveOrderCount: Int = 0,
    val failedCancelProtectiveOrderCount: Int = 0,
    val preCloseVerificationState: PanicPreCloseVerificationState = PanicPreCloseVerificationState.VERIFIED_NON_ZERO,
    val postCloseVerifiedZero: Boolean = false,
    val finalExchangeVerifiedClean: Boolean = false,
    val pnlStatus: FillPnlStatus = FillPnlStatus.VERIFIED,
    val stageTrail: List<PanicCloseStage> = emptyList(),
    val summaryVi: String
)

data class ProbabilityCalibrationReport(
    val predictedRankingScore: BigDecimal,
    val calibratedProbability: BigDecimal?, // NULL if sampleSize < minRequiredSamples
    val realizedWinRate: BigDecimal?,
    val sampleSize: Int,
    val minRequiredSamples: Int = 30,
    val isSufficientSample: Boolean,
    val brierScore: BigDecimal? = null,
    val reliabilityError: BigDecimal? = null,
    val calibrationMethod: String = "UNCALIBRATED_INSUFFICIENT_SAMPLE",
    val noteVi: String
)

data class ExecutionGateDecision(
    val approved: Boolean,
    val symbol: String,
    val signalId: String?,
    val decisionId: String?,
    val sizingId: String?,
    val riskApprovalId: String?,
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
    val rejectReasonCode: String? = null,
    val rejectReasonVi: String? = null,
    val evaluatedAtUtcMs: Long = System.currentTimeMillis()
)

data class AiSkepticReport(
    val schemaVersion: String = "1.0",
    val skepticDecisionId: String,
    val analystDecisionId: String,
    val symbol: String,
    val verdict: SkepticVerdict,
    val contradictingEvidenceIds: List<String>,
    val failureModes: List<String>,
    val overconfidenceFlags: List<String>,
    val missingDataFlags: List<String>,
    val confidencePenalty: BigDecimal = BigDecimal.ZERO,
    val reasonVi: String,
    val expiresAtUtc: String
)

/**
 * MOD-06 · Market Scanner Candidate Item
 * Represents a pre-screened symbol from the 20–50 liquid symbol universe narrowed down to Top 3–5 candidates.
 */
data class ScannerCandidateItem(
    val symbol: String,
    val lastPrice: BigDecimal,
    val quoteVolume24hUsdt: BigDecimal,
    val spreadPct: BigDecimal,
    val relativeVolume: BigDecimal = BigDecimal("1.0"),
    val minNotionalUsdt: BigDecimal,
    val feasibleForCurrentCapital: Boolean = true,
    val primaryRegime: RegimeLabel = RegimeLabel.BREAKOUT_CANDIDATE,
    val candidateDirection: TradeSide = TradeSide.NONE,
    val scannerScore: BigDecimal = BigDecimal.ZERO,
    val noteVi: String = "",
    val dataSource: DataSourceType = DataSourceType.BINANCE_PUBLIC_REST
)

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
    val isClosed: Boolean
)

/**
 * MOD-09.1 & MOD-09.2 · Reproducible Quant Feature Snapshot with version & hash.
 */
data class FeatureSnapshot(
    val symbol: String,
    val asOfUtcMs: Long,
    val featureVersion: String = "v5.1-baseline-2",
    val snapshotHash: String = "",
    val dataSource: DataSourceType = DataSourceType.BINANCE_PUBLIC_REST,
    val lastPrice: BigDecimal,
    val markPrice: BigDecimal,
    val indexPrice: BigDecimal,
    val return1m: BigDecimal,
    val return5m: BigDecimal,
    val return15m: BigDecimal,
    val return1h: BigDecimal,
    val ema20_15m: BigDecimal,
    val ema50_15m: BigDecimal,
    val emaSlope15m: BigDecimal,
    val ema20_1h: BigDecimal,
    val ema50_1h: BigDecimal,
    val atr14: BigDecimal,
    val atrPct: BigDecimal,
    val rollingReturnStd15m: BigDecimal = BigDecimal.ZERO,
    val ewmaVolatility15m: BigDecimal = BigDecimal.ZERO,
    val robustReturnZScore: BigDecimal = BigDecimal.ZERO,
    val bodyToRangeRatio: BigDecimal = BigDecimal.ZERO,
    val upperWickRatio: BigDecimal = BigDecimal.ZERO,
    val lowerWickRatio: BigDecimal = BigDecimal.ZERO,
    val relativeVolume: BigDecimal,
    val closedRangeHigh20: BigDecimal,
    val closedRangeLow20: BigDecimal,
    val breakoutUp: Boolean,
    val breakoutDown: Boolean,
    val spreadPct: BigDecimal,
    val topBookBidQty: BigDecimal,
    val topBookAskQty: BigDecimal,
    val microprice: BigDecimal = BigDecimal.ZERO,
    val orderBookImbalance: BigDecimal = BigDecimal.ZERO,
    val markIndexBasisBps: BigDecimal = BigDecimal.ZERO,
    val fundingRate: BigDecimal,
    val nextFundingTimeMs: Long,
    val dataAgeMs: Long,
    val isDataStale: Boolean,
    val isEstimated: Boolean,
    val regimes: Set<RegimeLabel>,
    val primaryRegime: RegimeLabel
)

data class NetEvEstimate(
    val pWin: BigDecimal,
    val probabilityCalibrated: Boolean,
    val rankingScore: BigDecimal,
    val avgWinPct: BigDecimal,
    val avgLossPct: BigDecimal,
    val expectedFeePct: BigDecimal,
    val expectedSlippagePct: BigDecimal,
    val expectedFundingPct: BigDecimal,
    val totalExpectedCostPct: BigDecimal,
    val netEvPct: BigDecimal,
    val uncertaintyMarginPct: BigDecimal,
    val isPositiveEdge: Boolean,
    val canUseForLive: Boolean,
    val noteVi: String,
    val realizedWinRate: BigDecimal? = null,
    val calibrationSampleSize: Int = 0,
    val brierScore: BigDecimal? = null
)

data class SkepticCheckResult(
    val verdict: SkepticVerdict,
    val aiActuallyExecuted: Boolean,
    val reasonCodes: List<String>,
    val summaryVi: String,
    val skepticReport: AiSkepticReport? = null
)

data class SizingDecision(
    val approved: Boolean,
    val symbol: String,
    val side: TradeSide,
    val capitalSource: DataSourceType,
    val equityUsdt: BigDecimal,
    val availableBalanceUsdt: BigDecimal,
    val capitalTier: CapitalTier,
    val riskBudgetUsdt: BigDecimal,
    val entryPrice: BigDecimal,
    val stopPrice: BigDecimal,
    val targetPrice: BigDecimal,
    val netBreakevenEstimate: BigDecimal,
    val estimatedLiquidationPrice: BigDecimal,
    val rawRiskQty: BigDecimal,
    val roundedQty: BigDecimal,
    val minViableQty: BigDecimal,
    val notionalUsdt: BigDecimal,
    val initialMarginUsdt: BigDecimal,
    val leverage: Int,
    val estimatedFeesUsdt: BigDecimal,
    val estimatedSlippageUsdt: BigDecimal,
    val estimatedFundingUsdt: BigDecimal,
    val totalEstimatedLossAtStopUsdt: BigDecimal,
    val rejectReasonCode: String?,
    val rejectReasonVi: String?,
    val sizingId: String = "SIZ-${System.currentTimeMillis()}",
    val signalId: String? = null,
    val decisionId: String? = null,
    val riskApprovalId: String? = null,
    val usedMarketLotSize: Boolean = true,
    val isRealExchangeFilter: Boolean = false
)

data class AiCandidatePlan(
    val schemaVersion: String,
    val decisionId: String,
    val asOfUtc: String,
    val symbol: String,
    val recommendation: String, // TRADE_CANDIDATE | NO_TRADE | PAUSE | MANAGE_POSITION
    val strategyCandidate: String,
    val directionCandidate: TradeSide,
    val horizon: String,
    val regimeHypothesis: String,
    val supportingEvidenceIds: List<String>,
    val contradictingEvidenceIds: List<String>,
    val invalidationConditions: List<String>,
    val risks: List<String>,
    val missingData: List<String>,
    val score: BigDecimal?,
    val calibratedProbability: BigDecimal?,
    val reasonVi: String,
    val expiresAtUtc: String
)

/**
 * Section 10 & Appendix B.9 · News, Event Taxonomy, Deduplication & Crowd Attention Proxy (estimated=true).
 */
enum class NewsVerificationStatus(val labelVi: String) {
    RUMOR("TIN ĐỒN (RUMOR · Chưa xác thực)"),
    REPORTED("ĐÃ ĐƯA TIN (REPORTED · Hãng tin)"),
    CONFIRMED("ĐÃ XÁC MINH (CONFIRMED · Nguồn gốc)"),
    DENIED("ĐÃ PHỦ NHẬN (DENIED)"),
    CORRECTED("ĐÃ ĐÍNH CHÍNH (CORRECTED)"),
    STALE("TIN CŨ (STALE · Quá hạn)")
}

enum class NewsEventCategory(val labelVi: String, val triggersEventRiskGate: Boolean) {
    EXCHANGE_INFRA("Bảo trì / Hạ tầng sàn (EXCHANGE_INFRA)", true),
    SECURITY_EXPLOIT("Hack / Exploit / Sự cố bảo mật", true),
    REGULATORY_ACTION("Pháp lý / Cơ quan quản lý", true),
    MACRO_POLICY("Vĩ mô (Lãi suất / CPI / Dòng vốn ETF)", true),
    LISTING_DELISTING("Niêm yết / Hủy niêm yết", true),
    STABLECOIN_DEPEG("Mất chốt tỷ giá Stablecoin (Depeg)", true),
    TOKEN_UNLOCK("Mở khóa Token / Thay đổi cung", false),
    PROTOCOL_UPGRADE("Nâng cấp giao thức / Hard Fork", false),
    SOCIAL_CHATTER("Thảo luận mạng xã hội / Tin cộng đồng", false),
    OTHER("Sự kiện thị trường khác", false)
}

data class NewsEventItem(
    val eventId: String,
    val canonicalUrl: String,
    val duplicateClusterId: String,
    val repostCount: Int = 1,
    val title: String,
    val sourceName: String,
    val sourceTier: Int = 3, // 1 = Official/Exchange/Regulator, 2 = Major Financial Wire, 3 = Social/Aggregator
    val reliabilityScore: Double = 0.30,
    val verificationStatus: NewsVerificationStatus = NewsVerificationStatus.RUMOR,
    val category: NewsEventCategory = NewsEventCategory.OTHER,
    val authorSentimentScore: Double = 0.0, // separated from event impact (Section 10.4.5)
    val eventImpactScore: Double = 0.0,
    val publishedAtMs: Long = System.currentTimeMillis(),
    val ingestedAtMs: Long = System.currentTimeMillis(),
    val gateAction: String = "ALLOW",
    val containsUntrustedInstructionAttempt: Boolean = false
)

data class CrowdAttentionProxySnapshot(
    val symbol: String,
    val estimated: Boolean = true, // Mandatory per Section 10.6 & Appendix B.9
    val attentionScore0To100: Double = 0.0,
    val weightedSentimentScore: Double = 0.0,
    val fundingExtremeScore: Double = 0.0,
    val volumeBurstScore: Double = 0.0,
    val uniqueEventClusterCount: Int = 0,
    val totalRawMentionCount: Int = 0,
    val crowdBiasLabel: String = "NEUTRAL_BALANCED_ATTENTION",
    val eventGateActive: Boolean = false,
    val eventGateReason: String = "Không có sự kiện khóa giao dịch (ALLOW)",
    val computedAtMs: Long = System.currentTimeMillis()
)

/**
 * Section 18 · Self-Monitoring & Performance Metrics (Trading, Model Quality, Operational Reliability).
 */
data class PerformanceSelfMonitoringReport(
    val totalClosedTrades: Int = 0,
    val winCount: Int = 0,
    val lossCount: Int = 0,
    val winRatePct: BigDecimal = BigDecimal.ZERO,
    val avgWinUsdt: BigDecimal = BigDecimal.ZERO,
    val avgLossUsdt: BigDecimal = BigDecimal.ZERO,
    val profitFactor: BigDecimal = BigDecimal.ZERO,
    val expectancyUsdt: BigDecimal = BigDecimal.ZERO,
    val totalRealizedNetPnlUsdt: BigDecimal = BigDecimal.ZERO,
    val totalFeesAndFundingUsdt: BigDecimal = BigDecimal.ZERO,
    val feeToGrossPnlRatioPct: BigDecimal = BigDecimal.ZERO,
    val totalSignalsEvaluated: Int = 0,
    val rejectedSignalsCount: Int = 0,
    val signalRejectionRatePct: BigDecimal = BigDecimal.ZERO,
    val aiLatencyP50Ms: Long = 0L,
    val aiLatencyP95Ms: Long = 0L,
    val wsReconnectCount: Int = 0,
    val buildFingerprint: String = "ASTRA-V5.1-CFG1-STRAT_V1_BASELINE",
    val computedAtMs: Long = System.currentTimeMillis()
)

data class DeviceHealthSnapshot(
    val batteryPct: Int = 100,
    val batteryTempCelsius: Float = 31.0f,
    val isCharging: Boolean = true,
    val availableRamMb: Long = 0L,
    val kernelTotalRamMb: Long = 0L,
    val physicalNominalRamGb: Int = 4,
    val memInfoSource: String = "ActivityManager.MemoryInfo",
    val ignoringBatteryOptimizations: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val thermalWarning: Boolean = false,
    val lowRamWarning: Boolean = false,
    val serviceStatus: String = "RUNNING",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class LiveActivationChecklist(
    val buildFingerprint: String = "ASTRA-V5.1-ANDROID11-S7E",
    val safetyDebugLockDisengaged: Boolean = false, // Requirement 1: Safety lock on LIVE during auth debug
    val apiKeyConfigured: Boolean = false,
    val accountVerifiedFromBinance: Boolean = false, // Requirement 3: Must have verified Binance account response
    val withdrawDisabledConfirmed: Boolean = false,
    val clockSyncHealthy: Boolean = false,
    val exchangeMetadataLoaded: Boolean = false,
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
            clockSyncHealthy &&
            exchangeMetadataLoaded &&
            reconciliationCompleted &&
            noActiveCircuitBreaker &&
            deviceHealthStable &&
            riskLimitsReviewedByUser &&
            userExplicitlyConfirmedLive
}

data class CapabilityEntry(
    val methodId: String,
    val moduleOwner: String,
    val titleVi: String,
    val categoryVi: String,
    val priority: CapabilityPriority,
    val status: ImplementationStatus,
    val enabledInV1: Boolean,
    val descriptionVi: String
)

data class BacktestSummary(
    val symbol: String,
    val timeframe: String,
    val dataSource: DataSourceType,
    val barsTested: Int,
    val totalTrades: Int,
    val winCount: Int,
    val lossCount: Int,
    val netReturnUsdt: BigDecimal,
    val totalFeesAndSlippageUsdt: BigDecimal,
    val maxDrawdownPct: BigDecimal,
    val expectancyUsdt: BigDecimal,
    val buyAndHoldReturnUsdt: BigDecimal,
    val testedVariantsCount: Int = 1,
    val ablationWithoutCostFilterReturnUsdt: BigDecimal = BigDecimal.ZERO,
    val outOfSamplePassed: Boolean,
    val notesVi: String
)

object AstraCapabilityCatalog {
    val entries: List<CapabilityEntry> = listOf(
        CapabilityEntry(
            methodId = "MOD-01-FOREGROUND-RUNTIME",
            moduleOwner = "MOD-01",
            titleVi = "Foreground Service, BOOT_COMPLETED & Giám sát tiến trình Android 11",
            categoryVi = "Hệ thống & Vận hành",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Quản lý vòng đời dịch vụ nền, thông báo thường trực, giải phóng WakeLock an toàn, ghi nhận sự kiện khởi động lại thiết bị, không giả định tiến trình bất tử."
        ),
        CapabilityEntry(
            methodId = "MOD-02-ROOM-AUDIT-LEDGER",
            moduleOwner = "MOD-02",
            titleVi = "Room SQLite 17 Bảng Sổ cái, MarketFeatures, AiRequests, OrderIntent & Audit Log",
            categoryVi = "Lưu trữ & Đối soát",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Đầy đủ 17 bảng theo mục MOD-02: lưu OrderIntent trước khi phát lệnh (INV-04), lưu snapshot đặc trưng có hash, tách cashflow nạp/rút khỏi PnL (INV-18)."
        ),
        CapabilityEntry(
            methodId = "MOD-03-KEYSTORE-VAULT",
            moduleOwner = "MOD-03",
            titleVi = "Android Keystore AES-GCM Secret Vault & Kiểm toàn vẹn giải mã",
            categoryVi = "Bảo mật",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Mã hóa khóa API Binance, AI Provider và Telegram bằng khóa phần cứng/Keystore; kiểm tra round-trip digest, che bí mật trên UI/log."
        ),
        CapabilityEntry(
            methodId = "MOD-04-05-BINANCE-ADAPTER",
            moduleOwner = "MOD-04 / MOD-05",
            titleVi = "Binance USDⓈ-M Futures REST Interfaces, ListenKey User Stream & Clock Sync",
            categoryVi = "Dữ liệu & Kết nối sàn",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Tách MarketDataClient, AccountClient, TradeClient, AlgoOrderClient; đồng bộ lệch giờ RTT, ký HMAC-SHA256 chuẩn query string, tách TESTNET/LIVE (INV-19)."
        ),
        CapabilityEntry(
            methodId = "MOD-06-MULTI-SYMBOL-SCANNER",
            moduleOwner = "MOD-06",
            titleVi = "Market Scanner 20–50 Symbol Thanh khoản cao -> Thu hẹp Top 5 Ứng viên & Cooldown",
            categoryVi = "Quét thị trường",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Lọc rẻ theo 24h Quote Volume, Spread%, bộ lọc minNotional/stepSize và ngân sách rủi ro tài khoản nhỏ, kèm cooldown chống nhảy symbol liên tục."
        ),
        CapabilityEntry(
            methodId = "MOD-09-BASELINE-FEATURES",
            moduleOwner = "MOD-09.1 / B.4",
            titleVi = "Log Returns đa khung, EMA20/50 Slope, ATR%, Tỷ lệ Thân/Râu nến, RVOL, Breakout nến đóng",
            categoryVi = "Đặc trưng định lượng (Quant)",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Tính toán bằng BigDecimal xác định, chống lookahead, kèm Robust Median/MAD Z-score, EWMA Vol, Microprice & Top-book Imbalance."
        ),
        CapabilityEntry(
            methodId = "MOD-09-RULE-REGIME",
            moduleOwner = "MOD-09.4",
            titleVi = "Bộ phát hiện Regime thị trường dựa trên quy tắc & Cờ sự kiện tin tức",
            categoryVi = "Đặc trưng định lượng (Quant)",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Phân loại TREND_UP, TREND_DOWN, RANGE, HIGH_VOL, BREAKOUT_CANDIDATE, EVENT_RISK, DATA_UNCERTAIN."
        ),
        CapabilityEntry(
            methodId = "MOD-10-NEWS-EVENT-GATE",
            moduleOwner = "Mục 10 / B.9",
            titleVi = "Pipeline Tin tức, Gom cụm Chống trùng (Dedup), Phân tầng Nguồn & Proxy Tâm lý (estimated=true)",
            categoryVi = "Tin tức & An toàn NLP",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Tách Rumor/Reported/Confirmed, không nhân đôi bài đăng lại, lọc Prompt Injection từ văn bản ngoài, gắn nhãn estimated=true cho proxy tâm lý."
        ),
        CapabilityEntry(
            methodId = "MOD-11-RISK-SIZING-ENGINE",
            moduleOwner = "MOD-11 / B.12",
            titleVi = "Định cỡ vị thế theo Stop-Risk + Chi phí + Bước giá StepSize/MinNotional",
            categoryVi = "Quản trị vốn & Rủi ro",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Tính Q_risk từ ngân sách lỗ R, trừ phí/trượt giá/funding, từ chối nếu Q_risk < Q_min (INV-10). Tách biệt vốn khai báo VNĐ và Equity thực."
        ),
        CapabilityEntry(
            methodId = "MOD-12-13-EXECUTION-RECONCILER",
            moduleOwner = "MOD-12 / MOD-13",
            titleVi = "ExecutionCoordinator, Dời SL Hòa vốn ròng An toàn (7.4) & Đối soát 3 nguồn",
            categoryVi = "Thực thi & Bảo vệ vị thế",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Cổng gửi lệnh duy nhất (INV-01), giữ SL cũ cho đến khi SL mới được xác nhận trên sàn (Mục 7.3/7.4), chống retry mù khi 5XX/timeout (INV-06)."
        ),
        CapabilityEntry(
            methodId = "MOD-07-08-AI-SKEPTIC-PARSER",
            moduleOwner = "MOD-07 / MOD-08",
            titleVi = "AI Provider Router, Health Check Gate, Strict JSON Schema Validator & Skeptic",
            categoryVi = "AI & Ngôn ngữ",
            priority = CapabilityPriority.OPTIONAL_VALIDATED,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = false,
            descriptionVi = "Cho phép thêm/cấu hình Provider trong allowlist HTTPS, chỉ bật sau khi đạt Health Check. Tách biệt Rule-based check và AI Skeptic thực sự."
        ),
        CapabilityEntry(
            methodId = "MOD-14-15-TELEGRAM-GUARD",
            moduleOwner = "MOD-14 / MOD-15",
            titleVi = "Telegram Bot API, Long-Polling, Whitelist User/Chat & Panic 2 bước",
            categoryVi = "Giao tiếp & Điều khiển",
            priority = CapabilityPriority.V1_BASELINE,
            status = ImplementationStatus.IMPLEMENTED,
            enabledInV1 = true,
            descriptionVi = "Gửi thông báo thật, kiểm tra getUpdates chống phát lại update_id, khóa nhận API secret qua chat, xác nhận 2 bước có nonce cho /panic."
        ),
        CapabilityEntry(
            methodId = "APP-B-MICROSTRUCTURE-OFI",
            moduleOwner = "MOD-09.2 / B.6",
            titleVi = "Order Book Depth Imbalance đa tầng & CVD nâng cao",
            categoryVi = "Vi cấu trúc thị trường",
            priority = CapabilityPriority.OPTIONAL_VALIDATED,
            status = ImplementationStatus.PARTIAL,
            enabledInV1 = false,
            descriptionVi = "V1 bật Spread%, Top-of-book Imbalance và Microprice; sổ lệnh sâu đa tầng tắt mặc định để tiết kiệm RAM 4 GB."
        ),
        CapabilityEntry(
            methodId = "APP-B-HEAVY-ML-RL",
            moduleOwner = "MOD-10.3 / B.10",
            titleVi = "Deep Sequence (LSTM/Transformer), Reinforcement Learning, VPIN, Full Kelly",
            categoryVi = "Nghiên cứu ngoài phạm vi V1",
            priority = CapabilityPriority.RESEARCH_ONLY,
            status = ImplementationStatus.NOT_IMPLEMENTED,
            enabledInV1 = false,
            descriptionVi = "Không chạy mô hình LLM/RL nặng trên S7 Edge RAM 4 GB hoặc chưa có kiểm định ngoài mẫu (INV-09, INV-13)."
        )
    )
}

enum class MarketDataEndpoint {
    MAINNET_PUBLIC,
    TESTNET
}

enum class AccountReadEndpoint {
    NONE,
    MAINNET_READONLY,
    TESTNET,
    MAINNET_LIVE
}

enum class OrderExecutionEndpoint {
    DISABLED,
    TESTNET,
    MAINNET_EMERGENCY_EXIT_ONLY,
    MAINNET
}

/**
 * P0-08 · Explicit Capability Matrix separating Market Data, Account Read, and Order Execution privileges.
 */
data class ExecutionCapability(
    val environmentMode: EnvironmentMode = EnvironmentMode.TEST,
    val marketDataEndpoint: MarketDataEndpoint = MarketDataEndpoint.TESTNET,
    val accountReadEndpoint: AccountReadEndpoint = AccountReadEndpoint.TESTNET,
    val orderExecutionEndpoint: OrderExecutionEndpoint = OrderExecutionEndpoint.TESTNET,
    val allowOrderSubmission: Boolean = true,
    val allowEmergencyClose: Boolean = true
) {
    companion object {
        fun fromConfig(
            environmentMode: EnvironmentMode,
            endpointTarget: String,
            safetyDebugLockActive: Boolean,
            liveExplicitlyArmed: Boolean
        ): ExecutionCapability {
            val isMainnetTarget = endpointTarget.equals("MAINNET", ignoreCase = true)
            return if (environmentMode == EnvironmentMode.LIVE && isMainnetTarget && !safetyDebugLockActive && liveExplicitlyArmed) {
                ExecutionCapability(
                    environmentMode = EnvironmentMode.LIVE,
                    marketDataEndpoint = MarketDataEndpoint.MAINNET_PUBLIC,
                    accountReadEndpoint = AccountReadEndpoint.MAINNET_LIVE,
                    orderExecutionEndpoint = OrderExecutionEndpoint.MAINNET,
                    allowOrderSubmission = true,
                    allowEmergencyClose = true
                )
            } else if (isMainnetTarget) {
                // TEST mode inspecting MAINNET: read-only account & market data, new order submissions disabled on Mainnet
                ExecutionCapability(
                    environmentMode = EnvironmentMode.TEST,
                    marketDataEndpoint = MarketDataEndpoint.MAINNET_PUBLIC,
                    accountReadEndpoint = AccountReadEndpoint.MAINNET_READONLY,
                    orderExecutionEndpoint = OrderExecutionEndpoint.DISABLED,
                    allowOrderSubmission = false,
                    allowEmergencyClose = true
                )
            } else {
                ExecutionCapability(
                    environmentMode = EnvironmentMode.TEST,
                    marketDataEndpoint = MarketDataEndpoint.TESTNET,
                    accountReadEndpoint = AccountReadEndpoint.TESTNET,
                    orderExecutionEndpoint = OrderExecutionEndpoint.TESTNET,
                    allowOrderSubmission = true,
                    allowEmergencyClose = true
                )
            }
        }
    }
}

/**
 * P0-12 · Verified funding fee record from `GET /fapi/v1/income?incomeType=FUNDING_FEE`.
 */
data class FundingIncomeRecord(
    val tranId: String,
    val symbol: String,
    val incomeType: String,
    val incomeUsdt: BigDecimal,
    val asset: String,
    val timeMs: Long
)

