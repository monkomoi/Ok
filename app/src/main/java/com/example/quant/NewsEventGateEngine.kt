package com.example.quant

import com.example.domain.NewsGateState
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

data class ScheduledMacroEvent(
    val title: String,
    val source: String,
    val dayOfWeekUtc: Int?, // Calendar.WEDNESDAY, etc. (null = daily check)
    val hourUtc: Int,
    val minuteUtc: Int,
    val preFreezeMinutes: Int = 30,
    val postCooldownMinutes: Int = 30
)

/**
 * Deterministic macro-calendar and microstructure shock gate that blocks new entry
 * orders around high-impact scheduled USD macro releases (FOMC, CPI, NFP) or
 * sudden exchange funding/spread shocks.
 */
object NewsEventGateEngine {

    private val recurringMacroWindows = listOf(
        ScheduledMacroEvent(
            title = "US CPI / NFP Macro Release Window (13:30 UTC)",
            source = "BLS_SCHEDULE_UTC",
            dayOfWeekUtc = null, // Checked on typical release days or when volatility spikes near 13:30 UTC
            hourUtc = 13,
            minuteUtc = 30,
            preFreezeMinutes = 20,
            postCooldownMinutes = 25
        ),
        ScheduledMacroEvent(
            title = "FOMC Rate Decision / Minutes Window (18:00 UTC)",
            source = "FED_CALENDAR_UTC",
            dayOfWeekUtc = Calendar.WEDNESDAY,
            hourUtc = 18,
            minuteUtc = 0,
            preFreezeMinutes = 30,
            postCooldownMinutes = 45
        )
    )

    fun evaluateGate(
        nowTimestampMs: Long = System.currentTimeMillis(),
        spreadBps: Double = 1.5,
        fundingRatePct: Double = 0.01,
        atrPct15m: Double = 0.45,
        manualFreezeReason: String? = null
    ): NewsGateState {
        if (!manualFreezeReason.isNullOrBlank()) {
            return NewsGateState(
                status = "FREEZE",
                activeEventTitle = manualFreezeReason,
                sourceTag = "OPERATOR_MANUAL_OVERRIDE",
                freezeUntilMs = nowTimestampMs + 30 * 60_000L,
                allowNewEntries = false,
                rationale = "Operator manual news/event freeze active: $manualFreezeReason"
            )
        }

        // Microstructure / funding shock check (real exchange telemetry)
        if (spreadBps > 10.0 || abs(fundingRatePct) > 0.10 || atrPct15m > 2.80) {
            return NewsGateState(
                status = "FREEZE",
                activeEventTitle = "Microstructure / Volatility Shock",
                sourceTag = "EXCHANGE_TELEMETRY_GATE",
                freezeUntilMs = nowTimestampMs + 15 * 60_000L,
                allowNewEntries = false,
                rationale = "Exchange telemetry shock: spread=${"%.1f".format(spreadBps)}bps, funding=${"%.3f".format(fundingRatePct)}%, ATR%=${"%.2f".format(atrPct15m)}%"
            )
        }

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowTimestampMs
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        for (ev in recurringMacroWindows) {
            if (ev.dayOfWeekUtc != null && ev.dayOfWeekUtc != dow) continue
            val evMinuteOfDay = ev.hourUtc * 60 + ev.minuteUtc
            val deltaMin = minuteOfDay - evMinuteOfDay
            // Only trigger daily 13:30 UTC window if spread or volatility is elevated or on Wed/Thu/Fri
            val isHighRiskDay = (dow == Calendar.WEDNESDAY || dow == Calendar.THURSDAY || dow == Calendar.FRIDAY)
            if (isHighRiskDay && deltaMin >= -ev.preFreezeMinutes && deltaMin <= ev.postCooldownMinutes) {
                val status = if (deltaMin <= 0) "PRE_EVENT_FREEZE" else "POST_EVENT_COOLDOWN"
                val remainingMin = (ev.postCooldownMinutes - deltaMin).coerceAtLeast(5)
                return NewsGateState(
                    status = status,
                    activeEventTitle = ev.title,
                    sourceTag = ev.source,
                    freezeUntilMs = nowTimestampMs + remainingMin * 60_000L,
                    allowNewEntries = false,
                    rationale = "$status for ${ev.title} (${remainingMin}m remaining)"
                )
            }
        }

        return NewsGateState(
            status = "CLEAR",
            activeEventTitle = null,
            sourceTag = "EXCHANGE_AND_UTC_CALENDAR",
            freezeUntilMs = 0L,
            allowNewEntries = true,
            rationale = "No active macro release freeze or microstructure shock"
        )
    }
}
