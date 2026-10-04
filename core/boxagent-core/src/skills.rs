//! Skills: saved, reusable procedures.
//!
//! A skill is instructions (how to do something on this phone) plus,
//! optionally, deterministic steps — tool calls with text selectors and
//! `{{param}}` placeholders. The app sends the enabled skills at run start.
//!
//! Progressive disclosure keeps them cheap: the system prompt carries only
//! a one-line index; `skill` loads one skill in full; `run_skill` expands
//! its steps and runs them through the normal tool path (so every step is
//! still confirmed and audited by the app).

use serde_json::{json, Map, Value};

/// Index lines in the system prompt; the app sends skills most-relevant
/// first, so a long library degrades gracefully.
pub const MAX_INDEXED: usize = 40;
pub const MAX_STEPS: usize = 30;

#[derive(Debug, Clone, PartialEq)]
pub struct Param {
    pub name: String,
    pub description: String,
    pub required: bool,
    pub default: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Step {
    pub tool: String,
    pub args: Value,
    /// A failing optional step doesn't stop the run (e.g. dismissing a
    /// dialog that only sometimes appears).
    pub optional: bool,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Skill {
    /// Slug, unique: `web-search`.
    pub name: String,
    pub title: String,
    /// When to use it — the index line.
    pub description: String,
    pub instructions: String,
    pub apps: Vec<String>,
    pub params: Vec<Param>,
    pub steps: Vec<Step>,
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or_default().trim().to_string()
}

impl Skill {
    pub fn from_value(v: &Value) -> Option<Skill> {
        let name = s(&v["name"]);
        if name.is_empty() {
            return None;
        }
        let params = v["params"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|p| {
                let name = s(&p["name"]);
                (!name.is_empty()).then(|| Param {
                    name,
                    description: s(&p["description"]),
                    required: p["required"].as_bool().unwrap_or(true),
                    default: p["default"].as_str().map(String::from),
                })
            })
            .collect();
        let steps = v["steps"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|st| {
                let tool = s(&st["tool"]);
                (!tool.is_empty()).then(|| Step {
                    tool,
                    args: match &st["args"] {
                        Value::Object(m) => Value::Object(m.clone()),
                        _ => json!({}),
                    },
                    optional: st["optional"].as_bool().unwrap_or(false),
                })
            })
            .take(MAX_STEPS)
            .collect();
        Some(Skill {
            title: Some(s(&v["title"]))
                .filter(|t| !t.is_empty())
                .unwrap_or_else(|| name.clone()),
            name,
            description: s(&v["description"]),
            instructions: v["instructions"]
                .as_str()
                .unwrap_or_default()
                .trim()
                .to_string(),
            apps: v["apps"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|a| a.as_str().map(|a| a.trim().to_string()))
                .filter(|a| !a.is_empty())
                .collect(),
            params,
            steps,
        })
    }

    pub fn runnable(&self) -> bool {
        !self.steps.is_empty()
    }

    /// One index line: `- web-search(query) [runs]: Search the web.`
    fn index_line(&self) -> String {
        let mut l = format!("- {}", self.name);
        if !self.params.is_empty() {
            let names: Vec<&str> = self.params.iter().map(|p| p.name.as_str()).collect();
            l.push('(');
            l.push_str(&names.join(", "));
            l.push(')');
        }
        if self.runnable() {
            l.push_str(" [runs]");
        }
        l.push_str(": ");
        l.push_str(if self.description.is_empty() {
            &self.title
        } else {
            &self.description
        });
        l
    }

    /// Full skill for the `skill` tool.
    pub fn card(&self) -> Value {
        let mut v = json!({
            "ok": true,
            "name": self.name,
            "title": self.title,
            "description": self.description,
            "instructions": self.instructions,
        });
        if !self.apps.is_empty() {
            v["apps"] = json!(self.apps);
        }
        if !self.params.is_empty() {
            v["params"] = json!(self
                .params
                .iter()
                .map(|p| {
                    let mut o = json!({"name": p.name, "description": p.description});
                    if !p.required {
                        o["required"] = json!(false);
                    }
                    if let Some(d) = &p.default {
                        o["default"] = json!(d);
                    }
                    o
                })
                .collect::<Vec<_>>());
        }
        if self.runnable() {
            v["steps"] = json!(self
                .steps
                .iter()
                .map(|st| format!("{} {}", st.tool, st.args))
                .collect::<Vec<_>>());
            v["how"] = json!("run_skill executes these steps; continue manually if one fails");
        }
        v
    }

    /// Steps with `{{param}}` filled in. Fails (before anything runs) on a
    /// missing required parameter.
    pub fn expand(&self, given: &Value) -> Result<Vec<Step>, String> {
        let mut values: Map<String, Value> = Map::new();
        for p in &self.params {
            let v = given.get(&p.name).filter(|v| !v.is_null());
            match (v, &p.default) {
                (Some(v), _) => {
                    let text = match v {
                        Value::String(s) => s.clone(),
                        other => other.to_string(),
                    };
                    values.insert(p.name.clone(), json!(text));
                }
                (None, Some(d)) => {
                    values.insert(p.name.clone(), json!(d));
                }
                (None, None) if p.required => {
                    return Err(format!(
                        "missing parameter `{}` ({})",
                        p.name, p.description
                    ));
                }
                (None, None) => {
                    values.insert(p.name.clone(), json!(""));
                }
            }
        }
        self.steps
            .iter()
            .map(|st| {
                Ok(Step {
                    tool: st.tool.clone(),
                    args: fill(&st.args, &values)?,
                    optional: st.optional,
                })
            })
            .collect()
    }
}

/// Replace `{{name}}` in every string of `v`.
fn fill(v: &Value, values: &Map<String, Value>) -> Result<Value, String> {
    Ok(match v {
        Value::String(s) => {
            let mut out = s.clone();
            let mut start = 0;
            while let Some(open) = out[start..].find("{{") {
                let open = start + open;
                let Some(close) = out[open..].find("}}") else {
                    break;
                };
                let close = open + close;
                let inner = out[open + 2..close].to_string();
                let (key, filter) = match inner.split_once('|') {
                    Some((k, f)) => (k.trim(), Some(f.trim())),
                    None => (inner.trim(), None),
                };
                let Some(raw) = values.get(key).and_then(Value::as_str) else {
                    return Err(format!("step uses unknown parameter `{key}`"));
                };
                let val = match filter {
                    None => raw.to_string(),
                    Some("url") => url_encode(raw),
                    Some(f) => return Err(format!("unknown filter `{f}` on `{key}`")),
                };
                out.replace_range(open..close + 2, &val);
                start = open + val.len();
            }
            Value::String(out)
        }
        Value::Array(a) => Value::Array(
            a.iter()
                .map(|x| fill(x, values))
                .collect::<Result<_, _>>()?,
        ),
        Value::Object(m) => Value::Object(
            m.iter()
                .map(|(k, x)| Ok((k.clone(), fill(x, values)?)))
                .collect::<Result<_, String>>()?,
        ),
        other => other.clone(),
    })
}

/// Percent-encode for a URL query/path component (`{{q|url}}`).
fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

pub fn parse_all(v: &Value) -> Vec<Skill> {
    let mut seen = std::collections::HashSet::new();
    v.as_array()
        .into_iter()
        .flatten()
        .filter_map(Skill::from_value)
        .filter(|sk| seen.insert(sk.name.to_lowercase()))
        .collect()
}

/// Look up by slug, then by title (models sometimes use the display name).
pub fn find<'a>(skills: &'a [Skill], name: &str) -> Option<&'a Skill> {
    let n = name.trim().trim_start_matches('/').to_lowercase();
    skills
        .iter()
        .find(|s| s.name.to_lowercase() == n)
        .or_else(|| skills.iter().find(|s| s.title.to_lowercase() == n))
}

