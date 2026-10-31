package com.example.ai

import com.example.domain.AiProviderHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Verifies real connectivity, key format, and HTTP status for Gemini, OpenAI, and Claude
 * API keys without faking success when keys are missing or invalid.
 */
class AiApiCheckerEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    suspend fun checkAllProviders(
        geminiKey: String,
        openAiKey: String,
        claudeKey: String
    ): List<AiProviderHealth> = withContext(Dispatchers.IO) {
        listOf(
            checkGemini(geminiKey),
            checkOpenAi(openAiKey),
            checkClaude(claudeKey)
        )
    }

    suspend fun checkGemini(apiKey: String): AiProviderHealth = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            return@withContext AiProviderHealth(
                providerName = "Google Gemini",
                configured = false,
                status = "UNCONFIGURED",
                httpCode = 0,
                latencyMs = 0L,
                message = "No Gemini API key configured in SecretVault",
                checkedAtMs = now
            )
        }
        val start = System.currentTimeMillis()
        runCatching {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val lat = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val status = when {
                    resp.isSuccessful -> "HEALTHY"
                    resp.code == 401 || resp.code == 403 -> "UNAUTHORIZED"
                    resp.code == 429 -> "RATE_LIMITED"
                    else -> "HTTP_ERROR_${resp.code}"
                }
                AiProviderHealth(
                    providerName = "Google Gemini",
                    configured = true,
                    status = status,
                    httpCode = resp.code,
                    latencyMs = lat,
                    message = if (resp.isSuccessful) "Models endpoint reachable (${lat}ms)" else "HTTP ${resp.code}: ${resp.message}",
                    checkedAtMs = now
                )
            }
        }.getOrElse { err ->
            AiProviderHealth(
                providerName = "Google Gemini",
                configured = true,
                status = "NETWORK_ERROR",
                httpCode = -1,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                message = err.message ?: "Network connection failed",
                checkedAtMs = now
            )
        }
    }

    suspend fun checkOpenAi(apiKey: String): AiProviderHealth = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            return@withContext AiProviderHealth(
                providerName = "OpenAI",
                configured = false,
                status = "UNCONFIGURED",
                httpCode = 0,
                latencyMs = 0L,
                message = "No OpenAI API key configured in SecretVault",
                checkedAtMs = now
            )
        }
        val start = System.currentTimeMillis()
        runCatching {
            val req = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val lat = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val status = when {
                    resp.isSuccessful -> "HEALTHY"
                    resp.code == 401 || resp.code == 403 -> "UNAUTHORIZED"
                    resp.code == 429 -> "RATE_LIMITED"
                    else -> "HTTP_ERROR_${resp.code}"
                }
                AiProviderHealth(
                    providerName = "OpenAI",
                    configured = true,
                    status = status,
                    httpCode = resp.code,
                    latencyMs = lat,
                    message = if (resp.isSuccessful) "OpenAI models reachable (${lat}ms)" else "HTTP ${resp.code}: ${resp.message}",
                    checkedAtMs = now
                )
            }
        }.getOrElse { err ->
            AiProviderHealth(
                providerName = "OpenAI",
                configured = true,
                status = "NETWORK_ERROR",
                httpCode = -1,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                message = err.message ?: "Network connection failed",
                checkedAtMs = now
            )
        }
    }

    suspend fun checkClaude(apiKey: String): AiProviderHealth = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            return@withContext AiProviderHealth(
                providerName = "Anthropic Claude",
                configured = false,
                status = "UNCONFIGURED",
                httpCode = 0,
                latencyMs = 0L,
                message = "No Claude API key configured in SecretVault",
                checkedAtMs = now
            )
        }
        val start = System.currentTimeMillis()
        runCatching {
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .addHeader("x-api-key", apiKey.trim())
                .addHeader("anthropic-version", "2023-06-01")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val lat = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val status = when {
                    resp.isSuccessful -> "HEALTHY"
                    resp.code == 401 || resp.code == 403 -> "UNAUTHORIZED"
                    resp.code == 429 -> "RATE_LIMITED"
                    else -> "HTTP_ERROR_${resp.code}"
                }
                AiProviderHealth(
                    providerName = "Anthropic Claude",
                    configured = true,
                    status = status,
                    httpCode = resp.code,
                    latencyMs = lat,
                    message = if (resp.isSuccessful) "Anthropic models reachable (${lat}ms)" else "HTTP ${resp.code}: ${resp.message}",
                    checkedAtMs = now
                )
            }
        }.getOrElse { err ->
            AiProviderHealth(
                providerName = "Anthropic Claude",
                configured = true,
                status = "NETWORK_ERROR",
                httpCode = -1,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                message = err.message ?: "Network connection failed",
                checkedAtMs = now
            )
        }
    }
}
