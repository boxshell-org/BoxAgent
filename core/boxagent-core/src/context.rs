//! Token hygiene for the rolling context.
//!
//! Screen observations dominate an operating agent's token bill: every
//! action produces one, and every request resends all of them. This module
//! keeps exactly one live screen in context:
//!
//! * a tool result's `screen` field is rendered as raw text after the JSON
//!   metadata (no `\n`/`\"` escaping) under a `[screen]` marker;
//! * a screen identical to the latest one collapses to `[screen] unchanged`;
//! * once a newer full screen exists, older ones are elided in place;
//! * vision screenshots ride in a trailing user message and only the newest
//!   image is kept.
//!
//! It also flags repeated no-effect calls so the model changes approach
//! instead of burning steps.

use serde_json::{json, Map, Value};
use std::collections::HashMap;

/// Separates a tool result's JSON metadata from its full screen text.
pub const SCREEN_TAG: &str = "\n[screen]\n";
const SCREEN_UNCHANGED: &str = "\n[screen] unchanged";
const SCREEN_OMITTED: &str = "\n[screen] omitted (a newer screen follows)";
const IMAGE_CAPTION: &str = "Screenshot after the last action (boxes are element refs):";
const IMAGE_OMITTED: &str = "(earlier screenshot omitted)";
/// What an image is assumed to cost when sizing the context — its base64
/// would otherwise look like ~100 KB of text and trip trimming at once.
pub const IMAGE_SIZE_ESTIMATE: usize = 3000;
/// Screen-less results bigger than this that repeat unchanged collapse.
const DEDUP_MIN: usize = 64;
const REPEAT_HINT: &str = "No effect: this exact call just ran with the same result. \
Try a different element, scroll, go back, or ask_user.";

/// A screenshot destined for a vision model.
#[derive(Debug, Clone, PartialEq)]
pub struct Image {
    pub mime: String,
    pub b64: String,
}

/// A shaped tool result: the `tool` message text, plus an image to attach.
#[derive(Debug)]
pub struct Shaped {
    pub content: String,
    pub image: Option<Image>,
}

#[derive(PartialEq)]
enum ScreenPart {
    None,
    Unchanged,
    Full(String),
}

/// Per-run state for shaping tool results.
pub struct Shaper {
    vision: bool,
    last_screen: Option<String>,
    last_by_tool: HashMap<String, String>,
    last_sig: String,
    last_outcome: String,
}

impl Shaper {
    pub fn new(vision: bool) -> Self {
        Shaper {
            vision,
            last_screen: None,
            last_by_tool: HashMap::new(),
            last_sig: String::new(),
            last_outcome: String::new(),
        }
    }

    /// Forget dedup baselines — after context trimming they may refer to
    /// messages that are gone.
    pub fn reset(&mut self) {
        self.last_screen = None;
        self.last_by_tool.clear();
    }

    pub fn shape(&mut self, name: &str, args: &str, raw: &str) -> Shaped {
        let mut v: Value = match serde_json::from_str(raw) {
            Ok(v @ Value::Object(_)) => v,
            _ => {
                self.note_call(name, args, raw.to_string());
                return Shaped {
                    content: raw.to_string(),
                    image: None,
                };
            }
        };
        let obj = v.as_object_mut().expect("object");
        let image = take_binary(obj, self.vision);

        let screen = match obj.remove("screen") {
            Some(Value::String(s)) if !s.trim().is_empty() => ScreenPart::Full(s),
            _ => ScreenPart::None,
        };
        let screen = match screen {
            ScreenPart::Full(s) if self.last_screen.as_deref() == Some(s.as_str()) => {
                ScreenPart::Unchanged
            }
            ScreenPart::Full(s) => {
                self.last_screen = Some(s.clone());
                ScreenPart::Full(s)
            }
            other => other,
        };

        let mut meta = v.to_string();
        if screen == ScreenPart::None {
            // Same large result as this tool's previous call (e.g. listing
            // an unchanged directory): the earlier copy is still in context.
            if meta.len() > DEDUP_MIN && self.last_by_tool.get(name) == Some(&meta) {
                meta = json!({"ok": true, "note": "unchanged from previous call"}).to_string();
            } else {
                self.last_by_tool.insert(name.to_string(), meta.clone());
            }
        }

        let outcome = match &screen {
            ScreenPart::Unchanged => format!("{meta}|unchanged"),
            ScreenPart::Full(_) => format!("{meta}|changed"),
            ScreenPart::None => meta.clone(),
        };
        if self.note_call(name, args, outcome) {
            if let Ok(Value::Object(mut m)) = serde_json::from_str::<Value>(&meta) {
                m.insert("hint".into(), json!(REPEAT_HINT));
                meta = Value::Object(m).to_string();
            }
        }

        let content = match screen {
            ScreenPart::None => meta,
            ScreenPart::Unchanged => format!("{meta}{SCREEN_UNCHANGED}"),
            ScreenPart::Full(s) => format!("{meta}{SCREEN_TAG}{}", s.trim_end()),
        };
        Shaped { content, image }
    }

