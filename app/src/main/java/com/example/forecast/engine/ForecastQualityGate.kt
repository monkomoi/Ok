package com.example.forecast.engine

import com.example.domain.TradeSide
import com.example.forecast.domain.ForecastOperatingMode
import com.example.forecast.domain.ForecastStatus
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Cost-Aware Forecast Quality Gate (`ForecastQualityGate.kt`, Section 6.2 & 12).
 * - Computes round-trip cost threshold `tau_cost(H)` (`2 * fee + spread + slippage + funding + safetyMargin`).
 * - Verifies calibration status, OOD score, uncertainty, and cost-adjusted edge.
 * - Never triggers an order on its own; only produces advisory verdicts (`ALLOW_CANDIDATE`, `ABSTAIN_NO_EDGE`,
 *   `VETO_OPPOSITE_DIRECTION`, `SHADOW_OBSERVE_ONLY`).
 */
object ForecastQualityGate {

    data class QualityGateDecision(
        val finalStatus: ForecastStatus,
        val passesQualityGate: Boolean,
        val costThresholdPct: BigDecimal,
        val advisoryAction: String,
        val reasonVi: String
    )

    fun computeCostThresholdPct(
        horizon: String,
        spreadPct: BigDecimal = BigDecimal("0.0004"),
        takerFeeRate: BigDecimal = BigDecimal("0.0005"),
        slippageRate: BigDecimal = BigDecimal("0.0004"),
        fundingRatePer8h: BigDecimal = BigDecimal("0.0001"),
        safetyMargin: BigDecimal = BigDecimal("0.0004")
    ): BigDecimal {
        val bars15m = when (horizon) {
            "15m" -> BigDecimal.ONE
            "1h" -> BigDecimal("4")
            else -> BigDecimal("16")
        }
        val horizonScale = when (horizon) {
            "15m" -> BigDecimal.ONE
            "1h" -> BigDecimal("1.35")
            else -> BigDecimal("1.85")
        }
        val fundingEst = fundingRatePer8h.abs()
            .multiply(bars15m)
            .divide(BigDecimal("32"), 6, RoundingMode.HALF_UP)
        val baseCost = takerFeeRate.multiply(BigDecimal("2"))
            .add(spreadPct)
            .add(slippageRate)
            .add(fundingEst)
        val totalFraction = baseCost.add(safetyMargin.multiply(horizonScale))
        // Return in percentage points (e.g. 0.0018 -> 0.1800%)
        return totalFraction.multiply(BigDecimal("100")).setScale(4, RoundingMode.HALF_UP)
    }

