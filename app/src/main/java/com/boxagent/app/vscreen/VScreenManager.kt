package com.boxagent.app.vscreen

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import com.boxagent.app.daemon.DaemonManager
import com.boxagent.app.data.Secrets
import com.boxagent.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

enum class VState { OFF, STARTING, READY, ERROR }

data class VScreenStatus(
    val state: VState = VState.OFF,
    val displayId: Int = -1,
    val w: Int = 0,
    val h: Int = 0,
    val dpi: Int = 0,
    val detail: String = "",
)

/** A11yService reads this instead of holding manager references — the
 *  service binds/unbinds on the system's schedule, not ours. */
object VScreenBridge {
    /** When set, a11y reads/gestures target this virtual display. */
    @Volatile var displayId: Int = -1
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    /** Semantic touch ops routed to the vscreen host; null = dispatchGesture. */
    @Volatile var touchBackend: TouchBackend? = null
}

/** Touch primitives the vscreen host performs instead of a11y gestures
 *  (dispatchGesture only reaches the physical display). */
interface TouchBackend {
    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun hold(x: Float, y: Float, durationMs: Long): Boolean
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean
    suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean, percent: Int): Boolean
}

/**
 * Owns the virtual-display lifecycle: pushes the host jar through the
 * daemon, spawns `app_process`, connects the socket, creates the display
 * and wires a11y filtering + touch routing. When disabled the manager is
 * inert and the agent runs on the physical screen exactly as before.
 */
