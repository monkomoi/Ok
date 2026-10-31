package com.example.forecast.runtime

import android.content.Context
import com.example.forecast.data.ForecastFeatureSchema
import com.example.forecast.data.ForecastScaler
import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.engine.ForecastCalibrationEngine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Model Registry & Automatic Rollback Controller (`ForecastModelRegistry.kt`, Section 14).
 * - Holds the verified Champion (`astra_gru_tcn_hybrid_v1_int8`) and Rollback (`astra_logistic_l2_baseline_v1`) runtimes.
 * - Automatically switches to Rollback Baseline if `>= 3` consecutive inference errors occur.
 */
class ForecastModelRegistry {

    @Volatile
    var loadStatus: ForecastStatus = ForecastStatus.MODEL_MISSING
        private set

    @Volatile
    var loadErrorReasonVi: String? = "Chưa khởi tạo gói model dự báo."
        private set

    @Volatile
    var bundleMetadata: ForecastModelBundle? = null
        private set

    @Volatile
    var schema: ForecastFeatureSchema? = null
        private set

    @Volatile
    var scaler: ForecastScaler? = null
        private set

    @Volatile
    var calibrationEngine: ForecastCalibrationEngine? = null
        private set

    @Volatile
    var runtime: LiteRtForecastRuntime? = null
        private set

    @Volatile
    var lastLoadLatencyMs: Long = 0L
        private set

    private val consecutiveRuntimeErrors = AtomicInteger(0)

    fun initializeFromAssets(context: Context, cpuThreads: Int = 2): ForecastStatus {
        val loaded = ForecastModelLoader.loadFromAssets(context, cpuThreads = cpuThreads)
        applyLoadedArtifacts(loaded)
        return loaded.status
    }

    fun initializeFromDirectory(directory: File, cpuThreads: Int = 2): ForecastStatus {
        val loaded = ForecastModelLoader.loadFromDirectory(directory, cpuThreads = cpuThreads)
        applyLoadedArtifacts(loaded)
        return loaded.status
    }

    fun initializeFromByteProvider(readBytes: (String) -> ByteArray, cpuThreads: Int = 2): ForecastStatus {
        val loaded = ForecastModelLoader.loadFromByteProvider(readBytes, cpuThreads = cpuThreads)
        applyLoadedArtifacts(loaded)
        return loaded.status
    }

    private fun applyLoadedArtifacts(loaded: ForecastModelLoader.LoadedForecastBundleArtifacts) {
        runtime?.close()
        loadStatus = loaded.status
        loadErrorReasonVi = loaded.errorReasonVi
        bundleMetadata = loaded.bundleMetadata
        schema = loaded.schema
        scaler = loaded.scaler
        calibrationEngine = loaded.calibrationEngine
        runtime = loaded.runtime
        lastLoadLatencyMs = loaded.loadLatencyMs
        consecutiveRuntimeErrors.set(0)
    }

    fun recordInferenceSuccess() {
        consecutiveRuntimeErrors.set(0)
    }

    fun recordInferenceFailureAndMaybeRollback(): Boolean {
        val count = consecutiveRuntimeErrors.incrementAndGet()
        val rt = runtime ?: return false
        if (count >= 3 && !rt.isRollbackMode) {
            rt.setRollbackToBaseline(true)
            return true
        }
        return rt.isRollbackMode
    }

    fun triggerManualRollback(enableRollback: Boolean) {
        runtime?.setRollbackToBaseline(enableRollback)
        if (!enableRollback) {
            consecutiveRuntimeErrors.set(0)
        }
    }

    fun getConsecutiveErrorCount(): Int = consecutiveRuntimeErrors.get()
}
