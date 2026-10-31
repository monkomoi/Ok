package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiIntelligenceGateway
import com.example.data.local.AppSettingsEntity
import com.example.data.local.AstraDatabase
import com.example.data.local.FillEntity
import com.example.data.local.OrderIntentEntity
import com.example.domain.AccountCapitalState
import com.example.domain.AccountVerificationState
import com.example.domain.AiModuleStatus
import com.example.domain.BotState
import com.example.domain.DataSourceType
import com.example.domain.DeviceHealthSnapshot
import com.example.domain.FeatureSnapshot
import com.example.domain.LiveActivationChecklist
import com.example.domain.RegimeLabel
import com.example.engine.AstraTradingCoordinator
import com.example.network.BinanceAdapter
import com.example.network.ClockSyncStatus
import com.example.security.SecretVault
import com.example.telegram.TelegramBotController
import com.example.ui.screens.OverviewTabScreen
import com.example.ui.screens.StrategyRiskTabScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AstraDatabase
    private lateinit var vault: SecretVault

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = SecretVault(context)
        vault.clearAllBinanceCredentials()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------------------
    // Mục 2 & Mục 6 - Test 1b: SecretVault round-trip decryption & SHA-256 integrity
    // ---------------------------------------------------------------------------
    @Test
    fun `SecretVault round-trip encryption preserves exact API Key case and verifies SHA-256`() {
        val originalKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz12"
        val savedOk = vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, "  $originalKey\r\n ")
        assertTrue(savedOk)

        val decrypted = vault.getSecret(SecretVault.SLOT_BINANCE_API_KEY)
        assertEquals(originalKey, decrypted)

        val integrityOk = vault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY)
        assertTrue(integrityOk)
        assertEquals(originalKey.length, vault.getSlotLength(SecretVault.SLOT_BINANCE_API_KEY))
    }

    // ---------------------------------------------------------------------------
    // Mục 6 - Test 6, 7, 9, 10, 11: Reconciliation failure, No fake equity, No duplicate order after timeout, No fake position
    // ---------------------------------------------------------------------------
    @Test
    fun `Tests 06 07 09 10 11 - Reconciliation failure keeps equity null and blocks duplicate orders and unconfirmed fills`() = runBlocking {
        val dao = database.astraDao()
        dao.upsertSettings(AppSettingsEntity())

        val binanceAdapter = BinanceAdapter(vault)
        val aiGateway = AiIntelligenceGateway(vault, BinanceAdapter.defaultOkHttpClient())
        val tgController = TelegramBotController(vault, dao, BinanceAdapter.defaultOkHttpClient())
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = binanceAdapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        // Test 6 & 7: Without valid Binance credentials, reconciliation fails and exchangeEquityUsdt remains null (never 5.8939)
        val reconMsg = coordinator.runFullReconciliation("DOGEUSDT")
        assertTrue(reconMsg.contains("CHƯA ĐỐI SOÁT"))
        val capState = coordinator.accountCapitalState.value
        assertNull("exchangeEquityUsdt must stay null on reconciliation failure", capState.exchangeEquityUsdt)
        assertNull("exchangeAvailableBalanceUsdt must stay null on reconciliation failure", capState.exchangeAvailableBalanceUsdt)
        assertEquals(AccountVerificationState.UNCONFIGURED, capState.verificationState)
        assertEquals(BotState.CONFIG_REQUIRED, coordinator.botState.value)

        // Verify DailySnapshotEntity in Room stores "UNVERIFIED", never "5.8939"
        val latestSnapshot = dao.getLatestDailySnapshotOnce()
        assertTrue(latestSnapshot != null)
        assertEquals("UNVERIFIED", latestSnapshot?.currentEquityUsdt)
        assertEquals("UNVERIFIED", latestSnapshot?.availableBalanceUsdt)

        // Test 9: LIVE mode checklist is locked when reconciliation has not succeeded
        assertFalse(coordinator.checklist.value.canEnableLive)
        assertTrue(coordinator.safetyDebugLockActive.value)

        // Test 10: Simulate an OrderIntent in UNKNOWN state (after network timeout) -> blocks new order execution (INV-06)
        dao.upsertOrderIntent(
            OrderIntentEntity(
                intentId = "intent-timeout-1",
                clientOrderId = "ASTRA_ENTRY_DOGEUSDT_TIMEOUT1",
                symbol = "DOGEUSDT",
                side = "BUY",
                positionDirection = "LONG",
                orderType = "MARKET",
                quantity = "40",
                intendedEntryPrice = "0.14000",
                intendedStopPrice = "0.13700",
                intendedTargetPrice = "0.14700",
                intendedRiskUsdt = "0.1200",
                environmentMode = "TEST",
                status = "UNKNOWN",
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
        )

        val execBlockedMsg = coordinator.executeApprovedCandidateIfEligible()
        assertTrue(
            execBlockedMsg.contains("TỪ CHỐI") || execBlockedMsg.contains("UNVERIFIED") || execBlockedMsg.contains("INV-06")
        )

        // Test 11: Verify no PositionEntity is ever created when no fill is confirmed
        val activePositions = dao.getActivePositionsOnce()
        assertTrue("No position may exist without a confirmed fill", activePositions.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Mục 6 - Test 12: UI không tràn chữ hoặc chồng lấn ở các thành phần đã sửa (360dp S7 Edge width)
    // ---------------------------------------------------------------------------
    @Test
    fun `Test 12 - Compact 360dp UI renders UNVERIFIED equity, V1_BASELINE, and Pause Drain Resume cleanly`() {
        val sampleFeatures = FeatureSnapshot(
            symbol = "DOGEUSDT",
            asOfUtcMs = System.currentTimeMillis(),
            dataSource = DataSourceType.DEMO_SIMULATED,
            lastPrice = BigDecimal("0.14250"),
            markPrice = BigDecimal("0.14250"),
            indexPrice = BigDecimal("0.14245"),
            return1m = BigDecimal("0.001"),
            return5m = BigDecimal("0.003"),
            return15m = BigDecimal("0.008"),
            return1h = BigDecimal("0.015"),
            ema20_15m = BigDecimal("0.14180"),
            ema50_15m = BigDecimal("0.14090"),
            emaSlope15m = BigDecimal("0.002"),
            ema20_1h = BigDecimal("0.14120"),
            ema50_1h = BigDecimal("0.13980"),
            atr14 = BigDecimal("0.00210"),
            atrPct = BigDecimal("0.014"),
            relativeVolume = BigDecimal("1.85"),
            closedRangeHigh20 = BigDecimal("0.14190"),
            closedRangeLow20 = BigDecimal("0.13800"),
            breakoutUp = true,
            breakoutDown = false,
            spreadPct = BigDecimal("0.00025"),
            topBookBidQty = BigDecimal("50000"),
            topBookAskQty = BigDecimal("48000"),
            fundingRate = BigDecimal("0.0001"),
            nextFundingTimeMs = System.currentTimeMillis() + 3_600_000L,
            dataAgeMs = 1000L,
            isDataStale = false,
            isEstimated = true,
            regimes = setOf(RegimeLabel.BREAKOUT_CANDIDATE),
            primaryRegime = RegimeLabel.BREAKOUT_CANDIDATE
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                OverviewTabScreen(
                    settings = AppSettingsEntity(),
                    botState = BotState.CONFIG_REQUIRED,
                    safetyDebugLockActive = true,
                    accountCapitalState = AccountCapitalState(),
                    aiModuleStatus = AiModuleStatus.UNCONFIGURED,
                    dailySnapshot = null,
                    activePositions = emptyList(),
                    featureSnapshot = sampleFeatures,
                    evEstimate = null,
                    skepticResult = null,
                    sizingDecision = null,
                    deviceHealth = DeviceHealthSnapshot(
                        batteryPct = 85,
                        batteryTempCelsius = 31.2f,
                        isCharging = true,
                        availableRamMb = 1420L,
                        kernelTotalRamMb = 3580L,
                        physicalNominalRamGb = 4,
                        memInfoSource = "ActivityManager.MemoryInfo"
                    ),
                    clockStatus = ClockSyncStatus(),
                    liveChecklist = LiveActivationChecklist(),
                    selectedSymbol = "DOGEUSDT",
                    isBusy = false,
                    onSelectSymbol = {},
                    onScanAndEvaluate = {},
                    onReconcile = {},
                    onExecuteApprovedCandidate = {},
                    onPause = {},
                    onDrain = {},
                    onResume = {},
                    onPanicCloseConfirmed = {},
                    onSwitchEnvironment = { _, _ -> },
                    onToggleForegroundService = {}
                )
            }
        }

        // Verify UNVERIFIED / UNKNOWN is shown for Exchange Equity & Available Balance (not 5.8939 USDT as equity)
        composeTestRule.onNodeWithTag("equity_usdt_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("account_verification_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("safety_lock_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pause_bot_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("drain_bot_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("resume_bot_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Test 12b - StrategyRiskTabScreen renders V1_BASELINE badge horizontally without vertical character wrapping`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                StrategyRiskTabScreen(
                    settings = AppSettingsEntity(),
                    featureSnapshot = null,
                    evEstimate = null,
                    recentSignals = emptyList(),
                    onSaveRiskSettings = { _, _, _, _, _, _, _ -> }
                )
            }
        }

        val badgeBounds = composeTestRule.onNodeWithTag("strategy_baseline_badge", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        // A horizontal pill badge for "V1_BASELINE" must be wider than it is tall (never wrapped into a vertical column)
        assertTrue(
            "Expected V1_BASELINE badge width (${badgeBounds.width}) > height (${badgeBounds.height})",
            badgeBounds.width > badgeBounds.height
        )
    }

    @Test
    fun `verify Room Fill deduplication and Telegram security guards`() = runBlocking {
        val appName = context.getString(R.string.app_name)
        assertEquals("Astra Trader", appName)

        val dao = database.astraDao()
        val fill = FillEntity(
            tradeId = "TRD_9991",
            clientOrderId = "ASTRA_ENTRY_DOGEUSDT_1",
            symbol = "DOGEUSDT",
            side = "BUY",
            price = "0.14250",
            quantity = "50",
            realizedPnlUsdt = "0.1200",
            commissionUsdt = "0.0035",
            commissionAsset = "USDT",
            reasonVi = "Test fill deduplication",
            executedAtMs = 1_760_000_000_000L
        )
        val firstInsert = dao.insertFillIgnoreDuplicate(fill)
        val secondInsert = dao.insertFillIgnoreDuplicate(fill)
        assertTrue(firstInsert > 0L)
        assertEquals(-1L, secondInsert)

        val tgController = TelegramBotController(vault, dao, BinanceAdapter.defaultOkHttpClient())
        val secretOutcome = tgController.processIncomingUpdate(
            updateId = 1003L,
            fromUserId = "owner_1",
            chatId = "chat_1",
            text = "/setkey api_secret=my_secret_123",
            whitelistedUserId = "owner_1",
            whitelistedChatId = "chat_1",
            onAuthorizedCommand = { "SHOULD_NOT_RUN" }
        )
        assertFalse(secretOutcome.accepted)
        assertEquals("SECRET_REJECTED", secretOutcome.command)
    }

    @Test
    fun `verify MOD-02 17-table Room persistence for MarketFeatureEntity and AiRequestEntity`() = runBlocking {
        val dao = database.astraDao()
        val featureEntity = com.example.data.local.MarketFeatureEntity(
            snapshotHash = "HASH_DOGE_1",
            symbol = "DOGEUSDT",
            asOfUtcMs = 1_760_000_000_000L,
            featureVersion = "v5.1-baseline-2",
            dataSource = "BINANCE_PUBLIC_REST",
            markPrice = "0.14250",
            return15m = "0.008",
            return1h = "0.015",
            atrPct = "0.014",
            relativeVolume = "1.65",
            spreadPct = "0.0002",
            microprice = "0.14251",
            orderBookImbalance = "0.12",
            primaryRegime = "BREAKOUT_CANDIDATE",
            isDataStale = false,
            isEstimated = false,
            createdAtMs = 1_760_000_000_000L
        )
        dao.insertMarketFeatureSnapshot(featureEntity)

        val aiReqEntity = com.example.data.local.AiRequestEntity(
            requestId = "REQ_AI_01",
            providerId = "gemini",
            modelId = "gemini-2.5-flash",
            purpose = "FAST_ANALYST",
            symbol = "DOGEUSDT",
            latencyMs = 420L,
            estimatedPromptTokens = 128,
            status = "SUCCESS",
            correlationId = "HASH_DOGE_1",
            requestedAtMs = 1_760_000_000_000L
        )
        dao.insertAiRequest(aiReqEntity)
        val recentAiRequests = dao.getRecentAiRequestsOnce()
        assertEquals(1, recentAiRequests.size)
        assertEquals("REQ_AI_01", recentAiRequests.first().requestId)
    }

    @Test
    fun `verify X-MBX-APIKEY header integrity and Outbound IP and Binance request diagnostics`(): Unit = runBlocking {
        val dao = database.astraDao()
        val defaultSettings = AppSettingsEntity()
        dao.upsertSettings(defaultSettings)

        val binanceAdapter = BinanceAdapter(vault)
        val aiGateway = AiIntelligenceGateway(vault, BinanceAdapter.defaultOkHttpClient())
        val tgController = TelegramBotController(vault, dao, BinanceAdapter.defaultOkHttpClient())
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = binanceAdapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        // 1. Default binanceEndpointTarget is MAINNET -> routes read-only verification to https://fapi.binance.com
        assertEquals("MAINNET", defaultSettings.binanceEndpointTarget)
        val resolvedMode = coordinator.resolveApiEndpointMode(defaultSettings)
        assertEquals(com.example.domain.EnvironmentMode.LIVE, resolvedMode)
        assertEquals("https://fapi.binance.com", binanceAdapter.restBaseUrl(resolvedMode))

        // 2. Save a valid 64-char test key & secret in SecretVault
        val sampleApiKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz12"
        val sampleApiSecret = "9876543210ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210ZyXwVuTsRqPoNmLkJi"
        assertTrue(vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, "  Bearer $sampleApiKey\r\n "))
        assertTrue(vault.putSecret(SecretVault.SLOT_BINANCE_API_SECRET, "  \"$sampleApiSecret\"\n "))

        // 3. Verify Step 1 Header & HMAC-SHA256 integrity without leaking plaintext key, secret, or signature
        val step1 = binanceAdapter.verifyHeaderAndSignatureIntegrity()
        assertTrue("Step 1 header & signature check must pass", step1.passed)
        assertFalse("Diagnostic output must never contain plaintext API Key", step1.detailVi.contains(sampleApiKey))
        assertFalse("Diagnostic output must never contain plaintext API Secret", step1.detailVi.contains(sampleApiSecret))
        assertTrue(step1.detailVi.contains("khớp 100% với header X-MBX-APIKEY"))
        assertTrue(step1.detailVi.contains("apiKeyLoadedFromVault=true, apiKeyAttachedToHeader=true"))

        // 4. Verify standalone Outbound IP diagnostics (Requirements 1-5 & 9)
        val ipDiag = coordinator.runOutboundIpDiagnostics()
        assertTrue(ipDiag.probedAtUtcMs > 0L)
        assertTrue(ipDiag.networkBindingLimitationNoteVi.contains("Giới hạn kỹ thuật"))
        assertTrue(ipDiag.observationDisclaimerVi.contains("KHÔNG khẳng định máy chủ Binance nhìn thấy cùng"))
        println("[REAL_DIAGNOSTIC_TELEMETRY] OutboundIP: ipv4=${ipDiag.outboundIpv4}, ipv4Status=${ipDiag.ipv4ProbeStatus}, ipv6=${ipDiag.outboundIpv6}, ipv6Status=${ipDiag.ipv6ProbeStatus}, dualStack=${ipDiag.outboundDefaultIp}, transport=${ipDiag.transportTypeVi}, sameAndroidNet=${ipDiag.sameAndroidNetworkBound}, connStatus=${ipDiag.connectionStatusVi}")

        // 5. Verify full Read-Only Binance Diagnostics & Request Inspections (Requirements 6-8)
        val fullDiag = coordinator.runReadOnlyBinanceDiagnostics()
        assertTrue(fullDiag.apiKeyLoadedFromVault)
        assertTrue(fullDiag.apiKeyAttachedToHeader)
        assertTrue("Request inspections must record actual Binance requests", fullDiag.requestInspections.isNotEmpty())
        fullDiag.requestInspections.forEach { req ->
            println("[REAL_DIAGNOSTIC_TELEMETRY] BinanceReq: [${req.environment}/${req.product}] ${req.httpMethod} https://${req.host}${req.endpointPath} -> http=${req.httpStatus}, code=${req.binanceErrorCode}, keyLoaded=${req.apiKeyLoadedFromVault}, headerAttached=${req.apiKeyAttachedToHeader}, msg=${req.rawServerMessage}")
            assertFalse("Request inspection must never leak API Key", req.rawServerMessage.contains(sampleApiKey))
            assertFalse("Request inspection must never leak API Secret", req.rawServerMessage.contains(sampleApiSecret))
        }

        // 6. Verify strict separation between SAVED_IN_VAULT_UNVERIFIED and AccountVerificationState.UNVERIFIED
        coordinator.onCredentialsUpdatedInVault()
        val capAfterSave = coordinator.accountCapitalState.value
        assertEquals(com.example.domain.VaultCredentialState.SAVED_IN_VAULT_UNVERIFIED, capAfterSave.vaultCredentialState)
        assertEquals(AccountVerificationState.UNVERIFIED, capAfterSave.verificationState)
        assertNull("Saving key in Vault must never fabricate verified equity", capAfterSave.exchangeEquityUsdt)

        // 7. Verify LIVE order submission remains strictly locked on Mainnet while safetyDebugLockActive is true or environmentMode is TEST
        val execBlocked = coordinator.executeApprovedCandidateIfEligible()
        assertTrue(execBlocked.contains("KHÓA AN TOÀN LIVE"))

        // 8. Verify ApiIntegrationTabScreen renders Outbound IP Diagnostics button & telemetry cleanly
        composeTestRule.setContent {
            MyApplicationTheme {
                com.example.ui.screens.ApiIntegrationTabScreen(
                    settings = defaultSettings,
                    secretVault = vault,
                    secretRefreshTick = 1,
                    providers = emptyList(),
                    aiModuleStatus = AiModuleStatus.UNCONFIGURED,
                    recentAiDecisions = emptyList(),
                    onSaveBinanceCredentials = { _, _, _ -> },
                    onClearBinanceCredentials = {},
                    onTestAndReconcileBinance = {},
                    onSaveAiKey = { _, _ -> },
                    onToggleProvider = { _, _ -> },
                    onTriggerAiReview = {},
                    onSaveTelegramConfig = { _, _, _ -> },
                    onSimulateTelegramCommand = { _, _, _ -> },
                    accountCapitalState = capAfterSave,
                    authDiagnosticReport = fullDiag,
                    outboundNetworkIdentity = ipDiag
                )
            }
        }
        composeTestRule.onNodeWithTag("run_outbound_ip_diagnostics_button").assertExists()
        composeTestRule.onNodeWithTag("outbound_network_identity_text").assertExists()
        composeTestRule.onNodeWithTag("network_binding_limitation_text").assertExists()
        composeTestRule.onNodeWithTag("ip_observation_disclaimer_text").assertExists()
        composeTestRule.onNodeWithTag("vault_boolean_status_text").assertExists()
        composeTestRule.onNodeWithTag("ai_api_checker_card").assertExists()
        composeTestRule.onNodeWithTag("ai_checker_list_models_button").assertExists()
        composeTestRule.onNodeWithTag("ai_checker_test_single_button").assertExists()
        composeTestRule.onNodeWithTag("ai_checker_test_all_button").assertExists()
        composeTestRule.onNodeWithTag("ai_checker_chat_section").assertExists()
        Unit
    }

    @Test
    fun `verify AiApiCheckerEngine presets, protocol auth headers, error explanations, and response parsing`() {
        val engine = com.example.ai.AiApiCheckerEngine(vault, BinanceAdapter.defaultOkHttpClient())

        // 1. Verify all 22 presets + custom from aiai_5b5f22f2.py are registered
        val expectedPresetKeys = listOf(
            "openai", "openrouter", "groq", "together", "fireworks", "deepseek",
            "mistral", "xai", "cerebras", "perplexity", "deepinfra", "dashscope",
            "siliconflow", "zai", "minimax", "cohere", "huggingface", "anthropic",
            "gemini", "ollama", "lmstudio", "vllm", "custom"
        )
        for (key in expectedPresetKeys) {
            assertTrue("Missing preset provider key: $key", com.example.ai.AiApiCheckerEngine.PROVIDERS.containsKey(key))
        }

        // 2. Verify auth headers for openai, anthropic, and gemini
        val openAiHeaders = com.example.ai.AiApiCheckerEngine.authHeaders("openai", "sk-test-123")
        assertEquals("Bearer sk-test-123", openAiHeaders["Authorization"])

        val anthropicHeaders = com.example.ai.AiApiCheckerEngine.authHeaders("anthropic", "sk-ant-123")
        assertEquals("sk-ant-123", anthropicHeaders["x-api-key"])
        assertEquals("2023-06-01", anthropicHeaders["anthropic-version"])

        val geminiHeaders = com.example.ai.AiApiCheckerEngine.authHeaders("gemini", "AIzaSyTest123")
        assertEquals("AIzaSyTest123", geminiHeaders["x-goog-api-key"])

        // 3. Verify HTTP error explanations
        assertTrue(com.example.ai.AiApiCheckerEngine.explainHttpError(401, "Unauthorized").contains("API key sai"))
        assertTrue(com.example.ai.AiApiCheckerEngine.explainHttpError(429, "insufficient_quota").contains("Hết quota"))
        assertTrue(com.example.ai.AiApiCheckerEngine.explainHttpError(429, "Too Many Requests").contains("rate limit"))

        // 4. Verify SecretVault storage per provider without leaking plaintext
        assertTrue(engine.saveApiKeyForProvider("openrouter", "  sk-or-v1-abcdef1234567890  "))
        assertEquals("sk-or-v1-abcdef1234567890", engine.resolveApiKey("openrouter", noAuth = false))
        assertEquals("", engine.resolveApiKey("openrouter", noAuth = true))
        val preview = engine.getMaskedKeyPreview("openrouter", noAuth = false)
        assertFalse(preview.contains("abcdef123456"))
    }

    // ---------------------------------------------------------------------------
    // Stage 4 Audit Test (P1-3.7, P1-3.8 & P1-3.10): Strict AI Key Isolation & SecretVault Fail-Closed Security
    // ---------------------------------------------------------------------------
    @Test
    fun `verify P1_3_7 strict per-provider API key isolation and P1_3_10 SecretVault fail-closed security`() {
        val engine = com.example.ai.AiApiCheckerEngine(vault, BinanceAdapter.defaultOkHttpClient())
        val aiGateway = AiIntelligenceGateway(vault, BinanceAdapter.defaultOkHttpClient())

        // 1. Save a Gemini key into SLOT_AI_PRIMARY_KEY
        vault.clearAllBinanceCredentials()
        vault.removeSecret(SecretVault.SLOT_AI_PRIMARY_KEY)
        vault.removeSecret(SecretVault.SLOT_AI_SKEPTIC_KEY)
        assertTrue(engine.saveApiKeyForProvider("gemini", "AIzaSyGeminiPrimarySecretKey123456789"))

        // 2. Verify that querying OpenRouter, Groq, or DeepSeek NEVER falls back to Gemini's SLOT_AI_PRIMARY_KEY
        assertEquals("", engine.resolveApiKey("openrouter", noAuth = false))
        assertEquals("", engine.resolveApiKey("groq", noAuth = false))
        assertEquals("", engine.resolveApiKey("deepseek", noAuth = false))

        val groqProvider = com.example.data.local.ProviderConfigEntity(
            providerId = "groq",
            displayName = "Groq Skeptic",
            role = "SKEPTIC",
            apiType = "OPENAI_COMPATIBLE",
            baseUrl = "https://api.groq.com/openai/v1",
            modelId = "llama-3.3-70b-versatile",
            secretSlot = SecretVault.SLOT_AI_SKEPTIC_KEY,
            enabled = true,
            healthStatus = "VERIFIED_HEALTHY"
        )
        assertNull("Groq provider must not read Gemini primary key", aiGateway.resolveIsolatedProviderKey(groqProvider))
        assertFalse(aiGateway.hasIsolatedProviderKey(groqProvider))

        // 3. Saving an OpenRouter key must NOT overwrite Gemini's SLOT_AI_PRIMARY_KEY
        assertTrue(engine.saveApiKeyForProvider("openrouter", "sk-or-v1-isolated-openrouter-key-999"))
        assertEquals("AIzaSyGeminiPrimarySecretKey123456789", vault.getSecret(SecretVault.SLOT_AI_PRIMARY_KEY))
        assertEquals("sk-or-v1-isolated-openrouter-key-999", engine.resolveApiKey("openrouter", noAuth = false))

        // 4. Verify P1-3.10: Simulated Keystore failure forces SecretVault to fail-closed (no plaintext SharedPreferences fallback)
        vault.setSimulateKeystoreFailureForTest(true)
        try {
            val writeUnderFailure = vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGh")
            assertFalse("SecretVault must fail-closed when Keystore is unavailable", writeUnderFailure)
            assertNull("SecretVault must return null when Keystore fails", vault.getSecret(SecretVault.SLOT_AI_PRIMARY_KEY))
            assertFalse(vault.getVaultSecurityStatus().isOperational)
        } finally {
            vault.setSimulateKeystoreFailureForTest(false)
        }
    }

    // ---------------------------------------------------------------------------
    // Stage 4 Audit Test (P0-3.2 & P0-3.3): 8-Stage Panic Close State Machine & 16-Condition Unified ExecutionGate
    // ---------------------------------------------------------------------------
    @Test
    fun `verify P0_3_2 Panic Close never deletes position on failure and P0_3_3 ExecutionGate blocks expired or slipped signals`() = runBlocking {
        val dao = database.astraDao()
        dao.upsertSettings(AppSettingsEntity())

        val binanceAdapter = BinanceAdapter(vault)
        val aiGateway = AiIntelligenceGateway(vault, BinanceAdapter.defaultOkHttpClient())
        val tgController = TelegramBotController(vault, dao, BinanceAdapter.defaultOkHttpClient())
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = binanceAdapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        // 1. Insert an active BOT_OWNED position in Room
        val openPos = com.example.data.local.PositionEntity(
            symbol = "DOGEUSDT",
            signedQuantity = "100",
            entryAvgPrice = "0.14000",
            markPrice = "0.14200",
            unrealizedPnlUsdt = "0.2000",
            isolatedMarginUsdt = "7.0000",
            leverage = 2,
            stopPrice = "0.13700",
            targetPrice = "0.14800",
            netBreakevenEstimate = "0.14020",
            protectiveSlOrderId = "SL_101",
            protectiveSlVerified = true,
            protectiveTpOrderId = "TP_101",
            protectiveTpVerified = true,
            positionSide = "BOTH",
            signalId = "SIG-DOGEUSDT-1",
            ownershipTag = "BOT_OWNED",
            state = "OPEN_PROTECTED",
            openedAtMs = 1_760_000_000_000L,
            updatedAtMs = 1_760_000_000_000L
        )
        dao.upsertPosition(openPos)

        // 2. Call Panic Close when API credentials are missing -> MUST fail-closed, keep position in DB, and insert 0 fills
        vault.clearAllBinanceCredentials()
        val panicReportNoKey = coordinator.executePanicCloseStateMachine("Test Panic Close without credentials")
        assertEquals(com.example.domain.PanicCloseStage.PANIC_FAILED_UNVERIFIED, panicReportNoKey.finalStage)
        assertFalse(panicReportNoKey.confirmedClosed)
        assertTrue(panicReportNoKey.summaryVi.contains("MISSING_API_CREDENTIALS"))

        val remainingPositions = dao.getActivePositionsOnce()
        assertEquals("Local position must NOT be deleted when exchange close did not occur", 1, remainingPositions.size)
        assertEquals("PANIC_CLOSE_FAILED", remainingPositions.first().state)
        assertTrue("No fake FillEntity may be recorded on failed Panic Close", dao.getAllFillsOnce().isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Master Remediation Test (P0-01, P0-02, P0-03, P0-04):
    // 8-Stage Panic Close State Machine: FETCH_FAILED, VERIFIED_ZERO, POST_CLOSE_VERIFICATION_FAILED,
    // CANCEL_PROTECTIVE_FAILED, and VERIFIED_NON_ZERO with /fapi/v1/userTrades accounting
    // ---------------------------------------------------------------------------
    @Test
    fun `verify P0_01 P0_02 P0_03 P0_04 Panic Close state machine enforces 3 pre-close states, mandatory post-close verification, verified cancel, and userTrades accounting`() = runBlocking {
        val dao = database.astraDao()
        dao.upsertSettings(AppSettingsEntity(binanceEndpointTarget = "TESTNET", environmentMode = "TEST"))

        val sampleApiKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz12"
        val sampleApiSecret = "9876543210ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210ZyXwVuTsRqPoNmLkJi"
        assertTrue(vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, sampleApiKey))
        assertTrue(vault.putSecret(SecretVault.SLOT_BINANCE_API_SECRET, sampleApiSecret))

        var scenario = "FETCH_FAILED"
        var accountCallCountInScenario = 0
        var orderSubmissionCount = 0

        val mockHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val method = req.method
                val jsonMedia = "application/json".toMediaType()

                fun buildResp(code: Int, body: String): okhttp3.Response {
                    return okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code in 200..299) "OK" else "Error")
                        .body(body.toResponseBody(jsonMedia))
                        .build()
                }

                when {
                    path.endsWith("/fapi/v1/time") -> buildResp(200, """{"serverTime":1760000000000}""")
                    path.endsWith("/fapi/v2/account") -> {
                        accountCallCountInScenario++
                        when (scenario) {
                            "FETCH_FAILED" -> buildResp(503, """{"code":-1001,"msg":"Service unavailable"}""")
                            "VERIFIED_ZERO" -> buildResp(
                                200,
                                """{"totalWalletBalance":"12.5000","totalMarginBalance":"12.5000","availableBalance":"12.5000","totalUnrealizedProfit":"0.0000","canTrade":true,"positions":[]}"""
                            )
                            "POST_CLOSE_FAIL" -> {
                                if (accountCallCountInScenario == 1) {
                                    buildResp(
                                        200,
                                        """{"totalWalletBalance":"12.3000","totalMarginBalance":"12.5000","availableBalance":"5.5000","totalUnrealizedProfit":"0.2000","canTrade":true,"positions":[{"symbol":"DOGEUSDT","positionAmt":"100","entryPrice":"0.14000","markPrice":"0.14200","unrealizedProfit":"0.2000","isolatedWallet":"7.0000","leverage":"2","isolated":true,"positionSide":"BOTH"}]}"""
                                    )
                                } else {
                                    buildResp(503, """{"code":-1001,"msg":"Post-close verification timeout"}""")
                                }
                            }
                            else -> {
                                // CANCEL_FAIL and FULL_SUCCESS: 1st call has positionAmt=100, 2nd call has positionAmt=0
                                if (accountCallCountInScenario == 1) {
                                    buildResp(
                                        200,
                                        """{"totalWalletBalance":"12.0000","totalMarginBalance":"12.5000","availableBalance":"5.5000","totalUnrealizedProfit":"0.5000","canTrade":true,"positions":[{"symbol":"DOGEUSDT","positionAmt":"100","entryPrice":"0.14000","markPrice":"0.14500","unrealizedProfit":"0.5000","isolatedWallet":"7.0000","leverage":"2","isolated":true,"positionSide":"BOTH"}]}"""
                                    )
                                } else {
                                    buildResp(
                                        200,
                                        """{"totalWalletBalance":"12.9942","totalMarginBalance":"12.9942","availableBalance":"12.9942","totalUnrealizedProfit":"0.0000","canTrade":true,"positions":[]}"""
                                    )
                                }
                            }
                        }
                    }
                    path.endsWith("/fapi/v1/openOrders") -> buildResp(200, "[]")
                    path.endsWith("/fapi/v1/openAlgoOrders") -> buildResp(200, "[]")
                    path.endsWith("/fapi/v1/order") && method == "POST" -> {
                        orderSubmissionCount++
                        buildResp(
                            200,
                            """{"orderId":99001122,"clientOrderId":"ASTRA_PANIC_DOGEUSDT_1760000000000","symbol":"DOGEUSDT","side":"SELL","positionSide":"BOTH","type":"MARKET","status":"FILLED","origQty":"100","executedQty":"100","avgPrice":"0.14500","stopPrice":"0","workingType":"CONTRACT_PRICE","closePosition":false,"reduceOnly":true,"updateTime":1760000050000}"""
                        )
                    }
                    path.endsWith("/fapi/v1/algoOrder") && method == "DELETE" -> {
                        if (scenario == "CANCEL_FAIL") {
                            buildResp(500, """{"code":-1001,"msg":"Internal error canceling algo order"}""")
                        } else {
                            buildResp(200, """{"algoId":8801,"clientAlgoId":"ASTRA_SL_DOGEUSDT_1","algoStatus":"CANCELED","code":200,"msg":"success"}""")
                        }
                    }
                    path.endsWith("/fapi/v1/userTrades") -> {
                        buildResp(
                            200,
                            """[{"id":701,"orderId":99001122,"symbol":"DOGEUSDT","side":"SELL","price":"0.14500","qty":"100","quoteQty":"14.5000","realizedPnl":"0.5000","commission":"0.0058","commissionAsset":"USDT","time":1760000050005,"buyer":false,"maker":false}]"""
                        )
                    }
                    else -> buildResp(200, "{}")
                }
            }
            .build()

        val binanceAdapter = BinanceAdapter(vault, mockHttpClient)
        val aiGateway = AiIntelligenceGateway(vault, mockHttpClient)
        val tgController = TelegramBotController(vault, dao, mockHttpClient)
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = binanceAdapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        val basePos = com.example.data.local.PositionEntity(
            symbol = "DOGEUSDT",
            signedQuantity = "100",
            entryAvgPrice = "0.14000",
            markPrice = "0.14500",
            unrealizedPnlUsdt = "0.5000",
            isolatedMarginUsdt = "7.0000",
            leverage = 2,
            stopPrice = "0.13700",
            targetPrice = "0.14800",
            netBreakevenEstimate = "0.14020",
            protectiveSlOrderId = "8801",
            protectiveSlVerified = true,
            protectiveTpOrderId = "8802",
            protectiveTpVerified = true,
            positionSide = "BOTH",
            signalId = "SIG-DOGEUSDT-1",
            predictedScoreAtEntry = "0.7500",
            calibratedProbAtEntry = "0.6400",
            ownershipTag = "BOT_OWNED",
            state = "OPEN_PROTECTED",
            openedAtMs = 1_760_000_000_000L,
            updatedAtMs = 1_760_000_000_000L
        )
        dao.upsertPosition(basePos)

        // 1. P0-01 Case A: Pre-close fetch fails -> MUST NOT fall back to localSignedQty and MUST NOT submit a close order
        scenario = "FETCH_FAILED"
        accountCallCountInScenario = 0
        orderSubmissionCount = 0
        val reportFetchFailed = coordinator.executePanicCloseStateMachine("Test P0-01 FETCH_FAILED")
        assertFalse(reportFetchFailed.confirmedClosed)
        assertEquals(com.example.domain.PanicPreCloseVerificationState.FETCH_FAILED, reportFetchFailed.preCloseVerificationState)
        assertEquals("Must NOT submit a blind market close order when pre-close fetch fails", 0, orderSubmissionCount)
        assertEquals(1, dao.getActivePositionsOnce().size)

        // 2. P0-01 Case B: Exchange position is ALREADY 0 (VERIFIED_ZERO) -> MUST NOT submit a redundant close order; cleans up and deletes stale position
        scenario = "VERIFIED_ZERO"
        accountCallCountInScenario = 0
        orderSubmissionCount = 0
        val reportVerifiedZero = coordinator.executePanicCloseStateMachine("Test P0-01 VERIFIED_ZERO")
        assertTrue(reportVerifiedZero.confirmedClosed)
        assertEquals(com.example.domain.PanicPreCloseVerificationState.VERIFIED_ZERO, reportVerifiedZero.preCloseVerificationState)
        assertEquals("Must NOT submit a redundant close order when exchange position is already 0", 0, orderSubmissionCount)
        assertTrue("Stale local position must be deleted after VERIFIED_ZERO cleanup", dao.getActivePositionsOnce().isEmpty())

        // Re-insert position and protective order for Case C, D, E
        dao.upsertPosition(basePos)
        dao.upsertOrder(
            com.example.data.local.OrderRecordEntity(
                clientOrderId = "ASTRA_SL_DOGEUSDT_1",
                exchangeOrderId = "8801",
                symbol = "DOGEUSDT",
                side = "SELL",
                positionSide = "BOTH",
                orderType = "STOP_MARKET",
                purpose = "PROTECTIVE_SL",
                quantity = "100",
                executedQty = "0",
                avgPrice = "0",
                stopPrice = "0.13700",
                workingType = "MARK_PRICE",
                status = "NEW",
                isBotOwned = true,
                isAlgoOrder = true,
                updatedAtMs = 1_760_000_000_000L
            )
        )

        // 3. P0-02 Case C: Close order fills, but post-close position verification fails (HTTP 503) -> MUST NOT delete PositionEntity or report CLOSED_CONFIRMED
        scenario = "POST_CLOSE_FAIL"
        accountCallCountInScenario = 0
        orderSubmissionCount = 0
        val reportPostFail = coordinator.executePanicCloseStateMachine("Test P0-02 POST_CLOSE_FAIL")
        assertFalse("Must NOT confirm closed when post-close position verification fails", reportPostFail.confirmedClosed)
        assertFalse(reportPostFail.postCloseVerifiedZero)
        assertEquals(1, dao.getActivePositionsOnce().size)
        assertEquals("PANIC_CLOSE_FAILED", dao.getActivePositionsOnce().first().state)

        // 4. P0-03 Case D: Post-close position is 0, but canceling protective SL order fails (HTTP 500) -> marks CANCEL_PENDING, keeps PositionEntity
        scenario = "CANCEL_FAIL"
        accountCallCountInScenario = 0
        val reportCancelFail = coordinator.executePanicCloseStateMachine("Test P0-03 CANCEL_FAIL")
        assertFalse("Must NOT confirm closed when protective order cancellation fails", reportCancelFail.confirmedClosed)
        assertTrue(reportCancelFail.postCloseVerifiedZero)
        assertFalse(reportCancelFail.finalExchangeVerifiedClean)
        assertTrue(reportCancelFail.failedCancelProtectiveOrderCount > 0)
        assertEquals("CANCEL_PENDING", dao.getOrderByClientIdOnce("ASTRA_SL_DOGEUSDT_1")?.status)
        assertEquals(1, dao.getActivePositionsOnce().size)

        // Reset local order status to NEW for Case E
        dao.updateOrderStatus("ASTRA_SL_DOGEUSDT_1", "NEW", 1_760_000_000_000L)

        // 5. P0-04 Case E: Full verified close + verified cancel + /fapi/v1/userTrades accounting -> CLOSED_CONFIRMED with exact commission
        scenario = "FULL_SUCCESS"
        accountCallCountInScenario = 0
        val reportSuccess = coordinator.executePanicCloseStateMachine("Test P0-04 FULL_SUCCESS")
        assertTrue(reportSuccess.confirmedClosed)
        assertEquals(com.example.domain.PanicCloseStage.CLOSED_CONFIRMED, reportSuccess.finalStage)
        assertEquals(com.example.domain.FillPnlStatus.VERIFIED, reportSuccess.pnlStatus)
        assertEquals(BigDecimal("0.4942"), reportSuccess.realizedNetPnlUsdt)
        assertTrue("PositionEntity must be deleted after full verified close", dao.getActivePositionsOnce().isEmpty())
        assertEquals("CANCELED", dao.getOrderByClientIdOnce("ASTRA_SL_DOGEUSDT_1")?.status)

        val fills = dao.getAllFillsOnce()
        assertEquals(1, fills.size)
        val recordedFill = fills.first()
        assertEquals("VERIFIED", recordedFill.pnlStatus)
        assertFalse(recordedFill.isEstimated)
        assertEquals("0.0058", recordedFill.commissionUsdt)
        assertEquals("0.5000", recordedFill.grossPnlUsdt)
        assertEquals("0.4942", recordedFill.realizedPnlUsdt)
        assertEquals("0.7500", recordedFill.predictedScoreAtEntry)
    }

    // ---------------------------------------------------------------------------
    // Master Remediation Test (P0-05, P0-06, P1-03, P1-04):
    // Independent Skeptic fail-closed default, Reconciliation SL+TP verification,
    // Historical entry-score calibration, and Telegram getUpdates polling & strict grammar
    // ---------------------------------------------------------------------------
    @Test
    fun `verify P0_05 Independent Skeptic fail-closed default, P0_06 SL+TP reconciliation, P1_03 historical calibration, and P1_04 Telegram getUpdates`() = runBlocking {
        val dao = database.astraDao()
        dao.upsertSettings(AppSettingsEntity(binanceEndpointTarget = "TESTNET", environmentMode = "TEST"))

        val sampleApiKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz12"
        val sampleApiSecret = "9876543210ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210ZyXwVuTsRqPoNmLkJi"
        vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, sampleApiKey)
        vault.putSecret(SecretVault.SLOT_BINANCE_API_SECRET, sampleApiSecret)
        vault.putSecret(SecretVault.SLOT_AI_PRIMARY_KEY, "AIzaSyPrimaryAnalystKey123456789")

        var includeBothProtectiveOrdersInReconcile = false

        val mockHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                val path = req.url.encodedPath
                val jsonMedia = "application/json".toMediaType()
                val nowMs = System.currentTimeMillis()
                val validIso = java.time.Instant.ofEpochMilli(nowMs).toString()
                val futureIso = java.time.Instant.ofEpochMilli(nowMs + 180_000L).toString()

                fun buildResp(code: Int, body: String): okhttp3.Response {
                    return okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(code)
                        .message("OK")
                        .body(body.toResponseBody(jsonMedia))
                        .build()
                }

                when {
                    urlStr.contains("generativelanguage.googleapis.com") -> {
                        val analystInnerJson = """{"schema_version":"1.0","decision_id":"DEC-DOGE-1","as_of_utc":"$validIso","symbol":"DOGEUSDT","recommendation":"TRADE_CANDIDATE","strategy_candidate":"BREAKOUT_MOMENTUM","direction_candidate":"LONG","horizon":"15m","regime_hypothesis":"BREAKOUT_CANDIDATE","supporting_evidence_ids":["EV-1"],"contradicting_evidence_ids":[],"invalidation_conditions":["Close < EMA20"],"risks":["Slippage"],"missing_data":[],"score":0.74,"calibrated_probability":null,"reason_vi":"Phá vỡ kháng cự kèm RVOL cao.","expires_at_utc":"$futureIso"}"""
                        val escaped = org.json.JSONObject.quote(analystInnerJson)
                        buildResp(200, """{"candidates":[{"content":{"parts":[{"text":$escaped}]}}]}""")
                    }
                    urlStr.contains("api.groq.com") -> {
                        val skepticInnerJson = """{"schema_version":"1.0","skeptic_id":"SKP-DOGE-1","decision_id":"DEC-DOGE-1","symbol":"DOGEUSDT","verdict":"PASS","counter_evidence_ids":["CEV-1"],"missing_evidence":[],"invalidations":["Close < EMA20"],"penalty_score":0.10,"reason_vi":"Phản biện độc lập xác nhận dữ liệu hợp lệ.","expires_at_utc":"$futureIso"}"""
                        val escaped = org.json.JSONObject.quote(skepticInnerJson)
                        buildResp(200, """{"choices":[{"message":{"content":$escaped}}]}""")
                    }
                    path.endsWith("/fapi/v1/time") -> buildResp(200, """{"serverTime":$nowMs}""")
                    path.endsWith("/fapi/v2/account") -> buildResp(
                        200,
                        """{"totalWalletBalance":"24.8500","totalMarginBalance":"25.0000","availableBalance":"18.0000","totalUnrealizedProfit":"0.1500","canTrade":true,"positions":[{"symbol":"DOGEUSDT","positionAmt":"100","entryPrice":"0.14000","markPrice":"0.14150","unrealizedProfit":"0.1500","isolatedWallet":"7.0000","leverage":"2","isolated":true,"positionSide":"BOTH"}]}"""
                    )
                    path.endsWith("/fapi/v1/openOrders") -> buildResp(200, "[]")
                    path.endsWith("/fapi/v1/openAlgoOrders") -> {
                        if (!includeBothProtectiveOrdersInReconcile) {
                            // Only SL is active, TP is missing!
                            buildResp(
                                200,
                                """[{"algoId":8801,"clientAlgoId":"ASTRA_SL_DOGEUSDT_1","symbol":"DOGEUSDT","side":"SELL","positionSide":"BOTH","orderType":"STOP_MARKET","algoStatus":"NEW","triggerPrice":"0.13700","workingType":"MARK_PRICE","closePosition":true,"updateTime":$nowMs}]"""
                            )
                        } else {
                            // Both SL and TP are active!
                            buildResp(
                                200,
                                """[{"algoId":8801,"clientAlgoId":"ASTRA_SL_DOGEUSDT_1","symbol":"DOGEUSDT","side":"SELL","positionSide":"BOTH","orderType":"STOP_MARKET","algoStatus":"NEW","triggerPrice":"0.13700","workingType":"MARK_PRICE","closePosition":true,"updateTime":$nowMs},{"algoId":8802,"clientAlgoId":"ASTRA_TP_DOGEUSDT_1","symbol":"DOGEUSDT","side":"SELL","positionSide":"BOTH","orderType":"TAKE_PROFIT_MARKET","algoStatus":"NEW","triggerPrice":"0.14800","workingType":"MARK_PRICE","closePosition":true,"updateTime":$nowMs}]"""
                            )
                        }
                    }
                    else -> buildResp(404, "{}")
                }
            }
            .build()

        val binanceAdapter = BinanceAdapter(vault, mockHttpClient)
        val aiGateway = AiIntelligenceGateway(vault, mockHttpClient)
        val tgController = TelegramBotController(vault, dao, mockHttpClient)
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = binanceAdapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        // 1. P1-03: Insert 32 historical fills (30 with historical predictedScoreAtEntry, 2 legacy without predictedScoreAtEntry)
        for (i in 1..30) {
            val isWin = i <= 21
            dao.insertFillIgnoreDuplicate(
                FillEntity(
                    tradeId = "HIST_TRD_$i",
                    clientOrderId = "ASTRA_CLOSE_DOGE_$i",
                    symbol = "DOGEUSDT",
                    side = "SELL",
                    price = "0.14500",
                    quantity = "50",
                    grossPnlUsdt = if (isWin) "0.2500" else "-0.1200",
                    realizedPnlUsdt = if (isWin) "0.2400" else "-0.1300",
                    commissionUsdt = "0.0100",
                    commissionAsset = "USDT",
                    pnlStatus = "VERIFIED",
                    isEstimated = false,
                    predictedScoreAtEntry = if (isWin) "0.7600" else "0.2800",
                    positionLifecycleId = "POS_DOGE_$i",
                    reasonVi = "Historical trade $i",
                    executedAtMs = 1_760_000_000_000L + i * 1000L
                )
            )
        }
        for (j in 31..32) {
            dao.insertFillIgnoreDuplicate(
                FillEntity(
                    tradeId = "LEGACY_TRD_$j",
                    clientOrderId = "ASTRA_LEGACY_$j",
                    symbol = "DOGEUSDT",
                    side = "SELL",
                    price = "0.14500",
                    quantity = "50",
                    realizedPnlUsdt = "0.1000",
                    commissionUsdt = "0.0100",
                    commissionAsset = "USDT",
                    pnlStatus = "VERIFIED",
                    isEstimated = false,
                    predictedScoreAtEntry = null, // No historical prediction -> must be excluded from calibration!
                    reasonVi = "Legacy trade $j",
                    executedAtMs = 1_760_000_100_000L + j * 1000L
                )
            )
        }

        coordinator.scanAndEvaluateSymbol("DOGEUSDT")
        val calReport = coordinator.latestCalibrationReport.value
        assertTrue("Calibration report must be generated", calReport != null)
        assertEquals("Must only include the 30 fills that have historical predictedScoreAtEntry", 30, calReport!!.sampleSize)
        assertTrue(calReport.isSufficientSample)
        assertTrue(calReport.calibratedProbability != null)

        // 2. P0-05: Configure ONLY primary Analyst provider (no separate Skeptic provider) -> MUST NOT report SkepticVerdict.PASS!
        dao.upsertProvider(
            com.example.data.local.ProviderConfigEntity(
                providerId = "gemini",
                displayName = "Gemini Flash Analyst",
                role = "FAST_ANALYST",
                apiType = "GEMINI",
                baseUrl = "https://generativelanguage.googleapis.com",
                modelId = "gemini-2.5-flash",
                secretSlot = SecretVault.SLOT_AI_PRIMARY_KEY,
                enabled = true,
                healthStatus = "VERIFIED_HEALTHY"
            )
        )
        val reviewNoSkeptic = coordinator.requestAiCandidateReview()
        assertTrue(reviewNoSkeptic.contains("RULE_ONLY_PASS"))
        assertEquals(
            "Without an independent Skeptic provider, verdict must remain RULE_ONLY_PASS (never PASS)",
            com.example.domain.SkepticVerdict.RULE_ONLY_PASS,
            coordinator.latestSkepticResult.value?.verdict
        )

        // Now configure a separate Independent Skeptic provider with its own isolated key -> verdict upgrades to PASS
        vault.putSecret(SecretVault.SLOT_AI_SKEPTIC_KEY, "gsk_IndependentGroqSkepticKey987654321")
        dao.upsertProvider(
            com.example.data.local.ProviderConfigEntity(
                providerId = "groq",
                displayName = "Groq Independent Skeptic",
                role = "SKEPTIC",
                apiType = "OPENAI_COMPATIBLE",
                baseUrl = "https://api.groq.com/openai/v1",
                modelId = "llama-3.3-70b-versatile",
                secretSlot = SecretVault.SLOT_AI_SKEPTIC_KEY,
                enabled = true,
                healthStatus = "VERIFIED_HEALTHY"
            )
        )
        val reviewWithSkeptic = coordinator.requestAiCandidateReview()
        assertTrue(reviewWithSkeptic.contains("PASS"))
        assertEquals(
            com.example.domain.SkepticVerdict.PASS,
            coordinator.latestSkepticResult.value?.verdict
        )

        // 3. P0-06: Reconciliation when only SL exists (TP missing) -> PROTECTION_DEGRADED & BotState.PROTECTION_FAILURE
        includeBothProtectiveOrdersInReconcile = false
        val reconDegradedMsg = coordinator.runFullReconciliation("DOGEUSDT")
        assertTrue(reconDegradedMsg.contains("PROTECTION_DEGRADED"))
        assertEquals(BotState.PROTECTION_FAILURE, coordinator.botState.value)
        assertEquals("PROTECTION_DEGRADED", dao.getActivePositionsOnce().first().state)

        // Reconciliation when BOTH SL and TP exist -> OPEN_PROTECTED & BotState.POSITION_PROTECTED
        includeBothProtectiveOrdersInReconcile = true
        val reconProtectedMsg = coordinator.runFullReconciliation("DOGEUSDT")
        assertTrue(reconProtectedMsg.contains("ĐÃ ĐỐI SOÁT"))
        assertEquals(BotState.POSITION_PROTECTED, coordinator.botState.value)
        assertEquals("OPEN_PROTECTED", dao.getActivePositionsOnce().first().state)

        // 4. P1-04: Telegram getUpdates JSON parsing, monotonic update_id offset, and strict command grammar
        val sampleUpdatesJson = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 5001,
                  "message": {
                    "message_id": 10,
                    "from": {"id": 777888},
                    "chat": {"id": 999000},
                    "text": "/status"
                  }
                },
                {
                  "update_id": 5002,
                  "message": {
                    "message_id": 11,
                    "from": {"id": 777888},
                    "chat": {"id": 999000},
                    "text": "/status unexpected_arg"
                  }
                },
                {
                  "update_id": 5001,
                  "message": {
                    "message_id": 12,
                    "from": {"id": 777888},
                    "chat": {"id": 999000},
                    "text": "/status"
                  }
                }
              ]
            }
        """.trimIndent()

        val tgOutcomes = tgController.parseAndProcessGetUpdatesJson(
            rawJson = sampleUpdatesJson,
            whitelistedUserId = "777888",
            whitelistedChatId = "999000",
            onAuthorizedCommand = { cmd -> "Đã thực thi $cmd" }
        )
        assertEquals(3, tgOutcomes.size)
        assertTrue(tgOutcomes[0].accepted)
        assertEquals("/status", tgOutcomes[0].command)
        assertFalse("Extra arguments on single-token command must be rejected", tgOutcomes[1].accepted)
        assertEquals("MALFORMED_COMMAND", tgOutcomes[1].command)
        assertFalse("Replay of lower update_id 5001 after 5002 must be ignored", tgOutcomes[2].accepted)
        assertEquals("DUPLICATE_IGNORED", tgOutcomes[2].command)
        assertEquals(5002L, dao.getMaxTelegramUpdateId())
    }

    // ---------------------------------------------------------------------------
    // V5.1 Runbook Test (P0-02, P0-03, P0-04, P0-12, P1-04, P1-12):
    // Composite key (symbol, positionSide) for Hedge Mode, PRAGMA integrity_check,
    // ExecutionGate exact signalId match, multi-fill individual tradeId persistence,
    // Telegram panic nonce binding, and SecretVault missing hash fail-closed
    // ---------------------------------------------------------------------------
    @Test
    fun `verify V5_1 Runbook P0_02 Hedge Mode composite key, P0_03 integrity check, P0_04 exact signalId gate, P0_12 multi-fill accounting, and P1_12 vault hash fail-closed`() = runBlocking {
        // 1. P0-03: Verify Room database PRAGMA integrity_check passes and destructive fallback is disabled
        assertTrue("Database PRAGMA integrity_check must return true", database.runIntegrityCheck())
        assertEquals(6, AstraDatabase.ALL_MIGRATIONS.size)

        val dao = database.astraDao()
        dao.upsertSettings(AppSettingsEntity(binanceEndpointTarget = "TESTNET", environmentMode = "TEST"))

        // 2. P0-02: Verify composite primary key (symbol, positionSide) stores LONG and SHORT simultaneously without overwriting
        val longPos = com.example.data.local.PositionEntity(
            symbol = "DOGEUSDT",
            positionSide = "LONG",
            signedQuantity = "100",
            entryAvgPrice = "0.14000",
            markPrice = "0.14200",
            unrealizedPnlUsdt = "0.2000",
            isolatedMarginUsdt = "7.0000",
            leverage = 2,
            stopPrice = "0.13700",
            targetPrice = "0.14800",
            netBreakevenEstimate = "0.14020",
            protectiveSlOrderId = "SL_LONG_1",
            protectiveSlVerified = true,
            ownershipTag = "BOT_OWNED",
            state = "OPEN_PROTECTED",
            openedAtMs = 1_760_000_000_000L,
            updatedAtMs = 1_760_000_000_000L
        )
        val shortPos = longPos.copy(
            positionSide = "SHORT",
            signedQuantity = "-50",
            entryAvgPrice = "0.14500",
            stopPrice = "0.14900",
            targetPrice = "0.13800"
        )
        dao.upsertPosition(longPos)
        dao.upsertPosition(shortPos)

        val bothHedgePositions = dao.getActivePositionsOnce()
        assertEquals("Composite key (symbol, positionSide) must retain both LONG and SHORT rows", 2, bothHedgePositions.size)
        assertEquals("DOGEUSDT_LONG", dao.getPositionBySymbolAndSideOnce("DOGEUSDT", "LONG")?.positionKey)
        assertEquals("DOGEUSDT_SHORT", dao.getPositionBySymbolAndSideOnce("DOGEUSDT", "SHORT")?.positionKey)

        // Delete only SHORT leg -> LONG leg must remain intact
        dao.deletePositionBySymbolAndSide("DOGEUSDT", "SHORT")
        assertEquals(1, dao.getActivePositionsOnce().size)
        assertEquals("LONG", dao.getActivePositionsOnce().first().positionSide)
        dao.deletePositionBySymbolAndSide("DOGEUSDT", "LONG")
        assertTrue(dao.getActivePositionsOnce().isEmpty())

        // 3. P1-12: SecretVault.verifyVaultSlotIntegrity must fail-closed (return false) if hash metadata is missing
        val validKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz12"
        assertTrue(vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, validKey))
        assertTrue(vault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY))
        context.getSharedPreferences("astra_secret_vault_v51", Context.MODE_PRIVATE)
            .edit()
            .remove("hash_slot_${SecretVault.SLOT_BINANCE_API_KEY}")
            .commit()
        assertFalse(
            "verifyVaultSlotIntegrity must return false when SHA-256 hash metadata is missing",
            vault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY)
        )

        // Restore valid credentials for Panic Close multi-fill test
        vault.putSecret(SecretVault.SLOT_BINANCE_API_KEY, validKey)
        vault.putSecret(
            SecretVault.SLOT_BINANCE_API_SECRET,
            "9876543210ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210ZyXwVuTsRqPoNmLkJi"
        )

        // 4. P0-12: Verify Panic Close records ALL individual partial fills from /fapi/v1/userTrades without collapsing tradeIds
        var accountCallIdx = 0
        val multiFillMockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val method = req.method
                val media = "application/json".toMediaType()
                fun resp(code: Int, body: String) = okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(body.toResponseBody(media))
                    .build()

                when {
                    path.endsWith("/fapi/v1/time") -> resp(200, """{"serverTime":1760000000000}""")
                    path.endsWith("/fapi/v1/positionSide/dual") -> resp(200, """{"dualSidePosition":false}""")
                    path.endsWith("/fapi/v2/account") -> {
                        accountCallIdx++
                        if (accountCallIdx == 1) {
                            resp(
                                200,
                                """{"totalWalletBalance":"20.0000","totalMarginBalance":"20.5000","availableBalance":"13.5000","totalUnrealizedProfit":"0.5000","canTrade":true,"positions":[{"symbol":"DOGEUSDT","positionAmt":"100","entryPrice":"0.14000","markPrice":"0.14500","unrealizedProfit":"0.5000","isolatedWallet":"7.0000","leverage":"2","isolated":true,"positionSide":"BOTH"}]}"""
                            )
                        } else {
                            resp(
                                200,
                                """{"totalWalletBalance":"20.5142","totalMarginBalance":"20.5142","availableBalance":"20.5142","totalUnrealizedProfit":"0.0000","canTrade":true,"positions":[]}"""
                            )
                        }
                    }
                    path.endsWith("/fapi/v1/openOrders") -> resp(200, "[]")
                    path.endsWith("/fapi/v1/openAlgoOrders") -> resp(200, "[]")
                    path.endsWith("/fapi/v1/order") && method == "POST" -> resp(
                        200,
                        """{"orderId":99007788,"clientOrderId":"ASTRA_PANIC_DOGEUSDT_1","symbol":"DOGEUSDT","side":"SELL","positionSide":"BOTH","type":"MARKET","status":"FILLED","origQty":"100","executedQty":"100","avgPrice":"0.14520","stopPrice":"0","workingType":"CONTRACT_PRICE","closePosition":false,"reduceOnly":true,"updateTime":1760000050000}"""
                    )
                    path.endsWith("/fapi/v1/userTrades") -> resp(
                        200,
                        """[
                            {"id":8001,"orderId":99007788,"symbol":"DOGEUSDT","side":"SELL","price":"0.14500","qty":"60","quoteQty":"8.7000","realizedPnl":"0.3000","commission":"0.00348","commissionAsset":"USDT","time":1760000050001,"buyer":false,"maker":false},
                            {"id":8002,"orderId":99007788,"symbol":"DOGEUSDT","side":"SELL","price":"0.14550","qty":"40","quoteQty":"5.8200","realizedPnl":"0.2200","commission":"0.00232","commissionAsset":"USDT","time":1760000050005,"buyer":false,"maker":false}
                        ]"""
                    )
                    path.endsWith("/fapi/v1/income") -> resp(200, "[]")
                    else -> resp(200, "{}")
                }
            }
            .build()

        val adapter = BinanceAdapter(vault, multiFillMockClient)
        val aiGateway = AiIntelligenceGateway(vault, multiFillMockClient)
        val tgController = TelegramBotController(vault, dao, multiFillMockClient)
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = adapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        dao.upsertPosition(longPos.copy(positionSide = "BOTH"))
        val panicMultiFill = coordinator.executePanicCloseStateMachine("Test P0-12 multi-fill")
        assertTrue(panicMultiFill.confirmedClosed)
        val recordedFills = dao.getAllFillsOnce()
        assertEquals("Both partial fills (8001 and 8002) must be persisted as distinct FillEntity rows", 2, recordedFills.size)
        val tradeIds = recordedFills.map { it.tradeId }.toSet()
        assertTrue(tradeIds.contains("TRD_DOGEUSDT_8001"))
        assertTrue(tradeIds.contains("TRD_DOGEUSDT_8002"))

        // 5. P1-04: Telegram /panic confirmation nonce is strictly bound to originating chatId and userId
        val panicInit = tgController.processIncomingUpdate(
            updateId = 9001L,
            fromUserId = "owner_1",
            chatId = "chat_1",
            text = "/panic",
            whitelistedUserId = "owner_1",
            whitelistedChatId = "chat_1",
            onAuthorizedCommand = { "PANIC_EXECUTED" }
        )
        assertTrue(panicInit.accepted)
        assertEquals("/panic_step1", panicInit.command)
        val nonce = panicInit.replyMessageVi.substringAfter("/panic_confirm ").substringBefore("\n").trim()
        assertEquals(6, nonce.length)

        val panicConfirmOk = tgController.processIncomingUpdate(
            updateId = 9002L,
            fromUserId = "owner_1",
            chatId = "chat_1",
            text = "/panic_confirm $nonce",
            whitelistedUserId = "owner_1",
            whitelistedChatId = "chat_1",
            onAuthorizedCommand = { "PANIC_CONFIRMED_OK" }
        )
        assertTrue(panicConfirmOk.accepted)
        assertEquals("/panic_confirmed", panicConfirmOk.command)
        assertEquals("COMPLETED", dao.getTelegramUpdateOnce(9002L)?.status)
    }

    // ---------------------------------------------------------------------------
    // Forecasting Runbook Test (Section 10, 12, 13, 14):
    // Room MIGRATION_6_7 non-destructive upgrade, ForecastCoordinator persistence,
    // SHADOW/ADVISORY_TEST_ONLY safety invariants, and 360dp Forecast UI cards
    // ---------------------------------------------------------------------------
    @Test
    fun `verify Room MIGRATION_6_7 non-destructive upgrade, ForecastCoordinator persistence, and 360dp Forecast UI cards`(): Unit = runBlocking {
        // 1. Test MIGRATION_6_7 directly on a raw SQLite database simulating v6 schema
        val sqliteOpenHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `app_settings` (
                                `id` INTEGER NOT NULL,
                                `environmentMode` TEXT NOT NULL,
                                `binanceEndpointTarget` TEXT NOT NULL,
                                `liveExplicitlyArmed` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "INSERT INTO `app_settings` (`id`, `environmentMode`, `binanceEndpointTarget`, `liveExplicitlyArmed`) VALUES (1, 'TEST', 'MAINNET', 0)"
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val rawDb = sqliteOpenHelper.writableDatabase
        AstraDatabase.MIGRATION_6_7.migrate(rawDb)

        // Verify existing app_settings row survived and forecastOperatingMode defaults to SHADOW
        rawDb.query("SELECT environmentMode, forecastOperatingMode FROM app_settings WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("TEST", cursor.getString(0))
            assertEquals("SHADOW", cursor.getString(1))
        }

        // Verify all 4 new forecasting tables exist and pass PRAGMA integrity_check
        for (tableName in listOf("forecast_predictions", "forecast_models", "forecast_outcomes", "forecast_metrics")) {
            rawDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'").use { cursor ->
                assertTrue("Table $tableName must exist after MIGRATION_6_7", cursor.moveToFirst())
            }
        }
        rawDb.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0).lowercase())
        }
        sqliteOpenHelper.close()

        // 2. Test ForecastCoordinator & ForecastScheduler end-to-end with 90 closed 15m candles
        val dao = database.astraDao()
        dao.upsertSettings(AppSettingsEntity(environmentMode = "TEST", forecastOperatingMode = "ADVISORY_TEST_ONLY"))

        val klines90Json = buildString {
            append("[")
            for (i in 0 until 90) {
                if (i > 0) append(",")
                val openTime = 1_760_000_000_000L + i * 900_000L
                val closeTime = openTime + 899_999L
                val price = 0.14000 + (i * 0.00012)
                val openStr = "%.5f".format(java.util.Locale.US, price - 0.00005)
                val highStr = "%.5f".format(java.util.Locale.US, price + 0.00035)
                val lowStr = "%.5f".format(java.util.Locale.US, price - 0.00025)
                val closeStr = "%.5f".format(java.util.Locale.US, price)
                append("""[$openTime,"$openStr","$highStr","$lowStr","$closeStr","120000",$closeTime,"16800",180,"68000","9520","0"]""")
            }
            append("]")
        }

        val forecastMockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val media = "application/json".toMediaType()
                fun resp(code: Int, body: String) = okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(body.toResponseBody(media))
                    .build()

                when {
                    path.endsWith("/fapi/v1/klines") -> resp(200, klines90Json)
                    path.endsWith("/fapi/v1/premiumIndex") -> resp(
                        200,
                        """{"symbol":"DOGEUSDT","markPrice":"0.15068","indexPrice":"0.15062","lastFundingRate":"0.00010","nextFundingTime":1760100000000}"""
                    )
                    path.endsWith("/fapi/v1/ticker/bookTicker") -> resp(
                        200,
                        """{"symbol":"DOGEUSDT","bidPrice":"0.15065","bidQty":"52000","askPrice":"0.15071","askQty":"48000","time":1760081000000}"""
                    )
                    else -> resp(200, "{}")
                }
            }
            .build()

        val adapter = BinanceAdapter(vault, forecastMockClient)
        val aiGateway = AiIntelligenceGateway(vault, forecastMockClient)
        val tgController = TelegramBotController(vault, dao, forecastMockClient)
        val coordinator = AstraTradingCoordinator(
            context = context,
            dao = dao,
            secretVault = vault,
            binanceAdapter = adapter,
            aiGateway = aiGateway,
            telegramController = tgController
        )

        coordinator.scanAndEvaluateSymbol("DOGEUSDT")
        val report = coordinator.latestForecastReport.value
        assertTrue("Forecast report must be generated during scanAndEvaluateSymbol", report != null)
        assertEquals(com.example.forecast.domain.ForecastStatus.READY_VALIDATED, report!!.overallStatus)
        assertEquals(3, report.horizonResults.size)

        val persistedPredictions = dao.getRecentForecastPredictionsForSymbolOnce("DOGEUSDT", 10)
        assertEquals("All 3 horizons (15m, 1h, 4h) must be persisted in forecast_predictions", 3, persistedPredictions.size)
        val registeredModels = dao.getForecastModelsOnce()
        assertTrue("Champion model must be registered in forecast_models", registeredModels.isNotEmpty())

        // Verify LIVE mode remains strictly locked
        assertTrue("Forecasting engine must NEVER unlock safetyDebugLockActive", coordinator.safetyDebugLockActive.value)
        assertFalse("Forecasting engine must NEVER enable LIVE mode", coordinator.checklist.value.canEnableLive)

        // 3. Render OverviewTabScreen on 360dp width and verify Forecast Card & S7 Edge Diagnostics Card
        val diagnostics = coordinator.forecastDiagnostics.value
        composeTestRule.setContent {
            MyApplicationTheme {
                OverviewTabScreen(
                    settings = AppSettingsEntity(forecastOperatingMode = "ADVISORY_TEST_ONLY"),
                    botState = BotState.READY_TEST,
                    safetyDebugLockActive = true,
                    accountCapitalState = AccountCapitalState(),
                    aiModuleStatus = AiModuleStatus.UNCONFIGURED,
                    dailySnapshot = null,
                    activePositions = emptyList(),
                    featureSnapshot = coordinator.latestFeatureSnapshot.value,
                    evEstimate = coordinator.latestEvEstimate.value,
                    skepticResult = coordinator.latestSkepticResult.value,
                    sizingDecision = coordinator.latestSizingDecision.value,
                    deviceHealth = coordinator.deviceHealth.value,
                    clockStatus = ClockSyncStatus(),
                    liveChecklist = LiveActivationChecklist(),
                    selectedSymbol = "DOGEUSDT",
                    isBusy = false,
                    onSelectSymbol = {},
                    onScanAndEvaluate = {},
                    onReconcile = {},
                    onExecuteApprovedCandidate = {},
                    onPause = {},
                    onDrain = {},
                    onResume = {},
                    onPanicCloseConfirmed = {},
                    onSwitchEnvironment = { _, _ -> },
                    onToggleForegroundService = {},
                    forecastReport = report,
                    forecastDiagnostics = diagnostics
                )
            }
        }

        composeTestRule.onNodeWithTag("forecast_engine_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("forecast_status_badge").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("forecast_p_up_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("forecast_calibration_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("s7_edge_forecast_diagnostics_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("s7_edge_diagnostics_summary_text").performScrollTo().assertIsDisplayed()
        Unit
    }
}




