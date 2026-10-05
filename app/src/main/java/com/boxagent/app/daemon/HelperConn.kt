package com.boxagent.app.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Byte stream to a shell-uid helper (boxagentd, the vscreen host).
 *
 * Endpoints are `tcp:<port>` — 127.0.0.1 TCP, the production path:
 * SELinux on user builds denies `untrusted_app → shell
 * unix_stream_socket connectto`, so an abstract socket only ever worked
 * on permissive builds (emulators, redroid). Any other string is a legacy
 * abstract socket name, still accepted so an upgrade can reach the
 * previous daemon once and replace it.
 */
class HelperConn private constructor(
    private val tcp: Socket?,
    private val local: LocalSocket?,
) : Closeable {
    val input: InputStream = tcp?.getInputStream() ?: local!!.inputStream
    val output: OutputStream = tcp?.getOutputStream() ?: local!!.outputStream

    /** Read timeout in ms, 0 = block forever. */
    var timeoutMs: Int
        get() = tcp?.soTimeout ?: local!!.soTimeout
        set(v) {
            if (tcp != null) tcp.soTimeout = v else local!!.soTimeout = v
        }

    /** Like the platform flags, stays true after the peer goes away —
     *  callers track breakage themselves. */
    val isConnected: Boolean
        get() = tcp?.let { it.isConnected && !it.isClosed } ?: local!!.isConnected

    override fun close() {
        runCatching { tcp?.close() }
        runCatching { local?.close() }
    }

    companion object {
        private const val TCP = "tcp:"

        fun tcp(port: Int) = "$TCP$port"

        fun isTcp(endpoint: String) = endpoint.startsWith(TCP)

        /** Blocking connect; throws on failure. */
        fun open(endpoint: String, connectTimeoutMs: Int = 3_000): HelperConn {
            if (isTcp(endpoint)) {
                val port = endpoint.removePrefix(TCP).toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("bad endpoint $endpoint")
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(LOOPBACK_V4, port), connectTimeoutMs)
                } catch (e: Exception) {
                    runCatching { s.close() }
                    throw e
                }
                return HelperConn(s, null)
            }
            val ls = LocalSocket()
            try {
                ls.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
            } catch (e: Exception) {
                runCatching { ls.close() }
                throw e
            }
            return HelperConn(null, ls)
        }

        /** A loopback port that is free right now — handed to a helper on
         *  its command line (it binds 127.0.0.1 only). */
        fun freePort(): Int =
            ServerSocket(0, 1, LOOPBACK_V4).use { it.localPort }

        /** Helpers bind 127.0.0.1 — and Android's getLoopbackAddress()
         *  is ::1, which they don't listen on. */
        private val LOOPBACK_V4: InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
