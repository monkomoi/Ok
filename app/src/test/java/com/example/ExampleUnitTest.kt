package com.example

import com.example.domain.KlineBar
import com.example.domain.SymbolFilter
import com.example.domain.SymbolModelEligibility
import com.example.forecast.data.FeatureScalerStats
import com.example.forecast.data.ForecastFeaturePipeline
import com.example.forecast.data.ForecastFeatureSchema
import com.example.forecast.data.ForecastFeatureSpec
import com.example.forecast.data.ForecastScaler
import com.example.forecast.data.ForecastScalerConfig
import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.RawHorizonOutput
import com.example.forecast.engine.ForecastCalibrationConfig
import com.example.forecast.engine.ForecastCalibrationEngine
import com.example.forecast.engine.ForecastQualityGate
import com.example.forecast.engine.HorizonCalibrationConfig
import com.example.network.BinanceV5RetrofitModule
import com.example.quant.NewsEventGateEngine
import com.example.quant.QuantMathEngine
import com.example.telegram.TelegramBotController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.RoundingMode

class ExampleUnitTest {

    @Test
    fun binanceV5RetrofitModule_hmacSignatureAndServiceCreation() {
        val query = "symbol=BTCUSDT&side=BUY&type=LIMIT&quantity=0.002&price=65000.0&recvWindow=5000&timestamp=1700000000000"
        val secret = "test_secret_key_for_hmac_verification"
        val sig1 = BinanceV5RetrofitModule.computeHmacSha256Hex(query, secret)
        val sig2 = BinanceV5RetrofitModule.computeHmacSha256Hex(query, secret)
        assertEquals(64, sig1.length)
        assertEquals(sig1, sig2)

        val testnetService = BinanceV5RetrofitModule.createService(
            apiKeyProvider = { "test_key" },
            secretKeyProvider = { secret },
            isTestnetProvider = { true }
        )
        assertNotNull(testnetService)

        val module = BinanceV5RetrofitModule()
        val signedParams = module.buildSignedParams(
            mapOf("symbol" to "ETHUSDT", "side" to "BUY", "type" to "MARKET", "quantity" to "0.02"),
            secret
        )
        assertTrue(signedParams.containsKey("timestamp"))
        assertTrue(signedParams.containsKey("recvWindow"))
        assertEquals(64, signedParams["signature"]?.length)

        val repo = com.example.network.BinanceFuturesRepository(
            secretVault = com.example.security.SecretVault(),
            retrofitModule = module
        )
        val credStatus = repo.getCredentialStatus()
        assertNotNull(credStatus.activeSourceLabel)
    }

    @Test
    fun quantMathEngine_rejectsTradeWhenMinNotionalExceedsLossBudget() {
        // $80 small-capital account with 0.50% risk per trade -> R = $0.40 max loss
        // On BTCUSDT (minNotional = $100), a 1.0% stop distance requires ~$40 notional max,
        // which is below $105 ($100 * 1.05). The engine MUST reject rather than rounding up to $100!
        val btcFilter = SymbolFilter(
            symbol = "BTCUSDT",
            tickSize = 0.10,
            stepSize = 0.001,
            minQty = 0.001,
            minNotional = 100.0
        )
        val result = QuantMathEngine.calculatePositionSizing(
            symbolFilter = btcFilter,
            equityUsd = 80.0,
            availableMarginUsd = 80.0,
            riskPerTradePct = 0.005, // 0.5% -> R = $0.40
            remainingDailyRiskBudgetUsd = 1.20,
            entryPrice = 68000.0,
            stopLossPrice = 67320.0, // 1.0% stop distance
            takeProfitPrice = 69700.0, // 2.5% TP distance
            maxAllowedLeverage = 5
        )
        assertFalse("BTCUSDT trade on $80 account with 0.5% risk must be rejected due to minNotional", result.approved)
        assertTrue(result.rejectionReason!!.contains("minNotional"))

        // On DOGEUSDT (minNotional = $5.0), the same $80 account with 0.50% risk ($0.40) and 1.2% stop
        // sizes to ~$29.85 notional (> $5.25 required minNotional) and is APPROVED!
        val dogeFilter = SymbolFilter(
            symbol = "DOGEUSDT",
            tickSize = 0.00001,
            stepSize = 1.0,
            minQty = 1.0,
            minNotional = 5.0
        )
        val dogeResult = QuantMathEngine.calculatePositionSizing(
            symbolFilter = dogeFilter,
            equityUsd = 80.0,
            availableMarginUsd = 80.0,
            riskPerTradePct = 0.005,
            remainingDailyRiskBudgetUsd = 1.20,
            entryPrice = 0.14000,
            stopLossPrice = 0.13832, // 1.2% stop
            takeProfitPrice = 0.14420, // 3.0% TP
            maxAllowedLeverage = 5
        )
        assertTrue("DOGEUSDT trade within risk budget and above $5 minNotional should be approved", dogeResult.approved)
        assertTrue("Estimated max loss at stop must not exceed risk budget R=$0.40", dogeResult.estimatedMaxLossUsd <= 0.405)
    }

