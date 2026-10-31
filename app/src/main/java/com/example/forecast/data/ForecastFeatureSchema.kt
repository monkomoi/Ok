package com.example.forecast.data

import kotlinx.serialization.Serializable

@Serializable
data class ForecastFeatureSpec(
    val index: Int,
    val name: String,
    val group: String,
    val formula: String,
    val heavyTailed: Boolean,
    val clipMin: Double,
    val clipMax: Double
)

@Serializable
data class ForecastFeatureSchema(
    val version: String,
    val sequenceLength: Int,
    val featureCount: Int,
    val schemaHash: String,
    val features: List<ForecastFeatureSpec>
) {
    companion object {
        const val EXPECTED_SEQUENCE_LENGTH = 64
        const val EXPECTED_FEATURE_COUNT = 26

        val CANONICAL_FEATURE_NAMES = listOf(
            "ret_1",
            "ret_2",
            "ret_4",
            "ret_8",
            "ret_16",
            "ret_1h",
            "ret_4h",
            "hl_spread",
            "co_body",
            "upper_wick",
            "lower_wick",
            "rv_8",
            "rv_32",
            "atr_pct_14",
            "ema_dist_20",
            "ema_dist_50",
            "rsi_14_norm",
            "adx_14_norm",
            "bb_zscore_20",
            "rvol_20",
            "taker_buy_imbalance",
            "cvd_slope_8",
            "spread_bps_norm",
            "obi_top5",
            "hour_sin",
            "hour_cos"
        )
    }

    fun validateContract(): Boolean {
        if (sequenceLength != EXPECTED_SEQUENCE_LENGTH) return false
        if (featureCount != EXPECTED_FEATURE_COUNT) return false
        if (features.size != EXPECTED_FEATURE_COUNT) return false
        for (i in features.indices) {
            if (features[i].index != i) return false
            if (features[i].name != CANONICAL_FEATURE_NAMES[i]) return false
        }
        return true
    }
}
