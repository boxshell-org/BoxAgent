package com.boxagent.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.boxagent.app.daemon.DaemonManager
import com.boxagent.app.daemon.ExecResult
import com.boxagent.app.data.ConfirmPolicy
import com.boxagent.app.data.Settings
import com.boxagent.app.data.db.AppDb
import com.boxagent.app.data.db.AuditEntry
import com.boxagent.app.data.db.ToolCallRecord
import com.boxagent.app.service.A11yService
import com.boxagent.app.vscreen.VScreenClient
import com.boxagent.app.vscreen.VScreenManager
import com.boxagent.app.skills.SkillCodec
import com.boxagent.app.skills.SkillRepository
import com.boxagent.app.skills.SkillStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Pending user approval for a risky tool call. */
data class PendingConfirm(
    val tool: String,
    val argsJson: String,
    val risk: String,
    val answer: CompletableDeferred<Boolean>,
)

/**
 * Routes one atomic tool call to the right backend (daemon shell,
 * accessibility service, or meta), enforcing the confirmation policy and
 * writing every call to the audit log + tool_calls table.
 */
class ToolRunner(
    private val context: Context,
    private val daemon: DaemonManager,
    private val settings: Settings,
    private val db: AppDb,
    private val skills: SkillRepository,
    private val vscreen: VScreenManager,
) {
    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pending: StateFlow<PendingConfirm?> = _pending
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Written from the UI thread, read from the agent's Rust thread.
    private val alwaysAllowed: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var conversationId: Long = 0

    /** UI entry point: user approves/declines the current confirmation. */
    fun resolveConfirm(approved: Boolean, always: Boolean) {
        _pending.value?.let { p ->
            if (always && approved) alwaysAllowed.add(p.tool)
            p.answer.complete(approved)
        }
        _pending.value = null
    }

    /** Replayable form of the current run's successful actions — the raw
     *  material for "Save as skill" and the agent's `save_skill`. */
    private val trace = mutableListOf<SkillStep>()

    fun beginTrace() = synchronized(trace) { trace.clear() }

    fun traceSnapshot(): List<SkillStep> = synchronized(trace) { trace.toList() }

    /** Actions worth replaying: anything that changes state, plus waits. */
    private fun recordable(name: String) =
        name != "save_skill" && name != "notify" &&
            (name == "wait_for" || riskOf(name) != "readonly")

    /** Agent stopped: deny whatever is waiting so its thread can unwind. */
    fun cancelPending() {
        _pending.value?.answer?.complete(false)
        _pending.value = null
    }

    private fun riskOf(name: String): String =
        ToolCatalog.byName(name)?.risk ?: "moderate"

    private suspend fun needsConfirm(name: String): Boolean {
        if (name in alwaysAllowed) return false
        val risk = riskOf(name)
        return when (settings.confirmPolicy.first()) {
            ConfirmPolicy.AUTONOMOUS -> false
            ConfirmPolicy.BALANCED -> risk == "destructive"
            ConfirmPolicy.STRICT -> risk != "readonly"
        }
    }

    /** Called by the Rust agent loop (on its own thread). Must not suspend.
     *  Bounded so a call that never returns (wedged daemon socket, a
     *  gesture/screenshot callback that never fires, a forgotten confirm)
     *  can't park the run — and the input dock — forever. */
    fun executeBlocking(name: String, argsJson: String): String =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(CALL_TIMEOUT_MS) {
                execute(name, argsJson)
            }?.toString() ?: JSONObject()
                .put("ok", false)
                .put("error", "tool call timed out after ${CALL_TIMEOUT_MS / 1000}s")
                .toString()
        }

    suspend fun execute(name: String, argsJson: String): String {
        val start = System.currentTimeMillis()

        if (needsConfirm(name)) {
            val approved = awaitConfirm(name, argsJson)
            ioScope.launch { audit("tool_call", "$name approved=$approved args=redacted", approved) }
            if (!approved) {
                ioScope.launch { record(name, argsJson, "denied", false, start) }
                return JSONObject().put("ok", false).put("error", "user denied").toString()
            }
        }

        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrNull()
        // Resolve a ref while it is still valid — the action changes the
        // screen and its snapshot.
        val refTarget = args?.takeIf { it.has("ref") }?.let { a ->
            runCatching { A11yService.instance?.describe(a.getInt("ref")) }.getOrNull()
        }
        val result = runCatching {
            dispatch(name, args ?: throw IllegalArgumentException("arguments must be a JSON object"))
        }.getOrElse { e ->
            // executeBlocking's timeout cancels dispatch; letting the
            // CancellationException escape is what makes withTimeoutOrNull
            // return null — and the caller the "timed out" error.
            if (e is kotlinx.coroutines.CancellationException) throw e
            JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName)
        }

        val ok = result.optBoolean("ok", true)
        if (ok && args != null && recordable(name)) {
            SkillCodec.portable(name, args, refTarget)?.let { st ->
                synchronized(trace) { trace += st }
            }
        }
        val redacted = redact(result)
        // Persistence is observability, not correctness — don't make the
        // agent wait on Room.
        ioScope.launch {
            record(name, argsJson, redacted.toString(), ok, start)
            audit("tool_call", "$name ok=$ok", ok)
        }
        return result.toString()
    }

    private suspend fun awaitConfirm(name: String, argsJson: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val pc = PendingConfirm(name, argsJson, riskOf(name), deferred)
        _pending.value = pc
        try {
            return deferred.await()
        } finally {
            // A cancelled/timed-out call must not leave its card up —
            // answering it would do nothing.
            _pending.compareAndSet(pc, null)
        }
    }

    private suspend fun dispatch(name: String, a: JSONObject): JSONObject {
        // Virtual screen: display-semantic tools must reach the VD — an
        // error here beats silently tapping the user's physical screen.
        val vdOn = settings.vscreen.first()
        val vs = if (vdOn && name in VD_SCOPED) vscreen.ready() else null
        if (vdOn && name in VD_SCOPED && vs == null) {
            return err(IllegalStateException(
                "virtual screen unavailable: " +
                    vscreen.status.value.detail.ifEmpty { "host not running" }))
        }
        return when (name) {
            // -------- meta handled here (task_done/ask_user handled in Rust) --
            "notify" -> {
                val posted = Notifier.post(context, a.getString("title"), a.getString("text"))
                if (posted) ok()
                else err(SecurityException("notifications permission denied"))
            }
            // -------- shell backend ----------
            "shell_exec" -> shellExec(
                a.getString("cmd"),
                a.optLong("timeout_ms", 30_000).coerceIn(1_000, 120_000),
            )
            "app_list" -> runCatching {
                // Launchable apps only, one compact string each — the model
                // wants names to open, not every system package.
                val q = a.optStr("query")?.lowercase()
                val thirdOnly = a.optBoolean("third_party_only")
                val apps = launchableApps()
                    .filter { !thirdOnly || !it.system }
                    .filter { q == null || q in it.label.lowercase() || q in it.pkg.lowercase() }
                    .sortedBy { it.label.lowercase() }
                val shown = apps.take(MAX_APPS_LISTED)
                ok().put("count", apps.size)
                    .put("apps", JSONArray(shown.map { "${it.label} (${it.pkg})" }))
                    .apply { if (apps.size > shown.size) put("note", "filter with query to see the rest") }
            }.getOrElse { err(it) }
            "app_launch" -> {
                // By name, or "pkg", "pkg/.Act" in package, or a separate
                // component (".Act", "full.Cls" or "pkg/.Act").
                var pkg = a.optString("package").trim()
                val appName = a.optStr("name")
                if (pkg.isEmpty() && appName != null) {
                    val (found, candidates) = resolveApp(appName)
                    pkg = found ?: return JSONObject().put("ok", false).put(
                        "error",
                        if (candidates.isEmpty()) "no app named \"$appName\""
                        else "\"$appName\" is ambiguous",
                    ).apply { if (candidates.isNotEmpty()) put("candidates", JSONArray(candidates)) }
                }
                if (pkg.isEmpty()) return err(IllegalArgumentException("give name or package"))
                var comp = a.optString("component").trim()
                if (pkg.contains('/')) {
                    if (comp.isEmpty()) comp = pkg
                    pkg = pkg.substringBefore('/')
                }
                val compPkg = if (comp.contains('/')) comp.substringBefore('/') else pkg
                val clsPart = comp.substringAfter('/')
                val cls = when {
                    clsPart.startsWith(".") -> compPkg + clsPart
                    clsPart.contains(".") -> clsPart
                    else -> "$compPkg.$clsPart"
                }
                val launched = if (vs != null) {
                    // Virtual screen: am needs --display (monkey has none).
                    val compName = if (comp.isNotEmpty()) "$compPkg/$cls"
                        else context.packageManager.getLaunchIntentForPackage(pkg)
                            ?.component?.let { "${it.packageName}/${it.className}" }
                            ?: return err(IllegalStateException(
                                "no launcher activity for $pkg"))
                    shellExec("am start --display ${vscreen.displayId} -n ${sq(compName)}")
                } else runCatching {
                    val intent = if (comp.isNotEmpty()) {
                        Intent().setComponent(android.content.ComponentName(compPkg, cls))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    } else {
                        context.packageManager.getLaunchIntentForPackage(pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?: throw IllegalStateException("no launcher activity for $pkg")
                    }
                    context.startActivity(intent)
                    ok().put("package", pkg)
                }.getOrElse {
                    // Shell uid can still reach non-exported components and
                    // leanback-only launchers that PackageManager can't see.
                    if (comp.isNotEmpty()) shellExec("am start -n ${sq("$compPkg/$cls")}")
                    else shellExec("monkey -p ${sq(pkg)} -c android.intent.category.LAUNCHER 1")
                }
                withScreen(a, launched, waitPackage = pkg)
            }
            "app_stop" -> shellExec("am force-stop ${sq(a.getString("package"))}")
            "app_install" -> shellExec(
                "pm install ${if (a.optBoolean("replace", true)) "-r " else ""}${sq(a.getString("path"))}"
            )
            "app_uninstall" -> shellExec("pm uninstall ${sq(a.getString("package"))}")
            "app_clear_data" -> shellExec("pm clear ${sq(a.getString("package"))}")
            "screen_capture" -> if (vs != null) {
                runCatching {
                    val png = vs.screenshot()
                        ?: throw IllegalStateException("no frame rendered yet")
                    ok().put("mime", "image/png")
                        .put("data_b64", Base64.encodeToString(png, Base64.NO_WRAP))
                }.getOrElse { err(it) }
            } else screencapResult()
            "screen_info" -> if (vs != null) {
                val st = vscreen.status.value
                ok().put("width", st.w)
                    .put("height", st.h)
                    .put("density_dpi", st.dpi)
                    .put("density", st.dpi / 160.0)
                    .put("rotation", 0)
                    .put("display_id", st.displayId)
                    .put("virtual", true)
            } else runCatching {
                // Real size incl. system bars — the coordinate space gestures
                // use. App displayMetrics exclude the bars and ignore rotation.
                val dmgr = context.getSystemService(Context.DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager
                val display = dmgr.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val real = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") display.getRealMetrics(real)
                ok().put("width", real.widthPixels)
                    .put("height", real.heightPixels)
                    .put("density_dpi", real.densityDpi)
                    .put("density", real.density.toDouble())
                    .put("rotation", display.rotation * 90)
            }.getOrElse { err(it) }
            "device_info" -> runCatching {
                val bm = context.getSystemService(Context.BATTERY_SERVICE)
                    as android.os.BatteryManager
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                val net = cm.activeNetwork
                    ?.let { cm.getNetworkCapabilities(it) }
                    ?.let {
                        when {
                            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                            else -> "other"
                        }
                    } ?: "offline"
                ok().put("model", android.os.Build.MODEL)
                    .put("android", android.os.Build.VERSION.RELEASE)
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("battery_pct",
                        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("network", net)
            }.getOrElse { err(it) }
            "settings_get" -> runCatching {
                val v = when (a.getString("namespace")) {
                    "secure" -> android.provider.Settings.Secure.getString(
                        context.contentResolver, a.getString("key"))
                    "global" -> android.provider.Settings.Global.getString(
                        context.contentResolver, a.getString("key"))
                    else -> android.provider.Settings.System.getString(
                        context.contentResolver, a.getString("key"))
                }
                ok().put("value", v ?: JSONObject.NULL)
            }.getOrElse {
                // Some keys need shell-level read — fall back to the daemon.
                shellExec("settings get ${settingsNs(a)} ${sq(a.getString("key"))}")
            }
            "settings_put" -> shellExec(
                "settings put ${settingsNs(a)} ${sq(a.getString("key"))} ${sq(a.getString("value"))}"
            )
            "file_read" -> runCatching {
                val bytes = daemon.requireClient().fileRead(a.getString("path"))
                val asText = runCatching { String(bytes, Charsets.UTF_8) }
                    .getOrNull()?.takeIf { it.isNotEmpty() && !it.contains('�') }
                if (asText != null) {
                    // Text is far cheaper (and readable) for the LLM than b64.
                    ok().put("size", bytes.size)
                        .put("text", asText.take(4000))
                        .put("truncated", asText.length > 4000)
                } else {
                    ok().put("size", bytes.size)
                        .put("mime", "application/octet-stream")
                        .put("data_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            }.getOrElse { err(it) }
            "file_write" -> runCatching {
                val bytes = daemon.requireClient().fileWrite(
                    a.getString("path"),
                    Base64.decode(a.getString("data_b64"), Base64.DEFAULT),
                )
                ok().put("bytes", bytes)
            }.getOrElse { err(it) }
            "file_list" -> runCatching {
                ok().put("entries", JSONArray(daemon.requireClient().fileList(a.getString("path"))))
            }.getOrElse { err(it) }
            "process_list" -> shellExec("ps -A -o PID,USER,NAME,%CPU,RSS | head -80")
            "clipboard_get" -> runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val t = cm.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                    ?: throw IllegalStateException("empty")
                ok().put("text", t)
            }.getOrElse {
                // Background clipboard reads are restricted (Android 10+) —
                // the shell-uid daemon bypasses them.
                shellExec("cmd clipboard get 2>/dev/null || service call clipboard 2 | head -4")
            }
            "clipboard_set" -> runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("boxagent", a.getString("text")))
                // Android 10+ silently denies background writes — verify.
                val wrote = cm.primaryClip?.getItemAt(0)?.text?.toString() ==
                    a.getString("text")
                if (!wrote) throw IllegalStateException("denied")
                ok()
            }.getOrElse {
                shellExec("cmd clipboard set ${sq(a.getString("text"))}")
            }
            "input_tap" -> if (vs != null) {
                runCatching { vs.tap(a.getDouble("x"), a.getDouble("y")); ok() }
                    .getOrElse { err(it) }
            } else shellExec("input tap ${a.getInt("x")} ${a.getInt("y")}")
            "input_swipe" -> if (vs != null) {
                runCatching {
                    vs.swipe(
                        a.getDouble("x1"), a.getDouble("y1"),
                        a.getDouble("x2"), a.getDouble("y2"),
                        a.optLong("duration_ms", 300),
                    ); ok()
                }.getOrElse { err(it) }
            } else shellExec(
                "input swipe ${a.getInt("x1")} ${a.getInt("y1")} ${a.getInt("x2")} ${a.getInt("y2")} ${a.optInt("duration_ms", 300)}"
            )
            "input_text" -> if (vs != null) {
                runCatching { vs.text(a.getString("text")); ok() }
                    .getOrElse { err(it) }
            } else shellExec("input text ${sq(a.getString("text").replace(" ", "%s"))}")
            "input_key" -> if (vs != null) {
                runCatching { vs.key(keyCodeOf(a.getString("key"))); ok() }
                    .getOrElse { err(it) }
            } else shellExec("input keyevent ${sq(a.getString("key"))}")
            // -------- a11y backend ----------
            "ui_tree" -> a11y {
                it.dumpTree(a.optInt("max_depth", 30), a.optString("package"))
            }
            "ui_find" -> a11y {
                val nodes = it.findNodes(
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optBoolean("clickable_only"),
                )
                JSONObject().put("ok", true).put("count", nodes.size)
                    .put("nodes", JSONArray(nodes))
            }
            "screen" -> a11y { ok().put("screen", it.screenText()) }
            "tap" -> acting(a) {
                val (ok_, via) = it.tapOrNode(
                    a.optIntOrNull("ref"),
                    a.optDoubleOrNull("x")?.toFloat(), a.optDoubleOrNull("y")?.toFloat(),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                )
                result(ok_, via)
            }
            "long_press" -> acting(a) {
                val (ok_, via) = it.longPress(
                    a.optIntOrNull("ref"),
                    a.optDoubleOrNull("x")?.toFloat(), a.optDoubleOrNull("y")?.toFloat(),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optLong("duration_ms", 800),
                )
                result(ok_, via)
            }
            "swipe" -> acting(a) {
                val ok_ = it.swipe(
                    a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(),
                    a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(),
                    a.optLong("duration_ms", 300),
                )
                result(ok_, if (ok_) "gesture" else "gesture cancelled")
            }
            "pinch" -> acting(a) {
                val ok_ = it.pinch(
                    a.getDouble("x").toFloat(), a.getDouble("y").toFloat(),
                    a.getString("direction") == "in",
                    a.optInt("percent", 50),
                )
                result(ok_, if (ok_) "gesture" else "gesture cancelled")
            }
            "scroll" -> acting(a) {
                val (ok_, via) = it.scroll(
                    a.getString("direction"), a.optIntOrNull("ref"), a.optStr("text"),
                    a.optInt("times", 1),
                )
                result(ok_, via)
            }
            "type_text" -> acting(a) {
                val submit = a.optBoolean("submit")
                var (ok_, via) = it.typeText(
                    a.getString("text"), a.optIntOrNull("ref"),
                    a.optStr("target_text"), a.optStr("target_desc"), a.optStr("resource_id"),
                    submit,
                )
                // Some fields don't expose the IME action — the shell
                // daemon (if up) can still press enter.
                if (submit && !ok_ && via.startsWith("set_text;")) {
                    val pressed = if (vs != null) {
                        runCatching { vs.key(android.view.KeyEvent.KEYCODE_ENTER); true }
                            .getOrDefault(false)
                    } else shellEnter()
                    if (pressed) {
                        ok_ = true
                        via = "set_text+keyevent_enter"
                    }
                }
                result(ok_, via)
            }
            "key" -> acting(a) {
                val key = a.getString("name")
                var ok_ = if (vs != null) {
                    VD_KEYS[key.lowercase()]
                        ?.let { runCatching { vs.key(it); true }.getOrDefault(false) }
                        ?: it.globalKey(key) // system-level keys stay global
                } else it.globalKey(key)
                if (!ok_ && key.equals("enter", ignoreCase = true)) {
                    ok_ = if (vs != null) {
                        runCatching { vs.key(android.view.KeyEvent.KEYCODE_ENTER); true }
                            .getOrDefault(false)
                    } else shellEnter()
                }
                result(ok_, if (ok_) key else "unsupported or failed: $key")
            }
            "wait_for" -> a11y { svc ->
                val ok_ = svc.waitFor(
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optStr("package"), a.optLong("timeout_ms", 10_000),
                )
                result(ok_, if (ok_) "found" else "timed out").also { r ->
                    if (ok_) attachScreen(svc, a, r, settleMaxMs = 1500)
                }
            }
            "screenshot" -> if (vs != null) {
                vdScreenshot(vs)
            } else a11y { svc ->
                val shot = svc.markedScreenshot()
                if (shot == null) {
                    JSONObject().put("ok", false)
                        .put("error", "screenshot failed (secure window or unsupported)")
                } else {
                    ok().put("mime", "image/jpeg")
                        .put("image_b64", Base64.encodeToString(shot.first, Base64.NO_WRAP))
                        .put("screen", shot.second.render())
                }
            }
            "launch_intent" -> {
                val r = if (vs != null) {
                    // am start accepts intent: URIs and honors --display.
                    val intentUri = intentFor(a.getString("uri").trim())
                        .toUri(Intent.URI_INTENT_SCHEME)
                    shellExec("am start --display ${vscreen.displayId} ${sq(intentUri)}")
                } else runCatching {
                    context.startActivity(intentFor(a.getString("uri").trim()))
                    ok()
                }.getOrElse { err(it) }
                if (r.optBoolean("ok")) withScreen(a, r) else r
            }
            "act", "skill", "run_skill" ->
                err(UnsupportedOperationException("$name runs inside the agent loop"))
            "save_skill" -> skills.proposeFromAgent(a, traceSnapshot())
            else -> err(UnsupportedOperationException("unknown tool: $name"))
        }
    }

    private suspend fun a11y(block: suspend (A11yService) -> JSONObject): JSONObject {
        // instance can lag the settings grant — wait briefly before failing.
        val svc = A11yService.instance ?: A11yService.awaitInstance(2_500)
            ?: return JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled")
        // Everything used is binder-based (node ops, dispatchGesture,
        // performGlobalAction) or takes an explicit executor (takeScreenshot)
        // — safe off-main, and it keeps tool calls off the composer's thread
        // while the UI is busy recomposing.
        return withContext(Dispatchers.Default) { block(svc) }
    }

    /**
     * An a11y action that changes the screen: run it, then — unless the
     * agent asked otherwise (`observe:false` inside `act`) — wait for the UI
     * to settle and attach the resulting screen. Saves the model a separate
     * `screen` round trip after every action; the core dedups it when
     * nothing changed.
     */
    private suspend fun acting(
        a: JSONObject,
        block: suspend (A11yService) -> JSONObject,
    ): JSONObject = a11y { svc -> block(svc).also { attachScreen(svc, a, it) } }

    private suspend fun attachScreen(
        svc: A11yService,
        a: JSONObject,
        r: JSONObject,
        settleMaxMs: Long = 2000,
        settleMinMs: Long = 150,
    ) {
        if (!a.optBoolean("observe", true)) return
        runCatching {
            svc.settle(minMs = settleMinMs, maxMs = settleMaxMs)
            r.put("screen", svc.screenText())
        }
    }

    /** Screen after a non-a11y action (app launch, intent) when the a11y
     *  service is around. App starts take longer: wait for [waitPackage]
     *  to come to the front first. */
    private suspend fun withScreen(
        a: JSONObject,
        r: JSONObject,
        waitPackage: String? = null,
    ): JSONObject {
        val svc = A11yService.instance ?: return r
        if (!a.optBoolean("observe", true)) return r
        return withContext(Dispatchers.Default) {
            if (waitPackage != null) svc.waitFor(null, null, null, waitPackage, 4_000)
            attachScreen(svc, a, r, settleMaxMs = 3_000, settleMinMs = if (waitPackage != null) 250 else 700)
            r
        }
    }

    private fun result(ok: Boolean, via: String): JSONObject =
        if (ok) ok().put("via", via) else JSONObject().put("ok", false).put("error", via)

    /** KEYCODE_ENTER through the daemon, only if it is already connected
     *  (no respawn detour for a keypress). */
    private suspend fun shellEnter(): Boolean {
        val cli = daemon.client?.takeIf { it.isAlive } ?: return false
        return runCatching { cli.exec("input keyevent 66", 5_000).exit == 0 }.getOrDefault(false)
    }

    private data class App(val label: String, val pkg: String, val system: Boolean)

    private fun launchableApps(): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .map {
                App(
                    label = it.loadLabel(pm).toString(),
                    pkg = it.activityInfo.packageName,
                    system = (it.activityInfo.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
    }

    /** App name → package: exact label, then a unique substring of label or
     *  package. Otherwise null plus up to 8 candidates to choose from. */
    private fun resolveApp(name: String): Pair<String?, List<String>> {
        val q = name.trim().lowercase()
        val apps = launchableApps()
        val exact = apps.filter { it.label.lowercase() == q || it.pkg.lowercase() == q }
        if (exact.size == 1) return exact[0].pkg to emptyList()
        val partial = exact.ifEmpty {
            apps.filter { q in it.label.lowercase() || q in it.pkg.lowercase() }
        }
        if (partial.size == 1) return partial[0].pkg to emptyList()
        return null to partial.take(8).map { "${it.label} (${it.pkg})" }
    }

    /**
     * Intent for `launch_intent`: `intent:`/`android-app:` URIs, bare
     * actions (android.settings.WIFI_SETTINGS), or ACTION_VIEW on a URI.
     * The URI comes from the model (and so possibly from screen content):
     * strip URI-grant flags, selector and clip data so it can't hand our
     * FileProvider's files to another app.
     */
    private fun intentFor(uri: String): Intent {
        val i = when {
            uri.startsWith("intent:") || uri.startsWith("android-app:") ->
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME or Intent.URI_ANDROID_APP_SCHEME)
            ACTION_RE.matches(uri) -> Intent(uri)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }
        i.selector = null
        i.clipData = null
        i.flags = i.flags and GRANT_FLAGS.inv()
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun settingsNs(a: JSONObject): String =
        a.getString("namespace").trim().lowercase().also {
            require(it in SETTINGS_NAMESPACES) { "namespace must be system, secure or global" }
        }

    private suspend fun shellExec(cmd: String, timeout: Long = 30_000): JSONObject {
        val r: ExecResult = daemon.requireClient().exec(cmd, timeout)
        return ok()
            .put("exit", r.exit)
            .put("stdout", clipMiddle(r.stdout, MAX_STDOUT))
            .put("stderr", clipMiddle(r.stderr, MAX_STDERR))
            .put("duration_ms", r.durationMs)
            .put("timed_out", r.timedOut)
    }

    private suspend fun screencapResult(): JSONObject = runCatching {
        val bytes = daemon.requireClient().screencap()
        ok().put("mime", "image/png")
            .put("data_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }.getOrElse { err(it) }

    /** Vision screenshot off the virtual display: host PNG + a11y ref
     *  marks drawn on top (same set-of-marks shape the model knows). */
    private suspend fun vdScreenshot(vs: VScreenClient): JSONObject {
        val svc = A11yService.instance ?: A11yService.awaitInstance(2_500)
            ?: return JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled")
        return withContext(Dispatchers.Default) {
            runCatching {
                val png = vs.screenshot()
                    ?: throw IllegalStateException("no frame rendered yet")
                val screen = svc.snapshot()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                    ?: throw IllegalStateException("frame decode failed")
                val jpeg = svc.markBitmap(bmp, screen)
                ok().put("mime", "image/jpeg")
                    .put("image_b64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("screen", screen.render())
            }.getOrElse { err(it) }
        }
    }

    private fun keyCodeOf(name: String): Int {
        name.toIntOrNull()?.let { return it }
        return runCatching { android.view.KeyEvent.keyCodeFromString(name) }
            .getOrDefault(android.view.KeyEvent.KEYCODE_UNKNOWN)
    }

    private companion object {
        const val MAX_STDOUT = 6000
        const val MAX_STDERR = 1500
        const val MAX_APPS_LISTED = 120
        /** Upper bound for one tool call; `wait_for` itself caps lower. */
        const val CALL_TIMEOUT_MS = 180_000L

        /** Keep the start and the end: errors and summaries usually sit at
         *  the bottom of command output. */
        fun clipMiddle(s: String, max: Int): String {
            if (s.length <= max) return s
            val head = max * 2 / 3
            val tail = max - head
            return s.take(head) + "\n…[${s.length - max} chars omitted]…\n" + s.takeLast(tail)
        }

        val SETTINGS_NAMESPACES = setOf("system", "secure", "global")
        /** Display-semantic tools: when the virtual screen is on these must
         *  all run on it, never on the physical panel. */
        val VD_SCOPED = setOf(
            "screen", "ui_tree", "ui_find", "wait_for",
            "tap", "long_press", "swipe", "scroll", "pinch", "type_text", "key",
            "screenshot", "screen_capture", "screen_info",
            "input_tap", "input_swipe", "input_text", "input_key",
            "app_launch", "launch_intent",
        )
        /** Navigation keys the virtual display accepts by keycode; the
         *  rest (notifications, power dialog…) are system-level globals. */
        val VD_KEYS = mapOf(
            "back" to android.view.KeyEvent.KEYCODE_BACK,
            "home" to android.view.KeyEvent.KEYCODE_HOME,
            "recents" to android.view.KeyEvent.KEYCODE_APP_SWITCH,
            "enter" to android.view.KeyEvent.KEYCODE_ENTER,
        )
        val ACTION_RE = Regex("""^[A-Za-z_][\w]*(\.[A-Za-z_][\w]*)*\.[A-Z][A-Z0-9_]*$""")
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        /** POSIX single-quote for `sh -c`: model-supplied args stay one word. */
        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err(e: Throwable) =
        JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)

    private fun JSONObject.optStr(k: String): String? =
        if (has(k) && !isNull(k)) getString(k).ifEmpty { null } else null

    private fun JSONObject.optDoubleOrNull(k: String): Double? =
        if (has(k) && !isNull(k)) getDouble(k) else null

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (has(k) && !isNull(k)) getDouble(k).toInt() else null

    /** Strip large/binary fields before persistence. */
    private fun redact(result: JSONObject): JSONObject {
        val r = JSONObject(result.toString())
        for (k in listOf("data_b64", "image_b64")) {
            if (r.has(k)) r.put(k, "<${r.getString(k).length} bytes b64>")
        }
        if (r.optString("screen").length > 600) {
            r.put("screen", r.getString("screen").take(600) + "…")
        }
        if (r.optString("stdout").length > 1000) {
            r.put("stdout", r.getString("stdout").take(1000) + "…")
        }
        return r
    }

    private suspend fun record(name: String, args: String, result: String, ok: Boolean, start: Long) {
        if (conversationId <= 0) return
        db.toolCalls().insert(
            ToolCallRecord(
                conversationId = conversationId,
                name = name,
                argsJson = args.take(2000),
                resultJson = result.take(2000),
                risk = riskOf(name),
                durationMs = System.currentTimeMillis() - start,
                ok = ok,
            )
        )
    }

    private suspend fun audit(kind: String, detail: String, ok: Boolean) {
        db.audit().insert(AuditEntry(kind = kind, detail = detail, ok = ok))
    }
}