    @Test
    fun quantMathEngine_tickAndStepRoundingRules() {
        val qty = QuantMathEngine.roundToStepSize(12.987, 0.1)
        assertEquals(12.9, qty, 1e-6)

        val buyPrice = QuantMathEngine.roundToTickSize(68450.18, 0.10, RoundingMode.FLOOR)
        val sellPrice = QuantMathEngine.roundToTickSize(68450.12, 0.10, RoundingMode.CEILING)
        assertEquals(68450.10, buyPrice, 1e-6)
        assertEquals(68450.20, sellPrice, 1e-6)
    }

    @Test
    fun calibrationEngine_preventsDegenerateTemperatureCollapseAndDetectsHeadInconsistency() {
        // Verify that even if an unregularized config passes temperature=0.25 and biasShift=[1.30, -2.51, -2.57],
        // ForecastCalibrationEngine clamps temperature >= 0.75 and |biasShift| <= 0.25 so probabilities never collapse to 100%/0%/0%.
        val config = ForecastCalibrationConfig(
            version = "1.0.0",
            calibrationHash = "test_hash",
            minQualifiedSampleSize = 200,
            horizons = mapOf(
                "240" to HorizonCalibrationConfig(
                    horizonMinutes = 240,
                    temperature = 0.25, // degenerate small-sample temperature
                    biasShift = listOf(1.30578, -2.51091, -2.57841), // extreme small-sample prior shift
                    validationBrierScore = 0.18,
                    validationEce = 0.04,
                    sampleSize = 23,
                    qualificationStatus = "INSUFFICIENT_SAMPLE_SHADOW_ONLY"
                )
            )
        )
        val engine = ForecastCalibrationEngine(config)
        val raw = RawHorizonOutput(
            horizonMinutes = 240,
            rawLogits = doubleArrayOf(0.064, -0.070, 0.056),
            expectedReturnPct = 0.1092, // positive return while pDown would be high if uncoupled
            q10Pct = -0.35,
            q50Pct = 0.10,
            q90Pct = 0.65,
            epistemicUncertainty = 0.20
        )
        val calibrated = engine.calibrateHorizon(raw)
        assertTrue("Clamped temperature and bias shift must prevent pDown=1.0 collapse", calibrated.pDown < 0.65)
        assertTrue("pUp must remain non-zero", calibrated.pUp > 0.15)
        assertEquals("INSUFFICIENT_SAMPLE_SHADOW_ONLY", calibrated.calibrationQualificationStatus)

        // Direct test of checkHeadConsistency when pDown >= 0.52 and expectedReturnPct > +0.04%
        val contradiction = ForecastCalibrationEngine.checkHeadConsistency(
            pDown = 0.78,
            pFlat = 0.12,
            pUp = 0.10,
            expectedReturnPct = 0.11,
            q50Pct = 0.10
        )
        assertFalse("Contradictory direction and return heads must be flagged inconsistent", contradiction.isConsistent)
        assertTrue(contradiction.reason.contains("HEAD_CONTRADICTION"))
    }

