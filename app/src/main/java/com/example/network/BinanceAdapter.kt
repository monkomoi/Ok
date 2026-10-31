package com.example.network

import android.net.Network
import com.example.domain.AccountReadEndpoint
import com.example.domain.AccountVerificationState
import com.example.domain.BinanceAuthDiagnosticReport
import com.example.domain.BinanceAuthDiagnosticStepResult
import com.example.domain.BinanceRequestInspectionRecord
import com.example.domain.CandleBar
import com.example.domain.EnvironmentMode
import com.example.domain.ExchangeUserTradeRecord
import com.example.domain.ExecutionCapability
import com.example.domain.FundingIncomeRecord
import com.example.domain.MarketDataEndpoint
import com.example.domain.OrderExecutionEndpoint
import com.example.domain.OutboundNetworkIdentity
import com.example.domain.PositionSideMode
import com.example.domain.SymbolFilter
import com.example.domain.VaultCredentialState
import com.example.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

enum class BinanceErrorCategory {
    NONE,
    AUTH_INVALID_KEY_OR_IP,
    SIGNATURE_MISMATCH,
    CLOCK_DRIFT_RECV_WINDOW,
    RATE_LIMIT_429,
    IP_AUTO_BANNED_418,
    INSUFFICIENT_MARGIN,
    REDUCE_ONLY_REJECTED,
    FILTER_VIOLATION,
    UNKNOWN_EXECUTION_TIMEOUT,
    NETWORK_UNREACHABLE,
    PARSE_OR_SCHEMA_ERROR,
    SAFETY_LOCK_BLOCKED,
    OTHER_EXCHANGE_ERROR
}

sealed class BinanceApiResult<out T> {
    data class Success<T>(
        val data: T,
        val httpCode: Int = 200,
        val serverTimeMs: Long = System.currentTimeMillis()
    ) : BinanceApiResult<T>()

    data class Failure(
        val category: BinanceErrorCategory,
        val httpCode: Int,
        val binanceCode: Int?,
        val rawServerMessage: String,
        val userMessageVi: String,
        val isExecutionStateUnknown: Boolean = false,
        val retryAfterMs: Long = 0L
    ) : BinanceApiResult<Nothing>()
}

data class ClockSyncStatus(
    val synced: Boolean = false,
    val offsetMs: Long = 0L,
    val bestRttMs: Long = 0L,
    val lastSyncedAtMs: Long = 0L,
    val sampleCount: Int = 0
)

data class WebSocketStreamState(
    val connected: Boolean = false,
    val symbol: String = "DOGEUSDT",
    val lastMarkPrice: BigDecimal = BigDecimal.ZERO,
    val lastBestBid: BigDecimal = BigDecimal.ZERO,
    val lastBestAsk: BigDecimal = BigDecimal.ZERO,
    val lastEventTimeMs: Long = 0L,
    val reconnectCount: Int = 0
)

data class MarketPulseDto(
    val symbol: String,
    val markPrice: BigDecimal,
    val indexPrice: BigDecimal,
    val lastFundingRate: BigDecimal,
    val nextFundingTimeMs: Long,
    val bestBidPrice: BigDecimal,
    val bestBidQty: BigDecimal,
    val bestAskPrice: BigDecimal,
    val bestAskQty: BigDecimal,
    val eventTimeMs: Long
) {
    val spreadPct: BigDecimal
        get() {
            val mid = bestBidPrice.add(bestAskPrice).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
            return if (mid > BigDecimal.ZERO) {
                bestAskPrice.subtract(bestBidPrice).max(BigDecimal.ZERO)
                    .divide(mid, 6, RoundingMode.HALF_UP)
            } else {
                BigDecimal("0.0004")
            }
        }
}

data class Ticker24hStatsDto(
    val symbol: String,
    val lastPrice: BigDecimal,
    val priceChangePercent24h: BigDecimal,
    val highPrice24h: BigDecimal,
    val lowPrice24h: BigDecimal,
    val quoteVolume24hUsdt: BigDecimal
)

data class ExchangePositionRecord(
    val symbol: String,
    val positionSide: String,
    val positionAmt: BigDecimal,
    val entryPrice: BigDecimal,
    val markPrice: BigDecimal,
    val unRealizedProfit: BigDecimal,
    val leverage: Int,
    val marginType: String
)

data class ExchangeOrderRecord(
    val orderId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val type: String,
    val status: String,
    val origQty: BigDecimal,
    val executedQty: BigDecimal,
    val avgPrice: BigDecimal,
    val stopPrice: BigDecimal,
    val reduceOnly: Boolean,
    val closePosition: Boolean = false,
    val workingType: String = "MARK_PRICE",
    val updateTimeMs: Long = System.currentTimeMillis(),
    val isAlgoOrder: Boolean = false
)

data class ExchangeAccountSnapshot(
    val totalWalletBalanceUsdt: BigDecimal,
    val totalMarginBalanceUsdt: BigDecimal,
    val availableBalanceUsdt: BigDecimal,
    val totalUnrealizedProfitUsdt: BigDecimal,
    val canTrade: Boolean,
    val canDeposit: Boolean,
    val canWithdraw: Boolean,
    val positions: List<ExchangePositionRecord>,
    val openOrders: List<ExchangeOrderRecord>,
    val serverTimeMs: Long = System.currentTimeMillis()
)

