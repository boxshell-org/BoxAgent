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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

    private val alwaysAllowed = mutableSetOf<String>()
    @Volatile var conversationId: Long = 0

    /** UI entry point: user approves/declines the current confirmation. */
    fun resolveConfirm(approved: Boolean, always: Boolean) {
        _pending.value?.let { p ->
            if (always && approved) alwaysAllowed.add(p.tool)
            p.answer.complete(approved)
        }
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
            audit("tool_call", "$name denied=$approved args=redacted", approved)
            if (!approved) {
                record(name, argsJson, "denied", false, start)
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
        record(name, argsJson, redacted.toString(), ok, start)
        audit("tool_call", "$name ok=$ok", ok)
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
            "shell_exec" -> shellExec(a.getString("cmd"), a.optLong("timeout_ms", 30_000))
            "app_list" -> shellExec(
                "pm list packages ${if (a.optBoolean("third_party_only")) "-3" else ""} -f"
            )
            "app_launch" -> {
                val comp = a.optString("component")
                if (comp.isNotEmpty())
                    shellExec("am start -n $comp")
                else
                    shellExec("monkey -p ${a.getString("package")} -c android.intent.category.LAUNCHER 1")
            }
            "app_stop" -> shellExec("am force-stop ${a.getString("package")}")
            "app_install" -> shellExec(
                "pm install ${if (a.optBoolean("replace", true)) "-r" else ""} ${a.getString("path")}"
            )
            "app_uninstall" -> shellExec("pm uninstall ${a.getString("package")}")
            "app_clear_data" -> shellExec("pm clear ${a.getString("package")}")
            "screen_capture" -> screencapResult()
            "screen_info" -> shellExec("wm size; wm density; dumpsys window | grep -E 'mCurrentRotation|DisplayWidth|DisplayHeight' | head -6")
            "device_info" -> shellExec(
                "getprop ro.product.model; getprop ro.build.version.release; " +
                "getprop ro.build.version.sdk; dumpsys battery | head -12; " +
                "ip addr show wlan0 2>/dev/null | grep 'inet ' || true"
            )
            "settings_get" -> shellExec("settings get ${a.getString("namespace")} ${a.getString("key")}")
            "settings_put" -> shellExec("settings put ${a.getString("namespace")} ${a.getString("key")} ${a.getString("value")}")
            "file_read" -> runCatching {
                val bytes = daemon.requireClient().fileRead(a.getString("path"))
                val asText = runCatching { String(bytes, Charsets.UTF_8) }
                    .getOrNull()?.takeIf { it.isNotEmpty() && !it.contains('�') }
                if (asText != null) {
                    // Text is far cheaper (and readable) for the LLM than b64.
                    ok().put("size", bytes.size)
                        .put("text", asText.take(4000))
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
            "clipboard_get" -> shellExec("cmd clipboard get 2>/dev/null || service call clipboard 2 | head -4")
            "clipboard_set" -> shellExec("cmd clipboard set '${a.getString("text").replace("'", "'\\''")}'")
            "input_tap" -> shellExec("input tap ${a.getInt("x")} ${a.getInt("y")}")
            "input_swipe" -> shellExec(
                "input swipe ${a.getInt("x1")} ${a.getInt("y1")} ${a.getInt("x2")} ${a.getInt("y2")} ${a.optInt("duration_ms", 300)}"
            )
            "input_text" -> shellExec("input text '${a.getString("text").replace("'", "'\\''").replace(" ", "%s")}'")
            "input_key" -> shellExec("input keyevent ${a.getString("key")}")
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
                    a.optStr("text"), a.optStr("desc"),
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
                val ok_ = it.scroll(a.getString("direction"), a.optInt("times", 1))
                JSONObject().put("ok", ok_)
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
            "launch_intent" -> {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("uri")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ok()
            }
            else -> err(UnsupportedOperationException("unknown tool: $name"))
        }
    }

    private suspend fun a11y(block: suspend (A11yService) -> JSONObject): JSONObject {
        val svc = A11yService.instance
            ?: return JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled")
        return withContext(Dispatchers.Main) { block(svc) }
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
