package com.boxagent.app.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
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
    /** Nothing reachable. [wirelessOff]: wireless debugging is switched
     *  off and BoxAgent can't turn it back on itself (it can once the
     *  daemon has run once — see [DaemonManager.grantSelfSecureSettings]). */
    data class Manual(val wirelessOff: Boolean = false) : AutoOutcome
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
            // Loopback TCP, not an abstract socket: SELinux denies apps
            // `connectto` on shell-domain unix sockets (see HelperConn).
            val socket = HelperConn.tcp(HelperConn.freePort())
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
            // Stop at the first answer — every extra connect leaks a socket.
            var cli: DaemonClient? = null
            for (attempt in 0 until 10) {
                cli = DaemonClient.connect(socket, token)
                if (cli != null) break
                delay(300)
            }
            if (cli == null) {
                // The spawn "succeeded" but nothing answers: the daemon
                // died on start. Its log says why (bad arch, noexec, bind).
                val log = runCatching {
                    JSONObject(Core.nativeAdbShell(pem, host, port, "tail -n 5 $LOG_PATH"))
                        .optJSONObject("data")?.optString("output")?.trim()
                }.getOrNull()
                val base = DaemonClient.lastError ?: "daemon did not answer"
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
            scope.launch { grantSelfSecureSettings() }
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
        // A daemon from before the TCP switch listens on an abstract
        // socket only (reachable on permissive builds alone) — replace it
        // like a version mismatch so the app ends up on loopback TCP.
        val legacy = !HelperConn.isTcp(socket)
        if (cli.daemonVersion != BuildConfig.VERSION_NAME || legacy) {
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
        scope.launch { grantSelfSecureSettings() }
        cli
    }

    suspend fun requireClient(): DaemonClient {
        client?.let { if (it.isAlive) return it }
        reconnect()?.let { return it }
        // No live daemon — respawn through whatever endpoint is up now
        // (the wireless-debugging port moves on every toggle/reboot).
        if (autoConnect() == AutoOutcome.Online) client?.let { return it }
        throw IllegalStateException("no shell daemon — pair and connect first")
    }

    /**
     * Get shell access with as little user input as possible, cheapest
     * first:
     *   1. a live or previously spawned daemon,
     *   2. the endpoint that worked last time,
     *   3. plain adb on 127.0.0.1:5555 (emulator, `adb tcpip`, rooted),
     *   4. wireless debugging switched back on if it went off (reboot,
     *      Wi-Fi change) — possible once we hold WRITE_SECURE_SETTINGS,
     *   5. wireless debugging's mDNS connect endpoint (already paired),
     *   6. its pairing endpoint — returned for the user to finish,
     *   7. manual entry.
     * Silent: callers decide whether a [AutoOutcome.NeedsPair] /
     * [AutoOutcome.Manual] result becomes UI. Does NOT take [lock] — each
     * probe holds it via [connectAndSpawn].
     */
    suspend fun autoConnect(
        step: AutoStepSink = AutoStepSink { _, _ -> },
    ): AutoOutcome = autoMu.withLock {
        step.onStep("reconnect", "")
        reconnect()?.let { return@withLock AutoOutcome.Online }

        val host = settings.adbHost.first()
        val port = settings.adbPort.first()
        val tried = mutableSetOf<String>()
        suspend fun tryOnce(h: String, p: Int): Boolean =
            tried.add("$h:$p") && probe(h, p, step)

        if (port > 0 && tryOnce(host, port)) return@withLock AutoOutcome.Online
        if (tryOnce(NsdHelper.LOOPBACK, 5555)) return@withLock AutoOutcome.Online

        val wasOn = isWirelessDebuggingOn()
        val justEnabled = !wasOn && enableWirelessDebugging()
        if (justEnabled) step.onStep("enable_wd", "")

        step.onStep("scan", "")
        // A freshly enabled adbd takes a few seconds to announce itself.
        val scanMs = if (justEnabled) MDNS_SCAN_MS * 3 else MDNS_SCAN_MS
        findEndpoint(NsdHelper.TYPE_CONNECT, scanMs)?.let {
            if (tryOnce(it.host, it.port)) return@withLock AutoOutcome.Online
        }
        findEndpoint(NsdHelper.TYPE_PAIRING, MDNS_PAIR_MS)?.let {
            return@withLock AutoOutcome.NeedsPair(it.host, it.port)
        }
        AutoOutcome.Manual(wirelessOff = !isWirelessDebuggingOn())
    }

    /**
     * Pairing just succeeded — the pairing service flips to a connect
     * endpoint shortly after. Poll mDNS, then spawn. No stored endpoint
     * is touched: the pairing port is not the connect port.
     */
    suspend fun connectAfterPair(step: AutoStepSink): AutoOutcome = autoMu.withLock {
        repeat(4) {
            step.onStep("scan", "")
            findEndpoint(NsdHelper.TYPE_CONNECT, MDNS_SCAN_MS)?.let {
                if (probe(it.host, it.port, step)) return@withLock AutoOutcome.Online
            }
            delay(800)
        }
        AutoOutcome.Manual()
    }

    /** First _adb-tls-* service of THIS device that resolves (as a
     *  127.0.0.1 endpoint), or null on timeout/failure. */
    suspend fun findEndpoint(type: String, timeoutMs: Long): AdbEndpoint? =
        runCatching {
            withTimeoutOrNull(timeoutMs) {
                nsd.discover(type).first {
                    it.port > 0 || it.serviceName.startsWith("discovery_failed")
                }
            }?.takeIf { it.port > 0 }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
        }.getOrNull()

    /** A daemon came up at least once — before that the silent ladder
     *  (boot, health worker) has nothing to restore. */
    fun everConnected(): Boolean = secrets.daemonToken.isNotEmpty()

    /** Settings → Developer options → Wireless debugging. */
    fun isWirelessDebuggingOn(): Boolean =
        AndroidSettings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Wireless debugging turns itself off on reboot and whenever Wi-Fi
     * drops — the main reason shell "doesn't come back". With
     * WRITE_SECURE_SETTINGS (granted by our own daemon, Shizuku-style)
     * flip it back on, but only while USB debugging is still enabled: if
     * the user switched developer debugging off, that's a decision to
     * respect. adbd itself refuses (and resets the flag) on Wi-Fi
     * networks the user never allowed. True when it ends up on.
     */
    suspend fun enableWirelessDebugging(): Boolean {
        if (isWirelessDebuggingOn()) return true
        if (!canWriteSecureSettings()) return false
        val cr = context.contentResolver
        if (AndroidSettings.Global.getInt(cr, AndroidSettings.Global.ADB_ENABLED, 0) != 1) return false
        val ok = runCatching { AndroidSettings.Global.putInt(cr, ADB_WIFI_ENABLED, 1) }
            .getOrDefault(false)
        if (!ok) return false
        delay(1_500)
        return isWirelessDebuggingOn()
    }

    /**
     * Ask our shell daemon to grant WRITE_SECURE_SETTINGS to the app —
     * a development permission shell may grant. It is what lets
     * [enableWirelessDebugging] bring shell back after a reboot with no
     * human in the loop. Best-effort, once per process.
     */
    private suspend fun grantSelfSecureSettings() {
        if (secureGrantTried || canWriteSecureSettings()) return
        secureGrantTried = true
        runCatching {
            client?.exec("pm grant ${context.packageName} $WRITE_SECURE_SETTINGS", 10_000)
        }
    }
    @Volatile private var secureGrantTried = false

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
                        // The stored port is stale after any wireless
                        // debugging restart — climb the silent ladder.
                        if (autoConnect() != AutoOutcome.Online) {
                            _status.value = DaemonStatus(ShellState.OFFLINE, detail = "key:daemon_lost")
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
        /** Settings.Global.ADB_WIFI_ENABLED (hidden constant). */
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        private const val MDNS_SCAN_MS = 2_500L
        private const val MDNS_PAIR_MS = 1_500L
    }
}
