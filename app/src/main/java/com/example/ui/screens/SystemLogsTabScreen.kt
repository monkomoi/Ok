package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.RiskLogEntity
import com.example.data.local.SystemLogEntity
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
    systemLogs: List<SystemLogEntity>,
    riskLogs: List<RiskLogEntity>,
    onPruneLogs: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("system_logs_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AstraSurfaceCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Structured Audit & Risk Telemetry",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "System Logs: ${systemLogs.size} · Risk Events: ${riskLogs.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onPruneLogs,
                        modifier = Modifier.testTag("prune_logs_button")
                    ) {
                        Text("Prune Old")
                    }
                }
            }
        }

        if (riskLogs.isNotEmpty()) {
            item {
                Text(
                    text = "Risk & Kill-Switch Events",
                    style = MaterialTheme.typography.labelLarge,
                    color = AstraAmberWarn
                )
            }
            items(riskLogs.take(20), key = { "risk_${it.id}" }) { rLog ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AstraSurfaceCard)
                        .border(1.dp, AstraCrimsonDanger.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "[${rLog.severity}] ${rLog.eventType} · ${timeFormatter.format(Date(rLog.timestamp))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AstraCrimsonDanger
                    )
                    Text(
                        text = rLog.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Action: ${rLog.actionTaken}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AstraCyanAccent
                    )
                }
            }
        }

        item {
            Text(
                text = "System & Forecast Runtime Logs",
                style = MaterialTheme.typography.labelLarge,
                color = AstraCyanAccent
            )
        }

        items(systemLogs, key = { "sys_${it.id}" }) { log ->
            val levelColor = when (log.level) {
                "ERROR", "CRITICAL" -> AstraCrimsonDanger
                "WARN" -> AstraAmberWarn
                else -> AstraEmeraldGreen
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AstraSurfaceCard)
                    .border(1.dp, AstraSurfaceOutline, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "[${log.level}] ${log.module} · ${timeFormatter.format(Date(log.timestamp))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor
                )
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
