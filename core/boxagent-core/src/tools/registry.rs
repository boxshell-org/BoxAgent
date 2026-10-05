//! The atomic tool catalog — the single source of truth for both the LLM
//! `tools[]` payload and the in-app Tools browser.
//!
//! Risk levels drive the Kotlin-side confirmation policy:
//!   readonly    — never confirmed
//!   moderate    — confirmed under "strict" policy
//!   destructive — confirmed under "balanced" and "strict"
//!
//! Not every tool is offered to the model: [`Tool::visible`] filters by what
//! the device can do right now ([`Caps`]) and by [`Expose`], so a run only
//! pays tokens for tools it can actually use.

use serde::Serialize;
use serde_json::{json, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Risk {
    Readonly,
    Moderate,
    Destructive,
}

/// Capability a tool needs at runtime.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Requires {
    /// Runs in the app process (PackageManager, intents, …).
    Nothing,
    /// Needs the shell-uid daemon.
    Shell,
    /// Needs the accessibility service.
    A11y,
}

/// When a tool is offered to the model (the Tools tab always shows all).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Expose {
    Always,
    /// Debug/playground only — superseded for the model by a cheaper tool.
    CatalogOnly,
    /// Only for vision-capable runs (returns an image).
    Vision,
    /// Shell fallbacks for UI input, offered only without accessibility.
    NoA11y,
    /// Only when the run has skills to read/run.
    Skills,
    /// Only when the user lets the agent propose skills.
    Learn,
}

/// What the current run can use; sent by the app at run start.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Caps {
    pub a11y: bool,
    pub shell: bool,
    pub vision: bool,
    /// The run carries at least one skill.
    pub skills: bool,
    /// `save_skill` (agent-proposed skills, saved as drafts) is allowed.
    pub learn: bool,
}

impl Default for Caps {
    fn default() -> Self {
        Caps {
            a11y: true,
            shell: true,
            vision: false,
            skills: false,
            learn: true,
        }
    }
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
    pub requires: Requires,
    pub expose: Expose,
}

/// Tools the agent itself handles (or that edit skills) — never a step of
/// `act` or of a skill.
const AGENT_TOOLS: &[&str] = &[
    "act",
    "task_done",
    "ask_user",
    "skill",
    "run_skill",
    "save_skill",
];

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
    let requires = match backend {
        "shell" => Requires::Shell,
        "a11y" => Requires::A11y,
        _ => Requires::Nothing,
    };
    Tool {
        name,
        summary,
        description,
        parameters,
        risk,
        backend,
        requires,
        expose: Expose::Always,
    }
}

impl Tool {
    fn needs(mut self, r: Requires) -> Self {
        self.requires = r;
        self
    }

    fn exposed(mut self, e: Expose) -> Self {
        self.expose = e;
        self
    }

    /// Whether the model is offered this tool under `caps`.
    pub fn visible(&self, caps: &Caps) -> bool {
        let available = match self.requires {
            Requires::Nothing => true,
            Requires::Shell => caps.shell,
            Requires::A11y => caps.a11y,
        };
        let exposed = match self.expose {
            Expose::Always => true,
            Expose::CatalogOnly => false,
            Expose::Vision => caps.vision,
            Expose::NoA11y => !caps.a11y,
            Expose::Skills => caps.skills,
            Expose::Learn => caps.learn,
        };
        available && exposed
    }
}