    /// Record this call; true when it repeats the previous call exactly
    /// (same tool + args) with an outcome showing no effect.
    fn note_call(&mut self, name: &str, args: &str, outcome: String) -> bool {
        let sig = format!("{name}\u{1}{}", normalize_args(args));
        let repeated = sig == self.last_sig
            && (outcome == self.last_outcome || outcome.ends_with("|unchanged"));
        self.last_sig = sig;
        self.last_outcome = outcome;
        repeated
    }
}

/// Compare arguments structurally, so key order / spacing don't matter.
fn normalize_args(args: &str) -> String {
    serde_json::from_str::<Value>(args)
        .map(|v| v.to_string())
        .unwrap_or_else(|_| args.trim().to_string())
}

/// Strip binary payloads a text model can't use. With vision, a screenshot
/// (`image_b64`) is lifted out to be attached as an image instead.
fn take_binary(obj: &mut Map<String, Value>, vision: bool) -> Option<Image> {
    let mut image = None;
    if let Some(Value::String(b64)) = obj.remove("image_b64") {
        let mime = obj
            .get("mime")
            .and_then(Value::as_str)
            .unwrap_or("image/jpeg")
            .to_string();
        if vision {
            obj.insert("image".into(), json!("attached below"));
            image = Some(Image { mime, b64 });
        } else {
            obj.insert("bytes".into(), json!(b64.len() * 3 / 4));
            obj.insert(
                "note".into(),
                json!("image omitted: this model can't view images"),
            );
        }
    }
    if let Some(b64) = obj.remove("data_b64") {
        let bytes = b64.as_str().map(|s| s.len() * 3 / 4).unwrap_or(0);
        obj.insert("bytes".into(), json!(bytes));
        obj.insert(
            "note".into(),
            json!("binary data omitted; read the screen with `screen`"),
        );
    }
    image
}

/// Trailing user message carrying the newest screenshot.
pub fn image_message(img: &Image) -> Value {
    json!({
        "role": "user",
        "content": [
            {"type": "text", "text": IMAGE_CAPTION},
            {"type": "image_url", "image_url": {
                "url": format!("data:{};base64,{}", img.mime, img.b64),
            }},
        ],
    })
}

pub fn is_image_message(m: &Value) -> bool {
    m["role"] == "user"
        && m["content"]
            .as_array()
            .is_some_and(|parts| parts.iter().any(|p| p["type"] == "image_url"))
}

/// Keep only the newest screenshot; older ones become a short note.
pub fn elide_old_images(messages: &mut [Value]) -> bool {
    let idx: Vec<usize> = (0..messages.len())
        .filter(|&i| is_image_message(&messages[i]))
        .collect();
    let Some((_, older)) = idx.split_last() else {
        return false;
    };
    for &i in older {
        messages[i]["content"] = json!(IMAGE_OMITTED);
    }
    !older.is_empty()
}

