package com.zhelearn.garupaexport.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Proxy
import androidx.core.content.ContextCompat
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.proxy.ProxyProbe
import com.zhelearn.garupaexport.capture.proxy.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

internal data class ProxyEnvironment(
    val network: Network?,
    val wifi: Boolean,
    val host: String?,
    val port: Int?,
    val pac: Boolean,
    val exclusions: List<String>,
) {
    val usesLocalProxy: Boolean get() = !pac && port == ProxyServer.PORT &&
        host?.lowercase() in setOf("127.0.0.1", "localhost")
}

/** Reads the effective system configuration and verifies it using an unmodified system-proxy client. */
internal class SystemProxyVerifier(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun environment(): ProxyEnvironment {
        val network = connectivity.activeNetwork
        val proxy = connectivity.defaultProxy
        return ProxyEnvironment(
            network = network,
            wifi = network?.let { connectivity.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true,
            host = proxy?.host,
            port = proxy?.port,
            pac = !proxy?.pacFileUrl?.toString().isNullOrEmpty(),
            exclusions = proxy?.exclusionList?.toList().orEmpty(),
        )
    }

    val changes get() = callbackFlow {
        fun changed() { trySend(environment()) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changed()
        }
        connectivity.registerDefaultNetworkCallback(callback)
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Proxy.PROXY_CHANGE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (error: Exception) {
            connectivity.unregisterNetworkCallback(callback)
            throw error
        }
        changed()
        awaitClose {
            context.unregisterReceiver(receiver)
            connectivity.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    suspend fun verify(probe: ProxyProbe): Boolean = withContext(Dispatchers.IO) {
        // Do not specify a proxy here. A direct request cannot resolve this reserved .invalid host.
        val connection = probe.requestUrl().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.responseCode == HttpURLConnection.HTTP_OK && connection.inputStream.use {
                val bytes = ByteArray(probe.response.length + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = it.read(bytes, size, bytes.size - size)
                    if (count == -1) break
                    size += count
                }
                String(bytes, 0, size, Charsets.US_ASCII) == probe.response
            }
        } finally {
            connection.disconnect()
        }
    }

    fun configurationError(environment: ProxyEnvironment): String? = when {
        environment.network == null -> context.getString(R.string.guide_no_network)
        environment.pac -> context.getString(R.string.guide_pac_proxy)
        !environment.usesLocalProxy -> context.getString(R.string.guide_proxy_not_configured)
        else -> null
    }
}
