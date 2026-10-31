package com.example.forecast.engine

import com.example.forecast.data.ForecastScalerConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight rolling feature drift & OOD rate tracker using a fixed-capacity ring buffer
 * so memory consumption is strictly constant on 4GB RAM devices.
 */
class ForecastDriftMonitor(
    private val scalerConfig: ForecastScalerConfig,
    private val windowCapacity: Int = 64
) {
    private val lock = Any()
    private val recentZScoreMeans = DoubleArray(windowCapacity)
    private val recentOodFlags = BooleanArray(windowCapacity)
    private var cursor = 0
    private var count = 0

    fun recordSample(rawLatestFeatures: DoubleArray, oodScore: Double, oodThreshold: Double): Double {
        synchronized(lock) {
            var zShiftSum = 0.0
            val n = min(rawLatestFeatures.size, scalerConfig.stats.size)
            for (i in 0 until n) {
                val st = scalerConfig.stats[i]
                val v = rawLatestFeatures[i]
                val z = if (st.heavyTailed) {
                    abs(v - st.median) / max(st.iqr, 1e-8)
                } else {
                    abs(v - st.mean) / max(st.std, 1e-8)
                }
                // Excess beyond expected ~0.8 average absolute Z-score for standard normal
                zShiftSum += max(0.0, z - 0.85)
            }
            val samplePsiProxy = if (n > 0) min(1.5, zShiftSum / n.toDouble()) else 0.0
            recentZScoreMeans[cursor] = samplePsiProxy
            recentOodFlags[cursor] = (oodScore > oodThreshold)
            cursor = (cursor + 1) % windowCapacity
            if (count < windowCapacity) count++
            return getRollingPsiUnlocked()
        }
    }

    fun getRollingPsi(): Double = synchronized(lock) { getRollingPsiUnlocked() }

    fun getOodRatePct(): Double = synchronized(lock) {
        if (count == 0) return 0.0
        var oodCount = 0
        for (i in 0 until count) {
            if (recentOodFlags[i]) oodCount++
        }
        (oodCount.toDouble() / count.toDouble()) * 100.0
    }

    private fun getRollingPsiUnlocked(): Double {
        if (count == 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += recentZScoreMeans[i]
        return sum / count.toDouble()
    }
}
