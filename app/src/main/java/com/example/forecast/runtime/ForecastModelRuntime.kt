package com.example.forecast.runtime

import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.ForecastTensorInput
import com.example.forecast.domain.RawForecastOutput

/**
 * Runtime abstraction for on-device sequence forecasting models (Section 10 & 11.2).
 * Implementations are strictly thread-safe (serialized via Mutex / single-thread dispatcher)
 * and reuse preallocated buffers to avoid GC pauses on Samsung Galaxy S7 Edge (4 GB RAM).
 */
interface ForecastModelRuntime {
    val isReady: Boolean
    val activeModelId: String
    val cpuThreadsConfigured: Int
    val isRollbackMode: Boolean

    suspend fun warmup(): Boolean

    suspend fun predictHorizon(
        input: ForecastTensorInput,
        horizon: String,
        regimeKey: String = "DEFAULT"
    ): Pair<ForecastStatus, RawForecastOutput?>

    suspend fun predictAllHorizons(
        input: ForecastTensorInput,
        regimeKey: String = "DEFAULT"
    ): Pair<ForecastStatus, Map<String, RawForecastOutput>>

    fun setRollbackToBaseline(enabled: Boolean)

    fun getBundleMetadata(): ForecastModelBundle?

    fun close()
}
