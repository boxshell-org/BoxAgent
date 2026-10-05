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
import com.boxagent.app.vscreen.VScreenError
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
import kotlin.math.min

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
                    vdGuardEmpty()?.let { return it }
                    val shot = vs.screenshot("png", 100, 0)
                        ?: throw IllegalStateException("no frame rendered yet")
                    ok().put("mime", shot.mime)
                        .put("data_b64", Base64.encodeToString(shot.bytes, Base64.NO_WRAP))
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
                    .put("own_focus", st.ownFocus)
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
                    val offset = a.optInt("offset", 0).coerceIn(0, asText.length)
                    val maxChars = a.optInt("max_chars", 4000).coerceIn(1, 20_000)
                    val slice = asText.drop(offset).take(maxChars)
                    ok().put("size", bytes.size)
                        .put("offset", offset)
                        .put("text", slice)
                        .put("truncated", offset + slice.length < asText.length)
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
            "notifications" -> notificationsResult()
            "device_control" -> {
                val act = a.getString("action").lowercase().trim()
                val cmd = deviceCmd(a) ?: return err(IllegalArgumentException(
                    "unknown action \"$act\" — use brightness, brightness_auto, " +
                        "or one of: ${DEVICE_ACTIONS.keys.joinToString()}"))
                shellExec(cmd).put("action", act)
            }
            "app_permission" -> {
                val pkg = a.getString("package")
                when (a.optString("action", "list").lowercase()) {
                    "grant" -> shellExec("pm grant ${sq(pkg)} ${sq(a.getString("permission"))}")
                    "revoke" -> shellExec("pm revoke ${sq(pkg)} ${sq(a.getString("permission"))}")
                    "list" -> runCatching {
                        val r = daemon.requireClient()
                            .exec("dumpsys package ${sq(pkg)}", 20_000)
                        val (granted, denied) = parseRuntimePerms(r.stdout)
                        ok().put("package", pkg)
                            .put("granted", JSONArray(granted))
                            .put("denied", JSONArray(denied))
                    }.getOrElse { err(it) }
                    else -> err(IllegalArgumentException("action must be list, grant or revoke"))
                }
            }
            "http_request" -> httpRequest(a)
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
            "clipboard_set" -> clipboardSet(a.getString("text"))
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
                val text = a.getString("text")
                runCatching { vs.text(text); ok() }.getOrElse { e ->
                    // Characters without key mappings (CJK, emoji): set the
                    // focused field's text through accessibility instead.
                    if (e is VScreenError && A11yService.instance?.appendToFocused(text) == true) {
                        ok().put("via", "set_text")
                    } else err(e)
                }
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
            "scroll_until" -> acting(a) {
                val (found, via) = it.scrollUntil(
                    a.getString("direction"),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optIntOrNull("ref"), a.optStr("container_text"),
                    a.optInt("max_times", 8),
                )
                result(found, via).put("found", found)
            }
            "double_tap" -> acting(a) {
                val (ok_, via) = it.doubleTap(
                    a.optIntOrNull("ref"),
                    a.optDoubleOrNull("x")?.toFloat(), a.optDoubleOrNull("y")?.toFloat(),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                )
                result(ok_, via)
            }
            "drag" -> acting(a) {
                val (ok_, via) = it.drag(
                    a.optIntOrNull("ref"),
                    a.optDoubleOrNull("x")?.toFloat(), a.optDoubleOrNull("y")?.toFloat(),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.getDouble("to_x").toFloat(), a.getDouble("to_y").toFloat(),
                    a.optLong("duration_ms", 500), a.optLong("hold_ms", 350),
                )
                result(ok_, via)
            }
            "copy_text" -> a11y { svc ->
                val (text, via) = svc.copyText(
                    a.optIntOrNull("ref"), a.optStr("text"), a.optStr("desc"),
                    a.optStr("resource_id"),
                )
                when {
                    via == "copied" -> result(true, "copied")
                    text != null -> clipboardSet(text)
                    else -> result(false, via)
                }
            }
            "type_text" -> acting(a) {
                val submit = a.optBoolean("submit")
                var (ok_, via) = it.typeText(
                    a.getString("text"), a.optIntOrNull("ref"),
                    a.optStr("target_text"), a.optStr("target_desc"), a.optStr("resource_id"),
                    submit, a.optBoolean("append"),
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
                    val k = key.lowercase()
                    val code = VD_KEYS[k]
                    when {
                        // HOME is a no-op on a display without system
                        // decorations — "home" is the backdrop there.
                        k == "home" -> vscreen.goHome()
                        code != null -> runCatching { vs.key(code); true }.getOrDefault(false)
                        // Shade, recents, power menu, lock: all live on the
                        // physical screen the user is using.
                        else -> return@acting result(
                            false,
                            "key \"$key\" isn't available on the virtual screen " +
                                "(it would act on the user's physical screen)",
                        )
                    }
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
                val gone = a.optBoolean("gone")
                val ok_ = svc.waitFor(
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optStr("package"), a.optLong("timeout_ms", 10_000), gone,
                )
                result(ok_, if (ok_) (if (gone) "gone" else "found") else "timed out")
                    // On timeout the screen is the useful part — the model
                    // sees what's actually there instead of re-reading.
                    .also { r -> attachScreen(svc, a, r, settleMaxMs = 1500) }
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

    /** In-app clipboard write, with a shell fallback for the background
     *  write restrictions added in Android 10. */
    private suspend fun clipboardSet(text: String): JSONObject = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("boxagent", text))
        // Android 10+ silently denies background writes — verify.
        if (cm.primaryClip?.getItemAt(0)?.text?.toString() != text) {
            throw IllegalStateException("denied")
        }
        ok()
    }.getOrElse {
        shellExec("cmd clipboard set ${sq(text)}")
    }

    /** Active status-bar notifications, parsed out of `dumpsys
     *  notification` — extras formatting differs across builds, so the
     *  parser collects generously and falls back to clipped raw output. */
    private suspend fun notificationsResult(): JSONObject = runCatching {
        val r = daemon.requireClient().exec("dumpsys notification", 15_000)
        val items = parseNotifications(r.stdout)
        ok().put("count", items.size)
            .put("notifications", JSONArray(items))
            .apply {
                if (items.isEmpty() && r.stdout.isNotBlank()) {
                    put("note", "could not parse notification records; raw excerpt attached")
                    put("raw", clipMiddle(r.stdout, 2000))
                }
            }
    }.getOrElse { err(it) }

    private fun parseNotifications(dump: String): List<JSONObject> {
        // Blocks start at "  NotificationRecord(0x…: pkg=…" and end at
        // the next record or an unindented section header.
        val blocks = Regex(
            """(?ms)^\s+NotificationRecord\(.*?(?=^\s+NotificationRecord\(|^\S|\z)""",
        ).findAll(dump).toList()
        val out = mutableListOf<JSONObject>()
        for (b in blocks.take(30)) {
            val block = b.value
            val header = block.lineSequence().first()
            val pkg = Regex("""pkg=(\S+)""").find(header)?.groupValues?.get(1)
                ?: continue
            fun extra(name: String): String? =
                Regex("""(?m)^\s*android\.$name=(.*)$""").find(block)
                    ?.groupValues?.get(1)
                    ?.replace(Regex("""\s*\(\d+\)$"""), "")
                    ?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
            val title = extra("title") ?: extra("subText") ?: continue
            val text = extra("bigText")?.takeIf {
                it.length > (extra("text")?.length ?: 0)
            } ?: extra("text")
            val whenMs = Regex("""when=(\d+)""").find(block)
                ?.groupValues?.get(1)?.toLongOrNull()
            out += JSONObject()
                .put("package", pkg)
                .put("title", title)
                .apply {
                    text?.let { put("text", it) }
                    whenMs?.let { put("post_time_ms", it) }
                }
        }
        return out
    }

    /** `dumpsys package <pkg>` → runtime permissions section:
     *  `    android.permission.X: granted=true, flags=[…]` */
    private fun parseRuntimePerms(dump: String): Pair<List<String>, List<String>> {
        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()
        var inSection = false
        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("runtime permissions:")) { inSection = true; continue }
            if (inSection) {
                // Section ends at a less-indented or blank line.
                if (trimmed.isEmpty() || line.take(4).isNotBlank()) break
                val m = Regex("""^(\S+): granted=(true|false)""").find(trimmed) ?: continue
                (if (m.groupValues[2] == "true") granted else denied) += m.groupValues[1]
            }
        }
        return granted.sorted() to denied.sorted()
    }

    /** Map a device_control action to its shell command; null = unknown. */
    private fun deviceCmd(a: JSONObject): String? {
        val act = a.getString("action").lowercase().trim()
        if (act == "brightness") {
            val v = a.optIntOrNull("value")
                ?: throw IllegalArgumentException("brightness needs value 0-255")
            return "settings put system screen_brightness_mode 0;" +
                " settings put system screen_brightness ${v.coerceIn(0, 255)}"
        }
        return DEVICE_ACTIONS[act]
    }

    /** In-app HTTP client — the phone itself calls the URL (no proxy
     *  through the model), so it works on any connection the device has. */
    private suspend fun httpRequest(a: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = java.net.URL(a.getString("url"))
                require(url.protocol == "http" || url.protocol == "https") {
                    "only http/https URLs"
                }
                val method = a.optString("method", "GET").uppercase()
                require(method in HTTP_METHODS) { "unsupported method $method" }
                val timeout = a.optLong("timeout_ms", 15_000).coerceIn(1_000, 30_000).toInt()
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeout
                    readTimeout = timeout
                    instanceFollowRedirects = a.optBoolean("follow_redirects", true)
                    a.optJSONObject("headers")?.let { h ->
                        h.keys().forEach { k -> setRequestProperty(k, h.getString(k)) }
                    }
                    val body = a.optStr("body")
                    if (body != null && method in BODY_METHODS) {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                try {
                    val status = conn.responseCode
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    val bytes = stream?.let { readCapped(it, HTTP_MAX_BODY) }
                    val text = bytes?.let {
                        runCatching { String(it, Charsets.UTF_8) }
                            .getOrNull()?.takeIf { t -> !t.contains('�') }
                    }
                    ok().put("status", status)
                        .put("final_url", conn.url.toString())
                        .put("content_type", conn.contentType ?: JSONObject.NULL)
                        .apply {
                            if (text != null) {
                                put("body", clipMiddle(text, 4000))
                                put("body_chars", text.length)
                            } else if (bytes != null) {
                                put("body_bytes", bytes.size)
                            }
                        }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { err(it) }
        }

    private fun readCapped(stream: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(min(cap, 8192))
        val buf = ByteArray(8192)
        stream.use { s ->
            while (true) {
                val want = min(buf.size, cap - out.size())
                if (want <= 0) break
                val n = s.read(buf, 0, want)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
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

    /** Vision screenshot off the virtual display: the host downscales and
     *  JPEG-encodes (a full-size PNG over the socket costs ~10x more),
     *  then a11y ref marks go on top — same set-of-marks shape the model
     *  knows. Bounds are in display pixels, hence coordWidth. */
    private suspend fun vdScreenshot(vs: VScreenClient): JSONObject {
        val svc = A11yService.instance ?: A11yService.awaitInstance(2_500)
            ?: return JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled")
        return withContext(Dispatchers.Default) {
            runCatching {
                vdGuardEmpty()?.let { return@runCatching it }
                val shot = vs.screenshot("jpeg", 85, VISION_MAX_SIDE)
                    ?: throw IllegalStateException("no frame rendered yet")
                val screen = svc.snapshot()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size)
                    ?: throw IllegalStateException("frame decode failed")
                val jpeg = svc.markBitmap(
                    bmp, screen, VISION_MAX_SIDE, coordWidth = shot.srcW.takeIf { it > 0 } ?: bmp.width,
                )
                ok().put("mime", "image/jpeg")
                    .put("image_b64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("screen", screen.render())
            }.getOrElse { err(it) }
        }
    }

    /** An empty virtual display mirrors the physical one — its pixels
     *  would be the user's real screen. Error result in that case. */
    private fun vdGuardEmpty(): JSONObject? {
        val svc = A11yService.instance ?: return null
        if (svc.hasScopeContent()) return null
        return JSONObject().put("ok", false).put(
            "error", "virtual screen is empty — open an app (or key home) first",
        )
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

        /** device_control actions that map 1:1 to a shell command
         *  (brightness takes a `value` and is built in deviceCmd). */
        val DEVICE_ACTIONS = mapOf(
            "volume_up" to "input keyevent KEYCODE_VOLUME_UP",
            "volume_down" to "input keyevent KEYCODE_VOLUME_DOWN",
            "mute" to "input keyevent KEYCODE_MUTE",
            "media_play_pause" to "input keyevent KEYCODE_MEDIA_PLAY_PAUSE",
            "media_next" to "input keyevent KEYCODE_MEDIA_NEXT",
            "media_previous" to "input keyevent KEYCODE_MEDIA_PREVIOUS",
            "media_stop" to "input keyevent KEYCODE_MEDIA_STOP",
            "wifi_on" to "svc wifi enable",
            "wifi_off" to "svc wifi disable",
            "bluetooth_on" to "svc bluetooth enable",
            "bluetooth_off" to "svc bluetooth disable",
            "brightness_auto" to "settings put system screen_brightness_mode 1",
        )
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val HTTP_MAX_BODY = 256 * 1024
        /** Display-semantic tools: when the virtual screen is on these must
         *  all run on it, never on the physical panel. */
        val VD_SCOPED = setOf(
            "screen", "ui_tree", "ui_find", "wait_for",
            "tap", "long_press", "swipe", "scroll", "scroll_until", "pinch",
            "double_tap", "drag", "type_text", "key", "copy_text",
            "screenshot", "screen_capture", "screen_info",
            "input_tap", "input_swipe", "input_text", "input_key",
            "app_launch", "launch_intent",
        )
        /** Keys injected into the virtual display by keycode; "home" is
         *  the backdrop, everything else is physical-screen-only. */
        val VD_KEYS = mapOf(
            "back" to android.view.KeyEvent.KEYCODE_BACK,
            "enter" to android.view.KeyEvent.KEYCODE_ENTER,
            "tab" to android.view.KeyEvent.KEYCODE_TAB,
            "del" to android.view.KeyEvent.KEYCODE_DEL,
            "backspace" to android.view.KeyEvent.KEYCODE_DEL,
            "space" to android.view.KeyEvent.KEYCODE_SPACE,
            "esc" to android.view.KeyEvent.KEYCODE_ESCAPE,
            "escape" to android.view.KeyEvent.KEYCODE_ESCAPE,
            "up" to android.view.KeyEvent.KEYCODE_DPAD_UP,
            "down" to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            "move_home" to android.view.KeyEvent.KEYCODE_MOVE_HOME,
            "move_end" to android.view.KeyEvent.KEYCODE_MOVE_END,
        )
        const val VISION_MAX_SIDE = 1024
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
