# ASTRA TRADER V5.1 — SECURITY & CREDENTIAL ISOLATION AUDIT (`SECURITY_AUDIT.md`)

**Date:** 2026-10-30  
**Scope:** Android Keystore Cryptography, Credential Sanitization, Per-Provider AI Key Isolation, Telegram Command Security, Log Redaction, and Network Capability Enforcement.

---

## 1. Cryptographic Storage (`SecretVault.kt`)

1. **Hardware-Backed AES-256-GCM Encryption**:
   - Master key alias `astra_trader_master_key_v1` is generated inside `AndroidKeyStore` with `KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, and 256-bit key size.
   - Each secret encryption generates a fresh 12-byte IV via `Cipher.init(Cipher.ENCRYPT_MODE, secretKey)` and prepends `[1-byte IV length][IV][Ciphertext + 128-bit GCM Auth Tag]`.
2. **Fail-Closed Keystore Posture (`P1-12`)**:
   - There is **zero** plaintext fallback to `SharedPreferences` if `AndroidKeyStore` fails.
   - `verifyVaultSlotIntegrity(slot)` now strictly fails closed (`return false`) if `slot_sha256_<slot>` or `slot_len_<slot>` metadata is missing, zero, or does not match the SHA-256 digest of the decrypted secret.
3. **Strict Input Sanitization (`SecretVault.sanitizeCredentialInput`)**:
   - Strips outer whitespace, CR/LF (`\r`, `\n`), tabs, zero-width characters (`\u200B`, `\uFEFF`), surrounding quotes, and accidental `Bearer ` prefixes while preserving exact case sensitivity of Binance and AI API keys.

---

## 2. Complete Removal of Compile-Time Secret Fallbacks (`P0-09`, `P1-02`)

- **Audit Finding**: Earlier revisions fell back to `BuildConfig.GEMINI_API_KEY` when `SecretVault` was empty.
- **Remediation**:
  - Removed all `BuildConfig.GEMINI_API_KEY` fallbacks from `AiIntelligenceGateway.kt`, `AiApiCheckerEngine.kt`, `AstraViewModel.kt`, and `AstraTradingCoordinator.kt`.
  - Every AI provider (`gemini`, `openai`, `openrouter`, `groq`, `deepseek`, `anthropic`, etc.) resolves credentials **exclusively** from its dedicated `SecretVault` slot (`SecretVault.secretSlotForProvider(providerId)`).
  - Querying a non-Gemini provider never reads `SLOT_AI_PRIMARY_KEY`, and saving a key for one provider never overwrites another provider's slot.

---

## 3. Binance Authentication & Endpoint Capability Isolation (`P0-08`, `P1-13`)

1. **HMAC-SHA256 Signing Integrity**:
   - Signed requests construct a canonical query string, append `recvWindow` and clock-synced `timestamp`, compute lowercase hex HMAC-SHA256 using `SLOT_BINANCE_API_SECRET`, and attach `X-MBX-APIKEY` using the sanitized key from `SLOT_BINANCE_API_KEY`.
2. **Zero Secret Leakage in Diagnostics & Logs**:
   - `BinanceRequestDiagnostic`, `BinanceAuthDiagnosticReport`, and `SystemEventEntity` redact all `X-MBX-APIKEY` values, `signature=` query parameters, and secret strings before persistence or UI rendering.
3. **Adapter-Level Order Mutation Lock**:
   - `BinanceAdapter.verifyOrderMutationCapability` enforces `activeExecutionCapability` before any `POST` or `DELETE` order/leverage/margin call. When `environmentMode == "TEST"` and `binanceEndpointTarget == "MAINNET"`, `OrderExecutionEndpoint` is `DISABLED` and any new order attempt is rejected immediately at the adapter boundary (`EXECUTION_CAPABILITY_LOCKED`).

---

## 4. Telegram Remote Command Hardening (`P1-04`)

1. **Dual Whitelist Enforcement**: Every incoming Telegram update must match both `whitelistedUserId` and `whitelistedChatId`.
2. **Pre-Execution `IN_PROGRESS` Persistence**: `telegram_updates` persists `status = "IN_PROGRESS"` **before** invoking `onAuthorizedCommand`, preventing duplicate command execution if the process crashes mid-command.
3. **2-Step `/panic` Challenge Nonce Binding**:
   - `/panic` issues a 6-digit random challenge nonce valid for 60 seconds and binds it to `(activePanicNonce, activePanicChatId, activePanicUserId, activePanicExpiresAtMs)`.
   - `/panic confirm <nonce>` verifies exact nonce, originating `chatId`, originating `userId`, and non-expired timestamp, and clears the nonce immediately upon consumption.
4. **Secret Transmission Block**: Any Telegram message containing `api_key`, `api_secret`, `secret`, or `token` is rejected (`SECRET_REJECTED`) and never echoed.
