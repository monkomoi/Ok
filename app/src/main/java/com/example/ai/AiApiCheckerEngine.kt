package com.example.ai

import com.example.BuildConfig
import com.example.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AiPresetProvider(
    val key: String,
    val name: String,
    val baseUrl: String,
    val envKey: String = "",
    val protocol: String = "openai",
    val note: String = "",
    val defaultSuggestedModel: String = ""
)

data class AiChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String,
    val latencySec: Double? = null,
    val usageSummary: String = "",
    val isCommandOutput: Boolean = false
)

data class AiModelListResult(
    val ok: Boolean,
    val models: List<String>,
    val errorMessage: String = "",
    val elapsedSec: Double = 0.0,
    val endpointCalled: String = ""
)

data class AiChatCallResult(
    val model: String,
    val ok: Boolean,
    val status: Int,
    val elapsedSec: Double,
    val text: String,
    val usage: String,
    val error: String,
    val explanationVi: String,
    val endpointCalled: String = ""
)

data class AiCheckerSuiteReport(
    val toolVersion: String = AiApiCheckerEngine.VERSION,
    val providerKey: String,
    val providerName: String,
    val protocol: String,
    val baseUrl: String,
    val timestamp: String,
    val results: List<AiChatCallResult>
) {
    val passedCount: Int get() = results.count { it.ok }
    val totalCount: Int get() = results.size
    val allPassed: Boolean get() = totalCount > 0 && passedCount == totalCount

    fun toJsonReportString(): String {
        val root = JSONObject()
        root.put("tool_version", toolVersion)
        root.put("provider", providerKey)
        root.put("provider_name", providerName)
        root.put("protocol", protocol)
        root.put("base_url", baseUrl)
        root.put("timestamp", timestamp)
        val arr = JSONArray()
        for (r in results) {
            arr.put(
                JSONObject().apply {
                    put("model", r.model)
                    put("ok", r.ok)
                    put("http_status", r.status)
                    put("latency_seconds", Math.round(r.elapsedSec * 1000.0) / 1000.0)
                    put("response", r.text)
                    put("usage", r.usage)
                    put("error", r.error)
                    put("explanation_vi", r.explanationVi)
                }
            )
        }
        root.put("results", arr)
        return root.toString(2)
    }
}

/**
 * Kotlin/Android port of `ai_api_checker.py` (v1.1.0).
 * Supports:
 * - 22+ Preset Providers + Custom Endpoint
 * - 3 API Protocols: `openai` (OpenAI-compatible), `anthropic` (Anthropic Messages API), `gemini` (Google Gemini REST API)
 * - Model discovery (`/models`, `/v1/models`, `/v1beta/models`)
 * - Single & Batch (`--test-all`) model verification with latency, token usage, and HTTP error diagnosis
 * - Multi-turn interactive chat session with `/help`, `/reset`, `/model`, `/system`, `/save` commands
 * - Zero plaintext key leakage in logs, transcripts, or exported JSON reports
 */
