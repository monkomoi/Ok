package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.AiDecisionEntity
import com.example.data.local.AppSettingsEntity
import com.example.data.local.ProviderConfigEntity
import com.example.domain.AccountCapitalState
import com.example.domain.AiModuleStatus
import com.example.domain.BinanceAuthDiagnosticReport
import com.example.domain.OutboundNetworkIdentity
import com.example.security.SecretVault
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApiIntegrationTabScreen(
    settings: AppSettingsEntity,
    secretVault: SecretVault,
    secretRefreshTick: Int,
    providers: List<ProviderConfigEntity>,
    aiModuleStatus: AiModuleStatus,
    recentAiDecisions: List<AiDecisionEntity>,
    onSaveBinanceCredentials: (String, String, Boolean) -> Unit,
    onClearBinanceCredentials: () -> Unit,
    onTestAndReconcileBinance: () -> Unit,
    onSaveAiKey: (String, String) -> Unit,
    onToggleProvider: (ProviderConfigEntity, Boolean) -> Unit,
    onTriggerAiReview: () -> Unit,
    onSaveTelegramConfig: (String, String, String) -> Unit,
    onSimulateTelegramCommand: (String, String, String) -> Unit,
    onAddOrUpdateCustomProvider: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onSendTestTelegramMessage: () -> Unit = {},
    accountCapitalState: AccountCapitalState = AccountCapitalState(),
    authDiagnosticReport: BinanceAuthDiagnosticReport? = null,
    outboundNetworkIdentity: OutboundNetworkIdentity = OutboundNetworkIdentity(),
    onSelectEndpointTarget: (String) -> Unit = {},
    onRunAuthDiagnostics: () -> Unit = {},
    onRunOutboundIpDiagnostics: () -> Unit = {},
    onApplyTestedProviderToBot: (String, String, String, String, String, String, String, Boolean, Long?) -> Unit = { _, name, proto, url, model, role, _, _, _ ->
        onAddOrUpdateCustomProvider(name, proto, url, model, role)
    }
) {
    val keyLoadedFromVault = remember(secretRefreshTick) {
        secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_KEY)
    }
    val secretLoadedFromVault = remember(secretRefreshTick) {
        secretVault.hasSecret(SecretVault.SLOT_BINANCE_API_SECRET)
    }
    val keyVaultIntegrity = remember(secretRefreshTick) {
        keyLoadedFromVault && secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_KEY)
    }
    val keySavedAtMs = remember(secretRefreshTick) {
        secretVault.getSlotSavedAtMs(SecretVault.SLOT_BINANCE_API_KEY)
    }
    val secretVaultIntegrity = remember(secretRefreshTick) {
        secretLoadedFromVault && secretVault.verifyVaultSlotIntegrity(SecretVault.SLOT_BINANCE_API_SECRET)
    }
    val aiPrimaryPreview = remember(secretRefreshTick) {
        secretVault.getMaskedPreview(SecretVault.SLOT_AI_PRIMARY_KEY)
    }
    val tgTokenPreview = remember(secretRefreshTick) {
        secretVault.getMaskedPreview(SecretVault.SLOT_TELEGRAM_BOT_TOKEN)
    }
    val hwBacked = remember { secretVault.isHardwareBackedKeystoreAvailable() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()) }

    var apiKeyInput by remember { mutableStateOf("") }
    var apiSecretInput by remember { mutableStateOf("") }
    var withdrawDisabledChecked by remember(settings.withdrawDisabledConfirmed) {
        mutableStateOf(settings.withdrawDisabledConfirmed)
    }

    var aiKeyInput by remember { mutableStateOf("") }
    var customProvName by remember { mutableStateOf("DeepSeek R1 Analyst") }
    var customProvBaseUrl by remember { mutableStateOf("https://api.deepseek.com") }
    var customProvModel by remember { mutableStateOf("deepseek-chat") }

    var tgTokenInput by remember { mutableStateOf("") }
    var tgChatIdInput by remember(settings.telegramChatIdWhitelist) {
        mutableStateOf(settings.telegramChatIdWhitelist.ifBlank { "100200300" })
    }
    var tgUserIdInput by remember(settings.telegramUserIdWhitelist) {
        mutableStateOf(settings.telegramUserIdWhitelist.ifBlank { "900800700" })
    }
    var tgCommandTestInput by remember { mutableStateOf("/status") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Binance USDⓈ-M Futures Key Vault & 6-Step Read-Only Diagnostics (Requirements 1–9)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = AstraEmeraldGreen)
                    Text(
                        text = "Binance USDⓈ-M Futures · SecretVault & Chẩn đoán -2015",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Requirement 6: Strictly separated badges for Vault Storage vs Binance Verification
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "TÁCH BIỆT TRẠNG THÁI LƯU VAULT & XÁC MINH BINANCE (MỤC 6):",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraCyanAccent
                    )
                    Text(
                        text = "1. Trạng thái Lưu Vault: ${accountCapitalState.vaultCredentialState.badgeVi}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (keyVaultIntegrity && secretVaultIntegrity) AstraEmeraldGreen else AstraAmberWarn,
                        modifier = Modifier.testTag("vault_credential_state_badge")
                    )
                    Text(
                        text = "2. Trạng thái Xác minh Sàn: ${accountCapitalState.verificationState.badgeVi}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (accountCapitalState.verificationState.isVerified) AstraEmeraldGreen else AstraCrimsonDanger,
                        modifier = Modifier.testTag("api_tab_verification_badge")
                    )
                    Text(
                        text = "3. Môi trường Endpoint: ${accountCapitalState.activeEndpointTarget}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("active_endpoint_target_text")
                    )
                }

                // Requirement 2: Target Endpoint Selector (MAINNET https://fapi.binance.com vs TESTNET https://testnet.binancefuture.com)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "CHỌN ENDPOINT XÁC THỰC READ-ONLY (MỤC 2 · ĐỘC LẬP VỚI KHÓA LỆNH LIVE):",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraCyanAccent
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isMainnetTarget = !settings.binanceEndpointTarget.equals("TESTNET", ignoreCase = true)
                        Button(
                            onClick = { onSelectEndpointTarget("MAINNET") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMainnetTarget) AstraEmeraldGreen else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("select_mainnet_endpoint_button")
                        ) {
                            Text("MAINNET (fapi.binance.com)", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { onSelectEndpointTarget("TESTNET") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("select_testnet_endpoint_button")
                        ) {
                            Text("TESTNET (testnet.binancefuture)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        text = "Lưu ý Mục 2: Key tạo trên Binance.com (Mainnet) BẮT BUỘC gọi https://fapi.binance.com (nếu gửi sang testnet.binancefuture.com sẽ luôn bị lỗi -2015). Chế độ này chỉ thực hiện request ĐỌC (/fapi/v2/account, /fapi/v2/balance), tuyệt đối KHÔNG gửi lệnh giao dịch thật khi Khóa an toàn LIVE đang bật.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val savedAtText = if (keySavedAtMs > 0L) {
                    timeFormat.format(Date(keySavedAtMs))
                } else {
                    "Chưa lưu"
                }

                // Requirement 6 & 7: Report strictly true/false booleans for Vault & Header without logging any credential value
                Text(
                    text = "Kiểm tra lấy Key từ Vault & gắn Header (Mục 6 & 7 · Chỉ báo true/false, tuyệt đối không xuất giá trị):\n" +
                        "• apiKeyLoadedFromVault: $keyVaultIntegrity · apiSecretLoadedFromVault: $secretVaultIntegrity\n" +
                        "• apiKeyAttachedToHeader (X-MBX-APIKEY): ${authDiagnosticReport?.apiKeyAttachedToHeader ?: keyVaultIntegrity}\n" +
                        "• Thời điểm lưu bộ Key hiện tại: $savedAtText · Bảo mật: ${if (hwBacked) "AndroidKeyStore AES-GCM" else "Session Encrypted"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AstraCyanAccent,
                    modifier = Modifier.testTag("vault_boolean_status_text")
                )

                // Requirements 1–5 & 9: Dedicated Outbound IP & Android Network Diagnostics Card
                val activeNet = if (outboundNetworkIdentity.probedAtUtcMs > 0L) {
                    outboundNetworkIdentity
                } else {
                    authDiagnosticReport?.outboundNetwork ?: outboundNetworkIdentity
                }
                val ipProbedAtText = if (activeNet.probedAtUtcMs > 0L) {
                    timeFormat.format(Date(activeNet.probedAtUtcMs))
                } else {
                    "Chưa chạy kiểm tra (Bấm 'Chẩn đoán IP outbound')"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AstraCyanAccent.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CHẨN ĐOÁN IP OUTBOUND & ĐƯỜNG MẠNG ANDROID (MỤC 1–5 & 9):",
                            style = MaterialTheme.typography.labelSmall,
                            color = AstraCyanAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = onRunOutboundIpDiagnostics,
                        colors = ButtonDefaults.buttonColors(containerColor = AstraCyanAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("run_outbound_ip_diagnostics_button")
                    ) {
                        Text("Chẩn đoán IP outbound (IPv4-only & IPv6/Dual-Stack HTTPS)", color = MaterialTheme.colorScheme.surface)
                    }

                    Text(
                        text = "• IPv4 outbound quan sát được: ${activeNet.outboundIpv4 ?: "CHƯA QUAN SÁT ĐƯỢC"}\n" +
                            "  (Endpoint HTTPS IPv4-only: ${activeNet.ipv4ProbeEndpointUsed ?: "https://api4.ipify.org / ipv4.icanhazip.com"} · ${activeNet.ipv4ProbeStatus})\n" +
                            "• IPv6 outbound quan sát được: ${activeNet.outboundIpv6 ?: "KHÔNG CÓ IPv6 OUTBOUND"}\n" +
                            "  (Endpoint HTTPS IPv6-only: ${activeNet.ipv6ProbeEndpointUsed ?: "https://api6.ipify.org / ipv6.icanhazip.com"} · ${activeNet.ipv6ProbeStatus})\n" +
                            "• Dual-Stack mặc định: ${activeNet.outboundDefaultIp ?: "CHƯA QUAN SÁT ĐƯỢC"} (Ưu tiên mặc định IPv6 = ${activeNet.isIpv6DefaultEgress})\n" +
                            "• Thời điểm kiểm tra: $ipProbedAtText\n" +
                            "• Loại kết nối (Wi-Fi / Di động): ${activeNet.transportTypeVi} (Interface=${activeNet.networkInterfaceName ?: activeNet.activeTransport}, OS Validated=${activeNet.networkValidatedByOs}, VPN=${activeNet.vpnActive}, Proxy=${activeNet.systemProxy})\n" +
                            "• Trạng thái kết nối & lỗi mạng: ${activeNet.connectionStatusVi}" +
                            (if (!activeNet.ipv4ProbeError.isNullOrBlank()) "\n  - Lỗi kênh IPv4: ${activeNet.ipv4ProbeError}" else "") +
                            (if (!activeNet.ipv6ProbeError.isNullOrBlank()) "\n  - Chi tiết kênh IPv6: ${activeNet.ipv6ProbeError}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("outbound_network_identity_text")
                    )

                    // Requirement 3: Same Android Network binding & architectural limitation disclosure
                    Text(
                        text = "• Gắn kết cùng Android Network với request Binance (Mục 3): sameAndroidNetworkBound=${activeNet.sameAndroidNetworkBound} (handle=${activeNet.androidNetworkHandle ?: "null"})\n" +
                            "  ${activeNet.networkBindingLimitationNoteVi}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("network_binding_limitation_text")
                    )

                    // Requirement 5: Explicit disclaimer that observed IP is from probe service, not proven to be seen by Binance without Binance evidence
                    Text(
                        text = activeNet.observationDisclaimerVi,
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraAmberWarn,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("ip_observation_disclaimer_text")
                    )

                    // Requirement 9: Warnings for mobile IP rotation or IPv6 usage without changing whitelist or asking for withdrawal permission
                    if (activeNet.securityAndRoutingWarningsVi.isNotEmpty()) {
                        Text(
                            text = "CẢNH BÁO MẠNG DI ĐỘNG / IPv6 (MỤC 9):\n" +
                                activeNet.securityAndRoutingWarningsVi.joinToString("\n") { "• $it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = AstraCrimsonDanger,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("outbound_ip_warnings_text")
                        )
                    } else {
                        Text(
                            text = "Nguyên tắc an toàn Mục 9 & 10: Nếu dùng dữ liệu di động (IP xoay vòng) hoặc đường mạng có IPv6, IP nguồn có thể khác với IP Whitelist trên Binance. Ứng dụng KHÔNG tự động sửa whitelist, KHÔNG tắt bảo vệ IP, KHÔNG yêu cầu bật quyền rút tiền và giữ LIVE khóa cho tới khi đối soát tài khoản thật hoàn tất.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Requirement 8: Last Failed Step, Endpoint, Code & Raw Message Box
                if (accountCapitalState.lastFailedStep != null || accountCapitalState.lastFailedBinanceCode != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                            .border(1.dp, AstraCrimsonDanger, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "CHI TIẾT LỖI XÁC THỰC GẦN NHẤT (MỤC 6–8 · KHÔNG CHỨA CREDENTIAL/CHỮ KÝ):",
                            style = MaterialTheme.typography.labelSmall,
                            color = AstraCrimsonDanger,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Bước kiểm tra thất bại: ${accountCapitalState.lastFailedStep ?: "N/A"}\n" +
                                "• Endpoint: ${accountCapitalState.lastFailedEndpoint ?: "N/A"}\n" +
                                "• Mã lỗi Binance: ${accountCapitalState.lastFailedBinanceCode ?: "N/A"}\n" +
                                "• Message gốc từ Binance: ${accountCapitalState.lastRawBinanceErrorMsg ?: "N/A"}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("last_failed_auth_details_text")
                        )
                    }
                }

                // 6-Step Read-Only Diagnostic Report & Actual Binance Request Telemetry Panel (Requirements 6–8)
                if (authDiagnosticReport != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "KIỂM TRA REQUEST BINANCE THỰC TẾ & CHẨN ĐOÁN 6 BƯỚC READ-ONLY (${authDiagnosticReport.targetEndpointMode} · ${authDiagnosticReport.fapiBaseUrl}):",
                            style = MaterialTheme.typography.labelSmall,
                            color = AstraCyanAccent,
                            fontWeight = FontWeight.Bold
                        )

                        // Requirement 6 & 7: Actual Binance Request Inspection Table (Host, Endpoint, HTTP Status, Code, Message, Boolean Key/Header flags, Env & Product)
                        if (authDiagnosticReport.requestInspections.isNotEmpty()) {
                            Text(
                                text = "CHI TIẾT TỪNG REQUEST BINANCE ĐÃ GỬI (MỤC 6 & 7 · CHỈ ĐỌC):",
                                style = MaterialTheme.typography.labelSmall,
                                color = AstraEmeraldGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                            authDiagnosticReport.requestInspections.forEachIndexed { idx, req ->
                                Text(
                                    text = "${idx + 1}. [${req.environment} · ${req.product}] ${req.httpMethod} https://${req.host}${req.endpointPath}\n" +
                                        "   • apiKeyLoadedFromVault=${req.apiKeyLoadedFromVault} · apiKeyAttachedToHeader=${req.apiKeyAttachedToHeader} · hmacSigned=${req.hmacSignatureAttachedToQuery} · sameAndroidNet=${req.boundToSameAndroidNetwork}\n" +
                                        "   • HTTP Status=${req.httpStatus} · Binance Error Code=${req.binanceErrorCode ?: "NONE"} · Msg=\"${req.rawServerMessage.ifBlank { "OK" }}\"",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (req.httpStatus in 200..299) AstraEmeraldGreen else AstraAmberWarn,
                                    modifier = Modifier.testTag("binance_request_inspection_item_$idx")
                                )
                            }
                        }

                        authDiagnosticReport.steps.forEach { step ->
                            Text(
                                text = "${if (step.passed) "[PASS]" else "[FAIL]"} ${step.stepIndex}. ${step.stepCode}\n" +
                                    "   Endpoint: ${step.endpointCalled} · HTTP=${step.httpCode ?: "-"} · Code=${step.binanceErrorCode ?: "-"}\n" +
                                    (if (!step.rawServerMessage.isNullOrBlank()) "   Raw Msg: \"${step.rawServerMessage}\"\n" else "") +
                                    "   Kết quả: ${step.detailVi}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (step.passed) AstraEmeraldGreen else AstraCrimsonDanger
                            )
                        }

                        if (authDiagnosticReport.provenCausesVi.isNotEmpty()) {
                            Text(
                                text = "NGUYÊN NHÂN ĐÃ ĐƯỢC CHỨNG MINH:\n" +
                                    authDiagnosticReport.provenCausesVi.joinToString("\n") { "• $it" },
                                style = MaterialTheme.typography.bodySmall,
                                color = AstraEmeraldGreen,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("proven_causes_text")
                            )
                        }

                        if (authDiagnosticReport.remainingHypothesesVi.isNotEmpty()) {
                            Text(
                                text = "GIẢ THUYẾT CẦN ĐỐI CHIẾU THÊM:\n" +
                                    authDiagnosticReport.remainingHypothesesVi.joinToString("\n") { "• $it" },
                                style = MaterialTheme.typography.bodySmall,
                                color = AstraAmberWarn,
                                modifier = Modifier.testTag("remaining_hypotheses_text")
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Nhập Binance API Key mới") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_binance_api_key")
                )

                OutlinedTextField(
                    value = apiSecretInput,
                    onValueChange = { apiSecretInput = it },
                    label = { Text("Nhập Binance API Secret mới (Ký HMAC-SHA256)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_binance_api_secret")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = withdrawDisabledChecked,
                        onCheckedChange = { withdrawDisabledChecked = it },
                        modifier = Modifier.testTag("checkbox_withdraw_disabled")
                    )
                    Text(
                        text = "Tôi xác nhận API Key này ĐÃ TẮT quyền Rút tiền (Enable Withdrawals = OFF) trên Binance (Mục 14.1)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveBinanceCredentials(apiKeyInput, apiSecretInput, withdrawDisabledChecked)
                            apiKeyInput = ""
                            apiSecretInput = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_binance_key_button")
                    ) {
                        Text("Mã hóa & Lưu Vault")
                    }
                    OutlinedButton(
                        onClick = onTestAndReconcileBinance,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reconcile_binance_button")
                    ) {
                        Text("Kiểm tra & Đối soát")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRunAuthDiagnostics,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("run_auth_diagnostics_button")
                    ) {
                        Text("Chẩn đoán Request Binance (-2015)")
                    }
                    OutlinedButton(
                        onClick = onClearBinanceCredentials,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AstraCrimsonDanger),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("clear_binance_key_button")
                    ) {
                        Text("Xóa Key khỏi Vault")
                    }
                }
            }
        }

        // 2. AI API Checker v1.1.0 & Multi-Provider Router (aiai.py + MOD-07/08)
        AiApiCheckerCard(
            secretVault = secretVault,
            secretRefreshTick = secretRefreshTick,
            providers = providers,
            aiModuleStatus = aiModuleStatus,
            recentAiDecisions = recentAiDecisions,
            onSaveAiKey = onSaveAiKey,
            onToggleProvider = onToggleProvider,
            onTriggerAiReview = onTriggerAiReview,
            onAddOrUpdateCustomProvider = onAddOrUpdateCustomProvider,
            onApplyTestedProviderToBot = onApplyTestedProviderToBot
        )

        // 3. Telegram Transport, Whitelist & Command Tester (MOD-14 & MOD-15)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = AstraEmeraldGreen
                    )
                    Text(
                        text = "Telegram Điều khiển & Whitelist (MOD-14/15)",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "Bot Token trong Vault: $tgTokenPreview · Chỉ xử lý đúng user_id và chat_id đã ghim; tự động từ chối nếu ai đó gửi API Secret vào Telegram (INV-12).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = tgTokenInput,
                    onValueChange = { tgTokenInput = it },
                    label = { Text("Telegram Bot Token (Lưu mã hóa vào Vault)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tgUserIdInput,
                        onValueChange = { tgUserIdInput = it },
                        label = { Text("Whitelist user_id") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tgChatIdInput,
                        onValueChange = { tgChatIdInput = it },
                        label = { Text("Whitelist chat_id") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveTelegramConfig(tgTokenInput, tgChatIdInput, tgUserIdInput)
                            tgTokenInput = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_telegram_config_button")
                    ) {
                        Text("Lưu Token & Whitelist")
                    }
                    OutlinedButton(
                        onClick = onSendTestTelegramMessage,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("send_real_telegram_ping_button")
                    ) {
                        Text("Gửi Ping Telegram Thật")
                    }
                }

                Text(
                    text = "Bộ kiểm thử Command Router (/status, /pause, /drain, /resume, /report, /panic):",
                    style = MaterialTheme.typography.labelMedium,
                    color = AstraCyanAccent
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = tgCommandTestInput,
                        onValueChange = { tgCommandTestInput = it },
                        label = { Text("Lệnh Telegram") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_telegram_command_test")
                    )
                    Button(
                        onClick = {
                            onSimulateTelegramCommand(tgCommandTestInput, tgUserIdInput, tgChatIdInput)
                        },
                        modifier = Modifier.testTag("run_telegram_command_test_button")
                    ) {
                        Text("Chạy lệnh")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSimulateTelegramCommand("/panic", tgUserIdInput, tgChatIdInput) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Thử /panic (Bước 1)")
                    }
                    OutlinedButton(
                        onClick = { onSimulateTelegramCommand("/setkey api_secret=123456", tgUserIdInput, tgChatIdInput) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Thử gửi Secret (Chặn INV-12)")
                    }
                }
            }
        }
    }
}