    fun evaluate(
        horizon: String,
        operatingMode: ForecastOperatingMode,
        driftEval: ForecastDriftMonitor.DriftEvaluation,
        calibrationDist: ForecastCalibrationEngine.CalibratedDistribution,
        expectedReturnPct: BigDecimal,
        costThresholdPct: BigDecimal,
        candidateSide: TradeSide = TradeSide.NONE
    ): QualityGateDecision {
        if (driftEval.isOutOfDistribution) {
            return QualityGateDecision(
                finalStatus = ForecastStatus.OUT_OF_DISTRIBUTION,
                passesQualityGate = false,
                costThresholdPct = costThresholdPct,
                advisoryAction = "ABSTAIN_NO_EDGE",
                reasonVi = driftEval.noteVi
            )
        }

        if (!calibrationDist.calibrationApplied) {
            return QualityGateDecision(
                finalStatus = ForecastStatus.UNCALIBRATED,
                passesQualityGate = false,
                costThresholdPct = costThresholdPct,
                advisoryAction = "SHADOW_OBSERVE_ONLY",
                reasonVi = "Dự báo ở trạng thái UNCALIBRATED (N=${calibrationDist.sampleSize}, Method=${calibrationDist.method}) -> chỉ ghi nhận Shadow, không dùng để lọc lệnh."
            )
        }

        val maxUncertainty = BigDecimal("0.82000")
        if (calibrationDist.uncertainty > maxUncertainty) {
            return QualityGateDecision(
                finalStatus = ForecastStatus.READY_VALIDATED,
                passesQualityGate = false,
                costThresholdPct = costThresholdPct,
                advisoryAction = "ABSTAIN_NO_EDGE",
                reasonVi = "Độ bất định dự báo cao (Uncertainty=${calibrationDist.uncertainty} > $maxUncertainty) -> Khuyến nghị đứng ngoài (ABSTAIN)."
            )
        }

        val pUp = calibrationDist.pUp
        val pDown = calibrationDist.pDown
        val minDirProb = BigDecimal("0.42000")
        val minReturnEdgePct = costThresholdPct.multiply(BigDecimal("0.60")).setScale(4, RoundingMode.HALF_UP)

        val hasBullishEdge = pUp >= minDirProb && expectedReturnPct >= minReturnEdgePct
        val hasBearishEdge = pDown >= minDirProb && expectedReturnPct <= minReturnEdgePct.negate()

        if (operatingMode == ForecastOperatingMode.SHADOW) {
            return QualityGateDecision(
                finalStatus = ForecastStatus.READY_VALIDATED,
                passesQualityGate = hasBullishEdge || hasBearishEdge,
                costThresholdPct = costThresholdPct,
                advisoryAction = "SHADOW_OBSERVE_ONLY",
                reasonVi = "Chế độ SHADOW (${horizon}): pUp=${pUp.setScale(3, RoundingMode.HALF_UP)}, pDown=${pDown.setScale(3, RoundingMode.HALF_UP)}, E[r]=${expectedReturnPct.setScale(3, RoundingMode.HALF_UP)}% vs Cost=${costThresholdPct}%."
            )
        }

        // ADVISORY_TEST_ONLY mode
        return when {
            candidateSide == TradeSide.LONG && pDown > pUp.add(BigDecimal("0.12")) -> QualityGateDecision(
                finalStatus = ForecastStatus.READY_VALIDATED,
                passesQualityGate = false,
                costThresholdPct = costThresholdPct,
                advisoryAction = "VETO_OPPOSITE_DIRECTION",
                reasonVi = "Advisory Veto ($horizon): Ứng viên LONG mâu thuẫn với xác suất giảm pDown=${pDown.setScale(3, RoundingMode.HALF_UP)} > pUp=${pUp.setScale(3, RoundingMode.HALF_UP)}."
            )
            candidateSide == TradeSide.SHORT && pUp > pDown.add(BigDecimal("0.12")) -> QualityGateDecision(
                finalStatus = ForecastStatus.READY_VALIDATED,
                passesQualityGate = false,
                costThresholdPct = costThresholdPct,
                advisoryAction = "VETO_OPPOSITE_DIRECTION",
                reasonVi = "Advisory Veto ($horizon): Ứng viên SHORT mâu thuẫn với xác suất tăng pUp=${pUp.setScale(3, RoundingMode.HALF_UP)} > pDown=${pDown.setScale(3, RoundingMode.HALF_UP)}."
            )
            hasBullishEdge || hasBearishEdge -> QualityGateDecision(
                finalStatus = ForecastStatus.READY_VALIDATED,
                passesQualityGate = true,
                costThresholdPct = costThresholdPct,
                advisoryAction = "ALLOW_CANDIDATE",
                reasonVi = "Đạt ngưỡng biên lợi nhuận sau chi phí ($horizon): E[r]=${expectedReturnPct.setScale(3, RoundingMode.HALF_UP)}% vượt ngưỡng ${minReturnEdgePct}%."
            )
            else -> QualityGateDecision(
                finalStatus = ForecastStatus.READY_VALIDATED,
                passesQualityGate = false,
                costThresholdPct = costThresholdPct,
                advisoryAction = "ABSTAIN_NO_EDGE",
                reasonVi = "Biên lợi nhuận kỳ vọng ($horizon: E[r]=${expectedReturnPct.setScale(3, RoundingMode.HALF_UP)}%) chưa vượt ngưỡng chi phí ${minReturnEdgePct}% -> ABSTAIN."
            )
        }
    }
}