/// Elide the screen text of every tool result except the newest one that
/// still carries a full screen. Returns whether anything changed.
///
/// Touches only the second-newest screen on a typical turn, so the
/// provider's prefix cache stays warm up to that point.
pub fn elide_old_screens(messages: &mut [Value]) -> bool {
    let full: Vec<usize> = (0..messages.len())
        .filter(|&i| {
            messages[i]["role"] == "tool"
                && messages[i]["content"]
                    .as_str()
                    .is_some_and(|c| c.contains(SCREEN_TAG))
        })
        .collect();
    let Some((_, older)) = full.split_last() else {
        return false;
    };
    for &i in older {
        let c = messages[i]["content"].as_str().unwrap_or_default();
        let head = c.split(SCREEN_TAG).next().unwrap_or_default();
        messages[i]["content"] = json!(format!("{head}{SCREEN_OMITTED}"));
    }
    !older.is_empty()
}

/// Size of a message for context budgeting (images at a flat estimate).
pub fn message_size(m: &Value) -> usize {
    match m["content"].as_array() {
        Some(parts) => parts
            .iter()
            .map(|p| {
                if p["type"] == "image_url" {
                    IMAGE_SIZE_ESTIMATE
                } else {
                    p["text"].as_str().map_or(16, str::len)
                }
            })
            .sum(),
        None => m.to_string().len(),
    }
}

/// Running token totals across a run's requests, normalized over the
/// cached-token fields different providers use.
#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub struct Usage {
    pub prompt: u64,
    pub completion: u64,
    pub cached: u64,
    pub requests: u32,
}

impl Usage {
    pub fn from_value(u: &Value) -> Usage {
        let n = |v: &Value| v.as_u64().unwrap_or(0);
        let cached = [
            &u["prompt_tokens_details"]["cached_tokens"], // OpenAI
            &u["prompt_cache_hit_tokens"],                // DeepSeek
            &u["cache_read_input_tokens"],                // Anthropic-style
        ]
        .into_iter()
        .map(n)
        .max()
        .unwrap_or(0);
        Usage {
            prompt: n(&u["prompt_tokens"]).max(n(&u["input_tokens"])),
            completion: n(&u["completion_tokens"]).max(n(&u["output_tokens"])),
            cached,
            requests: 1,
        }
    }

    pub fn add(&mut self, o: &Usage) {
        self.prompt += o.prompt;
        self.completion += o.completion;
        self.cached += o.cached;
        self.requests += o.requests;
    }

