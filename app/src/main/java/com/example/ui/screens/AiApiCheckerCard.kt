package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ai.AiApiCheckerEngine
import com.example.ai.AiChatMessage
import com.example.ai.AiCheckerSuiteReport
import com.example.ai.AiModelListResult
import com.example.data.local.AiDecisionEntity
import com.example.data.local.ProviderConfigEntity
import com.example.domain.AiModuleStatus
import com.example.network.BinanceAdapter
import com.example.security.SecretVault
import com.example.ui.theme.AstraAmberWarn
import com.example.ui.theme.AstraCrimsonDanger
import com.example.ui.theme.AstraCyanAccent
import com.example.ui.theme.AstraEmeraldGreen
import com.example.ui.theme.AstraSurfaceCard
import com.example.ui.theme.AstraSurfaceOutline
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiApiCheckerCard(
    secretVault: SecretVault,
    secretRefreshTick: Int,
    providers: List<ProviderConfigEntity>,
    aiModuleStatus: AiModuleStatus,
    recentAiDecisions: List<AiDecisionEntity>,
    onSaveAiKey: (String, String) -> Unit,
    onToggleProvider: (ProviderConfigEntity, Boolean) -> Unit,
    onTriggerAiReview: () -> Unit,
    onAddOrUpdateCustomProvider: (String, String, String, String, String) -> Unit,
    onApplyTestedProviderToBot: (
        providerKey: String,
        displayName: String,
        protocol: String,
        baseUrl: String,
        modelId: String,
        role: String,
        secretSlot: String,
        verifiedHealthy: Boolean,
        latencyMs: Long?
    ) -> Unit = { _, displayName, protocol, baseUrl, modelId, role, _, _, _ ->
        onAddOrUpdateCustomProvider(
            displayName,
            AiApiCheckerEngine.toAstraApiType(protocol),
            baseUrl,
            modelId,
            role
        )
    }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkerEngine = remember(secretVault) {
        AiApiCheckerEngine(secretVault, BinanceAdapter.defaultOkHttpClient())
    }

    val allPresetKeys = remember { AiApiCheckerEngine.PROVIDERS.keys.toList() }
    var selectedProviderKey by remember { mutableStateOf("gemini") }
    val currentPreset = AiApiCheckerEngine.PROVIDERS[selectedProviderKey]
        ?: AiApiCheckerEngine.PROVIDERS.getValue("gemini")

    var selectedProtocol by remember { mutableStateOf(currentPreset.protocol) }
    var baseUrlInput by remember { mutableStateOf(currentPreset.baseUrl) }
    var selectedModelInput by remember { mutableStateOf(currentPreset.defaultSuggestedModel) }
    var selectedBotRole by remember { mutableStateOf("FAST_ANALYST") }
    var noAuthChecked by remember { mutableStateOf(false) }

    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKeyPlaintext by remember { mutableStateOf(false) }
    var localVaultTick by remember { mutableStateOf(0) }
    var showAllPresetsExpanded by remember { mutableStateOf(false) }

    // Checker Step [1/3] & [2/3] & [3/3] State
    var isWorking by remember { mutableStateOf(false) }
    var statusNoticeVi by remember {
        mutableStateOf("Sẵn sàng kiểm tra API key, endpoint, danh sách model và tốc độ phản hồi (AI API Checker v${AiApiCheckerEngine.VERSION}).")
    }
    var modelListResult by remember { mutableStateOf<AiModelListResult?>(null) }
    var modelSearchQuery by remember { mutableStateOf("") }

    var testPromptInput by remember { mutableStateOf(AiApiCheckerEngine.DEFAULT_PROMPT) }
    var testMaxTokensInput by remember { mutableStateOf("32") }
    var testTimeoutSecInput by remember { mutableStateOf("60") }
    var batchMaxModelsInput by remember { mutableStateOf("10") }
    var confirmBatchDialogVisible by remember { mutableStateOf(false) }

    var latestSuiteReport by remember { mutableStateOf<AiCheckerSuiteReport?>(null) }
    var showJsonReportBox by remember { mutableStateOf(false) }

    // Interactive Multi-turn Chat State (`chat_loop` from aiai.py)
    var showChatSession by remember { mutableStateOf(false) }
    var chatSystemPrompt by remember { mutableStateOf("") }
    var chatMaxTokensInput by remember { mutableStateOf("1024") }
    var chatUserInput by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<AiChatMessage>() }
    var chatTotalTurns by remember { mutableStateOf(0) }
    var savedChatTranscriptJson by remember { mutableStateOf<String?>(null) }

    val maskedKeyPreview = remember(selectedProviderKey, noAuthChecked, secretRefreshTick, localVaultTick) {
        checkerEngine.getMaskedKeyPreview(selectedProviderKey, noAuthChecked)
    }

    fun selectPreset(key: String) {
        val p = AiApiCheckerEngine.PROVIDERS[key] ?: return
        selectedProviderKey = key
        selectedProtocol = p.protocol
        baseUrlInput = p.baseUrl
        selectedModelInput = p.defaultSuggestedModel
        noAuthChecked = key in setOf("ollama", "lmstudio", "vllm")
        selectedBotRole = when (key) {
            "gemini" -> "FAST_ANALYST"
            "groq" -> "SKEPTIC"
            else -> "DEEP_REASONER"
        }
        modelListResult = null
        confirmBatchDialogVisible = false
        statusNoticeVi = "Đã chọn preset ${p.name} [${p.key}] · Giao thức: ${p.protocol} · Base URL: ${p.baseUrl}"
    }

    fun resolveEffectiveApiKeyAndSaveIfEntered(): String {
        val cleanTyped = SecretVault.sanitizeCredentialInput(apiKeyInput)
        if (cleanTyped.isNotEmpty() && !noAuthChecked) {
            checkerEngine.saveApiKeyForProvider(selectedProviderKey, cleanTyped)
            val slot = checkerEngine.slotForProvider(selectedProviderKey)
            onSaveAiKey(slot, cleanTyped)
            apiKeyInput = ""
            localVaultTick++
        }
        return checkerEngine.resolveApiKey(selectedProviderKey, noAuthChecked)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_api_checker_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, tint = AstraCyanAccent)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI API Checker v${AiApiCheckerEngine.VERSION} & Multi-Provider Router",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Hỗ trợ 22+ Preset (OpenAI, OpenRouter, Groq, DeepSeek, Anthropic, Gemini, xAI, Ollama...) • Lấy /models • Test phản hồi • Chat thử nghiệm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Bot AI Module Status Banner
            Surface(
                color = if (aiModuleStatus == AiModuleStatus.VALIDATED_SCHEMA) {
                    AstraEmeraldGreen.copy(alpha = 0.14f)
                } else {
                    AstraAmberWarn.copy(alpha = 0.14f)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Trạng thái AI Bot: ${aiModuleStatus.labelVi} · Lưu ý: Gửi prompt kiểm tra có thể tiêu tốn quota/chi phí của nhà cung cấp.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (aiModuleStatus == AiModuleStatus.VALIDATED_SCHEMA) AstraEmeraldGreen else AstraAmberWarn,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // 1. Provider Preset Selector (22 Presets + Custom from aiai.py)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1. CHỌN NHÀ CUNG CẤP AI (${allPresetKeys.size} PRESET & CUSTOM):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AstraCyanAccent
                    )
                    Text(
                        text = if (showAllPresetsExpanded) "Thu gọn danh sách ▲" else "Xem tất cả ${allPresetKeys.size} preset ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraEmeraldGreen,
                        modifier = Modifier
                            .clickable { showAllPresetsExpanded = !showAllPresetsExpanded }
                            .padding(4.dp)
                            .testTag("toggle_all_ai_presets_button")
                    )
                }

                if (showAllPresetsExpanded) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allPresetKeys.forEach { key ->
                            val p = AiApiCheckerEngine.PROVIDERS.getValue(key)
                            val isSelected = key == selectedProviderKey
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectPreset(key) },
                                label = {
                                    Text(
                                        text = "${p.name} [$key]",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AstraEmeraldGreen.copy(alpha = 0.22f),
                                    selectedLabelColor = AstraEmeraldGreen
                                ),
                                modifier = Modifier.testTag("ai_preset_chip_$key")
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allPresetKeys.forEach { key ->
                            val p = AiApiCheckerEngine.PROVIDERS.getValue(key)
                            val isSelected = key == selectedProviderKey
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectPreset(key) },
                                label = {
                                    Text(
                                        text = "${p.name} [$key]",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AstraEmeraldGreen.copy(alpha = 0.22f),
                                    selectedLabelColor = AstraEmeraldGreen
                                ),
                                modifier = Modifier.testTag("ai_preset_chip_$key")
                            )
                        }
                    }
                }

                // Selected Provider Details & Protocol Picker
                Text(
                    text = "Đang chọn: ${currentPreset.name} [$selectedProviderKey]" +
                        (if (currentPreset.envKey.isNotBlank()) " · Biến chuẩn: ${currentPreset.envKey}" else "") +
                        (if (currentPreset.note.isNotBlank()) " · Ghi chú: ${currentPreset.note}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Giao thức API (--protocol):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val protocols = listOf(
                        "openai" to "OpenAI-compatible",
                        "anthropic" to "Anthropic Messages",
                        "gemini" to "Google Gemini"
                    )
                    protocols.forEach { (protoKey, protoLabel) ->
                        val selected = selectedProtocol == protoKey
                        FilterChip(
                            selected = selected,
                            onClick = { selectedProtocol = protoKey },
                            label = { Text(protoLabel, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AstraCyanAccent.copy(alpha = 0.22f),
                                selectedLabelColor = AstraCyanAccent
                            ),
                            modifier = Modifier.testTag("ai_protocol_chip_$protoKey")
                        )
                    }
                }

                // Base URL & Reset button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("Base URL (http:// hoặc https://)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_checker_base_url_input")
                    )
                    IconButton(
                        onClick = { baseUrlInput = currentPreset.baseUrl },
                        modifier = Modifier.testTag("ai_checker_reset_url_button")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Khôi phục Base URL mặc định",
                            tint = AstraCyanAccent
                        )
                    }
                }

                // --no-auth Checkbox & Bot Role Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { noAuthChecked = !noAuthChecked }
                    ) {
                        Checkbox(
                            checked = noAuthChecked,
                            onCheckedChange = { noAuthChecked = it },
                            modifier = Modifier.testTag("ai_checker_no_auth_checkbox")
                        )
                        Text(
                            text = "Không gửi API key (--no-auth / Local server)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // API Key Input & SecretVault save
                if (!noAuthChecked) {
                    Text(
                        text = "Khóa API trong Vault (${currentPreset.envKey.ifBlank { "CUSTOM_API_KEY" }}): $maskedKeyPreview",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (maskedKeyPreview.startsWith("Chưa")) AstraAmberWarn else AstraEmeraldGreen,
                        modifier = Modifier.testTag("ai_checker_key_preview_text")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = {
                                Text("Nhập API Key cho ${currentPreset.name} (ẩn & mã hóa AES-GCM)")
                            },
                            visualTransformation = if (showApiKeyPlaintext) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showApiKeyPlaintext = !showApiKeyPlaintext }) {
                                    Icon(
                                        imageVector = if (showApiKeyPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showApiKeyPlaintext) "Ẩn API Key" else "Hiện API Key"
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_checker_api_key_input")
                        )
                        Button(
                            onClick = {
                                val clean = SecretVault.sanitizeCredentialInput(apiKeyInput)
                                if (clean.isBlank()) {
                                    statusNoticeVi = "Vui lòng nhập API key trước khi bấm Lưu."
                                } else {
                                    val saved = checkerEngine.saveApiKeyForProvider(selectedProviderKey, clean)
                                    val slot = checkerEngine.slotForProvider(selectedProviderKey)
                                    onSaveAiKey(slot, clean)
                                    apiKeyInput = ""
                                    localVaultTick++
                                    statusNoticeVi = if (saved) {
                                        "Đã mã hóa & lưu API key cho ${currentPreset.name} vào SecretVault. Hãy bấm [1/3] Lấy danh sách Model hoặc [2/3] Test Model."
                                    } else {
                                        "Không thể lưu API key vào SecretVault."
                                    }
                                }
                            },
                            modifier = Modifier.testTag("ai_checker_save_key_button")
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Text(" Lưu Key", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 2. Step [1/3] Model Discovery (/models) & Model ID Input
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "2. BƯỚC [1/3] LẤY DANH SÁCH MODEL (--list-only) & CHỌN MODEL ID:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AstraCyanAccent
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = selectedModelInput,
                        onValueChange = { selectedModelInput = it },
                        label = { Text("Model ID cần kiểm tra / sử dụng (--model)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_checker_model_input")
                    )
                    OutlinedButton(
                        enabled = !isWorking,
                        onClick = {
                            scope.launch {
                                isWorking = true
                                val effectiveKey = resolveEffectiveApiKeyAndSaveIfEntered()
                                val timeoutSec = testTimeoutSecInput.toIntOrNull()?.coerceIn(5, 180) ?: 30
                                statusNoticeVi = "[1/3] Đang kiểm tra kết nối và lấy danh sách model từ $baseUrlInput..."
                                val res = checkerEngine.listModels(
                                    protocol = selectedProtocol,
                                    baseUrl = baseUrlInput,
                                    apiKey = effectiveKey,
                                    noAuth = noAuthChecked,
                                    timeoutSec = timeoutSec
                                )
                                modelListResult = res
                                if (res.ok && res.models.isNotEmpty()) {
                                    if (selectedModelInput.isBlank()) {
                                        selectedModelInput = res.models.first()
                                    }
                                    statusNoticeVi = "[OK] Lấy được ${res.models.size} model (${String.format(Locale.US, "%.2f", res.elapsedSec)}s). Chạm vào một model bên dưới để chọn."
                                } else {
                                    statusNoticeVi = "[!] ${res.errorMessage}. Một số API không cung cấp endpoint /models; vẫn có thể test bằng Model ID thủ công."
                                }
                                isWorking = false
                            }
                        },
                        modifier = Modifier.testTag("ai_checker_list_models_button")
                    ) {
                        Text("[1/3] Lấy /models", style = MaterialTheme.typography.labelSmall)
                    }
                }

                modelListResult?.let { listRes ->
                    if (listRes.ok && listRes.models.isNotEmpty()) {
                        Text(
                            text = "[OK] Tìm thấy ${listRes.models.size} model khả dụng (${String.format(Locale.US, "%.2f", listRes.elapsedSec)}s · ${listRes.endpointCalled}):",
                            style = MaterialTheme.typography.bodySmall,
                            color = AstraEmeraldGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (listRes.models.size > 6) {
                            OutlinedTextField(
                                value = modelSearchQuery,
                                onValueChange = { modelSearchQuery = it },
                                label = { Text("Lọc nhanh trong ${listRes.models.size} model...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val filteredModels = remember(listRes.models, modelSearchQuery) {
                            val q = modelSearchQuery.trim().lowercase(Locale.ROOT)
                            val base = if (q.isEmpty()) listRes.models else listRes.models.filter { it.lowercase(Locale.ROOT).contains(q) }
                            base.take(120)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                                .clip(RoundedCornerShape(8.dp))
                                .background(AstraSurfaceCard)
                                .padding(8.dp)
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                filteredModels.forEach { mId ->
                                    val isCurrentModel = mId == selectedModelInput
                                    FilterChip(
                                        selected = isCurrentModel,
                                        onClick = { selectedModelInput = mId },
                                        label = { Text(mId, style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AstraEmeraldGreen.copy(alpha = 0.25f),
                                            selectedLabelColor = AstraEmeraldGreen
                                        )
                                    )
                                }
                            }
                        }
                    } else if (!listRes.ok) {
                        Text(
                            text = "[!] Không lấy được model list: ${listRes.errorMessage}\nMột số API không cung cấp endpoint /models; vẫn có thể test bằng Model ID thủ công ở ô trên.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AstraAmberWarn
                        )
                    }
                }
            }

            // 3. Step [2/3] Prompt Test & Batch Test & Bot Integration
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "3. BƯỚC [2/3] GỬI PROMPT KIỂM TRA MODEL & ĐO TỐC ĐỘ PHẢN HỒI:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AstraCyanAccent
                )

                OutlinedTextField(
                    value = testPromptInput,
                    onValueChange = { testPromptInput = it },
                    label = { Text("Prompt test (--prompt)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_checker_prompt_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = testMaxTokensInput,
                        onValueChange = { testMaxTokensInput = it },
                        label = { Text("Max tokens") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = testTimeoutSecInput,
                        onValueChange = { testTimeoutSecInput = it },
                        label = { Text("Timeout (s)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = batchMaxModelsInput,
                        onValueChange = { batchMaxModelsInput = it },
                        label = { Text("Giới hạn --test-all") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Single Model Check & Batch Model Check buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = !isWorking,
                        onClick = {
                            scope.launch {
                                val targetModel = selectedModelInput.trim()
                                if (targetModel.isBlank()) {
                                    statusNoticeVi = "Chưa có Model ID. Hãy nhập Model ID hoặc bấm [1/3] Lấy /models để chọn."
                                    return@launch
                                }
                                isWorking = true
                                val effectiveKey = resolveEffectiveApiKeyAndSaveIfEntered()
                                if (effectiveKey.isBlank() && !noAuthChecked) {
                                    statusNoticeVi = "Thiếu API key cho ${currentPreset.name}. Hãy nhập API key hoặc bật --no-auth nếu là local server."
                                    isWorking = false
                                    return@launch
                                }
                                val maxTok = testMaxTokensInput.toIntOrNull()?.coerceAtLeast(1) ?: 32
                                val timeoutSec = testTimeoutSecInput.toIntOrNull()?.coerceIn(3, 180) ?: 60
                                statusNoticeVi = "[2/3] Đang gửi prompt kiểm tra tới model '$targetModel' (${currentPreset.name})..."
                                val report = checkerEngine.runModelCheckSuite(
                                    providerKey = selectedProviderKey,
                                    providerName = currentPreset.name,
                                    protocol = selectedProtocol,
                                    baseUrl = baseUrlInput,
                                    apiKey = effectiveKey,
                                    noAuth = noAuthChecked,
                                    modelsToTest = listOf(targetModel),
                                    prompt = testPromptInput.ifBlank { AiApiCheckerEngine.DEFAULT_PROMPT },
                                    maxTokens = maxTok,
                                    timeoutSec = timeoutSec,
                                    maxModelsCap = 1
                                )
                                latestSuiteReport = report
                                val first = report.results.firstOrNull()
                                if (first != null && first.ok) {
                                    statusNoticeVi = "[PASS] Model '$targetModel' phản hồi thành công (HTTP ${first.status} | ${String.format(Locale.US, "%.2f", first.elapsedSec)}s${first.usage}). Bạn có thể mở Chat thử ngay hoặc Áp dụng vào Bot!"
                                } else if (first != null) {
                                    statusNoticeVi = "[FAIL] Model '$targetModel' lỗi HTTP ${first.status}: ${first.explanationVi}"
                                }
                                isWorking = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AstraEmeraldGreen),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_checker_test_single_button")
                    ) {
                        Text("[2/3] Test Model Đang Chọn", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        enabled = !isWorking,
                        onClick = {
                            val fetched = modelListResult?.models.orEmpty()
                            if (fetched.isEmpty()) {
                                statusNoticeVi = "Không có danh sách model để chạy --test-all. Hãy bấm [1/3] Lấy /models trước hoặc test từng Model ID."
                            } else {
                                confirmBatchDialogVisible = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_checker_test_all_button")
                    ) {
                        Text("Test Hàng Loạt (--test-all)", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Batch confirmation prompt (matching aiai.py confirmation before --test-all)
                if (confirmBatchDialogVisible) {
                    val fetched = modelListResult?.models.orEmpty()
                    val cap = (batchMaxModelsInput.toIntOrNull() ?: 10).coerceIn(1, 50)
                    val targetBatch = fetched.take(cap)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AstraAmberWarn.copy(alpha = 0.14f))
                            .border(1.dp, AstraAmberWarn, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "XÁC NHẬN TEST HÀNG LOẠT (--test-all): Sắp gửi request tới ${targetBatch.size}/${fetched.size} model đầu tiên. Mỗi request có thể tiêu tốn quota/chi phí.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AstraAmberWarn,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !isWorking,
                                onClick = {
                                    confirmBatchDialogVisible = false
                                    scope.launch {
                                        isWorking = true
                                        val effectiveKey = resolveEffectiveApiKeyAndSaveIfEntered()
                                        val maxTok = testMaxTokensInput.toIntOrNull()?.coerceAtLeast(1) ?: 32
                                        val timeoutSec = testTimeoutSecInput.toIntOrNull()?.coerceIn(3, 180) ?: 60
                                        statusNoticeVi = "[2/3] Đang test lần lượt ${targetBatch.size} model trên ${currentPreset.name}..."
                                        val report = checkerEngine.runModelCheckSuite(
                                            providerKey = selectedProviderKey,
                                            providerName = currentPreset.name,
                                            protocol = selectedProtocol,
                                            baseUrl = baseUrlInput,
                                            apiKey = effectiveKey,
                                            noAuth = noAuthChecked,
                                            modelsToTest = targetBatch,
                                            prompt = testPromptInput.ifBlank { AiApiCheckerEngine.DEFAULT_PROMPT },
                                            maxTokens = maxTok,
                                            timeoutSec = timeoutSec,
                                            maxModelsCap = cap
                                        )
                                        latestSuiteReport = report
                                        statusNoticeVi = "[3/3] Hoàn tất --test-all: ${report.passedCount}/${report.totalCount} model phản hồi thành công."
                                        isWorking = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AstraAmberWarn)
                            ) {
                                Text("Xác nhận YES (Chạy ${targetBatch.size} model)", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = { confirmBatchDialogVisible = false }) {
                                Text("Hủy", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Role selector & Apply to Astra Bot Provider Router
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Vai trò trong Bot:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf("FAST_ANALYST", "SKEPTIC", "DEEP_REASONER").forEach { roleOpt ->
                        FilterChip(
                            selected = selectedBotRole == roleOpt,
                            onClick = { selectedBotRole = roleOpt },
                            label = { Text(roleOpt, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Button(
                    enabled = !isWorking,
                    onClick = {
                        resolveEffectiveApiKeyAndSaveIfEntered()
                        val slot = checkerEngine.slotForProvider(selectedProviderKey)
                        val matchingPass = latestSuiteReport?.results?.firstOrNull {
                            it.model.equals(selectedModelInput.trim(), ignoreCase = true) && it.ok
                        }
                        val latencyMs = matchingPass?.let { (it.elapsedSec * 1000.0).toLong().coerceAtLeast(1L) }
                        onApplyTestedProviderToBot(
                            selectedProviderKey,
                            "${currentPreset.name} (${selectedModelInput.trim()})",
                            selectedProtocol,
                            baseUrlInput.trim().trimEnd('/'),
                            selectedModelInput.trim(),
                            selectedBotRole,
                            slot,
                            matchingPass != null,
                            latencyMs
                        )
                        statusNoticeVi = if (matchingPass != null) {
                            "Đã lưu & kích hoạt '${currentPreset.name} (${selectedModelInput.trim()})' cho vai trò $selectedBotRole (VERIFIED_HEALTHY · ${latencyMs}ms)!"
                        } else {
                            "Đã lưu cấu hình '${currentPreset.name} (${selectedModelInput.trim()})' cho vai trò $selectedBotRole. Hãy bấm '[2/3] Test Model' hoặc 'Chạy phân tích AI' để xác nhận Health Check."
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_custom_ai_provider_button")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Text(
                        text = " Lưu & Gắn Provider Này Vào Bot ($selectedBotRole)",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Status Notice Banner
                Text(
                    text = statusNoticeVi,
                    style = MaterialTheme.typography.bodySmall,
                    color = AstraCyanAccent,
                    modifier = Modifier.testTag("ai_checker_status_notice_text")
                )
            }

            // 4. Step [3/3] Summary & Detailed Results & JSON Report (--json-out)
            latestSuiteReport?.let { report ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (report.allPassed) AstraEmeraldGreen else AstraAmberWarn,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                        .testTag("ai_checker_suite_report_box"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[3/3] TỔNG KẾT · Provider: ${report.providerKey} | ${report.passedCount}/${report.totalCount} PASS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (report.allPassed) AstraEmeraldGreen else AstraAmberWarn
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { showJsonReportBox = !showJsonReportBox },
                                modifier = Modifier.testTag("ai_checker_toggle_json_button")
                            ) {
                                Text(if (showJsonReportBox) "Ẩn JSON" else "Báo cáo JSON", style = MaterialTheme.typography.labelSmall)
                            }
                            if (report.passedCount > 0) {
                                Button(
                                    onClick = {
                                        val firstPassModel = report.results.firstOrNull { it.ok }?.model
                                        if (!firstPassModel.isNullOrBlank()) {
                                            selectedModelInput = firstPassModel
                                        }
                                        showChatSession = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AstraCyanAccent),
                                    modifier = Modifier.testTag("ai_checker_open_chat_after_pass_button")
                                ) {
                                    Icon(Icons.Default.Chat, contentDescription = null)
                                    Text(" Chat thử ngay", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    report.results.forEachIndexed { idx, r ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AstraSurfaceCard)
                                .border(
                                    1.dp,
                                    if (r.ok) AstraEmeraldGreen.copy(alpha = 0.5f) else AstraCrimsonDanger.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                                .testTag("ai_checker_result_item_$idx"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "${if (r.ok) "[PASS]" else "[FAIL]"} ${r.model} · HTTP ${if (r.status > 0) r.status else "NETWORK"} | ${String.format(Locale.US, "%.2f", r.elapsedSec)}s${r.usage}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (r.ok) AstraEmeraldGreen else AstraCrimsonDanger
                            )
                            if (r.ok) {
                                Text(
                                    text = "Phản hồi: ${r.text.replace("\n", " ").take(500)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                Text(
                                    text = "Nguyên nhân: ${r.explanationVi}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AstraAmberWarn
                                )
                                if (r.error.isNotBlank()) {
                                    Text(
                                        text = "Chi tiết: ${r.error.take(400)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (showJsonReportBox) {
                        val jsonStr = remember(report) { report.toJsonReportString() }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "JSON Report (--json-out · Đã ẩn hoàn toàn API Key):",
                                style = MaterialTheme.typography.labelSmall,
                                color = AstraCyanAccent
                            )
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("ai_checker_report", jsonStr))
                                    statusNoticeVi = "Đã sao chép báo cáo JSON vào bộ nhớ tạm."
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Text(" Copy JSON", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            text = jsonStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AstraSurfaceCard)
                                .padding(8.dp)
                        )
                    }
                }
            }

            // 5. Multi-Turn Interactive Chat Session (--chat / chat_loop from aiai.py)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .testTag("ai_checker_chat_section"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = AstraCyanAccent)
                        Text(
                            text = "4. CHAT THỬ NGHIỆM ĐA LƯỢT (--chat · Không lưu API key)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AstraCyanAccent
                        )
                    }
                    OutlinedButton(
                        onClick = { showChatSession = !showChatSession },
                        modifier = Modifier.testTag("ai_checker_toggle_chat_button")
                    ) {
                        Text(if (showChatSession) "Thu gọn Chat" else "Mở Phiên Chat", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (showChatSession) {
                    Text(
                        text = "Provider: $selectedProviderKey | Model: ${selectedModelInput.ifBlank { "(chưa chọn)" }} | Protocol: $selectedProtocol | Lượt thành công: $chatTotalTurns",
                        style = MaterialTheme.typography.labelSmall,
                        color = AstraEmeraldGreen
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chatSystemPrompt,
                            onValueChange = { chatSystemPrompt = it },
                            label = { Text("System Prompt (--system)") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = chatMaxTokensInput,
                            onValueChange = { chatMaxTokensInput = it },
                            label = { Text("Chat Max Tokens") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Quick command buttons matching aiai.py (/help, /reset, /system off, /save)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                chatHistory.add(
                                    AiChatMessage(
                                        role = "assistant",
                                        content = "Lệnh hỗ trợ: /help (Hướng dẫn), /reset (Xóa lịch sử), /model MODEL_ID (Đổi model & xóa lịch sử), /system TEXT (Đặt system prompt), /system off (Tắt system prompt), /save (Xuất JSON hội thoại không kèm API key).",
                                        isCommandOutput = true
                                    )
                                )
                            }
                        ) {
                            Text("/help", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                chatHistory.clear()
                                chatTotalTurns = 0
                                savedChatTranscriptJson = null
                                statusNoticeVi = "Đã xóa lịch sử hội thoại (/reset)."
                            },
                            modifier = Modifier.testTag("ai_checker_chat_reset_button")
                        ) {
                            Text("/reset", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                chatSystemPrompt = ""
                                statusNoticeVi = "Đã tắt system prompt (/system off)."
                            }
                        ) {
                            Text("/system off", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                val msgsArr = JSONArray()
                                if (chatSystemPrompt.isNotBlank()) {
                                    msgsArr.put(JSONObject().put("role", "system").put("content", chatSystemPrompt))
                                }
                                chatHistory.filter { !it.isCommandOutput }.forEach { m ->
                                    msgsArr.put(JSONObject().put("role", m.role).put("content", m.content))
                                }
                                val transcript = JSONObject().apply {
                                    put("provider", selectedProviderKey)
                                    put("protocol", selectedProtocol)
                                    put("base_url", baseUrlInput.trim().trimEnd('/'))
                                    put("model", selectedModelInput.trim())
                                    put("saved_at", fmt.format(Date()))
                                    put("messages", msgsArr)
                                }.toString(2)
                                savedChatTranscriptJson = transcript
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("ai_chat_history", transcript))
                                statusNoticeVi = "Đã xuất & sao chép JSON hội thoại (/save · không lưu API key)."
                            }
                        ) {
                            Text("/save JSON", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Conversation history box
                    if (chatHistory.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .clip(RoundedCornerShape(8.dp))
                                .background(AstraSurfaceCard)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            chatHistory.forEach { msg ->
                                val prefix = when {
                                    msg.isCommandOutput -> "[SYSTEM CMD]"
                                    msg.role == "user" -> "Bạn"
                                    else -> "AI"
                                }
                                val color = when {
                                    msg.isCommandOutput -> AstraAmberWarn
                                    msg.role == "user" -> AstraCyanAccent
                                    else -> AstraEmeraldGreen
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "$prefix: ${msg.content}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = color
                                    )
                                    if (msg.latencySec != null) {
                                        Text(
                                            text = "[${String.format(Locale.US, "%.2f", msg.latencySec)}s${msg.usageSummary}]",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    savedChatTranscriptJson?.let { transcript ->
                        Text(
                            text = "Transcript JSON (/save):\n$transcript",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AstraSurfaceCard)
                                .padding(8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = chatUserInput,
                            onValueChange = { chatUserInput = it },
                            label = { Text("Tin nhắn hoặc lệnh (/help, /reset, /model ID...)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_checker_chat_input")
                        )
                        Button(
                            enabled = !isWorking,
                            onClick = {
                                val text = chatUserInput.trim()
                                if (text.isEmpty()) return@Button
                                chatUserInput = ""

                                // Handle slash commands from aiai.py
                                val low = text.lowercase(Locale.ROOT)
                                if (low in setOf("/exit", "/quit", "/q")) {
                                    showChatSession = false
                                    statusNoticeVi = "Đã đóng phiên chat ($chatTotalTurns lượt thành công)."
                                    return@Button
                                }
                                if (low == "/help") {
                                    chatHistory.add(
                                        AiChatMessage(
                                            role = "assistant",
                                            content = "Lệnh chat: /help, /exit, /reset, /model MODEL_ID, /system TEXT, /system off, /save",
                                            isCommandOutput = true
                                        )
                                    )
                                    return@Button
                                }
                                if (low == "/reset") {
                                    chatHistory.clear()
                                    chatTotalTurns = 0
                                    savedChatTranscriptJson = null
                                    return@Button
                                }
                                if (low.startsWith("/model ")) {
                                    val newModel = text.substring(7).trim()
                                    if (newModel.isNotBlank()) {
                                        selectedModelInput = newModel
                                        chatHistory.clear()
                                        chatHistory.add(
                                            AiChatMessage(
                                                role = "assistant",
                                                content = "Đã chuyển sang model: $newModel. Lịch sử cũ được xóa để tránh trộn ngữ cảnh giữa model.",
                                                isCommandOutput = true
                                            )
                                        )
                                    }
                                    return@Button
                                }
                                if (low == "/system off") {
                                    chatSystemPrompt = ""
                                    chatHistory.add(
                                        AiChatMessage(
                                            role = "assistant",
                                            content = "Đã tắt system prompt.",
                                            isCommandOutput = true
                                        )
                                    )
                                    return@Button
                                }
                                if (low.startsWith("/system ")) {
                                    chatSystemPrompt = text.substring(8).trim()
                                    chatHistory.add(
                                        AiChatMessage(
                                            role = "assistant",
                                            content = "Đã cập nhật system prompt. Lịch sử chat được giữ nguyên.",
                                            isCommandOutput = true
                                        )
                                    )
                                    return@Button
                                }

                                // Normal multi-turn chat request
                                scope.launch {
                                    isWorking = true
                                    val effectiveKey = resolveEffectiveApiKeyAndSaveIfEntered()
                                    val reqMessages = mutableListOf<AiChatMessage>()
                                    if (chatSystemPrompt.isNotBlank()) {
                                        reqMessages.add(AiChatMessage(role = "system", content = chatSystemPrompt.trim()))
                                    }
                                    reqMessages.addAll(chatHistory.filter { !it.isCommandOutput })
                                    reqMessages.add(AiChatMessage(role = "user", content = text))

                                    val maxTok = chatMaxTokensInput.toIntOrNull()?.coerceAtLeast(1) ?: 1024
                                    val timeoutSec = testTimeoutSecInput.toIntOrNull()?.coerceIn(3, 180) ?: 60
                                    val res = checkerEngine.chatRequest(
                                        protocol = selectedProtocol,
                                        baseUrl = baseUrlInput,
                                        apiKey = effectiveKey,
                                        noAuth = noAuthChecked,
                                        model = selectedModelInput,
                                        messages = reqMessages,
                                        maxTokens = maxTok,
                                        timeoutSec = timeoutSec
                                    )

                                    if (res.ok) {
                                        chatHistory.add(AiChatMessage(role = "user", content = text))
                                        chatHistory.add(
                                            AiChatMessage(
                                                role = "assistant",
                                                content = res.text,
                                                latencySec = res.elapsedSec,
                                                usageSummary = res.usage
                                            )
                                        )
                                        chatTotalTurns++
                                        // Trim history to last 40 messages (20 turns) as in aiai.py
                                        val nonCmdCount = chatHistory.count { !it.isCommandOutput }
                                        if (nonCmdCount > 40) {
                                            while (chatHistory.size > 40) {
                                                chatHistory.removeAt(0)
                                            }
                                        }
                                    } else {
                                        chatHistory.add(
                                            AiChatMessage(
                                                role = "assistant",
                                                content = "[API ERROR: HTTP ${if (res.status > 0) res.status else "NETWORK"}] ${res.explanationVi} (${res.error.take(300)}). Tin nhắn lỗi không được thêm vào lịch sử.",
                                                isCommandOutput = true
                                            )
                                        )
                                    }
                                    isWorking = false
                                }
                            },
                            modifier = Modifier.testTag("ai_checker_chat_send_button")
                        ) {
                            Text("Gửi")
                        }
                    }
                }
            }

            // 6. Active Bot Providers List & Strict JSON Schema 1.0 Evaluation
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "5. DANH SÁCH AI PROVIDER ĐANG ĐĂNG KÝ TRONG BOT & KIỂM ĐỊNH SCHEMA 1.0:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AstraCyanAccent
                )

                providers.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AstraSurfaceCard)
                            .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = p.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Model: ${p.modelId} · Giao thức: ${p.apiType} · Role: ${p.role}\nBase URL: ${p.baseUrl}\nHealth: ${p.healthStatus} · Enabled=${p.enabled}" +
                                    (if (p.lastLatencyMs > 0L) " · ${p.lastLatencyMs}ms" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.healthStatus == "VERIFIED_HEALTHY") AstraEmeraldGreen else AstraAmberWarn
                            )
                        }
                        Switch(
                            checked = p.enabled && p.healthStatus == "VERIFIED_HEALTHY",
                            onCheckedChange = { onToggleProvider(p, it) }
                        )
                    }
                }

                Button(
                    onClick = onTriggerAiReview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("trigger_ai_review_button")
                ) {
                    Text("Chạy phân tích AI & Kiểm định Strict JSON Schema 1.0")
                }

                if (recentAiDecisions.isNotEmpty()) {
                    val lastAi = recentAiDecisions.first()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AstraSurfaceCard)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Kết quả AI Bot gần nhất (${lastAi.modelId} · ${lastAi.validationStatus} · ${lastAi.latencyMs}ms)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (lastAi.validationStatus == "VALID") AstraEmeraldGreen else AstraAmberWarn
                        )
                        Text(
                            text = "${lastAi.symbol}: ${lastAi.recommendation} (${lastAi.directionCandidate}) · Score=${lastAi.score ?: "null"} · CalibratedProb=${lastAi.calibratedProbability ?: "null (INV-11)"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = lastAi.reasonVi,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