    @Test
    fun qualityGate_blocksExecutionOnHeadInconsistencyOrInsufficientSample() {
        val bundle = ForecastModelBundle(
            modelId = "test_model",
            modelVersion = "2.0.0",
            architecture = "GRU_TCN",
            sequenceLength = 64,
            featureCount = 26,
            featureSchemaHash = "h1",
            scalerHash = "h2",
            calibrationHash = "h3",
            weightsCompactSha256 = "h4",
            goldenVectorsSha256 = "h5",
            goldenOutputsSha256 = "h6",
            createdTimestampUtc = "2026-01-01T00:00:00Z",
            trainWindowUtc = listOf("a", "b"),
            valWindowUtc = listOf("b", "c"),
            testWindowUtc = listOf("c", "d"),
            supportedSymbols = listOf("BTCUSDT"),
            horizonsMinutes = listOf(15, 60, 240),
            quantization = "INT8",
            minConfidenceThreshold = 0.45,
            maxEntropyThreshold = 0.90,
            maxOodScoreThreshold = 3.0
        )

        val specs = ForecastFeatureSchema.CANONICAL_FEATURE_NAMES.mapIndexed { idx, name ->
            ForecastFeatureSpec(idx, name, "g", "f", false, -10.0, 10.0)
        }
        val schema = ForecastFeatureSchema("1.0.0", 64, 26, "h1", specs)
        val stats = ForecastFeatureSchema.CANONICAL_FEATURE_NAMES.mapIndexed { idx, name ->
            FeatureScalerStats(idx, name, 0.0, 1.0, 0.0, 1.0, -3.0, 3.0, false, -10.0, 10.0)
        }
        val scaler = ForecastScaler(ForecastScalerConfig("1.0.0", "h2", stats))
        val pipeline = ForecastFeaturePipeline(schema, scaler)

        val bars = (0 until 100).map { i ->
            val t = 1700000000000L + i * 900_000L
            KlineBar(t, 68000.0 + i * 10, 68050.0 + i * 10, 67980.0 + i * 10, 68030.0 + i * 10, 100.0, t + 899_999L)
        }
        val input = pipeline.buildSequenceTensor("BTCUSDT", bars, 1.5, 0.1, bars.last().closeTime + 1000L)
        assertTrue(input.isWarmupComplete)

        val calEngine = ForecastCalibrationEngine(
            ForecastCalibrationConfig(
                version = "1.0.0",
                calibrationHash = "h3",
                minQualifiedSampleSize = 200,
                horizons = mapOf(
                    "15" to HorizonCalibrationConfig(15, 1.0, listOf(0.0, 0.0, 0.0), 0.17, 0.03, 250, "QUALIFIED"),
                    "60" to HorizonCalibrationConfig(60, 1.0, listOf(0.0, 0.0, 0.0), 0.18, 0.03, 250, "QUALIFIED"),
                    "240" to HorizonCalibrationConfig(240, 1.0, listOf(0.0, 0.0, 0.0), 0.19, 0.04, 250, "QUALIFIED")
                )
            )
        )
        val h15 = calEngine.calibrateHorizon(RawHorizonOutput(15, doubleArrayOf(-1.2, -0.5, 1.4), 0.35, -0.1, 0.32, 0.8, 0.1))
        val h60 = calEngine.calibrateHorizon(RawHorizonOutput(60, doubleArrayOf(-1.0, -0.4, 1.2), 0.55, -0.2, 0.50, 1.2, 0.1))
        // Inject contradictory 4h head (strong DOWN logits but positive expectedReturnPct = +0.45%)
        val h240Bad = calEngine.calibrateHorizon(RawHorizonOutput(240, doubleArrayOf(1.8, -0.8, -1.2), 0.45, -0.2, 0.42, 0.9, 0.1))
        assertFalse(h240Bad.headConsistent)

        val gate = ForecastQualityGate()
        val decision = gate.evaluate(
            bundle = bundle,
            input = input,
            horizons = mapOf(15 to h15, 60 to h60, 240 to h240Bad),
            operatingMode = ForecastOperatingMode.GATED_ENFORCEMENT,
            symbolEligibility = SymbolModelEligibility.FULL_SUPPORTED,
            driftPsi = 0.05,
            inferenceLatencyMs = 5L
        )
        assertEquals(ForecastStatus.HEAD_INCONSISTENT, decision.status)
        assertFalse(decision.allowTradeExecution)
    }

