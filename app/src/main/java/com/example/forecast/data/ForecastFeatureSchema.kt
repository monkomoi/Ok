package com.example.forecast.data

import org.json.JSONObject

data class HorizonSpec(
    val name: String,
    val bars15m: Int,
    val minutes: Int,
    val ttlMs: Long
)

data class FeatureItemSpec(
    val index: Int,
    val name: String,
    val group: String,
    val formula: String
)

data class ForecastFeatureSchema(
    val schemaName: String,
    val schemaVersion: String,
    val lookbackLength: Int,
    val featureCount: Int,
    val baseTimeframe: String,
    val higherTimeframes: List<String>,
    val horizons: List<HorizonSpec>,
    val clipZScore: Float,
    val oodZThreshold: Float,
    val oodFractionLimit: Float,
    val features: List<FeatureItemSpec>
) {
    val featureNames: List<String>
        get() = features.sortedBy { it.index }.map { it.name }

    fun validateExpectedContract(): Boolean {
        if (lookbackLength != EXPECTED_LOOKBACK_LENGTH) return false
        if (featureCount != EXPECTED_FEATURE_COUNT) return false
        if (features.size != EXPECTED_FEATURE_COUNT) return false
        val names = featureNames
        return names == EXPECTED_FEATURE_NAMES
    }

    companion object {
        const val EXPECTED_LOOKBACK_LENGTH = 64
        const val EXPECTED_FEATURE_COUNT = 26

        val EXPECTED_FEATURE_NAMES = listOf(
            "ret_1",
            "ret_2",
            "ret_4",
            "ret_8",
            "ret_16",
            "hl_range_pct",
            "co_return_pct",
            "body_ratio",
            "upper_wick_ratio",
            "lower_wick_ratio",
            "ema20_dist",
            "ema50_dist",
            "ema20_slope",
            "rsi_14_norm",
            "macd_hist_pct",
            "bb_percent_b",
            "bb_width_pct",
            "atr_14_pct",
            "ewma_vol_20",
            "rvol_20",
            "htf_1h_ret",
            "htf_4h_ret",
            "tod_sin",
            "tod_cos",
            "order_book_imbalance",
            "funding_rate_bps"
        )

        fun fromJsonString(jsonStr: String): ForecastFeatureSchema {
            val obj = JSONObject(jsonStr)
            val htfArr = obj.optJSONArray("higher_timeframes")
            val htf = mutableListOf<String>()
            if (htfArr != null) {
                for (i in 0 until htfArr.length()) {
                    htf.add(htfArr.getString(i))
                }
            }
            val horizArr = obj.getJSONArray("horizons")
            val horizons = mutableListOf<HorizonSpec>()
            for (i in 0 until horizArr.length()) {
                val h = horizArr.getJSONObject(i)
                horizons.add(
                    HorizonSpec(
                        name = h.getString("name"),
                        bars15m = h.getInt("bars_15m"),
                        minutes = h.getInt("minutes"),
                        ttlMs = h.getLong("ttl_ms")
                    )
                )
            }
            val featArr = obj.getJSONArray("features")
            val feats = mutableListOf<FeatureItemSpec>()
            for (i in 0 until featArr.length()) {
                val f = featArr.getJSONObject(i)
                feats.add(
                    FeatureItemSpec(
                        index = f.getInt("index"),
                        name = f.getString("name"),
                        group = f.getString("group"),
                        formula = f.getString("formula")
                    )
                )
            }
            return ForecastFeatureSchema(
                schemaName = obj.getString("schema_name"),
                schemaVersion = obj.getString("schema_version"),
                lookbackLength = obj.getInt("lookback_length"),
                featureCount = obj.getInt("feature_count"),
                baseTimeframe = obj.getString("base_timeframe"),
                higherTimeframes = htf,
                horizons = horizons,
                clipZScore = obj.optDouble("clip_z_score", 5.0).toFloat(),
                oodZThreshold = obj.optDouble("ood_z_threshold", 3.8).toFloat(),
                oodFractionLimit = obj.optDouble("ood_fraction_limit", 0.25).toFloat(),
                features = feats.sortedBy { it.index }
            )
        }
    }
}
