package com.example.analytics.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.analytics.AnalyticsLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks whether any network is available and fires [onNetworkAvailable] when one appears.
 *
 * Deliberately does NOT require NET_CAPABILITY_VALIDATED: networks that can't reach Google's
 * validation servers (some regions, enterprise Wi-Fi) never validate yet work fine for our backend.
 * Captive portals are handled by the uploader treating failed requests as retryable.
 */
internal class ConnectivityMonitor(
    private val context: Context,
    private val logger: AnalyticsLogger,
    private val onNetworkAvailable: () -> Unit,
) {
    private val networks = ConcurrentHashMap<Network, String>()

    @Volatile
    private var registered = false

    /** null when unknown (monitoring unavailable) — callers should then just try. */
    val isConnected: Boolean? get() = if (registered) networks.isNotEmpty() else null

    val networkType: String get() = networks.values.firstOrNull() ?: if (registered) "none" else "unknown"

    fun start() {
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasEmpty = networks.isEmpty()
                    networks[network] = typeOf(manager.getNetworkCapabilities(network))
                    if (wasEmpty) onNetworkAvailable()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    networks[network] = typeOf(capabilities)
                }

                override fun onLost(network: Network) {
                    networks.remove(network)
                }
            }
            if (Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(request, callback)
            }
            // Don't depend solely on the initial callback (some OEM builds never deliver it):
            // seed from the current default network.
            manager.activeNetwork?.let { networks.putIfAbsent(it, typeOf(manager.getNetworkCapabilities(it))) }
            registered = true
        } catch (e: Exception) {
            // SecurityException without ACCESS_NETWORK_STATE, or OEM bugs (e.g. too many callbacks).
            logger.warn("Connectivity monitoring unavailable; uploads will be attempted blindly", e)
        }
    }

    private fun typeOf(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "unknown"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }
}
