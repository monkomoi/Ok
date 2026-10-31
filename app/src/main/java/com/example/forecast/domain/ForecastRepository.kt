package com.example.forecast.domain

import com.example.data.local.ForecastEvaluationEntity
import kotlinx.coroutines.flow.Flow

interface ForecastRepository {
    fun observeEvaluations(limit: Int = 100): Flow<List<ForecastEvaluationEntity>>
    suspend fun recordForecastEvaluation(entity: ForecastEvaluationEntity)
    suspend fun resolvePendingEvaluations(
        symbol: String,
        currentTimestampMs: Long,
        currentPrice: Double,
        costBudgetPct: Double
    ): Int
    suspend fun getRollingCalibrationMetrics(horizonMinutes: Int, windowSize: Int = 50): Pair<Double, Double>
}
