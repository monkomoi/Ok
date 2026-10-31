package com.example.telegram

import com.example.data.local.AstraDao
import com.example.data.local.SystemEventEntity
import com.example.data.local.TelegramUpdateEntity
import com.example.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

data class TelegramCommandOutcome(
    val accepted: Boolean,
    val command: String,
    val replyMessageVi: String
)

/**
 * MOD-14 & MOD-15 · Telegram Transport, Command Router, Whitelist Authorization & 2-Step Panic Nonce.
 * - Never accepts API secrets via Telegram (INV-12, Section 17).
 * - Deduplicates `update_id` in Room before executing commands.
 * - Requires pinned `chat_id` and `user_id` allowlist.
 * - Cannot arm LIVE for the first time via Telegram (INV-20, Section 11.2).
 */
class TelegramBotController(
    private val secretVault: SecretVault,
    private val dao: AstraDao,
    private val httpClient: OkHttpClient,
    private val baseApiUrl: String = "https://api.telegram.org"
) {
    private val nonRedirectHttpClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile
    private var activePanicNonce: String? = null
    @Volatile
    private var activePanicChatId: String? = null
    @Volatile
    private var activePanicUserId: String? = null
    @Volatile
    private var activePanicExpiresAtMs: Long = 0L

    suspend fun sendVietnameseNotification(
        chatId: String,
        eventTag: String,
        bodyVi: String
    ): Boolean = withContext(Dispatchers.IO) {
        val token = secretVault.getSecret(SecretVault.SLOT_TELEGRAM_BOT_TOKEN)
        if (token.isNullOrBlank() || chatId.isBlank()) return@withContext false

        // Sanitize outgoing message to ensure no secret material is ever sent (INV-12)
        if (SecretVault.containsSuspiciousSecretMaterial(bodyVi)) {
            return@withContext false
        }

        val formatted = "[$eventTag] Astra Trader V5.1\n$bodyVi"
        val json = JSONObject().apply {
            put("chat_id", chatId.trim())
            put("text", formatted)
        }
        val req = Request.Builder()
            .url("${baseApiUrl.trimEnd('/')}/bot$token/sendMessage")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext try {
            nonRedirectHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * P1-04 · Polls Telegram Bot API `getUpdates` with offset = `maxProcessedUpdateId + 1`,
     * processes each update with strict allowlist & idempotency, and sends Vietnamese replies.
     */
    suspend fun pollUpdatesOnce(
        whitelistedUserId: String,
        whitelistedChatId: String,
        nowMs: Long = System.currentTimeMillis(),
        onAuthorizedCommand: suspend (String) -> String
    ): List<TelegramCommandOutcome> = withContext(Dispatchers.IO) {
        val token = secretVault.getSecret(SecretVault.SLOT_TELEGRAM_BOT_TOKEN)
        if (token.isNullOrBlank() || whitelistedUserId.isBlank() || whitelistedChatId.isBlank()) {
            return@withContext emptyList()
        }
        val offset = (dao.getMaxTelegramUpdateId() ?: 0L) + 1L
        val url = "${baseApiUrl.trimEnd('/')}/bot$token/getUpdates?offset=$offset&timeout=0&allowed_updates=%5B%22message%22%5D"
        val req = Request.Builder().url(url).get().build()

        val rawBody = try {
            nonRedirectHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        val outcomes = parseAndProcessGetUpdatesJson(
            rawJson = rawBody,
            whitelistedUserId = whitelistedUserId,
            whitelistedChatId = whitelistedChatId,
            nowMs = nowMs,
            onAuthorizedCommand = onAuthorizedCommand
        )
        for (outcome in outcomes) {
            if (outcome.replyMessageVi.isNotBlank()) {
                sendVietnameseNotification(
                    chatId = whitelistedChatId,
                    eventTag = outcome.command.uppercase(),
                    bodyVi = outcome.replyMessageVi
                )
            }
        }
        outcomes
    }

    /**
     * P1-04 · Parses a Telegram `getUpdates` JSON payload and processes each message update in order.
     */
    suspend fun parseAndProcessGetUpdatesJson(
        rawJson: String,
        whitelistedUserId: String,
        whitelistedChatId: String,
        nowMs: Long = System.currentTimeMillis(),
        onAuthorizedCommand: suspend (String) -> String
    ): List<TelegramCommandOutcome> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            val root = JSONObject(rawJson)
            if (!root.optBoolean("ok", false)) return emptyList()
            val resultArr = root.optJSONArray("result") ?: return emptyList()
            val outcomes = mutableListOf<TelegramCommandOutcome>()
            for (i in 0 until resultArr.length()) {
                val item = resultArr.optJSONObject(i) ?: continue
                val updateId = item.optLong("update_id", -1L)
                if (updateId <= 0L) continue
                val msgObj = item.optJSONObject("message") ?: continue
                val fromObj = msgObj.optJSONObject("from")
                val chatObj = msgObj.optJSONObject("chat")
                val fromUserId = fromObj?.opt("id")?.toString().orEmpty()
                val chatId = chatObj?.opt("id")?.toString().orEmpty()
                val text = msgObj.optString("text", "")
                if (text.isBlank()) continue

                val outcome = processIncomingUpdate(
                    updateId = updateId,
                    fromUserId = fromUserId,
                    chatId = chatId,
                    text = text,
                    whitelistedUserId = whitelistedUserId,
                    whitelistedChatId = whitelistedChatId,
                    nowMs = nowMs,
                    onAuthorizedCommand = onAuthorizedCommand
                )
                outcomes.add(outcome)
            }
            outcomes
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Processes a single incoming Telegram update with strict allowlist, secret rejection, and idempotency.
     */
    suspend fun processIncomingUpdate(
        updateId: Long,
        fromUserId: String,
        chatId: String,
        text: String,
        whitelistedUserId: String,
        whitelistedChatId: String,
        nowMs: Long = System.currentTimeMillis(),
        onAuthorizedCommand: suspend (String) -> String
    ): TelegramCommandOutcome {
        // 1. Idempotency check (MOD-14 / Section 17): reject duplicate or non-monotonic update_id
        val maxSeen = dao.getMaxTelegramUpdateId()
        if (dao.isTelegramUpdateProcessed(updateId) || (maxSeen != null && updateId <= maxSeen)) {
            return TelegramCommandOutcome(
                accepted = false,
                command = "DUPLICATE_IGNORED",
                replyMessageVi = "" // Silent ignore for duplicate update_id
            )
        }

        // 2. Authorization allowlist check (MOD-15 / Section 11.3)
        val userAllowed = whitelistedUserId.isNotBlank() && fromUserId.trim() == whitelistedUserId.trim()
        val chatAllowed = whitelistedChatId.isNotBlank() && chatId.trim() == whitelistedChatId.trim()
        if (!userAllowed || !chatAllowed) {
            dao.insertTelegramUpdate(
                TelegramUpdateEntity(
                    updateId = updateId,
                    chatId = chatId,
                    userId = fromUserId,
                    command = "UNAUTHORIZED",
                    status = "IGNORED_UNAUTHORIZED",
                    processedAtMs = nowMs
                )
            )
            dao.insertSystemEvent(
                SystemEventEntity(
                    category = "SECURITY",
                    level = "WARN",
                    titleVi = "Từ chối lệnh Telegram ngoài danh sách cho phép",
                    detailVi = "Đã bỏ qua tin nhắn từ userId=$fromUserId / chatId=$chatId (không tiết lộ trạng thái bot).",
                    correlationId = "tg-unauth-$updateId",
                    occurredAtMs = nowMs
                )
            )
            return TelegramCommandOutcome(
                accepted = false,
                command = "UNAUTHORIZED",
                replyMessageVi = "" // Do not reveal bot state to strangers (Section 11.3)
            )
        }

        // P1-04: Persist update_id as IN_PROGRESS before executing action to guarantee at-most-once execution,
        // then transition to COMPLETED / REJECTED after execution finishes.
        val cleanText = text.trim()
        dao.insertTelegramUpdate(
            TelegramUpdateEntity(
                updateId = updateId,
                chatId = chatId,
                userId = fromUserId,
                command = cleanText.take(32),
                status = "IN_PROGRESS",
                processedAtMs = nowMs
            )
        )

        suspend fun completeUpdate(finalStatus: String, outcome: TelegramCommandOutcome): TelegramCommandOutcome {
            dao.upsertTelegramUpdate(
                TelegramUpdateEntity(
                    updateId = updateId,
                    chatId = chatId,
                    userId = fromUserId,
                    command = outcome.command.take(32),
                    status = finalStatus,
                    processedAtMs = nowMs
                )
            )
            return outcome
        }

        // 3. Reject any secret material sent in chat (INV-12 / Section 17)
        if (SecretVault.containsSuspiciousSecretMaterial(cleanText) ||
            cleanText.startsWith("/setkey", ignoreCase = true) ||
            cleanText.startsWith("/secret", ignoreCase = true)
        ) {
            dao.insertSystemEvent(
                SystemEventEntity(
                    category = "SECURITY",
                    level = "ERROR",
                    titleVi = "Cảnh báo bảo mật: Phát hiện gửi khóa bí mật qua Telegram",
                    detailVi = "Từ chối xử lý chuỗi nghi ngờ chứa API Secret trên Telegram. Người dùng cần thu hồi (revoke) khóa ngay trên sàn.",
                    correlationId = "tg-sec-$updateId",
                    occurredAtMs = nowMs
                )
            )
            return completeUpdate(
                "REJECTED_SECRET",
                TelegramCommandOutcome(
                    accepted = false,
                    command = "SECRET_REJECTED",
                    replyMessageVi = "CẢNH BÁO BẢO MẬT (INV-12): Astra Trader tuyệt đối KHÔNG nhận API Key/Secret qua Telegram. Xóa tin nhắn không đảm bảo bí mật chưa lộ qua cache/notification. Hãy thu hồi (revoke) key ngay trên Binance/Provider!"
                )
            )
        }

        // 4. Reject enabling LIVE from Telegram (INV-20 / Section 11.2)
        if (cleanText.startsWith("/live", ignoreCase = true)) {
            return completeUpdate(
                "REJECTED_LIVE",
                TelegramCommandOutcome(
                    accepted = false,
                    command = "/live",
                    replyMessageVi = "Từ chối (INV-20): Không được bật chế độ LIVE từ Telegram. Vui lòng kiểm tra Checklist và xác nhận trực tiếp trên ứng dụng Astra Trader."
                )
            )
        }

        val parts = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val rawFirstToken = parts.firstOrNull()?.lowercase().orEmpty()
        val normalizedCmd = rawFirstToken.substringBefore('@')

        // 5. Two-step PANIC confirmation with time-limited nonce bound to (chatId, userId, expiresAtMs) (MOD-15 / P1-04)
        if (normalizedCmd == "/panic") {
            if (parts.size > 1) {
                return completeUpdate(
                    "REJECTED_MALFORMED",
                    TelegramCommandOutcome(
                        accepted = false,
                        command = "MALFORMED_COMMAND",
                        replyMessageVi = "Từ chối lệnh /panic: Không chấp nhận tham số phụ. Chỉ gửi đúng `/panic`."
                    )
                )
            }
            val nonce = UUID.randomUUID().toString().take(6).uppercase()
            activePanicNonce = nonce
            activePanicChatId = chatId.trim()
            activePanicUserId = fromUserId.trim()
            activePanicExpiresAtMs = nowMs + 45_000L
            return completeUpdate(
                "COMPLETED",
                TelegramCommandOutcome(
                    accepted = true,
                    command = "/panic_step1",
                    replyMessageVi = "XÁC NHẬN ĐÓNG KHẨN CẤP (Bước 1/2): Lệnh thị trường có thể bị trượt giá bất lợi. Để xác nhận đóng vị thế thật và đối soát trong 45 giây, hãy gửi:\n/panic_confirm $nonce"
                )
            )
        }

        if (normalizedCmd == "/panic_confirm") {
            if (parts.size != 2) {
                activePanicNonce = null
                activePanicChatId = null
                activePanicUserId = null
                activePanicExpiresAtMs = 0L
                return completeUpdate(
                    "REJECTED_MALFORMED",
                    TelegramCommandOutcome(
                        accepted = false,
                        command = "MALFORMED_COMMAND",
                        replyMessageVi = "Từ chối /panic_confirm: Phải truyền đúng 1 mã nonce (ví dụ: `/panic_confirm ABC123`)."
                    )
                )
            }
            val suppliedNonce = parts[1]
            val validNonce = activePanicNonce != null &&
                suppliedNonce == activePanicNonce &&
                chatId.trim() == activePanicChatId &&
                fromUserId.trim() == activePanicUserId &&
                nowMs <= activePanicExpiresAtMs
            activePanicNonce = null
            activePanicChatId = null
            activePanicUserId = null
            activePanicExpiresAtMs = 0L
            return if (validNonce) {
                val execReply = onAuthorizedCommand("/panic_confirmed")
                completeUpdate(
                    "COMPLETED",
                    TelegramCommandOutcome(
                        accepted = true,
                        command = "/panic_confirmed",
                        replyMessageVi = execReply
                    )
                )
            } else {
                completeUpdate(
                    "REJECTED_EXPIRED_NONCE",
                    TelegramCommandOutcome(
                        accepted = false,
                        command = "/panic_expired",
                        replyMessageVi = "Mã xác nhận /panic_confirm không hợp lệ hoặc đã quá hạn 45 giây. Đã hủy thao tác đóng khẩn cấp."
                    )
                )
            }
        }

        return when (normalizedCmd) {
            "/status", "/pause", "/drain", "/resume", "/report", "/reconcile", "/why", "/help" -> {
                // P1-04: Strict command grammar — single-token commands reject extra trailing arguments
                if (parts.size > 1) {
                    return completeUpdate(
                        "REJECTED_MALFORMED",
                        TelegramCommandOutcome(
                            accepted = false,
                            command = "MALFORMED_COMMAND",
                            replyMessageVi = "Từ chối lệnh: Cú pháp không hợp lệ (không chấp nhận tham số phụ cho $normalizedCmd)."
                        )
                    )
                }
                val reply = onAuthorizedCommand(normalizedCmd)
                completeUpdate(
                    "COMPLETED",
                    TelegramCommandOutcome(
                        accepted = true,
                        command = normalizedCmd,
                        replyMessageVi = reply
                    )
                )
            }
            else -> completeUpdate(
                "REJECTED_UNKNOWN_CMD",
                TelegramCommandOutcome(
                    accepted = false,
                    command = "UNKNOWN_CMD",
                    replyMessageVi = "Lệnh không hợp lệ. Các lệnh được phép: /status, /why, /pause, /drain, /resume, /reconcile, /report, /panic, /help"
                )
            )
        }
    }
}
