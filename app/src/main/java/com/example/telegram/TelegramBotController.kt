package com.example.telegram

import com.example.data.local.AstraDao
import com.example.data.local.SystemLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TelegramCommandOutcome(
    val accepted: Boolean,
    val command: String,
    val responseText: String,
    val triggeredKillSwitchLevel: String? = null
)

/**
 * Authenticated, replay-protected Telegram Bot controller for out-of-band operator alerts
 * and remote kill-switch / status commands (`/status`, `/positions`, `/risk`, `/pause`,
 * `/resume`, `/panic_close`).
 *
 * Enforces:
 * 1. Strict Chat ID allowlist (`allowedChatId`)
 * 2. Monotonic `update_id` replay protection (`lastProcessedUpdateId`)
 * 3. Message timestamp freshness (`|now - messageTimestampSec| <= 300s`)
 * 4. Two-step confirmation token for destructive `/panic_close` command
 */
class TelegramBotController(
    private val dao: AstraDao? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val lastProcessedUpdateId = AtomicLong(0L)
    private val pendingPanicConfirmationByChat = ConcurrentHashMap<String, Long>()

    fun processIncomingCommand(
        updateId: Long,
        senderChatId: String,
        configuredChatId: String,
        messageTimestampSec: Long,
        rawText: String,
        nowTimestampMs: Long = System.currentTimeMillis(),
        statusSummaryProvider: () -> String = { "Status: OK" }
    ): TelegramCommandOutcome {
        if (configuredChatId.isBlank() || senderChatId.trim() != configuredChatId.trim()) {
            return TelegramCommandOutcome(
                accepted = false,
                command = "UNAUTHORIZED",
                responseText = "Rejected: unauthorized Telegram chat_id ($senderChatId)"
            )
        }

        val prevUpdateId = lastProcessedUpdateId.get()
        if (updateId <= prevUpdateId) {
            return TelegramCommandOutcome(
                accepted = false,
                command = "REPLAY_BLOCKED",
                responseText = "Rejected: stale or replayed update_id ($updateId <= $prevUpdateId)"
            )
        }

        val messageAgeSec = (nowTimestampMs / 1000L) - messageTimestampSec
        if (messageAgeSec > 300L || messageAgeSec < -60L) {
            return TelegramCommandOutcome(
                accepted = false,
                command = "EXPIRED_TIMESTAMP",
                responseText = "Rejected: command timestamp outside 300s freshness window (age=${messageAgeSec}s)"
            )
        }

        lastProcessedUpdateId.set(updateId)
        val trimmed = rawText.trim()
        val parts = trimmed.split("\\s+".toRegex())
        val cmd = parts.firstOrNull()?.lowercase() ?: ""

        return when (cmd) {
            "/status", "/positions", "/risk", "/why_last_trade", "/daily_report" -> {
                TelegramCommandOutcome(
                    accepted = true,
                    command = cmd,
                    responseText = statusSummaryProvider()
                )
            }
            "/pause" -> {
                TelegramCommandOutcome(
                    accepted = true,
                    command = "/pause",
                    responseText = "PAUSE kill-switch activated. New entries blocked.",
                    triggeredKillSwitchLevel = "PAUSE"
                )
            }
            "/drain" -> {
                TelegramCommandOutcome(
                    accepted = true,
                    command = "/drain",
                    responseText = "DRAIN mode activated. Unfilled working entries cancelled; existing stops kept.",
                    triggeredKillSwitchLevel = "DRAIN"
                )
            }
            "/resume" -> {
                TelegramCommandOutcome(
                    accepted = true,
                    command = "/resume",
                    responseText = "Trading state resumed to SHADOW/TEST monitoring.",
                    triggeredKillSwitchLevel = "NONE"
                )
            }
            "/panic_close" -> {
                val arg = parts.getOrNull(1)
                val pendingExpiry = pendingPanicConfirmationByChat[senderChatId] ?: 0L
                if (arg == "CONFIRM" && nowTimestampMs <= pendingExpiry) {
                    pendingPanicConfirmationByChat.remove(senderChatId)
                    TelegramCommandOutcome(
                        accepted = true,
                        command = "/panic_close CONFIRM",
                        responseText = "PANIC_CLOSE confirmed and executed. All working orders cancelled and positions flattened.",
                        triggeredKillSwitchLevel = "PANIC_CLOSE"
                    )
                } else {
                    pendingPanicConfirmationByChat[senderChatId] = nowTimestampMs + 60_000L
                    TelegramCommandOutcome(
                        accepted = true,
                        command = "/panic_close",
                        responseText = "Confirmation required within 60s: send `/panic_close CONFIRM` to flatten all positions."
                    )
                }
            }
            else -> {
                TelegramCommandOutcome(
                    accepted = false,
                    command = cmd,
                    responseText = "Unknown command '$cmd'. Supported: /status, /positions, /risk, /pause, /drain, /resume, /panic_close"
                )
            }
        }
    }

    suspend fun sendAlertMessage(
        botToken: String,
        chatId: String,
        messageText: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Telegram bot token or chat ID not configured"))
        }
        runCatching {
            val url = "https://api.telegram.org/bot${botToken.trim()}/sendMessage"
            val payload = buildJsonObject {
                put("chat_id", chatId.trim())
                put("text", messageText)
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    dao?.insertSystemLog(
                        SystemLogEntity(
                            timestamp = System.currentTimeMillis(),
                            level = "WARN",
                            module = "TelegramOutbox",
                            message = "Telegram send HTTP ${resp.code}; message queued in audit log"
                        )
                    )
                    error("Telegram HTTP ${resp.code}")
                }
                true
            }
        }
    }
}
