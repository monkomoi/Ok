package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

data class RuntimeTelemetryState(
    val serviceActive: Boolean = false,
    val loopRunning: Boolean = false,
    val activeLoopInstances: Int = 0,
    val heartbeatCount: Long = 0L,
    val lastHeartbeatUtcMs: Long = 0L,
    val lastListenKeyKeepAliveUtcMs: Long = 0L,
    val wsReconnectAttempts: Int = 0,
    val isMarketStreamStale: Boolean = false,
    val postReconnectReconcileCount: Int = 0,
    val statusNoteVi: String = "Chưa khởi chạy"
)

/**
 * MOD-01 & P1-3.5 · 24/7 Background Runtime & Foreground Service
 * - Maintains a singleton CoroutineScope (`SupervisorJob() + Dispatchers.Default`) guarded by `AtomicBoolean`
 *   so repeated `START_STICKY` or `onStartCommand` invocations never spawn duplicate background loops.
 * - Executes periodic heartbeat monitoring, stale market stream detection, WebSocket exponential backoff
 *   with jitter reconnect scheduling, User Data Stream `listenKey` keep-alive (every 25 minutes),
 *   and post-reconnect REST reconciliation.
 * - Acquires `PARTIAL_WAKE_LOCK` only with a bounded timeout (max 10 minutes per cycle).
 */
class TradingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null
    private var keepAliveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopRuntimeLoops()
                releaseBoundedWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildSilentPersistentNotification()
                startForeground(NOTIFICATION_ID, notification)
                acquireBoundedWakeLockIfNeeded()
                startSingletonRuntimeLoopsIfNeeded()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopRuntimeLoops()
        serviceScope.cancel()
        releaseBoundedWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSingletonRuntimeLoopsIfNeeded() {
        if (!singletonLoopGuard.compareAndSet(false, true)) {
            // Singleton loop is already active; do not spawn duplicate monitoring loops.
            return
        }
        val activeInstances = activeLoopCounter.incrementAndGet()
        _runtimeTelemetry.value = _runtimeTelemetry.value.copy(
            serviceActive = true,
            loopRunning = true,
            activeLoopInstances = activeInstances,
            statusNoteVi = "Đang chạy tiến trình giám sát ngầm 24/7 (Singleton Loop #1)"
        )

        monitoringJob = serviceScope.launch {
            while (isActive && singletonLoopGuard.get()) {
                val nowMs = System.currentTimeMillis()
                acquireBoundedWakeLockIfNeeded()
                try {
                    onRuntimeHeartbeatTick(nowMs)
                } catch (e: Exception) {
                    val errMsg = "Lỗi heartbeat runtime: ${e.javaClass.simpleName}"
                    _runtimeTelemetry.value = _runtimeTelemetry.value.copy(statusNoteVi = errMsg)
                    try {
                        errorCallback?.invoke(errMsg)
                    } catch (_: Exception) {
                    }
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        keepAliveJob = serviceScope.launch {
            while (isActive && singletonLoopGuard.get()) {
                delay(LISTEN_KEY_KEEPALIVE_INTERVAL_MS)
                val nowMs = System.currentTimeMillis()
                executeListenKeyKeepAliveTick(nowMs)
            }
        }
    }

    private fun stopRuntimeLoops() {
        if (singletonLoopGuard.compareAndSet(true, false)) {
            val remaining = activeLoopCounter.decrementAndGet().coerceAtLeast(0)
            monitoringJob?.cancel()
            keepAliveJob?.cancel()
            monitoringJob = null
            keepAliveJob = null
            _runtimeTelemetry.value = _runtimeTelemetry.value.copy(
                serviceActive = false,
                loopRunning = false,
                activeLoopInstances = remaining,
                statusNoteVi = "Đã dừng tiến trình giám sát ngầm"
            )
        }
    }

    private suspend fun onRuntimeHeartbeatTick(nowMs: Long) {
        val prev = _runtimeTelemetry.value
        val stale = isStreamStale(
            lastMessageAtMs = lastStreamMessageUtcMs,
            nowMs = nowMs,
            maxStaleMs = MAX_STREAM_STALE_MS
        )
        var reconnectAttempts = prev.wsReconnectAttempts
        var postReconcileCount = prev.postReconnectReconcileCount

        if (stale && lastStreamMessageUtcMs > 0L) {
            reconnectAttempts += 1
            reconnectCallback?.invoke(reconnectAttempts)
            reconcileCallback?.invoke()
            postReconcileCount += 1
        } else {
            heartbeatCallback?.invoke()
        }

        _runtimeTelemetry.value = prev.copy(
            heartbeatCount = prev.heartbeatCount + 1L,
            lastHeartbeatUtcMs = nowMs,
            wsReconnectAttempts = reconnectAttempts,
            isMarketStreamStale = stale,
            postReconnectReconcileCount = postReconcileCount,
            statusNoteVi = if (stale) {
                "Phát hiện luồng dữ liệu trễ -> Đã kích hoạt kết nối lại & đối soát REST"
            } else {
                "Heartbeat #${prev.heartbeatCount + 1L} ổn định"
            }
        )
    }

    private fun acquireBoundedWakeLockIfNeeded() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AstraTrader::BoundedReconcileWakeLock"
            ).apply {
                setReferenceCounted(false)
                // Bounded 10-minute timeout so WakeLock is never held infinitely (MOD-01)
                acquire(10 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseBoundedWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Astra Trader Foreground Runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo thường trực không âm thanh cho tiến trình giám sát Astra Trader V5.1"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildSilentPersistentNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "astra_trader_runtime_channel_v51"
        const val NOTIFICATION_ID = 5101
        const val ACTION_START_SERVICE = "com.example.action.START_ASTRA_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_ASTRA_SERVICE"

        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val LISTEN_KEY_KEEPALIVE_INTERVAL_MS = 25 * 60 * 1_000L
        const val MAX_STREAM_STALE_MS = 45_000L
        const val BASE_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L

        private val singletonLoopGuard = AtomicBoolean(false)
        private val activeLoopCounter = AtomicInteger(0)

        @Volatile
        var lastStreamMessageUtcMs: Long = 0L

        private var heartbeatCallback: (suspend () -> Unit)? = null
        private var reconcileCallback: (suspend () -> Unit)? = null
        private var keepAliveCallback: (suspend () -> Unit)? = null
        private var keepAliveVerifiedCallback: (suspend () -> Boolean)? = null
        private var reconnectCallback: (suspend (Int) -> Unit)? = null
        private var errorCallback: (suspend (String) -> Unit)? = null

        private val _runtimeTelemetry = MutableStateFlow(RuntimeTelemetryState())
        val runtimeTelemetry: StateFlow<RuntimeTelemetryState> = _runtimeTelemetry.asStateFlow()

        fun registerRuntimeCallbacks(
            onHeartbeat: (suspend () -> Unit)? = null,
            onReconcile: (suspend () -> Unit)? = null,
            onKeepAlive: (suspend () -> Unit)? = null,
            onReconnect: (suspend (Int) -> Unit)? = null,
            onKeepAliveVerified: (suspend () -> Boolean)? = null,
            onError: (suspend (String) -> Unit)? = null
        ) {
            heartbeatCallback = onHeartbeat
            reconcileCallback = onReconcile
            keepAliveCallback = onKeepAlive
            reconnectCallback = onReconnect
            keepAliveVerifiedCallback = onKeepAliveVerified
            errorCallback = onError
        }

        /**
         * P1-01 & P1-05 · Executes a listenKey keep-alive tick and ONLY updates `lastListenKeyKeepAliveUtcMs`
         * when a registered callback actually runs and succeeds (returns true).
         */
        suspend fun executeListenKeyKeepAliveTick(nowMs: Long = System.currentTimeMillis()): Boolean {
            return try {
                val succeeded = when {
                    keepAliveVerifiedCallback != null -> keepAliveVerifiedCallback?.invoke() == true
                    keepAliveCallback != null -> {
                        keepAliveCallback?.invoke()
                        true
                    }
                    else -> false
                }
                if (succeeded) {
                    _runtimeTelemetry.value = _runtimeTelemetry.value.copy(
                        lastListenKeyKeepAliveUtcMs = nowMs,
                        statusNoteVi = "Đã xác nhận gia hạn listenKey User Data Stream lúc $nowMs"
                    )
                } else {
                    _runtimeTelemetry.value = _runtimeTelemetry.value.copy(
                        statusNoteVi = "Gia hạn listenKey chưa thành công hoặc chưa đăng ký callback (giữ nguyên mốc cũ)"
                    )
                }
                succeeded
            } catch (e: Exception) {
                val errMsg = "Lỗi gia hạn listenKey: ${e.javaClass.simpleName}"
                _runtimeTelemetry.value = _runtimeTelemetry.value.copy(statusNoteVi = errMsg)
                try {
                    errorCallback?.invoke(errMsg)
                } catch (_: Exception) {
                }
                false
            }
        }

        fun recordStreamMessageReceived(timestampUtcMs: Long = System.currentTimeMillis()) {
            lastStreamMessageUtcMs = timestampUtcMs
        }

        /**
         * P1-3.5 · Computes exponential backoff with bounded jitter for WebSocket reconnects:
         * `min(MAX_RECONNECT_DELAY_MS, BASE_RECONNECT_DELAY_MS * 2^attempt) + jitter(0..450ms)`.
         */
        fun computeReconnectDelayMs(attempt: Int, deterministicJitterMs: Long? = null): Long {
            val safeAttempt = attempt.coerceIn(0, 10)
            val expDelay = min(MAX_RECONNECT_DELAY_MS, BASE_RECONNECT_DELAY_MS * (1L shl safeAttempt))
            val jitter = deterministicJitterMs?.coerceIn(0L, 450L) ?: Random.Default.nextLong(0L, 451L)
            return (expDelay + jitter).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }

        /**
         * P1-3.5 · Detects whether a WebSocket stream is stale beyond the allowed threshold.
         */
        fun isStreamStale(
            lastMessageAtMs: Long,
            nowMs: Long,
            maxStaleMs: Long = MAX_STREAM_STALE_MS
        ): Boolean {
            if (lastMessageAtMs <= 0L) return true
            return (nowMs - lastMessageAtMs) > maxStaleMs
        }

        /**
         * P1-3.5 · Attempts to acquire the singleton loop lock for testing or verification.
         */
        fun tryAcquireSingletonLoopForTest(): Boolean {
            val acquired = singletonLoopGuard.compareAndSet(false, true)
            if (acquired) {
                val count = activeLoopCounter.incrementAndGet()
                _runtimeTelemetry.value = _runtimeTelemetry.value.copy(
                    serviceActive = true,
                    loopRunning = true,
                    activeLoopInstances = count
                )
            }
            return acquired
        }

        fun releaseSingletonLoopForTest() {
            if (singletonLoopGuard.compareAndSet(true, false)) {
                val count = activeLoopCounter.decrementAndGet().coerceAtLeast(0)
                _runtimeTelemetry.value = _runtimeTelemetry.value.copy(
                    serviceActive = false,
                    loopRunning = false,
                    activeLoopInstances = count
                )
            }
        }
    }
}