    pub fn to_json(self) -> Value {
        json!({
            "prompt": self.prompt,
            "completion": self.completion,
            "cached": self.cached,
            "requests": self.requests,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tool(content: &str) -> Value {
        json!({"role": "tool", "tool_call_id": "x", "content": content})
    }

    #[test]
    fn screen_is_rendered_raw_and_deduped() {
        let mut s = Shaper::new(false);
        let a = s.shape(
            "tap",
            r#"{"ref":3}"#,
            r#"{"ok":true,"via":"node_click","screen":"app: x\n[1] button: \"OK\" @1,2"}"#,
        );
        assert_eq!(
            a.content,
            "{\"ok\":true,\"via\":\"node_click\"}\n[screen]\napp: x\n[1] button: \"OK\" @1,2"
        );
        let b = s.shape(
            "scroll",
            r#"{"direction":"down"}"#,
            r#"{"ok":true,"screen":"app: x\n[1] button: \"OK\" @1,2"}"#,
        );
        assert_eq!(b.content, "{\"ok\":true}\n[screen] unchanged");
        // A different screen is full again.
        let c = s.shape(
            "key",
            r#"{"name":"back"}"#,
            r#"{"ok":true,"screen":"app: y"}"#,
        );
        assert!(c.content.ends_with("[screen]\napp: y"));
    }

    #[test]
    fn repeated_no_effect_call_gets_a_hint() {
        let mut s = Shaper::new(false);
        let raw = r#"{"ok":true,"screen":"app: x\n[1] list (scroll) @5,5"}"#;
        let first = s.shape("scroll", r#"{"direction":"down"}"#, raw);
        assert!(!first.content.contains("hint"));
        // Same call, same screen: the list is at its end.
        let second = s.shape("scroll", r#"{ "direction": "down" }"#, raw);
        assert!(second.content.contains("No effect"), "{}", second.content);
        // Same failing call twice.
        let err = r#"{"ok":false,"error":"no matching node"}"#;
        s.shape("tap", r#"{"text":"Foo"}"#, err);
        assert!(s
            .shape("tap", r#"{"text":"Foo"}"#, err)
            .content
            .contains("No effect"));
        // Different args → no hint.
        assert!(!s
            .shape("tap", r#"{"text":"Bar"}"#, err)
            .content
            .contains("No effect"));
    }

    #[test]
    fn only_newest_screen_survives() {
        let mut m = vec![
            json!({"role": "system", "content": "s"}),
            tool("{\"ok\":true}\n[screen]\nOLD1"),
            tool("{\"ok\":true}\n[screen] unchanged"),
            tool("{\"ok\":true}\n[screen]\nOLD2"),
            tool("{\"ok\":false}"),
            tool("{\"ok\":true}\n[screen]\nNEW"),
            tool("{\"ok\":true}\n[screen] unchanged"),
        ];
        assert!(elide_old_screens(&mut m));
        assert_eq!(
            m[1]["content"],
            "{\"ok\":true}\n[screen] omitted (a newer screen follows)"
        );
        assert!(m[3]["content"]
            .as_str()
            .unwrap()
            .ends_with("omitted (a newer screen follows)"));
        assert_eq!(m[5]["content"], "{\"ok\":true}\n[screen]\nNEW");
        assert_eq!(m[2]["content"], "{\"ok\":true}\n[screen] unchanged");
        // Idempotent.
        let snap = m.clone();
        assert!(!elide_old_screens(&mut m));
        assert_eq!(m, snap);
    }

    #[test]
    fn images_attach_only_with_vision_and_only_newest_kept() {
        let raw = r#"{"ok":true,"mime":"image/jpeg","image_b64":"QUJD","screen":"app: x"}"#;
        let text_only = Shaper::new(false).shape("screenshot", "{}", raw);
        assert!(text_only.image.is_none());
        assert!(text_only.content.contains("can't view images"));
        assert!(!text_only.content.contains("QUJD"));

        let seen = Shaper::new(true).shape("screenshot", "{}", raw);
        let img = seen.image.expect("image");
        assert_eq!(img.b64, "QUJD");
        assert!(!seen.content.contains("QUJD"));

        let mut m = vec![image_message(&img), tool("{}"), image_message(&img)];
        assert!(is_image_message(&m[0]));
        assert!(elide_old_images(&mut m));
        assert_eq!(m[0]["content"], IMAGE_OMITTED);
        assert!(is_image_message(&m[2]));
        assert_eq!(
            message_size(&m[2]),
            IMAGE_CAPTION.len() + IMAGE_SIZE_ESTIMATE
        );
    }

    #[test]
    fn non_screen_results_dedupe_by_tool() {
        let mut s = Shaper::new(false);
        let big = format!(r#"{{"ok":true,"entries":"{}"}}"#, "x".repeat(100));
        assert!(s
            .shape("file_list", r#"{"path":"/a"}"#, &big)
            .content
            .contains("xxx"));
        let again = s.shape("file_list", r#"{"path":"/a"}"#, &big);
        assert!(again.content.contains("unchanged from previous call"));
        // Non-JSON passes through.
        assert_eq!(s.shape("x", "{}", "plain").content, "plain");
    }

    #[test]
    fn usage_normalizes_provider_fields() {
        let openai = json!({"prompt_tokens": 100, "completion_tokens": 7,
            "prompt_tokens_details": {"cached_tokens": 64}});
        let deepseek = json!({"prompt_tokens": 50, "completion_tokens": 3,
            "prompt_cache_hit_tokens": 40});
        let mut total = Usage::default();
        total.add(&Usage::from_value(&openai));
        total.add(&Usage::from_value(&deepseek));
        assert_eq!(
            total,
            Usage {
                prompt: 150,
                completion: 10,
                cached: 104,
                requests: 2
            }
        );
    }
}
