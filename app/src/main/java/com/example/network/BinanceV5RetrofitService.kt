package com.example.network

import com.example.BuildConfig
import com.example.domain.CandleBar
import com.example.domain.EnvironmentMode
import com.example.domain.ExecutionCapability
import com.example.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Converter
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import java.io.IOException
import java.lang.reflect.Type
import java.math.BigDecimal
import java.net.SocketTimeoutException

/**
 * Retrofit V5 / USDⓈ-M Futures API interface for real-time market data, account queries,
 * leverage/margin configuration, and signed order / conditional Algo Order placement.
 */
interface BinanceV5ApiService {
    @GET("fapi/v1/ping")
    suspend fun ping(): Response<JSONObject>

    @GET("fapi/v1/time")
    suspend fun getServerTime(): Response<JSONObject>

    @GET("fapi/v1/exchangeInfo")
    suspend fun getExchangeInfo(): Response<JSONObject>

    @GET("fapi/v1/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 100
    ): Response<JSONArray>

    @GET("fapi/v1/depth")
    suspend fun getOrderBookDepth(
        @Query("symbol") symbol: String,
        @Query("limit") limit: Int = 20
    ): Response<JSONObject>

    @GET("fapi/v1/premiumIndex")
    suspend fun getPremiumIndex(
        @Query("symbol") symbol: String
    ): Response<JSONObject>

    @GET("fapi/v1/openInterest")
    suspend fun getOpenInterest(
        @Query("symbol") symbol: String
    ): Response<JSONObject>

    @GET("fapi/v1/ticker/bookTicker")
    suspend fun getBookTicker(
        @Query("symbol") symbol: String
    ): Response<JSONObject>

    @GET("fapi/v1/ticker/24hr")
    suspend fun get24hTickers(): Response<JSONArray>

