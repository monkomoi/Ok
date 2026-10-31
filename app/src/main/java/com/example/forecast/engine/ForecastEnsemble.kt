package com.example.forecast.engine

import com.example.domain.RegimeLabel

/**
 * Maps Astra Trader's deterministic `RegimeLabel` to the regime key used by `LiteRtForecastRuntime`'s
 * ensemble combiner (`DEFAULT`, `TREND`, `RANGE`, `HIGH_VOL`).
 */
object ForecastEnsemble {

    fun resolveRegimeEnsembleKey(primaryRegime: RegimeLabel?): String {
        return when (primaryRegime) {
            RegimeLabel.TREND_UP,
            RegimeLabel.TREND_DOWN,
            RegimeLabel.BREAKOUT_CANDIDATE -> "TREND"
            RegimeLabel.RANGE -> "RANGE"
            RegimeLabel.HIGH_VOL,
            RegimeLabel.EVENT_RISK,
            RegimeLabel.DATA_UNCERTAIN -> "HIGH_VOL"
            null -> "DEFAULT"
        }
    }
}
