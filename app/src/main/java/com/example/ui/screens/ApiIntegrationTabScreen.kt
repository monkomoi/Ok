package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.AiProviderHealth
import com.example.domain.KillSwitchLevel
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard

@Composable
fun ApiIntegrationTabScreen(
    hasBinanceCredentials: Boolean,
    binanceAuthDiagnostic: String,
    telegramConfigured: Boolean,
    telegramStatusMessage: String,
    aiProviderHealthList: List<AiProviderHealth>,
    isBusy: Boolean,
    onSaveBinanceCredentials: (String, String) -> Unit,
    onVerifyBinanceConnection: () -> Unit,
    onSaveTelegramCredentials: (String, String) -> Unit,
    onTestTelegramAlert: () -> Unit,
    onSaveAiKeys: (String, String, String) -> Unit,
    onVerifyAiProviders: () -> Unit,
    onCredentialLockdown: () -> Unit
) {
    var binanceApiKey by remember { mutableStateOf("") }
    var binanceSecretKey by remember { mutableStateOf("") }
    var telegramToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var openAiKey by remember { mutableStateOf("") }
    var claudeKey by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Binance USDⓈ-M Futures Keystore Vault Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("binance_credentials_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Binance USDⓈ-M Futures API (AES256-GCM SecretVault)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (hasBinanceCredentials) {
                        "Vault Status: Encrypted API Key & Secret stored in Android Keystore"
                    } else {
                        "Vault Status: EMPTY (Enter Testnet or Futures-only API keys; never enable Withdraw permission)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasBinanceCredentials) AstraEmeraldGreen else AstraAmberWarn
                )

                OutlinedTextField(
                    value = binanceApiKey,
                    onValueChange = { binanceApiKey = it },
                    label = { Text("Binance API Key") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_binance_api_key")
                )
                OutlinedTextField(
                    value = binanceSecretKey,
                    onValueChange = { binanceSecretKey = it },
                    label = { Text("Binance Secret Key (HMAC-SHA256)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_binance_secret_key")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveBinanceCredentials(binanceApiKey, binanceSecretKey)
                            binanceApiKey = ""
                            binanceSecretKey = ""
                        },
                        enabled = !isBusy && binanceApiKey.isNotBlank() && binanceSecretKey.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_binance_keys_button")
                    ) {
                        Text("Save to Vault")
                    }
                    OutlinedButton(
                        onClick = onVerifyBinanceConnection,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verify_binance_connection_button")
                    ) {
                        Text("6-Step Auth Test")
                    }
                }

                Text(
                    text = binanceAuthDiagnostic,
                    style = MaterialTheme.typography.bodySmall,
                    color = AstraCyanAccent,
                    modifier = Modifier.testTag("binance_auth_diagnostic_text")
                )
            }
        }

        // 2. Dual-Agent AI Keys & Live Health Verification
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "AI Analyst & Skeptic Keys (SecretVault)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "You can also configure keys via the Secrets panel in AI Studio (.env).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_gemini_api_key")
                )
                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = { openAiKey = it },
                    label = { Text("OpenAI API Key (Optional Failover)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_openai_api_key")
                )
                OutlinedTextField(
                    value = claudeKey,
                    onValueChange = { claudeKey = it },
                    label = { Text("Claude API Key (Optional Failover)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_claude_api_key")
                )

                Button(
                    onClick = {
                        onSaveAiKeys(geminiKey, openAiKey, claudeKey)
                        geminiKey = ""
                        openAiKey = ""
                        claudeKey = ""
                    },
                    enabled = !isBusy && (geminiKey.isNotBlank() || openAiKey.isNotBlank() || claudeKey.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_ai_keys_button")
                ) {
                    Text("Save AI Keys to Vault")
                }
            }
        }

        AiApiCheckerCard(
            providerHealthList = aiProviderHealthList,
            isChecking = isBusy,
            onRunAiCheck = onVerifyAiProviders
        )

        // 3. Telegram Bot Out-of-Band Control & Replay-Protected Webhook/Polling
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Telegram Bot Remote Control & Alerts",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Enforces strict Chat ID allowlist, monotonic update_id replay guard, and 60s 2-step /panic_close confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = telegramToken,
                    onValueChange = { telegramToken = it },
                    label = { Text("Telegram Bot Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_telegram_bot_token")
                )
                OutlinedTextField(
                    value = telegramChatId,
                    onValueChange = { telegramChatId = it },
                    label = { Text("Authorized Chat ID Allowlist") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_telegram_chat_id")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveTelegramCredentials(telegramToken, telegramChatId)
                            telegramToken = ""
                            telegramChatId = ""
                        },
                        enabled = !isBusy && telegramToken.isNotBlank() && telegramChatId.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_telegram_button")
                    ) {
                        Text("Save Telegram")
                    }
                    OutlinedButton(
                        onClick = onTestTelegramAlert,
                        enabled = !isBusy && telegramConfigured,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_telegram_alert_button")
                    ) {
                        Text("Send Test Ping")
                    }
                }

                Text(
                    text = telegramStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = AstraCyanAccent
                )
            }
        }

        // 4. Level 4 Kill Switch: Credential Lockdown
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Emergency Level-4 Credential Lockdown",
                    style = MaterialTheme.typography.titleMedium,
                    color = AstraCrimsonDanger
                )
                Text(
                    text = "Flattens open positions, engages safety lock, and wipes all API keys from Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onCredentialLockdown,
                    colors = ButtonDefaults.buttonColors(containerColor = AstraCrimsonDanger),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("credential_lockdown_button")
                ) {
                    Text("Execute Level-4 Credential Lockdown")
                }
            }
        }
    }
}