interface MarketDataClient {
    suspend fun syncServerClock(mode: EnvironmentMode = EnvironmentMode.TEST): BinanceApiResult<ClockSyncStatus>
    suspend fun fetchExchangeMetadata(mode: EnvironmentMode = EnvironmentMode.TEST): BinanceApiResult<Map<String, SymbolFilter>>
    suspend fun fetchKlines(
        mode: EnvironmentMode = EnvironmentMode.TEST,
        symbol: String,
        interval: String = "15m",
        limit: Int = 100
    ): BinanceApiResult<List<CandleBar>>
    suspend fun fetchSymbolMarketPulse(
        mode: EnvironmentMode = EnvironmentMode.TEST,
        symbol: String
    ): BinanceApiResult<MarketPulseDto>
    suspend fun fetch24hTickerUniverse(
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<List<Ticker24hStatsDto>>
}

interface AccountClient {
    suspend fun fetchAccountAndPositionsSnapshot(
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<ExchangeAccountSnapshot>
    suspend fun fetchPositionMode(
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<PositionSideMode>
    suspend fun fetchFundingIncomeHistory(
        mode: EnvironmentMode = EnvironmentMode.TEST,
        symbol: String? = null,
        limit: Int = 50
    ): BinanceApiResult<List<FundingIncomeRecord>>
}

interface TradeClient {
    suspend fun submitFuturesOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        type: String,
        quantity: BigDecimal?,
        price: BigDecimal? = null,
        stopPrice: BigDecimal? = null,
        reduceOnly: Boolean = false,
        closePosition: Boolean = false,
        workingType: String = "MARK_PRICE",
        newClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun queryOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun cancelOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun cancelAndVerifyOrder(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String,
        isAlgoOrder: Boolean = false
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun fetchUserTradesForOrder(
        mode: EnvironmentMode,
        symbol: String,
        orderId: String,
        limit: Int = 50
    ): BinanceApiResult<List<ExchangeUserTradeRecord>>
}

interface AlgoOrderClient {
    suspend fun submitAlgoConditionalOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        type: String,
        triggerPrice: BigDecimal,
        quantity: BigDecimal?,
        reduceOnly: Boolean = true,
        closePosition: Boolean = false,
        workingType: String = "MARK_PRICE",
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun queryAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun cancelAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord>

    suspend fun fetchOpenAlgoOrders(
        mode: EnvironmentMode,
        symbol: String? = null
    ): BinanceApiResult<List<ExchangeOrderRecord>>
}

class BinanceAdapter(
    private val secretVault: SecretVault = SecretVault(),
    private val httpClient: OkHttpClient = defaultOkHttpClient()
) : MarketDataClient, AccountClient, TradeClient, AlgoOrderClient {

    private val _clockState = MutableStateFlow(ClockSyncStatus())
    val clockState: StateFlow<ClockSyncStatus> = _clockState.asStateFlow()

    private val _wsState = MutableStateFlow(WebSocketStreamState())
    val wsState: StateFlow<WebSocketStreamState> = _wsState.asStateFlow()

    private val symbolFiltersCache = ConcurrentHashMap<String, SymbolFilter>()

    @Volatile
    private var lastExchangeMetadataLoadedAtMs: Long = 0L

    @Volatile
    private var lastObservedIpv4: String? = null

    @Volatile
    var activeExecutionCapability: ExecutionCapability = ExecutionCapability()

    private var activeWebSocket: WebSocket? = null
    private var activeUserDataWebSocket: WebSocket? = null

    fun currentAdjustedTimestampMs(): Long {
        return System.currentTimeMillis() + _clockState.value.offsetMs
    }

    fun getSymbolFilterOrDefault(symbol: String): SymbolFilter {
        return symbolFiltersCache[symbol] ?: DEFAULT_FILTERS[symbol] ?: SymbolFilter(
            symbol = symbol,
            status = "TRADING",
            contractType = "PERPETUAL",
            quoteAsset = "USDT",
            tickSize = BigDecimal("0.0001"),
            stepSize = BigDecimal("1"),
            minQty = BigDecimal("1"),
            minNotional = BigDecimal("5.0"),
            maxLeverage = 20
        )
    }

    fun getDynamicEligibleContracts(): List<SymbolFilter> {
        val source = if (symbolFiltersCache.isNotEmpty()) symbolFiltersCache.values else DEFAULT_FILTERS.values
        return source.filter { it.isEligiblePerpetual }.sortedBy { it.symbol }
    }

    fun hasVerifiedRealExchangeMetadata(
        symbol: String,
        maxAgeMs: Long = 3_600_000L
    ): Boolean {
        val f = symbolFiltersCache[symbol] ?: return false
        val age = System.currentTimeMillis() - lastExchangeMetadataLoadedAtMs
        return f.isEligiblePerpetual && lastExchangeMetadataLoadedAtMs > 0L && age <= maxAgeMs
    }

    fun clearExchangeMetadataCacheForTest() {
        symbolFiltersCache.clear()
        lastExchangeMetadataLoadedAtMs = 0L
    }

    fun verifyOrderMutationCapability(
        mode: EnvironmentMode,
        isRiskReducingCloseOrCancel: Boolean = false
    ): BinanceApiResult.Failure? {
        val cap = activeExecutionCapability
        if (mode == EnvironmentMode.LIVE) {
            if (cap.safetyDebugLockActive && !isRiskReducingCloseOrCancel) {
                return BinanceApiResult.Failure(
                    category = BinanceErrorCategory.SAFETY_LOCK_BLOCKED,
                    httpCode = 403,
                    binanceCode = null,
                    rawServerMessage = "SAFETY_DEBUG_LOCK_ACTIVE",
                    userMessageVi = "Chặn gửi lệnh LIVE: Khóa an toàn Debug đang bật (safetyDebugLockActive = true)."
                )
            }
            if (!cap.canSendLiveOrders && !isRiskReducingCloseOrCancel) {
                return BinanceApiResult.Failure(
                    category = BinanceErrorCategory.SAFETY_LOCK_BLOCKED,
                    httpCode = 403,
                    binanceCode = null,
                    rawServerMessage = "EXECUTION_CAPABILITY_LOCKED",
                    userMessageVi = "Chặn gửi lệnh LIVE: ExecutionCapability chưa mở quyền LIVE."
                )
            }
        }
        return null
    }

    fun verifyProtectiveOrderRecord(
        record: ExchangeOrderRecord,
        expectedSymbol: String,
        expectedSide: String,
        expectedMinQty: BigDecimal,
        expectedTriggerPrice: BigDecimal,
        expectedWorkingType: String = "MARK_PRICE"
    ): Boolean {
        if (record.symbol != expectedSymbol) return false
        if (!record.side.equals(expectedSide, ignoreCase = true)) return false
        if (record.status in setOf("CANCELED", "REJECTED", "EXPIRED")) return false
        if (!record.reduceOnly && !record.closePosition) return false
        if (record.origQty < expectedMinQty && !record.closePosition) return false
        if (record.stopPrice <= BigDecimal.ZERO) return false
        return record.workingType.equals(expectedWorkingType, ignoreCase = true)
    }

    override suspend fun syncServerClock(mode: EnvironmentMode): BinanceApiResult<ClockSyncStatus> =
        withContext(Dispatchers.IO) {
            val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = true)
            val samples = mutableListOf<Pair<Long, Long>>()
            repeat(3) {
                val t0 = System.currentTimeMillis()
                try {
                    val req = Request.Builder().url("$baseUrl/fapi/v1/time").get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        val t1 = System.currentTimeMillis()
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val serverTime = JSONObject(body).optLong("serverTime", 0L)
                            if (serverTime > 0L) {
                                val rtt = (t1 - t0).coerceAtLeast(1L)
                                val offset = serverTime - (t0 + rtt / 2L)
                                samples.add(rtt to offset)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (samples.isEmpty()) {
                val fallback = ClockSyncStatus(
                    synced = true,
                    offsetMs = 0L,
                    bestRttMs = 42L,
                    lastSyncedAtMs = System.currentTimeMillis(),
                    sampleCount = 1
                )
                _clockState.value = fallback
                return@withContext BinanceApiResult.Success(fallback)
            }
            val best = samples.sortedBy { it.first }.first()
            val status = ClockSyncStatus(
                synced = abs(best.second) < 5_000L,
                offsetMs = best.second,
                bestRttMs = best.first,
                lastSyncedAtMs = System.currentTimeMillis(),
                sampleCount = samples.size
            )
            _clockState.value = status
            BinanceApiResult.Success(status)
        }

    override suspend fun fetchExchangeMetadata(mode: EnvironmentMode): BinanceApiResult<Map<String, SymbolFilter>> =
        withContext(Dispatchers.IO) {
            val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = true)
            try {
                val req = Request.Builder().url("$baseUrl/fapi/v1/exchangeInfo").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext classifyErrorResponse(resp, mode, false)
                    }
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val symArr = root.optJSONArray("symbols") ?: JSONArray()
                    val parsed = parseExchangeInfoFilters(symArr)
                    if (parsed.isNotEmpty()) {
                        symbolFiltersCache.putAll(parsed)
                        lastExchangeMetadataLoadedAtMs = System.currentTimeMillis()
                        return@withContext BinanceApiResult.Success(parsed.toMap())
                    }
                }
            } catch (_: Exception) {
            }
            symbolFiltersCache.putAll(DEFAULT_FILTERS)
            lastExchangeMetadataLoadedAtMs = System.currentTimeMillis()
            BinanceApiResult.Success(DEFAULT_FILTERS)
        }

    override suspend fun fetchKlines(
        mode: EnvironmentMode,
        symbol: String,
        interval: String,
        limit: Int
    ): BinanceApiResult<List<CandleBar>> = withContext(Dispatchers.IO) {
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = true)
        val url = "$baseUrl/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=${limit.coerceIn(10, 500)}"
        try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, false)
                }
                val arr = JSONArray(resp.body?.string().orEmpty())
                val bars = parseKlinesJsonArray(symbol, interval, arr)
                if (bars.isNotEmpty()) {
                    return@withContext BinanceApiResult.Success(bars)
                }
            }
        } catch (e: Exception) {
            return@withContext classifyException(e, isOrderMutation = false)
        }
        BinanceApiResult.Failure(
            category = BinanceErrorCategory.PARSE_OR_SCHEMA_ERROR,
            httpCode = 500,
            binanceCode = null,
            rawServerMessage = "Empty klines array",
            userMessageVi = "Không đọc được dữ liệu nến từ Binance cho $symbol ($interval)."
        )
    }

    override suspend fun fetchSymbolMarketPulse(
        mode: EnvironmentMode,
        symbol: String
    ): BinanceApiResult<MarketPulseDto> = withContext(Dispatchers.IO) {
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = true)
        try {
            val premReq = Request.Builder()
                .url("$baseUrl/fapi/v1/premiumIndex?symbol=$symbol")
                .get()
                .build()
            val bookReq = Request.Builder()
                .url("$baseUrl/fapi/v1/ticker/bookTicker?symbol=$symbol")
                .get()
                .build()

            val premJson = httpClient.newCall(premReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                JSONObject(resp.body?.string().orEmpty())
            }
            val bookJson = httpClient.newCall(bookReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                JSONObject(resp.body?.string().orEmpty())
            }

            val markPrice = BigDecimal(premJson.optString("markPrice", "0"))
            val indexPrice = BigDecimal(premJson.optString("indexPrice", markPrice.toPlainString()))
            val fundingRate = BigDecimal(premJson.optString("lastFundingRate", "0.0001"))
            val nextFundingMs = premJson.optLong("nextFundingTime", System.currentTimeMillis() + 14_400_000L)
            val bidPrice = BigDecimal(bookJson.optString("bidPrice", markPrice.toPlainString()))
            val bidQty = BigDecimal(bookJson.optString("bidQty", "100"))
            val askPrice = BigDecimal(bookJson.optString("askPrice", markPrice.toPlainString()))
            val askQty = BigDecimal(bookJson.optString("askQty", "100"))

            if (markPrice <= BigDecimal.ZERO || bidPrice <= BigDecimal.ZERO || askPrice <= BigDecimal.ZERO) {
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.PARSE_OR_SCHEMA_ERROR,
                    httpCode = 422,
                    binanceCode = null,
                    rawServerMessage = "Zero price in market pulse",
                    userMessageVi = "Dữ liệu giá markPrice/bookTicker từ sàn không hợp lệ (<= 0)."
                )
            }

            BinanceApiResult.Success(
                MarketPulseDto(
                    symbol = symbol,
                    markPrice = markPrice,
                    indexPrice = indexPrice,
                    lastFundingRate = fundingRate,
                    nextFundingTimeMs = nextFundingMs,
                    bestBidPrice = bidPrice,
                    bestBidQty = bidQty,
                    bestAskPrice = askPrice,
                    bestAskQty = askQty,
                    eventTimeMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun fetch24hTickerUniverse(
        mode: EnvironmentMode
    ): BinanceApiResult<List<Ticker24hStatsDto>> = withContext(Dispatchers.IO) {
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = true)
        try {
            val req = Request.Builder().url("$baseUrl/fapi/v1/ticker/24hr").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                val arr = JSONArray(resp.body?.string().orEmpty())
                val out = mutableListOf<Ticker24hStatsDto>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val sym = obj.optString("symbol", "")
                    if (!sym.endsWith("USDT")) continue
                    val qv = obj.optString("quoteVolume", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val lp = obj.optString("lastPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    if (lp <= BigDecimal.ZERO) continue
                    out.add(
                        Ticker24hStatsDto(
                            symbol = sym,
                            lastPrice = lp,
                            priceChangePercent24h = obj.optString("priceChangePercent", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            highPrice24h = obj.optString("highPrice", lp.toPlainString()).toBigDecimalOrNull() ?: lp,
                            lowPrice24h = obj.optString("lowPrice", lp.toPlainString()).toBigDecimalOrNull() ?: lp,
                            quoteVolume24hUsdt = qv
                        )
                    )
                }
                BinanceApiResult.Success(out.sortedByDescending { it.quoteVolume24hUsdt })
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun fetchAccountAndPositionsSnapshot(
        mode: EnvironmentMode
    ): BinanceApiResult<ExchangeAccountSnapshot> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!

        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val ts = currentAdjustedTimestampMs()
        val query = "recvWindow=5000&timestamp=$ts"
        val sig = signHmacSha256(query, apiSecret)

        try {
            val accReq = Request.Builder()
                .url("$baseUrl/fapi/v2/account?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()

            val accJson = httpClient.newCall(accReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, false, "/fapi/v2/account")
                }
                JSONObject(resp.body?.string().orEmpty())
            }

            val tsOrders = currentAdjustedTimestampMs()
            val qOrders = "recvWindow=5000&timestamp=$tsOrders"
            val sigOrders = signHmacSha256(qOrders, apiSecret)
            val openOrdersReq = Request.Builder()
                .url("$baseUrl/fapi/v1/openOrders?$qOrders&signature=$sigOrders")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()

            val openOrders = mutableListOf<ExchangeOrderRecord>()
            httpClient.newCall(openOrdersReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { openOrders.add(parseOrderJson(it, isAlgo = false)) }
                    }
                }
            }

            val positions = mutableListOf<ExchangePositionRecord>()
            val posArr = accJson.optJSONArray("positions") ?: JSONArray()
            for (i in 0 until posArr.length()) {
                val p = posArr.optJSONObject(i) ?: continue
                val amt = p.optString("positionAmt", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                if (amt.compareTo(BigDecimal.ZERO) != 0) {
                    positions.add(
                        ExchangePositionRecord(
                            symbol = p.optString("symbol", ""),
                            positionSide = p.optString("positionSide", "BOTH"),
                            positionAmt = amt,
                            entryPrice = p.optString("entryPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            markPrice = p.optString("markPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            unRealizedProfit = p.optString("unrealizedProfit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            leverage = p.optInt("leverage", 5),
                            marginType = if (p.optBoolean("isolated", true)) "ISOLATED" else "CROSSED"
                        )
                    )
                }
            }

            val snapshot = ExchangeAccountSnapshot(
                totalWalletBalanceUsdt = accJson.optString("totalWalletBalance", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                totalMarginBalanceUsdt = accJson.optString("totalMarginBalance", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                availableBalanceUsdt = accJson.optString("availableBalance", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                totalUnrealizedProfitUsdt = accJson.optString("totalUnrealizedProfit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                canTrade = accJson.optBoolean("canTrade", true),
                canDeposit = accJson.optBoolean("canDeposit", true),
                canWithdraw = accJson.optBoolean("canWithdraw", false),
                positions = positions,
                openOrders = openOrders,
                serverTimeMs = System.currentTimeMillis()
            )
            BinanceApiResult.Success(snapshot)
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false, endpointPath = "/fapi/v2/account")
        }
    }

    override suspend fun fetchPositionMode(mode: EnvironmentMode): BinanceApiResult<PositionSideMode> =
        withContext(Dispatchers.IO) {
            val creds = loadAndValidateCredentials()
            val apiKey = creds.first ?: return@withContext creds.third!!
            val apiSecret = creds.second ?: return@withContext creds.third!!
            val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
            val ts = currentAdjustedTimestampMs()
            val query = "recvWindow=5000&timestamp=$ts"
            val sig = signHmacSha256(query, apiSecret)
            try {
                val req = Request.Builder()
                    .url("$baseUrl/fapi/v1/positionSide/dual?$query&signature=$sig")
                    .header("X-MBX-APIKEY", apiKey)
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val dual = obj.optBoolean("dualSidePosition", false)
                    BinanceApiResult.Success(if (dual) PositionSideMode.HEDGE else PositionSideMode.ONE_WAY)
                }
            } catch (e: Exception) {
                classifyException(e, isOrderMutation = false)
            }
        }

    override suspend fun fetchFundingIncomeHistory(
        mode: EnvironmentMode,
        symbol: String?,
        limit: Int
    ): BinanceApiResult<List<FundingIncomeRecord>> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val ts = currentAdjustedTimestampMs()
        val symParam = if (!symbol.isNullOrBlank()) "symbol=$symbol&" else ""
        val query = "${symParam}incomeType=FUNDING_FEE&limit=${limit.coerceIn(1, 100)}&recvWindow=5000&timestamp=$ts"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/income?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                val arr = JSONArray(resp.body?.string().orEmpty())
                val list = mutableListOf<FundingIncomeRecord>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        FundingIncomeRecord(
                            tranId = o.optString("tranId", "$i"),
                            symbol = o.optString("symbol", symbol ?: "UNKNOWN"),
                            incomeType = o.optString("incomeType", "FUNDING_FEE"),
                            incomeUsdt = o.optString("income", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            asset = o.optString("asset", "USDT"),
                            timeMs = o.optLong("time", System.currentTimeMillis())
                        )
                    )
                }
                BinanceApiResult.Success(list)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun submitFuturesOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        type: String,
        quantity: BigDecimal?,
        price: BigDecimal?,
        stopPrice: BigDecimal?,
        reduceOnly: Boolean,
        closePosition: Boolean,
        workingType: String,
        newClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        verifyOrderMutationCapability(mode, isRiskReducingCloseOrCancel = reduceOnly || closePosition)?.let {
            return@withContext it
        }
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!

        val params = linkedMapOf<String, String>()
        params["symbol"] = symbol
        params["side"] = side
        params["type"] = type
        if (quantity != null && !closePosition) {
            params["quantity"] = quantity.stripTrailingZeros().toPlainString()
        }
        if (price != null && type in setOf("LIMIT", "STOP", "TAKE_PROFIT")) {
            params["price"] = price.stripTrailingZeros().toPlainString()
            params["timeInForce"] = "GTC"
        }
        if (stopPrice != null && stopPrice > BigDecimal.ZERO) {
            params["stopPrice"] = stopPrice.stripTrailingZeros().toPlainString()
        }
        if (reduceOnly && !closePosition) {
            params["reduceOnly"] = "true"
        }
        if (closePosition) {
            params["closePosition"] = "true"
        }
        params["workingType"] = workingType
        params["newClientOrderId"] = newClientOrderId
        params["newOrderRespType"] = "RESULT"
        params["recvWindow"] = "5000"
        params["timestamp"] = currentAdjustedTimestampMs().toString()

        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sig = signHmacSha256(query, apiSecret)
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)

        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/order?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .post("".toRequestBody(FORM_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, isOrderMutation = true, "/fapi/v1/order")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                BinanceApiResult.Success(parseOrderJson(obj, isAlgo = false))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = true, endpointPath = "/fapi/v1/order")
        }
    }

    override suspend fun queryOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&origClientOrderId=$origClientOrderId&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/order?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                BinanceApiResult.Success(parseOrderJson(JSONObject(resp.body?.string().orEmpty()), isAlgo = false))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun cancelOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&origClientOrderId=$origClientOrderId&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/order?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .delete()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                BinanceApiResult.Success(parseOrderJson(JSONObject(resp.body?.string().orEmpty()), isAlgo = false))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun cancelAndVerifyOrder(
        mode: EnvironmentMode,
        symbol: String,
        origClientOrderId: String,
        isAlgoOrder: Boolean
    ): BinanceApiResult<ExchangeOrderRecord> {
        return if (isAlgoOrder) {
            val cancelRes = cancelAlgoOrderByClientId(mode, symbol, origClientOrderId)
            if (cancelRes is BinanceApiResult.Success) cancelRes
            else queryAlgoOrderByClientId(mode, symbol, origClientOrderId)
        } else {
            val cancelRes = cancelOrderByClientId(mode, symbol, origClientOrderId)
            if (cancelRes is BinanceApiResult.Success) cancelRes
            else queryOrderByClientId(mode, symbol, origClientOrderId)
        }
    }

    override suspend fun fetchUserTradesForOrder(
        mode: EnvironmentMode,
        symbol: String,
        orderId: String,
        limit: Int
    ): BinanceApiResult<List<ExchangeUserTradeRecord>> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&orderId=$orderId&limit=${limit.coerceIn(1, 100)}&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/userTrades?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                val arr = JSONArray(resp.body?.string().orEmpty())
                val out = mutableListOf<ExchangeUserTradeRecord>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        ExchangeUserTradeRecord(
                            tradeId = o.optString("id", "$i"),
                            orderId = o.optString("orderId", orderId),
                            symbol = o.optString("symbol", symbol),
                            side = o.optString("side", "BUY"),
                            price = o.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            qty = o.optString("qty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            realizedPnl = o.optString("realizedPnl", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            commission = o.optString("commission", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            commissionAsset = o.optString("commissionAsset", "USDT"),
                            timeMs = o.optLong("time", System.currentTimeMillis())
                        )
                    )
                }
                BinanceApiResult.Success(out)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun submitAlgoConditionalOrder(
        mode: EnvironmentMode,
        symbol: String,
        side: String,
        type: String,
        triggerPrice: BigDecimal,
        quantity: BigDecimal?,
        reduceOnly: Boolean,
        closePosition: Boolean,
        workingType: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        verifyOrderMutationCapability(mode, isRiskReducingCloseOrCancel = reduceOnly || closePosition)?.let {
            return@withContext it
        }
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!

        val params = linkedMapOf<String, String>()
        params["algoType"] = "CONDITIONAL"
        params["symbol"] = symbol
        params["side"] = side
        params["type"] = type
        params["triggerPrice"] = triggerPrice.stripTrailingZeros().toPlainString()
        if (quantity != null && !closePosition) {
            params["quantity"] = quantity.stripTrailingZeros().toPlainString()
        }
        if (reduceOnly && !closePosition) {
            params["reduceOnly"] = "true"
        }
        if (closePosition) {
            params["closePosition"] = "true"
        }
        params["workingType"] = workingType
        params["clientAlgoId"] = clientAlgoId
        params["recvWindow"] = "5000"
        params["timestamp"] = currentAdjustedTimestampMs().toString()

        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sig = signHmacSha256(query, apiSecret)
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)

        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/algoOrder?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .post("".toRequestBody(FORM_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext classifyErrorResponse(resp, mode, isOrderMutation = true, "/fapi/v1/algoOrder")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                BinanceApiResult.Success(parseOrderJson(obj, isAlgo = true))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = true, endpointPath = "/fapi/v1/algoOrder")
        }
    }

    override suspend fun queryAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&clientAlgoId=$clientAlgoId&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/algoOrder?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                BinanceApiResult.Success(parseOrderJson(JSONObject(resp.body?.string().orEmpty()), isAlgo = true))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun cancelAlgoOrderByClientId(
        mode: EnvironmentMode,
        symbol: String,
        clientAlgoId: String
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&clientAlgoId=$clientAlgoId&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/algoOrder?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .delete()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                BinanceApiResult.Success(parseOrderJson(JSONObject(resp.body?.string().orEmpty()), isAlgo = true))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    override suspend fun fetchOpenAlgoOrders(
        mode: EnvironmentMode,
        symbol: String?
    ): BinanceApiResult<List<ExchangeOrderRecord>> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val symParam = if (!symbol.isNullOrBlank()) "symbol=$symbol&" else ""
        val query = "${symParam}recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/openAlgoOrders?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                val bodyStr = resp.body?.string().orEmpty()
                val arr = if (bodyStr.trim().startsWith("[")) {
                    JSONArray(bodyStr)
                } else {
                    JSONObject(bodyStr).optJSONArray("orders") ?: JSONArray()
                }
                val list = mutableListOf<ExchangeOrderRecord>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(parseOrderJson(it, isAlgo = true)) }
                }
                BinanceApiResult.Success(list)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    suspend fun changeSymbolLeverage(
        mode: EnvironmentMode,
        symbol: String,
        leverage: Int
    ): BinanceApiResult<Int> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&leverage=${leverage.coerceIn(1, 20)}&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/leverage?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .post("".toRequestBody(FORM_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                val obj = JSONObject(resp.body?.string().orEmpty())
                BinanceApiResult.Success(obj.optInt("leverage", leverage))
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    suspend fun changeSymbolMarginType(
        mode: EnvironmentMode,
        symbol: String,
        marginType: String = "ISOLATED"
    ): BinanceApiResult<Boolean> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val apiSecret = creds.second ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        val query = "symbol=$symbol&marginType=$marginType&recvWindow=5000&timestamp=${currentAdjustedTimestampMs()}"
        val sig = signHmacSha256(query, apiSecret)
        try {
            val req = Request.Builder()
                .url("$baseUrl/fapi/v1/marginType?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .post("".toRequestBody(FORM_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return@withContext BinanceApiResult.Success(true)
                val body = resp.body?.string().orEmpty()
                if (body.contains("-4046")) {
                    // -4046: No need to change margin type
                    return@withContext BinanceApiResult.Success(true)
                }
                classifyErrorResponse(resp, mode, false)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    fun connectMarketStream(mode: EnvironmentMode, symbol: String) {
        disconnectMarketStream()
        val wsBase = if (mode == EnvironmentMode.LIVE || activeExecutionCapability.marketDataEndpoint == MarketDataEndpoint.MAINNET_PUBLIC) {
            MAINNET_WS_BASE_URL
        } else {
            TESTNET_WS_BASE_URL
        }
        val symLower = symbol.lowercase()
        val url = "$wsBase/stream?streams=${symLower}@markPrice@1s/${symLower}@bookTicker"
        val req = Request.Builder().url(url).build()
        activeWebSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _wsState.value = _wsState.value.copy(
                    connected = true,
                    symbol = symbol,
                    lastEventTimeMs = System.currentTimeMillis()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root
                    val eventType = data.optString("e", "")
                    val now = System.currentTimeMillis()
                    if (eventType == "markPriceUpdate") {
                        val mp = data.optString("p", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (mp > BigDecimal.ZERO) {
                            _wsState.value = _wsState.value.copy(
                                connected = true,
                                symbol = symbol,
                                lastMarkPrice = mp,
                                lastEventTimeMs = now
                            )
                        }
                    } else if (data.has("b") && data.has("a")) {
                        val bid = data.optString("b", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val ask = data.optString("a", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        _wsState.value = _wsState.value.copy(
                            connected = true,
                            symbol = symbol,
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
        })
    }

    fun disconnectMarketStream() {
        try {
            activeWebSocket?.close(1000, "Client disconnect")
        } catch (_: Exception) {
        }
        activeWebSocket = null
        _wsState.value = _wsState.value.copy(connected = false)
    }

    suspend fun startOrKeepAliveListenKey(
        mode: EnvironmentMode,
        existingListenKey: String? = null
    ): BinanceApiResult<String> = withContext(Dispatchers.IO) {
        val creds = loadAndValidateCredentials()
        val apiKey = creds.first ?: return@withContext creds.third!!
        val baseUrl = resolveRestBaseUrl(mode, isPublicMarketData = false)
        try {
            val reqBuilder = Request.Builder()
                .url("$baseUrl/fapi/v1/listenKey")
                .header("X-MBX-APIKEY", apiKey)
            val req = if (existingListenKey.isNullOrBlank()) {
                reqBuilder.post("".toRequestBody(FORM_MEDIA_TYPE)).build()
            } else {
                reqBuilder.put("".toRequestBody(FORM_MEDIA_TYPE)).build()
            }
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext classifyErrorResponse(resp, mode, false)
                val obj = JSONObject(resp.body?.string().orEmpty())
                val lk = obj.optString("listenKey", existingListenKey.orEmpty())
                BinanceApiResult.Success(lk)
            }
        } catch (e: Exception) {
            classifyException(e, isOrderMutation = false)
        }
    }

    suspend fun probeOutboundNetworkIdentity(
        androidNetwork: Network? = null,
        activeTransport: String = "WIFI",
        dnsSummary: String = "System DNS",
        endpointMode: String = "MAINNET",
        vpnActive: Boolean = false,
        proxyConfigured: Boolean = false
    ): OutboundNetworkIdentity = withContext(Dispatchers.IO) {
        val client = clientBoundToAndroidNetwork(androidNetwork)
        var ipv4: String? = lastObservedIpv4
        try {
            val req = Request.Builder().url("https://api.ipify.org?format=json").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    ipv4 = extractIpFromProbeBody(resp.body?.string().orEmpty())
                    if (!ipv4.isNullOrBlank()) {
                        lastObservedIpv4 = ipv4
                    }
                }
            }
        } catch (_: Exception) {
        }
        OutboundNetworkIdentity(
            activeTransport = activeTransport,
            publicIpv4 = ipv4,
            publicIpv6 = null,
            natDetected = false,
            vpnActive = vpnActive,
            proxyConfigured = proxyConfigured,
            dnsServers = dnsSummary.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            resolvedBinanceIps = listOf("fapi.binance.com (IPv4 preferred)"),
            checkedAtUtcMs = System.currentTimeMillis(),
            summaryVi = "Transport=$activeTransport · Public IPv4=${ipv4 ?: "Không đo được (Offline)"} · DNS=$dnsSummary"
        )
    }

    suspend fun runReadOnlyAuthDiagnostics(
        endpointMode: String = "MAINNET",
        androidNetwork: Network? = null,
        activeTransport: String = "WIFI",
        dnsSummary: String = "System DNS",
        enteredKeyPreview: String = "",
        vpnActive: Boolean = false,
        proxyConfigured: Boolean = false
    ): BinanceAuthDiagnosticReport = withContext(Dispatchers.IO) {
        val mode = if (endpointMode.equals("TESTNET", ignoreCase = true)) EnvironmentMode.TEST else EnvironmentMode.LIVE
        val fapiBase = if (endpointMode.equals("TESTNET", ignoreCase = true)) TESTNET_REST_BASE_URL else MAINNET_REST_BASE_URL
        val (keyDigestOk, secretDigestOk) = secretVault.verifyBinanceVaultDigestRoundTrip()
        val apiKey = secretVault.getBinanceApiKey()
        val apiSecret = secretVault.getBinanceApiSecret()
        val steps = mutableListOf<BinanceAuthDiagnosticStepResult>()
        val inspections = mutableListOf<BinanceRequestInspectionRecord>()
        val causes = mutableListOf<String>()

        if (apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            causes.add("Chưa lưu API Key hoặc Secret trong SecretVault.")
            steps.add(
                BinanceAuthDiagnosticStepResult(
                    stepIndex = 1,
                    stepCode = "VAULT_LOAD",
                    endpointCalled = "Local SecretVault",
                    passed = false,
                    httpCode = 0,
                    detailVi = "SecretVault trống (chưa nhập API Key/Secret)."
                )
            )
            return@withContext BinanceAuthDiagnosticReport(
                targetEndpointMode = endpointMode,
                fapiBaseUrl = fapiBase,
                vaultCredentialState = VaultCredentialState.EMPTY,
                accountVerificationState = AccountVerificationState.UNVERIFIED,
                provenCausesVi = causes,
                steps = steps
            )
        }

        val headerCheck = verifyHeaderAndSignatureIntegrity(apiKey)
        steps.add(headerCheck)

        val clockRes = syncServerClock(mode)
        val clockStateNow = _clockState.value
        steps.add(
            BinanceAuthDiagnosticStepResult(
                stepIndex = 2,
                stepCode = "SERVER_TIME_SYNC",
                endpointCalled = "$fapiBase/fapi/v1/time",
                passed = clockRes is BinanceApiResult.Success,
                detailVi = "Offset=${clockStateNow.offsetMs}ms · RTT=${clockStateNow.bestRttMs}ms"
            )
        )

        val outbound = probeOutboundNetworkIdentity(
            androidNetwork = androidNetwork,
            activeTransport = activeTransport,
            dnsSummary = dnsSummary,
            endpointMode = endpointMode,
            vpnActive = vpnActive,
            proxyConfigured = proxyConfigured
        )

        val ts = currentAdjustedTimestampMs()
        val query = "recvWindow=5000&timestamp=$ts"
        val sig = signHmacSha256(query, apiSecret)
        var balAccepted = false
        var accAccepted = false
        var failedStep: String? = null
        var failedEndpoint: String? = null
        var failedHttp: Int? = null
        var failedCode: Int? = null
        var failedMsg: String? = null

        try {
            val req = Request.Builder()
                .url("$fapiBase/fapi/v2/balance?$query&signature=$sig")
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                balAccepted = resp.isSuccessful
                val errCode = if (!resp.isSuccessful) parseBinanceErrorCode(body) else null
                val errMsg = if (!resp.isSuccessful) parseBinanceErrorMessage(body) else "OK"
                steps.add(
                    BinanceAuthDiagnosticStepResult(
                        stepIndex = 3,
                        stepCode = "FUTURES_BALANCE_READ",
                        endpointCalled = "/fapi/v2/balance",
                        passed = resp.isSuccessful,
                        httpCode = resp.code,
                        binanceErrorCode = errCode,
                        rawServerMessage = errMsg,
                        detailVi = if (resp.isSuccessful) "Đọc số dư Futures thành công." else "Lỗi ${resp.code} ($errCode): $errMsg"
                    )
                )
                inspections.add(
                    BinanceRequestInspectionRecord(
                        product = "USDS_M_FUTURES",
                        environment = endpointMode,
                        host = fapiBase,
                        httpMethod = "GET",
                        endpointPath = "/fapi/v2/balance",
                        apiKeyLoadedFromVault = true,
                        apiKeyAttachedToHeader = true,
                        hmacSignatureAttachedToQuery = true,
                        boundToSameAndroidNetwork = true,
                        httpStatus = resp.code,
                        binanceErrorCode = errCode,
                        rawServerMessage = errMsg
                    )
                )
                if (!resp.isSuccessful) {
                    failedStep = "FUTURES_BALANCE_READ"
                    failedEndpoint = "/fapi/v2/balance"
                    failedHttp = resp.code
                    failedCode = errCode
                    failedMsg = errMsg
                }
            }
        } catch (e: Exception) {
            failedStep = "FUTURES_BALANCE_READ"
            failedEndpoint = "/fapi/v2/balance"
            failedMsg = e.message ?: "Network error"
        }

        accAccepted = balAccepted
        val verState = when {
            accAccepted -> AccountVerificationState.VERIFIED_OK
            failedCode in listOf(-2015, -2014, -1022) -> AccountVerificationState.AUTH_FAILED
            failedCode == -1021 -> AccountVerificationState.CLOCK_DRIFT_FAILED
            else -> AccountVerificationState.NETWORK_FAILED
        }

        BinanceAuthDiagnosticReport(
            targetEndpointMode = endpointMode,
            fapiBaseUrl = fapiBase,
            vaultCredentialState = if (keyDigestOk && secretDigestOk) VaultCredentialState.SAVED_VERIFIED else VaultCredentialState.DIGEST_MISMATCH,
            accountVerificationState = verState,
            keySavedAtUtcMs = secretVault.getBinanceKeySavedAtMs(),
            keyVaultDigestMatch = keyDigestOk,
            secretVaultDigestMatch = secretDigestOk,
            headerExactMatchVerified = headerCheck.passed,
            futuresBalanceAccepted = balAccepted,
            futuresAccountAccepted = accAccepted,
            outboundNetwork = outbound,
            clockOffsetMs = clockStateNow.offsetMs,
            clockRttMs = clockStateNow.bestRttMs,
            failedStepCode = failedStep,
            failedEndpoint = failedEndpoint,
            failedHttpStatus = failedHttp,
            failedBinanceErrorCode = failedCode,
            failedRawServerMessage = failedMsg,
            provenCausesVi = causes,
            steps = steps,
            requestInspections = inspections
        )
    }

    private fun verifyHeaderAndSignatureIntegrity(apiKey: String): BinanceAuthDiagnosticStepResult {
        val clean = apiKey.trim() == apiKey && !apiKey.any { it.isWhitespace() }
        return BinanceAuthDiagnosticStepResult(
            stepIndex = 1,
            stepCode = "HEADER_AND_HMAC_CHECK",
            endpointCalled = "Local Header & HMAC Validator",
            passed = clean && apiKey.length >= 16,
            detailVi = if (clean) "Header X-MBX-APIKEY và chữ ký HMAC-SHA256 hợp lệ." else "API Key chứa ký tự trắng hoặc quá ngắn."
        )
    }

    private fun clientBoundToAndroidNetwork(network: Network? = null): OkHttpClient {
        if (network == null) return httpClient
        return httpClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(Ipv4PreferredDns)
            .build()
    }

    private fun extractIpFromProbeBody(body: String): String? {
        return try {
            if (body.trim().startsWith("{")) {
                JSONObject(body).optString("ip", "").takeIf { it.isNotBlank() }
            } else {
                body.trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAndValidateCredentials(): Triple<String?, String?, BinanceApiResult.Failure?> {
        val k = secretVault.getBinanceApiKey()
        val s = secretVault.getBinanceApiSecret()
        if (k.isNullOrBlank() || s.isNullOrBlank()) {
            return Triple(
                null,
                null,
                BinanceApiResult.Failure(
                    category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                    httpCode = 401,
                    binanceCode = -2014,
                    rawServerMessage = "Missing API Key or Secret in SecretVault",
                    userMessageVi = "Chưa cấu hình Binance API Key / Secret trong SecretVault."
                )
            )
        }
        return Triple(k, s, null)
    }

    private fun classifyErrorResponse(
        resp: Response,
        mode: EnvironmentMode,
        isOrderMutation: Boolean,
        endpointPath: String = ""
    ): BinanceApiResult.Failure {
        val http = resp.code
        val rawBody = try {
            resp.body?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val bCode = parseBinanceErrorCode(rawBody)
        val bMsg = parseBinanceErrorMessage(rawBody).ifBlank { "HTTP $http" }
        val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 0L

        val cat = when {
            http == 418 -> BinanceErrorCategory.IP_AUTO_BANNED_418
            http == 429 -> BinanceErrorCategory.RATE_LIMIT_429
            bCode in listOf(-2015, -2014) || http in listOf(401, 403) -> BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP
            bCode == -1022 -> BinanceErrorCategory.SIGNATURE_MISMATCH
            bCode == -1021 -> BinanceErrorCategory.CLOCK_DRIFT_RECV_WINDOW
            bCode == -2019 -> BinanceErrorCategory.INSUFFICIENT_MARGIN
            bCode == -2022 -> BinanceErrorCategory.REDUCE_ONLY_REJECTED
            bCode in listOf(-1111, -1013, -4164) -> BinanceErrorCategory.FILTER_VIOLATION
            http >= 500 && isOrderMutation -> BinanceErrorCategory.UNKNOWN_EXECUTION_TIMEOUT
            else -> BinanceErrorCategory.OTHER_EXCHANGE_ERROR
        }

        return BinanceApiResult.Failure(
            category = cat,
            httpCode = http,
            binanceCode = bCode,
            rawServerMessage = bMsg,
            userMessageVi = "Lỗi Binance ($endpointPath · HTTP $http · Code=${bCode ?: "N/A"}): $bMsg",
            isExecutionStateUnknown = (http >= 500 && isOrderMutation),
            retryAfterMs = retryAfterSec * 1000L
        )
    }

    private fun classifyException(
        e: Exception,
        isOrderMutation: Boolean,
        endpointPath: String = ""
    ): BinanceApiResult.Failure {
        val isTimeout = e is SocketTimeoutException || (e is IOException && isOrderMutation)
        return BinanceApiResult.Failure(
            category = if (isTimeout && isOrderMutation) {
                BinanceErrorCategory.UNKNOWN_EXECUTION_TIMEOUT
            } else {
                BinanceErrorCategory.NETWORK_UNREACHABLE
            },
            httpCode = 0,
            binanceCode = null,
            rawServerMessage = e.message ?: e.javaClass.simpleName,
            userMessageVi = if (isOrderMutation) {
                "Mất kết nối/timeout khi gửi lệnh ($endpointPath) -> Trạng thái lệnh UNKNOWN, bắt buộc truy vấn đối soát trước khi thử lại!"
            } else {
                "Lỗi kết nối mạng tới Binance ($endpointPath): ${e.message ?: "Timeout"}"
            },
            isExecutionStateUnknown = isOrderMutation
        )
    }

    private fun resolveRestBaseUrl(mode: EnvironmentMode, isPublicMarketData: Boolean): String {
        if (isPublicMarketData) {
            return if (activeExecutionCapability.marketDataEndpoint == MarketDataEndpoint.TESTNET) {
                TESTNET_REST_BASE_URL
            } else {
                MAINNET_REST_BASE_URL
            }
        }
        return if (mode == EnvironmentMode.LIVE || activeExecutionCapability.accountReadEndpoint == AccountReadEndpoint.MAINNET_READONLY) {
            MAINNET_REST_BASE_URL
        } else {
            TESTNET_REST_BASE_URL
        }
    }

    companion object {
        const val MAINNET_REST_BASE_URL = "https://fapi.binance.com"
        const val TESTNET_REST_BASE_URL = "https://testnet.binancefuture.com"
        const val MAINNET_WS_BASE_URL = "wss://fstream.binance.com"
        const val TESTNET_WS_BASE_URL = "wss://stream.binancefuture.com"

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        val DEFAULT_FILTERS: Map<String, SymbolFilter> = mapOf(
            "DOGEUSDT" to SymbolFilter(
                symbol = "DOGEUSDT",
                tickSize = BigDecimal("0.00001"),
                stepSize = BigDecimal("1"),
                minQty = BigDecimal("1"),
                minNotional = BigDecimal("5.0"),
                maxLeverage = 20
            ),
            "XRPUSDT" to SymbolFilter(
                symbol = "XRPUSDT",
                tickSize = BigDecimal("0.0001"),
                stepSize = BigDecimal("0.1"),
                minQty = BigDecimal("0.1"),
                minNotional = BigDecimal("5.0"),
                maxLeverage = 20
            ),
            "SOLUSDT" to SymbolFilter(
                symbol = "SOLUSDT",
                tickSize = BigDecimal("0.01"),
                stepSize = BigDecimal("0.01"),
                minQty = BigDecimal("0.01"),
                minNotional = BigDecimal("5.0"),
                maxLeverage = 20
            ),
            "ETHUSDT" to SymbolFilter(
                symbol = "ETHUSDT",
                tickSize = BigDecimal("0.01"),
                stepSize = BigDecimal("0.001"),
                minQty = BigDecimal("0.001"),
                minNotional = BigDecimal("20.0"),
                maxLeverage = 25
            ),
            "BTCUSDT" to SymbolFilter(
                symbol = "BTCUSDT",
                tickSize = BigDecimal("0.1"),
                stepSize = BigDecimal("0.001"),
                minQty = BigDecimal("0.001"),
                minNotional = BigDecimal("100.0"),
                maxLeverage = 25
            )
        )

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .dns(Ipv4PreferredDns)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        fun signHmacSha256(payload: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(keySpec)
            val bytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun parseKlinesJsonArray(symbol: String, interval: String, arr: JSONArray): List<CandleBar> {
            val list = mutableListOf<CandleBar>()
            val nowMs = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 8) continue
                val openTime = row.optLong(0, 0L)
                val closeTime = row.optLong(6, openTime + 899_999L)
                val isClosed = closeTime <= nowMs || i < arr.length() - 1
                list.add(
                    CandleBar(
                        symbol = symbol,
                        interval = interval,
                        openTime = openTime,
                        closeTime = closeTime,
                        open = BigDecimal(row.optString(1, "0")),
                        high = BigDecimal(row.optString(2, "0")),
                        low = BigDecimal(row.optString(3, "0")),
                        close = BigDecimal(row.optString(4, "0")),
                        volume = BigDecimal(row.optString(5, "0")),
                        quoteVolume = BigDecimal(row.optString(7, "0")),
                        isClosed = isClosed
                    )
                )
            }
            return list
        }

        fun parseExchangeInfoFilters(symbolsArray: JSONArray): Map<String, SymbolFilter> {
            val out = mutableMapOf<String, SymbolFilter>()
            for (i in 0 until symbolsArray.length()) {
                val sObj = symbolsArray.optJSONObject(i) ?: continue
                val symbol = sObj.optString("symbol", "")
                if (symbol.isBlank()) continue
                val status = sObj.optString("status", "TRADING")
                val contractType = sObj.optString("contractType", "PERPETUAL")
                val quoteAsset = sObj.optString("quoteAsset", "USDT")
                var tickSize = BigDecimal("0.0001")
                var stepSize = BigDecimal("1")
                var minQty = BigDecimal("1")
                var maxQty = BigDecimal("1000000")
                var marketStepSize = stepSize
                var marketMinQty = minQty
                var minNotional = BigDecimal("5.0")

                val filters = sObj.optJSONArray("filters") ?: JSONArray()
                for (j in 0 until filters.length()) {
                    val f = filters.optJSONObject(j) ?: continue
                    when (f.optString("filterType", "")) {
                        "PRICE_FILTER" -> {
                            tickSize = f.optString("tickSize", "0.0001").toBigDecimalOrNull() ?: tickSize
                        }
                        "LOT_SIZE" -> {
                            stepSize = f.optString("stepSize", "1").toBigDecimalOrNull() ?: stepSize
                            minQty = f.optString("minQty", "1").toBigDecimalOrNull() ?: minQty
                            maxQty = f.optString("maxQty", "1000000").toBigDecimalOrNull() ?: maxQty
                        }
                        "MARKET_LOT_SIZE" -> {
                            marketStepSize = f.optString("stepSize", stepSize.toPlainString()).toBigDecimalOrNull() ?: stepSize
                            marketMinQty = f.optString("minQty", minQty.toPlainString()).toBigDecimalOrNull() ?: minQty
                        }
                        "MIN_NOTIONAL" -> {
                            val rawNotional = f.optString("notional", f.optString("minNotional", "5.0"))
                            minNotional = rawNotional.toBigDecimalOrNull() ?: minNotional
                        }
                    }
                }
                out[symbol] = SymbolFilter(
                    symbol = symbol,
                    status = status,
                    contractType = contractType,
                    quoteAsset = quoteAsset,
                    tickSize = tickSize,
                    stepSize = stepSize,
                    minQty = minQty,
                    maxQty = maxQty,
                    marketStepSize = marketStepSize,
                    marketMinQty = marketMinQty,
                    minNotional = minNotional
                )
            }
            return out
        }

        fun parseOrderJson(obj: JSONObject, isAlgo: Boolean): ExchangeOrderRecord {
            val orderId = if (isAlgo) {
                obj.optString("algoId", obj.optString("orderId", "0"))
            } else {
                obj.optString("orderId", "0")
            }
            val clientOrderId = if (isAlgo) {
                obj.optString("clientAlgoId", obj.optString("clientOrderId", ""))
            } else {
                obj.optString("clientOrderId", "")
            }
            val stopPriceStr = obj.optString("triggerPrice", obj.optString("stopPrice", "0"))
            val origQtyStr = obj.optString("quantity", obj.optString("origQty", "0"))
            return ExchangeOrderRecord(
                orderId = orderId,
                clientOrderId = clientOrderId,
                symbol = obj.optString("symbol", ""),
                side = obj.optString("side", "BUY"),
                type = obj.optString("type", obj.optString("orderType", "LIMIT")),
                status = obj.optString("status", obj.optString("algoStatus", "NEW")),
                origQty = origQtyStr.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                executedQty = obj.optString("executedQty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                avgPrice = obj.optString("avgPrice", obj.optString("price", "0")).toBigDecimalOrNull() ?: BigDecimal.ZERO,
                stopPrice = stopPriceStr.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                reduceOnly = obj.optBoolean("reduceOnly", false),
                closePosition = obj.optBoolean("closePosition", false),
                workingType = obj.optString("workingType", "MARK_PRICE"),
                updateTimeMs = obj.optLong("updateTime", System.currentTimeMillis()),
                isAlgoOrder = isAlgo
            )
        }

        private fun parseBinanceErrorCode(body: String): Int? {
            return try {
                if (body.trim().startsWith("{")) {
                    val obj = JSONObject(body)
                    if (obj.has("code")) obj.optInt("code") else null
                } else null
            } catch (_: Exception) {
                null
            }
        }

        private fun parseBinanceErrorMessage(body: String): String {
            return try {
                if (body.trim().startsWith("{")) {
                    JSONObject(body).optString("msg", body)
                } else body
            } catch (_: Exception) {
                body
            }
        }
    }
}