    @GET("fapi/v2/account")
    suspend fun getAccountInfo(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @GET("fapi/v2/balance")
    suspend fun getAccountBalances(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONArray>

    @GET("fapi/v2/positionRisk")
    suspend fun getPositionRisk(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONArray>

    @POST("fapi/v1/leverage")
    suspend fun changeInitialLeverage(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @POST("fapi/v1/marginType")
    suspend fun changeMarginType(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @POST("fapi/v1/order")
    suspend fun placeFuturesOrder(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @GET("fapi/v1/order")
    suspend fun queryFuturesOrder(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @DELETE("fapi/v1/order")
    suspend fun cancelFuturesOrder(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @DELETE("fapi/v1/allOpenOrders")
    suspend fun cancelAllOpenOrders(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>

    @POST("fapi/v1/algoOrder")
    suspend fun placeAlgoConditionalOrder(
        @Header("X-MBX-APIKEY") apiKey: String,
        @QueryMap(encoded = true) signedParams: Map<String, String>
    ): Response<JSONObject>
}

/**
 * Zero-reflection org.json Converter.Factory for Retrofit2 (S7 Edge memory & R8 friendly).
 */
object OrgJsonConverterFactory : Converter.Factory() {
    private val jsonMediaType: MediaType = "application/json; charset=utf-8".toMediaType()

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        return when (type) {
            String::class.java -> Converter { body -> body.use { it.string() } }
            JSONObject::class.java -> Converter { body -> JSONObject(body.use { it.string() }) }
            JSONArray::class.java -> Converter { body -> JSONArray(body.use { it.string() }) }
            else -> null
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? {
        if (type == String::class.java) {
            return Converter<String, RequestBody> { value ->
                with(RequestBody) { value.toRequestBody(jsonMediaType) }
            }
        }
        return null
    }
}

/**
 * High-level Retrofit service class for Binance V5 / USDⓈ-M Futures real-time market data
 * and authenticated order execution using `BuildConfig.BINANCE_API_KEY` / `BuildConfig.BINANCE_API_SECRET`
 * (or `SecretVault` overrides) and HMAC-SHA256 request signing.
 */
class BinanceV5RetrofitModule(
    private val secretVault: SecretVault = SecretVault(),
    private val binanceAdapter: BinanceAdapter = BinanceAdapter(secretVault),
    private val okHttpClient: OkHttpClient = BinanceAdapter.defaultOkHttpClient(),
    private val baseUrlOverride: String? = null
) {
    private val apiByBaseUrl = LinkedHashMap<String, BinanceV5ApiService>()

    /**
     * Resolves the active Binance API Key from `BuildConfig.BINANCE_API_KEY` (injected from `.env`)
     * or `SecretVault`.
     */
    fun resolveApiKey(): String {
        val fromEnv = SecretVault.sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_KEY }.getOrNull())
        return fromEnv ?: secretVault.getBinanceApiKey()
    }

    /**
     * Resolves the active Binance API Secret from `BuildConfig.BINANCE_API_SECRET` (injected from `.env`)
     * or `SecretVault`.
     */
    fun resolveApiSecret(): String {
        val fromEnv = SecretVault.sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_SECRET }.getOrNull())
        return fromEnv ?: secretVault.getBinanceApiSecret()
    }

    @Synchronized
    fun apiService(mode: EnvironmentMode = EnvironmentMode.TEST): BinanceV5ApiService {
        val rawBase = baseUrlOverride ?: if (mode == EnvironmentMode.LIVE) {
            BinanceAdapter.MAINNET_REST_BASE_URL
        } else {
            BinanceAdapter.TESTNET_REST_BASE_URL
        }
        val normalizedBase = if (rawBase.endsWith("/")) rawBase else "$rawBase/"
        return apiByBaseUrl.getOrPut(normalizedBase) {
            Retrofit.Builder()
                .baseUrl(normalizedBase)
                .client(okHttpClient)
                .addConverterFactory(OrgJsonConverterFactory)
                .build()
                .create(BinanceV5ApiService::class.java)
        }
    }

    suspend fun fetchRealtimeKlines(
        symbol: String,
        interval: String = "15m",
        limit: Int = 100,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<List<CandleBar>> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService(mode).getKlines(
                symbol = symbol,
                interval = interval,
                limit = limit.coerceIn(5, 500)
            )
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = if (resp.code() == 429) BinanceErrorCategory.RATE_LIMIT_429 else BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = resp.code(),
                    binanceCode = parseErrorCode(errBody),
                    rawServerMessage = errBody,
                    userMessageVi = "Lỗi tải nến Retrofit ($symbol $interval): HTTP ${resp.code()}"
                )
            }
            val arr = resp.body() ?: JSONArray()
            val bars = BinanceAdapter.parseKlinesJsonArray(symbol, interval, arr)
            if (bars.isEmpty()) {
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.PARSE_OR_SCHEMA_ERROR,
                    httpCode = 422,
                    binanceCode = null,
                    rawServerMessage = "Empty klines response",
                    userMessageVi = "Danh sách nến trống cho $symbol ($interval)."
                )
            }
            BinanceApiResult.Success(bars, httpCode = resp.code())
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi lấy nến thời gian thực: ${e.message}"
            )
        }
    }

    suspend fun fetchRealtimeMarketPulse(
        symbol: String,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<MarketPulseDto> = withContext(Dispatchers.IO) {
        try {
            val api = apiService(mode)
            val premResp = api.getPremiumIndex(symbol)
            if (!premResp.isSuccessful) {
                val err = premResp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = premResp.code(),
                    binanceCode = parseErrorCode(err),
                    rawServerMessage = err,
                    userMessageVi = "Lỗi premiumIndex ($symbol): HTTP ${premResp.code()}"
                )
            }
            val bookResp = api.getBookTicker(symbol)
            if (!bookResp.isSuccessful) {
                val err = bookResp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = bookResp.code(),
                    binanceCode = parseErrorCode(err),
                    rawServerMessage = err,
                    userMessageVi = "Lỗi bookTicker ($symbol): HTTP ${bookResp.code()}"
                )
            }

            val prem = premResp.body() ?: JSONObject()
            val book = bookResp.body() ?: JSONObject()
            val markPrice = prem.optString("markPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val indexPrice = prem.optString("indexPrice", "0").toBigDecimalOrNull() ?: markPrice
            val fundingRate = prem.optString("lastFundingRate", "0.0001").toBigDecimalOrNull() ?: BigDecimal("0.0001")
            val nextFundingTimeMs = prem.optLong("nextFundingTime", System.currentTimeMillis() + 14_400_000L)
            val bidPrice = book.optString("bidPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val bidQty = book.optString("bidQty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val askPrice = book.optString("askPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val askQty = book.optString("askQty", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO

            if (markPrice <= BigDecimal.ZERO || bidPrice <= BigDecimal.ZERO || askPrice <= BigDecimal.ZERO || askPrice < bidPrice) {
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.PARSE_OR_SCHEMA_ERROR,
                    httpCode = 422,
                    binanceCode = null,
                    rawServerMessage = "Invalid or zero mark/bid/ask price",
                    userMessageVi = "Dữ liệu giá thời gian thực không hợp lệ (markPrice/bid/ask <= 0)."
                )
            }

            BinanceApiResult.Success(
                MarketPulseDto(
                    symbol = symbol,
                    markPrice = markPrice,
                    indexPrice = indexPrice,
                    lastFundingRate = fundingRate,
                    nextFundingTimeMs = nextFundingTimeMs,
                    bestBidPrice = bidPrice,
                    bestBidQty = bidQty,
                    bestAskPrice = askPrice,
                    bestAskQty = askQty,
                    eventTimeMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi lấy MarketPulse ($symbol): ${e.message}"
            )
        }
    }

    suspend fun fetchAuthenticatedAccount(
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        val apiSecret = resolveApiSecret()
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return@withContext BinanceApiResult.Failure(
                category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                httpCode = 401,
                binanceCode = -2014,
                rawServerMessage = "Missing BINANCE_API_KEY or BINANCE_API_SECRET in environment / SecretVault",
                userMessageVi = "Thiếu BINANCE_API_KEY hoặc BINANCE_API_SECRET trong biến môi trường hoặc SecretVault."
            )
        }

        val params = buildSignedParams(emptyMap(), apiSecret)
        try {
            val resp = apiService(mode).getAccountInfo(apiKey = apiKey, signedParams = params)
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                    httpCode = resp.code(),
                    binanceCode = parseErrorCode(errBody),
                    rawServerMessage = errBody,
                    userMessageVi = "Lỗi xác thực tài khoản (/fapi/v2/account): HTTP ${resp.code()}"
                )
            }
            BinanceApiResult.Success(resp.body() ?: JSONObject(), httpCode = resp.code())
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi truy vấn /fapi/v2/account: ${e.message}"
            )
        }
    }

    suspend fun executeOrderPlacement(
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
        newClientOrderId: String,
        executionCapability: ExecutionCapability = binanceAdapter.activeExecutionCapability
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        binanceAdapter.activeExecutionCapability = executionCapability
        binanceAdapter.verifyOrderMutationCapability(
            mode = mode,
            isRiskReducingCloseOrCancel = reduceOnly || closePosition
        )?.let { blocked ->
            return@withContext blocked
        }

        val apiKey = resolveApiKey()
        val apiSecret = resolveApiSecret()
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return@withContext BinanceApiResult.Failure(
                category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                httpCode = 401,
                binanceCode = -2014,
                rawServerMessage = "Missing BINANCE_API_KEY or BINANCE_API_SECRET",
                userMessageVi = "Thiếu API Key hoặc Secret trong biến môi trường (.env) hoặc SecretVault."
            )
        }

        val rawParams = linkedMapOf<String, String>()
        rawParams["symbol"] = symbol
        rawParams["side"] = side
        rawParams["type"] = type
        if (quantity != null && !closePosition) {
            rawParams["quantity"] = quantity.stripTrailingZeros().toPlainString()
        }
        if (price != null && type in setOf("LIMIT", "STOP", "TAKE_PROFIT")) {
            rawParams["price"] = price.stripTrailingZeros().toPlainString()
            rawParams["timeInForce"] = "GTC"
        }
        if (stopPrice != null && stopPrice > BigDecimal.ZERO) {
            rawParams["stopPrice"] = stopPrice.stripTrailingZeros().toPlainString()
        }
        if (reduceOnly && !closePosition) {
            rawParams["reduceOnly"] = "true"
        }
        if (closePosition) {
            rawParams["closePosition"] = "true"
        }
        rawParams["workingType"] = workingType
        rawParams["newClientOrderId"] = newClientOrderId
        rawParams["newOrderRespType"] = "RESULT"

        val signedParams = buildSignedParams(rawParams, apiSecret)

        try {
            val resp = apiService(mode).placeFuturesOrder(
                apiKey = apiKey,
                signedParams = signedParams
            )
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string().orEmpty()
                val bCode = parseErrorCode(errBody)
                val isUnknown = resp.code() >= 500
                return@withContext BinanceApiResult.Failure(
                    category = if (isUnknown) BinanceErrorCategory.UNKNOWN_EXECUTION_TIMEOUT else BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = resp.code(),
                    binanceCode = bCode,
                    rawServerMessage = errBody,
                    userMessageVi = "Đặt lệnh thất bại (HTTP ${resp.code()}, Code=${bCode ?: "N/A"}): $errBody",
                    isExecutionStateUnknown = isUnknown
                )
            }
            val body = resp.body() ?: JSONObject()
            BinanceApiResult.Success(
                BinanceAdapter.parseOrderJson(body, isAlgo = false),
                httpCode = resp.code()
            )
        } catch (e: Exception) {
            val isTimeout = e is SocketTimeoutException || e is IOException
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.UNKNOWN_EXECUTION_TIMEOUT,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Timeout",
                userMessageVi = "Timeout khi gửi lệnh ($newClientOrderId) -> Trạng thái UNKNOWN, bắt buộc truy vấn đối soát!",
                isExecutionStateUnknown = isTimeout
            )
        }
    }

    fun buildSignedParams(
        unsignedParams: Map<String, String>,
        apiSecret: String,
        recvWindowMs: Long = 5000L
    ): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>(unsignedParams)
        params["recvWindow"] = recvWindowMs.toString()
        params["timestamp"] = binanceAdapter.currentAdjustedTimestampMs().toString()
        val queryString = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        params["signature"] = computeHmacSha256Hex(queryString, apiSecret)
        return params
    }

    private fun parseErrorCode(body: String): Int? {
        return try {
            if (body.trim().startsWith("{")) {
                val o = JSONObject(body)
                if (o.has("code")) o.optInt("code") else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun computeHmacSha256Hex(queryString: String, secretKey: String): String {
            return BinanceAdapter.signHmacSha256(queryString, secretKey)
        }

        fun createService(
            apiKeyProvider: () -> String = {
                SecretVault.sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_KEY }.getOrNull()) ?: ""
            },
            secretKeyProvider: () -> String = {
                SecretVault.sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_SECRET }.getOrNull()) ?: ""
            },
            isTestnetProvider: () -> Boolean = { true },
            okHttpClient: OkHttpClient = BinanceAdapter.defaultOkHttpClient()
        ): BinanceV5ApiService {
            val baseUrl = if (isTestnetProvider()) {
                "${BinanceAdapter.TESTNET_REST_BASE_URL}/"
            } else {
                "${BinanceAdapter.MAINNET_REST_BASE_URL}/"
            }
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(OrgJsonConverterFactory)
                .build()
                .create(BinanceV5ApiService::class.java)
        }
    }
}
