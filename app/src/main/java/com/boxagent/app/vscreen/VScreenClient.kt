package com.boxagent.app.vscreen

import android.util.Base64
import com.boxagent.app.daemon.HelperConn
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

/** One frame off the virtual display. [w]x[h] is the encoded image,
 *  [srcW]x[srcH] the display's own pixel space (what bounds are in). */
class VScreenShot(
    val bytes: ByteArray,
    val mime: String,
    val w: Int,
    val h: Int,
    val srcW: Int,
    val srcH: Int,
)

/**
 * Client for the vscreen app_process host: loopback TCP (see
 * [HelperConn]), the same 4-byte length-prefixed JSON frames as
 * boxagentd (host speaks `op`-keyed requests rather than `type`-keyed).
 * One request in flight at a time.
 */
class VScreenClient private constructor(
    private val socket: HelperConn,
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
                    socket.close()
                    throw if (e is IOException) e
                        else IOException(e.message ?: e.javaClass.simpleName, e)
                }
            }
        }

    /** display_id (<0 when none), w/h/dpi and the flags the display got. */
    suspend fun info(): JSONObject = op("info")

    /** Creates the display, or resizes the existing one in place (its
     *  tasks survive); returns the info frame. */
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

    /** Press-hold at (x1,y1) for [holdMs], then move to (x2,y2) over
     *  [durationMs] and release — the host runs the sequence atomically. */
    suspend fun drag(
        x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long, holdMs: Long,
    ) {
        op("drag") {
            put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
            put("duration_ms", durationMs); put("hold_ms", holdMs)
        }
    }

    suspend fun key(code: Int) {
        op("key") { put("code", code) }
    }

    /** Key-event typing; throws [VScreenError] when [text] has characters
     *  with no key mapping (CJK, emoji…) — nothing is typed then. */
    suspend fun text(text: String) {
        op("text") { put("text", text) }
    }

    /**
     * Latest frame. [format] "png" | "jpeg"; [maxSide] > 0 downscales on
     * the host so only the small image crosses the socket. Null when the
     * display hasn't rendered anything yet.
     */
    suspend fun screenshot(format: String = "png", quality: Int = 90, maxSide: Int = 0): VScreenShot? {
        val r = op("screenshot") {
            put("format", format); put("quality", quality); put("max_side", maxSide)
        }
        if (r.isNull("data_b64")) return null
        return VScreenShot(
            bytes = Base64.decode(r.getString("data_b64"), Base64.DEFAULT),
            mime = r.optString("mime", "image/png"),
            w = r.optInt("w"), h = r.optInt("h"),
            srcW = r.optInt("src_w"), srcH = r.optInt("src_h"),
        )
    }

    fun close() {
        broken = true
        socket.close()
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

        /** Connect + auth to `tcp:<port>`; null on any failure. */
        suspend fun connect(endpoint: String, token: String): VScreenClient? =
            withContext(Dispatchers.IO) {
                var socket: HelperConn? = null
                runCatching {
                    val s = HelperConn.open(endpoint).also { socket = it }
                    s.timeoutMs = 10_000
                    val client = VScreenClient(
                        s, DataInputStream(s.input.buffered()), DataOutputStream(s.output.buffered()),
                    )
                    client.send(JSONObject().put("op", "auth").put("token", token))
                    val f = client.recv()
                    if (!f.optBoolean("ok")) throw IOException(f.optString("error", "auth rejected"))
                    client.hostVersion = f.optString("version")
                    // Bound every read — a wedged host must fail the op,
                    // not park the io mutex (and the run) forever. Long
                    // gestures run inside the host before it answers.
                    s.timeoutMs = 20_000
                    client
                }.onFailure {
                    socket?.close()
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }.getOrNull()
            }
    }
}
