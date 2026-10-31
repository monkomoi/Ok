package com.example.forecast.engine

import com.example.forecast.domain.ForecastResult
import com.example.forecast.domain.ForecastStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EnsembleTradeSignalDecision(
    val symbol: String,
    val ruleSignalSide: String, // "LONG", "SHORT", "NONE"
    val ruleSignalScore: Double,
    val forecastDirectionScore: Double,
    val forecastConfidence: Double,
    val combinedConvictionScore: Double,
    val approvedForExecution: Boolean,
    val effectiveRiskMultiplier: Double,
    val decisionRationale: String
)

/**
 * Combines deterministic rule-based technical regime signals with calibrated multi-horizon
 * ML forecasts according to the configured [ForecastOperatingMode].
 */
class ForecastEnsemble {
    fun combineRuleAndForecast(
        symbol: String,
        ruleSide: String,
        ruleScore: Double, // [0, 100]
        forecast: ForecastResult?
    ): EnsembleTradeSignalDecision {
        if (ruleSide == "NONE" || ruleScore < 60.0) {
            return EnsembleTradeSignalDecision(
                symbol = symbol,
                ruleSignalSide = "NONE",
                ruleSignalScore = ruleScore,
                forecastDirectionScore = forecast?.compositeDirectionalScore ?: 0.0,
                forecastConfidence = forecast?.compositeConfidence ?: 0.0,
                combinedConvictionScore = ruleScore,
                approvedForExecution = false,
                effectiveRiskMultiplier = 0.0,
                decisionRationale = "Rule signal below entry threshold (${"%.1f".format(ruleScore)} < 60.0)"
            )
        }

        if (forecast == null) {
            return EnsembleTradeSignalDecision(
                symbol = symbol,
                ruleSignalSide = ruleSide,
                ruleSignalScore = ruleScore,
                forecastDirectionScore = 0.0,
                forecastConfidence = 0.0,
                combinedConvictionScore = ruleScore,
                approvedForExecution = true,
                effectiveRiskMultiplier = 1.0,
                decisionRationale = "No active forecast bundle; executing deterministic rule baseline"
            )
        }

        val ruleSign = if (ruleSide == "LONG") 1.0 else -1.0
        val forecastSign = if (forecast.compositeDirectionalScore >= 0.0) 1.0 else -1.0
        val directionAligned = (ruleSign * forecastSign) > 0.0 && abs(forecast.compositeDirectionalScore) >= 0.08

        return when (forecast.operatingMode) {
            com.example.forecast.domain.ForecastOperatingMode.DISABLED,
            com.example.forecast.domain.ForecastOperatingMode.SHADOW -> {
                EnsembleTradeSignalDecision(
                    symbol = symbol,
                    ruleSignalSide = ruleSide,
                    ruleSignalScore = ruleScore,
                    forecastDirectionScore = forecast.compositeDirectionalScore,
                    forecastConfidence = forecast.compositeConfidence,
                    combinedConvictionScore = ruleScore,
                    approvedForExecution = true,
                    effectiveRiskMultiplier = 1.0,
                    decisionRationale = "Shadow/Disabled mode: rule baseline active (ML status=${forecast.status}, dir=${"%.2f".format(forecast.compositeDirectionalScore)})"
                )
            }

            com.example.forecast.domain.ForecastOperatingMode.GATED_ENFORCEMENT -> {
                val allowed = forecast.status == ForecastStatus.VALID &&
                    forecast.headConsistent &&
                    forecast.allowTradeExecution &&
                    directionAligned
                val combinedScore = if (allowed) {
                    min(100.0, 0.60 * ruleScore + 40.0 * forecast.compositeConfidence)
                } else {
                    ruleScore * 0.5
                }
                EnsembleTradeSignalDecision(
                    symbol = symbol,
                    ruleSignalSide = if (allowed) ruleSide else "NONE",
                    ruleSignalScore = ruleScore,
                    forecastDirectionScore = forecast.compositeDirectionalScore,
                    forecastConfidence = forecast.compositeConfidence,
                    combinedConvictionScore = combinedScore,
                    approvedForExecution = allowed,
                    effectiveRiskMultiplier = if (allowed) 1.0 else 0.0,
                    decisionRationale = if (allowed) {
                        "Gated ML enforcement approved $ruleSide (conf=${"%.2f".format(forecast.compositeConfidence)})"
                    } else {
                        "Gated ML enforcement blocked $ruleSide: status=${forecast.status} (${forecast.statusReason}), aligned=$directionAligned"
                    }
                )
            }

            com.example.forecast.domain.ForecastOperatingMode.ADAPTIVE_SIZING -> {
                val allowed = forecast.status == ForecastStatus.VALID &&
                    forecast.headConsistent &&
                    forecast.allowTradeExecution &&
                    directionAligned
                val mult = if (allowed) max(0.50, min(1.00, forecast.positionSizingMultiplier)) else 0.0
                val combinedScore = if (allowed) {
                    min(100.0, 0.55 * ruleScore + 45.0 * forecast.compositeConfidence)
                } else {
                    ruleScore * 0.5
                }
                EnsembleTradeSignalDecision(
                    symbol = symbol,
                    ruleSignalSide = if (allowed) ruleSide else "NONE",
                    ruleSignalScore = ruleScore,
                    forecastDirectionScore = forecast.compositeDirectionalScore,
                    forecastConfidence = forecast.compositeConfidence,
                    combinedConvictionScore = combinedScore,
                    approvedForExecution = allowed,
                    effectiveRiskMultiplier = mult,
                    decisionRationale = if (allowed) {
                        "Adaptive ML sizing approved $ruleSide with risk multiplier ${"%.2f".format(mult)}x"
                    } else {
                        "Adaptive ML sizing blocked $ruleSide: status=${forecast.status} (${forecast.statusReason})"
                    }
                )
            }
        }
    }
}
