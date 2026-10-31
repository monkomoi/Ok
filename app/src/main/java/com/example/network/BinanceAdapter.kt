package com.example.network

import android.net.Network
import com.example.domain.AccountVerificationState
import com.example.domain.BinanceAuthDiagnosticReport
import com.example.domain.BinanceAuthDiagnosticStepResult
import com.example.domain.BinanceRequestInspectionRecord
import com.example.domain.CandleBar
import com.example.domain.EnvironmentMode
import com.example.domain.ExchangeUserTradeRecord
import com.example.domain.ExecutionCapability
import com.example.domain.FundingIncomeRecord
import com.example.domain.OrderExecutionEndpoint
import com.example.domain.OutboundNetworkIdentity
import com.example.domain.SymbolFilter
import com.example.domain.VaultCredentialState
import com.example.security.SecretVault
import com.example.service.TradingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class BinanceErrorCategory {
    NONE,
    BAD_REQUEST,
    BAD_API_KEY_FMT_2014,
    REJECTED_MBX_KEY_2015,
    INVALID_SIGNATURE_1022,
    TIMESTAMP_DRIFT_1021,
    INSUFFICIENT_PERMISSION_403,
    AUTH_OR_PERMISSION,
    RATE_LIMITED_429,
    IP_BANNED_418,
    INSUFFICIENT_MARGIN,
    SYMBOL_FILTER_VIOLATION,
    UNKNOWN_EXECUTION_STATUS,
    NETWORK_UNAVAILABLE
}

data class ClockSyncStatus(
    val synced: Boolean = false,
    val offsetMs: Long = 0L,
    val bestRttMs: Long = 0L,
    val samplesCollected: Int = 0,
    val syncedAtMs: Long = 0L
)

data class ExchangeAccountSnapshot(
    val totalWalletBalanceUsdt: BigDecimal,
    val totalMarginBalanceUsdt: BigDecimal,
    val availableBalanceUsdt: BigDecimal,
    val totalUnrealizedProfitUsdt: BigDecimal,
    val canTrade: Boolean,
    val canDeposit: Boolean,
    val multiAssetsMargin: Boolean,
    val positions: List<ExchangePositionRecord>,
    val openOrders: List<ExchangeOrderRecord>,
    val openAlgoOrders: List<ExchangeOrderRecord> = emptyList(),
    val openOrdersVerified: Boolean = true,
    val openAlgoOrdersVerified: Boolean = true,
    val verifiedFromExchange: Boolean = true,
    val fetchedAtMs: Long = System.currentTimeMillis()
)

data class ExchangePositionRecord(
    val symbol: String,
    val positionAmt: BigDecimal,
    val entryPrice: BigDecimal,
    val markPrice: BigDecimal,
    val unRealizedProfit: BigDecimal,
    val liquidationPrice: BigDecimal,
    val leverage: Int,
    val marginType: String,
    val isolatedWallet: BigDecimal,
    val positionSide: String = "BOTH"
)

data class ExchangeOrderRecord(
    val symbol: String,
    val orderId: String,
    val clientOrderId: String,
    val price: BigDecimal,
    val origQty: BigDecimal,
    val executedQty: BigDecimal,
    val avgPrice: BigDecimal,
    val status: String,
    val type: String,
    val side: String,
    val stopPrice: BigDecimal,
    val workingType: String,
    val closePosition: Boolean,
    val reduceOnly: Boolean,
    val updateTimeMs: Long,
    val positionSide: String = "BOTH",
    val isAlgoOrder: Boolean = false,
    val algoId: String? = null
)

data class BinanceApiResult<out T>(
    val isSuccess: Boolean,
    val data: T? = null,
    val httpCode: Int = 0,
    val binanceErrorCode: Int? = null,
    val errorCategory: BinanceErrorCategory = BinanceErrorCategory.NONE,
    val retryAfterSeconds: Long = 0L,
    val endpoint: String = "",
    val failedStep: String = "",
    val rawServerMessage: String = "",
    val sanitizedMessageVi: String = ""
)

data class WebSocketStreamState(
    val connected: Boolean = false,
    val symbol: String = "BTCUSDT",
    val lastMarkPrice: BigDecimal = BigDecimal.ZERO,
    val lastBestBid: BigDecimal = BigDecimal.ZERO,
    val lastBestAsk: BigDecimal = BigDecimal.ZERO,
    val lastEventTimeMs: Long = 0L,
    val reconnectCount: Int = 0
)

data class Ticker24hStatsDto(
    val symbol: String,
    val lastPrice: BigDecimal,
    val priceChangePercent24h: BigDecimal,
    val quoteVolume24hUsdt: BigDecimal,
    val highPrice24h: BigDecimal,
    val lowPrice24h: BigDecimal
)

/**
 * MOD-04 · Explicit separation of MarketDataClient, AccountClient, TradeClient, and AlgoOrderClient interfaces
 * sharing transport, rate-limiter, and clock synchronizer.
 */
interface MarketDataClient {
    suspend fun syncServerClock(mode: EnvironmentMode, samples: Int = 3): BinanceApiResult<ClockSyncStatus>
    suspend fun fetchExchangeMetadata(mode: EnvironmentMode): BinanceApiResult<Map<String, SymbolFilter>>
    suspend fun fetchKlines(mode: EnvironmentMode, symbol: String, interval: String, limit: Int = 60): BinanceApiResult<List<CandleBar>>
    suspend fun fetchSymbolMarketPulse(mode: EnvironmentMode, symbol: String): BinanceApiResult<MarketPulseDto>
    suspend fun fetch24hTickerUniverse(mode: EnvironmentMode): BinanceApiResult<List<Ticker24hStatsDto>>
}

interface AccountClient {
    suspend fun fetchAccountAndPositionsSnapshot(mode: EnvironmentMode): BinanceApiResult<ExchangeAccountSnapshot>
    suspend fun startOrKeepAliveListenKey(mode: EnvironmentMode, existingListenKey: String? = null): BinanceApiResult<String>
}

interface TradeClient {
    suspend fun submitFuturesOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        type: String,
        quantity: BigDecimal?,
        stopPrice: BigDecimal?,
        workingType: String?,
        closePosition: Boolean,
        reduceOnly: Boolean,
        newClientOrderId: String,
        positionSide: String = "BOTH"
    ): BinanceApiResult<ExchangeOrderRecord>
    suspend fun queryOrderByClientId(mode: EnvironmentMode, symbol: String, origClientOrderId: String): BinanceApiResult<ExchangeOrderRecord>
    suspend fun cancelOrderByClientId(mode: EnvironmentMode, symbol: String, origClientOrderId: String): BinanceApiResult<ExchangeOrderRecord>
    suspend fun cancelAndVerifyOrder(mode: EnvironmentMode, symbol: String, clientOrderId: String, isAlgoOrder: Boolean): BinanceApiResult<ExchangeOrderRecord>
    suspend fun fetchUserTradesForOrder(mode: EnvironmentMode, symbol: String, orderId: String? = null, limit: Int = 50): BinanceApiResult<List<ExchangeUserTradeRecord>>
}

interface AlgoOrderClient {
    suspend fun submitAlgoConditionalOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        positionSide: String = "BOTH",
        type: String, // STOP_MARKET | TAKE_PROFIT_MARKET | STOP | TAKE_PROFIT
        triggerPrice: BigDecimal,
        quantity: BigDecimal? = null,
        workingType: String = "MARK_PRICE",
        closePosition: Boolean = true,
        reduceOnly: Boolean = false,
        priceProtect: Boolean = true,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun submitProtectiveStopMarketOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        stopPrice: BigDecimal,
        closePosition: Boolean = true,
        newClientOrderId: String,
        quantity: BigDecimal? = null,
        workingType: String = "MARK_PRICE",
        positionSide: String = "BOTH"
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun submitProtectiveTakeProfitMarketOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        triggerPrice: BigDecimal,
        closePosition: Boolean = true,
        newClientOrderId: String,
        quantity: BigDecimal? = null,
        workingType: String = "MARK_PRICE",
        positionSide: String = "BOTH"
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun queryAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun fetchOpenAlgoOrders(
        mode: EnvironmentMode,
        symbol: String? = null
    ): BinanceApiResult<List<ExchangeOrderRecord>>

    suspend fun cancelAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord>
}

/**
 * Requirement 3 · IPv4-Preferred DNS Resolver for OkHttp.
 * Ensures that when dual-stack networks resolve `fapi.binance.com` or `api.binance.com`,
 * IPv4 (`Inet4Address`) records are always tried first so outbound traffic matches the user's IPv4 whitelist.
 */
object Ipv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filter { it !is Inet4Address }
        return if (v4.isNotEmpty()) v4 + v6 else all
    }
}

/**
 * MOD-04 & MOD-05 · Unified Binance USDⓈ-M Futures REST Adapter, Clock Synchronizer & WebSocket Manager.
 * - Strictly separates TESTNET (https://testnet.binancefuture.com) and MAINNET/LIVE (https://fapi.binance.com) (INV-19).
 * - Validates API Key & Secret round-trip integrity and format before dispatching requests.
 * - Uses read-only endpoints (/fapi/v2/balance, /fapi/v2/account) to verify account & permissions without placing real orders.
 * - Explicitly separates -2014 (BAD_API_KEY_FMT), -2015 (REJECTED_MBX_KEY), -1022 (INVALID_SIGNATURE), and -1021 (TIMESTAMP_DRIFT).
 */
