package com.example.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class VaultSecurityStatus(val badgeVi: String, val isHardwareOrKeystoreBacked: Boolean) {
    KEYSTORE_AES256_GCM("AndroidKeyStore AES-256-GCM", true),
    IN_MEMORY_FALLBACK_LOCKED("Bộ nhớ tạm (Chặn LIVE)", false)
}

data class KeyFormatValidationResult(
    val valid: Boolean,
    val sanitizedValue: String,
    val warningVi: String? = null
)

class SecretVault(context: Context? = null) {
    private val inMemoryStore = ConcurrentHashMap<String, String>()
    private val encryptedPrefs: SharedPreferences?
    val securityStatus: VaultSecurityStatus

    init {
        var prefs: SharedPreferences? = null
        var status = VaultSecurityStatus.IN_MEMORY_FALLBACK_LOCKED
        if (context != null) {
            try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                status = VaultSecurityStatus.KEYSTORE_AES256_GCM
            } catch (_: Exception) {
                prefs = null
                status = VaultSecurityStatus.IN_MEMORY_FALLBACK_LOCKED
            }
        }
        encryptedPrefs = prefs
        securityStatus = status
    }

    fun sanitizeAndValidateCredential(raw: String, minLen: Int = 8): KeyFormatValidationResult {
        val trimmed = raw
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .trim()
        if (trimmed.length < minLen || isPlaceholderValue(trimmed)) {
            return KeyFormatValidationResult(
                valid = false,
                sanitizedValue = "",
                warningVi = "Khóa quá ngắn (< $minLen ký tự) hoặc đang là giá trị mẫu (placeholder)."
            )
        }
        if (trimmed.any { it.isWhitespace() }) {
            return KeyFormatValidationResult(
                valid = false,
                sanitizedValue = trimmed.replace("\\s+".toRegex(), ""),
                warningVi = "Khóa chứa khoảng trắng hoặc xuống dòng không hợp lệ."
            )
        }
        return KeyFormatValidationResult(valid = true, sanitizedValue = trimmed)
    }

    fun putSecret(key: String, value: String): Boolean {
        val clean = value.replace("\u200B", "").replace("\uFEFF", "").trim()
        inMemoryStore[key] = clean
        val prefs = encryptedPrefs ?: return true
        return try {
            prefs.edit().putString(key, clean).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun getSecret(key: String): String? {
        val fromPrefs = try {
            encryptedPrefs?.getString(key, null)
        } catch (_: Exception) {
            null
        }
        val v = fromPrefs ?: inMemoryStore[key]
        return v?.takeIf { it.isNotBlank() && !isPlaceholderValue(it) }
    }

    fun removeSecret(key: String) {
        inMemoryStore.remove(key)
        try {
            encryptedPrefs?.edit()?.remove(key)?.commit()
        } catch (_: Exception) {
        }
    }

    fun saveBinanceCredentialsVerified(apiKey: String, apiSecret: String): Boolean {
        val cleanKey = apiKey.replace("\u200B", "").replace("\uFEFF", "").trim()
        val cleanSecret = apiSecret.replace("\u200B", "").replace("\uFEFF", "").trim()
        val now = System.currentTimeMillis()
        putSecret(KEY_BINANCE_API_KEY, cleanKey)
        putSecret(KEY_BINANCE_API_SECRET, cleanSecret)
        putSecret(KEY_BINANCE_KEY_DIGEST, sha256Hex(cleanKey))
        putSecret(KEY_BINANCE_SECRET_DIGEST, sha256Hex(cleanSecret))
        putSecret(KEY_BINANCE_SAVED_AT_MS, now.toString())
        val readBackKey = getSecret(KEY_BINANCE_API_KEY)
        val readBackSecret = getSecret(KEY_BINANCE_API_SECRET)
        return readBackKey == cleanKey && readBackSecret == cleanSecret
    }

    fun saveBinanceCredentials(apiKey: String, apiSecret: String) {
        saveBinanceCredentialsVerified(apiKey, apiSecret)
    }

    fun clearBinanceCredentials() {
        removeSecret(KEY_BINANCE_API_KEY)
        removeSecret(KEY_BINANCE_API_SECRET)
        removeSecret(KEY_BINANCE_KEY_DIGEST)
        removeSecret(KEY_BINANCE_SECRET_DIGEST)
        removeSecret(KEY_BINANCE_SAVED_AT_MS)
    }

    fun clearAllCredentials() {
        clearBinanceCredentials()
        removeSecret(KEY_TELEGRAM_BOT_TOKEN)
        removeSecret(KEY_TELEGRAM_CHAT_ID)
        removeSecret("ai_provider_key_gemini")
        removeSecret("ai_provider_key_openai")
        removeSecret("ai_provider_key_claude")
    }

    /**
     * Resolves the Binance API Key from Android Keystore (`SecretVault`) first,
     * falling back to `BuildConfig.BINANCE_API_KEY` injected from `.env` via the Secrets Gradle Plugin.
     */
    fun getBinanceApiKey(): String =
        getSecret(KEY_BINANCE_API_KEY)
            ?: sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_KEY }.getOrNull())
            ?: ""

    /**
     * Resolves the Binance API Secret from Android Keystore (`SecretVault`) first,
     * falling back to `BuildConfig.BINANCE_API_SECRET` injected from `.env` via the Secrets Gradle Plugin.
     */
    fun getBinanceApiSecret(): String =
        getSecret(KEY_BINANCE_API_SECRET)
            ?: sanitizeBuildConfigSecret(runCatching { BuildConfig.BINANCE_API_SECRET }.getOrNull())
            ?: ""

    fun getBinanceSecretKey(): String = getBinanceApiSecret()

    fun hasBinanceCredentials(): Boolean =
        getBinanceApiKey().isNotBlank() && getBinanceApiSecret().isNotBlank()

    fun getBinanceKeySavedAtMs(): Long =
        getSecret(KEY_BINANCE_SAVED_AT_MS)?.toLongOrNull() ?: 0L

    fun verifyBinanceVaultDigestRoundTrip(): Pair<Boolean, Boolean> {
        val k = getBinanceApiKey().takeIf { it.isNotBlank() } ?: return false to false
        val s = getBinanceApiSecret().takeIf { it.isNotBlank() } ?: return false to false
        val storedKDigest = getSecret(KEY_BINANCE_KEY_DIGEST)
        val storedSDigest = getSecret(KEY_BINANCE_SECRET_DIGEST)
        if (storedKDigest == null || storedSDigest == null) {
            // Loaded from BuildConfig environment variables
            return true to true
        }
        return (storedKDigest == sha256Hex(k)) to (storedSDigest == sha256Hex(s))
    }

    fun saveTelegramBotToken(token: String): Boolean = putSecret(KEY_TELEGRAM_BOT_TOKEN, token)
    fun getTelegramBotToken(): String = getSecret(KEY_TELEGRAM_BOT_TOKEN) ?: ""

    fun saveTelegramCredentials(botToken: String, chatId: String) {
        putSecret(KEY_TELEGRAM_BOT_TOKEN, botToken)
        putSecret(KEY_TELEGRAM_CHAT_ID, chatId)
    }

    fun getTelegramChatId(): String = getSecret(KEY_TELEGRAM_CHAT_ID) ?: ""

    fun saveAiProviderKey(providerId: String, apiKey: String): Boolean =
        putSecret("ai_provider_key_${providerId.lowercase()}", apiKey)

    fun saveAiProviderKeys(geminiKey: String, openAiKey: String, claudeKey: String) {
        if (geminiKey.isNotBlank()) saveAiProviderKey("gemini", geminiKey)
        if (openAiKey.isNotBlank()) saveAiProviderKey("openai", openAiKey)
        if (claudeKey.isNotBlank()) saveAiProviderKey("claude", claudeKey)
    }

    fun getAiProviderKey(providerId: String): String? {
        val stored = getSecret("ai_provider_key_${providerId.lowercase()}")
        if (!stored.isNullOrBlank()) return stored
        if (providerId.equals("gemini", ignoreCase = true)) {
            return sanitizeBuildConfigSecret(runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull())
        }
        return null
    }

    fun getGeminiApiKey(): String = getAiProviderKey("gemini") ?: ""
    fun getOpenAiApiKey(): String = getAiProviderKey("openai") ?: ""
    fun getClaudeApiKey(): String = getAiProviderKey("claude") ?: ""

    fun hasAiProviderKey(providerId: String): Boolean =
        !getAiProviderKey(providerId).isNullOrBlank()

    fun maskSecretPreview(raw: String?): String {
        if (raw.isNullOrBlank() || isPlaceholderValue(raw)) return "CHƯA LƯU (EMPTY)"
        val clean = raw.trim()
        if (clean.length <= 8) return "**** (${clean.length} ký tự)"
        return "${clean.take(4)}••••${clean.takeLast(4)} (${clean.length} ký tự)"
    }

    fun redactSecretsFromText(text: String): String {
        var out = text
        val candidates = mutableListOf<String>()
        getBinanceApiKey().takeIf { it.length >= 6 }?.let { candidates.add(it) }
        getBinanceApiSecret().takeIf { it.length >= 6 }?.let { candidates.add(it) }
        getTelegramBotToken().takeIf { it.length >= 6 }?.let { candidates.add(it) }
        for ((k, v) in inMemoryStore) {
            if (k.startsWith("ai_provider_key_") && v.length >= 6) {
                candidates.add(v)
            }
        }
        for (secret in candidates.distinct()) {
            out = out.replace(secret, "[REDACTED_SECRET]")
        }
        return out
    }

    companion object {
        private const val PREFS_FILE_NAME = "astra_secure_vault_v2"
        const val KEY_BINANCE_API_KEY = "binance_api_key"
        const val KEY_BINANCE_API_SECRET = "binance_api_secret"
        const val KEY_BINANCE_KEY_DIGEST = "binance_api_key_sha256"
        const val KEY_BINANCE_SECRET_DIGEST = "binance_api_secret_sha256"
        const val KEY_BINANCE_SAVED_AT_MS = "binance_credentials_saved_at_ms"
        const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"

        fun isPlaceholderValue(value: String?): Boolean {
            if (value.isNullOrBlank()) return true
            val upper = value.trim().uppercase()
            return upper == "NULL" ||
                upper.startsWith("YOUR_") ||
                upper.endsWith("_DEFAULT_VALUE") ||
                upper == "PLACEHOLDER"
        }

        fun sanitizeBuildConfigSecret(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val clean = raw.replace("\u200B", "").replace("\uFEFF", "").trim()
            return if (isPlaceholderValue(clean)) null else clean
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
