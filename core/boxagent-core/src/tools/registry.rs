//! The atomic tool catalog — the single source of truth for both the LLM
//! `tools[]` payload and the in-app Tools browser.
//!
//! Risk levels drive the Kotlin-side confirmation policy:
//!   readonly    — never confirmed
//!   moderate    — confirmed under "strict" policy
//!   destructive — confirmed under "balanced" and "strict"

use serde::Serialize;
use serde_json::{json, Value};

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Risk {
    Readonly,
    Moderate,
    Destructive,
}

#[derive(Debug, Clone)]
pub struct Tool {
    pub name: &'static str,
    pub summary: &'static str,
    pub description: &'static str,
    pub parameters: Value,
    pub risk: Risk,
    /// "shell" (daemon), "a11y" (accessibility service), or "meta" (agent).
    pub backend: &'static str,
}

fn obj(props: Value, required: &[&str]) -> Value {
    json!({
        "type": "object",
        "properties": props,
        "required": required,
        "additionalProperties": false
    })
}

fn s(desc: &str) -> Value {
    json!({"type": "string", "description": desc})
}

fn i(desc: &str) -> Value {
    json!({"type": "integer", "description": desc})
}

fn b(desc: &str) -> Value {
    json!({"type": "boolean", "description": desc})
}

fn t(
    name: &'static str,
    summary: &'static str,
    description: &'static str,
    parameters: Value,
    risk: Risk,
    backend: &'static str,
) -> Tool {
    Tool {
        name,
        summary,
        description,
        parameters,
        risk,
        backend,
    }
}

