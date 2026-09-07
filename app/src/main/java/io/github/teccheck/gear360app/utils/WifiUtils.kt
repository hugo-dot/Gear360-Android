package io.github.teccheck.gear360app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import java.net.URL
import java.net.URLConnection

private const val TAG = "G360-WIFI"

object WifiUtils {
    @Volatile
    private var activeSession: WifiSession? = null

    interface WifiConnectionListener {
        fun onAvailable(network: Network) {}
        fun onUnavailable() {}
        fun onLost() {}
        fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {}
    }

    class WifiSession internal constructor(
        val ssid: String,
        internal val connectivityManager: ConnectivityManager?,
        internal var callback: ConnectivityManager.NetworkCallback?,
        internal val legacyWifiManager: WifiManager?,
        internal val legacyNetworkId: Int?,
        internal var processBound: Boolean = false
    ) {
        @Volatile
        internal var network: Network? = null

        fun activeNetwork(): Network? = network
    }

    fun getMacAddress(context: Context): String {
        val manager = context.applicationContext.getSystemService<WifiManager>()
        return manager?.connectionInfo?.macAddress ?: return ""
    }

    fun activeNetwork(): Network? = activeSession?.activeNetwork()

    fun openConnection(url: URL): URLConnection {
        val network = activeNetwork()
        return if (network != null) {
            Log.d(TAG, "Opening ${url.host} on active Gear 360 Wi-Fi network")
            network.openConnection(url)
        } else {
            Log.d(TAG, "Opening ${url.host} on default network; Gear 360 Wi-Fi not bound")
            url.openConnection()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToWifi(
        context: Context,
        ssid: String,
        password: String,
        hidden: Boolean,
        listener: WifiConnectionListener? = null
    ): WifiSession? {
        val appContext = context.applicationContext
        if (ssid.isBlank()) {
            Log.w(TAG, "Cannot connect: empty SSID")
            listener?.onUnavailable()
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !AndroidPermissionUtils.hasNearbyWifiDevicesPermission(appContext)
        ) {
            Log.w(TAG, "Cannot connect to Gear 360 Wi-Fi: NEARBY_WIFI_DEVICES missing")
            listener?.onUnavailable()
            return null
        }

        disconnectWifi(appContext)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithNetworkSpecifier(appContext, ssid, password, hidden, listener)
        } else {
            connectWithLegacyWifiManager(appContext, ssid, password, hidden, listener)
        }
    }

    fun disconnectWifi(context: Context) {
        val session = activeSession ?: return
        activeSession = null

        val manager = session.connectivityManager
            ?: context.applicationContext.getSystemService<ConnectivityManager>()

        if (session.processBound && manager != null) {
            restoreProcessNetwork(manager)
        }

        val callback = session.callback
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Network callback already unregistered", e)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            session.legacyNetworkId?.let { networkId ->
                session.legacyWifiManager?.disableNetwork(networkId)
            }
        }

        Log.i(TAG, "Disconnected Gear 360 Wi-Fi session for ${session.ssid}")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithNetworkSpecifier(
        context: Context,
        ssid: String,
        password: String,
        hidden: Boolean,
        listener: WifiConnectionListener?
    ): WifiSession? {
        val manager = context.getSystemService<ConnectivityManager>()
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable")
            listener?.onUnavailable()
            return null
        }

        val request = try {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setIsHiddenSsid(hidden)
            if (password.isNotBlank()) {
                specifierBuilder.setWpa2Passphrase(password)
            }

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Invalid Gear 360 Wi-Fi request for ssid=$ssid", e)
            listener?.onUnavailable()
            return null
        }

        val session = WifiSession(
            ssid = ssid,
            connectivityManager = manager,
            callback = null,
            legacyWifiManager = null,
            legacyNetworkId = null
        )

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (activeSession !== session) return

                session.network = network
                session.processBound = bindProcessToNetwork(manager, network)
                Log.i(TAG, "Gear 360 Wi-Fi available ssid=$ssid bound=${session.processBound}")
                listener?.onAvailable(network)
            }

            override fun onUnavailable() {
                if (activeSession === session) {
                    activeSession = null
                }

                Log.w(TAG, "Gear 360 Wi-Fi unavailable ssid=$ssid")
                listener?.onUnavailable()
            }

            override fun onLost(network: Network) {
                if (session.network == network) {
                    session.network = null
                }
                if (session.processBound) {
                    restoreProcessNetwork(manager)
                    session.processBound = false
                }
                if (activeSession === session) {
                    activeSession = null
                }

                Log.i(TAG, "Gear 360 Wi-Fi lost ssid=$ssid")
                listener?.onLost()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (activeSession !== session) return
                listener?.onCapabilitiesChanged(network, networkCapabilities)
            }
        }

        session.callback = callback
        activeSession = session

        return try {
            Log.i(TAG, "Requesting Gear 360 Wi-Fi ssid=$ssid hidden=$hidden")
            manager.requestNetwork(request, callback)
            session
        } catch (e: SecurityException) {
            activeSession = null
            Log.e(TAG, "Missing permission while requesting Gear 360 Wi-Fi", e)
            listener?.onUnavailable()
            null
        } catch (e: RuntimeException) {
            activeSession = null
            Log.e(TAG, "Failed to request Gear 360 Wi-Fi", e)
            listener?.onUnavailable()
            null
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectWithLegacyWifiManager(
        context: Context,
        ssid: String,
        password: String,
        hidden: Boolean,
        listener: WifiConnectionListener?
    ): WifiSession? {
        val manager = context.getSystemService<WifiManager>()
        if (manager == null) {
            Log.w(TAG, "WifiManager unavailable")
            listener?.onUnavailable()
            return null
        }

        val config = WifiConfiguration().apply {
            SSID = quoteWifiValue(ssid)
            hiddenSSID = hidden
            if (password.isBlank()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = quoteWifiValue(password)
            }
        }

        return try {
            val networkId = manager.addNetwork(config)
            if (networkId == -1) {
                Log.w(TAG, "Legacy Wi-Fi addNetwork failed for ssid=$ssid")
                listener?.onUnavailable()
                return null
            }

            val session = WifiSession(
                ssid = ssid,
                connectivityManager = null,
                callback = null,
                legacyWifiManager = manager,
                legacyNetworkId = networkId
            )
            activeSession = session

            manager.disconnect()
            manager.enableNetwork(networkId, true)
            manager.reconnect()
            Log.i(TAG, "Legacy Gear 360 Wi-Fi connect requested ssid=$ssid")
            listener?.onUnavailable()
            session
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission while connecting legacy Gear 360 Wi-Fi", e)
            listener?.onUnavailable()
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "Legacy Gear 360 Wi-Fi connect failed", e)
            listener?.onUnavailable()
            null
        }
    }

    private fun bindProcessToNetwork(manager: ConnectivityManager, network: Network): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.bindProcessToNetwork(network)
        } else {
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(network)
            true
        }
    }

    private fun restoreProcessNetwork(manager: ConnectivityManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.bindProcessToNetwork(null)
        } else {
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(null)
        }
    }

    private fun quoteWifiValue(value: String): String {
        return if (value.startsWith("\"") && value.endsWith("\"")) {
            value
        } else {
            "\"$value\""
        }
    }
}