class AiApiCheckerEngine(
    private val secretVault: SecretVault,
    private val baseHttpClient: OkHttpClient
) {

    fun slotForProvider(providerKey: String): String {
        return when (providerKey.lowercase(Locale.ROOT)) {
            "gemini" -> SecretVault.SLOT_AI_PRIMARY_KEY
            "groq" -> SecretVault.SLOT_AI_SKEPTIC_KEY
            else -> "ai_provider_key_${providerKey.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]+"), "_")}"
        }
    }

    /**
     * P1-3.7 & P1-3.8 · Strict per-provider API key isolation.
     * NEVER falls back to another provider's slot (`SLOT_AI_PRIMARY_KEY`), preventing accidental
     * transmission of a Gemini/Primary API key in `Authorization: Bearer ...` headers to third-party endpoints.
     */
    fun resolveApiKey(providerKey: String, noAuth: Boolean): String {
        if (noAuth) return ""
        val slot = slotForProvider(providerKey)
        val fromProviderSlot = secretVault.getSecret(slot)
        if (!fromProviderSlot.isNullOrBlank()) return fromProviderSlot
        return ""
    }

    fun hasConfiguredKey(providerKey: String, noAuth: Boolean): Boolean {
        if (noAuth) return true
        return resolveApiKey(providerKey, noAuth = false).isNotBlank()
    }

    /**
     * P1-3.7 · Saves the API key strictly into the target provider's dedicated Vault slot.
     * Never overwrites `SLOT_AI_PRIMARY_KEY` when saving a non-Gemini provider's key.
     */
    fun saveApiKeyForProvider(providerKey: String, rawApiKey: String): Boolean {
        val clean = SecretVault.sanitizeCredentialInput(rawApiKey)
        val slot = slotForProvider(providerKey)
        if (clean.isBlank()) {
            secretVault.removeSecret(slot)
            return false
        }
        return secretVault.putSecret(slot, clean)
    }

    fun getMaskedKeyPreview(providerKey: String, noAuth: Boolean): String {
        if (noAuth) return "Không dùng key (--no-auth)"
        val slot = slotForProvider(providerKey)
        if (secretVault.hasSecret(slot)) {
            return secretVault.getMaskedPreview(slot)
        }
        return "Chưa cấu hình"
    }

    private fun clientWithTimeout(timeoutSec: Int): OkHttpClient {
        val sec = timeoutSec.coerceIn(3, 180).toLong()
        return baseHttpClient.newBuilder()
            .connectTimeout(sec, TimeUnit.SECONDS)
            .readTimeout(sec, TimeUnit.SECONDS)
            .writeTimeout(sec, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun listModels(
        protocol: String,
        baseUrl: String,
        apiKey: String,
        noAuth: Boolean = false,
        timeoutSec: Int = 30
    ): AiModelListResult = withContext(Dispatchers.IO) {
        val normProtocol = normalizeProtocol(protocol)
        val cleanUrl = try {
            cleanBaseUrl(baseUrl)
        } catch (e: Exception) {
            return@withContext AiModelListResult(
                ok = false,
                models = emptyList(),
                errorMessage = e.message ?: "Base URL không hợp lệ."
            )
        }

        val effectiveKey = if (noAuth) "" else apiKey.trim()
        val headers = authHeaders(normProtocol, effectiveKey)
        val url = when (normProtocol) {
            "gemini" -> "$cleanUrl/v1beta/models"
            "anthropic" -> if (cleanUrl.endsWith("/v1")) "$cleanUrl/models" else "$cleanUrl/v1/models"
            else -> "$cleanUrl/models"
        }

        val t0 = System.nanoTime()
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "AI-API-Checker/$VERSION")
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            clientWithTimeout(timeoutSec).newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
                val data = parseJsonSafely(raw)
                if (resp.code !in 200..299) {
                    val errMsg = errorMessage(data)
                    val explain = explainHttpError(resp.code, errMsg)
                    return@withContext AiModelListResult(
                        ok = false,
                        models = emptyList(),
                        errorMessage = "HTTP ${resp.code}: $errMsg ($explain)",
                        elapsedSec = elapsed,
                        endpointCalled = url
                    )
                }

                val rawItems = data.optJSONArray("data") ?: data.optJSONArray("models") ?: JSONArray()
                val modelIds = mutableSetOf<String>()
                for (i in 0 until rawItems.length()) {
                    val item = rawItems.opt(i)
                    when (item) {
                        is String -> if (item.isNotBlank()) modelIds.add(item.trim())
                        is JSONObject -> {
                            val id = item.optString("id").ifBlank {
                                item.optString("name").ifBlank { item.optString("model") }
                            }
                            if (normProtocol == "gemini") {
                                val methods = item.optJSONArray("supportedGenerationMethods")
                                if (methods != null && methods.length() > 0) {
                                    var supportsGenerate = false
                                    for (m in 0 until methods.length()) {
                                        if (methods.optString(m) == "generateContent") {
                                            supportsGenerate = true
                                            break
                                        }
                                    }
                                    if (!supportsGenerate) continue
                                }
                            }
                            if (id.isNotBlank()) {
                                modelIds.add(if (normProtocol == "gemini") id.removePrefix("models/") else id)
                            }
                        }
                    }
                }
                val sorted = modelIds.sortedBy { it.lowercase(Locale.ROOT) }
                if (sorted.isEmpty()) {
                    return@withContext AiModelListResult(
                        ok = false,
                        models = emptyList(),
                        errorMessage = "API list-models trả về thành công nhưng không có model khả dụng.",
                        elapsedSec = elapsed,
                        endpointCalled = url
                    )
                }
                AiModelListResult(
                    ok = true,
                    models = sorted,
                    elapsedSec = elapsed,
                    endpointCalled = url
                )
            }
        } catch (e: Exception) {
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            AiModelListResult(
                ok = false,
                models = emptyList(),
                errorMessage = "Không lấy được danh sách model: ${e.javaClass.simpleName}: ${e.message}",
                elapsedSec = elapsed,
                endpointCalled = url
            )
        }
    }

    suspend fun chatRequest(
        protocol: String,
        baseUrl: String,
        apiKey: String,
        noAuth: Boolean = false,
        model: String,
        messages: List<AiChatMessage>,
        maxTokens: Int = 32,
        timeoutSec: Int = 60
    ): AiChatCallResult = withContext(Dispatchers.IO) {
        val normProtocol = normalizeProtocol(protocol)
        val cleanModel = model.trim()
        if (cleanModel.isBlank()) {
            return@withContext AiChatCallResult(
                model = "",
                ok = false,
                status = 0,
                elapsedSec = 0.0,
                text = "",
                usage = "",
                error = "Chưa nhập Model ID.",
                explanationVi = "Hãy chọn một model từ danh sách hoặc nhập Model ID thủ công."
            )
        }
        val cleanUrl = try {
            cleanBaseUrl(baseUrl)
        } catch (e: Exception) {
            return@withContext AiChatCallResult(
                model = cleanModel,
                ok = false,
                status = 0,
                elapsedSec = 0.0,
                text = "",
                usage = "",
                error = e.message ?: "Base URL không hợp lệ.",
                explanationVi = "Base URL phải bắt đầu bằng http:// hoặc https://"
            )
        }

        val effectiveKey = if (noAuth) "" else apiKey.trim()
        val headers = authHeaders(normProtocol, effectiveKey)
        val (url, payload) = buildChatUrlAndPayload(normProtocol, cleanUrl, cleanModel, messages, maxTokens)

        try {
            var (status, data, elapsed) = executeJsonPost(url, headers, payload, timeoutSec)
            if (normProtocol == "openai" && status == 400 && errorMessage(data).lowercase(Locale.ROOT).contains("max_tokens")) {
                payload.remove("max_tokens")
                payload.put("max_completion_tokens", maxTokens)
                val retry = executeJsonPost(url, headers, payload, timeoutSec)
                status = retry.first
                data = retry.second
                elapsed = retry.third
            }

            val text = extractText(normProtocol, data).trim()
            val usage = usageInfo(normProtocol, data)
            val isSuccess = status in 200..299 && text.isNotEmpty()
            val errStr = if (status !in 200..299) {
                errorMessage(data)
            } else if (text.isEmpty()) {
                "API trả về rỗng/không có text"
            } else {
                ""
            }
            val explainVi = if (isSuccess) {
                "Model phản hồi thành công."
            } else {
                explainHttpError(status, errStr)
            }

            AiChatCallResult(
                model = cleanModel,
                ok = isSuccess,
                status = status,
                elapsedSec = elapsed,
                text = text,
                usage = usage,
                error = errStr,
                explanationVi = explainVi,
                endpointCalled = url
            )
        } catch (e: Exception) {
            val errStr = "${e.javaClass.simpleName}: ${e.message ?: "Lỗi kết nối mạng"}"
            AiChatCallResult(
                model = cleanModel,
                ok = false,
                status = 0,
                elapsedSec = 0.0,
                text = "",
                usage = "",
                error = errStr,
                explanationVi = explainHttpError(0, errStr),
                endpointCalled = url
            )
        }
    }

    suspend fun chatTest(
        protocol: String,
        baseUrl: String,
        apiKey: String,
        noAuth: Boolean = false,
        model: String,
        prompt: String = DEFAULT_PROMPT,
        maxTokens: Int = 32,
        timeoutSec: Int = 60
    ): AiChatCallResult {
        val effectivePrompt = prompt.ifBlank { DEFAULT_PROMPT }
        return chatRequest(
            protocol = protocol,
            baseUrl = baseUrl,
            apiKey = apiKey,
            noAuth = noAuth,
            model = model,
            messages = listOf(AiChatMessage(role = "user", content = effectivePrompt)),
            maxTokens = maxTokens,
            timeoutSec = timeoutSec
        )
    }

    suspend fun runModelCheckSuite(
        providerKey: String,
        providerName: String,
        protocol: String,
        baseUrl: String,
        apiKey: String,
        noAuth: Boolean = false,
        modelsToTest: List<String>,
        prompt: String = DEFAULT_PROMPT,
        maxTokens: Int = 32,
        timeoutSec: Int = 60,
        maxModelsCap: Int = 10
    ): AiCheckerSuiteReport {
        val cap = maxModelsCap.coerceIn(1, 50)
        val targetModels = modelsToTest.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(cap)
        val results = mutableListOf<AiChatCallResult>()
        for (m in targetModels) {
            val res = chatTest(
                protocol = protocol,
                baseUrl = baseUrl,
                apiKey = apiKey,
                noAuth = noAuth,
                model = m,
                prompt = prompt,
                maxTokens = maxTokens,
                timeoutSec = timeoutSec
            )
            results.add(res)
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return AiCheckerSuiteReport(
            providerKey = providerKey,
            providerName = providerName,
            protocol = normalizeProtocol(protocol),
            baseUrl = baseUrl.trim().trimEnd('/'),
            timestamp = fmt.format(Date()),
            results = results
        )
    }

    private fun buildChatUrlAndPayload(
        normProtocol: String,
        cleanUrl: String,
        model: String,
        messages: List<AiChatMessage>,
        maxTokens: Int
    ): Pair<String, JSONObject> {
        return when (normProtocol) {
            "gemini" -> {
                val modelPath = model.removePrefix("models/")
                val encodedModel = URLEncoder.encode(modelPath, StandardCharsets.UTF_8.name())
                    .replace("%3A", ":")
                    .replace("%2F", "/")
                val url = "$cleanUrl/v1beta/models/$encodedModel:generateContent"
                val systemParts = messages.filter { it.role == "system" && it.content.isNotBlank() }.map { it.content }
                val contents = JSONArray()
                for (m in messages) {
                    if (m.role == "user" || m.role == "assistant") {
                        contents.put(
                            JSONObject().apply {
                                put("role", if (m.role == "assistant") "model" else "user")
                                put("parts", JSONArray().put(JSONObject().put("text", m.content)))
                            }
                        )
                    }
                }
                val payload = JSONObject().apply {
                    put("contents", contents)
                    put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
                    if (systemParts.isNotEmpty()) {
                        put(
                            "systemInstruction",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", systemParts.joinToString("\n\n")))
                            )
                        )
                    }
                }
                url to payload
            }
            "anthropic" -> {
                val url = if (cleanUrl.endsWith("/v1")) "$cleanUrl/messages" else "$cleanUrl/v1/messages"
                val systemParts = messages.filter { it.role == "system" && it.content.isNotBlank() }.map { it.content }
                val chatMessages = JSONArray()
                for (m in messages) {
                    if (m.role == "user" || m.role == "assistant") {
                        chatMessages.put(
                            JSONObject().apply {
                                put("role", m.role)
                                put("content", m.content)
                            }
                        )
                    }
                }
                val payload = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", maxTokens)
                    put("messages", chatMessages)
                    if (systemParts.isNotEmpty()) {
                        put("system", systemParts.joinToString("\n\n"))
                    }
                }
                url to payload
            }
            else -> {
                val url = "$cleanUrl/chat/completions"
                val chatMessages = JSONArray()
                for (m in messages) {
                    if (m.role in setOf("system", "user", "assistant")) {
                        chatMessages.put(
                            JSONObject().apply {
                                put("role", m.role)
                                put("content", m.content)
                            }
                        )
                    }
                }
                val payload = JSONObject().apply {
                    put("model", model)
                    put("messages", chatMessages)
                    put("max_tokens", maxTokens)
                }
                url to payload
            }
        }
    }

    private fun executeJsonPost(
        url: String,
        headers: Map<String, String>,
        payload: JSONObject,
        timeoutSec: Int
    ): Triple<Int, JSONObject, Double> {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "AI-API-Checker/$VERSION")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val t0 = System.nanoTime()
        clientWithTimeout(timeoutSec).newCall(reqBuilder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            val parsed = parseJsonSafely(raw)
            return Triple(resp.code, parsed, elapsed)
        }
    }

    companion object {
        const val VERSION = "1.1.0"
        const val DEFAULT_PROMPT = "Reply with exactly: API_OK"

        val PROVIDERS: LinkedHashMap<String, AiPresetProvider> = linkedMapOf(
            "openai" to AiPresetProvider("openai", "OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY", "openai", "", "gpt-4o-mini"),
            "openrouter" to AiPresetProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "openai", "", "openai/gpt-4o-mini"),
            "groq" to AiPresetProvider("groq", "Groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY", "openai", "", "llama-3.3-70b-versatile"),
            "together" to AiPresetProvider("together", "Together AI", "https://api.together.xyz/v1", "TOGETHER_API_KEY", "openai", "", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
            "fireworks" to AiPresetProvider("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", "FIREWORKS_API_KEY", "openai", "", "accounts/fireworks/models/llama-v3p3-70b-instruct"),
            "deepseek" to AiPresetProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY", "openai", "", "deepseek-chat"),
            "mistral" to AiPresetProvider("mistral", "Mistral AI", "https://api.mistral.ai/v1", "MISTRAL_API_KEY", "openai", "", "mistral-small-latest"),
            "xai" to AiPresetProvider("xai", "xAI / Grok", "https://api.x.ai/v1", "XAI_API_KEY", "openai", "", "grok-2-latest"),
            "cerebras" to AiPresetProvider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY", "openai", "", "llama-3.3-70b"),
            "perplexity" to AiPresetProvider("perplexity", "Perplexity", "https://api.perplexity.ai", "PERPLEXITY_API_KEY", "openai", "", "sonar"),
            "deepinfra" to AiPresetProvider("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", "DEEPINFRA_API_KEY", "openai", "", "meta-llama/Llama-3.3-70B-Instruct"),
            "dashscope" to AiPresetProvider("dashscope", "Alibaba DashScope / Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", "openai", "", "qwen-plus"),
            "siliconflow" to AiPresetProvider("siliconflow", "SiliconFlow", "https://api.siliconflow.com/v1", "SILICONFLOW_API_KEY", "openai", "", "deepseek-ai/DeepSeek-V3"),
            "zai" to AiPresetProvider("zai", "Z.ai / GLM", "https://api.z.ai/api/paas/v4", "ZAI_API_KEY", "openai", "", "glm-4-plus"),
            "minimax" to AiPresetProvider("minimax", "MiniMax", "https://api.minimax.io/v1", "MINIMAX_API_KEY", "openai", "", "MiniMax-Text-01"),
            "cohere" to AiPresetProvider("cohere", "Cohere (Compatibility API)", "https://api.cohere.ai/compatibility/v1", "COHERE_API_KEY", "openai", "", "command-r-plus"),
            "huggingface" to AiPresetProvider("huggingface", "Hugging Face Router", "https://router.huggingface.co/v1", "HF_TOKEN", "openai", "", "meta-llama/Llama-3.3-70B-Instruct"),
            "anthropic" to AiPresetProvider("anthropic", "Anthropic / Claude", "https://api.anthropic.com", "ANTHROPIC_API_KEY", "anthropic", "", "claude-3-5-haiku-latest"),
            "gemini" to AiPresetProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", "GEMINI_API_KEY", "gemini", "", "gemini-2.5-flash"),
            "ollama" to AiPresetProvider("ollama", "Ollama (local)", "http://localhost:11434/v1", "", "openai", "Thường không cần API key", "llama3.2"),
            "lmstudio" to AiPresetProvider("lmstudio", "LM Studio (local)", "http://localhost:1234/v1", "", "openai", "Bật Local Server trong LM Studio trước", "local-model"),
            "vllm" to AiPresetProvider("vllm", "vLLM (local/custom)", "http://localhost:8000/v1", "", "openai", "Cần server vLLM đang chạy", "default"),
            "custom" to AiPresetProvider("custom", "Custom (Tự nhập endpoint)", "https://api.openai.com/v1", "", "openai", "Tự cấu hình giao thức và Base URL", "gpt-4o-mini")
        )

        fun normalizeProtocol(protocol: String): String {
            return when (protocol.trim().lowercase(Locale.ROOT)) {
                "gemini", "google" -> "gemini"
                "anthropic", "claude" -> "anthropic"
                else -> "openai"
            }
        }

        fun toAstraApiType(protocol: String): String {
            return when (normalizeProtocol(protocol)) {
                "gemini" -> "GEMINI"
                "anthropic" -> "ANTHROPIC"
                else -> "OPENAI_COMPATIBLE"
            }
        }

        fun cleanBaseUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                throw IllegalArgumentException("Base URL phải bắt đầu bằng http:// hoặc https://")
            }
            return trimmed
        }

        fun authHeaders(protocol: String, apiKey: String): Map<String, String> {
            if (apiKey.isBlank()) return emptyMap()
            return when (normalizeProtocol(protocol)) {
                "anthropic" -> mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
                "gemini" -> mapOf("x-goog-api-key" to apiKey)
                else -> mapOf("Authorization" to "Bearer $apiKey")
            }
        }

        fun explainHttpError(status: Int, message: String): String {
            val low = message.lowercase(Locale.ROOT)
            return when {
                status == 401 -> "API key sai, hết hạn hoặc chưa được gửi đúng cách."
                status == 403 -> "Bị từ chối quyền: kiểm tra quyền API, billing, khu vực hoặc giới hạn tài khoản."
                status == 404 -> "Không tìm thấy endpoint/model. Kiểm tra Base URL, đường dẫn API và model ID."
                status == 429 -> {
                    if (listOf("quota", "billing", "insufficient_quota", "credit").any { low.contains(it) }) {
                        "Hết quota/tín dụng hoặc chưa bật thanh toán."
                    } else {
                        "Đang bị rate limit. Giảm tần suất hoặc kiểm tra giới hạn RPM/TPM."
                    }
                }
                status == 400 -> "Request không hợp lệ: thường do model ID hoặc tham số không được hỗ trợ."
                status == 408 || status == 504 -> "Request timeout. Kiểm tra mạng hoặc tăng timeout."
                status >= 500 -> "Lỗi phía nhà cung cấp/server. Thử lại sau hoặc kiểm tra trạng thái dịch vụ."
                else -> "Request thất bại. Xem thông báo lỗi chi tiết bên dưới."
            }
        }

        fun parseJsonSafely(raw: String): JSONObject {
            if (raw.isBlank()) return JSONObject()
            return try {
                JSONObject(raw)
            } catch (_: Exception) {
                JSONObject().put("_raw", raw.take(4000))
            }
        }

        fun errorMessage(data: JSONObject): String {
            val err = data.opt("error") ?: data
            if (err is JSONObject) {
                for (key in listOf("message", "detail", "type", "code")) {
                    val v = err.optString(key)
                    if (v.isNotBlank() && v != "null") return v.take(700)
                }
            }
            if (err is String && err.isNotBlank()) {
                return err.take(700)
            }
            val raw = data.optString("_raw")
            if (raw.isNotBlank()) return raw.take(700)
            return data.toString().take(700)
        }

        fun extractText(protocol: String, data: JSONObject): String {
            return when (normalizeProtocol(protocol)) {
                "anthropic" -> {
                    val content = data.optJSONArray("content") ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        if (part != null) sb.append(part.optString("text", ""))
                    }
                    sb.toString()
                }
                "gemini" -> {
                    val candidates = data.optJSONArray("candidates") ?: return ""
                    if (candidates.length() == 0) return ""
                    val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
                    val parts = content.optJSONArray("parts") ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        if (part != null) sb.append(part.optString("text", ""))
                    }
                    sb.toString()
                }
                else -> {
                    val choices = data.optJSONArray("choices") ?: return ""
                    if (choices.length() == 0) return ""
                    val choice = choices.optJSONObject(0) ?: return ""
                    val message = choice.optJSONObject("message")
                    val content = message?.opt("content")
                    when (content) {
                        is JSONArray -> {
                            val sb = StringBuilder()
                            for (i in 0 until content.length()) {
                                val item = content.optJSONObject(i)
                                if (item != null) sb.append(item.optString("text", ""))
                            }
                            sb.toString()
                        }
                        is String -> content
                        else -> choice.optString("text", "")
                    }
                }
            }
        }

        fun usageInfo(protocol: String, data: JSONObject): String {
            return when (normalizeProtocol(protocol)) {
                "gemini" -> {
                    val usage = data.optJSONObject("usageMetadata") ?: data.optJSONObject("usage") ?: return ""
                    val inp = if (usage.has("promptTokenCount")) usage.optInt("promptTokenCount") else null
                    val out = if (usage.has("candidatesTokenCount")) usage.optInt("candidatesTokenCount") else null
                    if (inp == null && out == null) "" else " | token vào: ${inp ?: "?"} | token ra: ${out ?: "?"}"
                }
                else -> {
                    val usage = data.optJSONObject("usage") ?: return ""
                    val inp = when {
                        usage.has("input_tokens") -> usage.optInt("input_tokens")
                        usage.has("prompt_tokens") -> usage.optInt("prompt_tokens")
                        else -> null
                    }
                    val out = when {
                        usage.has("output_tokens") -> usage.optInt("output_tokens")
                        usage.has("completion_tokens") -> usage.optInt("completion_tokens")
                        else -> null
                    }
                    if (inp == null && out == null) "" else " | token vào: ${inp ?: "?"} | token ra: ${out ?: "?"}"
                }
            }
        }
    }
}