class VScreenManager(
    private val context: Context,
    private val settings: Settings,
    private val secrets: Secrets,
    private val daemon: DaemonManager,
) {
    private val lock = Mutex()
    private val rand = SecureRandom()

    private val _status = MutableStateFlow(VScreenStatus())
    val status: StateFlow<VScreenStatus> = _status

    @Volatile var client: VScreenClient? = null
        private set
    @Volatile var displayId: Int = -1
        private set

    suspend fun isEnabled() = settings.vscreen.first()

    /** Live client when enabled + ready, else null. Callers that need the
     *  virtual display should error rather than fall back to the physical
     *  screen — a silent fallback taps the user's real screen. */
    suspend fun ready(): VScreenClient? {
        if (!isEnabled()) return null
        return try {
            // Spawn + dexopt + connect should take seconds; a wedged step
            // must surface as an error, not park the tool call forever.
            kotlinx.coroutines.withTimeout(45_000) { ensureClient() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _status.value = _status.value.copy(
                state = VState.ERROR, detail = "host bring-up timed out")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(
                state = VState.ERROR,
                detail = e.message ?: e.javaClass.simpleName,
            )
            null
        }
    }

    /** Ensures host + display exist; throws with a readable reason. */
    private suspend fun ensureClient(): VScreenClient = lock.withLock {
        client?.takeIf { it.isAlive && displayId >= 0 }
            ?.let { if (it.ping()) return it }
        client?.close()
        client = null
        displayId = -1
        detachA11y()
        _status.value = VScreenStatus(state = VState.STARTING, detail = "connecting")

        val dc = daemon.requireClient()
        val jarUpdated = pushJar(dc)
        var cli = VScreenClient.connect(VScreenClient.SOCKET_NAME, token())
        // A live host running a stale jar must be replaced — its display
        // keeps the old flags/behavior even though the socket answers.
        if (cli != null && jarUpdated) {
            cli.close(); cli = null
            runCatching { dc.exec(KILL_CMD) }
        }
        if (cli == null) {
            spawn(dc)
            // First spawn pays for dexopt — give it room.
            repeat(20) {
                delay(400)
                cli = VScreenClient.connect(VScreenClient.SOCKET_NAME, token())
                if (cli != null) return@repeat
            }
        }
        val c = cli ?: throw IllegalStateException(hostLogTail(dc))

        // Ensure the display exists at the configured size.
        val (w, h, dpi) = wantedSize()
        var info = c.info()
        var id = info.optInt("display_id", -1)
        if (id < 0 || info.optInt("w") != w || info.optInt("h") != h) {
            info = c.create(w, h, dpi)
            id = info.optInt("display_id", -1)
        }
        if (id < 0) throw IllegalStateException("display create failed")

        client = c
        displayId = id
        attachA11y(id, w, h)
        _status.value = VScreenStatus(
            state = VState.READY, displayId = id, w = w, h = h, dpi = dpi,
            detail = "display $id · ${w}x$h@$dpi",
        )
        c
    }

    /** Called from settings: turn the feature off/on. On disable the host
     *  and its display go away; the agent instantly returns to physical. */
    suspend fun setEnabled(enabled: Boolean) {
        settings.setVscreen(enabled)
        if (!enabled) {
            release()
        } else {
            // Warm it up now so the first tool call isn't the spawn.
            ready()
        }
    }

    /** Destroy the display and stop the host. */
    suspend fun release() = lock.withLock {
        runCatching { client?.destroy() }
        client?.close()
        client = null
        displayId = -1
        detachA11y()
        _status.value = VScreenStatus(state = VState.OFF)
    }

    /** Push the bundled host jar when the remote copy is stale; true when
     *  a new jar was written (a running host then needs a respawn). */
    private suspend fun pushJar(dc: com.boxagent.app.daemon.DaemonClient): Boolean {
        val jar = context.assets.open("vscreen.jar").use { it.readBytes() }
        val remote = runCatching {
            dc.exec("md5sum $REMOTE_JAR 2>/dev/null | cut -d' ' -f1").stdout.trim()
        }.getOrDefault("")
        val local = java.security.MessageDigest.getInstance("MD5")
            .digest(jar).joinToString("") { "%02x".format(it) }
        if (remote != local) {
            dc.fileWrite(REMOTE_JAR, jar)
            return true
        }
        return false
    }

    /**
     * Spawn the app_process host through the daemon. Environment: a bare
     * `sh -c` from the daemon may lack the ART vars (redroid's adbd passes
     * almost nothing), and dexopt additionally needs DEX2OATBOOTCLASSPATH.
     * The app's own process env — inherited from zygote — carries all of
     * them, so we forward it; a /proc environ scrape remains as fallback
     * for runtimes where the app env is also stripped.
     */
    private suspend fun spawn(dc: com.boxagent.app.daemon.DaemonClient) {
        // A dead-but-listening old host (stale token, wedged reader) must
        // go before a new one can bind the abstract socket.
        dc.exec(KILL_CMD)
        val tok = token()
        val script = """
export ANDROID_DATA=/data/local/tmp
${hostEnvLines()}
export PATH="${'$'}{PATH}:/system/bin:/system/xbin"
mkdir -p /data/local/tmp/dalvik-cache
if [ -z "${'$'}BOOTCLASSPATH" ]; then
  for f in /proc/[0-9]*/environ; do
    v=${'$'}(tr '\0' '\n' < "${'$'}f" 2>/dev/null | grep -m1 ^BOOTCLASSPATH=)
    [ -n "${'$'}v" ] && export BOOTCLASSPATH="${'$'}{v#BOOTCLASSPATH=}" && break
  done
fi
CLASSPATH=$REMOTE_JAR setsid app_process /system/bin com.boxagent.vscreen.Main --socket ${VScreenClient.SOCKET_NAME} --token $tok </dev/null >>$HOST_LOG 2>&1 &
        """.trimIndent()
        dc.exec("sh -c ${sq(script)}", 10_000)
    }

    /** `export K='v'` lines for ART env the app process inherited from
     *  zygote (BOOTCLASSPATH, ANDROID_*_ROOT, …). */
    private fun hostEnvLines(): String {
        val env = System.getenv()
        val keys = listOf(
            "ANDROID_ROOT", "ANDROID_ASSETS", "ANDROID_STORAGE",
            "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH",
        )
        return keys.mapNotNull { k ->
            env[k]?.takeIf { it.isNotEmpty() }?.let { "export $k=${sq(it)}" }
        }.joinToString("\n")
    }

    private suspend fun hostLogTail(dc: com.boxagent.app.daemon.DaemonClient): String {
        val tail = runCatching {
            dc.exec("tail -n 5 $HOST_LOG 2>/dev/null").stdout.trim()
        }.getOrDefault("")
        return "vscreen host not reachable" +
            if (tail.isEmpty()) "" else " — host log: $tail"
    }

    private fun token(): String {
        if (secrets.vscreenToken.isEmpty()) {
            secrets.vscreenToken = ByteArray(24).also { rand.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
        }
        return secrets.vscreenToken
    }

    /** Configured size, falling back to the physical panel's metrics. */
    private suspend fun wantedSize(): Triple<Int, Int, Int> {
        val w = settings.vscreenW.first()
        val h = settings.vscreenH.first()
        val dpi = settings.vscreenDpi.first()
        if (w > 0 && h > 0 && dpi > 0) return Triple(w, h, dpi)
        val dm = DisplayMetrics()
        (context.getSystemService(Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).let {
                @Suppress("DEPRECATION") it.getRealMetrics(dm)
            }
        return Triple(
            if (w > 0) w else dm.widthPixels,
            if (h > 0) h else dm.heightPixels,
            if (dpi > 0) dpi else dm.densityDpi,
        )
    }

    private fun attachA11y(id: Int, w: Int, h: Int) {
        VScreenBridge.displayId = id
        VScreenBridge.width = w
        VScreenBridge.height = h
        VScreenBridge.touchBackend = VScreenTouch(this)
    }

    private fun detachA11y() {
        VScreenBridge.displayId = -1
        VScreenBridge.touchBackend = null
    }

    /** Touch ops → the connected host. ensureClient is NOT called here —
     *  the backend is only consulted while a display exists. */
    private class VScreenTouch(private val mgr: VScreenManager) : TouchBackend {
        private suspend fun cli() = mgr.client
            ?: throw IllegalStateException("virtual display host is gone")

        override suspend fun tap(x: Float, y: Float) =
            runCatching { cli().tap(x.toDouble(), y.toDouble()); true }.getOrDefault(false)
        override suspend fun hold(x: Float, y: Float, durationMs: Long) =
            runCatching { cli().longPress(x.toDouble(), y.toDouble(), durationMs); true }
                .getOrDefault(false)
        override suspend fun swipe(
            x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
        ) = runCatching {
            cli().swipe(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), durationMs)
            true
        }.getOrDefault(false)
        override suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean, percent: Int) =
            runCatching {
                cli().pinch(cx.toDouble(), cy.toDouble(), zoomIn, percent); true
            }.getOrDefault(false)
    }

    companion object {
        const val REMOTE_JAR = "/data/local/tmp/boxagent-vscreen.jar"
        const val HOST_LOG = "/data/local/tmp/vscreen.log"
        const val PID_FILE = "/data/local/tmp/boxagent-vscreen.pid"
        // Shell can't enumerate the host (restricted /proc view) — the
        // host records its pid here at startup so `kill` still reaches it.
        private val KILL_CMD =
            "p=${'$'}(cat $PID_FILE 2>/dev/null); [ -n \"${'$'}p\" ] && kill ${'$'}p 2>/dev/null;" +
                " rm -f $PID_FILE; true"
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
