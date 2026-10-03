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

    /** Called by the Rust agent loop (on its own thread). Must not suspend. */
    fun executeBlocking(name: String, argsJson: String): String =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { execute(name, argsJson) }

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

        val result = runCatching { dispatch(name, JSONObject(argsJson.ifBlank { "{}" })) }
            .getOrElse { e ->
                JSONObject().put("ok", false)
                    .put("error", e.message ?: e.javaClass.simpleName)
            }

        val ok = result.optBoolean("ok", true)
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
        _pending.value = PendingConfirm(name, argsJson, riskOf(name), deferred)
        return deferred.await()
    }

    private suspend fun dispatch(name: String, a: JSONObject): JSONObject {
        return when (name) {
            // -------- meta handled here (task_done/ask_user handled in Rust) --
            "notify" -> {
                Notifier.post(context, a.getString("title"), a.getString("text"))
                ok()
            }
            // -------- shell backend ----------
            "shell_exec" -> shellExec(
                a.getString("cmd"),
                a.optLong("timeout_ms", 30_000).coerceIn(1_000, 120_000),
            )
            "app_list" -> runCatching {
                // Direct PackageManager read — the `pm` CLI costs a JVM spawn.
                val pm = context.packageManager
                val thirdOnly = a.optBoolean("third_party_only")
                val arr = JSONArray()
                pm.getInstalledApplications(0)
                    .filter {
                        !thirdOnly ||
                            (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                    }
                    .sortedBy { it.packageName }
                    .forEach {
                        arr.put(
                            JSONObject()
                                .put("package", it.packageName)
                                .put("label", pm.getApplicationLabel(it).toString()),
                        )
                    }
                ok().put("count", arr.length()).put("apps", arr)
            }.getOrElse { err(it) }
            "app_launch" -> {
                // Accept "pkg", "pkg/.Act" in package, or a separate component
                // (".Act", "full.Cls" or "pkg/.Act").
                var pkg = a.getString("package").trim()
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
                runCatching {
                    val intent = if (comp.isNotEmpty()) {
                        Intent().setComponent(android.content.ComponentName(compPkg, cls))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    } else {
                        context.packageManager.getLaunchIntentForPackage(pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?: throw IllegalStateException("no launcher activity for $pkg")
                    }
                    context.startActivity(intent)
                    ok()
                }.getOrElse {
                    // Shell uid can still reach non-exported components and
                    // leanback-only launchers that PackageManager can't see.
                    if (comp.isNotEmpty()) shellExec("am start -n ${sq("$compPkg/$cls")}")
                    else shellExec("monkey -p ${sq(pkg)} -c android.intent.category.LAUNCHER 1")
                }
            }
            "app_stop" -> shellExec("am force-stop ${sq(a.getString("package"))}")
            "app_install" -> shellExec(
                "pm install ${if (a.optBoolean("replace", true)) "-r " else ""}${sq(a.getString("path"))}"
            )
            "app_uninstall" -> shellExec("pm uninstall ${sq(a.getString("package"))}")
            "app_clear_data" -> shellExec("pm clear ${sq(a.getString("package"))}")
            "screen_capture" -> screencapResult()
            "screen_info" -> runCatching {
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
            "input_tap" -> shellExec("input tap ${a.getInt("x")} ${a.getInt("y")}")
            "input_swipe" -> shellExec(
                "input swipe ${a.getInt("x1")} ${a.getInt("y1")} ${a.getInt("x2")} ${a.getInt("y2")} ${a.optInt("duration_ms", 300)}"
            )
            "input_text" -> shellExec("input text ${sq(a.getString("text").replace(" ", "%s"))}")
            "input_key" -> shellExec("input keyevent ${sq(a.getString("key"))}")
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
            "tap" -> a11y {
                val (ok_, via) = it.tapOrNode(
                    a.optDoubleOrNull("x")?.toFloat(), a.optDoubleOrNull("y")?.toFloat(),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                )
                JSONObject().put("ok", ok_).put("via", via)
            }
            "long_press" -> a11y {
                val ok_ = it.longPress(
                    a.optDoubleOrNull("x")?.toFloat(), a.optDoubleOrNull("y")?.toFloat(),
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optLong("duration_ms", 800),
                )
                JSONObject().put("ok", ok_)
            }
            "swipe" -> a11y {
                val ok_ = it.swipe(
                    a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(),
                    a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(),
                    a.optLong("duration_ms", 300),
                )
                JSONObject().put("ok", ok_)
            }
            "pinch" -> a11y {
                val ok_ = it.pinch(
                    a.getDouble("x").toFloat(), a.getDouble("y").toFloat(),
                    a.getString("direction") == "in",
                    a.optInt("percent", 50),
                )
                JSONObject().put("ok", ok_)
            }
            "scroll" -> a11y {
                val (ok_, via) = it.scroll(
                    a.getString("direction"), a.optStr("text"), a.optInt("times", 1),
                )
                JSONObject().put("ok", ok_).put("via", via)
            }
            "type_text" -> a11y {
                val (ok_, via) = it.typeText(
                    a.getString("text"),
                    a.optStr("target_text"), a.optStr("target_desc"), a.optStr("resource_id"),
                )
                JSONObject().put("ok", ok_).put("via", via)
            }
            "key" -> a11y {
                val ok_ = it.globalKey(a.getString("name"))
                JSONObject().put("ok", ok_)
            }
            "wait_for" -> a11y {
                val ok_ = it.waitFor(
                    a.optStr("text"), a.optStr("desc"), a.optStr("resource_id"),
                    a.optStr("package"), a.optLong("timeout_ms", 10_000),
                )
                JSONObject().put("ok", ok_)
            }
            "screenshot" -> a11y { svc ->
                val bytes = svc.screenshot()
                if (bytes == null) {
                    JSONObject().put("ok", false).put("error", "screenshot failed; try screen_capture")
                } else {
                    JSONObject().put("ok", true)
                        .put("mime", "image/jpeg")
                        .put("data_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            }
            "launch_intent" -> runCatching {
                context.startActivity(intentFor(a.getString("uri").trim()))
                ok()
            }.getOrElse { err(it) }
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
            .put("stdout", r.stdout.take(8000))
            .put("stderr", r.stderr.take(2000))
            .put("duration_ms", r.durationMs)
            .put("timed_out", r.timedOut)
    }

    private suspend fun screencapResult(): JSONObject = runCatching {
        val bytes = daemon.requireClient().screencap()
        ok().put("mime", "image/png")
            .put("data_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }.getOrElse { err(it) }

    private companion object {
        val SETTINGS_NAMESPACES = setOf("system", "secure", "global")
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

    /** Strip large/binary fields before persistence. */
    private fun redact(result: JSONObject): JSONObject {
        val r = JSONObject(result.toString())
        if (r.has("data_b64")) {
            r.put("data_b64", "<${r.getString("data_b64").length} bytes b64>")
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