/// The system-prompt section listing skills (empty when there are none).
pub fn index(skills: &[Skill]) -> String {
    if skills.is_empty() {
        return String::new();
    }
    let mut out = String::from(
        "Skills (saved procedures for this phone). Before improvising, check whether one fits: \
         `skill name=…` shows its instructions; skills marked [runs] can be executed with \
         `run_skill name=… params={…}`.",
    );
    for sk in skills.iter().take(MAX_INDEXED) {
        out.push('\n');
        out.push_str(&sk.index_line());
    }
    if skills.len() > MAX_INDEXED {
        out.push_str(&format!(
            "\n(+{} more; `skill` accepts any skill name)",
            skills.len() - MAX_INDEXED
        ));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn web_search() -> Value {
        json!({
            "name": "web-search",
            "title": "Web search",
            "description": "Search the web in the browser",
            "instructions": "Open a search results page for the query.",
            "params": [{"name": "query", "description": "What to search for"}],
            "steps": [
                {"tool": "launch_intent", "args": {"uri": "https://www.google.com/search?q={{query}}"}},
                {"tool": "tap", "args": {"text": "Accept all"}, "optional": true},
            ],
        })
    }

    #[test]
    fn parses_and_indexes_compactly() {
        let skills = parse_all(&json!([
            web_search(),
            {"name": "tidy", "description": "Clear notifications"},
            {"name": "", "description": "dropped"},
            {"name": "Web-Search", "description": "duplicate name dropped"},
        ]));
        assert_eq!(skills.len(), 2);
        let idx = index(&skills);
        assert!(idx.contains("\n- web-search(query) [runs]: Search the web in the browser"));
        assert!(idx.ends_with("\n- tidy: Clear notifications"));
        assert!(index(&[]).is_empty());
        // ~one short line per skill.
        assert!(idx.lines().skip(1).all(|l| l.len() < 120));
    }

    #[test]
    fn expands_params_and_reports_missing_ones() {
        let sk = Skill::from_value(&web_search()).unwrap();
        let steps = sk.expand(&json!({"query": "rust jni"})).unwrap();
        assert_eq!(
            steps[0].args["uri"],
            "https://www.google.com/search?q=rust jni"
        );
        assert!(steps[1].optional);
        let err = sk.expand(&json!({})).unwrap_err();
        assert!(err.contains("missing parameter `query`"), "{err}");
        // Non-string values are accepted as text.
        let n = sk.expand(&json!({"query": 42})).unwrap();
        assert!(n[0].args["uri"].as_str().unwrap().ends_with("q=42"));
    }

    #[test]
    fn defaults_and_unknown_placeholders() {
        let sk = Skill::from_value(&json!({
            "name": "x",
            "params": [{"name": "a", "default": "1"}, {"name": "b", "required": false}],
            "steps": [{"tool": "type_text", "args": {"text": "{{a}}-{{ b }}-{{a}}"}}],
        }))
        .unwrap();
        let st = sk.expand(&json!({})).unwrap();
        assert_eq!(st[0].args["text"], "1--1");
        let bad = Skill::from_value(&json!({
            "name": "y",
            "steps": [{"tool": "tap", "args": {"text": "{{nope}}"}}],
        }))
        .unwrap();
        assert!(bad
            .expand(&json!({}))
            .unwrap_err()
            .contains("unknown parameter `nope`"));
    }

    #[test]
    fn url_filter_encodes_values() {
        let sk = Skill::from_value(&json!({
            "name": "q",
            "params": [{"name": "q"}],
            "steps": [{"tool": "launch_intent",
                "args": {"uri": "https://www.bing.com/search?q={{q|url}}"}}],
        }))
        .unwrap();
        let st = sk.expand(&json!({"q": "cats & dogs/猫"})).unwrap();
        assert_eq!(
            st[0].args["uri"],
            "https://www.bing.com/search?q=cats%20%26%20dogs%2F%E7%8C%AB"
        );
        let bad = Skill::from_value(&json!({
            "name": "b", "params": [{"name": "q"}],
            "steps": [{"tool": "tap", "args": {"text": "{{q|shout}}"}}],
        }))
        .unwrap();
        assert!(bad
            .expand(&json!({"q": "x"}))
            .unwrap_err()
            .contains("unknown filter"));
    }

    #[test]
    fn value_containing_braces_is_not_re_expanded() {
        let sk = Skill::from_value(&json!({
            "name": "z",
            "params": [{"name": "q"}],
            "steps": [{"tool": "type_text", "args": {"text": "{{q}}!"}}],
        }))
        .unwrap();
        let st = sk.expand(&json!({"q": "{{q}}"})).unwrap();
        assert_eq!(st[0].args["text"], "{{q}}!");
    }

    #[test]
    fn find_by_slug_or_title_and_card_shape() {
        let skills = parse_all(&json!([web_search()]));
        assert!(find(&skills, "WEB-SEARCH").is_some());
        assert!(find(&skills, "Web search").is_some());
        assert!(find(&skills, "/web-search").is_some());
        assert!(find(&skills, "nope").is_none());
        let card = skills[0].card();
        assert_eq!(card["params"][0]["name"], "query");
        assert_eq!(card["steps"].as_array().unwrap().len(), 2);
    }
}
