package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AstraDatabase
import com.example.data.local.SystemEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MOD-01 & INV-16 · Boot Recovery Receiver
 * Marks system state as requiring reconciliation after device reboot before allowing any new trades.
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = AstraDatabase.getInstance(context).astraDao()
                    val settings = dao.getSettingsOnce()
                    if (settings != null) {
                        dao.upsertSettings(
                            settings.copy(
                                botState = "RECONCILIATION_REQUIRED",
                                liveExplicitlyArmed = false, // Never auto-enable LIVE after reboot (Section 16.3)
                                environmentMode = "TEST",
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                    }
                    dao.insertSystemEvent(
                        SystemEventEntity(
                            category = "RECONCILE",
                            level = "WARN",
                            titleVi = "Thiết bị vừa khởi động lại (BOOT_COMPLETED)",
                            detailVi = "Đã khóa chế độ LIVE và đặt trạng thái RECONCILIATION_REQUIRED (INV-16). Người dùng cần mở ứng dụng để đối soát với Binance.",
                            correlationId = "boot-${System.currentTimeMillis()}"
                        )
                    )
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
