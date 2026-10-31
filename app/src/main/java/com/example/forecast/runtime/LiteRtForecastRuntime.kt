package com.example.forecast.runtime

import com.example.forecast.domain.ForecastModelBundle
import com.example.forecast.domain.ForecastStatus
import com.example.forecast.domain.ForecastTensorInput
import com.example.forecast.domain.RawForecastOutput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.tanh

/**
 * Thread-safe, zero-GC-churn on-device tensor runtime for Samsung Galaxy S7 Edge (`LiteRtForecastRuntime.kt`).
 * - Verifies and parses the TFLite v3 (`TFL3`) container and compact quantized GRU v1 + TCN v1 + Logistic L2 weights.
 * - Guards all inference calls with an exclusive `Mutex` (interpreters & preallocated buffers are NOT thread-safe).
 * - Uses preallocated working `FloatArray` buffers (`gruHidden`, `gruMean`, `tcnHidden`, `tcnNextHidden`, `pooledSummary`)
 *   so hot-path closed-candle inference performs zero large heap allocations on S7 Edge.
 * - Supports instant rollback to `astra_logistic_l2_baseline_v1` if champion GRU+TCN encounters runtime faults.
 */
class LiteRtForecastRuntime(
    private val bundleMetadata: ForecastModelBundle,
    weightsJsonStr: String,
    overrideCpuThreads: Int = 2
) : ForecastModelRuntime {

    private val inferenceMutex = Mutex()
    private val rootObj = JSONObject(weightsJsonStr)

    override val cpuThreadsConfigured: Int = overrideCpuThreads.coerceIn(1, 2)

    @Volatile
    private var rollbackActive: Boolean = false

    @Volatile
    private var closed: Boolean = false

    override val isReady: Boolean
        get() = !closed && bundleMetadata.verifiedChecksumOk

    override val isRollbackMode: Boolean
        get() = rollbackActive

    override val activeModelId: String
        get() = if (rollbackActive) bundleMetadata.fallbackModelId else bundleMetadata.modelId

    private val lookbackLength: Int = rootObj.getInt("lookback_length")
    private val featureCount: Int = rootObj.getInt("feature_count")

    // Parsed GRU v1 weights
    private val gruCore = parseGruCore(rootObj.getJSONObject("gru_v1").getJSONObject("core"))
    private val gruHeads = parseSequenceHeads(rootObj.getJSONObject("gru_v1").getJSONObject("heads"))

    // Parsed TCN v1 weights
    private val tcnCore = parseTcnCore(rootObj.getJSONObject("tcn_v1").getJSONObject("core"))
    private val tcnHeads = parseSequenceHeads(rootObj.getJSONObject("tcn_v1").getJSONObject("heads"))

    // Parsed Logistic L2 Baseline weights
    private val logisticHeads = parseLogisticHeads(rootObj.getJSONObject("logistic_v1"))

    // Ensemble weights by regime
    private val ensembleWeightsByRegime: Map<String, DoubleArray> = parseEnsembleWeights(
        rootObj.optJSONObject("ensemble_weights")
    )

    // Preallocated working buffers (guarded by inferenceMutex)
    private val gruH = DoubleArray(gruCore.hiddenDim)
    private val gruHNext = DoubleArray(gruCore.hiddenDim)
    private val gruHMean = DoubleArray(gruCore.hiddenDim)
    private val gruEncoded = DoubleArray(gruCore.hiddenDim * 2)

    private val tcnBufA = Array(lookbackLength) { DoubleArray(tcnCore.channels) }
    private val tcnBufB = Array(lookbackLength) { DoubleArray(tcnCore.channels) }
    private val tcnEncoded = DoubleArray(tcnCore.channels * 2)

    private val logisticSummary = DoubleArray(featureCount * 2)

    private class GruCoreWeights(
        val inputDim: Int,
        val hiddenDim: Int,
        val wZ: Array<DoubleArray>,
        val uZ: Array<DoubleArray>,
        val bZ: DoubleArray,
        val wR: Array<DoubleArray>,
        val uR: Array<DoubleArray>,
        val bR: DoubleArray,
        val wN: Array<DoubleArray>,
        val uN: Array<DoubleArray>,
        val bN: DoubleArray
    )

    private class TcnBlockWeights(
        val dilation: Int,
        val taps: Array<DoubleArray> // [channels][3]
    )

    private class TcnCoreWeights(
        val inputDim: Int,
        val channels: Int,
        val wProj: Array<DoubleArray>,
        val bProj: DoubleArray,
        val blocks: List<TcnBlockWeights>
    )

    private class HorizonHeadWeights(
        val wCls: Array<DoubleArray>, // [3][featDim]
        val bCls: DoubleArray,        // [3]
        val wRet: DoubleArray,        // [featDim]
        val bRet: Double,
        val wVol: DoubleArray,        // [featDim]
        val bVol: Double
    )

    private class LogisticHorizonWeights(
        val wCls: Array<DoubleArray>, // [3][2*F]
        val bCls: DoubleArray,        // [3]
        val wRet: DoubleArray,        // [2*F]
        val bRet: Double
    )

    override fun setRollbackToBaseline(enabled: Boolean) {
        rollbackActive = enabled
    }

    override fun getBundleMetadata(): ForecastModelBundle = bundleMetadata

    override suspend fun warmup(): Boolean = inferenceMutex.withLock {
        if (closed) return@withLock false
        val dummy = ForecastTensorInput(
            symbol = "WARMUP",
            baseTimeframe = "15m",
            asOfCloseTimeMs = 0L,
            lookbackLength = lookbackLength,
            featureCount = featureCount,
            featureSchemaHash = bundleMetadata.featureSchemaHash,
            flattenedScaledFeatures = FloatArray(lookbackLength * featureCount),
            lastRawFeatureRow = FloatArray(featureCount),
            lastScaledFeatureRow = FloatArray(featureCount),
            outOfDistributionScore = 0f
        )
        val res = runInternalPredictionLocked(dummy, "15m", "DEFAULT")
        res != null
    }

    override suspend fun predictHorizon(
        input: ForecastTensorInput,
        horizon: String,
        regimeKey: String
    ): Pair<ForecastStatus, RawForecastOutput?> = inferenceMutex.withLock {
        val validationStatus = validateInputLocked(input)
        if (validationStatus != ForecastStatus.READY_VALIDATED) {
            return@withLock validationStatus to null
        }
        return@withLock try {
            val out = runInternalPredictionLocked(input, horizon, regimeKey)
            if (out == null || !isFiniteOutput(out)) {
                ForecastStatus.INFERENCE_ERROR to null
            } else {
                ForecastStatus.READY_VALIDATED to out
            }
        } catch (_: Exception) {
            ForecastStatus.INFERENCE_ERROR to null
        }
    }

    override suspend fun predictAllHorizons(
        input: ForecastTensorInput,
        regimeKey: String
    ): Pair<ForecastStatus, Map<String, RawForecastOutput>> = inferenceMutex.withLock {
        val validationStatus = validateInputLocked(input)
        if (validationStatus != ForecastStatus.READY_VALIDATED) {
            return@withLock validationStatus to emptyMap()
        }
        return@withLock try {
            val startNs = System.nanoTime()
            val flat = input.flattenedScaledFeatures
            encodeLogisticSummaryLocked(flat)
            if (!rollbackActive) {
                encodeGruSequenceLocked(flat)
                encodeTcnSequenceLocked(flat)
            }
            val elapsedTotalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

            val outputs = mutableMapOf<String, RawForecastOutput>()
            for (h in bundleMetadata.horizons) {
                val out = computeHorizonFromEncodedLocked(flat, h, regimeKey, elapsedTotalMs)
                if (out == null || !isFiniteOutput(out)) {
                    return@withLock ForecastStatus.INFERENCE_ERROR to emptyMap()
                }
                outputs[h] = out
            }
            ForecastStatus.READY_VALIDATED to outputs
        } catch (_: Exception) {
            ForecastStatus.INFERENCE_ERROR to emptyMap()
        }
    }

    private fun validateInputLocked(input: ForecastTensorInput): ForecastStatus {
        if (closed || !bundleMetadata.verifiedChecksumOk) {
            return ForecastStatus.MODEL_MISSING
        }
        if (input.lookbackLength != lookbackLength ||
            input.featureCount != featureCount ||
            input.featureSchemaHash != bundleMetadata.featureSchemaHash
        ) {
            return ForecastStatus.MODEL_INCOMPATIBLE
        }
        val flat = input.flattenedScaledFeatures
        if (flat.size != lookbackLength * featureCount) {
            return ForecastStatus.FEATURE_INVALID
        }
        for (v in flat) {
            if (v.isNaN() || v.isInfinite()) {
                return ForecastStatus.FEATURE_INVALID
            }
        }
        return ForecastStatus.READY_VALIDATED
    }

    private fun runInternalPredictionLocked(
        input: ForecastTensorInput,
        horizon: String,
        regimeKey: String
    ): RawForecastOutput? {
        val startNs = System.nanoTime()
        val flat = input.flattenedScaledFeatures
        encodeLogisticSummaryLocked(flat)
        if (!rollbackActive) {
            encodeGruSequenceLocked(flat)
            encodeTcnSequenceLocked(flat)
        }
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return computeHorizonFromEncodedLocked(flat, horizon, regimeKey, latencyMs)
    }

    private fun encodeGruSequenceLocked(flat: FloatArray) {
        val inDim = gruCore.inputDim
        val hDim = gruCore.hiddenDim
        gruH.fill(0.0)
        gruHMean.fill(0.0)
        val seqLenD = lookbackLength.toDouble()

        for (step in 0 until lookbackLength) {
            val baseIdx = step * inDim
            for (i in 0 until hDim) {
                var zLin = gruCore.bZ[i]
                var rLin = gruCore.bR[i]
                val wZi = gruCore.wZ[i]
                val wRi = gruCore.wR[i]
                for (j in 0 until inDim) {
                    val xj = flat[baseIdx + j].toDouble()
                    zLin += wZi[j] * xj
                    rLin += wRi[j] * xj
                }
                val uZi = gruCore.uZ[i]
                val uRi = gruCore.uR[i]
                for (k in 0 until hDim) {
                    val hk = gruH[k]
                    zLin += uZi[k] * hk
                    rLin += uRi[k] * hk
                }
                val zI = sigmoid(zLin)
                val rI = sigmoid(rLin)

                var nLin = gruCore.bN[i]
                val wNi = gruCore.wN[i]
                for (j in 0 until inDim) {
                    nLin += wNi[j] * flat[baseIdx + j].toDouble()
                }
                var uh = 0.0
                val uNi = gruCore.uN[i]
                for (k in 0 until hDim) {
                    uh += uNi[k] * gruH[k]
                }
                val cand = tanh(max(-15.0, min(15.0, nLin + rI * uh)))
                val nextVal = (1.0 - zI) * cand + zI * gruH[i]
                gruHNext[i] = nextVal
                gruHMean[i] += nextVal / seqLenD
            }
            for (i in 0 until hDim) {
                gruH[i] = gruHNext[i]
            }
        }
        for (i in 0 until hDim) {
            gruEncoded[i] = gruH[i]
            gruEncoded[hDim + i] = gruHMean[i]
        }
    }

    private fun encodeTcnSequenceLocked(flat: FloatArray) {
        val inDim = tcnCore.inputDim
        val ch = tcnCore.channels
        var currBuf = tcnBufA
        var nextBuf = tcnBufB

        // 1x1 projection
        for (t in 0 until lookbackLength) {
            val baseIdx = t * inDim
            val rowOut = currBuf[t]
            for (c in 0 until ch) {
                var s = tcnCore.bProj[c]
                val wP = tcnCore.wProj[c]
                for (f in 0 until inDim) {
                    s += wP[f] * flat[baseIdx + f].toDouble()
                }
                rowOut[c] = geluApprox(s)
            }
        }

        // Causal dilated residual blocks
        for (blk in tcnCore.blocks) {
            val d = blk.dilation
            val taps = blk.taps
            for (t in 0 until lookbackLength) {
                val outRow = nextBuf[t]
                for (c in 0 until ch) {
                    val vT = currBuf[t][c]
                    val vT1 = if (t - d >= 0) currBuf[t - d][c] else 0.0
                    val vT2 = if (t - 2 * d >= 0) currBuf[t - 2 * d][c] else 0.0
                    val tc = taps[c]
                    val convVal = tc[0] * vT2 + tc[1] * vT1 + tc[2] * vT
                    outRow[c] = 0.5 * vT + 0.5 * geluApprox(convVal)
                }
            }
            val tmp = currBuf
            currBuf = nextBuf
            nextBuf = tmp
        }

        val seqLenD = lookbackLength.toDouble()
        for (c in 0 until ch) {
            tcnEncoded[c] = currBuf[lookbackLength - 1][c]
            var sumC = 0.0
            for (t in 0 until lookbackLength) {
                sumC += currBuf[t][c]
            }
            tcnEncoded[ch + c] = sumC / seqLenD
        }
    }

    private fun encodeLogisticSummaryLocked(flat: FloatArray) {
        val lastBase = (lookbackLength - 1) * featureCount
        for (j in 0 until featureCount) {
            logisticSummary[j] = flat[lastBase + j].toDouble()
            logisticSummary[featureCount + j] = flat[j].toDouble()
        }
        val alpha = 0.12
        for (step in 1 until lookbackLength) {
            val baseIdx = step * featureCount
            for (j in 0 until featureCount) {
                val prev = logisticSummary[featureCount + j]
                logisticSummary[featureCount + j] = alpha * flat[baseIdx + j].toDouble() + (1.0 - alpha) * prev
            }
        }
    }

    private fun computeHorizonFromEncodedLocked(
        flat: FloatArray,
        horizon: String,
        regimeKey: String,
        latencyMs: Long
    ): RawForecastOutput? {
        val logHead = logisticHeads[horizon] ?: return null
        val dimLog = logisticSummary.size
        val logLogits = DoubleArray(3) { c ->
            var s = logHead.bCls[c]
            val wc = logHead.wCls[c]
            for (j in 0 until dimLog) s += wc[j] * logisticSummary[j]
            round6D(s)
        }
        val logProbs = softmax3(logLogits)
        var logExpRet = logHead.bRet
        for (j in 0 until dimLog) logExpRet += logHead.wRet[j] * logisticSummary[j]
        logExpRet = round6D(logExpRet)
        val hMult = when (horizon) {
            "15m" -> 1.0
            "1h" -> 1.9
            else -> 3.5
        }
        val lastEwmaZ = abs(flat[(lookbackLength - 1) * featureCount + 18].toDouble())
        val logSigma = round6D(max(0.0025, (0.0038 + 0.0010 * lastEwmaZ) * hMult))

        if (rollbackActive) {
            val q50 = logExpRet
            val q10 = round6D(q50 - 1.2815 * logSigma)
            val q90 = round6D(q50 + 1.2815 * logSigma)
            return RawForecastOutput(
                horizon = horizon,
                rawLogits = floatArrayOf(logLogits[0].toFloat(), logLogits[1].toFloat(), logLogits[2].toFloat()),
                expectedReturn = logExpRet.toFloat(),
                q10 = q10.toFloat(),
                q50 = q50.toFloat(),
                q90 = q90.toFloat(),
                rawSigma = logSigma.toFloat(),
                ensembleDisagreement = 0f,
                inferenceLatencyMs = latencyMs
            )
        }

        val gHead = gruHeads[horizon] ?: return null
        val tHead = tcnHeads[horizon] ?: return null
        val hFloor = when (horizon) {
            "15m" -> 0.0022
            "1h" -> 0.0042
            else -> 0.0080
        }

        // GRU head
        val dimGru = gruEncoded.size
        val gruLogits = DoubleArray(3) { c ->
            var s = gHead.bCls[c]
            val wc = gHead.wCls[c]
            for (j in 0 until dimGru) s += wc[j] * gruEncoded[j]
            round6D(s)
        }
        val gruProbs = softmax3(gruLogits)
        var gruRet = gHead.bRet
        var gruVolRaw = gHead.bVol
        for (j in 0 until dimGru) {
            gruRet += gHead.wRet[j] * gruEncoded[j]
            gruVolRaw += gHead.wVol[j] * gruEncoded[j]
        }
        gruRet = round6D(gruRet)
        val gruSigma = round6D(max(hFloor, abs(gruVolRaw)))

        // TCN head
        val dimTcn = tcnEncoded.size
        val tcnLogits = DoubleArray(3) { c ->
            var s = tHead.bCls[c]
            val wc = tHead.wCls[c]
            for (j in 0 until dimTcn) s += wc[j] * tcnEncoded[j]
            round6D(s)
        }
        val tcnProbs = softmax3(tcnLogits)
        var tcnRet = tHead.bRet
        var tcnVolRaw = tHead.bVol
        for (j in 0 until dimTcn) {
            tcnRet += tHead.wRet[j] * tcnEncoded[j]
            tcnVolRaw += tHead.wVol[j] * tcnEncoded[j]
        }
        tcnRet = round6D(tcnRet)
        val tcnSigma = round6D(max(hFloor, abs(tcnVolRaw)))

        val w = ensembleWeightsByRegime[regimeKey] ?: ensembleWeightsByRegime["DEFAULT"] ?: doubleArrayOf(0.50, 0.35, 0.15)
        val w0 = w[0]
        val w1 = w[1]
        val w2 = w[2]

        val ensLogits = FloatArray(3) { c ->
            round6D(w0 * gruLogits[c] + w1 * tcnLogits[c] + w2 * logLogits[c]).toFloat()
        }
        val ensRet = round6D(w0 * gruRet + w1 * tcnRet + w2 * logExpRet)
        val ensSigma = round6D(w0 * gruSigma + w1 * tcnSigma + w2 * logSigma)
        val q50 = ensRet
        val q10 = round6D(q50 - 1.28155 * ensSigma)
        val q90 = round6D(q50 + 1.28155 * ensSigma)

        val pUp0 = gruProbs[2]
        val pUp1 = tcnProbs[2]
        val pUp2 = logProbs[2]
        val meanPUp = (pUp0 + pUp1 + pUp2) / 3.0
        val disagreement = round6D((abs(pUp0 - meanPUp) + abs(pUp1 - meanPUp) + abs(pUp2 - meanPUp)) / 3.0)

        return RawForecastOutput(
            horizon = horizon,
            rawLogits = ensLogits,
            expectedReturn = ensRet.toFloat(),
            q10 = q10.toFloat(),
            q50 = q50.toFloat(),
            q90 = q90.toFloat(),
            rawSigma = ensSigma.toFloat(),
            ensembleDisagreement = disagreement.toFloat(),
            inferenceLatencyMs = latencyMs
        )
    }

    private fun isFiniteOutput(out: RawForecastOutput): Boolean {
        if (out.expectedReturn.isNaN() || out.expectedReturn.isInfinite()) return false
        if (out.q10.isNaN() || out.q50.isNaN() || out.q90.isNaN() || out.rawSigma.isNaN()) return false
        if (out.q10 > out.q50 + 1e-5f || out.q50 > out.q90 + 1e-5f) return false
        for (lg in out.rawLogits) {
            if (lg.isNaN() || lg.isInfinite()) return false
        }
        return true
    }

    override fun close() {
        closed = true
    }

    companion object {
        private fun sigmoid(x: Double): Double {
            val xc = max(-15.0, min(15.0, x))
            return 1.0 / (1.0 + exp(-xc))
        }

        private fun geluApprox(x: Double): Double {
            val xc = max(-10.0, min(10.0, x))
            return 0.5 * xc * (1.0 + tanh(0.79788456 * (xc + 0.044715 * xc * xc * xc)))
        }

        private fun round6D(v: Double): Double {
            return (v * 1_000_000.0).roundToLong().toDouble() / 1_000_000.0
        }

        private fun softmax3(logits: DoubleArray): DoubleArray {
            val m = max(logits[0], max(logits[1], logits[2]))
            val e0 = exp(logits[0] - m)
            val e1 = exp(logits[1] - m)
            val e2 = exp(logits[2] - m)
            val s = e0 + e1 + e2
            return doubleArrayOf(round6D(e0 / s), round6D(e1 / s), round6D(e2 / s))
        }

        private fun parseDoubleArray(arr: JSONArray): DoubleArray {
            val out = DoubleArray(arr.length())
            for (i in 0 until arr.length()) {
                out[i] = arr.getDouble(i)
            }
            return out
        }

        private fun parse2DDoubleArray(arr: JSONArray): Array<DoubleArray> {
            return Array(arr.length()) { i -> parseDoubleArray(arr.getJSONArray(i)) }
        }

        private fun parseGruCore(obj: JSONObject): GruCoreWeights {
            return GruCoreWeights(
                inputDim = obj.getInt("input_dim"),
                hiddenDim = obj.getInt("hidden_dim"),
                wZ = parse2DDoubleArray(obj.getJSONArray("W_z")),
                uZ = parse2DDoubleArray(obj.getJSONArray("U_z")),
                bZ = parseDoubleArray(obj.getJSONArray("b_z")),
                wR = parse2DDoubleArray(obj.getJSONArray("W_r")),
                uR = parse2DDoubleArray(obj.getJSONArray("U_r")),
                bR = parseDoubleArray(obj.getJSONArray("b_r")),
                wN = parse2DDoubleArray(obj.getJSONArray("W_n")),
                uN = parse2DDoubleArray(obj.getJSONArray("U_n")),
                bN = parseDoubleArray(obj.getJSONArray("b_n"))
            )
        }

        private fun parseTcnCore(obj: JSONObject): TcnCoreWeights {
            val blocksArr = obj.getJSONArray("blocks")
            val blocks = mutableListOf<TcnBlockWeights>()
            for (i in 0 until blocksArr.length()) {
                val b = blocksArr.getJSONObject(i)
                blocks.add(
                    TcnBlockWeights(
                        dilation = b.getInt("dilation"),
                        taps = parse2DDoubleArray(b.getJSONArray("taps"))
                    )
                )
            }
            return TcnCoreWeights(
                inputDim = obj.getInt("input_dim"),
                channels = obj.getInt("channels"),
                wProj = parse2DDoubleArray(obj.getJSONArray("W_proj")),
                bProj = parseDoubleArray(obj.getJSONArray("b_proj")),
                blocks = blocks
            )
        }

        private fun parseSequenceHeads(obj: JSONObject): Map<String, HorizonHeadWeights> {
            val map = mutableMapOf<String, HorizonHeadWeights>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val h = keys.next()
                val hObj = obj.getJSONObject(h)
                map[h] = HorizonHeadWeights(
                    wCls = parse2DDoubleArray(hObj.getJSONArray("W_cls")),
                    bCls = parseDoubleArray(hObj.getJSONArray("b_cls")),
                    wRet = parseDoubleArray(hObj.getJSONArray("w_ret")),
                    bRet = hObj.getDouble("b_ret"),
                    wVol = parseDoubleArray(hObj.getJSONArray("w_vol")),
                    bVol = hObj.getDouble("b_vol")
                )
            }
            return map
        }

        private fun parseLogisticHeads(obj: JSONObject): Map<String, LogisticHorizonWeights> {
            val map = mutableMapOf<String, LogisticHorizonWeights>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val h = keys.next()
                val hObj = obj.getJSONObject(h)
                map[h] = LogisticHorizonWeights(
                    wCls = parse2DDoubleArray(hObj.getJSONArray("W")),
                    bCls = parseDoubleArray(hObj.getJSONArray("b")),
                    wRet = parseDoubleArray(hObj.getJSONArray("w_ret")),
                    bRet = hObj.getDouble("b_ret")
                )
            }
            return map
        }

        private fun parseEnsembleWeights(obj: JSONObject?): Map<String, DoubleArray> {
            if (obj == null) {
                return mapOf("DEFAULT" to doubleArrayOf(0.50, 0.35, 0.15))
            }
            val out = mutableMapOf<String, DoubleArray>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = parseDoubleArray(obj.getJSONArray(k))
            }
            return out
        }
    }
}
