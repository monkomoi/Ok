package com.example.network

import com.example.BuildConfig
import com.example.domain.CandleBar
import com.example.domain.EnvironmentMode
import com.example.domain.ExecutionCapability
import com.example.domain.SymbolFilter
import com.example.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class BinanceEnvironmentCredentialStatus(
    val hasEnvApiKey: Boolean,
    val hasEnvApiSecret: Boolean,
    val hasVaultOverride: Boolean,
    val activeSourceLabel: String,
    val maskedApiKeyPreview: String
)

/**
 * Repository connecting to the Binance USDⓈ-M Futures API via [BinanceV5RetrofitModule]
 * using the API keys defined in the environment variables (`BuildConfig.BINANCE_API_KEY`
 * and `BuildConfig.BINANCE_API_SECRET`) with fallback to [SecretVault].
 */
class BinanceFuturesRepository(
    private val secretVault: SecretVault = SecretVault(),
    private val retrofitModule: BinanceV5RetrofitModule = BinanceV5RetrofitModule(secretVault)
) {
    private val _lastServerSyncOffsetMs = MutableStateFlow(0L)
    val lastServerSyncOffsetMs: StateFlow<Long> = _lastServerSyncOffsetMs.asStateFlow()

    /**
     * Inspects whether `BuildConfig.BINANCE_API_KEY` and `BuildConfig.BINANCE_API_SECRET`
     * are populated from `.env` / AI Studio Secrets panel or overridden in [SecretVault].
     */
    fun getCredentialStatus(): BinanceEnvironmentCredentialStatus {
        val envKey = SecretVault.sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_KEY }.getOrNull())
        val envSecret = SecretVault.sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_SECRET }.getOrNull())
        val vaultKey = secretVault.getSecret(SecretVault.KEY_BINANCE_API_KEY)
        val vaultSecret = secretVault.getSecret(SecretVault.KEY_BINANCE_API_SECRET)

        val hasEnvKey = !envKey.isNullOrBlank()
        val hasEnvSecret = !envSecret.isNullOrBlank()
        val hasVault = !vaultKey.isNullOrBlank() && !vaultSecret.isNullOrBlank()

        val activeKey = envKey ?: vaultKey
        val sourceLabel = when {
            hasEnvKey && hasEnvSecret -> "BUILD_CONFIG_ENV (.env / AI Studio Secrets)"
            hasVault -> "ANDROID_KEYSTORE_VAULT"
            else -> "UNCONFIGURED"
        }

        return BinanceEnvironmentCredentialStatus(
            hasEnvApiKey = hasEnvKey,
            hasEnvApiSecret = hasEnvSecret,
            hasVaultOverride = hasVault,
            activeSourceLabel = sourceLabel,
            maskedApiKeyPreview = secretVault.maskSecretPreview(activeKey)
        )
    }

    /**
     * Synchronizes local timestamp offset with Binance USDⓈ-M `/fapi/v1/time`.
     */
    suspend fun syncServerTime(
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<Long> = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val resp = retrofitModule.apiService(mode).getServerTime()
            val t1 = System.currentTimeMillis()
            if (!resp.isSuccessful) {
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = resp.code(),
                    binanceCode = null,
                    rawServerMessage = resp.errorBody()?.string().orEmpty(),
                    userMessageVi = "Không thể đồng bộ giờ máy chủ Binance (HTTP ${resp.code()})"
                )
            }
            val serverTime = resp.body()?.optLong("serverTime", 0L) ?: 0L
            val midpoint = (t0 + t1) / 2L
            val offset = serverTime - midpoint
            _lastServerSyncOffsetMs.value = offset
            BinanceApiResult.Success(serverTime, httpCode = resp.code(), serverTimeMs = serverTime)
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi đồng bộ giờ Binance: ${e.message}"
            )
        }
    }

    /**
     * Fetches real-time OHLCV klines from `/fapi/v1/klines`.
     */
    suspend fun getKlines(
        symbol: String,
        interval: String = "15m",
        limit: Int = 100,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<List<CandleBar>> {
        return retrofitModule.fetchRealtimeKlines(
            symbol = symbol.uppercase(),
            interval = interval,
            limit = limit,
            mode = mode
        )
    }

    /**
     * Fetches real-time mark price, funding rate, and top-of-book bid/ask spread.
     */
    suspend fun getMarketPulse(
        symbol: String,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<MarketPulseDto> {
        return retrofitModule.fetchRealtimeMarketPulse(
            symbol = symbol.uppercase(),
            mode = mode
        )
    }

    /**
     * Fetches authenticated USDⓈ-M Futures account information (`/fapi/v2/account`)
     * signed with `BuildConfig.BINANCE_API_KEY` and `BuildConfig.BINANCE_API_SECRET`.
     */
    suspend fun getAuthenticatedAccount(
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<JSONObject> {
        return retrofitModule.fetchAuthenticatedAccount(mode = mode)
    }

    /**
     * Fetches authenticated open position risk (`/fapi/v2/positionRisk`) for [symbol] (or all symbols if null).
     */
    suspend fun getPositionRisk(
        symbol: String? = null,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<JSONArray> = withContext(Dispatchers.IO) {
        val apiKey = retrofitModule.resolveApiKey()
        val apiSecret = retrofitModule.resolveApiSecret()
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return@withContext BinanceApiResult.Failure(
                category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                httpCode = 401,
                binanceCode = -2014,
                rawServerMessage = "Missing BINANCE_API_KEY or BINANCE_API_SECRET",
                userMessageVi = "Thiếu khóa API Binance trong biến môi trường (.env) hoặc SecretVault."
            )
        }

        val unsigned = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) {
            unsigned["symbol"] = symbol.uppercase()
        }
        val signedParams = retrofitModule.buildSignedParams(unsigned, apiSecret)

        try {
            val resp = retrofitModule.apiService(mode).getPositionRisk(
                apiKey = apiKey,
                signedParams = signedParams
            )
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = resp.code(),
                    binanceCode = null,
                    rawServerMessage = errBody,
                    userMessageVi = "Lỗi truy vấn vị thế (/fapi/v2/positionRisk): HTTP ${resp.code()}"
                )
            }
            BinanceApiResult.Success(resp.body() ?: JSONArray(), httpCode = resp.code())
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi truy vấn vị thế: ${e.message}"
            )
        }
    }

    /**
     * Configures Isolated margin and capped leverage (`1x..5x`) on `/fapi/v1/marginType` and `/fapi/v1/leverage`.
     */
    suspend fun configureIsolatedLeverage(
        symbol: String,
        leverage: Int,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val apiKey = retrofitModule.resolveApiKey()
        val apiSecret = retrofitModule.resolveApiSecret()
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return@withContext BinanceApiResult.Failure(
                category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                httpCode = 401,
                binanceCode = -2014,
                rawServerMessage = "Missing BINANCE_API_KEY or BINANCE_API_SECRET",
                userMessageVi = "Thiếu khóa API Binance trong biến môi trường (.env) hoặc SecretVault."
            )
        }

        val normSymbol = symbol.uppercase()
        val safeLeverage = leverage.coerceIn(1, 5)
        try {
            val api = retrofitModule.apiService(mode)
            val marginParams = retrofitModule.buildSignedParams(
                mapOf("symbol" to normSymbol, "marginType" to "ISOLATED"),
                apiSecret
            )
            // Ignore -4046 ("No need to change margin type") when already ISOLATED
            api.changeMarginType(apiKey = apiKey, signedParams = marginParams)

            val levParams = retrofitModule.buildSignedParams(
                mapOf("symbol" to normSymbol, "leverage" to safeLeverage.toString()),
                apiSecret
            )
            val levResp = api.changeInitialLeverage(apiKey = apiKey, signedParams = levParams)
            if (!levResp.isSuccessful) {
                val errBody = levResp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = levResp.code(),
                    binanceCode = null,
                    rawServerMessage = errBody,
                    userMessageVi = "Lỗi cài đặt đòn bẩy ($normSymbol ${safeLeverage}x): HTTP ${levResp.code()}"
                )
            }
            BinanceApiResult.Success(levResp.body() ?: JSONObject(), httpCode = levResp.code())
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi cài đặt đòn bẩy: ${e.message}"
            )
        }
    }

    /**
     * Places a signed USDⓈ-M Futures order via [BinanceV5RetrofitModule.executeOrderPlacement].
     */
    suspend fun placeOrder(
        mode: EnvironmentMode = EnvironmentMode.TEST,
        symbol: String,
        side: String,
        type: String,
        quantity: BigDecimal?,
        price: BigDecimal? = null,
        stopPrice: BigDecimal? = null,
        reduceOnly: Boolean = false,
        closePosition: Boolean = false,
        newClientOrderId: String,
        executionCapability: ExecutionCapability = ExecutionCapability()
    ): BinanceApiResult<ExchangeOrderRecord> {
        return retrofitModule.executeOrderPlacement(
            mode = mode,
            symbol = symbol.uppercase(),
            side = side.uppercase(),
            type = type.uppercase(),
            quantity = quantity,
            price = price,
            stopPrice = stopPrice,
            reduceOnly = reduceOnly,
            closePosition = closePosition,
            newClientOrderId = newClientOrderId,
            executionCapability = executionCapability
        )
    }

    /**
     * Queries an existing order by `origClientOrderId` on `/fapi/v1/order`.
     */
    suspend fun queryOrderByClientOrderId(
        symbol: String,
        origClientOrderId: String,
        mode: EnvironmentMode = EnvironmentMode.TEST
    ): BinanceApiResult<ExchangeOrderRecord> = withContext(Dispatchers.IO) {
        val apiKey = retrofitModule.resolveApiKey()
        val apiSecret = retrofitModule.resolveApiSecret()
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return@withContext BinanceApiResult.Failure(
                category = BinanceErrorCategory.AUTH_INVALID_KEY_OR_IP,
                httpCode = 401,
                binanceCode = -2014,
                rawServerMessage = "Missing BINANCE_API_KEY or BINANCE_API_SECRET",
                userMessageVi = "Thiếu khóa API Binance."
            )
        }

        val signed = retrofitModule.buildSignedParams(
            mapOf("symbol" to symbol.uppercase(), "origClientOrderId" to origClientOrderId),
            apiSecret
        )
        try {
            val resp = retrofitModule.apiService(mode).queryFuturesOrder(apiKey, signed)
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string().orEmpty()
                return@withContext BinanceApiResult.Failure(
                    category = BinanceErrorCategory.OTHER_EXCHANGE_ERROR,
                    httpCode = resp.code(),
                    binanceCode = null,
                    rawServerMessage = errBody,
                    userMessageVi = "Lỗi truy vấn lệnh ($origClientOrderId): HTTP ${resp.code()}"
                )
            }
            val body = resp.body() ?: JSONObject()
            BinanceApiResult.Success(
                BinanceAdapter.parseOrderJson(body, isAlgo = false),
                httpCode = resp.code()
            )
        } catch (e: Exception) {
            BinanceApiResult.Failure(
                category = BinanceErrorCategory.NETWORK_UNREACHABLE,
                httpCode = 0,
                binanceCode = null,
                rawServerMessage = e.message ?: "Network error",
                userMessageVi = "Lỗi mạng khi truy vấn lệnh: ${e.message}"
            )
        }
    }
}
