package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkConnectivityState(
    val isConnected: Boolean = true,
    val isValidated: Boolean = true,
    val transportLabelVi: String = "Đang kiểm tra...",
    val statusMessageVi: String = "Kết nối Internet ổn định",
    val lastChangedAtMs: Long = System.currentTimeMillis()
)

/**
 * Real-time internet connectivity monitor using Android [ConnectivityManager].
 * Tracks [NetworkCapabilities.NET_CAPABILITY_INTERNET] and [NetworkCapabilities.NET_CAPABILITY_VALIDATED]
 * to alert the user immediately if internet access drops during trading operations.
 */
class NetworkConnectivityMonitor(
    context: Context,
    private val onConnectivityChanged: ((NetworkConnectivityState) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _state = MutableStateFlow(queryCurrentConnectivityState())
    val state: StateFlow<NetworkConnectivityState> = _state.asStateFlow()

    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateStateFromManager(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val updated = buildStateFromCapabilities(networkCapabilities)
            publishState(updated)
        }

        override fun onLost(network: Network) {
            updateStateFromManager(null)
        }

        override fun onUnavailable() {
            publishState(
                NetworkConnectivityState(
                    isConnected = false,
                    isValidated = false,
                    transportLabelVi = "Mất kết nối",
                    statusMessageVi = "CẢNH BÁO MẤT MẠNG: Thiết bị không có kết nối Internet. Tạm khóa gửi lệnh mới & yêu cầu đối soát lại khi có mạng!",
                    lastChangedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    init {
        startMonitoring()
    }

    fun startMonitoring() {
        val cm = connectivityManager ?: return
        if (callbackRegistered) return
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (_: Exception) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
                callbackRegistered = true
            } catch (_: Exception) {
                // Ignore if security/OS restrictions block callback registration in test environments
            }
        }
        refreshNow()
    }

    fun refreshNow(): NetworkConnectivityState {
        val current = queryCurrentConnectivityState()
        publishState(current)
        return current
    }

    fun stopMonitoring() {
        if (!callbackRegistered) return
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        } finally {
            callbackRegistered = false
        }
    }

    private fun updateStateFromManager(overrideNetwork: Network?) {
        val cm = connectivityManager
        if (cm == null) {
            publishState(
                NetworkConnectivityState(
                    isConnected = false,
                    isValidated = false,
                    transportLabelVi = "Không rõ",
                    statusMessageVi = "Không thể truy cập ConnectivityManager trên thiết bị.",
                    lastChangedAtMs = System.currentTimeMillis()
                )
            )
            return
        }
        val activeNet = try {
            cm.activeNetwork ?: overrideNetwork
        } catch (_: Exception) {
            overrideNetwork
        }
        val caps = try {
            if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        } catch (_: Exception) {
            null
        }
        publishState(buildStateFromCapabilities(caps))
    }

    private fun queryCurrentConnectivityState(): NetworkConnectivityState {
        val cm = connectivityManager ?: return NetworkConnectivityState()
        return try {
            val activeNet = cm.activeNetwork
            val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
            buildStateFromCapabilities(caps)
        } catch (_: Exception) {
            NetworkConnectivityState()
        }
    }

    private fun buildStateFromCapabilities(caps: NetworkCapabilities?): NetworkConnectivityState {
        if (caps == null) {
            return NetworkConnectivityState(
                isConnected = false,
                isValidated = false,
                transportLabelVi = "Mất mạng",
                statusMessageVi = "CẢNH BÁO MẤT MẠNG: Không phát hiện giao diện mạng hoạt động. Các luồng dữ liệu giá và gửi lệnh với Binance đang bị gián đoạn!",
                lastChangedAtMs = System.currentTimeMillis()
            )
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val baseTransport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G/5G Di động"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            hasVpn -> "VPN"
            else -> "Mạng khác"
        }
        val transportLabel = if (hasVpn && baseTransport != "VPN") "$baseTransport + VPN" else baseTransport

        val isOnline = hasInternet
        val statusMsg = when {
            !hasInternet ->
                "CẢNH BÁO MẤT MẠNG ($transportLabel): Kết nối mạng không có quyền truy cập Internet. Đã khóa gửi lệnh mới!"
            !isValidated ->
                "Mạng $transportLabel đã kết nối (chưa xác thực cổng Internet đầy đủ · Captive/Unvalidated)."
            else ->
                "Trực tuyến ($transportLabel) · Luồng kết nối sẵn sàng cho giao dịch tần suất cao."
        }

        return NetworkConnectivityState(
            isConnected = isOnline,
            isValidated = isValidated,
            transportLabelVi = transportLabel,
            statusMessageVi = statusMsg,
            lastChangedAtMs = System.currentTimeMillis()
        )
    }

    private fun publishState(newState: NetworkConnectivityState) {
        val prev = _state.value
        val changed = prev.isConnected != newState.isConnected ||
            prev.isValidated != newState.isValidated ||
            prev.transportLabelVi != newState.transportLabelVi
        _state.value = newState
        if (changed) {
            onConnectivityChanged?.invoke(newState)
        }
    }
}
