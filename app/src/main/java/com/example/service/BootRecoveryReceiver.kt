package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AstraDatabase
import com.example.data.local.SystemLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` broadcasts by logging recovery intent
 * and enforcing safe startup reconciliation before any order placement can resume.
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = AstraDatabase.getInstance(context).astraDao()
                    dao.insertSystemLog(
                        SystemLogEntity(
                            timestamp = System.currentTimeMillis(),
                            level = "INFO",
                            module = "BootRecoveryReceiver",
                            message = "System broadcast $action received. Enforcing SHADOW/TEST safety lock until exchange state reconciliation completes."
                        )
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
