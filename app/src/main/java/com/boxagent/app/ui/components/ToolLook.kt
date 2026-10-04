package com.boxagent.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardAlt
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONObject

/** How a tool call looks in the activity timeline: glyph, label, one-line detail. */
object ToolLook {
    fun icon(tool: String): ImageVector = when (tool) {
        "screen", "ui_tree", "ui_find", "screen_info" -> Icons.AutoMirrored.Rounded.ViewQuilt
        "tap", "long_press", "input_tap" -> Icons.Rounded.TouchApp
        "swipe", "scroll", "pinch", "input_swipe" -> Icons.Rounded.SwapVert
        "type_text", "input_text", "key", "input_key" -> Icons.Rounded.KeyboardAlt
        "screenshot", "screen_capture" -> Icons.Rounded.Image
        "app_launch", "launch_intent" -> Icons.AutoMirrored.Rounded.Launch
        "app_list", "app_stop", "app_install", "app_uninstall", "app_clear_data" -> Icons.Rounded.Apps
        "shell_exec", "process_list" -> Icons.Rounded.Terminal
        "file_read", "file_write", "file_list" -> Icons.Rounded.Folder
        "settings_get", "settings_put" -> Icons.Rounded.Settings
        "clipboard_get", "clipboard_set" -> Icons.Rounded.ContentPaste
        "wait_for" -> Icons.Rounded.HourglassEmpty
        "ask_user" -> Icons.Rounded.QuestionAnswer
        "notify" -> Icons.Rounded.Notifications
        "act" -> Icons.Rounded.Bolt
        "skill" -> Icons.AutoMirrored.Rounded.MenuBook
        "run_skill", "save_skill" -> Icons.Rounded.AutoAwesome
        "device_info" -> Icons.Rounded.Info
        else -> Icons.Rounded.PanTool
    }

    /** `type_text` → "Type text". */
    fun label(tool: String): String =
        tool.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** The argument a person cares about: `ref 4`, `"Wi-Fi"`, `down`… */
    fun detail(tool: String, argsJson: String): String {
        val a = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ""
        fun s(k: String) = a.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        val text = when (tool) {
            "type_text", "input_text" -> s("text")?.let { "\"$it\"" + if (a.optBoolean("submit")) " ⏎" else "" }
            "shell_exec" -> s("cmd")
            "skill", "run_skill", "save_skill" -> s("name")
            "act" -> a.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).joinToString(" → ") { arr.optJSONObject(it)?.optString("tool").orEmpty() }
            }
            else -> null
        } ?: when {
            a.has("ref") -> "[${a.optInt("ref")}]"
            s("text") != null -> "\"${s("text")}\""
            s("name") != null -> s("name")
            s("package") != null -> s("package")
            s("uri") != null -> s("uri")
            s("direction") != null -> s("direction")
            s("path") != null -> s("path")
            s("question") != null -> s("question")
            a.has("x") && a.has("y") -> "${a.optInt("x")}, ${a.optInt("y")}"
            else -> null
        }
        return text.orEmpty().replace('\n', ' ').take(60)
    }

    /** Result → (ok, short error). Unknown shapes count as ok. */
    fun outcome(result: String?): Pair<Boolean, String?> {
        if (result == null) return true to null
        val json = result.substringBefore("\n[screen]")
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return true to null
        val ok = o.optBoolean("ok", true)
        return ok to (if (ok) null else o.optString("error").takeIf { it.isNotEmpty() }?.take(80))
    }
}
