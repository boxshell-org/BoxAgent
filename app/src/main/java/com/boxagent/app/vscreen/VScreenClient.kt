package com.boxagent.app.vscreen

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** The host answered with ok=false — the connection is still fine. */
class VScreenError(message: String) : IOException(message)

/**
 * Client for the vscreen app_process host: abstract LocalSocket, the same
 * 4-byte length-prefixed JSON frames as boxagentd (host speaks `op`-keyed
 * requests rather than `type`-keyed). One request in flight at a time.
 */
class VScreenClient private constructor(
    private val socket: LocalSocket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) {
    private val io = Mutex()

    @Volatile private var broken = false
    val isAlive: Boolean get() = !broken && socket.isConnected

    var hostVersion: String = ""
        private set

    suspend fun ping(): Boolean =
        if (broken) false
        else try {
            op("ping").optBoolean("ok")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            broken = true
            false
        }

    private suspend fun op(name: String, build: JSONObject.() -> Unit = {}): JSONObject =
        request {
            send(JSONObject().put("op", name).apply(build))
            val r = recv()
            if (!r.optBoolean("ok")) throw VScreenError(r.optString("error", "unknown error"))
            r
        }

    private suspend fun request(block: () -> JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            io.withLock {
                if (broken) throw IOException("vscreen connection closed")
                try {
                    block()
                } catch (e: VScreenError) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    broken = true
                    runCatching { socket.close() }
                    throw if (e is IOException) e
                        else IOException(e.message ?: e.javaClass.simpleName, e)
                }
            }
        }

    /** display_id (<0 when none), w/h/dpi of the current display. */
    suspend fun info(): JSONObject = op("info")

    /** Creates the display when absent; returns the info frame. */
    suspend fun create(w: Int, h: Int, dpi: Int): JSONObject = op("create") {
        put("w", w); put("h", h); put("dpi", dpi)
    }

    suspend fun destroy() {
        runCatching { op("destroy") }
        close()
    }

    suspend fun tap(x: Double, y: Double) {
        op("tap") { put("x", x); put("y", y) }
    }

    suspend fun longPress(x: Double, y: Double, durationMs: Long) {
        op("long_press") { put("x", x); put("y", y); put("duration_ms", durationMs) }
    }

    suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long) {
        op("swipe") {
            put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
            put("duration_ms", durationMs)
        }
    }

    suspend fun pinch(cx: Double, cy: Double, zoomIn: Boolean, percent: Int) {
        op("pinch") {
            put("cx", cx); put("cy", cy); put("zoom_in", zoomIn); put("percent", percent)
        }
    }

    suspend fun key(code: Int) {
        op("key") { put("code", code) }
    }

    suspend fun text(text: String) {
        op("text") { put("text", text) }
    }

    /** Latest display frame, PNG-encoded; null when nothing rendered yet. */
    suspend fun screenshot(): ByteArray? {
        val r = op("screenshot")
        return if (r.isNull("png")) null
        else Base64.decode(r.getString("png"), Base64.DEFAULT)
    }

    fun close() {
        broken = true
        runCatching { socket.close() }
    }

    private fun send(v: JSONObject) {
        val payload = v.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun recv(): JSONObject {
        val len = input.readInt()
        if (len !in 1..MAX_FRAME) throw IOException("bad frame len $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return JSONObject(String(buf, Charsets.UTF_8))
    }

    companion object {
        const val MAX_FRAME = 16 * 1024 * 1024
        const val SOCKET_NAME = "boxagent.vscreen"

        /** Connect + auth; null on any failure. */
        suspend fun connect(socketName: String, token: String): VScreenClient? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val socket = LocalSocket()
                    socket.connect(
                        LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                    )
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    val client = VScreenClient(socket, input, output)
                    client.send(JSONObject().put("op", "auth").put("token", token))
                    val f = client.recv()
                    if (f.optBoolean("ok")) {
                        client.hostVersion = f.optString("version")
                        // Bound every read — a wedged host must fail the
                        // op, not park the io mutex (and the run) forever.
                        socket.soTimeout = 15_000
                        client
                    } else {
                        socket.close(); null
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }.getOrNull()
            }
    }
}
