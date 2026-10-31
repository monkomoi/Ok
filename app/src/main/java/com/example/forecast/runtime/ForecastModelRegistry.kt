package com.example.forecast.runtime

import android.content.Context
import com.example.domain.SymbolModelEligibility
import com.example.forecast.domain.ForecastModelBundle
import java.util.concurrent.atomic.AtomicReference

class ForecastModelRegistry(
    private val loader: ForecastModelLoader
) {
    private val activeArtifactsRef = AtomicReference<LoadedForecastArtifacts?>(null)
    private val lastLoadErrorRef = AtomicReference<String?>(null)

    fun initialize(): Result<LoadedForecastArtifacts> {
        val res = loader.loadAndVerifyBundle()
        res.onSuccess { artifacts ->
            activeArtifactsRef.set(artifacts)
            lastLoadErrorRef.set(null)
        }.onFailure { err ->
            activeArtifactsRef.set(null)
            lastLoadErrorRef.set(err.message ?: "Unknown forecast model bundle load failure")
        }
        return res
    }

    fun getActiveArtifacts(): LoadedForecastArtifacts? = activeArtifactsRef.get()

    fun getActiveBundle(): ForecastModelBundle? = activeArtifactsRef.get()?.bundle

    fun getLastLoadError(): String? = lastLoadErrorRef.get()

    fun getSymbolEligibility(symbol: String): SymbolModelEligibility {
        val bundle = activeArtifactsRef.get()?.bundle ?: return SymbolModelEligibility.UNSUPPORTED
        val normalized = symbol.uppercase()
        val rawTag = bundle.symbolEligibility[normalized]
        if (rawTag != null) {
            return runCatching { SymbolModelEligibility.valueOf(rawTag) }
                .getOrDefault(SymbolModelEligibility.POOLED_MODEL_ONLY)
        }
        return if (bundle.supportedSymbols.contains(normalized)) {
            SymbolModelEligibility.POOLED_MODEL_ONLY
        } else {
            SymbolModelEligibility.BASELINE_ONLY
        }
    }

    companion object {
        fun createDefault(context: Context): ForecastModelRegistry {
            return ForecastModelRegistry(ForecastModelLoader(context.applicationContext))
        }
    }
}
