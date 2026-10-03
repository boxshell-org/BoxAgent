package com.boxagent.app.daemon

import android.content.Context
import android.util.Base64
import com.boxagent.app.bridge.Core
import com.boxagent.app.data.Secrets
import com.boxagent.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom

enum class ShellState { OFFLINE, PAIRING, CONNECTING, ONLINE, ERROR }

data class DaemonStatus(
    val shell: ShellState = ShellState.OFFLINE,
    val uid: Int = -1,
    val detail: String = "",
    val socket: String = "",
)

/**
 * Owns the privileged-daemon lifecycle: pairing (via adb-tls), daemon push
 * and spawn (via adb_client ops), socket reconnect + watchdog.
 */
class DaemonManager(
    private val context: Context,
    private val settings: Settings,
    private val secrets: Secrets,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val rand = SecureRandom()

    private val _status = MutableStateFlow(DaemonStatus())
    val status: StateFlow<DaemonStatus> = _status

    @Volatile var client: DaemonClient? = null
        private set

    private var watchdogJob: kotlinx.coroutines.Job? = null

    fun ensureKey(): String {
        if (secrets.adbKeyPem.isEmpty()) {
            val resp = JSONObject(Core.nativeGenerateAdbKey())
            if (resp.optBoolean("ok")) {
                secrets.adbKeyPem = resp.getJSONObject("data").getString("pem")
            }
        }
        return secrets.adbKeyPem
    }

    /** mDNS-free pairing entry: user typed port + code from the wireless
     * debugging pairing dialog (or NSD-supplied endpoint). */
    suspend fun pair(host: String, port: Int, code: String): Result<String> =
        withContext(Dispatchers.IO) {
            _status.value = DaemonStatus(ShellState.PAIRING, detail = "key:pairing")
            runCatching {
                val pem = ensureKey()
                val resp = JSONObject(Core.nativePair(host, port, code, pem))
                if (!resp.optBoolean("ok")) error(resp.optString("error"))
                resp.getJSONObject("data").optString("guid")
            }.onSuccess {
                _status.value = DaemonStatus(ShellState.OFFLINE, detail = "key:paired_pending")
            }.onFailure {
                _status.value = DaemonStatus(ShellState.ERROR, detail = it.message ?: "key:pair_failed")
            }
        }

    /**
     * Push the bundled daemon over the ADB session and spawn it, then
     * connect + auth our local socket.
     */
    suspend fun connectAndSpawn(host: String, port: Int): Result<DaemonClient> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                _status.value = DaemonStatus(ShellState.CONNECTING, detail = "key:connecting_adb")
                runCatching {
                    val pem = ensureKey()
                    val daemonBytes = daemonBinary()
                    val socket = "boxagentd.${rand.nextInt().toString(16)}"
                    val token = ByteArray(24).also { rand.nextBytes(it) }
                        .joinToString("") { "%02x".format(it) }
                    val daemonB64 = Base64.encodeToString(daemonBytes, Base64.NO_WRAP)

                    val resp = JSONObject(
                        Core.nativeSpawnDaemon(
                            pem, host, port, daemonB64,
                            REMOTE_PATH, socket, token,
                        )
                    )
                    if (!resp.optBoolean("ok")) error(resp.optString("error"))

                    // Give the daemon a moment to bind, then connect.
                    var cli: DaemonClient? = null
                    var lastErr: Exception? = null
                    repeat(8) {
                        try {
                            cli = DaemonClient.connect(socket, token)
                        } catch (e: Exception) {
                            lastErr = e
                        }
                        if (cli != null) return@repeat
                        delay(400)
                    }
                    cli ?: throw lastErr ?: IllegalStateException("socket connect failed")

                    secrets.daemonToken = token
                    settings.setDaemonSocket(socket)
                    settings.setAdbEndpoint(host, port)
                    client = cli
                    _status.value = DaemonStatus(
                        ShellState.ONLINE,
                        uid = 2000,
                        detail = "key:daemon_up",
                        socket = socket,
                    )
                    startWatchdog()
                    cli
                }.onFailure {
                    _status.value = DaemonStatus(ShellState.ERROR, detail = it.message ?: "key:spawn_failed")
                }
            }
        }

    /** Reconnect to an already-spawned daemon (e.g. after app restart). */
    suspend fun reconnect(): DaemonClient? = lock.withLock {
        val socket = settings.daemonSocket.first()
        val token = secrets.daemonToken
        if (socket.isEmpty() || token.isEmpty()) return@withLock null
        val cli = DaemonClient.connect(socket, token) ?: return@withLock null
        if (!cli.ping()) {
            cli.close()
            return@withLock null
        }
        client = cli
        _status.value = DaemonStatus(ShellState.ONLINE, uid = 2000, detail = "key:reconnected", socket = socket)
        startWatchdog()
        cli
    }

    suspend fun requireClient(): DaemonClient {
        client?.let { if (it.isAlive) return it }
        reconnect()?.let { return it }
        // No existing daemon — try respawning via the stored endpoint.
        val host = settings.adbHost.first()
        val port = settings.adbPort.first()
        if (port > 0) {
            connectAndSpawn(host, port).getOrNull()?.let { return it }
        }
        throw IllegalStateException("no shell daemon — pair and connect first")
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(15_000)
                if (!settings.keepWatchdog.first()) continue
                val c = client ?: continue
                if (!c.ping()) {
                    _status.value = DaemonStatus(ShellState.CONNECTING, detail = "key:daemon_lost")
                    c.close()
                    client = null
                    reconnect() ?: run {
                        val host = settings.adbHost.first()
                        val port = settings.adbPort.first()
                        if (port > 0) connectAndSpawn(host, port)
                    }
                }
            }
        }
    }

    private fun daemonBinary(): ByteArray {
        val libDir = java.io.File(context.applicationInfo.nativeLibraryDir)
        val f = java.io.File(libDir, "libboxagentd.so")
        check(f.exists()) { "daemon binary missing from nativeLibraryDir" }
        return f.readBytes()
    }

    companion object {
        const val REMOTE_PATH = "/data/local/tmp/boxagentd"
    }
}
