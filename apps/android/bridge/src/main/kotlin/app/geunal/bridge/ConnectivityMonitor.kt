package app.geunal.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has a network now, for the screens that say "연결이 없어요" instead of trying (m1.md §4). Only the
 * default network counts, and a network that has not passed the system's internet check still does: a request that
 * then fails says so itself, while calling a working network offline would block sign-in for nothing.
 */
class ConnectivityMonitor(
    context: Context,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val online = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = online.asStateFlow()

    init {
        // The callback reports the current default network right away, then every change; it lives as long as the app.
        manager?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    online.value = false
                }
            },
        )
    }
}
