package com.boxagent.app.daemon

import android.content.Context
import android.util.Base64
import com.boxagent.app.BuildConfig
import com.boxagent.app.bridge.Core
import com.boxagent.app.data.Secrets
import com.boxagent.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val daemonVersion: String = "",
)

/** Reports ladder rungs to the UI — keys are string resources, [arg] is
 *  an endpoint like "192.168.1.5:39001". */
fun interface AutoStepSink {
    fun onStep(key: String, arg: String)
}

/** How [DaemonManager.autoConnect] ended. */
sealed interface AutoOutcome {
    data object Online : AutoOutcome
    /** Wireless debugging is up but unpaired — the only step that needs
     *  a human (the 6-digit code from the system pairing dialog). */
    data class NeedsPair(val host: String, val port: Int) : AutoOutcome
    /** Nothing discoverable — fall back to manual host/port entry. */
    data object Manual : AutoOutcome
}

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
    /** Serializes [autoConnect]/[connectAfterPair] — a second ladder waits
     *  for the first instead of probing the same endpoints concurrently. */
    private val autoMu = Mutex()
    private val rand = SecureRandom()
    private val nsd = NsdHelper(context)

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
        return secrets.adbKeyPem.ifEmpty {
            error("could not generate or load the adb key")
        }
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
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
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
            lock.withLock { connectAndSpawnLocked(host, port) }
        }

    /** Lock-free inner of [connectAndSpawn] — callers must hold [lock]. */
    private suspend fun connectAndSpawnLocked(host: String, port: Int): Result<DaemonClient> {
        _status.value = DaemonStatus(ShellState.CONNECTING, detail = "key:connecting_adb")
        return runCatching {
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
            if (cli == null) {
                // The spawn "succeeded" but nothing is listening: the daemon
                // died on start. Its log says why (bad arch, noexec, bind).
                val log = runCatching {
                    JSONObject(Core.nativeAdbShell(pem, host, port, "tail -n 5 $LOG_PATH"))
                        .optJSONObject("data")?.optString("output")?.trim()
                }.getOrNull()
                val base = lastErr?.message ?: "socket connect failed"
                throw IllegalStateException(
                    if (log.isNullOrEmpty()) base else "$base — daemon log: $log",
                )
            }

            secrets.daemonToken = token
            settings.setDaemonSocket(socket)
            settings.setAdbEndpoint(host, port)
            client?.close()
            client = cli
            _status.value = DaemonStatus(
                ShellState.ONLINE,
                uid = 2000,
                detail = "key:daemon_up",
                socket = socket,
                daemonVersion = cli!!.daemonVersion,
            )
            startWatchdog()
            cli
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            _status.value = DaemonStatus(ShellState.ERROR, detail = it.message ?: "key:spawn_failed")
        }
    }

    /** Reconnect to an already-spawned daemon (e.g. after app restart).
     *  Overwrite-installed upgrades leave the previous daemon running;
     *  auth_ok carries its build version — respawn on mismatch so the wire
     *  protocol can't drift between app and daemon. */
    suspend fun reconnect(): DaemonClient? = lock.withLock {
        // Already healthy: keep it (a second connection would leak the
        // first, possibly mid-exec for the agent).
        client?.takeIf { it.isAlive }?.let { if (it.ping()) return@withLock it }
        client?.close()
        client = null
        val socket = settings.daemonSocket.first()
        val token = secrets.daemonToken
        if (socket.isEmpty() || token.isEmpty()) return@withLock null
        val cli = DaemonClient.connect(socket, token) ?: return@withLock null
        if (!cli.ping()) {
            cli.close()
            return@withLock null
        }
        if (cli.daemonVersion != BuildConfig.VERSION_NAME) {
            cli.shutdown()
            val host = settings.adbHost.first()
            val port = settings.adbPort.first()
            if (port > 0) {
                _status.value = DaemonStatus(
                    ShellState.CONNECTING,
                    detail = "key:daemon_upgrade",
                )
                return connectAndSpawnLocked(host, port).getOrNull()
            }
            return@withLock null
        }
        client = cli
        _status.value = DaemonStatus(
            ShellState.ONLINE,
            uid = 2000,
            detail = "key:reconnected",
            socket = socket,
            daemonVersion = cli.daemonVersion,
        )
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

    /**
     * Get shell access with as little user input as possible, cheapest
     * first:
     *   1. a live or previously spawned daemon,
     *   2. the endpoint that worked last time,
     *   3. plain adb on 127.0.0.1:5555 (emulator, `adb tcpip`, rooted),
     *   4. wireless debugging's mDNS connect endpoint (already paired),
     *   5. its pairing endpoint — returned for the user to finish,
     *   6. manual entry.
     * Does NOT take [lock] — each probe holds it via [connectAndSpawn].
     */
    suspend fun autoConnect(
        step: AutoStepSink = AutoStepSink { _, _ -> },
    ): AutoOutcome = autoMu.withLock {
        step.onStep("reconnect", "")
        reconnect()?.let { return@withLock AutoOutcome.Online }

        val host = settings.adbHost.first()
        val port = settings.adbPort.first()
        if (port > 0 && probe(host, port, step)) return@withLock AutoOutcome.Online

        if (host != "127.0.0.1" || port != 5555) {
            if (probe("127.0.0.1", 5555, step)) return@withLock AutoOutcome.Online
        }

        step.onStep("scan", "")
        findEndpoint(NsdHelper.TYPE_CONNECT, MDNS_SCAN_MS)?.let {
            if (probe(it.host, it.port, step)) return@withLock AutoOutcome.Online
        }
        findEndpoint(NsdHelper.TYPE_PAIRING, MDNS_PAIR_MS)?.let {
            return@withLock AutoOutcome.NeedsPair(it.host, it.port)
        }
        AutoOutcome.Manual
    }

    /**
     * Pairing just succeeded — the pairing service flips to a connect
     * endpoint shortly after. Poll mDNS, then spawn. No stored endpoint
     * is touched: the pairing port is not the connect port.
     */
    suspend fun connectAfterPair(step: AutoStepSink): AutoOutcome = autoMu.withLock {
        repeat(3) {
            step.onStep("scan", "")
            findEndpoint(NsdHelper.TYPE_CONNECT, MDNS_SCAN_MS)?.let {
                if (probe(it.host, it.port, step)) return@withLock AutoOutcome.Online
            }
            delay(800)
        }
        AutoOutcome.Manual
    }

    /** First _adb-tls-* service that resolves, or null on timeout/failure. */
    suspend fun findEndpoint(type: String, timeoutMs: Long): AdbEndpoint? =
        runCatching {
            withTimeoutOrNull(timeoutMs) {
                nsd.discover(type).first {
                    it.port > 0 || it.serviceName.startsWith("discovery_failed")
                }
            }?.takeIf { it.port > 0 }
        }.getOrNull()

    /** One spawn attempt. The JNI op is hard-deadlined in Rust so it
     *  always returns and releases [lock]; the Kotlin-side timeout is a
     *  backstop set well above the native deadline. */
    private suspend fun probe(host: String, port: Int, step: AutoStepSink): Boolean {
        step.onStep("try", "$host:$port")
        val r = withTimeoutOrNull(PROBE_TIMEOUT_MS) { connectAndSpawn(host, port) }
        return r?.isSuccess == true
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(15_000)
                // One bad read (a DataStore hiccup, a reconnect racing a
                // spawn) must not kill the watchdog for the rest of the
                // process lifetime.
                runCatching {
                    if (!settings.keepWatchdog.first()) return@runCatching
                    val c = client ?: return@runCatching
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
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }
            }
        }
    }

    /**
     * The daemon ships as lib/<abi>/libboxagentd.so. With uncompressed
     * native libs (extractNativeLibs=false, our packaging) the installer
     * never unpacks it into nativeLibraryDir — read it straight out of the
     * APK (base or config split). It is only pushed over ADB, never run here.
     */
    private fun daemonBinary(): ByteArray {
        val name = "libboxagentd.so"
        val info = context.applicationInfo
        java.io.File(info.nativeLibraryDir, name).takeIf { it.isFile }?.let { return it.readBytes() }
        val apks = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        for (abi in android.os.Build.SUPPORTED_ABIS) {
            for (apk in apks) {
                java.util.zip.ZipFile(apk).use { z ->
                    z.getEntry("lib/$abi/$name")?.let { e ->
                        return z.getInputStream(e).use { it.readBytes() }
                    }
                }
            }
        }
        error("daemon binary missing for ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
    }

    companion object {
        const val REMOTE_PATH = "/data/local/tmp/boxagentd"
        const val LOG_PATH = "/data/local/tmp/boxagentd.log"
        /** Above the Rust op deadline (25s) — a backstop, not the bound. */
        private const val PROBE_TIMEOUT_MS = 35_000L
        private const val MDNS_SCAN_MS = 2_500L
        private const val MDNS_PAIR_MS = 1_500L
    }
}
