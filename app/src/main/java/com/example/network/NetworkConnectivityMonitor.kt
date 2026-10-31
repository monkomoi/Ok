package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

data class NetworkConnectivityState(
    val isConnected: Boolean = true,
    val isValidated: Boolean = true,
    val transportType: String = "WIFI",
    val dnsSummary: String = "System DNS",
    val lastChangedAtMs: Long = System.currentTimeMillis()
)

object Ipv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        return all.sortedBy { addr -> if (addr is Inet4Address) 0 else 1 }
    }
}

class NetworkConnectivityMonitor(
    context: Context? = null,
    private val onNetworkChanged: ((NetworkConnectivityState) -> Unit)? = null
) {
    private val connectivityManager =
        context?.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _state = MutableStateFlow(readCurrentState())
    val state: StateFlow<NetworkConnectivityState> = _state.asStateFlow()

    @Volatile
    var activeNetwork: Network? = connectivityManager?.activeNetwork
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeNetwork = network
            refreshAndNotify()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            activeNetwork = network
            refreshAndNotify()
        }

        override fun onLost(network: Network) {
            if (activeNetwork == network) {
                activeNetwork = connectivityManager?.activeNetwork
            }
            refreshAndNotify()
        }
    }

    init {
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(req, networkCallback)
        } catch (_: Exception) {
        }
    }

    fun refreshAndNotify(): NetworkConnectivityState {
        val next = readCurrentState()
        _state.value = next
        onNetworkChanged?.invoke(next)
        return next
    }

    fun unregister() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun readCurrentState(): NetworkConnectivityState {
        val cm = connectivityManager ?: return NetworkConnectivityState()
        return try {
            val net = cm.activeNetwork
            activeNetwork = net
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val link = net?.let { cm.getLinkProperties(it) }
            val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val transport = when {
                caps == null -> "DISCONNECTED"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            val dnsStr = link?.dnsServers?.joinToString(",") { it.hostAddress ?: "" }
                ?.takeIf { it.isNotBlank() } ?: "System DNS"
            NetworkConnectivityState(
                isConnected = connected,
                isValidated = validated,
                transportType = transport,
                dnsSummary = dnsStr,
                lastChangedAtMs = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            NetworkConnectivityState()
        }
    }
}
