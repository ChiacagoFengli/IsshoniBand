package com.isshoniband.v2.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.Executors

/** NSD 注册与发现（同一 WiFi 下的房间）。 */
class Discovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var registration: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    fun register(serviceName: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    fun unregister() {
        registration?.let {
            try {
                nsd.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        registration = null
    }

    fun startDiscovery(
        onRoom: (DiscoveredRoom) -> Unit,
        onLost: (String) -> Unit
    ) {
        stopDiscovery()
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith(SERVICE_TYPE_DISCOVERY)) return
                resolveExecutor.execute { resolve(info, onRoom) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                onLost(info.serviceName)
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE_DISCOVERY, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun resolve(info: NsdServiceInfo, onRoom: (DiscoveredRoom) -> Unit) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    onRoom(
                        DiscoveredRoom(
                            name = serviceInfo.serviceName,
                            host = host,
                            port = serviceInfo.port
                        )
                    )
                }
            })
        } catch (_: Exception) {
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("isshoni-discover").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    fun release() {
        unregister()
        stopDiscovery()
        resolveExecutor.shutdownNow()
    }

    companion object {
        const val SERVICE_TYPE = "_isshoni._tcp."
        private const val SERVICE_TYPE_DISCOVERY = "_isshoni._tcp"
    }
}
