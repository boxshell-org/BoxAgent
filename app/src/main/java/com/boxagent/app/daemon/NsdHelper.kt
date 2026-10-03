package com.boxagent.app.daemon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class AdbEndpoint(val host: String, val port: Int, val serviceName: String)

/**
 * mDNS discovery for wireless debugging:
 *   _adb-tls-pairing._tcp — pairing port (code required)
 *   _adb-tls-connect._tcp — connect port (paired key only)
 */
class NsdHelper(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun discover(serviceType: String): Flow<AdbEndpoint> = callbackFlow {
        val lock = wifi.createMulticastLock("boxagent_nsd").apply {
            setReferenceCounted(true)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        trySend(AdbEndpoint(host, info.port, info.serviceName ?: ""))
                    }
                })
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(AdbEndpoint("", -1, "discovery_failed:$errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }

    companion object {
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp."
        const val TYPE_CONNECT = "_adb-tls-connect._tcp."
    }
}