pub fn all() -> Vec<Tool> {
    use Risk::*;
    vec![
        // ---------- shell backend ----------
        t(
            "shell_exec", "Run a shell command",
            "Execute an arbitrary command via `sh -c` in the shell-uid context. \
             Prefer the higher-level tools when one fits. Returns exit code, stdout \
             and stderr (long output keeps its beginning and end).",
            obj(json!({
                "cmd": s("Command line to execute"),
                "timeout_ms": i("Kill after this many ms (default 30000, max 120000)")
            }), &["cmd"]),
            Destructive, "shell",
        ),
        t(
            "app_list", "List installed apps",
            "List launchable apps as `Label (package)`. Filter with `query` rather \
             than listing everything; to open an app just use app_launch with its name.",
            obj(json!({
                "query": s("Case-insensitive filter on label or package"),
                "third_party_only": b("Only user-installed apps (default false)")
            }), &[]),
            Readonly, "shell",
        ).needs(Requires::Nothing),
        t(
            "app_launch", "Open an app",
            "Open an app by its visible name or package (optionally a specific \
             activity). Returns the resulting screen.",
            obj(json!({
                "name": s("App name as shown in the launcher, e.g. \"Settings\""),
                "package": s("Package name (or package/.Activity)"),
                "component": s("Optional activity: .Relative, full.class.Name or package/.Activity")
            }), &[]),
            Moderate, "shell",
        ).needs(Requires::Nothing),
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
            "screen_capture", "Capture screen (PNG)",
            "Take a full-resolution PNG screenshot via `screencap`. For the playground; \
             the model uses `screen` (text) or `screenshot` (vision).",
            obj(json!({}), &[]),
            Readonly, "shell",
        ).exposed(Expose::CatalogOnly),
        t(
            "screen_info", "Screen geometry",
            "Display resolution, density and orientation (also in the `screen` header).",
            obj(json!({}), &[]),
            Readonly, "shell",
        ).needs(Requires::Nothing).exposed(Expose::CatalogOnly),
        t(
            "device_info", "Device information",
            "Model, Android version, build, battery and network summary.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ).needs(Requires::Nothing),
        t(
            "settings_get", "Read a setting",
            "Read one entry from a settings namespace.",
            obj(json!({
                "namespace": s("system | secure | global"),
                "key": s("Setting key")
            }), &["namespace", "key"]),
            Readonly, "shell",
        ).needs(Requires::Nothing),
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
            "Read a file (shell permissions). Returns text when UTF-8 decodable; \
             binary content is reported by size only. Page long files with \
             `offset`/`max_chars`.",
            obj(json!({
                "path": s("Absolute path"),
                "offset": i("Skip this many characters first (default 0)"),
                "max_chars": i("Cap on returned text (default 4000, max 20000)")
            }), &["path"]),
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
            "Running processes (`ps -A`), first 80 rows.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "notifications", "List notifications",
            "Active status-bar notifications: source app, title and text. Useful \
             for alerts, messages and one-time codes without opening apps.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ),
        t(
            "device_control", "Control the device",
            "Whole-device controls (these affect the device, not one app): volume, \
             mute, brightness, media keys, wifi and bluetooth radios.",
            obj(json!({
                "action": s("volume_up | volume_down | mute | brightness | \
                             brightness_auto | media_play_pause | media_next | \
                             media_previous | media_stop | wifi_on | wifi_off | \
                             bluetooth_on | bluetooth_off"),
                "value": i("For brightness: level 0-255")
            }), &["action"]),
            Moderate, "shell",
        ),
        t(
            "app_permission", "Manage app permissions",
            "List a package's runtime permissions (action=list) or grant/revoke \
             one — e.g. android.permission.CAMERA, POST_NOTIFICATIONS. Granting \
             is what unblocks apps that ask for access.",
            obj(json!({
                "package": s("Package name"),
                "action": s("list | grant | revoke (default list)"),
                "permission": s("For grant/revoke, e.g. android.permission.CAMERA")
            }), &["package"]),
            Moderate, "shell",
        ),
        t(
            "clipboard_get", "Read clipboard",
            "Current clipboard contents, if text.",
            obj(json!({}), &[]),
            Readonly, "shell",
        ).needs(Requires::Nothing),
        t(
            "clipboard_set", "Set clipboard",
            "Set the system clipboard text (in-app, with a shell fallback).",
            obj(json!({"text": s("Text to place on clipboard")}), &["text"]),
            Moderate, "shell",
        ).needs(Requires::Nothing),
        t(
            "input_tap", "Tap (input CLI)",
            "Tap via the `input` command. Only offered when accessibility is off.",
            obj(json!({
                "x": i("X coordinate"), "y": i("Y coordinate")
            }), &["x", "y"]),
            Moderate, "shell",
        ).exposed(Expose::NoA11y),
        t(
            "input_swipe", "Swipe (input CLI)",
            "Swipe via the `input` command. Only offered when accessibility is off.",
            obj(json!({
                "x1": i("Start X"), "y1": i("Start Y"),
                "x2": i("End X"), "y2": i("End Y"),
                "duration_ms": i("Gesture duration (default 300)")
            }), &["x1", "y1", "x2", "y2"]),
            Moderate, "shell",
        ).exposed(Expose::NoA11y),
        t(
            "input_text", "Type text (input CLI)",
            "Insert text via `input text` (ASCII only). Only offered when \
             accessibility is off.",
            obj(json!({"text": s("Text to input")}), &["text"]),
            Moderate, "shell",
        ).exposed(Expose::NoA11y),
        t(
            "input_key", "Key event (input CLI)",
            "Send a KEYEVENT (name like KEYCODE_BACK or numeric code) via \
             `input keyevent`. Only offered when accessibility is off.",
            obj(json!({"key": s("Key name or numeric code")}), &["key"]),
            Moderate, "shell",
        ).exposed(Expose::NoA11y),
        t(
            "http_request", "Make an HTTP request",
            "HTTP(S) request from the phone itself: fetch an API response, \
             download data or POST to a service. Returns status, content type \
             and body (long bodies keep beginning and end).",
            obj(json!({
                "url": s("http:// or https:// URL"),
                "method": s("GET | POST | PUT | PATCH | DELETE | HEAD (default GET)"),
                "headers": {"type": "object", "description": "Request headers as name: value"},
                "body": s("Request body (POST/PUT/PATCH)"),
                "timeout_ms": i("Connect and read timeout (default 15000, max 30000)")
            }), &["url"]),
            Moderate, "app",
        ),
        // ---------- a11y backend ----------
        t(
            "screen", "Read the screen",
            "The current screen as a compact list: `[ref] role: label (state) @x,y`, \
             plus plain text lines. Act on elements by ref with tap / long_press / \
             type_text / scroll. Action tools already return the updated screen, so \
             call this only when you have no current screen.",
            obj(json!({}), &[]),
            Readonly, "a11y",
        ),
        t(
            "ui_tree", "Dump UI hierarchy (raw)",
            "Raw accessibility node tree as JSON (class, id, text, bounds, caps). \
             For the playground; the model uses `screen`.",
            obj(json!({
                "max_depth": i("Depth cap (default 30)"),
                "package": s("Limit to this package's window if present")
            }), &[]),
            Readonly, "a11y",
        ).exposed(Expose::CatalogOnly),
        t(
            "ui_find", "Find UI nodes (raw)",
            "Find nodes by text / content-description / resource-id (regex allowed). \
             Returns matching nodes with bounds.",
            obj(json!({
                "text": s("Match node text (regex ok)"),
                "desc": s("Match content-description (regex ok)"),
                "resource_id": s("Match view id resource name (regex ok)"),
                "clickable_only": b("Only clickable nodes (default false)")
            }), &[]),
            Readonly, "a11y",
        ).exposed(Expose::CatalogOnly),
        t(
            "tap", "Tap",
            "Tap an element by ref (preferred), by its visible text/description, or \
             at x,y. Returns the updated screen.",
            obj(json!({
                "ref": i("Element ref from the screen"),
                "text": s("Visible text or description of the element"),
                "x": i("X coordinate"),
                "y": i("Y coordinate")
            }), &[]),
            Moderate, "a11y",
        ),
        t(
            "long_press", "Long press",
            "Long-press an element by ref or text, or at x,y. Returns the updated screen.",
            obj(json!({
                "ref": i("Element ref from the screen"),
                "text": s("Visible text or description of the element"),
                "x": i("X"), "y": i("Y"),
                "duration_ms": i("Hold duration (default 800)")
            }), &[]),
            Moderate, "a11y",
        ),
        t(
            "swipe", "Swipe",
            "Swipe between two points over duration_ms. Returns the updated screen.",
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
            "Scroll the screen or a list (by ref or text). Direction is where you want \
             to move in the content: down reveals content further down, like scrolling \
             a page. Returns the updated screen.",
            obj(json!({
                "direction": s("up | down | left | right"),
                "ref": i("Scrollable element ref (or an element inside it)"),
                "text": s("Text of an element inside the list to scroll"),
                "times": i("Repeat count (default 1, max 10)")
            }), &["direction"]),
            Moderate, "a11y",
        ),
        t(
            "scroll_until", "Scroll until found",
            "Scroll a list until `text`/`desc`/`resource_id` appears (up to \
             max_times scrolls) — cheaper than repeated scroll calls when looking \
             for something off-screen. Returns the screen with `found`.",
            obj(json!({
                "direction": s("up | down | left | right"),
                "text": s("Stop when this text is on screen (regex ok)"),
                "desc": s("Stop on this content-description (regex ok)"),
                "resource_id": s("Stop on this view id (regex ok)"),
                "ref": i("Scrollable element ref (or an element inside it)"),
                "container_text": s("Text of an element inside the list to scroll"),
                "max_times": i("Scroll budget (default 8, max 20)")
            }), &["direction"]),
            Moderate, "a11y",
        ),
        t(
            "double_tap", "Double tap",
            "Double-tap an element by ref or text, or at x,y — e.g. map zoom, \
             selecting a word. Returns the updated screen.",
            obj(json!({
                "ref": i("Element ref from the screen"),
                "text": s("Visible text or description of the element"),
                "x": i("X coordinate"),
                "y": i("Y coordinate")
            }), &[]),
            Moderate, "a11y",
        ),
        t(
            "drag", "Drag an element",
            "Press-hold an element (by ref or text, or from x,y) and drag it to \
             to_x,to_y — for reordering lists, sliders and drag-and-drop. \
             Returns the updated screen.",
            obj(json!({
                "ref": i("Element ref from the screen"),
                "text": s("Visible text or description of the element"),
                "x": i("Start X"), "y": i("Start Y"),
                "to_x": i("Destination X"), "to_y": i("Destination Y"),
                "duration_ms": i("Move duration (default 500)"),
                "hold_ms": i("Press-and-hold before moving (default 350)")
            }), &["to_x", "to_y"]),
            Moderate, "a11y",
        ),
        t(
            "copy_text", "Copy element text",
            "Copy an element's or text field's text to the clipboard (use \
             clipboard_get to read it). By ref or by its visible text.",
            obj(json!({
                "ref": i("Element ref from the screen"),
                "text": s("Visible text or description of the element")
            }), &[]),
            Moderate, "a11y",
        ),
        t(
            "type_text", "Type into field",
            "Replace the text of an input field (by ref, by its text/hint, or the \
             focused field; append=true adds instead). `submit` then presses the \
             keyboard's enter/search action. Returns the updated screen.",
            obj(json!({
                "text": s("Text to enter"),
                "ref": i("Input field ref"),
                "target_text": s("Field's current text or hint"),
                "append": b("Append to existing text instead of replacing (default false)"),
                "submit": b("Press enter/search afterwards (default false)")
            }), &["text"]),
            Moderate, "a11y",
        ),
        t(
            "key", "System key",
            "back | home | recents | notifications | quick_settings | power_dialog | \
             lock | enter (keyboard action on the focused field). On the virtual \
             screen: back, enter, tab, del, space, esc, up/down/left/right. \
             Returns the updated screen.",
            obj(json!({"name": s("Key name")}), &["name"]),
            Moderate, "a11y",
        ),
        t(
            "wait_for", "Wait for UI state",
            "Wait until text appears on screen or an app is in front (or timeout). \
             gone=true instead waits for it to DISAPPEAR (spinners, dialogs). \
             Use after actions that load slowly instead of sleeping.",
            obj(json!({
                "text": s("Wait for this text/description (regex ok)"),
                "package": s("Wait for this foreground package"),
                "gone": b("Wait until the target is gone instead (default false)"),
                "timeout_ms": i("Wait budget (default 10000, max 60000)")
            }), &[]),
            Readonly, "a11y",
        ),
        t(
            "screenshot", "Screenshot",
            "See the screen as an image, with each element's ref drawn as a numbered \
             box. Use when the text screen is ambiguous (unlabeled icons, images, \
             canvas/game content).",
            obj(json!({}), &[]),
            Readonly, "a11y",
        ).exposed(Expose::Vision),
        t(
            "launch_intent", "Open intent / deep link",
            "Start an activity by URI: deep link (https://, app scheme), `intent:` URI, \
             or a settings action like android.settings.WIFI_SETTINGS. Returns the \
             resulting screen.",
            obj(json!({"uri": s("URI, intent: URI, or android.settings.* action")}), &["uri"]),
            Moderate, "a11y",
        ).needs(Requires::Nothing),
        // ---------- meta ----------
        t(
            "act", "Run several steps",
            "Run a short sequence of tool calls in order and stop at the first failure; \
             only the final screen is returned. Use for predictable sequences, e.g. tap \
             a field, type, submit. Each step is checked and confirmed like a normal call.",
            obj(json!({
                "steps": {
                    "type": "array",
                    "maxItems": 10,
                    "description": "Steps like {\"tool\":\"tap\",\"args\":{\"ref\":3}}",
                    "items": {
                        "type": "object",
                        "properties": {
                            "tool": s("Tool name"),
                            "args": {"type": "object", "description": "That tool's arguments"}
                        },
                        "required": ["tool"]
                    }
                }
            }), &["steps"]),
            Moderate, "meta",
        ),
        t(
            "skill", "Read a skill",
            "Show a saved skill's full instructions, parameters and steps. Skills are \
             listed in the system prompt.",
            obj(json!({"name": s("Skill name from the list")}), &["name"]),
            Readonly, "meta",
        ).exposed(Expose::Skills),
        t(
            "run_skill", "Run a skill",
            "Execute a skill's saved steps (skills marked [runs]) with the given \
             parameters. Stops at the first failing step and returns the screen so \
             you can continue manually. Each step is confirmed like a normal call.",
            obj(json!({
                "name": s("Skill name"),
                "params": {"type": "object", "description": "Parameter values, e.g. {\"query\":\"…\"}"}
            }), &["name"]),
            Moderate, "meta",
        ).exposed(Expose::Skills),
        t(
            "save_skill", "Propose a skill",
            "Save a reusable skill when the user asks you to remember how to do \
             something. It is stored as a draft the user reviews before it can be used.",
            obj(json!({
                "name": s("Short slug, e.g. order-coffee"),
                "description": s("When to use it, one line"),
                "instructions": s("How to do it on this phone: apps, screens, pitfalls"),
                "params": {
                    "type": "array",
                    "description": "Inputs that change between uses",
                    "items": {"type": "object", "properties": {
                        "name": s("Parameter name"),
                        "description": s("What it is"),
                        "example": s("The value used in this run; recorded steps get {{name}} in its place")
                    }, "required": ["name"]}
                },
                "record_steps": b("Attach this run's successful UI steps as runnable steps (default true)")
            }), &["name", "description", "instructions"]),
            Moderate, "meta",
        ).exposed(Expose::Learn),
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
    /// OpenAI `tools[]` entry. `compact` swaps the long description for the
    /// one-line summary — parameter descriptions always stay.
    pub fn openai_schema(&self, compact: bool) -> Value {
        json!({
            "type": "function",
            "function": {
                "name": self.name,
                "description": if compact { self.summary } else { self.description },
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
            "requires": self.requires,
            "expose": self.expose,
            "parameters": self.parameters,
        })
    }
}

/// `tools[]` for a run: only what `caps` makes usable. The order is fixed so
/// the payload is byte-identical across turns (prefix-cache friendly).
pub fn openai_tools(compact: bool, caps: &Caps) -> Value {
    json!(all()
        .iter()
        .filter(|t| t.visible(caps))
        .map(|t| t.openai_schema(compact))
        .collect::<Vec<_>>())
}

/// Whether `name` may run as a step of `act` under `caps`.
pub fn allowed_in_act(name: &str, caps: &Caps) -> bool {
    !AGENT_TOOLS.contains(&name) && all().iter().any(|t| t.name == name && t.visible(caps))
}

/// Whether the model was offered `name` in this run.
pub fn offered(name: &str, caps: &Caps) -> bool {
    all().iter().any(|t| t.name == name && t.visible(caps))
}

pub fn catalog() -> Value {
    json!(all().iter().map(|t| t.catalog_json()).collect::<Vec<_>>())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(v: &Value) -> Vec<String> {
        v.as_array()
            .unwrap()
            .iter()
            .map(|t| t["function"]["name"].as_str().unwrap().to_string())
            .collect()
    }

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
            assert!(t.description.len() > 20, "{}: {:?}", t.name, t.description);
        }
        let caps = Caps::default();
        let v = openai_tools(false, &caps);
        assert!(v.is_array());
        let compact = openai_tools(true, &caps);
        assert!(
            compact.to_string().len() < v.to_string().len() * 9 / 10,
            "compact schemas should be measurably smaller"
        );
    }

    #[test]
    fn tool_set_follows_capabilities() {
        let full = names(&openai_tools(true, &Caps::default()));
        assert!(full.contains(&"screen".into()));
        assert!(full.contains(&"shell_exec".into()));
        assert!(
            !full.contains(&"input_tap".into()),
            "a11y on: no CLI fallbacks"
        );
        assert!(
            !full.contains(&"ui_tree".into()),
            "raw dump is playground-only"
        );
        assert!(
            !full.contains(&"screenshot".into()),
            "no vision: no image tool"
        );
        assert!(full.len() <= 42, "{} tools: {full:?}", full.len());
        assert!(full.contains(&"scroll_until".into()));
        assert!(full.contains(&"drag".into()));
        assert!(full.contains(&"double_tap".into()));
        assert!(full.contains(&"copy_text".into()));
        assert!(full.contains(&"notifications".into()));
        assert!(full.contains(&"device_control".into()));
        assert!(full.contains(&"app_permission".into()));
        assert!(full.contains(&"http_request".into()));
        assert!(!full.contains(&"skill".into()), "no skills: no skill tools");
        assert!(full.contains(&"save_skill".into()));
        let with_skills = names(&openai_tools(
            true,
            &Caps {
                skills: true,
                learn: false,
                ..Caps::default()
            },
        ));
        assert!(with_skills.contains(&"skill".into()) && with_skills.contains(&"run_skill".into()));
        assert!(!with_skills.contains(&"save_skill".into()));

        let no_a11y = names(&openai_tools(
            true,
            &Caps {
                a11y: false,
                ..Caps::default()
            },
        ));
        assert!(no_a11y.contains(&"input_tap".into()));
        assert!(!no_a11y.contains(&"tap".into()));
        assert!(
            no_a11y.contains(&"app_launch".into()),
            "in-process tools stay"
        );

        let no_shell = names(&openai_tools(
            true,
            &Caps {
                shell: false,
                vision: true,
                ..Caps::default()
            },
        ));
        assert!(!no_shell.contains(&"shell_exec".into()));
        assert!(no_shell.contains(&"screenshot".into()));
        assert!(no_shell.contains(&"app_launch".into()));
    }

    #[test]
    fn act_cannot_nest_agent_tools() {
        let caps = Caps::default();
        assert!(allowed_in_act("tap", &caps));
        assert!(!allowed_in_act("act", &caps));
        assert!(!allowed_in_act("task_done", &caps));
        assert!(!allowed_in_act("ask_user", &caps));
        let with_skills = Caps {
            skills: true,
            ..Caps::default()
        };
        assert!(
            !allowed_in_act("run_skill", &with_skills),
            "skills can't recurse"
        );
        assert!(!allowed_in_act("save_skill", &with_skills));
        assert!(
            !allowed_in_act("ui_tree", &caps),
            "not offered → not via act"
        );
        assert!(!allowed_in_act("nope", &caps));
    }

    #[test]
    fn tool_payload_is_smaller_than_before() {
        // Baseline: the previous catalog sent all 37 tools every turn.
        let all_compact: usize = all()
            .iter()
            .map(|t| t.openai_schema(true).to_string().len())
            .sum();
        let now = openai_tools(true, &Caps::default()).to_string().len();
        assert!(
            now * 100 < all_compact * 92,
            "visible set {now}B vs all {all_compact}B"
        );
    }
}
