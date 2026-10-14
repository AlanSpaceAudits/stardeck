package com.spaceaudits.stardeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * In the field the phone joins the Seestar's own hotspot. That network has no internet, so Android would
 * otherwise send the app's traffic over mobile data; the app binds itself to the Wi-Fi network instead.
 */
object FieldWifi {
    /** hotspot credentials, remembered from the scope (get_device_state `ap`) or typed in */
    var ssid by mutableStateOf("")
    var password by mutableStateOf("")
    var status by mutableStateOf("")

    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun cm(context: Context) = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The gateway of a network: on the Seestar's hotspot this is the Seestar. */
    fun gateway(context: Context, network: Network?): String? =
        network?.let { cm(context).getLinkProperties(it) }?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress

    /**
     * Binds the app to the current Wi-Fi network if it has no internet (a hotspot joined in the phone's
     * settings); returns its gateway, or null when the phone is not on such a network.
     */
    fun bindToHotspotIfJoined(context: Context): String? {
        val c = cm(context)
        @Suppress("DEPRECATION")
        val wifi = c.allNetworks.firstOrNull { n ->
            c.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
        } ?: return null
        c.bindProcessToNetwork(wifi)
        return gateway(context, wifi)
    }

    /** Asks Android to join the hotspot (it shows its own confirmation), then binds the app to it. */
    fun join(context: Context, onReady: (gateway: String?) -> Unit) {
        leave(context)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid.trim()).apply {
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val c = cm(context)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                c.bindProcessToNetwork(network)
                status = "joined ${ssid.trim()}"
                onReady(gateway(context, network))
            }

            override fun onUnavailable() {
                status = "could not join ${ssid.trim()}"
            }

            override fun onLost(network: Network) {
                c.bindProcessToNetwork(null)
                status = "left ${ssid.trim()}"
            }
        }
        callback = cb
        status = "joining ${ssid.trim()}…"
        c.requestNetwork(request, cb)
    }

    fun leave(context: Context) {
        callback?.let { runCatching { cm(context).unregisterNetworkCallback(it) } }
        callback = null
        cm(context).bindProcessToNetwork(null)
    }
}
