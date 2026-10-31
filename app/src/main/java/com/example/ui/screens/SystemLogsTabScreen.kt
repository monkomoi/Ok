package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AiRequestEntity
import com.example.data.local.MarketFeatureEntity
import com.example.data.local.RiskEventEntity
import com.example.data.local.SystemEventEntity
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
fun SystemLogsTabScreen(
    riskEvents: List<RiskEventEntity>,
    systemEvents: List<SystemEventEntity>,
    marketFeatures: List<MarketFeatureEntity> = emptyList(),
    aiRequests: List<AiRequestEntity> = emptyList()
) {
    var selectedCategory by remember { mutableStateOf("ALL") }
    val categories = listOf("ALL", "RECONCILE", "EXECUTION", "SECURITY", "THERMAL")
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Operational Reality & Risk Disclaimer Card (Section 0.1.10 & Section 1.2)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Tuyên bố Giới hạn Vận hành & Rủi ro Thực tế (Mục 1.2 & 15.1)",
                    style = MaterialTheme.typography.titleMedium,
                    color = AstraAmberWarn
                )
                Text(
                    text = stringResource(R.string.disclaimer_no_guarantee),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Kiểm toán 17 bảng Room (MOD-02): Đã ghi nhận ${marketFeatures.size} bản ghi market_features & ${aiRequests.size} bản ghi ai_requests (đã che bí mật).",
                    style = MaterialTheme.typography.labelSmall,
                    color = AstraCyanAccent
                )
            }
        }

        // 2. Risk & Circuit Breaker Timeline
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Sự kiện Rủi ro & Circuit Breaker (${riskEvents.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                if (riskEvents.isEmpty()) {
                    Text(
                        text = "Chưa có vi phạm rủi ro hay Circuit Breaker nào kích hoạt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstraEmeraldGreen
                    )
                } else {
                    riskEvents.take(10).forEach { ev ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "[${ev.severity}] ${ev.triggerCode}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (ev.severity == "CRITICAL") AstraCrimsonDanger else AstraAmberWarn
                                )
                                Text(
                                    text = timeFormatter.format(Date(ev.occurredAtMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = ev.actionTaken,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = ev.detailVi,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 3. System Audit Timeline (MOD-02 / Section 12.5 — Redacted of all secrets)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Nhật ký Hệ thống & Đối soát (Đã làm sạch chữ ký/Secret)",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                val filtered = if (selectedCategory == "ALL") {
                    systemEvents
                } else {
                    systemEvents.filter { it.category == selectedCategory }
                }

                if (filtered.isEmpty()) {
                    Text(
                        text = "Không có bản ghi nhật ký trong nhóm $selectedCategory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    filtered.take(25).forEach { sys ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "[${sys.category} · ${sys.level}] ${sys.titleVi}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (sys.level) {
                                        "ERROR" -> AstraCrimsonDanger
                                        "WARN" -> AstraAmberWarn
                                        else -> AstraCyanAccent
                                    }
                                )
                                Text(
                                    text = timeFormatter.format(Date(sys.occurredAtMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = sys.detailVi,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
