package com.boxagent.app.daemon

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

/** Result of a shell-side exec. */
data class ExecResult(
    val exit: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
)

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

    val isAlive: Boolean get() = socket.isConnected

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        io.withLock {
            try {
                // Half-dead daemons accept writes but never reply — bound the read.
                socket.soTimeout = 10_000
                send(JSONObject().put("type", "ping"))
                val alive = recv().optString("type") == "pong"
                socket.soTimeout = 0
                alive
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): ExecResult =
        withContext(Dispatchers.IO) {
            io.withLock {
                val id = nextId++
                send(
                    JSONObject()
                        .put("type", "exec")
                        .put("id", id)
                        .put("cmd", cmd)
                        .put("timeout_ms", timeoutMs)
                )
                val out = StringBuilder()
                val err = StringBuilder()
                while (true) {
                    val f = recv()
                    when (f.optString("type")) {
                        "chunk" -> {
                            val data = Base64.decode(f.getString("data_b64"), Base64.DEFAULT)
                            if (f.optString("stream") == "stderr") err.append(String(data))
                            else out.append(String(data))
                        }
                        "exec_done" -> return@withLock ExecResult(
                            exit = f.getInt("exit"),
                            stdout = out.toString(),
                            stderr = err.toString(),
                            durationMs = f.getLong("duration_ms"),
                            timedOut = f.optBoolean("timed_out"),
                        )
                        "err" -> throw IOException(f.optString("message"))
                        else -> throw IOException("unexpected frame: $f")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        }

    suspend fun fileRead(path: String): ByteArray = withContext(Dispatchers.IO) {
        io.withLock {
            send(JSONObject().put("type", "file_read").put("id", nextId++).put("path", path))
            val f = recv()
            when (f.optString("type")) {
                "file_data" -> Base64.decode(f.getString("data_b64"), Base64.DEFAULT)
                "err" -> throw IOException(f.optString("message"))
                else -> throw IOException("unexpected frame: $f")
            }
        }
    }

    suspend fun fileWrite(path: String, data: ByteArray): Long = withContext(Dispatchers.IO) {
        io.withLock {
            send(
                JSONObject().put("type", "file_write").put("id", nextId++)
                    .put("path", path)
                    .put("data_b64", Base64.encodeToString(data, Base64.NO_WRAP))
            )
            val f = recv()
            when (f.optString("type")) {
                "file_written" -> f.getLong("bytes")
                "err" -> throw IOException(f.optString("message"))
                else -> throw IOException("unexpected frame: $f")
            }
        }
    }

    suspend fun fileList(path: String): String = withContext(Dispatchers.IO) {
        io.withLock {
            send(JSONObject().put("type", "file_list").put("id", nextId++).put("path", path))
            val f = recv()
            when (f.optString("type")) {
                "file_list" -> f.getJSONArray("entries").toString()
                "err" -> throw IOException(f.optString("message"))
                else -> throw IOException("unexpected frame: $f")
            }
        }
    }

    suspend fun screencap(): ByteArray = withContext(Dispatchers.IO) {
        io.withLock {
            send(JSONObject().put("type", "screencap").put("id", nextId++))
            val f = recv()
            when (f.optString("type")) {
                "file_data" -> Base64.decode(f.getString("data_b64"), Base64.DEFAULT)
                "err" -> throw IOException(f.optString("message"))
                else -> throw IOException("unexpected frame: $f")
            }
        }
    }

    fun close() = runCatching { socket.close() }

    // ---- framing ----

    private fun send(v: JSONObject) {
        val payload = v.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun recv(): JSONObject {
        val len = input.readInt()
        require(len in 1..16 * 1024 * 1024) { "bad frame len $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return JSONObject(String(buf, Charsets.UTF_8))
    }

    companion object {
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
                        socket.soTimeout = 0
                        client
                    } else {
                        socket.close(); null
                    }
                }.getOrNull()
            }
    }
}
