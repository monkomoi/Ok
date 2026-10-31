package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class KeyFormatValidationResult(
    val isValid: Boolean,
    val errorCode: String? = null,
    val messageVi: String = "Định dạng khóa hợp lệ."
)

enum class VaultSecurityStatus(val badgeVi: String, val isOperational: Boolean) {
    HARDWARE_KEYSTORE_ACTIVE("ANDROID KEYSTORE AES-256-GCM (AN TOÀN)", true),
    JVM_EPHEMERAL_TEST_KEYSTORE("JVM TEST EPHEMERAL RAM KEY (CHỈ DÙNG TRONG UNIT TEST)", true),
    KEYSTORE_KEY_INVALIDATED("KHÓA KEYSTORE BỊ THU HỒI / INVALIDATED (CẦN NHẬP LẠI)", false),
    KEYSTORE_UNAVAILABLE_FAIL_CLOSED("LỖI ANDROID KEYSTORE · FAIL-CLOSED (KHÔNG LƯU KHÓA DỰ PHÒNG)", false)
}

/**
 * MOD-03 · Secret Vault (Remediated per P1 Section 3.10)
 * Protects Binance API Secret, AI Provider keys, and Telegram Bot Token using Android Keystore AES-256-GCM.
 *
 * Security Invariants:
 * - NEVER generates or persists a fallback AES master key inside SharedPreferences (`KEY_FALLBACK_SALT` and `FB:` mode are strictly prohibited and automatically purged).
 * - If Android Keystore is unavailable or throws an exception in production, fails closed (`putSecret` returns false, `getSecret` returns null).
 * - Detects `KeyPermanentlyInvalidatedException`, clears invalidated ciphertexts safely, and transitions to `KEYSTORE_KEY_INVALIDATED`.
 * - In local JVM unit tests (where OS `AndroidKeyStore` provider is absent), allows an explicit RAM-only ephemeral key that is NEVER persisted to disk or SharedPreferences, and supports `setSimulateKeystoreFailureForTest(true)` to test fail-closed behavior.
 */
