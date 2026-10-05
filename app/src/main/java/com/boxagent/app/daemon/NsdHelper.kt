package com.boxagent.app.daemon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume

data class AdbEndpoint(val host: String, val port: Int, val serviceName: String)

/**
 * mDNS discovery for wireless debugging:
 *   _adb-tls-pairing._tcp — pairing port (code required)
 *   _adb-tls-connect._tcp — connect port (paired key only)
 *
 * Only services advertised by THIS phone are emitted — every phone on
 * the Wi-Fi with wireless debugging on shows up too, and probing a
 * neighbour's adbd fails (or worse, pairs with it). Matches are rewritten
 * to 127.0.0.1: adbd binds the wildcard address, and loopback sidesteps
 * IPv6/zone-id addresses that NsdManager likes to resolve to.
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
        // Before API 34 resolveService fails with FAILURE_ALREADY_ACTIVE
        // while another resolve runs — found services queue up here and
        // resolve one at a time.
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(AdbEndpoint("", -1, "discovery_failed:$errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        launch {
            for (svc in found) {
                val info = resolve(svc) ?: continue
                if (info.port <= 0 || !isLocal(info)) continue
                trySend(AdbEndpoint(LOOPBACK, info.port, info.serviceName ?: ""))
            }
        }
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { trySend(AdbEndpoint("", -1, "discovery_failed:${it.message}")) }
        awaitClose {
            found.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback is 34+ only
    private suspend fun resolve(svc: NsdServiceInfo): NsdServiceInfo? {
        repeat(3) {
            var busy = false
            // Some ROMs never call back — don't wedge the queue on them.
            val r = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    runCatching {
                        nsd.resolveService(svc, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                busy = errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                                if (cont.isActive) cont.resume(null)
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (cont.isActive) cont.resume(info)
                            }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (r != null || !busy) return r
            delay(150)
        }
        return null
    }

    private fun isLocal(info: NsdServiceInfo): Boolean {
        val addrs: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION") listOfNotNull(info.host)
        }
        if (addrs.isEmpty()) return false
        val mine = localAddresses()
        return addrs.any { a -> a.isLoopbackAddress || mine.any { it.contentEquals(a.address) } }
    }

    companion object {
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp."
        const val TYPE_CONNECT = "_adb-tls-connect._tcp."
        const val LOOPBACK = "127.0.0.1"
        private const val RESOLVE_TIMEOUT_MS = 5_000L

        /** Raw bytes of every address on this device's interfaces (scope
         *  ids dropped, so fe80::x%wlan0 compares equal to fe80::x). */
        fun localAddresses(): List<ByteArray> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .map { it.address }
        }.getOrDefault(emptyList())
    }
}
