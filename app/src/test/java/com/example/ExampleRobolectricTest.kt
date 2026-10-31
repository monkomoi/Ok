package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AstraDatabase
import com.example.domain.DataTrustState
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.engine.ForecastCoordinator
import com.example.forecast.runtime.ForecastModelLoader
import com.example.forecast.runtime.ForecastModelRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h640dp-xxhdpi")
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var db: AstraDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AstraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun forecastModelLoader_verifiesBundleChecksumsGoldenVectorsAnd4hHeadConsistency() {
        val loader = ForecastModelLoader(context)
        val result = loader.loadAndVerifyBundle()
        assertTrue("Bundle load failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val artifacts = result.getOrThrow()
        assertEquals("WEIGHTS_COMPACT_JSON_INT8", artifacts.bundle.artifactFormat)
        assertEquals("TEST_FIXTURE", artifacts.bundle.dataTrustTag)
        assertTrue(artifacts.goldenParityPassed)
        assertTrue("Golden max abs diff must be <= 1e-4", artifacts.goldenMaxAbsDiff <= 1e-4)
        assertTrue(
            "All 4 target symbols must be supported",
            artifacts.bundle.supportedSymbols.containsAll(listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"))
        )
    }

    @Test
    fun forecastCoordinator_resolvesMultiHorizonEvaluationsIdempotently() = runBlocking {
        val dao = db.astraDao()
        val registry = ForecastModelRegistry(ForecastModelLoader(context))
        val coordinator = ForecastCoordinator(dao = dao, modelRegistry = registry)
        assertTrue(coordinator.initialize())

        val bars15m = (0 until 105).map { i ->
            val openTime = 1_700_000_000_000L + i * 900_000L
            com.example.domain.KlineBar(
                openTime = openTime,
                open = 68000.0 + i * 5.0,
                high = 68040.0 + i * 5.0,
                low = 67970.0 + i * 5.0,
                close = 68020.0 + i * 5.0,
                volume = 950.0 + i,
                closeTime = openTime + 899_999L
            )
        }
        val evalNow = bars15m.last().closeTime
        val fcResult = coordinator.evaluateSymbolSequence(
            symbol = "BTCUSDT",
            klines15m = bars15m,
            klines1h = emptyList(),
            klines4h = emptyList(),
            spreadBps = 1.4,
            obiTop5 = 0.08,
            operatingMode = ForecastOperatingMode.SHADOW,
            nowTimestampMs = evalNow
        )

        assertTrue("All horizons (15m, 1h, 4h) must be head-consistent", fcResult.headConsistent)
        assertEquals(3, fcResult.horizons.size)

        // Persist twice with identical timestamp -> unique index ensures idempotency (only 3 rows total: 15m, 60m, 240m)
        coordinator.persistMultiHorizonEvaluations(fcResult, entryPrice = 68500.0, isTestFixture = false)
        coordinator.persistMultiHorizonEvaluations(fcResult, entryPrice = 68500.0, isTestFixture = false)

        val unresolvedAtStart = dao.getUnresolvedForecastEvaluations("BTCUSDT", evalNow + 1000L)
        assertEquals(0, unresolvedAtStart.size)

        // Advance clock by 4 hours + 1 minute -> all 3 horizons (15m, 60m, 240m) become due and resolve
        val resolvedCount = coordinator.resolvePendingEvaluations(
            symbol = "BTCUSDT",
            currentTimestampMs = evalNow + 241 * 60_000L,
            currentPrice = 69100.0,
            costBudgetPct = 0.12
        )
        assertEquals(3, resolvedCount)

        val resolved15m = dao.getRecentResolvedEvaluations(15, 10)
        assertEquals(1, resolved15m.size)
        assertNotNull(resolved15m.first().brierScore)
    }

    @Test
    fun mainActivity_rendersBlankSlateSafetyLockAndForecastCardOnS7EdgeViewport() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_app_bar_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("data_trust_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("safety_lock_banner").assertIsDisplayed()

        // Navigate to Risk tab and verify the interactive PositionSizingCalculatorCard renders
        composeTestRule.onNodeWithTag("nav_tab_strategy").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("position_sizing_calculator_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calculator_approval_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calc_balance_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calc_stop_loss_pct_input").assertIsDisplayed()
    }
}