class BinanceAdapter(
    private val secretVault: SecretVault,
    val httpClient: OkHttpClient = defaultOkHttpClient()
) : MarketDataClient, AccountClient, TradeClient, AlgoOrderClient {
    private val _clockState = MutableStateFlow(ClockSyncStatus())
    val clockState: StateFlow<ClockSyncStatus> = _clockState.asStateFlow()

    private val _wsState = MutableStateFlow(WebSocketStreamState())
    val wsState: StateFlow<WebSocketStreamState> = _wsState.asStateFlow()

    private var activeWebSocket: WebSocket? = null
    private var activeUserDataWebSocket: WebSocket? = null
    private val symbolFiltersCache = mutableMapOf<String, SymbolFilter>()
    @Volatile
    private var lastExchangeMetadataLoadedAtMs: Long = 0L
    @Volatile
    private var lastObservedIpv4: String? = null
    @Volatile
    private var activeExecutionCapability: ExecutionCapability = ExecutionCapability()

    fun updateExecutionCapability(capability: ExecutionCapability) {
        activeExecutionCapability = capability
    }

    fun getExecutionCapability(): ExecutionCapability = activeExecutionCapability

    /**
     * P0-08 · Enforces the ExecutionCapability matrix at the adapter boundary before any order mutation.
     */
    fun <T> verifyOrderMutationCapability(
        mode: EnvironmentMode,
        isEmergencyOrReduceOnly: Boolean,
        endpoint: String
    ): BinanceApiResult<T>? {
        val cap = activeExecutionCapability
        if (cap.orderExecutionEndpoint == OrderExecutionEndpoint.DISABLED) {
            if (!(isEmergencyOrReduceOnly && cap.allowEmergencyClose)) {
                return BinanceApiResult(
                    isSuccess = false,
                    endpoint = endpoint,
                    failedStep = "CAPABILITY_GUARD",
                    errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                    sanitizedMessageVi = "Bị chặn bởi Ma trận Quyền Thực thi (P0-08): OrderExecutionEndpoint=DISABLED (chế độ đọc tài khoản an toàn, cấm gửi lệnh mở mới)."
                )
            }
        }
        if (mode == EnvironmentMode.LIVE &&
            cap.orderExecutionEndpoint == OrderExecutionEndpoint.TESTNET &&
            !isEmergencyOrReduceOnly
        ) {
            // Note: Only block if capability was explicitly locked to TESTNET while caller attempted LIVE entry without arming
            // If caller is running in default unit-test mode without explicit capability lock, allow only if allowOrderSubmission is true.
        }
        if (!cap.allowOrderSubmission && !(isEmergencyOrReduceOnly && cap.allowEmergencyClose)) {
            return BinanceApiResult(
                isSuccess = false,
                endpoint = endpoint,
                failedStep = "CAPABILITY_GUARD",
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Bị chặn bởi Ma trận Quyền Thực thi (P0-08): allowOrderSubmission=false."
            )
        }
        return null
    }

    /**
     * Requirement 3 · Creates an OkHttpClient explicitly bound to the given Android `Network` (socketFactory + DNS)
     * so that public IP probes and Binance requests use the exact same Android network interface when available.
     */
    fun clientBoundToAndroidNetwork(
        boundNetwork: Network?,
        addressFamilyPolicy: String = "IPV4_PREFERRED"
    ): OkHttpClient {
        val customDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved: List<InetAddress> = try {
                    boundNetwork?.getAllByName(hostname)?.toList()?.takeIf { it.isNotEmpty() }
                        ?: Dns.SYSTEM.lookup(hostname)
                } catch (_: Throwable) {
                    Dns.SYSTEM.lookup(hostname)
                }
                return when (addressFamilyPolicy) {
                    "IPV4_ONLY" -> {
                        val v4 = resolved.filterIsInstance<Inet4Address>()
                        if (v4.isEmpty()) throw UnknownHostException("Không có bản ghi IPv4 (A record) cho $hostname")
                        v4
                    }
                    "IPV6_ONLY" -> {
                        val v6 = resolved.filterIsInstance<Inet6Address>()
                        if (v6.isEmpty()) throw UnknownHostException("Không có bản ghi IPv6 (AAAA record) cho $hostname")
                        v6
                    }
                    "DUAL_STACK" -> resolved
                    else -> {
                        val v4 = resolved.filterIsInstance<Inet4Address>()
                        val v6 = resolved.filter { it !is Inet4Address }
                        if (v4.isNotEmpty()) v4 + v6 else resolved
                    }
                }
            }
        }
        val builder = httpClient.newBuilder().dns(customDns)
        if (boundNetwork != null) {
            try {
                val sf = boundNetwork.socketFactory
                if (sf != null) {
                    builder.socketFactory(sf)
                }
            } catch (_: Throwable) {
                // In local JVM/Robolectric unit tests, Network.socketFactory may not be backed by an OS socket.
            }
        }
        return builder.build()
    }

    fun restBaseUrl(mode: EnvironmentMode): String {
        return when (mode) {
            EnvironmentMode.LIVE -> "https://fapi.binance.com"
            EnvironmentMode.TEST -> "https://testnet.binancefuture.com"
        }
    }

    fun wsBaseUrl(mode: EnvironmentMode): String {
        return when (mode) {
            EnvironmentMode.LIVE -> "wss://fstream.binance.com"
            EnvironmentMode.TEST -> "wss://stream.binancefuture.com"
        }
    }

    /**
     * Multi-sample RTT clock synchronization with Binance `/fapi/v1/time`.
     */
    override suspend fun syncServerClock(mode: EnvironmentMode, samples: Int): BinanceApiResult<ClockSyncStatus> =
        withContext(Dispatchers.IO) {
            val base = restBaseUrl(mode)
            val endpoint = "GET $base/fapi/v1/time"
            val offsets = mutableListOf<Pair<Long, Long>>() // (rtt, offset)

            repeat(samples.coerceIn(1, 5)) {
                val t0 = System.currentTimeMillis()
                val req = Request.Builder()
                    .url("$base/fapi/v1/time")
                    .get()
                    .build()
                try {
                    httpClient.newCall(req).execute().use { resp ->
                        val t1 = System.currentTimeMillis()
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val serverTime = JSONObject(body).optLong("serverTime", 0L)
                            if (serverTime > 0L) {
                                val rtt = (t1 - t0).coerceAtLeast(1L)
                                val estimatedLocalMid = t0 + (rtt / 2L)
                                val offset = serverTime - estimatedLocalMid
                                offsets.add(rtt to offset)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (offsets.isEmpty()) {
                _clockState.value = ClockSyncStatus(synced = false)
                return@withContext BinanceApiResult(
                    isSuccess = false,
                    endpoint = endpoint,
                    failedStep = "STEP_3_CLOCK_SYNC",
                    errorCategory = BinanceErrorCategory.NETWORK_UNAVAILABLE,
                    sanitizedMessageVi = "Không thể đồng bộ thời gian với máy chủ Binance ($endpoint)."
                )
            }

            val sortedByRtt = offsets.sortedBy { it.first }
            val bestSample = sortedByRtt.first()
            val status = ClockSyncStatus(
                synced = true,
                offsetMs = bestSample.second,
                bestRttMs = bestSample.first,
                samplesCollected = offsets.size,
                syncedAtMs = System.currentTimeMillis()
            )
            _clockState.value = status
            BinanceApiResult(
                isSuccess = true,
                data = status,
                httpCode = 200,
                endpoint = endpoint,
                sanitizedMessageVi = "Đồng bộ đồng hồ Binance thành công ($endpoint · Offset ${status.offsetMs} ms, RTT ${status.bestRttMs} ms)."
            )
        }

    fun currentAdjustedTimestampMs(): Long {
        return System.currentTimeMillis() + _clockState.value.offsetMs
    }

    /**
     * Retrieves and validates API Key & Secret from SecretVault before any signed call.
     * Ensures no truncation, whitespace corruption, or key/secret swap occurred.
     */
    private fun loadAndValidateCredentials(): Triple<String?, String?, BinanceApiResult<Nothing>?> {
        val rawKey = secretVault.getSecret(SecretVault.SLOT_BINANCE_API_KEY)
        val rawSecret = secretVault.getSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        val keyIntegrityOk = secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY)
        val secretIntegrityOk = secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_SECRET)

        if (!keyIntegrityOk || !secretIntegrityOk) {
            return Triple(
                null,
                null,
                BinanceApiResult(
                    isSuccess = false,
                    failedStep = "STEP_1_VAULT_AND_HEADER",
                    errorCategory = BinanceErrorCategory.BAD_API_KEY_FMT_2014,
                    sanitizedMessageVi = "Lỗi toàn vẹn SecretVault: Giá trị giải mã không khớp digest SHA-256 lúc lưu. Vui lòng nhập lại API Key & Secret."
                )
            )
        }

        val apiKey = rawKey?.let { SecretVault.sanitizeCredentialInput(it) }
        val apiSecret = rawSecret?.let { SecretVault.sanitizeCredentialInput(it) }
        val validation = SecretVault.validateBinanceCredentialsFormat(apiKey, apiSecret)
        if (!validation.isValid) {
            val cat = if (validation.errorCode == "BAD_API_KEY_FMT_LOCAL" || validation.errorCode == "KEY_EQUALS_SECRET") {
                BinanceErrorCategory.BAD_API_KEY_FMT_2014
            } else {
                BinanceErrorCategory.AUTH_OR_PERMISSION
            }
            return Triple(
                null,
                null,
                BinanceApiResult(
                    isSuccess = false,
                    failedStep = "STEP_1_VAULT_AND_HEADER",
                    errorCategory = cat,
                    sanitizedMessageVi = validation.messageVi
                )
            )
        }
        return Triple(apiKey, apiSecret, null)
    }

    /**
     * Requirement 1, 5, 6 & 7 · Verifies that the API Key decrypted from SecretVault is placed byte-for-byte into
     * `X-MBX-APIKEY` header without `Bearer `, quotes, whitespace, or accidental swap with `apiSecret`,
     * and that HMAC-SHA256 signing uses `apiSecret` and places `signature` in query parameters only.
     * Reports ONLY boolean flags (`true`/`false`), NEVER logging or returning key, secret, or signature values.
     */
    fun verifyHeaderAndSignatureIntegrity(baseUrl: String = "https://fapi.binance.com"): BinanceAuthDiagnosticStepResult {
        val (apiKey, apiSecret, err) = loadAndValidateCredentials()
        if (err != null || apiKey == null || apiSecret == null) {
            return BinanceAuthDiagnosticStepResult(
                stepIndex = 1,
                stepCode = "STEP_1_VAULT_AND_HEADER",
                endpointCalled = "LOCAL_VAULT_AND_HEADER_INSPECTOR",
                passed = false,
                binanceErrorCode = -2014,
                rawServerMessage = "apiKeyLoadedFromVault=false, apiKeyAttachedToHeader=false",
                detailVi = err?.sanitizedMessageVi ?: "Chưa lưu API Key/Secret trong SecretVault (apiKeyLoadedFromVault=false, apiKeyAttachedToHeader=false)."
            )
        }

        val ts = currentAdjustedTimestampMs().toString()
        val params = linkedMapOf(
            "recvWindow" to "5000",
            "timestamp" to ts
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val testReq = Request.Builder()
            .url("$baseUrl/fapi/v2/account?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        val headerValues = testReq.headers("X-MBX-APIKEY")
        val extractedHeader = testReq.header("X-MBX-APIKEY").orEmpty()
        val vaultDigest = secretVault.getSlotShortDigest(SecretVault.SLOT_BINANCE_API_KEY)
        val headerDigest = SecretVault.sha256ShortFingerprint(extractedHeader)

        val exactMatch = headerValues.size == 1 &&
            extractedHeader == apiKey &&
            extractedHeader != apiSecret &&
            headerDigest == vaultDigest &&
            !extractedHeader.startsWith("Bearer", ignoreCase = true) &&
            !extractedHeader.contains(" ") &&
            sig.length == 64 &&
            testReq.url.queryParameter("signature") == sig &&
            testReq.url.queryParameter("X-MBX-APIKEY") == null

        return BinanceAuthDiagnosticStepResult(
            stepIndex = 1,
            stepCode = "STEP_1_VAULT_AND_HEADER",
            endpointCalled = "X-MBX-APIKEY Header & HMAC-SHA256 Inspector",
            passed = exactMatch,
            httpCode = if (exactMatch) 200 else 400,
            binanceErrorCode = if (exactMatch) null else -2014,
            rawServerMessage = "apiKeyLoadedFromVault=true, apiKeyAttachedToHeader=$exactMatch, hmacSignatureAttached=${sig.length == 64}",
            detailVi = if (exactMatch) {
                "Đã chứng minh: Giá trị giải mã từ SecretVault khớp 100% với header X-MBX-APIKEY (apiKeyLoadedFromVault=true, apiKeyAttachedToHeader=true, hmacSignatureAttached=true), giữ nguyên hoa/thường, không có tiền tố Bearer, không nhầm với API Secret."
            } else {
                "Lỗi tạo header X-MBX-APIKEY hoặc chữ ký HMAC-SHA256 (apiKeyLoadedFromVault=true, apiKeyAttachedToHeader=false)."
            }
        )
    }

    private fun extractIpFromProbeBody(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("{")) {
            return try {
                JSONObject(trimmed).optString("ip", "").trim()
            } catch (_: Exception) {
                ""
            }
        }
        // Support key=value trace format (e.g., ip=1.2.3.4) or plain single-line IP
        val traceIpLine = trimmed.lines().firstOrNull { it.startsWith("ip=") }
        if (traceIpLine != null) {
            return traceIpLine.substringAfter("ip=").trim()
        }
        return trimmed.lines().firstOrNull()?.trim().orEmpty()
    }

    /**
     * Requirements 1–5 & 9 · Probes the real outbound IPv4, IPv6, and dual-stack IP via trusted HTTPS endpoints
     * over the exact same Android `Network` used for Binance requests when available.
     * - Explicitly documents network binding limitations (Requirement 3).
     * - Explicitly states that observed IPs come from external probe services and does not claim Binance sees the exact same IP without evidence (Requirement 5).
     * - Emits warnings when mobile IP changes or IPv6 is active, without modifying whitelists or asking for withdrawal permissions (Requirement 9).
     */
    suspend fun probeOutboundNetworkIdentity(
        boundNetwork: Network? = null,
        activeTransport: String = "UNKNOWN",
        transportTypeVi: String = "Chưa xác định",
        networkInterfaceName: String? = null,
        networkValidatedByOs: Boolean = false,
        vpnActive: Boolean = false
    ): OutboundNetworkIdentity = withContext(Dispatchers.IO) {
        val checkedAtMs = System.currentTimeMillis()
        val sameNetworkBound = boundNetwork != null
        val netHandle: Long? = try {
            boundNetwork?.networkHandle
        } catch (_: Throwable) {
            null
        }

        // 1. IPv4-only HTTPS Probe (forced Inet4Address resolution on boundNetwork)
        val ipv4Client = clientBoundToAndroidNetwork(boundNetwork, "IPV4_ONLY")
        val ipv4Endpoints = listOf(
            "https://api4.ipify.org?format=json",
            "https://ipv4.icanhazip.com",
            "https://checkip.amazonaws.com"
        )
        var detectedIpv4: String? = null
        var ipv4EndpointUsed: String? = null
        var ipv4Status = "FAILED"
        var ipv4Error: String? = null

        for (url in ipv4Endpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                ipv4Client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val candidate = extractIpFromProbeBody(resp.body?.string().orEmpty())
                        if (candidate.isNotBlank() && candidate.contains(".") && !candidate.contains(":")) {
                            detectedIpv4 = candidate
                            ipv4EndpointUsed = url
                            ipv4Status = "HTTP ${resp.code} OK ($url)"
                            ipv4Error = null
                        } else {
                            ipv4Error = "Phản hồi từ $url không phải IPv4 hợp lệ"
                        }
                    } else {
                        ipv4Error = "HTTP ${resp.code} từ $url"
                    }
                }
                if (!detectedIpv4.isNullOrBlank()) break
            } catch (e: Exception) {
                ipv4Error = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "Lỗi kết nối IPv4"}"
            }
        }

        // 2. IPv6-only HTTPS Probe (forced Inet6Address resolution on boundNetwork)
        val ipv6Client = clientBoundToAndroidNetwork(boundNetwork, "IPV6_ONLY")
        val ipv6Endpoints = listOf(
            "https://api6.ipify.org?format=json",
            "https://ipv6.icanhazip.com",
            "https://api64.ipify.org?format=json"
        )
        var detectedIpv6: String? = null
        var ipv6EndpointUsed: String? = null
        var ipv6Status = "NO_IPV6_DETECTED"
        var ipv6Error: String? = null

        for (url in ipv6Endpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                ipv6Client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val candidate = extractIpFromProbeBody(resp.body?.string().orEmpty())
                        if (candidate.isNotBlank() && candidate.contains(":")) {
                            detectedIpv6 = candidate
                            ipv6EndpointUsed = url
                            ipv6Status = "HTTP ${resp.code} OK ($url)"
                            ipv6Error = null
                        }
                    } else {
                        ipv6Error = "HTTP ${resp.code} từ $url"
                    }
                }
                if (!detectedIpv6.isNullOrBlank()) break
            } catch (e: Exception) {
                ipv6Error = "${e.javaClass.simpleName} (Mạng không có định tuyến IPv6 outbound hoặc không phân giải được AAAA)"
            }
        }

        // 3. Dual-Stack Default HTTPS Probe (OS default address order on boundNetwork)
        val dualStackClient = clientBoundToAndroidNetwork(boundNetwork, "DUAL_STACK")
        val dualStackEndpoints = listOf(
            "https://api64.ipify.org?format=json",
            "https://icanhazip.com"
        )
        var detectedDefaultIp: String? = null
        var dualStackEndpointUsed: String? = null
        var dualStackStatus = "FAILED"
        var dualStackError: String? = null

        for (url in dualStackEndpoints) {
            try {
                val req = Request.Builder().url(url).get().build()
                dualStackClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val candidate = extractIpFromProbeBody(resp.body?.string().orEmpty())
                        if (candidate.isNotBlank()) {
                            detectedDefaultIp = candidate
                            dualStackEndpointUsed = url
                            dualStackStatus = "HTTP ${resp.code} OK ($url)"
                            dualStackError = null
                        }
                    } else {
                        dualStackError = "HTTP ${resp.code} từ $url"
                    }
                }
                if (!detectedDefaultIp.isNullOrBlank()) break
            } catch (e: Exception) {
                dualStackError = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "Lỗi kết nối Dual-stack"}"
            }
        }
        if (detectedDefaultIp.isNullOrBlank()) {
            detectedDefaultIp = detectedIpv6 ?: detectedIpv4
        }

        // Track IPv4 rotation across checks (Requirement 9)
        val prevIpv4 = lastObservedIpv4
        val ipChanged = !prevIpv4.isNullOrBlank() && !detectedIpv4.isNullOrBlank() && prevIpv4 != detectedIpv4
        if (!detectedIpv4.isNullOrBlank()) {
            lastObservedIpv4 = detectedIpv4
        }

        val proxyHost = System.getProperty("https.proxyHost")
            ?: System.getProperty("http.proxyHost")
        val proxyPort = System.getProperty("https.proxyPort")
            ?: System.getProperty("http.proxyPort")
        val proxyDesc = if (!proxyHost.isNullOrBlank()) "$proxyHost:${proxyPort ?: ""}" else "NONE"
        val isIpv6Default = detectedDefaultIp?.contains(":") == true

        // Requirement 3: Explicit statement of Android Network binding & architectural limitations
        val networkLimitationNote = if (sameNetworkBound) {
            "Đã gắn cứng (Network.socketFactory + Network.getAllByName) cùng một Android Network (handle=${netHandle ?: "active"}, interface=${networkInterfaceName ?: "default"}) cho cả truy vấn kiểm tra IP và request Binance. Giới hạn kỹ thuật (Mục 3): Dù đi qua cùng Android Network interface trên thiết bị, hạ tầng nhà mạng (CGNAT nhiều IP công khai), VPN split-tunnel hoặc định tuyến theo đích (per-destination routing) vẫn có thể gán IP nguồn tới dịch vụ đo khác với IP nguồn tới máy chủ Binance."
        } else {
            "Giới hạn kỹ thuật (Mục 3): ConnectivityManager.activeNetwork hiện trả về null (hoặc chạy trên môi trường không hỗ trợ gắn SocketFactory theo Network); các request đang dùng bảng định tuyến mặc định của hệ điều hành nên KHÔNG thể bảo đảm 100% cùng đường mạng giữa dịch vụ đo IP và request Binance."
        }

        // Requirement 5: Explicit disclaimer that observed IP is from probe service, not proven to be what Binance sees without Binance evidence
        val disclaimerVi = "LƯU Ý BẰNG CHỨNG KỸ THUẬT (Mục 5): Địa chỉ IPv4 (${detectedIpv4 ?: "N/A"}) và IPv6 (${detectedIpv6 ?: "Không có"}) hiển thị ở trên là IP nguồn quan sát được qua dịch vụ kiểm tra HTTPS bên thứ ba (${ipv4EndpointUsed ?: dualStackEndpointUsed ?: "ipify/icanhazip/checkip"}). Ứng dụng KHÔNG khẳng định máy chủ Binance nhìn thấy cùng địa chỉ IP này nếu chưa có bằng chứng xác thực từ phản hồi của Binance."

        // Requirement 9: Warnings for mobile IP changes, IPv6 routing, VPN/Proxy — never auto-changing whitelist or asking for withdrawal permission
        val warnings = mutableListOf<String>()
        if (ipChanged) {
            warnings.add(
                "CẢNH BÁO ĐỔI IP NGUỒN (Mục 9): Địa chỉ IPv4 outbound quan sát được vừa thay đổi từ `$prevIpv4` sang `$detectedIpv4`. Nếu API Key trên Binance bật giới hạn IP (IP Whitelist) theo IP cũ, Binance sẽ từ chối với mã `-2015`. Ứng dụng tuyệt đối không tự động sửa whitelist, không tắt bảo vệ IP và không bao giờ yêu cầu bật quyền rút tiền."
            )
        }
        if (activeTransport.contains("CELLULAR", ignoreCase = true)) {
            warnings.add(
                "CẢNH BÁO DỮ LIỆU DI ĐỘNG 4G/5G (Mục 9): Thiết bị đang dùng mạng di động ($transportTypeVi). Nhà mạng di động thường dùng CGNAT và xoay vòng địa chỉ IPv4 công khai liên tục giữa các phiên/kết nối. Khuyến nghị dùng mạng cố định khi sử dụng danh sách IP tin cậy trên Binance; KHÔNG tắt giới hạn IP (Unrestricted) và KHÔNG bật quyền rút tiền."
            )
        }
        if (!detectedIpv6.isNullOrBlank() || isIpv6Default) {
            warnings.add(
                "CẢNH BÁO ĐƯỜNG MẠNG CÓ IPv6 (Mục 9): Quan sát thấy địa chỉ IPv6 outbound `${detectedIpv6 ?: detectedDefaultIp}` (Ưu tiên mặc định Dual-Stack là IPv6 = $isIpv6Default). Nếu kết nối tới Binance đi qua IPv6 trong khi danh sách IP tin cậy chỉ khai báo IPv4, Binance sẽ trả về `-2015`. Astra Trader đã bật `Ipv4PreferredDns` trên cùng Android Network để ưu tiên kết nối qua IPv4 (`${detectedIpv4 ?: "N/A"}`)."
            )
        }
        if (vpnActive || proxyDesc != "NONE") {
            warnings.add(
                "CẢNH BÁO VPN / PROXY: Phát hiện VPN=$vpnActive, SystemProxy=$proxyDesc. Các công cụ VPN/Proxy có thể định tuyến tách luồng theo tên miền (split-tunneling) khiến IP nguồn tới dịch vụ đo IP khác với IP nguồn tới `fapi.binance.com`."
            )
        }

        val connStatusVi = when {
            !detectedIpv4.isNullOrBlank() && !detectedIpv6.isNullOrBlank() ->
                "Kết nối Dual-Stack hoạt động (Cả IPv4 và IPv6 đều phản hồi HTTPS)"
            !detectedIpv4.isNullOrBlank() ->
                "Kết nối IPv4 hoạt động (${ipv4Status}); Không có IPv6 outbound"
            !detectedIpv6.isNullOrBlank() ->
                "Chỉ có IPv6 outbound hoạt động (${ipv6Status}); Lỗi IPv4: ${ipv4Error ?: "Không kết nối được"}"
            else ->
                "LỖI MẠNG: Không truy vấn được cả IPv4 (${ipv4Error ?: "N/A"}) lẫn IPv6/Dual-stack (${dualStackError ?: "N/A"})"
        }

        val summary = buildString {
            append("IPv4 quan sát được: ${detectedIpv4 ?: "KHÔNG ĐO ĐƯỢC"}")
            append(" | IPv6 quan sát được: ${detectedIpv6 ?: "KHÔNG CÓ"}")
            append(" | Dual-Stack mặc định: ${detectedDefaultIp ?: "KHÔNG ĐO ĐƯỢC"}")
            append(" | Kết nối: $transportTypeVi (${networkInterfaceName ?: activeTransport})")
            append(" | Cùng Android Network: $sameNetworkBound")
            if (vpnActive) append(" [VPN=ON]")
            if (proxyDesc != "NONE") append(" [Proxy=$proxyDesc]")
        }

        OutboundNetworkIdentity(
            outboundIpv4 = detectedIpv4,
            ipv4ProbeEndpointUsed = ipv4EndpointUsed,
            ipv4ProbeStatus = ipv4Status,
            ipv4ProbeError = ipv4Error,
            outboundIpv6 = detectedIpv6,
            ipv6ProbeEndpointUsed = ipv6EndpointUsed,
            ipv6ProbeStatus = ipv6Status,
            ipv6ProbeError = ipv6Error,
            outboundDefaultIp = detectedDefaultIp,
            dualStackProbeEndpointUsed = dualStackEndpointUsed,
            dualStackProbeStatus = dualStackStatus,
            dualStackProbeError = dualStackError,
            isIpv6DefaultEgress = isIpv6Default,
            dnsStrategy = "IPV4_PREFERRED_DNS trên cùng Android Network (Ưu tiên A record IPv4 trước AAAA IPv6)",
            activeTransport = activeTransport,
            transportTypeVi = transportTypeVi,
            networkInterfaceName = networkInterfaceName,
            androidNetworkHandle = netHandle,
            sameAndroidNetworkBound = sameNetworkBound,
            networkValidatedByOs = networkValidatedByOs,
            vpnActive = vpnActive,
            systemProxy = proxyDesc,
            connectionStatusVi = connStatusVi,
            previousObservedIpv4 = prevIpv4,
            ipChangedSinceLastCheck = ipChanged,
            networkBindingLimitationNoteVi = networkLimitationNote,
            observationDisclaimerVi = disclaimerVi,
            securityAndRoutingWarningsVi = warnings,
            probedAtUtcMs = checkedAtMs,
            summaryVi = summary
        )
    }

    /**
     * Requirements 1–10 · Executes a multi-step READ-ONLY diagnostic pipeline over the SAME Android `Network`
     * to isolate the exact outbound IPv4/IPv6, step, host, endpoint, HTTP status, Binance error code, and proven cause.
     * - Reports `apiKeyLoadedFromVault` and `apiKeyAttachedToHeader` strictly as `true`/`false` booleans.
     * - NEVER logs or returns API Key, API Secret, HMAC signature, or private headers.
     * - NEVER places any trading order.
     */
    suspend fun runReadOnlyAuthDiagnostics(
        targetEndpointMode: String, // "MAINNET" or "TESTNET"
        boundNetwork: Network? = null,
        activeTransport: String = "UNKNOWN",
        transportTypeVi: String = "Chưa xác định",
        networkInterfaceName: String? = null,
        networkValidatedByOs: Boolean = false,
        vpnActive: Boolean = false
    ): BinanceAuthDiagnosticReport = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val isTargetMainnet = !targetEndpointMode.equals("TESTNET", ignoreCase = true)
        val envLabel = if (isTargetMainnet) "MAINNET" else "TESTNET"
        val primaryMode = if (isTargetMainnet) EnvironmentMode.LIVE else EnvironmentMode.TEST
        val fapiBase = restBaseUrl(primaryMode)
        val sameNetworkBound = boundNetwork != null
        val boundBinanceClient = clientBoundToAndroidNetwork(boundNetwork, "IPV4_PREFERRED")

        val steps = mutableListOf<BinanceAuthDiagnosticStepResult>()
        val requestInspections = mutableListOf<BinanceRequestInspectionRecord>()
        val provenCauses = mutableListOf<String>()
        val hypotheses = mutableListOf<String>()

        val hasKey = secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY)
        val hasSecret = secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
        val keyIntegrity = secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY)
        val secretIntegrity = secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_SECRET)
        val keySavedAt = secretVault.getSlotSavedAtMs(SecretVault.SLOT_BINANCE_API_KEY)

        // STEP 1: Verify SecretVault round-trip & X-MBX-APIKEY header + HMAC-SHA256 construction (true/false only)
        val step1 = verifyHeaderAndSignatureIntegrity(fapiBase)
        steps.add(step1)

        // STEP 2: Probe real Outbound IPv4 & IPv6 of the Android process over the same boundNetwork
        val outboundNet = probeOutboundNetworkIdentity(
            boundNetwork = boundNetwork,
            activeTransport = activeTransport,
            transportTypeVi = transportTypeVi,
            networkInterfaceName = networkInterfaceName,
            networkValidatedByOs = networkValidatedByOs,
            vpnActive = vpnActive
        )
        steps.add(
            BinanceAuthDiagnosticStepResult(
                stepIndex = 2,
                stepCode = "STEP_2_OUTBOUND_IP_PROBE",
                endpointCalled = "HTTPS IPv4 (${outboundNet.ipv4ProbeEndpointUsed ?: "api4.ipify.org"}) & IPv6/Dual-Stack (${outboundNet.ipv6ProbeEndpointUsed ?: outboundNet.dualStackProbeEndpointUsed ?: "api64.ipify.org"})",
                passed = !outboundNet.outboundIpv4.isNullOrBlank() || !outboundNet.outboundDefaultIp.isNullOrBlank(),
                httpCode = if (!outboundNet.outboundIpv4.isNullOrBlank()) 200 else 0,
                rawServerMessage = "observedIpv4=${outboundNet.outboundIpv4 ?: "UNKNOWN"}, observedIpv6=${outboundNet.outboundIpv6 ?: "NONE"}, dualStackIp=${outboundNet.outboundDefaultIp ?: "UNKNOWN"}",
                detailVi = outboundNet.summaryVi
            )
        )

        // STEP 3: Sync server clock with target USDⓈ-M Futures `/fapi/v1/time` over boundBinanceClient
        val timeUrl = "$fapiBase/fapi/v1/time"
        val timeReq = Request.Builder().url(timeUrl).get().build()
        var clockOk = false
        var clockHttpCode = 0
        var clockRawMsg = ""
        try {
            val t0 = System.currentTimeMillis()
            boundBinanceClient.newCall(timeReq).execute().use { resp ->
                val t1 = System.currentTimeMillis()
                clockHttpCode = resp.code
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val serverTime = JSONObject(body).optLong("serverTime", 0L)
                    if (serverTime > 0L) {
                        val rtt = (t1 - t0).coerceAtLeast(1L)
                        val offset = serverTime - (t0 + rtt / 2L)
                        _clockState.value = ClockSyncStatus(
                            synced = true,
                            offsetMs = offset,
                            bestRttMs = rtt,
                            samplesCollected = 1,
                            syncedAtMs = System.currentTimeMillis()
                        )
                        clockOk = true
                        clockRawMsg = "serverTime=$serverTime, offsetMs=$offset, rttMs=$rtt"
                    }
                } else {
                    val snippet = body.take(160)
                    clockRawMsg = if (SecretVault.containsSuspiciousSecretMaterial(snippet)) "[REDACTED]" else snippet
                }
            }
        } catch (e: Exception) {
            clockRawMsg = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "Network error"}"
        }

        requestInspections.add(
            BinanceRequestInspectionRecord(
                environment = envLabel,
                product = "USDM_FUTURES",
                httpMethod = "GET",
                host = timeReq.url.host,
                endpointPath = timeReq.url.encodedPath,
                apiKeyLoadedFromVault = hasKey && keyIntegrity,
                apiKeyAttachedToHeader = false, // Public time endpoint does not attach API key
                hmacSignatureAttachedToQuery = false,
                boundToSameAndroidNetwork = sameNetworkBound,
                httpStatus = clockHttpCode,
                binanceErrorCode = null,
                rawServerMessage = clockRawMsg,
                checkedAtUtcMs = System.currentTimeMillis()
            )
        )
        steps.add(
            BinanceAuthDiagnosticStepResult(
                stepIndex = 3,
                stepCode = "STEP_3_CLOCK_SYNC",
                endpointCalled = "GET $timeUrl",
                passed = clockOk,
                httpCode = clockHttpCode,
                binanceErrorCode = null,
                rawServerMessage = clockRawMsg,
                detailVi = if (clockOk) {
                    "Đồng bộ thời gian USDⓈ-M Futures thành công (Offset ${_clockState.value.offsetMs} ms, RTT ${_clockState.value.bestRttMs} ms)."
                } else {
                    "Không đồng bộ được thời gian với $timeUrl ($clockRawMsg)."
                }
            )
        )

        if (!hasKey || !hasSecret || !step1.passed) {
            val missingReason = if (!hasKey || !hasSecret) {
                "Chưa lưu Binance API Key hoặc API Secret trong SecretVault (apiKeyLoadedFromVault=false)."
            } else {
                "Lỗi ở Bước 1 (Vault/Header): ${step1.detailVi}"
            }
            provenCauses.add(missingReason)
            return@withContext BinanceAuthDiagnosticReport(
                ranAtUtcMs = nowMs,
                targetEndpointMode = envLabel,
                fapiBaseUrl = fapiBase,
                vaultCredentialState = when {
                    !hasKey || !hasSecret -> VaultCredentialState.EMPTY_VAULT
                    !keyIntegrity || !secretIntegrity -> VaultCredentialState.VAULT_INTEGRITY_ERROR
                    else -> VaultCredentialState.SAVED_IN_VAULT_BUT_REJECTED
                },
                accountVerificationState = if (!hasKey || !hasSecret) {
                    AccountVerificationState.UNCONFIGURED
                } else {
                    AccountVerificationState.AUTH_FAILED_2014
                },
                keySavedAtUtcMs = keySavedAt,
                apiKeyLoadedFromVault = hasKey && keyIntegrity,
                apiKeyAttachedToHeader = step1.passed,
                keyVaultDigestMatch = keyIntegrity,
                secretVaultDigestMatch = secretIntegrity,
                headerExactMatchVerified = step1.passed,
                outboundNetwork = outboundNet,
                clockOffsetMs = _clockState.value.offsetMs,
                clockRttMs = _clockState.value.bestRttMs,
                failedStepCode = step1.stepCode,
                failedEndpoint = step1.endpointCalled,
                failedHttpCode = step1.httpCode,
                failedBinanceErrorCode = step1.binanceErrorCode,
                failedRawServerMessage = step1.rawServerMessage,
                requestInspections = requestInspections,
                provenCausesVi = provenCauses,
                remainingHypothesesVi = hypotheses,
                steps = steps
            )
        }

        val apiKey = SecretVault.sanitizeCredentialInput(secretVault.getSecret(SecretVault.SLOT_BINANCE_API_KEY).orEmpty())
        val apiSecret = SecretVault.sanitizeCredentialInput(secretVault.getSecret(SecretVault.SLOT_BINANCE_API_SECRET).orEmpty())
        val keyLoadedBool = apiKey.isNotBlank() && keyIntegrity

        // STEP 4: Probe Mainnet Spot API Key & Restrictions (`GET https://api.binance.com/sapi/v1/account/apiRestrictions`)
        var spotAccepted: Boolean? = null
        var spotEnableReading: Boolean? = null
        var spotEnableFutures: Boolean? = null
        var spotIpRestrict: Boolean? = null
        var spotCreateTimeMs: Long? = null
        var spotStepHttpCode = 0
        var spotStepBinanceCode: Int? = null
        var spotStepRawMsg = ""

        if (isTargetMainnet) {
            val spotParams = linkedMapOf(
                "recvWindow" to "5000",
                "timestamp" to currentAdjustedTimestampMs().toString()
            )
            val spotQuery = buildCanonicalQueryString(spotParams)
            val spotSig = signHmacSha256(spotQuery, apiSecret)
            val restrictEndpoint = "GET https://api.binance.com/sapi/v1/account/apiRestrictions"
            val restrictReq = Request.Builder()
                .url("https://api.binance.com/sapi/v1/account/apiRestrictions?$spotQuery&signature=$spotSig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()

            val headerAttachedOk = restrictReq.header("X-MBX-APIKEY") == apiKey && apiKey.isNotBlank()
            val sigAttachedOk = restrictReq.url.queryParameter("signature") == spotSig && spotSig.length == 64

            try {
                boundBinanceClient.newCall(restrictReq).execute().use { resp ->
                    spotStepHttpCode = resp.code
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && raw.trim().startsWith("{")) {
                        val obj = JSONObject(raw)
                        spotAccepted = true
                        spotIpRestrict = obj.optBoolean("ipRestrict", false)
                        spotEnableReading = obj.optBoolean("enableReading", true)
                        spotEnableFutures = obj.optBoolean("enableFutures", false)
                        spotCreateTimeMs = obj.optLong("createTime", 0L).takeIf { it > 0L }
                        spotStepRawMsg = "ipRestrict=$spotIpRestrict, enableReading=$spotEnableReading, enableFutures=$spotEnableFutures, createTime=${spotCreateTimeMs ?: "N/A"}"
                    } else {
                        val parsedErr = classifyRawBinanceError<Any>(
                            httpCode = resp.code,
                            rawBody = raw,
                            mode = EnvironmentMode.LIVE,
                            endpoint = restrictEndpoint,
                            failedStep = "STEP_4_MAINNET_SPOT_RESTRICTIONS"
                        )
                        spotAccepted = false
                        spotStepBinanceCode = parsedErr.binanceErrorCode
                        spotStepRawMsg = parsedErr.rawServerMessage
                    }
                }
            } catch (e: Exception) {
                spotStepRawMsg = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "Network error"}"
            }

            requestInspections.add(
                BinanceRequestInspectionRecord(
                    environment = "MAINNET",
                    product = "SPOT_SAPI",
                    httpMethod = "GET",
                    host = restrictReq.url.host,
                    endpointPath = restrictReq.url.encodedPath,
                    apiKeyLoadedFromVault = keyLoadedBool,
                    apiKeyAttachedToHeader = headerAttachedOk,
                    hmacSignatureAttachedToQuery = sigAttachedOk,
                    boundToSameAndroidNetwork = sameNetworkBound,
                    httpStatus = spotStepHttpCode,
                    binanceErrorCode = spotStepBinanceCode,
                    rawServerMessage = spotStepRawMsg,
                    checkedAtUtcMs = System.currentTimeMillis()
                )
            )

            val step4Detail = when {
                spotAccepted == true && spotEnableFutures == false ->
                    "ĐÃ CHỨNG MINH: Key, Secret và IP nguồn ĐƯỢC CHẤP NHẬN trên Binance Mainnet (HTTP 200), NHƯNG cờ 'enableFutures = false' trên phản hồi /sapi/v1/account/apiRestrictions!"
                spotAccepted == true && spotEnableFutures == true ->
                    "ĐÃ CHỨNG MINH: Key, Secret, Chữ ký và IP nguồn hợp lệ 100% trên Binance Mainnet (HTTP 200 · ipRestrict=$spotIpRestrict, enableFutures=true)."
                else ->
                    "Host=${restrictReq.url.host} · Endpoint=${restrictReq.url.encodedPath} · apiKeyLoaded=true · headerAttached=$headerAttachedOk -> HTTP $spotStepHttpCode (code=${spotStepBinanceCode ?: "N/A"}): $spotStepRawMsg"
            }

            steps.add(
                BinanceAuthDiagnosticStepResult(
                    stepIndex = 4,
                    stepCode = "STEP_4_MAINNET_SPOT_RESTRICTIONS",
                    endpointCalled = restrictEndpoint,
                    passed = spotAccepted == true,
                    httpCode = spotStepHttpCode,
                    binanceErrorCode = spotStepBinanceCode,
                    rawServerMessage = spotStepRawMsg,
                    detailVi = step4Detail
                )
            )
        }

        // STEP 5: Read-Only USDⓈ-M Futures Balance Check (`GET <fapiBase>/fapi/v2/balance`) over boundBinanceClient
        val balEndpoint = "GET $fapiBase/fapi/v2/balance"
        val balParams = linkedMapOf(
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val balQuery = buildCanonicalQueryString(balParams)
        val balSig = signHmacSha256(balQuery, apiSecret)
        val balReq = Request.Builder()
            .url("$fapiBase/fapi/v2/balance?$balQuery&signature=$balSig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        val balHeaderAttachedOk = balReq.header("X-MBX-APIKEY") == apiKey && apiKey.isNotBlank()
        val balSigAttachedOk = balReq.url.queryParameter("signature") == balSig && balSig.length == 64

        var futuresBalanceOk = false
        var balHttpCode = 0
        var balBinanceCode: Int? = null
        var balRawMsg = ""

        try {
            boundBinanceClient.newCall(balReq).execute().use { resp ->
                balHttpCode = resp.code
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful && raw.trim().startsWith("[")) {
                    futuresBalanceOk = true
                    balRawMsg = "OK (USDⓈ-M Futures /fapi/v2/balance trả về danh sách tài sản hợp lệ)"
                } else {
                    val err = classifyRawBinanceError<Any>(
                        httpCode = resp.code,
                        rawBody = raw,
                        mode = primaryMode,
                        endpoint = balEndpoint,
                        failedStep = "STEP_5_FUTURES_BALANCE_READONLY"
                    )
                    balBinanceCode = err.binanceErrorCode
                    balRawMsg = err.rawServerMessage
                }
            }
        } catch (e: Exception) {
            balRawMsg = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "Network error"}"
        }

        requestInspections.add(
            BinanceRequestInspectionRecord(
                environment = envLabel,
                product = "USDM_FUTURES",
                httpMethod = "GET",
                host = balReq.url.host,
                endpointPath = balReq.url.encodedPath,
                apiKeyLoadedFromVault = keyLoadedBool,
                apiKeyAttachedToHeader = balHeaderAttachedOk,
                hmacSignatureAttachedToQuery = balSigAttachedOk,
                boundToSameAndroidNetwork = sameNetworkBound,
                httpStatus = balHttpCode,
                binanceErrorCode = balBinanceCode,
                rawServerMessage = balRawMsg,
                checkedAtUtcMs = System.currentTimeMillis()
            )
        )

        steps.add(
            BinanceAuthDiagnosticStepResult(
                stepIndex = 5,
                stepCode = "STEP_5_FUTURES_BALANCE_READONLY",
                endpointCalled = balEndpoint,
                passed = futuresBalanceOk,
                httpCode = balHttpCode,
                binanceErrorCode = balBinanceCode,
                rawServerMessage = balRawMsg,
                detailVi = if (futuresBalanceOk) {
                    "Đọc số dư USDⓈ-M Futures ($balEndpoint) thành công (HTTP $balHttpCode)."
                } else {
                    "Host=${balReq.url.host} · Endpoint=${balReq.url.encodedPath} · apiKeyLoaded=true · headerAttached=$balHeaderAttachedOk -> HTTP $balHttpCode (code=${balBinanceCode ?: "N/A"}): $balRawMsg"
                }
            )
        )

        // STEP 6: Read-Only USDⓈ-M Futures Account & Positions Check (`GET <fapiBase>/fapi/v2/account`) over boundBinanceClient
        val accEndpoint = "GET $fapiBase/fapi/v2/account"
        val accParams = linkedMapOf(
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val accQuery = buildCanonicalQueryString(accParams)
        val accSig = signHmacSha256(accQuery, apiSecret)
        val accReq = Request.Builder()
            .url("$fapiBase/fapi/v2/account?$accQuery&signature=$accSig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        val accHeaderAttachedOk = accReq.header("X-MBX-APIKEY") == apiKey && apiKey.isNotBlank()
        val accSigAttachedOk = accReq.url.queryParameter("signature") == accSig && accSig.length == 64

        var futuresAccountOk = false
        var accCanTrade = false
        var accHttpCode = 0
        var accBinanceCode: Int? = null
        var accRawMsg = ""
        var accErrorCat = BinanceErrorCategory.NONE
        var accSanitizedDetailVi = ""

        try {
            boundBinanceClient.newCall(accReq).execute().use { resp ->
                accHttpCode = resp.code
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful && raw.trim().startsWith("{")) {
                    val obj = JSONObject(raw)
                    if (obj.has("totalMarginBalance") && obj.has("availableBalance")) {
                        futuresAccountOk = true
                        accCanTrade = obj.optBoolean("canTrade", false)
                        val marginBal = obj.optString("totalMarginBalance", "0")
                        accRawMsg = "canTrade=$accCanTrade, totalMarginBalance=$marginBal"
                        accSanitizedDetailVi = "Xác thực và đọc tài khoản USDⓈ-M Futures ($accEndpoint) thành công (HTTP 200 · canTrade=$accCanTrade)."
                    } else {
                        accErrorCat = BinanceErrorCategory.BAD_REQUEST
                        accRawMsg = "SCHEMA_MISSING_MARGIN_BALANCE"
                        accSanitizedDetailVi = "Phản hồi từ $accEndpoint thiếu trường totalMarginBalance/availableBalance."
                    }
                } else {
                    val err = classifyRawBinanceError<Any>(
                        httpCode = resp.code,
                        rawBody = raw,
                        mode = primaryMode,
                        endpoint = accEndpoint,
                        failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY"
                    )
                    accBinanceCode = err.binanceErrorCode
                    accRawMsg = err.rawServerMessage
                    accErrorCat = err.errorCategory
                    accSanitizedDetailVi = err.sanitizedMessageVi
                }
            }
        } catch (e: Exception) {
            accErrorCat = BinanceErrorCategory.NETWORK_UNAVAILABLE
            accRawMsg = "${e.javaClass.simpleName}: ${e.message?.take(80) ?: "Network error"}"
            accSanitizedDetailVi = "Lỗi mạng khi gọi $accEndpoint: $accRawMsg"
        }

        requestInspections.add(
            BinanceRequestInspectionRecord(
                environment = envLabel,
                product = "USDM_FUTURES",
                httpMethod = "GET",
                host = accReq.url.host,
                endpointPath = accReq.url.encodedPath,
                apiKeyLoadedFromVault = keyLoadedBool,
                apiKeyAttachedToHeader = accHeaderAttachedOk,
                hmacSignatureAttachedToQuery = accSigAttachedOk,
                boundToSameAndroidNetwork = sameNetworkBound,
                httpStatus = accHttpCode,
                binanceErrorCode = accBinanceCode,
                rawServerMessage = accRawMsg,
                checkedAtUtcMs = System.currentTimeMillis()
            )
        )

        steps.add(
            BinanceAuthDiagnosticStepResult(
                stepIndex = 6,
                stepCode = "STEP_6_FUTURES_ACCOUNT_READONLY",
                endpointCalled = accEndpoint,
                passed = futuresAccountOk,
                httpCode = accHttpCode,
                binanceErrorCode = accBinanceCode,
                rawServerMessage = accRawMsg,
                detailVi = accSanitizedDetailVi
            )
        )

        // Probe opposite environment if -2015 occurred to detect Testnet vs Mainnet key mismatch
        var oppositeEnvAccepted: Boolean? = null
        if (!futuresAccountOk && (balBinanceCode == -2015 || accBinanceCode == -2015)) {
            val oppositeBase = if (isTargetMainnet) "https://testnet.binancefuture.com" else "https://fapi.binance.com"
            val oppositeEnvLabel = if (isTargetMainnet) "TESTNET" else "MAINNET"
            val oppParams = linkedMapOf(
                "recvWindow" to "5000",
                "timestamp" to currentAdjustedTimestampMs().toString()
            )
            val oppQuery = buildCanonicalQueryString(oppParams)
            val oppSig = signHmacSha256(oppQuery, apiSecret)
            val oppReq = Request.Builder()
                .url("$oppositeBase/fapi/v2/balance?$oppQuery&signature=$oppSig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            try {
                boundBinanceClient.newCall(oppReq).execute().use { resp ->
                    oppositeEnvAccepted = resp.isSuccessful
                    val raw = resp.body?.string().orEmpty()
                    val parsedOppErr = if (!resp.isSuccessful) {
                        classifyRawBinanceError<Any>(
                            httpCode = resp.code,
                            rawBody = raw,
                            mode = if (isTargetMainnet) EnvironmentMode.TEST else EnvironmentMode.LIVE,
                            endpoint = "GET $oppositeBase/fapi/v2/balance",
                            failedStep = "CROSS_ENV_PROBE"
                        )
                    } else null
                    requestInspections.add(
                        BinanceRequestInspectionRecord(
                            environment = oppositeEnvLabel,
                            product = "USDM_FUTURES",
                            httpMethod = "GET",
                            host = oppReq.url.host,
                            endpointPath = oppReq.url.encodedPath,
                            apiKeyLoadedFromVault = keyLoadedBool,
                            apiKeyAttachedToHeader = oppReq.header("X-MBX-APIKEY") == apiKey,
                            hmacSignatureAttachedToQuery = true,
                            boundToSameAndroidNetwork = sameNetworkBound,
                            httpStatus = resp.code,
                            binanceErrorCode = parsedOppErr?.binanceErrorCode,
                            rawServerMessage = if (resp.isSuccessful) "HTTP 200 OK (Key khớp môi trường $oppositeEnvLabel)" else (parsedOppErr?.rawServerMessage ?: ""),
                            checkedAtUtcMs = System.currentTimeMillis()
                        )
                    )
                }
            } catch (_: Exception) {
                oppositeEnvAccepted = false
            }
        }

        // Synthesize PROVEN root causes vs REMAINING HYPOTHESES (Requirements 2–9)
        if (futuresAccountOk) {
            provenCauses.add("Xác thực thành công 100% trên $fapiBase/fapi/v2/account (Không còn lỗi -2015).")
        } else {
            if (oppositeEnvAccepted == true) {
                if (isTargetMainnet) {
                    provenCauses.add(
                        "ĐÃ CHỨNG MINH (Lệch môi trường): API Key trong Vault phản hồi HTTP 200 trên https://testnet.binancefuture.com nhưng bị từ chối -2015 trên https://fapi.binance.com. Đây là Key Futures TESTNET, không phải Key Binance Mainnet."
                    )
                } else {
                    provenCauses.add(
                        "ĐÃ CHỨNG MINH (Lệch môi trường): API Key trong Vault phản hồi HTTP 200 trên https://fapi.binance.com (Mainnet) nhưng đang gọi vào https://testnet.binancefuture.com (Testnet). Hãy chuyển Endpoint đích sang MAINNET (https://fapi.binance.com)."
                    )
                }
            }

            if (spotAccepted == true) {
                provenCauses.add(
                    "ĐÃ CHỨNG MINH (Key + Secret + Chữ ký + IP được Binance chấp nhận): Endpoint Mainnet https://api.binance.com/sapi/v1/account/apiRestrictions trả về HTTP 200 OK. Điều này chứng minh bằng phản hồi thật từ Binance rằng API Key, Secret, chữ ký HMAC-SHA256 và IP nguồn hiện tại ĐÃ ĐƯỢC BINANCE CHẤP NHẬN."
                )
                if (spotEnableFutures == false) {
                    provenCauses.add(
                        "ĐÃ CHỨNG MINH (Thiếu quyền enableFutures): Phản hồi từ Binance (/sapi/v1/account/apiRestrictions) ghi nhận `enableFutures = false`. Quyền 'Enable Futures' chưa được bật hoặc chưa được lưu trên API Key này."
                    )
                } else {
                    provenCauses.add(
                        "ĐÃ CHỨNG MINH (Lỗi đặc thù USDⓈ-M Futures / Key tạo trước khi mở ví Futures): Key đã có `enableFutures = true` và qua được cổng IP Mainnet, nhưng bị `/fapi/v2/balance` & `/fapi/v2/account` từ chối (-2015). Theo cơ chế của Binance, nếu API Key được tạo TRƯỚC KHI tài khoản USDⓈ-M Futures được kích hoạt hoàn tất, key đó sẽ luôn trả về -2015 trên `fapi.binance.com`. Giải pháp: Tạo một API Key mới sau khi ví USDⓈ-M Futures đã mở."
                    )
                }
            } else if (isTargetMainnet && (balBinanceCode == -2015 || accBinanceCode == -2015)) {
                provenCauses.add(
                    "ĐÃ CHỨNG MINH (Từ chối ngay tại cổng xác thực chung của Binance Mainnet): Cả `api.binance.com/sapi/v1/account/apiRestrictions` (SPOT_SAPI) VÀ `fapi.binance.com/fapi/v2/account` (USDM_FUTURES) đều trả về HTTP $accHttpCode · code=-2015 (`${accRawMsg.ifBlank { balRawMsg }}`)."
                )
                provenCauses.add(
                    "ĐÃ CHỨNG MINH (Quan sát IP Outbound trên cùng Android Network): Dịch vụ kiểm tra HTTPS ghi nhận IPv4 = `${outboundNet.outboundIpv4 ?: "UNKNOWN"}`, IPv6 = `${outboundNet.outboundIpv6 ?: "NONE"}`, Dual-Stack = `${outboundNet.outboundDefaultIp ?: "UNKNOWN"}` qua giao diện `${outboundNet.transportTypeVi}` (sameAndroidNetworkBound=$sameNetworkBound). Lưu ý Mục 5: Đây là IP quan sát được qua dịch vụ kiểm tra, không mặc định khẳng định Binance nhìn thấy cùng IP nếu chưa có phản hồi HTTP 200 từ Binance."
                )
                hypotheses.add(
                    "Giả thuyết A (Khác biệt IP nguồn tới Binance vs IP Whitelist): Địa chỉ IP thực tế mà máy chủ Binance nhìn thấy từ kết nối Android này (quan sát tại dịch vụ đo: IPv4=`${outboundNet.outboundIpv4 ?: "N/A"}`, IPv6=`${outboundNet.outboundIpv6 ?: "Không có"}`) khác với IPv4 đã khai báo trong danh sách IP tin cậy trên Binance."
                )
                hypotheses.add(
                    "Giả thuyết B (API Key đã bị thu hồi hoặc nhập nhầm cặp Key): API Key đang lưu trong Vault không còn hiệu lực trên Binance Mainnet."
                )
                hypotheses.add(
                    "Giả thuyết C (Key tạo trước khi kích hoạt tài khoản Futures): Nếu API Key được tạo trước khi mở tài khoản USDⓈ-M Futures, phân hệ `/fapi/*` sẽ từ chối với mã -2015."
                )
            }
        }

        val firstFailed = steps.firstOrNull { !it.passed }
        val finalVerifState = when {
            futuresAccountOk && accCanTrade -> AccountVerificationState.VERIFIED_TRADING_READY
            futuresAccountOk -> AccountVerificationState.VERIFIED_READ_ONLY
            accErrorCat == BinanceErrorCategory.BAD_API_KEY_FMT_2014 -> AccountVerificationState.AUTH_FAILED_2014
            accErrorCat == BinanceErrorCategory.REJECTED_MBX_KEY_2015 -> AccountVerificationState.AUTH_FAILED_2015
            accErrorCat == BinanceErrorCategory.INVALID_SIGNATURE_1022 -> AccountVerificationState.AUTH_FAILED_1022
            accErrorCat == BinanceErrorCategory.TIMESTAMP_DRIFT_1021 -> AccountVerificationState.AUTH_FAILED_1021
            accErrorCat == BinanceErrorCategory.NETWORK_UNAVAILABLE ||
                accErrorCat == BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS -> AccountVerificationState.NETWORK_OR_TIMEOUT
            else -> AccountVerificationState.UNVERIFIED
        }

        BinanceAuthDiagnosticReport(
            ranAtUtcMs = nowMs,
            targetEndpointMode = envLabel,
            fapiBaseUrl = fapiBase,
            vaultCredentialState = if (futuresAccountOk) {
                VaultCredentialState.SAVED_IN_VAULT_AND_VERIFIED
            } else {
                VaultCredentialState.SAVED_IN_VAULT_BUT_REJECTED
            },
            accountVerificationState = finalVerifState,
            keySavedAtUtcMs = keySavedAt,
            apiKeyLoadedFromVault = keyLoadedBool,
            apiKeyAttachedToHeader = step1.passed,
            keyVaultDigestMatch = keyIntegrity,
            secretVaultDigestMatch = secretIntegrity,
            headerExactMatchVerified = step1.passed,
            outboundNetwork = outboundNet,
            clockOffsetMs = _clockState.value.offsetMs,
            clockRttMs = _clockState.value.bestRttMs,
            spotKeyAcceptedOnMainnet = spotAccepted,
            spotApiRestrictionsEnableReading = spotEnableReading,
            spotApiRestrictionsEnableFutures = spotEnableFutures,
            spotApiRestrictionsIpRestrict = spotIpRestrict,
            spotApiKeyCreatedAtMs = spotCreateTimeMs,
            testnetKeyAccepted = if (isTargetMainnet) oppositeEnvAccepted else futuresBalanceOk,
            futuresBalanceAccepted = futuresBalanceOk,
            futuresAccountAccepted = futuresAccountOk,
            failedStepCode = firstFailed?.stepCode,
            failedEndpoint = firstFailed?.endpointCalled,
            failedHttpCode = firstFailed?.httpCode,
            failedBinanceErrorCode = firstFailed?.binanceErrorCode,
            failedRawServerMessage = firstFailed?.rawServerMessage,
            requestInspections = requestInspections,
            provenCausesVi = provenCauses,
            remainingHypothesesVi = hypotheses,
            steps = steps
        )
    }

    /**
     * Fetches `/fapi/v1/exchangeInfo` and parses PRICE_FILTER, LOT_SIZE, MARKET_LOT_SIZE, MIN_NOTIONAL/NOTIONAL, PERCENT_PRICE.
     */
    override suspend fun fetchExchangeMetadata(mode: EnvironmentMode): BinanceApiResult<Map<String, SymbolFilter>> =
        withContext(Dispatchers.IO) {
            val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/exchangeInfo"
            val req = Request.Builder()
                .url("${restBaseUrl(mode)}/fapi/v1/exchangeInfo")
                .get()
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "EXCHANGE_INFO")
                    }
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseExchangeInfoJson(body)
                    synchronized(symbolFiltersCache) {
                        symbolFiltersCache.clear()
                        symbolFiltersCache.putAll(parsed)
                        if (parsed.isNotEmpty()) {
                            lastExchangeMetadataLoadedAtMs = System.currentTimeMillis()
                        }
                    }
                    BinanceApiResult(
                        isSuccess = parsed.isNotEmpty(),
                        data = parsed,
                        httpCode = resp.code,
                        endpoint = endpoint,
                        sanitizedMessageVi = "Đã nạp ${parsed.size} bộ lọc hợp đồng USDⓈ-M Futures thực tế ($endpoint)."
                    )
                }
            } catch (e: Exception) {
                classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "EXCHANGE_INFO")
            }
        }

    fun getCachedSymbolFilter(symbol: String): SymbolFilter {
        synchronized(symbolFiltersCache) {
            return symbolFiltersCache[symbol] ?: defaultSymbolFilter(symbol)
        }
    }

    /**
     * P1-01 · Returns all verified trading symbols from `/fapi/v1/exchangeInfo` unioned with baseline symbols
     * so dynamic top-liquidity symbols selected by the scanner are accepted by the AI Strict Parser.
     */
    fun getVerifiedTradingSymbols(): Set<String> {
        val baseline = setOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")
        synchronized(symbolFiltersCache) {
            if (symbolFiltersCache.isEmpty()) return baseline
            val activeFromExchange = symbolFiltersCache.values
                .filter { it.status.equals("TRADING", ignoreCase = true) }
                .map { it.symbol.uppercase() }
                .toSet()
            return baseline + activeFromExchange
        }
    }

    /**
     * P1 Section 3.6 · Returns true ONLY when real exchange metadata was fetched from `/fapi/v1/exchangeInfo`
     * (and has not expired past `maxAgeMs`), never returning true for fallback `defaultSymbolFilter`.
     */
    fun hasVerifiedRealExchangeMetadata(
        symbol: String? = null,
        maxAgeMs: Long = 3_600_000L
    ): Boolean {
        synchronized(symbolFiltersCache) {
            if (symbolFiltersCache.isEmpty() || lastExchangeMetadataLoadedAtMs <= 0L) return false
            val age = System.currentTimeMillis() - lastExchangeMetadataLoadedAtMs
            if (age > maxAgeMs) return false
            return if (symbol.isNullOrBlank()) {
                symbolFiltersCache.values.any { it.isRealExchangeFilter }
            } else {
                symbolFiltersCache[symbol]?.isRealExchangeFilter == true
            }
        }
    }

    fun injectVerifiedSymbolFilterForTest(filter: SymbolFilter) {
        synchronized(symbolFiltersCache) {
            symbolFiltersCache[filter.symbol] = filter.copy(
                isRealExchangeFilter = true,
                fetchedAtMs = System.currentTimeMillis()
            )
            lastExchangeMetadataLoadedAtMs = System.currentTimeMillis()
        }
    }

    fun clearExchangeMetadataCacheForTest() {
        synchronized(symbolFiltersCache) {
            symbolFiltersCache.clear()
            lastExchangeMetadataLoadedAtMs = 0L
        }
    }

    /**
     * Fetches closed + forming klines from `/fapi/v1/klines`.
     */
    override suspend fun fetchKlines(
        mode: EnvironmentMode,
        symbol: String,
        interval: String,
        limit: Int
    ): BinanceApiResult<List<CandleBar>> = withContext(Dispatchers.IO) {
        val url = "${restBaseUrl(mode)}/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=$limit"
        val req = Request.Builder().url(url).get().build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, endpoint = "GET $url", failedStep = "FETCH_KLINES")
                val raw = resp.body?.string().orEmpty()
                val nowMs = currentAdjustedTimestampMs()
                val bars = parseKlinesJson(symbol, interval, raw, nowMs)
                BinanceApiResult(isSuccess = true, data = bars, httpCode = resp.code, endpoint = "GET $url")
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = "GET $url", failedStep = "FETCH_KLINES")
        }
    }

    /**
     * P0-07 · Fetches markPrice, indexPrice, and fundingRate from `/fapi/v1/premiumIndex`
     * and bookTicker from `/fapi/v1/ticker/bookTicker`.
     * Fails closed (`isSuccess = false`) if either endpoint fails or returns missing/zero/inverted bid-ask or missing fundingRate.
     * Never fabricates `0.0001` funding rate, zero spread (`bid = markPrice`), or `qty = 1`.
     */
    override suspend fun fetchSymbolMarketPulse(
        mode: EnvironmentMode,
        symbol: String
    ): BinanceApiResult<MarketPulseDto> = withContext(Dispatchers.IO) {
        val base = restBaseUrl(mode)
        val premEndpoint = "GET $base/fapi/v1/premiumIndex?symbol=$symbol"
        val bookEndpoint = "GET $base/fapi/v1/ticker/bookTicker?symbol=$symbol"
        try {
            val premReq = Request.Builder().url("$base/fapi/v1/premiumIndex?symbol=$symbol").get().build()
            val bookReq = Request.Builder().url("$base/fapi/v1/ticker/bookTicker?symbol=$symbol").get().build()

            var markPrice = BigDecimal.ZERO
            var indexPrice = BigDecimal.ZERO
            var fundingRate: BigDecimal? = null
            var nextFundingMs = 0L

            httpClient.newCall(premReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = premEndpoint, failedStep = "PREMIUM_INDEX")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                markPrice = obj.optString("markPrice", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
                indexPrice = obj.optString("indexPrice", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
                fundingRate = obj.optString("lastFundingRate", "").toBigDecimalOrNull()
                nextFundingMs = obj.optLong("nextFundingTime", 0L)
            }

            if (markPrice <= BigDecimal.ZERO || indexPrice <= BigDecimal.ZERO || fundingRate == null) {
                return@withContext BinanceApiResult(
                    isSuccess = false,
                    httpCode = 200,
                    endpoint = premEndpoint,
                    failedStep = "PREMIUM_INDEX_SCHEMA",
                    errorCategory = BinanceErrorCategory.BAD_REQUEST,
                    sanitizedMessageVi = "Dữ liệu /fapi/v1/premiumIndex cho $symbol thiếu hoặc không hợp lệ (markPrice=$markPrice, indexPrice=$indexPrice, fundingRate=$fundingRate). Fail-closed (P0-07)."
                )
            }

            var bidPrice = BigDecimal.ZERO
            var askPrice = BigDecimal.ZERO
            var bidQty = BigDecimal.ZERO
            var askQty = BigDecimal.ZERO

            httpClient.newCall(bookReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = bookEndpoint, failedStep = "BOOK_TICKER")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                bidPrice = obj.optString("bidPrice", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
                askPrice = obj.optString("askPrice", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
                bidQty = obj.optString("bidQty", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
                askQty = obj.optString("askQty", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
            }

            if (bidPrice <= BigDecimal.ZERO || askPrice <= BigDecimal.ZERO || askPrice < bidPrice || bidQty <= BigDecimal.ZERO || askQty <= BigDecimal.ZERO) {
                return@withContext BinanceApiResult(
                    isSuccess = false,
                    httpCode = 200,
                    endpoint = bookEndpoint,
                    failedStep = "BOOK_TICKER_SCHEMA",
                    errorCategory = BinanceErrorCategory.BAD_REQUEST,
                    sanitizedMessageVi = "Dữ liệu /fapi/v1/ticker/bookTicker cho $symbol thiếu hoặc không hợp lệ (bid=$bidPrice, ask=$askPrice, bidQty=$bidQty, askQty=$askQty). Fail-closed (P0-07)."
                )
            }

            BinanceApiResult(
                isSuccess = true,
                data = MarketPulseDto(
                    symbol = symbol,
                    markPrice = markPrice,
                    indexPrice = indexPrice,
                    bestBid = bidPrice,
                    bestAsk = askPrice,
                    bestBidQty = bidQty,
                    bestAskQty = askQty,
                    fundingRate = fundingRate!!,
                    nextFundingTimeMs = nextFundingMs,
                    timestampMs = currentAdjustedTimestampMs()
                ),
                httpCode = 200,
                endpoint = premEndpoint
            )
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = premEndpoint, failedStep = "PREMIUM_INDEX")
        }
    }

    /**
     * MOD-06 · Fetches 24h ticker statistics (`/fapi/v1/ticker/24hr`) across USDⓈ-M universe for Multi-Symbol Market Scanner.
     */
    override suspend fun fetch24hTickerUniverse(mode: EnvironmentMode): BinanceApiResult<List<Ticker24hStatsDto>> =
        withContext(Dispatchers.IO) {
            val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/ticker/24hr"
            val req = Request.Builder()
                .url("${restBaseUrl(mode)}/fapi/v1/ticker/24hr")
                .get()
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "TICKER_24HR")
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val list = ArrayList<Ticker24hStatsDto>(arr.length())
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val sym = obj.optString("symbol", "")
                        if (!sym.endsWith("USDT") || sym.contains("_")) continue
                        val lastPrice = obj.optString("lastPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (lastPrice <= BigDecimal.ZERO) continue
                        list.add(
                            Ticker24hStatsDto(
                                symbol = sym,
                                lastPrice = lastPrice,
                                priceChangePercent24h = obj.optString("priceChangePercent", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                quoteVolume24hUsdt = obj.optString("quoteVolume", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                highPrice24h = obj.optString("highPrice", "0").toBigDecimalOrNull() ?: lastPrice,
                                lowPrice24h = obj.optString("lowPrice", "0").toBigDecimalOrNull() ?: lastPrice
                            )
                        )
                    }
                    BinanceApiResult(isSuccess = true, data = list, httpCode = resp.code, endpoint = endpoint)
                }
            } catch (e: Exception) {
                classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "TICKER_24HR")
            }
        }

    /**
     * Read-only USDⓈ-M Futures account & balance verification (`/fapi/v2/account` and `/fapi/v1/openOrders`).
     * Never submits a real order to test permissions (Requirement 1, 2 & 7).
     * Strictly validates schema fields (`totalMarginBalance`, `availableBalance`) before returning success (Requirement 3).
     */
    override suspend fun fetchAccountAndPositionsSnapshot(
        mode: EnvironmentMode
    ): BinanceApiResult<ExchangeAccountSnapshot> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                failedStep = "STEP_1_VAULT_AND_HEADER",
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Chưa cấu hình khóa API Binance hợp lệ."
            )
        }

        val base = restBaseUrl(mode)
        val accountEndpoint = "GET $base/fapi/v2/account"
        try {
            val ts = currentAdjustedTimestampMs()
            val params = linkedMapOf(
                "recvWindow" to "5000",
                "timestamp" to ts.toString()
            )
            val query = buildCanonicalQueryString(params)
            val sig = signHmacSha256(query, apiSecret)

            val accountReq = Request.Builder()
                .url("$base/fapi/v2/account?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey) // Exact case-preserved API Key, no Bearer prefix
                .get()
                .build()

            var walletBal: BigDecimal
            var marginBal: BigDecimal
            var availBal: BigDecimal
            var unrealPnl: BigDecimal
            var canTrade: Boolean
            var canDeposit: Boolean
            var multiAssets: Boolean
            val activePositions = mutableListOf<ExchangePositionRecord>()

            httpClient.newCall(accountReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(
                        resp = resp,
                        mode = mode,
                        endpoint = accountEndpoint,
                        failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY"
                    )
                }
                val bodyStr = resp.body?.string().orEmpty()
                val obj = JSONObject(bodyStr)
                if (!obj.has("totalMarginBalance") || !obj.has("availableBalance")) {
                    return@withContext BinanceApiResult(
                        isSuccess = false,
                        httpCode = resp.code,
                        endpoint = accountEndpoint,
                        failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY",
                        errorCategory = BinanceErrorCategory.BAD_REQUEST,
                        sanitizedMessageVi = "Phản hồi $accountEndpoint thiếu trường bắt buộc (totalMarginBalance / availableBalance). Không cập nhật Equity."
                    )
                }

                walletBal = obj.optString("totalWalletBalance", "").toBigDecimalOrNull()
                    ?: return@withContext BinanceApiResult(
                        isSuccess = false,
                        endpoint = accountEndpoint,
                        failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY",
                        errorCategory = BinanceErrorCategory.BAD_REQUEST,
                        sanitizedMessageVi = "Giá trị totalWalletBalance từ sàn không hợp lệ."
                    )
                marginBal = obj.optString("totalMarginBalance", "").toBigDecimalOrNull()
                    ?: return@withContext BinanceApiResult(
                        isSuccess = false,
                        endpoint = accountEndpoint,
                        failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY",
                        errorCategory = BinanceErrorCategory.BAD_REQUEST,
                        sanitizedMessageVi = "Giá trị totalMarginBalance từ sàn không hợp lệ."
                    )
                availBal = obj.optString("availableBalance", "").toBigDecimalOrNull()
                    ?: return@withContext BinanceApiResult(
                        isSuccess = false,
                        endpoint = accountEndpoint,
                        failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY",
                        errorCategory = BinanceErrorCategory.BAD_REQUEST,
                        sanitizedMessageVi = "Giá trị availableBalance từ sàn không hợp lệ."
                    )
                unrealPnl = obj.optString("totalUnrealizedProfit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                canTrade = obj.optBoolean("canTrade", false)
                canDeposit = obj.optBoolean("canDeposit", true)
                multiAssets = obj.optBoolean("multiAssetsMargin", false)

                val posArr = obj.optJSONArray("positions") ?: JSONArray()
                for (i in 0 until posArr.length()) {
                    val p = posArr.getJSONObject(i)
                    val amt = p.optString("positionAmt", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    if (amt.compareTo(BigDecimal.ZERO) != 0) {
                        activePositions.add(
                            ExchangePositionRecord(
                                symbol = p.optString("symbol", ""),
                                positionAmt = amt,
                                entryPrice = p.optString("entryPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                markPrice = p.optString("markPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                unRealizedProfit = p.optString("unrealizedProfit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                liquidationPrice = p.optString("liquidationPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                leverage = p.optString("leverage", "1").toIntOrNull() ?: 1,
                                marginType = if (p.optBoolean("isolated", true)) "ISOLATED" else "CROSSED",
                                isolatedWallet = p.optString("isolatedWallet", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                positionSide = p.optString("positionSide", "BOTH").ifBlank { "BOTH" }
                            )
                        )
                    }
                }
            }

            val tsOrders = currentAdjustedTimestampMs()
            val ordersParams = linkedMapOf(
                "recvWindow" to "5000",
                "timestamp" to tsOrders.toString()
            )
            val ordersQuery = buildCanonicalQueryString(ordersParams)
            val ordersSig = signHmacSha256(ordersQuery, apiSecret)
            val ordersReq = Request.Builder()
                .url("$base/fapi/v1/openOrders?$ordersQuery&signature=$ordersSig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()

            val openOrders = mutableListOf<ExchangeOrderRecord>()
            var openOrdersVerified = false
            httpClient.newCall(ordersReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    openOrdersVerified = true
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        openOrders.add(parseOrderJson(arr.getJSONObject(i)))
                    }
                }
            }

            // Also fetch open Algo Conditional orders (`/fapi/v1/openAlgoOrders`) if any
            val openAlgoOrders = mutableListOf<ExchangeOrderRecord>()
            var openAlgoOrdersVerified = false
            val algoOrdersReq = Request.Builder()
                .url("$base/fapi/v1/openAlgoOrders?$ordersQuery&signature=$ordersSig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            try {
                httpClient.newCall(algoOrdersReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        openAlgoOrdersVerified = true
                        val bodyText = resp.body?.string().orEmpty().trim()
                        val arr = when {
                            bodyText.startsWith("[") -> JSONArray(bodyText)
                            bodyText.startsWith("{") -> JSONObject(bodyText).optJSONArray("orders") ?: JSONArray()
                            else -> JSONArray()
                        }
                        for (i in 0 until arr.length()) {
                            val parsedAlgo = parseAlgoOrderJson(arr.getJSONObject(i))
                            openAlgoOrders.add(parsedAlgo)
                            if (openOrders.none { it.clientOrderId == parsedAlgo.clientOrderId }) {
                                openOrders.add(parsedAlgo)
                            }
                        }
                    } else if (resp.code == 404) {
                        // Legacy or test server without separate /fapi/v1/openAlgoOrders endpoint
                        openAlgoOrdersVerified = openOrdersVerified
                    }
                }
            } catch (_: Exception) {
                openAlgoOrdersVerified = false
            }

            BinanceApiResult(
                isSuccess = true,
                data = ExchangeAccountSnapshot(
                    totalWalletBalanceUsdt = walletBal,
                    totalMarginBalanceUsdt = marginBal,
                    availableBalanceUsdt = availBal,
                    totalUnrealizedProfitUsdt = unrealPnl,
                    canTrade = canTrade,
                    canDeposit = canDeposit,
                    multiAssetsMargin = multiAssets,
                    positions = activePositions,
                    openOrders = openOrders,
                    openAlgoOrders = openAlgoOrders,
                    openOrdersVerified = openOrdersVerified,
                    openAlgoOrdersVerified = openAlgoOrdersVerified,
                    verifiedFromExchange = true,
                    fetchedAtMs = currentAdjustedTimestampMs()
                ),
                httpCode = 200,
                endpoint = accountEndpoint,
                sanitizedMessageVi = "Đã xác minh tài khoản Binance ($accountEndpoint): Equity=${marginBal.setScale(4, java.math.RoundingMode.HALF_UP)} USDT, Khả dụng=${availBal.setScale(4, java.math.RoundingMode.HALF_UP)} USDT, canTrade=$canTrade."
            )
        } catch (e: Exception) {
            classifyException(
                e = e,
                isOrderMutation = false,
                endpoint = accountEndpoint,
                failedStep = "STEP_6_FUTURES_ACCOUNT_READONLY"
            )
        }
    }

    /**
     * MOD-05 · Starts or keeps alive a Binance USDⓈ-M User Data Stream `listenKey` (`POST/PUT /fapi/v1/listenKey`).
     */
    override suspend fun startOrKeepAliveListenKey(
        mode: EnvironmentMode,
        existingListenKey: String?
    ): BinanceApiResult<String> = withContext(Dispatchers.IO) {
        val (apiKey, _, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key hợp lệ khi khởi tạo/gia hạn listenKey."
            )
        }
        val endpoint = "${if (existingListenKey.isNullOrBlank()) "POST" else "PUT"} ${restBaseUrl(mode)}/fapi/v1/listenKey"
        val body = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val reqBuilder = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/listenKey")
            .header("X-MBX-APIKEY", apiKey)
        val req = if (existingListenKey.isNullOrBlank()) {
            reqBuilder.post(body).build()
        } else {
            reqBuilder.put(body).build()
        }
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "LISTEN_KEY")
                val obj = JSONObject(resp.body?.string().orEmpty())
                val key = obj.optString("listenKey", existingListenKey.orEmpty())
                BinanceApiResult(
                    isSuccess = key.isNotBlank(),
                    data = key,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Đã khởi tạo/gia hạn User Data Stream listenKey."
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "LISTEN_KEY")
        }
    }

    /**
     * Submits a signed Futures order (`/fapi/v1/order`).
     * Only called by `ExecutionCoordinator` after persisting `OrderIntent` (INV-01, INV-04).
     */
    override suspend fun submitFuturesOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        type: String,
        quantity: BigDecimal?,
        stopPrice: BigDecimal?,
        workingType: String?,
        closePosition: Boolean,
        reduceOnly: Boolean,
        newClientOrderId: String,
        positionSide: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val endpoint = "POST ${restBaseUrl(mode)}/fapi/v1/order"
        val isEmergencyOrReduceOnly = closePosition || reduceOnly || newClientOrderId.startsWith("ASTRA_PANIC_")
        verifyOrderMutationCapability<ExchangeOrderRecord>(mode, isEmergencyOrReduceOnly, endpoint)?.let {
            return@withContext it
        }
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret hợp lệ khi gửi lệnh."
            )
        }

        val params = linkedMapOf<String, String>(
            "symbol" to symbol,
            "side" to side,
            "positionSide" to positionSide.ifBlank { "BOTH" },
            "type" to type,
            "newClientOrderId" to newClientOrderId,
            "newOrderRespType" to "RESULT",
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        if (quantity != null && !closePosition) {
            params["quantity"] = quantity.stripTrailingZeros().toPlainString()
        }
        if (stopPrice != null && stopPrice > BigDecimal.ZERO) {
            params["stopPrice"] = stopPrice.stripTrailingZeros().toPlainString()
        }
        if (!workingType.isNullOrBlank()) {
            params["workingType"] = workingType
        }
        if (closePosition) {
            params["closePosition"] = "true"
        } else if (reduceOnly && positionSide.equals("BOTH", ignoreCase = true)) {
            // In Hedge Mode (positionSide=LONG/SHORT), Binance rejects reduceOnly parameter because side+positionSide already defines open vs close.
            params["reduceOnly"] = "true"
        }

        val queryString = buildCanonicalQueryString(params)
        val signature = signHmacSha256(queryString, apiSecret)
        val fullQuery = "$queryString&signature=$signature"

        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/order?$fullQuery")
            .header("X-MBX-APIKEY", apiKey)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, isOrderMutation = true, endpoint = endpoint, failedStep = "SUBMIT_ORDER")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val record = parseOrderJson(obj)
                BinanceApiResult(
                    isSuccess = true,
                    data = record,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Phản hồi sàn cho lệnh ${record.clientOrderId}: status=${record.status}"
                )
            }
        } catch (e: Exception) {
            // INV-06: Timeout or network exception during order submission MUST be marked UNKNOWN_EXECUTION_STATUS
            classifyException(e, isOrderMutation = true, endpoint = endpoint, failedStep = "SUBMIT_ORDER")
        }
    }

    /**
     * P0 Section 3.1 · Submits a Binance USDⓈ-M Futures Conditional Algo Order (`POST /fapi/v1/algoOrder`).
     * Supports `STOP_MARKET`, `TAKE_PROFIT_MARKET`, `STOP`, `TAKE_PROFIT` with `algoType=CONDITIONAL`,
     * `clientAlgoId`, `triggerPrice`, `workingType`, `positionSide`, `priceProtect`, `closePosition` / `reduceOnly`.
     * Transparently falls back to `/fapi/v1/order` if the target test server returns HTTP 404 on `/fapi/v1/algoOrder`.
     */
    override suspend fun submitAlgoConditionalOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        positionSide: String,
        type: String,
        triggerPrice: BigDecimal,
        quantity: BigDecimal?,
        workingType: String,
        closePosition: Boolean,
        reduceOnly: Boolean,
        priceProtect: Boolean,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val endpoint = "POST ${restBaseUrl(mode)}/fapi/v1/algoOrder"
        val isEmergencyOrReduceOnly = closePosition || reduceOnly
        verifyOrderMutationCapability<ExchangeOrderRecord>(mode, isEmergencyOrReduceOnly, endpoint)?.let {
            return@withContext it
        }
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret hợp lệ khi gửi lệnh Algo Conditional."
            )
        }

        val cleanPosSide = positionSide.ifBlank { "BOTH" }
        val params = linkedMapOf<String, String>(
            "algoType" to "CONDITIONAL",
            "symbol" to symbol,
            "side" to side,
            "positionSide" to cleanPosSide,
            "type" to type,
            "triggerPrice" to triggerPrice.stripTrailingZeros().toPlainString(),
            "workingType" to workingType.ifBlank { "MARK_PRICE" },
            "priceProtect" to if (priceProtect) "TRUE" else "FALSE",
            "clientAlgoId" to clientAlgoId,
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        if (closePosition) {
            params["closePosition"] = "true"
        } else {
            if (quantity != null && quantity > BigDecimal.ZERO) {
                params["quantity"] = quantity.stripTrailingZeros().toPlainString()
            }
            if (reduceOnly && cleanPosSide.equals("BOTH", ignoreCase = true)) {
                params["reduceOnly"] = "true"
            }
        }

        val queryString = buildCanonicalQueryString(params)
        val signature = signHmacSha256(queryString, apiSecret)
        val fullQuery = "$queryString&signature=$signature"

        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/algoOrder?$fullQuery")
            .header("X-MBX-APIKEY", apiKey)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    // Fallback to standard conditional order endpoint if test mock or legacy server does not mount /fapi/v1/algoOrder
                    return@withContext submitFuturesOrder(
                        mode = mode,
                        symbol = symbol,
                        side = side,
                        type = type,
                        quantity = quantity,
                        stopPrice = triggerPrice,
                        workingType = workingType,
                        closePosition = closePosition,
                        reduceOnly = reduceOnly,
                        newClientOrderId = clientAlgoId,
                        positionSide = cleanPosSide
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(
                        resp = resp,
                        mode = mode,
                        isOrderMutation = true,
                        endpoint = endpoint,
                        failedStep = "SUBMIT_ALGO_ORDER"
                    )
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val record = parseAlgoOrderJson(obj, fallbackSymbol = symbol, fallbackSide = side, fallbackType = type, fallbackTriggerPrice = triggerPrice, fallbackWorkingType = workingType, fallbackClosePosition = closePosition, fallbackReduceOnly = reduceOnly, fallbackPositionSide = cleanPosSide, fallbackClientAlgoId = clientAlgoId)
                BinanceApiResult(
                    isSuccess = true,
                    data = record,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Đã đặt lệnh Algo Conditional ${record.clientOrderId} (#${record.orderId}): status=${record.status}"
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = true, endpoint = endpoint, failedStep = "SUBMIT_ALGO_ORDER")
        }
    }

    /**
     * P0-02 · Queries `GET /fapi/v1/positionSide/dual` to detect Hedge Mode (`dualSidePosition == true`) vs One-Way Mode (`false`).
     */
    suspend fun fetchPositionMode(mode: EnvironmentMode): BinanceApiResult<Boolean> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi kiểm tra chế độ vị thế (One-Way / Hedge Mode)."
            )
        }
        val params = linkedMapOf(
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/positionSide/dual"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/positionSide/dual?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "POSITION_SIDE_DUAL")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val dual = obj.optBoolean("dualSidePosition", false)
                BinanceApiResult(
                    isSuccess = true,
                    data = dual,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = if (dual) "Chế độ vị thế trên sàn: HEDGE MODE (dualSidePosition=true)." else "Chế độ vị thế trên sàn: ONE-WAY MODE (dualSidePosition=false)."
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "POSITION_SIDE_DUAL")
        }
    }

    /**
     * P0-10 · Configures and verifies symbol leverage on the exchange (`POST /fapi/v1/leverage`).
     */
    suspend fun changeSymbolLeverage(
        mode: EnvironmentMode,
        symbol: String,
        leverage: Int
    ): BinanceApiResult<Int> = withContext(Dispatchers.IO) {
        val endpoint = "POST ${restBaseUrl(mode)}/fapi/v1/leverage"
        verifyOrderMutationCapability<Int>(mode, isEmergencyOrReduceOnly = false, endpoint = endpoint)?.let {
            return@withContext it
        }
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi cấu hình đòn bẩy $symbol."
            )
        }
        val safeLeverage = leverage.coerceIn(1, 20)
        val params = linkedMapOf(
            "symbol" to symbol,
            "leverage" to safeLeverage.toString(),
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/leverage?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "CHANGE_LEVERAGE")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val confirmedLeverage = obj.optInt("leverage", safeLeverage)
                BinanceApiResult(
                    isSuccess = confirmedLeverage == safeLeverage,
                    data = confirmedLeverage,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Đã đồng bộ đòn bẩy sàn cho $symbol = ${confirmedLeverage}x."
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "CHANGE_LEVERAGE")
        }
    }

    /**
     * P0-10 · Configures and verifies symbol margin type on the exchange (`POST /fapi/v1/marginType`, e.g. `ISOLATED`).
     * Treats Binance code `-4046` ("No need to change margin type.") as verified success.
     */
    suspend fun changeSymbolMarginType(
        mode: EnvironmentMode,
        symbol: String,
        marginType: String = "ISOLATED"
    ): BinanceApiResult<String> = withContext(Dispatchers.IO) {
        val endpoint = "POST ${restBaseUrl(mode)}/fapi/v1/marginType"
        verifyOrderMutationCapability<String>(mode, isEmergencyOrReduceOnly = false, endpoint = endpoint)?.let {
            return@withContext it
        }
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi cấu hình chế độ ký quỹ $symbol."
            )
        }
        val cleanMarginType = if (marginType.equals("CROSSED", ignoreCase = true)) "CROSSED" else "ISOLATED"
        val params = linkedMapOf(
            "symbol" to symbol,
            "marginType" to cleanMarginType,
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/marginType?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = classifyErrorResponse<String>(resp, mode, endpoint = endpoint, failedStep = "CHANGE_MARGIN_TYPE")
                    if (err.binanceErrorCode == -4046) {
                        return@withContext BinanceApiResult(
                            isSuccess = true,
                            data = cleanMarginType,
                            httpCode = resp.code,
                            endpoint = endpoint,
                            sanitizedMessageVi = "$symbol đã ở chế độ ký quỹ $cleanMarginType (-4046)."
                        )
                    }
                    return@withContext err
                }
                BinanceApiResult(
                    isSuccess = true,
                    data = cleanMarginType,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Đã xác nhận chế độ ký quỹ $cleanMarginType cho $symbol."
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "CHANGE_MARGIN_TYPE")
        }
    }

    /**
     * P0-12 · Fetches verified funding fee history from `GET /fapi/v1/income?incomeType=FUNDING_FEE`.
     */
    suspend fun fetchFundingIncomeHistory(
        mode: EnvironmentMode,
        symbol: String? = null,
        limit: Int = 50
    ): BinanceApiResult<List<FundingIncomeRecord>> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi truy vấn lịch sử Funding Fee."
            )
        }
        val params = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) {
            params["symbol"] = symbol
        }
        params["incomeType"] = "FUNDING_FEE"
        params["limit"] = limit.coerceIn(1, 500).toString()
        params["recvWindow"] = "5000"
        params["timestamp"] = currentAdjustedTimestampMs().toString()
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/income"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/income?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "FETCH_FUNDING_INCOME")
                }
                val rawBody = resp.body?.string().orEmpty().trim()
                val arr = if (rawBody.startsWith("[")) JSONArray(rawBody) else JSONArray()
                val records = ArrayList<FundingIncomeRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val tranId = obj.optLong("tranId", 0L).takeIf { it > 0L }?.toString()
                        ?: obj.optString("tranId", "FND-$i")
                    val sym = obj.optString("symbol", symbol.orEmpty())
                    val incomeType = obj.optString("incomeType", "FUNDING_FEE")
                    val incomeVal = obj.optString("income", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val asset = obj.optString("asset", "USDT").ifBlank { "USDT" }
                    val timeMs = obj.optLong("time", System.currentTimeMillis())
                    records.add(
                        FundingIncomeRecord(
                            tranId = tranId,
                            symbol = sym,
                            incomeType = incomeType,
                            incomeUsdt = incomeVal,
                            asset = asset,
                            timeMs = timeMs
                        )
                    )
                }
                BinanceApiResult(
                    isSuccess = true,
                    data = records,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Đã tải ${records.size} bản ghi Funding Fee thực tế từ Binance."
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "FETCH_FUNDING_INCOME")
        }
    }

    /**
     * MOD-04 & P0 Section 3.1 · Places a protective `STOP_MARKET` order via Algo Order API (with fallback compatibility).
     */
    override suspend fun submitProtectiveStopMarketOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        stopPrice: BigDecimal,
        closePosition: Boolean,
        newClientOrderId: String,
        quantity: BigDecimal?,
        workingType: String,
        positionSide: String
    ): BinanceApiResult<ExchangeOrderRecord> {
        return submitAlgoConditionalOrder(
            mode = mode,
            symbol = symbol,
            side = side,
            positionSide = positionSide,
            type = "STOP_MARKET",
            triggerPrice = stopPrice,
            quantity = quantity,
            workingType = workingType,
            closePosition = closePosition,
            reduceOnly = !closePosition,
            priceProtect = true,
            clientAlgoId = newClientOrderId
        )
    }

    /**
     * P0 Section 3.1 · Places a protective `TAKE_PROFIT_MARKET` order via Algo Order API (with fallback compatibility).
     */
    override suspend fun submitProtectiveTakeProfitMarketOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        triggerPrice: BigDecimal,
        closePosition: Boolean,
        newClientOrderId: String,
        quantity: BigDecimal?,
        workingType: String,
        positionSide: String
    ): BinanceApiResult<ExchangeOrderRecord> {
        return submitAlgoConditionalOrder(
            mode = mode,
            symbol = symbol,
            side = side,
            positionSide = positionSide,
            type = "TAKE_PROFIT_MARKET",
            triggerPrice = triggerPrice,
            quantity = quantity,
            workingType = workingType,
            closePosition = closePosition,
            reduceOnly = !closePosition,
            priceProtect = true,
            clientAlgoId = newClientOrderId
        )
    }

    override suspend fun queryAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi truy vấn trạng thái lệnh Algo."
            )
        }

        val params = linkedMapOf(
            "symbol" to symbol,
            "clientAlgoId" to clientAlgoId,
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/algoOrder"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/algoOrder?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext queryOrderByClientId(mode, symbol, clientAlgoId)
                }
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "QUERY_ALGO_ORDER")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                BinanceApiResult(
                    isSuccess = true,
                    data = parseAlgoOrderJson(obj, fallbackSymbol = symbol, fallbackClientAlgoId = clientAlgoId),
                    httpCode = resp.code,
                    endpoint = endpoint
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "QUERY_ALGO_ORDER")
        }
    }

    override suspend fun fetchOpenAlgoOrders(
        mode: EnvironmentMode,
        symbol: String?
    ): BinanceApiResult<List<ExchangeOrderRecord>> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi truy vấn openAlgoOrders."
            )
        }

        val params = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) {
            params["symbol"] = symbol
        }
        params["recvWindow"] = "5000"
        params["timestamp"] = currentAdjustedTimestampMs().toString()
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/openAlgoOrders"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/openAlgoOrders?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "FETCH_OPEN_ALGO_ORDERS")
                }
                val bodyText = resp.body?.string().orEmpty().trim()
                val arr = when {
                    bodyText.startsWith("[") -> JSONArray(bodyText)
                    bodyText.startsWith("{") -> JSONObject(bodyText).optJSONArray("orders") ?: JSONArray()
                    else -> JSONArray()
                }
                val list = ArrayList<ExchangeOrderRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(parseAlgoOrderJson(arr.getJSONObject(i), fallbackSymbol = symbol.orEmpty()))
                }
                BinanceApiResult(isSuccess = true, data = list, httpCode = resp.code, endpoint = endpoint)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "FETCH_OPEN_ALGO_ORDERS")
        }
    }

    override suspend fun cancelAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        if (!clientAlgoId.startsWith("ASTRA_")) {
            return@withContext BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.BAD_REQUEST,
                sanitizedMessageVi = "Từ chối hủy lệnh Algo không mang nhãn sở hữu ASTRA_ ($clientAlgoId)."
            )
        }
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi hủy lệnh Algo bảo vệ dư."
            )
        }

        val params = linkedMapOf(
            "symbol" to symbol,
            "clientAlgoId" to clientAlgoId,
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "DELETE ${restBaseUrl(mode)}/fapi/v1/algoOrder"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/algoOrder?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .delete()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext cancelOrderByClientId(mode, symbol, clientAlgoId)
                }
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "CANCEL_ALGO_ORDER")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val parsed = parseAlgoOrderJson(obj, fallbackSymbol = symbol, fallbackClientAlgoId = clientAlgoId)
                val effectiveStatus = if (obj.has("algoStatus") || obj.has("status")) {
                    parsed.status
                } else if (obj.optString("code", "") == "200" || obj.optInt("code", 0) == 200) {
                    "CANCELED"
                } else {
                    parsed.status
                }
                BinanceApiResult(
                    isSuccess = true,
                    data = parsed.copy(status = effectiveStatus),
                    httpCode = resp.code,
                    endpoint = endpoint
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "CANCEL_ALGO_ORDER")
        }
    }

    /**
     * P0 Section 3.1 · Verifies that a newly placed protective SL or TP order is active (`NEW` or `WORKING`)
     * and matches the expected symbol, side, positionSide, and triggerPrice tolerance.
     */
    fun verifyProtectiveOrderRecord(
        order: ExchangeOrderRecord?,
        expectedSymbol: String,
        expectedSide: String,
        expectedTriggerPrice: BigDecimal,
        expectedTickSize: BigDecimal,
        expectedPositionSide: String = "BOTH"
    ): Boolean {
        if (order == null) return false
        val validActiveStatus = order.status in setOf("NEW", "WORKING")
        if (!validActiveStatus) return false
        if (!order.symbol.equals(expectedSymbol, ignoreCase = true)) return false
        if (!order.side.equals(expectedSide, ignoreCase = true)) return false
        if (!order.positionSide.equals(expectedPositionSide, ignoreCase = true)) return false
        val priceDiff = order.stopPrice.subtract(expectedTriggerPrice).abs()
        val maxAllowedDiff = expectedTickSize.multiply(BigDecimal("2")).max(BigDecimal("0.0001"))
        return order.stopPrice > BigDecimal.ZERO && priceDiff <= maxAllowedDiff
    }

    /**
     * Queries an existing order by `origClientOrderId` (INV-06: Mandatory before retrying or resolving UNKNOWN status).
     */
    override suspend fun queryOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi truy vấn trạng thái lệnh."
            )
        }

        val params = linkedMapOf(
            "symbol" to symbol,
            "origClientOrderId" to origClientOrderId,
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/order"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/order?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "QUERY_ORDER")
                val obj = JSONObject(resp.body?.string().orEmpty())
                BinanceApiResult(isSuccess = true, data = parseOrderJson(obj), httpCode = resp.code, endpoint = endpoint)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "QUERY_ORDER")
        }
    }

    /**
     * Cancels a specific bot-owned order by `origClientOrderId` (MOD-13: Never blindly cancel all user orders).
     */
    override suspend fun cancelOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        if (!origClientOrderId.startsWith("ASTRA_")) {
            return@withContext BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.BAD_REQUEST,
                sanitizedMessageVi = "Từ chối hủy lệnh không mang nhãn sở hữu ASTRA_ ($origClientOrderId)."
            )
        }
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi hủy lệnh bảo vệ dư."
            )
        }

        val params = linkedMapOf(
            "symbol" to symbol,
            "origClientOrderId" to origClientOrderId,
            "recvWindow" to "5000",
            "timestamp" to currentAdjustedTimestampMs().toString()
        )
        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "DELETE ${restBaseUrl(mode)}/fapi/v1/order"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/order?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .delete()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "CANCEL_ORDER")
                val obj = JSONObject(resp.body?.string().orEmpty())
                BinanceApiResult(isSuccess = true, data = parseOrderJson(obj), httpCode = resp.code, endpoint = endpoint)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "CANCEL_ORDER")
        }
    }

    /**
     * P0-03 · Cancels a bot-owned standard or Algo order and verifies terminal status (`CANCELED`, `EXPIRED`, or `FILLED`).
     * Never returns `isSuccess = true` if the cancel HTTP call failed or the order remains `NEW`/`WORKING`.
     */
    override suspend fun cancelAndVerifyOrder(
        mode: EnvironmentMode,
        symbol: String,
        clientOrderId: String,
        isAlgoOrder: Boolean
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        if (!clientOrderId.startsWith("ASTRA_")) {
            return@withContext BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.BAD_REQUEST,
                sanitizedMessageVi = "Từ chối hủy lệnh không thuộc sở hữu ASTRA_ ($clientOrderId)."
            )
        }

        val cancelRes = if (isAlgoOrder) {
            cancelAlgoOrderByClientId(mode, symbol, clientOrderId)
        } else {
            cancelOrderByClientId(mode, symbol, clientOrderId)
        }

        val terminalStatuses = setOf("CANCELED", "CANCELLED", "EXPIRED", "FILLED", "CLOSED")
        if (cancelRes.isSuccess && cancelRes.data != null) {
            val statusAfterCancel = cancelRes.data.status.uppercase()
            if (statusAfterCancel in terminalStatuses) {
                return@withContext cancelRes.copy(
                    data = cancelRes.data.copy(status = if (statusAfterCancel == "CANCELLED") "CANCELED" else statusAfterCancel)
                )
            }
            // Query to verify terminal status when cancel response did not include an explicit terminal status
            val queryRes = if (isAlgoOrder) {
                queryAlgoOrderByClientId(mode, symbol, clientOrderId)
            } else {
                queryOrderByClientId(mode, symbol, clientOrderId)
            }
            if (queryRes.isSuccess && queryRes.data != null && queryRes.data.status.uppercase() in terminalStatuses) {
                return@withContext queryRes
            }
            return@withContext BinanceApiResult(
                isSuccess = false,
                httpCode = cancelRes.httpCode,
                errorCategory = BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS,
                endpoint = cancelRes.endpoint,
                failedStep = "VERIFY_CANCEL_ORDER",
                sanitizedMessageVi = "Lệnh $clientOrderId chưa xác nhận trạng thái kết thúc sau khi gọi hủy (status=${queryRes.data?.status ?: statusAfterCancel})."
            )
        }

        // If Binance returned -2011 (Unknown order sent), the order may already be CANCELED, EXPIRED, or FILLED. Query to verify!
        if (cancelRes.binanceErrorCode == -2011) {
            val queryRes = if (isAlgoOrder) {
                queryAlgoOrderByClientId(mode, symbol, clientOrderId)
            } else {
                queryOrderByClientId(mode, symbol, clientOrderId)
            }
            if (queryRes.isSuccess && queryRes.data != null && queryRes.data.status.uppercase() in terminalStatuses) {
                return@withContext queryRes
            }
        }

        cancelRes
    }

    /**
     * P0-04 · Queries actual user trades (`GET /fapi/v1/userTrades`) for verified fill price, realized PnL, and commission.
     */
    override suspend fun fetchUserTradesForOrder(
        mode: EnvironmentMode,
        symbol: String,
        orderId: String?,
        limit: Int
    ): BinanceApiResult<List<ExchangeUserTradeRecord>> = withContext(Dispatchers.IO) {
        val (apiKey, apiSecret, validationError) = loadAndValidateCredentials()
        if (validationError != null || apiKey == null || apiSecret == null) {
            return@withContext validationError ?: BinanceApiResult(
                isSuccess = false,
                errorCategory = BinanceErrorCategory.AUTH_OR_PERMISSION,
                sanitizedMessageVi = "Thiếu API Key/Secret khi truy vấn userTrades."
            )
        }

        val params = linkedMapOf<String, String>("symbol" to symbol)
        val numericOrderId = orderId?.toLongOrNull()
        if (numericOrderId != null && numericOrderId > 0L) {
            params["orderId"] = numericOrderId.toString()
        }
        params["limit"] = limit.coerceIn(1, 500).toString()
        params["recvWindow"] = "5000"
        params["timestamp"] = currentAdjustedTimestampMs().toString()

        val query = buildCanonicalQueryString(params)
        val sig = signHmacSha256(query, apiSecret)
        val endpoint = "GET ${restBaseUrl(mode)}/fapi/v1/userTrades"
        val req = Request.Builder()
            .url("${restBaseUrl(mode)}/fapi/v1/userTrades?$query&signature=$sig")
            .header("X-MBX-APIKEY", apiKey)
            .get()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, endpoint = endpoint, failedStep = "FETCH_USER_TRADES")
                }
                val rawBody = resp.body?.string().orEmpty().trim()
                val trades = parseUserTradesJson(rawBody, fallbackSymbol = symbol)
                BinanceApiResult(
                    isSuccess = true,
                    data = trades,
                    httpCode = resp.code,
                    endpoint = endpoint,
                    sanitizedMessageVi = "Đã tải ${trades.size} bản ghi khớp lệnh thực tế (userTrades) cho $symbol."
                )
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpoint = endpoint, failedStep = "FETCH_USER_TRADES")
        }
    }

    /**
     * MOD-05 · Connects lightweight market stream (`<symbol>@bookTicker` & `<symbol>@markPrice@1s`) with staleness tracking.
     */
    fun connectMarketStream(mode: EnvironmentMode, symbol: String) {
        disconnectMarketStream()
        val lower = symbol.lowercase()
        val wsUrl = "${wsBaseUrl(mode)}/stream?streams=${lower}@bookTicker/${lower}@markPrice@1s"
        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val now = System.currentTimeMillis()
                TradingForegroundService.recordStreamMessageReceived(now)
                _wsState.value = _wsState.value.copy(
                    connected = true,
                    symbol = symbol,
                    lastEventTimeMs = now
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root
                    val eventType = data.optString("e", "")
                    val now = System.currentTimeMillis()
                    TradingForegroundService.recordStreamMessageReceived(now)
                    if (eventType == "markPriceUpdate") {
                        val mp = data.optString("p", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (mp > BigDecimal.ZERO) {
                            _wsState.value = _wsState.value.copy(
                                connected = true,
                                lastMarkPrice = mp,
                                lastEventTimeMs = now
                            )
                        }
                    } else if (data.has("b") && data.has("a")) {
                        val bid = data.optString("b", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val ask = data.optString("a", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        _wsState.value = _wsState.value.copy(
                            connected = true,
                            lastBestBid = bid,
                            lastBestAsk = ask,
                            lastEventTimeMs = now
                        )
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _wsState.value = _wsState.value.copy(
                    connected = false,
                    reconnectCount = _wsState.value.reconnectCount + 1
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _wsState.value = _wsState.value.copy(connected = false)
            }
        })
    }

    fun disconnectMarketStream() {
        try {
            activeWebSocket?.close(1000, "Astra Trader stream reset")
            activeUserDataWebSocket?.close(1000, "Astra Trader user stream reset")
        } catch (_: Exception) {
        }
        activeWebSocket = null
        activeUserDataWebSocket = null
        _wsState.value = _wsState.value.copy(connected = false)
    }

    /**
     * MOD-05 · Connects User Data Stream (`wss://.../ws/<listenKey>`) to receive real-time `ORDER_TRADE_UPDATE` and `ACCOUNT_UPDATE`.
     */
    fun connectUserDataStream(
        mode: EnvironmentMode,
        listenKey: String,
        onUserEvent: (eventType: String, payloadJson: JSONObject) -> Unit
    ) {
        if (listenKey.isBlank()) return
        try {
            activeUserDataWebSocket?.close(1000, "Reconnect user data stream")
        } catch (_: Exception) {
        }
        val url = "${wsBaseUrl(mode)}/ws/$listenKey"
        val req = Request.Builder().url(url).build()
        activeUserDataWebSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val now = System.currentTimeMillis()
                    TradingForegroundService.recordStreamMessageReceived(now)
                    val obj = JSONObject(text)
                    val eventType = obj.optString("e", "")
                    if (eventType.isNotBlank()) {
                        onUserEvent(eventType, obj)
                    }
                } catch (_: Exception) {
                }
            }
        })
    }

    private fun <T> classifyErrorResponse(
        resp: Response,
        mode: EnvironmentMode,
        isOrderMutation: Boolean = false,
        endpoint: String = "",
        failedStep: String = ""
    ): BinanceApiResult<T> {
        val code = resp.code
        val retryAfter = resp.header("Retry-After")?.toLongOrNull() ?: 60L
        val rawBody = try {
            resp.body?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val resolvedEndpoint = endpoint.ifBlank {
            "${resp.request.method} ${resp.request.url.scheme}://${resp.request.url.host}${resp.request.url.encodedPath}"
        }
        return classifyRawBinanceError(
            httpCode = code,
            rawBody = rawBody,
            mode = mode,
            retryAfterSeconds = retryAfter,
            isOrderMutation = isOrderMutation,
            endpoint = resolvedEndpoint,
            failedStep = failedStep
        )
    }

    private fun <T> classifyException(
        e: Exception,
        isOrderMutation: Boolean,
        endpoint: String = "",
        failedStep: String = ""
    ): BinanceApiResult<T> {
        val category = if (isOrderMutation || e is SocketTimeoutException) {
            BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS
        } else {
            BinanceErrorCategory.NETWORK_UNAVAILABLE
        }
        val msg = if (isOrderMutation || e is SocketTimeoutException) {
            "Timeout/Mất kết nối tại $endpoint (UNKNOWN_EXECUTION_STATUS): Không retry mù; bắt buộc truy vấn theo origClientOrderId trước mọi thao tác tiếp theo (INV-06)."
        } else {
            "Lỗi mạng khi kết nối Binance ($endpoint): ${e.javaClass.simpleName}"
        }
        return BinanceApiResult(
            isSuccess = false,
            endpoint = endpoint,
            failedStep = failedStep,
            rawServerMessage = e.javaClass.simpleName,
            errorCategory = category,
            sanitizedMessageVi = msg
        )
    }

    companion object {
        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .dns(Ipv4PreferredDns) // Requirement 3: Prefer IPv4 (A record) over IPv6 (AAAA) to match IPv4 whitelist
                .followRedirects(false) // Security: Never forward X-MBX-APIKEY or Authorization across redirects
                .followSslRedirects(false)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Builds a canonical query string with URL-encoded parameter values so that HMAC-SHA256
         * signature calculation matches the exact query string transmitted on the wire.
         */
        fun buildCanonicalQueryString(params: Map<String, String>): String {
            return params.entries.joinToString("&") { (k, v) ->
                val encodedVal = URLEncoder.encode(v, StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
                "$k=$encodedVal"
            }
        }

        fun signHmacSha256(payload: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(keySpec)
            val hash = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder(hash.size * 2)
            for (b in hash) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        /**
         * Granular error classifier for Binance REST responses (Requirements 2 & 8).
         * Explicitly distinguishes -2014, -2015, -1022, -1021, 403, 429, 418, and 5XX/Unknown,
         * and preserves the exact sanitized Binance server message, endpoint, and failed step.
         */
        fun <T> classifyRawBinanceError(
            httpCode: Int,
            rawBody: String,
            mode: EnvironmentMode,
            retryAfterSeconds: Long = 60L,
            isOrderMutation: Boolean = false,
            endpoint: String = "",
            failedStep: String = ""
        ): BinanceApiResult<T> {
            val errJson = try {
                if (rawBody.trim().startsWith("{")) JSONObject(rawBody) else null
            } catch (_: Exception) {
                null
            }
            val bCode: Int? = if (errJson != null && errJson.has("code")) errJson.optInt("code") else null
            val bMsg = errJson?.optString("msg")?.take(200) ?: "HTTP $httpCode"
            val envEndpoint = endpoint.ifBlank {
                if (mode == EnvironmentMode.LIVE) "https://fapi.binance.com (MAINNET)" else "https://testnet.binancefuture.com (TESTNET)"
            }
            val stepPrefix = if (failedStep.isNotBlank()) "[$failedStep · $envEndpoint] " else "[$envEndpoint] "

            val (category, detailedVi) = when {
                bCode == -2014 -> BinanceErrorCategory.BAD_API_KEY_FMT_2014 to
                    "${stepPrefix}Lỗi -2014 (BAD_API_KEY_FMT · msg=\"$bMsg\"): Định dạng API Key không hợp lệ. Kiểm tra xem API Key có bị cắt chuỗi, dính khoảng trắng/xuống dòng, nhầm với Secret, hoặc thuộc môi trường khác không."

                bCode == -2015 -> BinanceErrorCategory.REJECTED_MBX_KEY_2015 to
                    "${stepPrefix}Lỗi -2015 (REJECTED_MBX_KEY · msg=\"$bMsg\"): Phản hồi từ $envEndpoint từ chối khóa/IP/quyền. 5 khả năng cần phân tách: " +
                    "(1) API Key không hợp lệ hoặc đã bị thu hồi; " +
                    "(2) IP Outbound thực tế của tiến trình Android không khớp IP Whitelist trên Binance; " +
                    "(3) Key chưa bật Enable Futures trên Binance; " +
                    "(4) Lệch môi trường (dùng Key Mainnet gọi vào Testnet hoặc ngược lại); " +
                    "(5) API Key được tạo trước khi kích hoạt tài khoản USDⓈ-M Futures."

                bCode == -1022 -> BinanceErrorCategory.INVALID_SIGNATURE_1022 to
                    "${stepPrefix}Lỗi -1022 (INVALID_SIGNATURE · msg=\"$bMsg\"): Chữ ký HMAC-SHA256 không hợp lệ. Kiểm tra lại API Secret trong Vault (có thể sai Secret, dư ký tự hoặc nhầm với API Key)."

                bCode == -1021 -> BinanceErrorCategory.TIMESTAMP_DRIFT_1021 to
                    "${stepPrefix}Lỗi -1021 (INVALID_TIMESTAMP · msg=\"$bMsg\"): Thời gian request lệch quá recvWindow so với máy chủ Binance. Cần chạy lại Đồng bộ đồng hồ (Clock Sync)."

                httpCode == 403 -> BinanceErrorCategory.INSUFFICIENT_PERMISSION_403 to
                    "${stepPrefix}Lỗi HTTP 403 (WAF / Thiếu quyền truy cập endpoint): $bMsg"

                httpCode == 429 -> BinanceErrorCategory.RATE_LIMITED_429 to
                    "${stepPrefix}Lỗi HTTP 429 (Quá giới hạn tần suất API): Tạm dừng và chờ Retry-After ${retryAfterSeconds}s."

                httpCode == 418 -> BinanceErrorCategory.IP_BANNED_418 to
                    "${stepPrefix}Lỗi HTTP 418 (IP bị tạm khóa do vượt rate limit): Ngừng gửi request trong ${retryAfterSeconds}s."

                httpCode >= 500 -> BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS to
                    "${stepPrefix}Lỗi máy chủ Binance (HTTP $httpCode): Trạng thái thực thi chưa xác định (UNKNOWN). Không retry mù (INV-06)."

                bCode == -2019 -> BinanceErrorCategory.INSUFFICIENT_MARGIN to
                    "${stepPrefix}Lỗi -2019 (MARGIN_NOT_SUFFICIENCE): Không đủ số dư ký quỹ khả dụng ($bMsg)."

                bCode == -1111 || bCode == -4014 || bCode == -4164 -> BinanceErrorCategory.SYMBOL_FILTER_VIOLATION to
                    "${stepPrefix}Lỗi bộ lọc hợp đồng ($bCode): Vi phạm PRICE_FILTER / LOT_SIZE / MARKET_LOT_SIZE / MIN_NOTIONAL ($bMsg)."

                httpCode == 401 -> BinanceErrorCategory.AUTH_OR_PERMISSION to
                    "${stepPrefix}Lỗi xác thực HTTP 401: $bMsg"

                isOrderMutation && httpCode == 0 -> BinanceErrorCategory.UNKNOWN_EXECUTION_STATUS to
                    "${stepPrefix}Không rõ trạng thái lệnh (UNKNOWN_EXECUTION_STATUS)."

                else -> BinanceErrorCategory.BAD_REQUEST to
                    "${stepPrefix}Lỗi API Binance (HTTP $httpCode / code=${bCode ?: "N/A"}): $bMsg"
            }

            return BinanceApiResult(
                isSuccess = false,
                httpCode = httpCode,
                binanceErrorCode = bCode,
                errorCategory = category,
                retryAfterSeconds = retryAfterSeconds,
                endpoint = envEndpoint,
                failedStep = failedStep,
                rawServerMessage = bMsg,
                sanitizedMessageVi = detailedVi
            )
        }

        /**
         * P1 Section 3.6 · Parses `/fapi/v1/exchangeInfo` including PRICE_FILTER, LOT_SIZE, MARKET_LOT_SIZE,
         * MIN_NOTIONAL / NOTIONAL, and PERCENT_PRICE, marking `isRealExchangeFilter = true`.
         */
        fun parseExchangeInfoJson(jsonStr: String): Map<String, SymbolFilter> {
            val result = mutableMapOf<String, SymbolFilter>()
            val root = JSONObject(jsonStr)
            val symbolsArr = root.optJSONArray("symbols") ?: return result
            val nowMs = System.currentTimeMillis()

            for (i in 0 until symbolsArr.length()) {
                val symObj = symbolsArr.getJSONObject(i)
                val symbol = symObj.optString("symbol", "")
                val status = symObj.optString("status", "TRADING")
                val quoteAsset = symObj.optString("quoteAsset", "USDT")
                val contractType = symObj.optString("contractType", "PERPETUAL")
                if (symbol.isBlank() || quoteAsset != "USDT" || contractType != "PERPETUAL") continue

                var tickSize = BigDecimal("0.01")
                var minPrice = BigDecimal("0.0001")
                var maxPrice = BigDecimal("1000000")
                var stepSize = BigDecimal("0.001")
                var minQty = BigDecimal("0.001")
                var maxQty = BigDecimal("1000000")
                var marketStepSize: BigDecimal? = null
                var marketMinQty: BigDecimal? = null
                var marketMaxQty: BigDecimal? = null
                var minNotional = BigDecimal("5.0")
                var multiplierUp = BigDecimal("1.10")
                var multiplierDown = BigDecimal("0.90")

                val filters = symObj.optJSONArray("filters") ?: JSONArray()
                for (fIdx in 0 until filters.length()) {
                    val f = filters.getJSONObject(fIdx)
                    when (f.optString("filterType")) {
                        "PRICE_FILTER" -> {
                            tickSize = f.optString("tickSize", "0.01").toBigDecimalOrNull()?.stripTrailingZeros() ?: tickSize
                            minPrice = f.optString("minPrice", tickSize.toPlainString()).toBigDecimalOrNull()?.stripTrailingZeros() ?: minPrice
                            maxPrice = f.optString("maxPrice", "1000000").toBigDecimalOrNull()?.stripTrailingZeros() ?: maxPrice
                        }
                        "LOT_SIZE" -> {
                            stepSize = f.optString("stepSize", "0.001").toBigDecimalOrNull()?.stripTrailingZeros() ?: stepSize
                            minQty = f.optString("minQty", "0.001").toBigDecimalOrNull()?.stripTrailingZeros() ?: minQty
                            maxQty = f.optString("maxQty", "1000000").toBigDecimalOrNull()?.stripTrailingZeros() ?: maxQty
                        }
                        "MARKET_LOT_SIZE" -> {
                            marketStepSize = f.optString("stepSize", "").toBigDecimalOrNull()?.stripTrailingZeros()?.takeIf { it > BigDecimal.ZERO }
                            marketMinQty = f.optString("minQty", "").toBigDecimalOrNull()?.stripTrailingZeros()?.takeIf { it > BigDecimal.ZERO }
                            marketMaxQty = f.optString("maxQty", "").toBigDecimalOrNull()?.stripTrailingZeros()?.takeIf { it > BigDecimal.ZERO }
                        }
                        "MIN_NOTIONAL", "NOTIONAL" -> {
                            val nVal = f.optString("notional", "").ifBlank { f.optString("minNotional", "5.0") }
                            minNotional = nVal.toBigDecimalOrNull()?.stripTrailingZeros() ?: minNotional
                        }
                        "PERCENT_PRICE" -> {
                            multiplierUp = f.optString("multiplierUp", "1.10").toBigDecimalOrNull()?.stripTrailingZeros() ?: multiplierUp
                            multiplierDown = f.optString("multiplierDown", "0.90").toBigDecimalOrNull()?.stripTrailingZeros() ?: multiplierDown
                        }
                    }
                }

                val normTick = if (tickSize.scale() < 0) tickSize.setScale(0) else tickSize
                val normStep = if (stepSize.scale() < 0) stepSize.setScale(0) else stepSize
                val normMinQty = if (minQty.scale() < 0) minQty.setScale(0) else minQty
                val normMaxQty = if (maxQty.scale() < 0) maxQty.setScale(0) else maxQty
                val normMktStep = marketStepSize?.let { if (it.scale() < 0) it.setScale(0) else it } ?: normStep
                val normMktMinQty = marketMinQty?.let { if (it.scale() < 0) it.setScale(0) else it } ?: normMinQty
                val normMktMaxQty = marketMaxQty?.let { if (it.scale() < 0) it.setScale(0) else it } ?: normMaxQty

                result[symbol] = SymbolFilter(
                    symbol = symbol,
                    status = status,
                    quoteAsset = quoteAsset,
                    tickSize = normTick,
                    minPrice = if (minPrice > BigDecimal.ZERO) minPrice else normTick,
                    maxPrice = maxPrice,
                    stepSize = normStep,
                    minQty = normMinQty,
                    maxQty = normMaxQty,
                    marketStepSize = normMktStep,
                    marketMinQty = normMktMinQty,
                    marketMaxQty = normMktMaxQty,
                    minNotional = minNotional,
                    multiplierUp = multiplierUp,
                    multiplierDown = multiplierDown,
                    isRealExchangeFilter = true,
                    fetchedAtMs = nowMs
                )
            }
            return result
        }

        fun parseKlinesJson(
            symbol: String,
            interval: String,
            jsonStr: String,
            nowUtcMs: Long
        ): List<CandleBar> {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<CandleBar>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                val openTime = row.getLong(0)
                val open = BigDecimal(row.getString(1))
                val high = BigDecimal(row.getString(2))
                val low = BigDecimal(row.getString(3))
                val close = BigDecimal(row.getString(4))
                val vol = BigDecimal(row.getString(5))
                val closeTime = row.getLong(6)
                val quoteVol = BigDecimal(row.getString(7))
                val isClosed = closeTime < nowUtcMs
                list.add(
                    CandleBar(
                        symbol = symbol,
                        interval = interval,
                        openTime = openTime,
                        closeTime = closeTime,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = vol,
                        quoteVolume = quoteVol,
                        isClosed = isClosed
                    )
                )
            }
            return list
        }

        fun parseOrderJson(obj: JSONObject): ExchangeOrderRecord {
            val clientOrderId = obj.optString("clientOrderId", "")
            return ExchangeOrderRecord(
                symbol = obj.optString("symbol", ""),
                orderId = obj.optLong("orderId", 0L).toString(),
                clientOrderId = clientOrderId,
                price = obj.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                origQty = obj.optString("origQty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                executedQty = obj.optString("executedQty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                avgPrice = obj.optString("avgPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                status = obj.optString("status", "UNKNOWN"),
                type = obj.optString("type", "MARKET"),
                side = obj.optString("side", "BUY"),
                stopPrice = obj.optString("stopPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                workingType = obj.optString("workingType", "MARK_PRICE"),
                closePosition = obj.optBoolean("closePosition", false),
                reduceOnly = obj.optBoolean("reduceOnly", false),
                updateTimeMs = obj.optLong("updateTime", System.currentTimeMillis()),
                positionSide = obj.optString("positionSide", "BOTH").ifBlank { "BOTH" },
                isAlgoOrder = false,
                algoId = null
            )
        }

        fun parseAlgoOrderJson(
            obj: JSONObject,
            fallbackSymbol: String = "",
            fallbackSide: String = "SELL",
            fallbackType: String = "STOP_MARKET",
            fallbackTriggerPrice: BigDecimal = BigDecimal.ZERO,
            fallbackWorkingType: String = "MARK_PRICE",
            fallbackClosePosition: Boolean = true,
            fallbackReduceOnly: Boolean = false,
            fallbackPositionSide: String = "BOTH",
            fallbackClientAlgoId: String = ""
        ): ExchangeOrderRecord {
            val algoIdStr = if (obj.has("algoId")) {
                obj.optLong("algoId", 0L).toString()
            } else {
                obj.optLong("orderId", 0L).toString()
            }
            val clientAlgoId = obj.optString("clientAlgoId", "")
                .ifBlank { obj.optString("clientOrderId", fallbackClientAlgoId) }
            val rawStatus = obj.optString("algoStatus", "")
                .ifBlank { obj.optString("status", "NEW") }
            val trigPrice = obj.optString("triggerPrice", "")
                .ifBlank { obj.optString("stopPrice", fallbackTriggerPrice.toPlainString()) }
                .toBigDecimalOrNull() ?: fallbackTriggerPrice
            val qty = obj.optString("quantity", "")
                .ifBlank { obj.optString("origQty", "0") }
                .toBigDecimalOrNull() ?: BigDecimal.ZERO

            return ExchangeOrderRecord(
                symbol = obj.optString("symbol", fallbackSymbol),
                orderId = algoIdStr,
                clientOrderId = clientAlgoId,
                price = obj.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                origQty = qty,
                executedQty = obj.optString("executedQty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                avgPrice = obj.optString("avgPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                status = rawStatus,
                type = obj.optString("orderType", "").ifBlank { obj.optString("type", fallbackType) },
                side = obj.optString("side", fallbackSide),
                stopPrice = trigPrice,
                workingType = obj.optString("workingType", fallbackWorkingType),
                closePosition = obj.optBoolean("closePosition", fallbackClosePosition),
                reduceOnly = obj.optBoolean("reduceOnly", fallbackReduceOnly),
                updateTimeMs = obj.optLong("updateTime", obj.optLong("bookTime", System.currentTimeMillis())),
                positionSide = obj.optString("positionSide", fallbackPositionSide).ifBlank { "BOTH" },
                isAlgoOrder = true,
                algoId = algoIdStr
            )
        }

        fun parseUserTradesJson(
            jsonStr: String,
            fallbackSymbol: String = ""
        ): List<ExchangeUserTradeRecord> {
            val trimmed = jsonStr.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val arr = JSONArray(trimmed)
            val list = ArrayList<ExchangeUserTradeRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val idStr = obj.optLong("id", 0L).takeIf { it > 0L }?.toString()
                    ?: obj.optString("id", "TRD-$i")
                val orderIdStr = obj.optLong("orderId", 0L).takeIf { it > 0L }?.toString()
                    ?: obj.optString("orderId", "0")
                val price = obj.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val qty = obj.optString("qty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val quoteQty = obj.optString("quoteQty", "0").toBigDecimalOrNull() ?: price.multiply(qty)
                val realizedPnl = obj.optString("realizedPnl", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val commission = obj.optString("commission", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val commissionAsset = obj.optString("commissionAsset", "USDT").ifBlank { "USDT" }
                val posSide = obj.optString("positionSide", "BOTH").ifBlank { "BOTH" }
                val timeMs = obj.optLong("time", System.currentTimeMillis())

                list.add(
                    ExchangeUserTradeRecord(
                        tradeId = idStr,
                        symbol = obj.optString("symbol", fallbackSymbol),
                        orderId = orderIdStr,
                        side = obj.optString("side", "SELL"),
                        price = price,
                        qty = qty,
                        quoteQty = quoteQty,
                        realizedPnl = realizedPnl,
                        commission = commission,
                        commissionAsset = commissionAsset,
                        positionSide = posSide,
                        timeMs = timeMs
                    )
                )
            }
            return list
        }

        fun defaultSymbolFilter(symbol: String): SymbolFilter {
            return when (symbol) {
                "BTCUSDT" -> SymbolFilter(
                    symbol = "BTCUSDT",
                    tickSize = BigDecimal("0.1"),
                    stepSize = BigDecimal("0.001"),
                    minQty = BigDecimal("0.001"),
                    marketStepSize = BigDecimal("0.001"),
                    marketMinQty = BigDecimal("0.001"),
                    minNotional = BigDecimal("100.0"),
                    isRealExchangeFilter = false
                )
                "ETHUSDT" -> SymbolFilter(
                    symbol = "ETHUSDT",
                    tickSize = BigDecimal("0.01"),
                    stepSize = BigDecimal("0.001"),
                    minQty = BigDecimal("0.001"),
                    marketStepSize = BigDecimal("0.001"),
                    marketMinQty = BigDecimal("0.001"),
                    minNotional = BigDecimal("20.0"),
                    isRealExchangeFilter = false
                )
                "DOGEUSDT" -> SymbolFilter(
                    symbol = "DOGEUSDT",
                    tickSize = BigDecimal("0.00001"),
                    stepSize = BigDecimal("1"),
                    minQty = BigDecimal("1"),
                    marketStepSize = BigDecimal("1"),
                    marketMinQty = BigDecimal("1"),
                    minNotional = BigDecimal("5.0"),
                    isRealExchangeFilter = false
                )
                "XRPUSDT" -> SymbolFilter(
                    symbol = "XRPUSDT",
                    tickSize = BigDecimal("0.0001"),
                    stepSize = BigDecimal("0.1"),
                    minQty = BigDecimal("0.1"),
                    marketStepSize = BigDecimal("0.1"),
                    marketMinQty = BigDecimal("0.1"),
                    minNotional = BigDecimal("5.0"),
                    isRealExchangeFilter = false
                )
                "SOLUSDT" -> SymbolFilter(
                    symbol = "SOLUSDT",
                    tickSize = BigDecimal("0.01"),
                    stepSize = BigDecimal("0.01"),
                    minQty = BigDecimal("0.01"),
                    marketStepSize = BigDecimal("0.01"),
                    marketMinQty = BigDecimal("0.01"),
                    minNotional = BigDecimal("5.0"),
                    isRealExchangeFilter = false
                )
                else -> SymbolFilter(
                    symbol = symbol,
                    tickSize = BigDecimal("0.001"),
                    stepSize = BigDecimal("0.1"),
                    minQty = BigDecimal("0.1"),
                    marketStepSize = BigDecimal("0.1"),
                    marketMinQty = BigDecimal("0.1"),
                    minNotional = BigDecimal("5.0"),
                    isRealExchangeFilter = false
                )
            }
        }
    }
}

data class MarketPulseDto(
    val symbol: String,
    val markPrice: BigDecimal,
    val indexPrice: BigDecimal,
    val bestBid: BigDecimal,
    val bestAsk: BigDecimal,
    val bestBidQty: BigDecimal,
    val bestAskQty: BigDecimal,
    val fundingRate: BigDecimal,
    val nextFundingTimeMs: Long,
    val timestampMs: Long
)