class SecretVault(
    context: Context,
    private val allowJvmTestEphemeralKeyWhenKeystoreAbsent: Boolean = isRunningUnderJvmUnitTest()
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    @Volatile
    private var simulateKeystoreFailureForTest: Boolean = false

    @Volatile
    private var lastSecurityStatus: VaultSecurityStatus = VaultSecurityStatus.HARDWARE_KEYSTORE_ACTIVE

    init {
        purgeLegacyInsecureFallbackData()
        refreshSecurityStatus()
    }

    fun setSimulateKeystoreFailureForTest(simulateFailure: Boolean) {
        simulateKeystoreFailureForTest = simulateFailure
        refreshSecurityStatus()
    }

    fun getVaultSecurityStatus(): VaultSecurityStatus {
        return refreshSecurityStatus()
    }

    /**
     * Immediately removes any legacy `fallback_aes_key_v51` or `FB:` ciphertexts from SharedPreferences
     * so no plaintext/Base64 master key or weakly encrypted secret ever remains on disk.
     */
    fun purgeLegacyInsecureFallbackData(): Int {
        var purgedCount = 0
        val editor = prefs.edit()
        if (prefs.contains(LEGACY_KEY_FALLBACK_SALT)) {
            editor.remove(LEGACY_KEY_FALLBACK_SALT)
            purgedCount++
        }
        val allEntries = prefs.all
        for ((k, v) in allEntries) {
            if (k.startsWith("enc_slot_") && v is String && !v.startsWith("KS:")) {
                val slotName = k.removePrefix("enc_slot_")
                editor.remove(slotKey(slotName))
                editor.remove(slotHashKey(slotName))
                editor.remove(slotLenKey(slotName))
                editor.remove(slotSavedAtKey(slotName))
                purgedCount++
            }
        }
        if (purgedCount > 0) {
            editor.commit()
        }
        return purgedCount
    }

    /**
     * Sanitizes, encrypts via Keystore AES-256-GCM, stores, and immediately verifies round-trip decryption equality.
     * Fails closed (returns false and writes nothing) if Keystore is unavailable or invalidated.
     */
    fun putSecret(slot: String, rawInput: String): Boolean {
        val sanitized = sanitizeCredentialInput(rawInput)
        if (sanitized.isEmpty()) {
            removeSecret(slot)
            return false
        }
        val inputBytes = sanitized.toByteArray(StandardCharsets.UTF_8)
        val encryptedPayload = try {
            encryptWithKeystoreOrFailClosed(inputBytes)
        } catch (e: KeyPermanentlyInvalidatedException) {
            handleKeyInvalidation()
            return false
        } catch (_: Exception) {
            lastSecurityStatus = VaultSecurityStatus.KEYSTORE_UNAVAILABLE_FAIL_CLOSED
            return false
        } ?: return false

        val expectedHash = sha256ShortFingerprint(sanitized)
        val nowMs = System.currentTimeMillis()
        val committed = prefs.edit()
            .putString(slotKey(slot), encryptedPayload)
            .putString(slotHashKey(slot), expectedHash)
            .putInt(slotLenKey(slot), sanitized.length)
            .putLong(slotSavedAtKey(slot), nowMs)
            .commit()
        if (!committed) return false

        // Immediate round-trip verification
        val roundTripDecrypted = getSecret(slot)
        val ok = roundTripDecrypted != null &&
            roundTripDecrypted == sanitized &&
            sha256ShortFingerprint(roundTripDecrypted) == expectedHash
        if (!ok) {
            removeSecret(slot)
        }
        return ok
    }

    fun getSecret(slot: String): String? {
        if (simulateKeystoreFailureForTest) {
            lastSecurityStatus = VaultSecurityStatus.KEYSTORE_UNAVAILABLE_FAIL_CLOSED
            return null
        }
        val payload = prefs.getString(slotKey(slot), null) ?: return null
        if (!payload.startsWith("KS:")) {
            // Never decrypt legacy non-Keystore payloads; purge immediately
            removeSecret(slot)
            return null
        }
        return try {
            val bytes = decryptWithKeystoreOrFailClosed(payload) ?: return null
            val decoded = String(bytes, StandardCharsets.UTF_8)
            val storedHash = prefs.getString(slotHashKey(slot), null)
            if (storedHash != null && sha256ShortFingerprint(decoded) != storedHash) {
                null
            } else {
                decoded
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            handleKeyInvalidation()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun hasSecret(slot: String): Boolean {
        val s = getSecret(slot)
        return !s.isNullOrBlank()
    }

    fun verifyVaultSlotIntegrity(slot: String): Boolean {
        val secret = getSecret(slot) ?: return false
        val expectedHash = prefs.getString(slotHashKey(slot), null) ?: return false
        if (!prefs.contains(slotLenKey(slot))) return false
        val expectedLen = prefs.getInt(slotLenKey(slot), -1)
        return expectedLen > 0 && secret.length == expectedLen && sha256ShortFingerprint(secret) == expectedHash
    }

    fun getSlotLength(slot: String): Int {
        return getSecret(slot)?.length ?: 0
    }

    fun getSlotSavedAtMs(slot: String): Long {
        return prefs.getLong(slotSavedAtKey(slot), 0L)
    }

    fun getSlotShortDigest(slot: String): String {
        val secret = getSecret(slot) ?: return "NONE"
        return sha256ShortFingerprint(secret)
    }

    fun getMaskedPreview(slot: String): String {
        val secret = getSecret(slot) ?: return "Chưa cấu hình"
        val len = secret.length
        return "${maskSecret(secret)} (len=$len)"
    }

    fun removeSecret(slot: String) {
        prefs.edit()
            .remove(slotKey(slot))
            .remove(slotHashKey(slot))
            .remove(slotLenKey(slot))
            .remove(slotSavedAtKey(slot))
            .commit()
    }

    fun clearAllBinanceCredentials() {
        removeSecret(SLOT_BINANCE_API_KEY)
        removeSecret(SLOT_BINANCE_API_SECRET)
    }

    fun clearAllSecrets() {
        prefs.edit().clear().commit()
    }

    fun isHardwareBackedKeystoreAvailable(): Boolean {
        if (simulateKeystoreFailureForTest) return false
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isVaultOperational(): Boolean {
        return refreshSecurityStatus().isOperational
    }

    private fun refreshSecurityStatus(): VaultSecurityStatus {
        if (simulateKeystoreFailureForTest) {
            lastSecurityStatus = VaultSecurityStatus.KEYSTORE_UNAVAILABLE_FAIL_CLOSED
            return lastSecurityStatus
        }
        val key = resolveActiveAesKey()
        lastSecurityStatus = when {
            key == null -> VaultSecurityStatus.KEYSTORE_UNAVAILABLE_FAIL_CLOSED
            isHardwareBackedKeystoreAvailable() -> VaultSecurityStatus.HARDWARE_KEYSTORE_ACTIVE
            else -> VaultSecurityStatus.JVM_EPHEMERAL_TEST_KEYSTORE
        }
        return lastSecurityStatus
    }

    private fun handleKeyInvalidation() {
        lastSecurityStatus = VaultSecurityStatus.KEYSTORE_KEY_INVALIDATED
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {
        }
        clearAllSecrets()
    }

    private fun slotKey(slot: String): String = "enc_slot_$slot"
    private fun slotHashKey(slot: String): String = "hash_slot_$slot"
    private fun slotLenKey(slot: String): String = "len_slot_$slot"
    private fun slotSavedAtKey(slot: String): String = "saved_at_slot_$slot"

    private fun getOrCreateHardwareAesKey(): SecretKey? {
        if (simulateKeystoreFailureForTest) return null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveActiveAesKey(): SecretKey? {
        if (simulateKeystoreFailureForTest) return null
        val hwKey = getOrCreateHardwareAesKey()
        if (hwKey != null) return hwKey
        if (allowJvmTestEphemeralKeyWhenKeystoreAbsent) {
            return getOrCreateJvmEphemeralTestKey()
        }
        return null
    }

    private fun encryptWithKeystoreOrFailClosed(input: ByteArray): String? {
        val secretKey = resolveActiveAesKey() ?: run {
            lastSecurityStatus = VaultSecurityStatus.KEYSTORE_UNAVAILABLE_FAIL_CLOSED
            return null
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH_BYTES) {
            "Invalid GCM IV length"
        }
        val cipherBytes = cipher.doFinal(input)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "KS:$ivB64:$ctB64"
    }

    private fun decryptWithKeystoreOrFailClosed(serialized: String): ByteArray? {
        val parts = serialized.split(":")
        if (parts.size != 3 || parts[0] != "KS") {
            return null
        }
        val secretKey = resolveActiveAesKey() ?: run {
            lastSecurityStatus = VaultSecurityStatus.KEYSTORE_UNAVAILABLE_FAIL_CLOSED
            return null
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        if (iv.size != GCM_IV_LENGTH_BYTES) return null
        val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AstraTraderMasterAesKeyV51"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFS_FILE = "astra_secret_vault_v51"
        private const val LEGACY_KEY_FALLBACK_SALT = "fallback_aes_key_v51"

        const val SLOT_BINANCE_API_KEY = "binance_api_key"
        const val SLOT_BINANCE_API_SECRET = "binance_api_secret"
        const val SLOT_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        const val SLOT_AI_PRIMARY_KEY = "ai_primary_key"
        const val SLOT_AI_SKEPTIC_KEY = "ai_skeptic_key"

        // Strictly in-memory map for JVM unit tests where AndroidKeyStore OS provider does not exist.
        // Never written to SharedPreferences or disk.
        private val jvmEphemeralTestKeys = ConcurrentHashMap<String, SecretKey>()

        private fun getOrCreateJvmEphemeralTestKey(): SecretKey {
            return jvmEphemeralTestKeys.computeIfAbsent(KEY_ALIAS) {
                val kg = KeyGenerator.getInstance("AES")
                kg.init(256)
                kg.generateKey()
            }
        }

        private fun isRunningUnderJvmUnitTest(): Boolean {
            return try {
                val fingerprint = android.os.Build.FINGERPRINT
                fingerprint == null ||
                    fingerprint.equals("robolectric", ignoreCase = true) ||
                    System.getProperty("java.Specification.name")?.contains("Java") == true &&
                    java.security.Security.getProvider(ANDROID_KEYSTORE) == null
            } catch (_: Throwable) {
                true
            }
        }

        /**
         * Strips leading/trailing whitespace, invisible zero-width spaces, carriage returns/newlines,
         * surrounding quotes, and accidental "Bearer " prefix without modifying valid characters or case.
         */
        fun sanitizeCredentialInput(raw: String): String {
            var s = raw
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
                if (s.length >= 2) {
                    s = s.substring(1, s.length - 1).trim()
                }
            }
            if (s.startsWith("Bearer ", ignoreCase = true)) {
                s = s.substring(7).trim()
            }
            return s
        }

        /**
         * Pre-validates Binance API Key and Secret format before sending HTTP requests to prevent -2014 BAD_API_KEY_FMT.
         */
        fun validateBinanceCredentialsFormat(
            apiKey: String?,
            apiSecret: String?
        ): KeyFormatValidationResult {
            if (apiKey.isNullOrBlank()) {
                return KeyFormatValidationResult(
                    isValid = false,
                    errorCode = "MISSING_API_KEY",
                    messageVi = "Chưa có Binance API Key trong SecretVault."
                )
            }
            if (apiSecret.isNullOrBlank()) {
                return KeyFormatValidationResult(
                    isValid = false,
                    errorCode = "MISSING_API_SECRET",
                    messageVi = "Chưa có Binance API Secret trong SecretVault."
                )
            }
            if (apiKey == apiSecret) {
                return KeyFormatValidationResult(
                    isValid = false,
                    errorCode = "KEY_EQUALS_SECRET",
                    messageVi = "Lỗi cấu hình (-2014/-1022): API Key và API Secret đang trùng nhau (có thể đã dán nhầm cùng một chuỗi vào cả 2 ô)."
                )
            }
            val validKeyRegex = Regex("^[A-Za-z0-9\\-_+/=]{16,128}$")
            if (!validKeyRegex.matches(apiKey)) {
                return KeyFormatValidationResult(
                    isValid = false,
                    errorCode = "BAD_API_KEY_FMT_LOCAL",
                    messageVi = "Lỗi định dạng API Key (-2014 BAD_API_KEY_FMT): Độ dài (${apiKey.length}) hoặc chứa ký tự không hợp lệ (khoảng trắng giữa chuỗi, dấu ngoặc, ký tự Unicode ẩn)."
                )
            }
            if (!validKeyRegex.matches(apiSecret)) {
                return KeyFormatValidationResult(
                    isValid = false,
                    errorCode = "BAD_API_SECRET_FMT_LOCAL",
                    messageVi = "Lỗi định dạng API Secret: Độ dài (${apiSecret.length}) hoặc chứa ký tự không hợp lệ."
                )
            }
            return KeyFormatValidationResult(isValid = true)
        }

        fun sha256ShortFingerprint(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder(16)
            for (i in 0 until 6) {
                sb.append(String.format("%02x", digest[i]))
            }
            return sb.toString()
        }

        fun maskSecret(raw: String): String {
            val clean = raw.trim()
            if (clean.isEmpty()) return "Chưa cấu hình"
            if (clean.length <= 6) return "••••••"
            return "${clean.take(3)}••••••••${clean.takeLast(3)}"
        }

        fun containsSuspiciousSecretMaterial(text: String): Boolean {
            val lower = text.lowercase()
            if (lower.contains("api_secret") || lower.contains("apisecret") || lower.contains("private_key")) {
                return true
            }
            val sixtyFourTokenRegex = Regex("\\b[A-Za-z0-9]{64}\\b")
            return sixtyFourTokenRegex.containsMatchIn(text)
        }
    }
}