pub fn all() -> Vec<Tool> {
    use Risk::*;
    vec![
        // ---------- shell backend ----------
        t(
            "shell_exec", "Run a shell command",
            "Execute an arbitrary command via `sh -c` in the shell-uid context. \
             Prefer the higher-level tools when one fits. Returns stdout, stderr and exit code.",
            obj(json!({
                "cmd": s("Command line to execute"),
                "timeout_ms": i("Kill after this many ms (default 30000, max 120000)")
            }), &["cmd"]),
            Destructive, "shell",
        ),
        t(
            "app_list", "List installed apps",
            "List packages visible to shell. Optionally filter to third-party only.",
            obj(json!({
                "third_party_only": b("Only user-installed apps (default false)")
            }), &[]),
            Readonly, "shell",
        ),
        t(
            "app_launch", "Launch an app",
            "Start an app's main activity, or a specific component as package/.Activity.",
            obj(json!({
                "package": s("Package name, or component package/.Activity"),
                "component": s("Explicit component (overrides package)")
            }), &["package"]),
            Moderate, "shell",
        ),
        t(
            "app_stop", "Force stop an app",
            "Force-stop a package (`am force-stop`).",
            obj(json!({"package": s("Package name")}), &["package"]),
            Moderate, "shell",
        ),
        t(
            "app_install", "Install an APK",
            "Install an APK already present on the device filesystem.",
            obj(json!({
                "path": s("Absolute path of the APK (e.g. /data/local/tmp/x.apk)"),
                "replace": b("pm install -r (default true)")
            }), &["path"]),
            Destructive, "shell",
        ),
        t(
            "app_uninstall", "Uninstall an app",
            "Remove a package (`pm uninstall`).",
            obj(json!({"package": s("Package name")}), &["package"]),
            Destructive, "shell",
        ),
        t(
            "app_clear_data", "Clear app data",
            "Wipe an app's data (`pm clear`). Destructive and irreversible.",
            obj(json!({"package": s("Package name")}), &["package"]),
            Destructive, "shell",
        ),
        t(
            "screen_capture", "Capture screen",
            "Take a screenshot of the primary display (`screencap`). Returns base64 image.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "screen_info", "Screen geometry",
            "Display resolution, density and orientation.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "device_info", "Device information",
            "Model, Android version, build, battery and network summary.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "settings_get", "Read a setting",
            "Read one entry from a settings namespace.",
            obj(json!({
                "namespace": s("system | secure | global"),
                "key": s("Setting key")
            }), &["namespace", "key"]),
            Readonly, "shell",
        ),
        t(
            "settings_put", "Write a setting",
            "Write one entry into a settings namespace.",
            obj(json!({
                "namespace": s("system | secure | global"),
                "key": s("Setting key"),
                "value": s("New value")
            }), &["namespace", "key", "value"]),
            Destructive, "shell",
        ),
        t(
            "file_read", "Read a file",
            "Read a file's bytes (shell permissions). Returns base64 + size.",
            obj(json!({"path": s("Absolute path")}), &["path"]),
            Readonly, "shell",
        ),
        t(
            "file_write", "Write a file",
            "Write bytes to a path (shell permissions).",
            obj(json!({
                "path": s("Absolute path"),
                "data_b64": s("File contents, base64")
            }), &["path", "data_b64"]),
            Destructive, "shell",
        ),
        t(
            "file_list", "List a directory",
            "List entries of a directory with size/mode/mtime.",
            obj(json!({"path": s("Directory path")}), &["path"]),
            Readonly, "shell",
        ),
        t(
            "process_list", "List processes",
            "Running processes (`ps -A`), parsed.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "clipboard_get", "Read clipboard",
            "Current clipboard contents, if text.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "clipboard_set", "Set clipboard",
            "Set clipboard text.",
            obj(json!({"text": s("Text to place on clipboard")}), &["text"]),
            Moderate, "shell",
        ),
        t(
            "input_tap", "Tap (input CLI)",
            "Tap via the `input` command. Prefer `tap` (accessibility) — this is a fallback.",
            obj(json!({
                "x": i("X coordinate"), "y": i("Y coordinate")
            }), &["x", "y"]),
            Moderate, "shell",
        ),
        t(
            "input_swipe", "Swipe (input CLI)",
            "Swipe via the `input` command with duration.",
            obj(json!({
                "x1": i("Start X"), "y1": i("Start Y"),
                "x2": i("End X"), "y2": i("End Y"),
                "duration_ms": i("Gesture duration (default 300)")
            }), &["x1", "y1", "x2", "y2"]),
            Moderate, "shell",
        ),
        t(
            "input_text", "Type text (input CLI)",
            "Insert text via `input text`. ASCII-safe path; prefer `type_text`.",
            obj(json!({"text": s("Text to input")}), &["text"]),
            Moderate, "shell",
        ),
        t(
            "input_key", "Key event (input CLI)",
            "Send a KEYEVENT code via `input keyevent` (e.g. KEYCODE_BACK, 4).",
            obj(json!({"key": s("Key name or numeric code")}), &["key"]),
            Moderate, "shell",
        ),
        // ---------- a11y backend ----------
        t(
            "ui_tree", "Dump UI hierarchy",
            "Accessibility node tree of the current window: each node has id, \
             class, text, content-desc, bounds and capability flags. Use this \
             first to understand what is on screen.",
            obj(json!({
                "max_depth": i("Depth cap (default 30)"),
                "package": s("Limit to this package's window if present")
            }), &[]),
            Readonly, "a11y",
        ),
        t(
            "ui_find", "Find UI nodes",
            "Find nodes by text / content-description / resource-id (regex allowed). \
             Returns matching nodes with bounds.",
            obj(json!({
                "text": s("Match node text (regex ok)"),
                "desc": s("Match content-description (regex ok)"),
                "resource_id": s("Match view id resource name (regex ok)"),
                "clickable_only": b("Only clickable nodes (default false)")
            }), &[]),
            Readonly, "a11y",
        ),
        t(
            "tap", "Tap",
            "Tap at (x,y) screen coordinates, or at the center of a node found by selector.",
            obj(json!({
                "x": i("X coordinate"),
                "y": i("Y coordinate"),
                "text": s("Tap center of node matching text"),
                "desc": s("Tap center of node matching content-desc"),
                "resource_id": s("Tap center of node matching view id")
            }), &[]),
            Moderate, "a11y",
        ),
        t(
            "long_press", "Long press",
            "Long-press at coordinates or node center.",
            obj(json!({
                "x": i("X"), "y": i("Y"),
                "text": s("Node text selector"),
                "desc": s("Node content-desc selector"),
                "duration_ms": i("Hold duration (default 800)")
            }), &[]),
            Moderate, "a11y",
        ),
        t(
            "swipe", "Swipe",
            "Swipe between two points over duration_ms.",
            obj(json!({
                "x1": i("Start X"), "y1": i("Start Y"),
                "x2": i("End X"), "y2": i("End Y"),
                "duration_ms": i("Duration (default 300)")
            }), &["x1", "y1", "x2", "y2"]),
            Moderate, "a11y",
        ),
        t(
            "pinch", "Pinch",
            "Pinch in (zoom out) or out (zoom in) centered on a point.",
            obj(json!({
                "x": i("Center X"), "y": i("Center Y"),
                "direction": s("\"in\" or \"out\""),
                "percent": i("Span change percent 1-100 (default 50)")
            }), &["x", "y", "direction"]),
            Moderate, "a11y",
        ),
        t(
            "scroll", "Scroll",
            "Scroll a direction on the screen or on a scrollable node.",
            obj(json!({
                "direction": s("up | down | left | right"),
                "text": s("Scrollable node's text selector"),
                "times": i("Repeat count (default 1)")
            }), &["direction"]),
            Moderate, "a11y",
        ),
        t(
            "type_text", "Type into field",
            "Set text on an editable node (focused, or found by selector). \
             Clears existing content first.",
            obj(json!({
                "text": s("Text to enter"),
                "target_text": s("Editable node's text selector"),
                "target_desc": s("Editable node's content-desc selector"),
                "resource_id": s("Editable node's view id")
            }), &["text"]),
            Moderate, "a11y",
        ),
        t(
            "key", "System key",
            "Global action: back | home | recents | notifications | quick_settings | power_dialog | lock.",
            obj(json!({"name": s("Action name")}), &["name"]),
            Moderate, "a11y",
        ),
        t(
            "wait_for", "Wait for UI state",
            "Wait until a node/activity appears (or timeout). Use after taps that \
             trigger navigation instead of sleeping.",
            obj(json!({
                "text": s("Wait for node text"),
                "desc": s("Wait for node content-desc"),
                "resource_id": s("Wait for view id"),
                "package": s("Wait for foreground package"),
                "timeout_ms": i("Wait budget (default 10000)")
            }), &[]),
            Readonly, "a11y",
        ),
        t(
            "screenshot", "Screenshot (a11y)",
            "Screenshot via AccessibilityService (better compression control). \
             Returns base64 image.",
            obj(json!({}), &[]),
            Readonly, "a11y",
        ),
        t(
            "launch_intent", "Open intent / deep link",
            "Start an activity by intent URI (deep link, settings screen).",
            obj(json!({"uri": s("Intent URI, e.g. android-app:// or https:// or app scheme")}), &["uri"]),
            Moderate, "a11y",
        ),
        // ---------- meta ----------
        t(
            "task_done", "Finish the task",
            "Call when the user's request is complete. Include a concise summary \
             of what was accomplished.",
            obj(json!({
                "summary": s("Completion summary for the user")
            }), &["summary"]),
            Readonly, "meta",
        ),
        t(
            "ask_user", "Ask the user",
            "Pause and ask the user a clarifying question when blocked or when \
             a choice materially changes the outcome.",
            obj(json!({"question": s("Question for the user")}), &["question"]),
            Readonly, "meta",
        ),
        t(
            "notify", "Post a notification",
            "Post a user-visible notification. Useful for long background tasks.",
            obj(json!({
                "title": s("Notification title"),
                "text": s("Notification body")
            }), &["title", "text"]),
            Readonly, "meta",
        ),
    ]
}

impl Tool {
    /// OpenAI `tools[]` entry.
    pub fn openai_schema(&self) -> Value {
        json!({
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            }
        })
    }

    /// In-app catalog entry.
    pub fn catalog_json(&self) -> Value {
        json!({
            "name": self.name,
            "summary": self.summary,
            "description": self.description,
            "risk": self.risk,
            "backend": self.backend,
            "parameters": self.parameters,
        })
    }
}

pub fn openai_tools() -> Value {
    json!(all().iter().map(|t| t.openai_schema()).collect::<Vec<_>>())
}

pub fn catalog() -> Value {
    json!(all().iter().map(|t| t.catalog_json()).collect::<Vec<_>>())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_is_valid() {
        let tools = all();
        assert!(tools.len() >= 30);
        let names: Vec<_> = tools.iter().map(|t| t.name).collect();
        let mut dedup = names.clone();
        dedup.sort();
        dedup.dedup();
        assert_eq!(names.len(), dedup.len(), "duplicate tool names");
        for t in &tools {
            assert_eq!(t.parameters["type"], "object");
            assert!(t.description.len() > 20);
        }
        let v = openai_tools();
        assert!(v.is_array());
    }
}