    @Test
    fun telegramBotController_enforcesAllowlistReplayAndTwoStepPanic() {
        val controller = TelegramBotController()
        val nowMs = 1_700_000_100_000L
        val msgSec = 1_700_000_095L

        // 1. Unauthorized chat ID rejected
        val unauth = controller.processIncomingCommand(
            updateId = 100L,
            senderChatId = "999999",
            configuredChatId = "123456",
            messageTimestampSec = msgSec,
            rawText = "/status",
            nowTimestampMs = nowMs
        )
        assertFalse(unauth.accepted)

        // 2. Authorized /status accepted
        val okStatus = controller.processIncomingCommand(
            updateId = 101L,
            senderChatId = "123456",
            configuredChatId = "123456",
            messageTimestampSec = msgSec,
            rawText = "/status",
            nowTimestampMs = nowMs
        )
        assertTrue(okStatus.accepted)

        // 3. Replayed updateId <= 101 rejected
        val replay = controller.processIncomingCommand(
            updateId = 101L,
            senderChatId = "123456",
            configuredChatId = "123456",
            messageTimestampSec = msgSec,
            rawText = "/pause",
            nowTimestampMs = nowMs
        )
        assertFalse(replay.accepted)
        assertEquals("REPLAY_BLOCKED", replay.command)

        // 4. Two-step /panic_close requires confirmation
        val step1 = controller.processIncomingCommand(
            updateId = 102L,
            senderChatId = "123456",
            configuredChatId = "123456",
            messageTimestampSec = msgSec,
            rawText = "/panic_close",
            nowTimestampMs = nowMs
        )
        assertTrue(step1.accepted)
        assertEquals(null, step1.triggeredKillSwitchLevel)

        val step2 = controller.processIncomingCommand(
            updateId = 103L,
            senderChatId = "123456",
            configuredChatId = "123456",
            messageTimestampSec = msgSec,
            rawText = "/panic_close CONFIRM",
            nowTimestampMs = nowMs + 10_000L
        )
        assertTrue(step2.accepted)
        assertEquals("PANIC_CLOSE", step2.triggeredKillSwitchLevel)
    }

    @Test
    fun newsEventGate_freezesOnExchangeSpreadOrFundingShock() {
        val shockGate = NewsEventGateEngine.evaluateGate(
            nowTimestampMs = 1_700_000_000_000L,
            spreadBps = 14.5, // > 10 bps shock ceiling
            fundingRatePct = 0.01,
            atrPct15m = 0.50
        )
        assertFalse(shockGate.allowNewEntries)
        assertEquals("FREEZE", shockGate.status)
    }

    @Test
    fun interactivePositionSizingPreview_calculatesFromBalanceAndStopLossPct() {
        // $200 balance, 0.50% risk ($1.00), 1.20% stop-loss on SOLUSDT @ $150.00
        val solPreview = com.example.ui.screens.calculateInteractivePositionSizingPreview(
            symbol = "SOLUSDT",
            side = "LONG",
            accountBalanceUsd = 200.0,
            riskPerTradePct = 0.50,
            stopLossPct = 1.20,
            rewardMultiple = 2.40,
            entryPrice = 150.0,
            maxLeverage = 5
        )
        assertTrue(solPreview.sizingResult.approved)
        assertEquals(1.00, solPreview.dollarRiskBudgetUsd, 1e-4)
        assertTrue(solPreview.stopLossPrice < 150.0)
        assertTrue(solPreview.takeProfitPrice > 150.0)
        assertTrue(solPreview.sizingResult.estimatedMaxLossUsd <= 1.01)

        // $60 balance, 0.50% risk ($0.30), 1.20% stop-loss on BTCUSDT -> rejected due to $100 minNotional
        val btcRejected = com.example.ui.screens.calculateInteractivePositionSizingPreview(
            symbol = "BTCUSDT",
            side = "LONG",
            accountBalanceUsd = 60.0,
            riskPerTradePct = 0.50,
            stopLossPct = 1.20,
            rewardMultiple = 2.40,
            entryPrice = 68000.0,
            maxLeverage = 5
        )
        assertFalse(btcRejected.sizingResult.approved)
        assertTrue(btcRejected.sizingResult.rejectionReason!!.contains("minNotional"))
    }
}
