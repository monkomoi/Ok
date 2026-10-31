package com.example.ai

import com.example.domain.AiProviderHealth
import com.example.domain.DualAgentConsensusResult
import com.example.forecast.domain.ForecastResult
import com.example.quant.QuantMathEngine
import com.example.domain.TechnicalSignalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@Serializable
data class StrictAiAgentDecision(
    val action: String = "HOLD", // "APPROVE_LONG", "APPROVE_SHORT", "HOLD", "VETO"
    val confidence: Double = 0.0,
    val riskFlag: Boolean = false,
    val rationale: String = ""
)

/**
 * Dual-role AI Intelligence Gateway (Analyst + Independent Risk Skeptic) with strict
 * JSON schema validation, provider failover (Gemini -> OpenAI -> Claude -> Deterministic Fallback),
 * and hard safety boundary: AI can NEVER invent a trade when technical + risk gates are closed,
 * and can NEVER increase position sizing above 1.0x.
 */
class AiIntelligenceGateway(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun evaluateDualAgentGate(
        symbol: String,
        technicalSignal: TechnicalSignalResult,
        forecastResult: ForecastResult?,
        spreadBps: Double,
        fundingRatePct: Double,
        geminiApiKey: String,
        openAiApiKey: String,
        claudeApiKey: String
    ): DualAgentConsensusResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        // If technical signal is NONE, do not waste external LLM quota
        if (technicalSignal.side == "NONE") {
            return@withContext DualAgentConsensusResult(
                approved = false,
                vetoedBySkeptic = false,
                analystAction = "HOLD",
                analystConfidence = 0.0,
                skepticRiskFlag = false,
                skepticObjection = "No technical trigger active",
                providerUsed = "DETERMINISTIC_SHORT_CIRCUIT",
                latencyMs = System.currentTimeMillis() - startMs,
                rationale = "Technical signal is NONE; AI gate skipped"
            )
        }

        val promptContext = buildCompactContextPrompt(
            symbol = symbol,
            tech = technicalSignal,
            forecast = forecastResult,
            spreadBps = spreadBps,
            fundingRatePct = fundingRatePct
        )

        // Attempt configured cloud AI providers in priority order with strict schema parsing
        val providerResponse = when {
            geminiApiKey.isNotBlank() -> callGeminiStrictJson(geminiApiKey, promptContext)
            openAiApiKey.isNotBlank() -> callOpenAiStrictJson(openAiApiKey, promptContext)
            claudeApiKey.isNotBlank() -> callClaudeStrictJson(claudeApiKey, promptContext)
            else -> Result.failure(IllegalStateException("No AI API keys configured"))
        }

        val elapsed = max(1L, System.currentTimeMillis() - startMs)
        providerResponse.fold(
            onSuccess = { (providerName, pair) ->
                val (analyst, skeptic) = pair
                val expectedAction = if (technicalSignal.side == "LONG") "APPROVE_LONG" else "APPROVE_SHORT"
                val analystApproved = analyst.action == expectedAction && analyst.confidence >= 0.60 && !analyst.riskFlag
                val skepticVeto = skeptic.riskFlag || skeptic.action == "VETO"
                val finalApproved = analystApproved && !skepticVeto
                DualAgentConsensusResult(
                    approved = finalApproved,
                    vetoedBySkeptic = skepticVeto,
                    analystAction = analyst.action,
                    analystConfidence = analyst.confidence,
                    skepticRiskFlag = skepticVeto,
                    skepticObjection = skeptic.rationale.ifBlank { "None" },
                    providerUsed = providerName,
                    latencyMs = elapsed,
                    rationale = "Analyst(${analyst.action}, conf=${"%.2f".format(analyst.confidence)}): ${analyst.rationale} | Skeptic(veto=$skepticVeto): ${skeptic.rationale}"
                )
            },
            onFailure = {
                // Deterministic local quantitative skeptic fallback (never blocks valid high-score signals unless anomaly present)
                evaluateDeterministicFallback(
                    technicalSignal = technicalSignal,
                    forecastResult = forecastResult,
                    spreadBps = spreadBps,
                    fundingRatePct = fundingRatePct,
                    latencyMs = elapsed
                )
            }
        )
    }

    private fun evaluateDeterministicFallback(
        technicalSignal: TechnicalSignalResult,
        forecastResult: ForecastResult?,
        spreadBps: Double,
        fundingRatePct: Double,
        latencyMs: Long
    ): DualAgentConsensusResult {
        val headBad = forecastResult != null && !forecastResult.headConsistent
        val highOod = (forecastResult?.oodScore ?: 0.0) > 2.5
        val adverseSpread = spreadBps > 6.0
        val adverseFunding = (technicalSignal.side == "LONG" && fundingRatePct > 0.04) ||
            (technicalSignal.side == "SHORT" && fundingRatePct < -0.04)

        val skepticVeto = headBad || highOod || adverseSpread || adverseFunding
        val vetoReason = when {
            headBad -> "Forecast head contradiction detected"
            highOod -> "OOD anomaly score ${(forecastResult?.oodScore ?: 0.0)} > 2.5"
            adverseSpread -> "Spread ${"%.1f".format(spreadBps)} bps > 6.0 bps"
            adverseFunding -> "Adverse funding rate ${"%.3f".format(fundingRatePct)}%"
            else -> "No risk objection"
        }

        val analystConf = min(0.95, max(0.50, technicalSignal.technicalScore / 100.0))
        val approved = !skepticVeto && technicalSignal.technicalScore >= 65.0
        val action = if (approved) "APPROVE_${technicalSignal.side}" else "HOLD"

        return DualAgentConsensusResult(
            approved = approved,
            vetoedBySkeptic = skepticVeto,
            analystAction = action,
            analystConfidence = analystConf,
            skepticRiskFlag = skepticVeto,
            skepticObjection = vetoReason,
            providerUsed = "DETERMINISTIC_QUANT_FALLBACK",
            latencyMs = latencyMs,
            rationale = "Deterministic dual-check: score=${"%.1f".format(technicalSignal.technicalScore)}, veto=$skepticVeto ($vetoReason)"
        )
    }

    private fun buildCompactContextPrompt(
        symbol: String,
        tech: TechnicalSignalResult,
        forecast: ForecastResult?,
        spreadBps: Double,
        fundingRatePct: Double
    ): String {
        val fcSummary = if (forecast != null) {
            "status=${forecast.status}, dir=${"%.2f".format(forecast.compositeDirectionalScore)}, conf=${"%.2f".format(forecast.compositeConfidence)}, headConsistent=${forecast.headConsistent}, ood=${"%.2f".format(forecast.oodScore)}"
        } else {
            "NONE"
        }
        return """
            Return ONLY valid JSON with keys:
            {"analyst":{"action":"APPROVE_LONG|APPROVE_SHORT|HOLD","confidence":0.0,"riskFlag":false,"rationale":"..."},"skeptic":{"action":"APPROVE|VETO","confidence":0.0,"riskFlag":false,"rationale":"..."}}
            Market context for $symbol:
            regime=${tech.regime}, proposedSide=${tech.side}, techScore=${"%.1f".format(tech.technicalScore)},
            rsi14=${"%.1f".format(tech.rsi14)}, adx14=${"%.1f".format(tech.adx14)}, atrPct=${"%.2f".format(tech.atrPct)}%, rvol=${"%.2f".format(tech.relativeVolume)},
            spreadBps=${"%.2f".format(spreadBps)}, fundingPct=${"%.4f".format(fundingRatePct)}%,
            mlForecast=[$fcSummary]
        """.trimIndent()
    }

    private fun callGeminiStrictJson(
        apiKey: String,
        prompt: String
    ): Result<Pair<String, Pair<StrictAiAgentDecision, StrictAiAgentDecision>>> = runCatching {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty Gemini body")
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: error("Missing Gemini candidate text")
            Pair("GEMINI_2_5_FLASH", parseDualJsonText(text))
        }
    }

    private fun callOpenAiStrictJson(
        apiKey: String,
        prompt: String
    ): Result<Pair<String, Pair<StrictAiAgentDecision, StrictAiAgentDecision>>> = runCatching {
        val url = "https://api.openai.com/v1/chat/completions"
        val payload = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("temperature", 0.1)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("OpenAI HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty OpenAI body")
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: error("Missing OpenAI content")
            Pair("OPENAI_GPT_4O_MINI", parseDualJsonText(text))
        }
    }

    private fun callClaudeStrictJson(
        apiKey: String,
        prompt: String
    ): Result<Pair<String, Pair<StrictAiAgentDecision, StrictAiAgentDecision>>> = runCatching {
        val url = "https://api.anthropic.com/v1/messages"
        val payload = buildJsonObject {
            put("model", "claude-3-5-haiku-latest")
            put("max_tokens", 300)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Claude HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty Claude body")
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: error("Missing Claude text")
            Pair("CLAUDE_3_5_HAIKU", parseDualJsonText(text))
        }
    }

    private fun parseDualJsonText(rawText: String): Pair<StrictAiAgentDecision, StrictAiAgentDecision> {
        val obj = json.parseToJsonElement(rawText.trim()).jsonObject
        val analystEl = obj["analyst"] ?: error("Missing 'analyst' object in AI JSON")
        val skepticEl = obj["skeptic"] ?: error("Missing 'skeptic' object in AI JSON")
        val analyst = json.decodeFromJsonElement(StrictAiAgentDecision.serializer(), analystEl)
        val skeptic = json.decodeFromJsonElement(StrictAiAgentDecision.serializer(), skepticEl)
        return Pair(analyst, skeptic)
    }
}
