package com.boxagent.app.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Result of a shell-side exec. */
data class ExecResult(
    val exit: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
)

/** The daemon answered with an `err` frame — the connection is still fine. */
class DaemonError(message: String) : IOException(message)

/**
 * Client for `boxagentd`: abstract-namespace LocalSocket, length-prefixed
 * JSON frames (mirrors boxagent-proto). One request in flight per
 * connection — the LLM drives calls sequentially anyway.
 */
class DaemonClient private constructor(
    private val socket: LocalSocket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) {
    private val io = Mutex()
    private var nextId = 1L

    /** Set once the socket is closed or framing can no longer be trusted. */
    @Volatile private var broken = false

    // LocalSocket.isConnected stays true after close() or a dead peer, so
    // liveness is tracked here: any transport/framing failure poisons it.
    val isAlive: Boolean get() = !broken && socket.isConnected

    /** Daemon build version, reported in the auth_ok frame ("" on old daemons). */
    var daemonVersion: String = ""
        private set

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        if (broken) return@withContext false
        io.withLock {
            try {
                // Half-dead daemons accept writes but never reply — bound the read.
                socket.soTimeout = 10_000
                send(JSONObject().put("type", "ping"))
                val alive = recv().optString("type") == "pong"
                socket.soTimeout = 0
                alive
            } catch (e: Exception) {
                // A timed-out read may have consumed half a frame.
                broken = true
                false
            }
        }
    }

    /**
     * Run one request under the connection lock. Transport or framing
     * failures mark the client dead (the stream may be mid-frame);
     * [DaemonError]s are ordinary per-request errors.
     */
    private suspend fun <T> request(block: () -> T): T = withContext(Dispatchers.IO) {
        io.withLock {
            if (broken) throw IOException("daemon connection closed")
            try {
                block()
            } catch (e: DaemonError) {
                throw e
            } catch (e: Exception) {
                broken = true
                runCatching { socket.close() }
                throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): ExecResult = request {
        val id = nextId++
        send(
            JSONObject()
                .put("type", "exec")
                .put("id", id)
                .put("cmd", cmd)
                .put("timeout_ms", timeoutMs)
        )
        // Collect raw bytes and decode once: a multi-byte UTF-8 character
        // can straddle two 32 KiB chunks. Capped — `logcat` or `yes` would
        // otherwise grow without bound; callers truncate far below this.
        val out = CappedBuffer(MAX_EXEC_OUTPUT)
        val err = CappedBuffer(MAX_EXEC_OUTPUT)
        while (true) {
            val f = recv()
            when (f.optString("type")) {
                "chunk" -> {
                    val data = Base64.decode(f.getString("data_b64"), Base64.DEFAULT)
                    if (f.optString("stream") == "stderr") err.write(data) else out.write(data)
                }
                "exec_done" -> return@request ExecResult(
                    exit = f.getInt("exit"),
                    stdout = out.text(),
                    stderr = err.text(),
                    durationMs = f.getLong("duration_ms"),
                    timedOut = f.optBoolean("timed_out"),
                )
                "err" -> throw DaemonError(f.optString("message"))
                else -> throw IOException("unexpected frame: $f")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun fileRead(path: String): ByteArray = request {
        send(JSONObject().put("type", "file_read").put("id", nextId++).put("path", path))
        val f = recv()
        when (f.optString("type")) {
            "file_data" -> Base64.decode(f.getString("data_b64"), Base64.DEFAULT)
            "err" -> throw DaemonError(f.optString("message"))
            else -> throw IOException("unexpected frame: $f")
        }
    }

    suspend fun fileWrite(path: String, data: ByteArray): Long = request {
        send(
            JSONObject().put("type", "file_write").put("id", nextId++)
                .put("path", path)
                .put("data_b64", Base64.encodeToString(data, Base64.NO_WRAP))
        )
        val f = recv()
        when (f.optString("type")) {
            "file_written" -> f.getLong("bytes")
            "err" -> throw DaemonError(f.optString("message"))
            else -> throw IOException("unexpected frame: $f")
        }
    }

    suspend fun fileList(path: String): String = request {
        send(JSONObject().put("type", "file_list").put("id", nextId++).put("path", path))
        val f = recv()
        when (f.optString("type")) {
            "file_list" -> f.getJSONArray("entries").toString()
            "err" -> throw DaemonError(f.optString("message"))
            else -> throw IOException("unexpected frame: $f")
        }
    }

    /** Ask the daemon to exit cleanly (best-effort — the socket just closes). */
    suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            io.withLock {
                runCatching {
                    send(JSONObject().put("type", "shutdown"))
                }
            }
        }
        close()
    }

    suspend fun screencap(): ByteArray = request {
        send(JSONObject().put("type", "screencap").put("id", nextId++))
        val f = recv()
        when (f.optString("type")) {
            "file_data" -> Base64.decode(f.getString("data_b64"), Base64.DEFAULT)
            "err" -> throw DaemonError(f.optString("message"))
            else -> throw IOException("unexpected frame: $f")
        }
    }

    fun close() {
        broken = true
        runCatching { socket.close() }
    }

    // ---- framing ----

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

    /** Byte sink that keeps the first [cap] bytes and counts the rest. */
    private class CappedBuffer(private val cap: Int) {
        private val buf = ByteArrayOutputStream()
        private var dropped = 0L

        fun write(data: ByteArray) {
            val room = cap - buf.size()
            if (room >= data.size) buf.write(data)
            else {
                if (room > 0) buf.write(data, 0, room)
                dropped += data.size - maxOf(room, 0)
            }
        }

        fun text(): String {
            val s = String(buf.toByteArray(), Charsets.UTF_8)
            return if (dropped > 0) "$s\n…[$dropped more bytes dropped]" else s
        }
    }

    companion object {
        const val MAX_FRAME = 16 * 1024 * 1024
        const val MAX_EXEC_OUTPUT = 1024 * 1024

        /** Connect + authenticate; returns null on any failure. */
        suspend fun connect(socketName: String, token: String): DaemonClient? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val socket = LocalSocket()
                    socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 15_000
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    val client = DaemonClient(socket, input, output)
                    client.send(JSONObject().put("type", "auth").put("token", token))
                    val f = client.recv()
                    if (f.optString("type") == "auth_ok") {
                        client.daemonVersion = f.optString("version")
                        socket.soTimeout = 0
                        client
                    } else {
                        socket.close(); null
                    }
                }.getOrNull()
            }
    }
}
