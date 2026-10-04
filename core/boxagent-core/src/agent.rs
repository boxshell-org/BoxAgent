//! The agent loop: prompt → LLM → tool calls (executed via callback into
//! Kotlin) → results fed back → final answer. Emits JSON events through an
//! event sink so the UI can stream progress.
//!
//! Token hygiene (screen dedup/elision, images, repeat hints) lives in
//! [`crate::context`]; the system prompt in [`crate::prompt`].

use crate::context::{self, Shaper, Usage};
use crate::llm::{self, LlmConfig, ToolCall};
use crate::prompt;
use crate::skills::{self, Skill, Step};
use crate::tools::registry::{self, Caps};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use thiserror::Error;

const MAX_TOOL_RESULT: usize = 8000;
const MAX_HISTORY_MSGS: usize = 80;
/// Rough byte budget for the rolling context (≈16k tokens of JSON text).
const MAX_CONTEXT_BYTES: usize = 64_000;
const CANCEL_POLL: Duration = Duration::from_millis(150);
/// Upper bound on steps inside one `act` call.
const MAX_ACT_STEPS: usize = 10;
/// Per-step result kept in an `act` summary.
const ACT_STEP_BRIEF: usize = 600;

#[derive(Debug, Clone)]
pub struct AgentConfig {
    pub llm: LlmConfig,
    /// User's custom instructions, appended to the built-in guide.
    pub instructions: String,
    /// One line about the device (model, Android, screen, locale, date).
    pub device_context: String,
    pub caps: Caps,
    pub prompt: String,
    pub history: Vec<Value>,
    pub max_steps: u32,
    pub max_wall_ms: u64,
    /// Send short `summary` strings instead of full `description` in tool
    /// schemas — much smaller `tools[]` payload on endpoints without
    /// server-side prompt caching.
    pub compact_tools: bool,
    /// Enabled skills, most relevant first.
    pub skills: Vec<Skill>,
    /// Run started from a skill (the Skills screen's Run button).
    pub start_skill: Option<StartSkill>,
}

#[derive(Debug, Clone)]
pub struct StartSkill {
    pub name: String,
    pub params: Value,
    /// When the skill's steps fail (or it has none), continue with the
    /// LLM. False when no model is configured: the run just reports.
    pub llm_fallback: bool,
}

#[derive(Debug, Error)]
pub enum AgentError {
    #[error("llm: {0}")]
    Llm(#[from] llm::LlmError),
    #[error("cancelled")]
    Cancelled,
    #[error("step limit reached")]
    StepLimit,
    #[error("time budget exceeded")]
    TimeBudget,
    #[error("executor failed: {0}")]
    Executor(String),
}

/// Implemented on the Kotlin side; synchronously executes one tool and
/// returns a JSON string result `{"ok":true,...}` or `{"ok":false,"error":..}`.
pub trait ToolExecutor: Send {
    fn execute(&self, name: &str, args_json: &str) -> String;
    /// Called for `ask_user`; should block until the user answers.
    fn ask(&self, question: &str) -> String;
}

/// Events emitted to the UI as JSON values.
pub trait EventSink: Send {
    fn emit(&self, event: Value);
}

pub struct Agent<E: ToolExecutor, S: EventSink> {
    cfg: AgentConfig,
    executor: E,
    sink: S,
    cancel: Arc<AtomicBool>,
}

impl<E: ToolExecutor, S: EventSink> Agent<E, S> {
    pub fn new(cfg: AgentConfig, executor: E, sink: S, cancel: Arc<AtomicBool>) -> Self {
        Self {
            cfg,
            executor,
            sink,
            cancel,
        }
    }

    fn check_cancel(&self) -> Result<(), AgentError> {
        if self.cancel.load(Ordering::Relaxed) {
            Err(AgentError::Cancelled)
        } else {
            Ok(())
        }
    }

    pub fn run(&self) -> Result<String, AgentError> {
        let started = Instant::now();
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| AgentError::Executor(e.to_string()))?;
        rt.block_on(self.run_async(started))
    }

    /// Resolves once the cancel flag flips — raced against LLM requests so
    /// Stop takes effect mid-stream instead of after the whole response.
    async fn cancelled(&self) {
        while !self.cancel.load(Ordering::Relaxed) {
            tokio::time::sleep(CANCEL_POLL).await;
        }
    }

    fn emit_call(&self, id: &str, name: &str, args: &str) {
        self.sink.emit(json!({
            "type": "tool_call", "id": id, "name": name, "args": args,
        }));
    }

    fn emit_result(&self, id: &str, name: &str, result: &str, t0: Instant) {
        self.sink.emit(json!({
            "type": "tool_result",
            "id": id,
            "name": name,
            "result": truncate(result, MAX_TOOL_RESULT),
            "duration_ms": t0.elapsed().as_millis() as u64,
        }));
    }

    async fn run_async(&self, started: Instant) -> Result<String, AgentError> {
        let mut caps = self.cfg.caps;
        caps.skills = !self.cfg.skills.is_empty();
        let system = prompt::system_prompt(
            &self.cfg.instructions,
            &self.cfg.device_context,
            &caps,
            &skills::index(&self.cfg.skills),
        );
        let mut messages = Vec::new();
        messages.push(json!({"role": "system", "content": system}));
        messages.extend(self.cfg.history.iter().cloned());
        messages.push(json!({"role": "user", "content": self.cfg.prompt}));
        // system + history + this run's prompt: never trimmed.
        let head = messages.len();

        let tools = registry::openai_tools(self.cfg.compact_tools, &caps);
        let wall = Duration::from_millis(self.cfg.max_wall_ms);
        let mut steps = 0u32;
        let mut turn = 0u32;
        let mut shaper = Shaper::new(caps.vision && caps.a11y);
        let mut total = Usage::default();

        if let Some(start) = &self.cfg.start_skill {
            if let Some(text) =
                self.start_from_skill(start, &caps, &mut messages, &mut shaper, &mut steps)?
            {
                return Ok(text);
            }
        }

        loop {
            self.check_cancel()?;
            if steps >= self.cfg.max_steps {
                return Err(AgentError::StepLimit);
            }
            if wall.checked_sub(started.elapsed()).is_none() {
                return Err(AgentError::TimeBudget);
            }
            turn += 1;

            // One retry on a transport-level failure — flaky connectivity is
            // the norm on phones. Only safe while nothing was streamed: a
            // mid-stream failure would duplicate already-delivered text.
            let mut assistant;
            let mut tried_retry = false;
            loop {
                let mut saw_delta = false;
                let request = llm::chat_stream(&self.cfg.llm, &messages, &tools, |delta| {
                    saw_delta = true;
                    self.sink.emit(json!({"type": "text_delta", "text": delta}));
                });
                let Some(remaining) = wall.checked_sub(started.elapsed()) else {
                    return Err(AgentError::TimeBudget);
                };
                let outcome = tokio::select! {
                    r = tokio::time::timeout(remaining, request) => match r {
                        Ok(r) => r,
                        Err(_) => return Err(AgentError::TimeBudget),
                    },
                    _ = self.cancelled() => return Err(AgentError::Cancelled),
                };
                match outcome {
                    Err(llm::LlmError::Network(msg)) if !tried_retry && !saw_delta => {
                        tried_retry = true;
                        self.sink.emit(json!({
                            "type": "warn",
                            "message": format!("network error ({msg}) — retrying…"),
                        }));
                        tokio::select! {
                            _ = tokio::time::sleep(Duration::from_millis(1200)) => {}
                            _ = self.cancelled() => return Err(AgentError::Cancelled),
                        }
                        let Some(left) = wall.checked_sub(started.elapsed()) else {
                            return Err(AgentError::TimeBudget);
                        };
                        if left < Duration::from_secs(5) {
                            return Err(AgentError::TimeBudget);
                        }
                    }
                    other => {
                        assistant = other?;
                        break;
                    }
                }
            }

            if let Some(u) = &assistant.usage {
                let last = Usage::from_value(u);
                total.add(&last);
                self.sink.emit(json!({
                    "type": "usage",
                    "usage": last.to_json(),
                    "total": total.to_json(),
                }));
            }

            if assistant.tool_calls.is_empty() {
                messages.push(json!({
                    "role": "assistant",
                    "content": assistant.content,
                }));
                self.sink.emit(json!({
                    "type": "done",
                    "text": assistant.content,
                    "steps": steps,
                }));
                return Ok(assistant.content);
            }

            // Some providers omit tool-call ids; `tool` replies must still
            // reference a unique id.
            for (i, c) in assistant.tool_calls.iter_mut().enumerate() {
                if c.id.is_empty() {
                    c.id = format!("call_{turn}_{i}");
                }
            }

            // Record the assistant turn (with tool calls) then execute each.
            messages.push(json!({
                "role": "assistant",
                "content": assistant.content,
                "tool_calls": assistant.tool_calls.iter().map(|c| json!({
                    "id": c.id,
                    "type": "function",
                    "function": {"name": c.name, "arguments": c.arguments},
                })).collect::<Vec<_>>(),
            }));
            if !assistant.content.is_empty() {
                self.sink.emit(json!({
                    "type": "assistant_text",
                    "text": assistant.content,
                }));
            }

            let mut image = None;
            for call in &assistant.tool_calls {
                self.check_cancel()?;
                if call.name == "task_done" {
                    let summary = serde_json::from_str::<Value>(&call.arguments)
                        .ok()
                        .and_then(|v| v["summary"].as_str().map(String::from))
                        .unwrap_or_default();
                    self.sink.emit(json!({
                        "type": "done",
                        "text": summary,
                        "steps": steps,
                    }));
                    return Ok(summary);
                }
                self.emit_call(&call.id, &call.name, &call.arguments);
                let t0 = Instant::now();
                let raw = self.dispatch(call, &caps, &mut steps)?;
                let shaped = shaper.shape(&call.name, &call.arguments, &raw);
                if shaped.image.is_some() {
                    image = shaped.image;
                }
                let content = truncate(&shaped.content, MAX_TOOL_RESULT);
                self.emit_result(&call.id, &call.name, &content, t0);
                messages.push(json!({
                    "role": "tool",
                    "tool_call_id": call.id,
                    "content": content,
                }));
            }

            // The newest screenshot follows the tool replies (tool messages
            // can't carry images); older ones drop to a note.
            if let Some(img) = image {
                messages.push(context::image_message(&img));
                context::elide_old_images(&mut messages);
            }
            context::elide_old_screens(&mut messages);

            // Keep the window bounded by message count AND bytes. Rare; it
            // breaks the prefix cache either way. Once older results may be
            // gone, "unchanged" markers would point at nothing — reset them.
            if trim_context(&mut messages, head, MAX_HISTORY_MSGS, MAX_CONTEXT_BYTES) {
                shaper.reset();
            }
        }
    }

    /// A run launched from a skill. A runnable skill executes before any
    /// LLM request — when every step succeeds the run is done with zero
    /// tokens spent. Otherwise (failure, or an instructions-only skill)
    /// the exchange is left in context as a regular tool call and the LLM
    /// takes over, if allowed. Returns the final text when finished.
    fn start_from_skill(
        &self,
        start: &StartSkill,
        caps: &Caps,
        messages: &mut Vec<Value>,
        shaper: &mut Shaper,
        steps: &mut u32,
    ) -> Result<Option<String>, AgentError> {
        let Some(sk) = skills::find(&self.cfg.skills, &start.name) else {
            return Err(AgentError::Executor(format!(
                "unknown skill `{}`",
                start.name
            )));
        };
        if !sk.runnable() && !start.llm_fallback {
            return Err(AgentError::Executor(format!(
                "skill `{}` has no steps and needs an AI model to follow",
                sk.name
            )));
        }
        let call = if sk.runnable() {
            ToolCall {
                id: "skill_start".into(),
                name: "run_skill".into(),
                arguments: json!({"name": sk.name, "params": start.params}).to_string(),
            }
        } else {
            ToolCall {
                id: "skill_start".into(),
                name: "skill".into(),
                arguments: json!({"name": sk.name}).to_string(),
            }
        };
        messages.push(json!({
            "role": "assistant",
            "content": "",
            "tool_calls": [{
                "id": call.id, "type": "function",
                "function": {"name": call.name, "arguments": call.arguments},
            }],
        }));
        self.emit_call(&call.id, &call.name, &call.arguments);
        let t0 = Instant::now();
        let raw = self.dispatch(&call, caps, steps)?;
        let shaped = shaper.shape(&call.name, &call.arguments, &raw);
        let content = truncate(&shaped.content, MAX_TOOL_RESULT);
        self.emit_result(&call.id, &call.name, &content, t0);
        messages.push(json!({"role": "tool", "tool_call_id": call.id, "content": content}));
        if let Some(img) = shaped.image {
            messages.push(context::image_message(&img));
        }

        if !sk.runnable() {
            return Ok(None);
        }
        let v: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
        if v["ok"].as_bool() == Some(true) {
            let text = format!("Ran skill \u{201c}{}\u{201d}.", sk.title);
            self.sink.emit(json!({
                "type": "done",
                "text": text,
                "steps": *steps,
                "skill": sk.name,
            }));
            return Ok(Some(text));
        }
        if !start.llm_fallback {
            let why = v["steps"]
                .as_array()
                .and_then(|a| a.iter().rev().find(|st| st["ok"] == false))
                .and_then(|st| st["error"].as_str().or_else(|| st["brief"].as_str()))
                .or_else(|| v["error"].as_str())
                .unwrap_or("a step failed");
            return Err(AgentError::Executor(format!(
                "skill \u{201c}{}\u{201d} stopped: {why}",
                sk.title
            )));
        }
        Ok(None)
    }

    /// Run one (non-`task_done`) tool call; returns its raw result JSON.
    fn dispatch(
        &self,
        call: &ToolCall,
        caps: &Caps,
        steps: &mut u32,
    ) -> Result<String, AgentError> {
        let name = call.name.as_str();
        if name.is_empty() {
            *steps += 1;
            return Ok(err_json("malformed tool call: missing function name"));
        }
        if !registry::offered(name, caps) {
            *steps += 1;
            return Ok(err_json(&format!(
                "tool `{name}` is not available in this session; use one of the provided tools"
            )));
        }
        match name {
            "ask_user" => {
                *steps += 1;
                let q = serde_json::from_str::<Value>(&call.arguments)
                    .ok()
                    .and_then(|v| v["question"].as_str().map(String::from))
                    .unwrap_or_else(|| call.arguments.clone());
                let answer = self.executor.ask(&q);
                // Stop pressed while the question was pending.
                self.check_cancel()?;
                Ok(json!({"ok": true, "answer": answer}).to_string())
            }
            "act" => self.run_act(call, caps, steps),
            "skill" => {
                *steps += 1;
                Ok(self.read_skill(call))
            }
            "run_skill" => self.run_skill(call, caps, steps),
            _ => {
                *steps += 1;
                Ok(self.executor.execute(name, &call.arguments))
            }
        }
    }

    fn emit_skill(&self, name: &str, action: &str, ok: bool) {
        self.sink.emit(json!({
            "type": "skill", "name": name, "action": action, "ok": ok,
        }));
    }

    /// `skill`: one skill's full instructions/params/steps.
    fn read_skill(&self, call: &ToolCall) -> String {
        let args: Value = serde_json::from_str(&call.arguments).unwrap_or(Value::Null);
        let name = args["name"].as_str().unwrap_or_default();
        match skills::find(&self.cfg.skills, name) {
            Some(sk) => {
                self.emit_skill(&sk.name, "read", true);
                sk.card().to_string()
            }
            None => json!({
                "ok": false,
                "error": format!("no skill named `{name}`"),
                "available": self.cfg.skills.iter().take(20).map(|s| &s.name).collect::<Vec<_>>(),
            })
            .to_string(),
        }
    }

    /// `run_skill`: expand the skill's steps with the given params and run
    /// them like `act` (each step through the executor). On failure the
    /// model gets the screen and a nudge to finish manually.
    fn run_skill(
        &self,
        call: &ToolCall,
        caps: &Caps,
        steps: &mut u32,
    ) -> Result<String, AgentError> {
        let args: Value = serde_json::from_str(&call.arguments).unwrap_or(Value::Null);
        let name = args["name"].as_str().unwrap_or_default();
        let Some(sk) = skills::find(&self.cfg.skills, name) else {
            *steps += 1;
            return Ok(err_json(&format!("no skill named `{name}`")));
        };
        if !sk.runnable() {
            *steps += 1;
            return Ok(err_json(&format!(
                "skill `{}` has no steps — read it with `skill` and follow its instructions",
                sk.name
            )));
        }
        let plan = match sk.expand(&args["params"]) {
            Ok(p) => p,
            Err(e) => {
                *steps += 1;
                return Ok(err_json(&format!("skill `{}`: {e}", sk.name)));
            }
        };
        // Refuse up front rather than half-running a skill whose later step
        // can't execute here.
        if let Some((i, st)) = plan
            .iter()
            .enumerate()
            .find(|(_, st)| !registry::allowed_in_act(&st.tool, caps))
        {
            *steps += 1;
            return Ok(err_json(&format!(
                "skill `{}` step {} uses `{}`, which isn't available now",
                sk.name,
                i + 1,
                st.tool
            )));
        }
        let mut out = self.run_steps(&call.id, &plan, caps, steps)?;
        let ok = out["ok"].as_bool().unwrap_or(false);
        out["skill"] = json!(sk.name);
        if !ok {
            out["hint"] = json!(
                "The skill stopped at a step that doesn't match this screen. \
                 Continue the task manually from the current screen."
            );
        }
        self.emit_skill(&sk.name, "run", ok);
        Ok(out.to_string())
    }

    /// `act`: run the model's steps in order (see [`Self::run_steps`]).
    fn run_act(&self, call: &ToolCall, caps: &Caps, steps: &mut u32) -> Result<String, AgentError> {
        let args: Value = serde_json::from_str(&call.arguments).unwrap_or(Value::Null);
        let Some(list) = args["steps"].as_array().filter(|l| !l.is_empty()) else {
            *steps += 1;
            return Ok(err_json("act needs a non-empty `steps` array"));
        };
        if list.len() > MAX_ACT_STEPS {
            *steps += 1;
            return Ok(err_json(&format!(
                "act takes at most {MAX_ACT_STEPS} steps"
            )));
        }
        let plan: Vec<Step> = list
            .iter()
            .map(|st| Step {
                tool: st["tool"]
                    .as_str()
                    .or_else(|| st["name"].as_str())
                    .unwrap_or("")
                    .to_string(),
                args: match &st["args"] {
                    Value::Object(m) => Value::Object(m.clone()),
                    _ => json!({}),
                },
                optional: st["optional"].as_bool().unwrap_or(false),
            })
            .collect();
        Ok(self.run_steps(&call.id, &plan, caps, steps)?.to_string())
    }

    /// Run steps in order through the executor (so each one still passes
    /// the app's confirmation gate and audit log); stop at the first
    /// failing non-optional step. Returns one combined result carrying
    /// only the final screen — intermediate steps skip their post-action
    /// screen read (`observe:false`).
    fn run_steps(
        &self,
        call_id: &str,
        plan: &[Step],
        caps: &Caps,
        steps: &mut u32,
    ) -> Result<Value, AgentError> {
        let mut summary = Vec::new();
        let mut screen: Option<Value> = None;
        let mut image: Option<(Value, Value)> = None;
        let mut done = 0usize;
        let mut ran = 0usize;
        let mut ok_all = true;
        for (i, step) in plan.iter().enumerate() {
            self.check_cancel()?;
            let tool = step.tool.as_str();
            if !registry::allowed_in_act(tool, caps) {
                summary.push(json!({"tool": tool, "ok": false,
                    "error": "not allowed here (unknown, unavailable, or an agent tool)"}));
                ok_all = false;
                break;
            }
            let mut a = match &step.args {
                Value::Object(m) => Value::Object(m.clone()),
                _ => json!({}),
            };
            if i + 1 < plan.len() {
                a["observe"] = json!(false);
            }
            let args_s = a.to_string();
            let id = format!("{call_id}.{}", i + 1);
            *steps += 1;
            self.emit_call(&id, tool, &args_s);
            let t0 = Instant::now();
            let raw = self.executor.execute(tool, &args_s);
            ran += 1;
            let mut v: Value = serde_json::from_str(&raw)
                .ok()
                .filter(Value::is_object)
                .unwrap_or_else(|| json!({"ok": true, "output": raw}));
            if let Some(s) = v.as_object_mut().and_then(|o| o.remove("screen")) {
                screen = Some(s);
            }
            if let Some(o) = v.as_object_mut() {
                if let Some(b) = o.remove("image_b64") {
                    image = Some((b, o.get("mime").cloned().unwrap_or(Value::Null)));
                }
            }
            self.emit_result(&id, tool, &v.to_string(), t0);
            let ok = v["ok"].as_bool().unwrap_or(true);
            let mut brief = v.clone();
            brief["tool"] = json!(tool);
            if step.optional && !ok {
                brief["optional"] = json!(true);
            }
            summary.push(match truncate(&brief.to_string(), ACT_STEP_BRIEF) {
                s if s.len() < brief.to_string().len() => {
                    json!({"tool": tool, "ok": ok, "brief": s})
                }
                _ => brief,
            });
            if !ok && !step.optional {
                ok_all = false;
                break;
            }
            if ok {
                done += 1;
            }
        }

        // A step failed after earlier ones may have changed the screen, and
        // no step reported it: read it so the model can recover.
        if !ok_all && screen.is_none() && caps.a11y && ran > 0 {
            let id = format!("{call_id}.screen");
            self.emit_call(&id, "screen", "{}");
            let t0 = Instant::now();
            let raw = self.executor.execute("screen", "{}");
            self.emit_result(&id, "screen", &raw, t0);
            if let Ok(Value::Object(mut o)) = serde_json::from_str::<Value>(&raw) {
                screen = o.remove("screen");
            }
        }

        let mut out = json!({
            "ok": ok_all,
            "completed": done,
            "of": plan.len(),
            "steps": summary,
        });
        if let Some(s) = screen {
            out["screen"] = s;
        }
        if let Some((b64, mime)) = image {
            out["image_b64"] = b64;
            out["mime"] = mime;
        }
        Ok(out)
    }
}

fn err_json(msg: &str) -> String {
    json!({"ok": false, "error": msg}).to_string()
}

/// Bound the rolling context. `messages[..head]` (system, prior history and
/// this run's prompt) always stays; the newest assistant+tool groups that
/// fit are kept and older groups dropped whole — an assistant `tool_calls`
/// message is never separated from its `tool` replies (providers reject
/// orphans), a screenshot message stays with the turn it belongs to, and no
/// mid-conversation system message is injected (several providers reject
/// those). Returns whether anything changed.
fn trim_context(messages: &mut Vec<Value>, head: usize, max_msgs: usize, max_bytes: usize) -> bool {
    let size = context::message_size;
    let mut bytes: usize = messages.iter().map(size).sum();
    let mut count = messages.len();
    if count <= max_msgs && bytes <= max_bytes {
        return false;
    }
    // A group starts at every assistant/user message after the head.
    let starts: Vec<usize> = (head..messages.len())
        .filter(|&i| messages[i]["role"] != "tool" && !context::is_image_message(&messages[i]))
        .collect();
    let mut cut = head;
    // windows(2) never yields the newest group, so it always survives.
    for w in starts.windows(2) {
        if count <= max_msgs && bytes <= max_bytes {
            break;
        }
        bytes -= messages[w[0]..w[1]].iter().map(size).sum::<usize>();
        count -= w[1] - w[0];
        cut = w[1];
    }
    let dropped = cut > head;
    messages.drain(head..cut);
    // Still over the byte budget (a few huge results): shrink older tool
    // messages.
    if bytes > max_bytes {
        shrink_old_tool_results(messages, max_bytes);
        return true;
    }
    dropped
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        return s.to_string();
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}…[truncated {} bytes]", &s[..end], s.len() - end)
}

/// Second-pass shrink when the rolling context still exceeds `budget`:
/// older tool messages (all but the last 4) collapse to a 200-char head.
fn shrink_old_tool_results(messages: &mut [Value], budget: usize) {
    let tool_idx: Vec<usize> = messages
        .iter()
        .enumerate()
        .filter(|(_, m)| m["role"] == "tool")
        .map(|(i, _)| i)
        .collect();
    let keep_full = 4usize;
    for &i in tool_idx
        .iter()
        .take(tool_idx.len().saturating_sub(keep_full))
    {
        if let Some(c) = messages[i]["content"].as_str() {
            if c.len() > 200 {
                messages[i]["content"] = json!(truncate(c, 200));
            }
        }
    }
    // Final resort: if still over budget, hard-truncate every tool message.
    if messages.iter().map(context::message_size).sum::<usize>() > budget {
        for m in messages.iter_mut() {
            if m["role"] == "tool" {
                if let Some(c) = m["content"].as_str().map(String::from) {
                    m["content"] = json!(truncate(&c, 500));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpListener;
    use std::sync::Mutex;

    /// Scripted OpenAI-compatible server: one canned response per request,
    /// every request body recorded.
    struct MockLlm {
        url: String,
        bodies: Arc<Mutex<Vec<Value>>>,
    }

    enum Reply {
        Sse(Vec<String>),
        /// Send these events, then stall (for cancel tests).
        SseStall(Vec<String>),
        Status(u16, String),
    }

    fn sse_line(v: Value) -> String {
        format!("data: {v}\n\n")
    }

    fn mock(replies: Vec<Reply>) -> MockLlm {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/v1", listener.local_addr().unwrap());
        let bodies = Arc::new(Mutex::new(Vec::new()));
        let rec = bodies.clone();
        std::thread::spawn(move || {
            for reply in replies {
                let Ok((mut sock, _)) = listener.accept() else {
                    return;
                };
                let mut rd = BufReader::new(sock.try_clone().unwrap());
                let mut len = 0usize;
                loop {
                    let mut line = String::new();
                    rd.read_line(&mut line).unwrap();
                    if line == "\r\n" || line.is_empty() {
                        break;
                    }
                    let l = line.to_ascii_lowercase();
                    if let Some(v) = l.strip_prefix("content-length:") {
                        len = v.trim().parse().unwrap();
                    }
                }
                let mut body = vec![0u8; len];
                rd.read_exact(&mut body).unwrap();
                rec.lock()
                    .unwrap()
                    .push(serde_json::from_slice(&body).unwrap());
                match reply {
                    Reply::Status(code, text) => {
                        let _ = write!(
                            sock,
                            "HTTP/1.1 {code} Bad\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{text}",
                            text.len()
                        );
                    }
                    Reply::Sse(events) => {
                        let _ = write!(sock, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n");
                        for e in events {
                            let _ = sock.write_all(e.as_bytes());
                            let _ = sock.flush();
                        }
                        let _ = sock.write_all(b"data: [DONE]\n\n");
                    }
                    Reply::SseStall(events) => {
                        let _ = write!(sock, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n");
                        for e in events {
                            let _ = sock.write_all(e.as_bytes());
                            let _ = sock.flush();
                        }
                        std::thread::sleep(Duration::from_secs(20));
                    }
                }
            }
        });
        MockLlm { url, bodies }
    }

    #[derive(Clone, Default)]
    struct Rec {
        calls: Arc<Mutex<Vec<(String, String)>>>,
        events: Arc<Mutex<Vec<Value>>>,
        /// Scripted results per tool name, consumed in order; default
        /// `{"ok":true,"tool":name}`.
        script: Arc<Mutex<std::collections::HashMap<String, Vec<Value>>>>,
    }

    impl Rec {
        fn on(self, name: &str, results: Vec<Value>) -> Self {
            self.script.lock().unwrap().insert(name.into(), results);
            self
        }
    }

    impl ToolExecutor for Rec {
        fn execute(&self, name: &str, args: &str) -> String {
            self.calls.lock().unwrap().push((name.into(), args.into()));
            let mut script = self.script.lock().unwrap();
            match script.get_mut(name) {
                Some(q) if !q.is_empty() => q.remove(0).to_string(),
                _ => json!({"ok": true, "tool": name}).to_string(),
            }
        }
        fn ask(&self, _q: &str) -> String {
            "yes".into()
        }
    }

    impl EventSink for Rec {
        fn emit(&self, e: Value) {
            self.events.lock().unwrap().push(e);
        }
    }

    fn cfg(url: &str) -> AgentConfig {
        AgentConfig {
            llm: LlmConfig {
                base_url: url.into(),
                api_key: "k".into(),
                model: "m".into(),
                temperature: 0.2,
                max_tokens: 256,
                prompt_cache_key: String::new(),
                reasoning_effort: String::new(),
            },
            instructions: "Be brief.".into(),
            device_context: "Test phone, Android 15".into(),
            caps: Caps::default(),
            prompt: "open settings".into(),
            history: vec![json!({"role": "user", "content": "earlier"})],
            max_steps: 10,
            max_wall_ms: 30_000,
            compact_tools: true,
            skills: vec![],
            start_skill: None,
        }
    }

    fn tool_turn() -> Vec<String> {
        vec![
            sse_line(
                json!({"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"app_launch","arguments":""}}]}}]}),
            ),
            sse_line(
                json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"package\":"}}]}}]}),
            ),
            sse_line(
                json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"com.android.settings\"}"}}]}}]}),
            ),
            // Second call with no id at all (some providers).
            sse_line(
                json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"type":"function","function":{"name":"screen","arguments":"{}"}}]}}]}),
            ),
            sse_line(json!({"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]})),
        ]
    }

    #[test]
    fn full_loop_executes_tools_and_feeds_results_back() {
        let srv = mock(vec![
            Reply::Sse(tool_turn()),
            Reply::Sse(vec![
                sse_line(json!({"choices":[{"index":0,"delta":{"content":"已打开"}}]})),
                sse_line(
                    json!({"choices":[{"index":0,"delta":{"content":"设置"},"finish_reason":"stop"}]}),
                ),
            ]),
        ]);
        let rec = Rec::default();
        let agent = Agent::new(
            cfg(&srv.url),
            rec.clone(),
            rec.clone(),
            Arc::new(AtomicBool::new(false)),
        );
        let out = agent.run().unwrap();
        assert_eq!(out, "已打开设置");

        let calls = rec.calls.lock().unwrap().clone();
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].0, "app_launch");
        assert_eq!(calls[0].1, r#"{"package":"com.android.settings"}"#);
        assert_eq!(calls[1].0, "screen");

        let bodies = srv.bodies.lock().unwrap().clone();
        assert_eq!(bodies.len(), 2);
        let first = bodies[0]["messages"].as_array().unwrap();
        // system, history, prompt — prompt exactly once.
        assert_eq!(first.len(), 3);
        let system = first[0]["content"].as_str().unwrap();
        assert!(system.starts_with("You are BoxAgent"));
        assert!(system.contains("Device: Test phone"));
        assert!(system.ends_with("Be brief."));
        // Capability-filtered tools: no playground-only raw dump.
        let offered: Vec<&str> = bodies[0]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["function"]["name"].as_str().unwrap())
            .collect();
        assert!(offered.contains(&"screen") && !offered.contains(&"ui_tree"));
        let second = bodies[1]["messages"].as_array().unwrap();
        let asst = &second[3];
        assert_eq!(asst["role"], "assistant");
        let ids: Vec<&str> = asst["tool_calls"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| c["id"].as_str().unwrap())
            .collect();
        assert_eq!(ids[0], "c1");
        assert!(!ids[1].is_empty(), "missing ids must be synthesized");
        assert_eq!(second[4]["tool_call_id"], ids[0]);
        assert_eq!(second[5]["tool_call_id"], ids[1]);

        let events = rec.events.lock().unwrap().clone();
        let kinds: Vec<&str> = events.iter().map(|e| e["type"].as_str().unwrap()).collect();
        assert_eq!(kinds.iter().filter(|k| **k == "tool_result").count(), 2);
        assert_eq!(*kinds.last().unwrap(), "done");
    }

    #[test]
    fn cancel_interrupts_a_stalled_stream() {
        let srv = mock(vec![Reply::SseStall(vec![sse_line(
            json!({"choices":[{"index":0,"delta":{"content":"thinking"}}]}),
        )])]);
        let rec = Rec::default();
        let cancel = Arc::new(AtomicBool::new(false));
        let flag = cancel.clone();
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(400));
            flag.store(true, Ordering::Relaxed);
        });
        let t0 = Instant::now();
        let err = Agent::new(cfg(&srv.url), rec.clone(), rec, cancel)
            .run()
            .unwrap_err();
        assert!(matches!(err, AgentError::Cancelled), "{err}");
        assert!(t0.elapsed() < Duration::from_secs(3), "{:?}", t0.elapsed());
    }

    #[test]
    fn rejected_param_is_dropped_and_retried() {
        let srv = mock(vec![
            Reply::Status(
                400,
                r#"{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}"#.into(),
            ),
            Reply::Sse(vec![sse_line(
                json!({"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}),
            )]),
        ]);
        let rec = Rec::default();
        let out = Agent::new(
            cfg(&srv.url),
            rec.clone(),
            rec,
            Arc::new(AtomicBool::new(false)),
        )
        .run()
        .unwrap();
        assert_eq!(out, "ok");
        let bodies = srv.bodies.lock().unwrap().clone();
        assert!(bodies[1].get("max_tokens").is_none());
        assert_eq!(bodies[1]["max_completion_tokens"], 256);
    }

    #[test]
    fn trim_keeps_head_and_never_orphans_tool_messages() {
        let mut m = vec![
            json!({"role": "system", "content": "s"}),
            json!({"role": "user", "content": "task"}),
        ];
        for i in 0..30 {
            m.push(json!({"role": "assistant", "content": "", "tool_calls": [
                {"id": format!("a{i}"), "type": "function", "function": {"name": "t", "arguments": "{}"}},
                {"id": format!("b{i}"), "type": "function", "function": {"name": "t", "arguments": "{}"}},
            ]}));
            m.push(json!({"role": "tool", "tool_call_id": format!("a{i}"), "content": "x".repeat(900)}));
            m.push(json!({"role": "tool", "tool_call_id": format!("b{i}"), "content": "y".repeat(900)}));
        }
        assert!(trim_context(&mut m, 2, 80, 20_000));
        assert_eq!(m[0]["role"], "system");
        assert_eq!(m[1]["content"], "task");
        assert_eq!(m[2]["role"], "assistant", "body must start at a group");
        assert!(m
            .iter()
            .all(|x| x["role"] != "system" || x["content"] == "s"));
        let bytes: usize = m.iter().map(|x| x.to_string().len()).sum();
        assert!(bytes <= 20_000, "{bytes}");
        // Every tool message answers a call in the assistant message before it.
        let mut open: Vec<String> = vec![];
        for x in &m[2..] {
            if x["role"] == "assistant" {
                open = x["tool_calls"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|c| c["id"].as_str().unwrap().to_string())
                    .collect();
            } else {
                let id = x["tool_call_id"].as_str().unwrap();
                assert!(open.iter().any(|o| o == id), "orphan {id}");
            }
        }
        // Idempotent once within budget.
        let snapshot = m.clone();
        assert!(!trim_context(&mut m, 2, 80, 20_000));
        assert_eq!(m, snapshot);
    }

    fn calls_turn(calls: &[(&str, &str, Value)]) -> Vec<String> {
        let tc: Vec<Value> = calls
            .iter()
            .enumerate()
            .map(|(i, (id, name, args))| {
                json!({"index": i, "id": id, "type": "function",
                    "function": {"name": name, "arguments": args.to_string()}})
            })
            .collect();
        vec![
            sse_line(json!({"choices":[{"index":0,"delta":{"tool_calls": tc}}]})),
            sse_line(json!({"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]})),
        ]
    }

    fn text_turn(t: &str) -> Vec<String> {
        vec![sse_line(
            json!({"choices":[{"index":0,"delta":{"content": t},"finish_reason":"stop"}]}),
        )]
    }

    fn run_with(srv: &MockLlm, rec: &Rec, caps: Caps) -> Result<String, AgentError> {
        let mut c = cfg(&srv.url);
        c.caps = caps;
        Agent::new(
            c,
            rec.clone(),
            rec.clone(),
            Arc::new(AtomicBool::new(false)),
        )
        .run()
    }

    fn tool_contents(body: &Value) -> Vec<String> {
        body["messages"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|m| m["role"] == "tool")
            .map(|m| m["content"].as_str().unwrap().to_string())
            .collect()
    }

    #[test]
    fn act_runs_steps_through_executor_with_one_final_screen() {
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[(
                "a1",
                "act",
                json!({"steps": [
                    {"tool": "tap", "args": {"ref": 3}},
                    {"tool": "type_text", "args": {"text": "wifi", "submit": true}},
                ]}),
            )])),
            Reply::Sse(text_turn("ok")),
        ]);
        let rec = Rec::default().on(
            "type_text",
            vec![json!({"ok": true, "via": "set_text",
                "screen": "app: settings\n[1] input: wifi @5,5"})],
        );
        assert_eq!(run_with(&srv, &rec, Caps::default()).unwrap(), "ok");

        let calls = rec.calls.lock().unwrap().clone();
        assert_eq!(calls.len(), 2);
        let a0: Value = serde_json::from_str(&calls[0].1).unwrap();
        let a1: Value = serde_json::from_str(&calls[1].1).unwrap();
        assert_eq!(
            a0["observe"], false,
            "intermediate steps skip the screen read"
        );
        assert!(a1.get("observe").is_none(), "final step observes");

        let tools = tool_contents(&srv.bodies.lock().unwrap()[1]);
        assert_eq!(tools.len(), 1);
        assert!(tools[0].starts_with("{\"completed\":2"), "{}", tools[0]);
        assert!(tools[0].ends_with("[screen]\napp: settings\n[1] input: wifi @5,5"));

        let ids: Vec<String> = rec
            .events
            .lock()
            .unwrap()
            .iter()
            .filter(|e| e["type"] == "tool_call")
            .map(|e| e["id"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(ids, vec!["a1", "a1.1", "a1.2"]);
    }

    #[test]
    fn act_stops_at_failure_and_reads_the_screen() {
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[(
                "a1",
                "act",
                json!({"steps": [
                    {"tool": "tap", "args": {"text": "Missing"}},
                    {"tool": "key", "args": {"name": "back"}},
                    {"tool": "task_done", "args": {"summary": "x"}},
                ]}),
            )])),
            Reply::Sse(text_turn("stopped")),
        ]);
        let rec = Rec::default()
            .on(
                "tap",
                vec![json!({"ok": false, "error": "no matching node"})],
            )
            .on("screen", vec![json!({"ok": true, "screen": "app: home"})]);
        run_with(&srv, &rec, Caps::default()).unwrap();
        let names: Vec<String> = rec
            .calls
            .lock()
            .unwrap()
            .iter()
            .map(|c| c.0.clone())
            .collect();
        assert_eq!(
            names,
            vec!["tap", "screen"],
            "key never ran; screen read for recovery"
        );
        let tools = tool_contents(&srv.bodies.lock().unwrap()[1]);
        assert!(tools[0].contains("\"ok\":false"));
        assert!(tools[0].contains("no matching node"));
        assert!(tools[0].ends_with("[screen]\napp: home"));
    }

    #[test]
    fn agent_tools_and_unoffered_tools_are_refused() {
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[
                ("c1", "ui_tree", json!({})),
                (
                    "c2",
                    "act",
                    json!({"steps": [{"tool": "task_done", "args": {}}]}),
                ),
            ])),
            Reply::Sse(text_turn("fine")),
        ]);
        let rec = Rec::default();
        run_with(&srv, &rec, Caps::default()).unwrap();
        assert!(rec.calls.lock().unwrap().is_empty(), "nothing executed");
        let tools = tool_contents(&srv.bodies.lock().unwrap()[1]);
        assert!(tools[0].contains("not available"));
        assert!(tools[1].contains("not allowed here"));
    }

    #[test]
    fn only_the_latest_screen_is_resent() {
        let s1 = "app: a\n[1] button: One @1,1";
        let s2 = "app: a\n[2] button: Two @2,2";
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[("c1", "tap", json!({"ref": 1}))])),
            Reply::Sse(calls_turn(&[("c2", "tap", json!({"ref": 2}))])),
            Reply::Sse(calls_turn(&[("c3", "key", json!({"name": "back"}))])),
            Reply::Sse(text_turn("done")),
        ]);
        let rec = Rec::default()
            .on(
                "tap",
                vec![
                    json!({"ok": true, "screen": s1}),
                    json!({"ok": true, "screen": s2}),
                ],
            )
            .on("key", vec![json!({"ok": true, "screen": s2})]);
        run_with(&srv, &rec, Caps::default()).unwrap();
        let bodies = srv.bodies.lock().unwrap().clone();
        let last = tool_contents(&bodies[3]);
        assert_eq!(last.len(), 3);
        assert!(last[0].ends_with("[screen] omitted (a newer screen follows)"));
        assert!(
            last[1].ends_with(s2),
            "newest full screen kept: {}",
            last[1]
        );
        assert!(last[2].ends_with("[screen] unchanged"));
        // Earlier requests are a strict prefix up to the elided message, so
        // the provider cache stays warm.
        let prev = bodies[2]["messages"].as_array().unwrap();
        let now = bodies[3]["messages"].as_array().unwrap();
        assert_eq!(prev[..prev.len() - 2], now[..prev.len() - 2]);
    }

    #[test]
    fn vision_screenshot_rides_in_a_trailing_user_message() {
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[("c1", "screenshot", json!({}))])),
            Reply::Sse(calls_turn(&[("c2", "screenshot", json!({}))])),
            Reply::Sse(text_turn("seen")),
        ]);
        let shot = |b64: &str| {
            json!({"ok": true, "mime": "image/jpeg", "image_b64": b64,
                "screen": format!("app: {b64}")})
        };
        let rec = Rec::default().on("screenshot", vec![shot("QUFB"), shot("QkJC")]);
        let caps = Caps {
            vision: true,
            ..Caps::default()
        };
        run_with(&srv, &rec, caps).unwrap();
        let bodies = srv.bodies.lock().unwrap().clone();
        let msgs = bodies[2]["messages"].as_array().unwrap();
        let images: Vec<&Value> = msgs
            .iter()
            .filter(|m| context::is_image_message(m))
            .collect();
        assert_eq!(images.len(), 1, "only the newest image is resent");
        let url = images[0]["content"][1]["image_url"]["url"]
            .as_str()
            .unwrap();
        assert_eq!(url, "data:image/jpeg;base64,QkJC");
        // It directly follows the tool reply it belongs to.
        assert_eq!(msgs.last().unwrap(), images[0]);
        assert!(bodies[0]["messages"][0]["content"]
            .as_str()
            .unwrap()
            .contains("You can see images"));
        // No base64 inside tool messages.
        assert!(tool_contents(&bodies[2])
            .iter()
            .all(|c| !c.contains("QkJC\"")));
    }

    #[test]
    fn context_growth_per_action_stays_small() {
        // 8 actions, each returning a different ~850-byte screen (the size
        // of a 10-row settings list in the compact format).
        let screen = |i: usize| {
            let mut s = format!("app: com.android.settings/SubSettings{i} · 1080x2400");
            for r in 0..10 {
                s.push_str(&format!(
                    "\n[{}] item: Option {r} of page {i} · Summary text {r} @540,{}",
                    i * 10 + r,
                    400 + r * 200
                ));
            }
            s
        };
        let n = 8;
        let mut replies: Vec<Reply> = (0..n)
            .map(|i| {
                Reply::Sse(calls_turn(&[(
                    &format!("c{i}"),
                    "tap",
                    json!({"ref": i * 10 + 1}),
                )]))
            })
            .collect();
        replies.push(Reply::Sse(text_turn("done")));
        let srv = mock(replies);
        let rec = Rec::default().on(
            "tap",
            (0..n)
                .map(|i| json!({"ok": true, "via": "node_click", "screen": screen(i)}))
                .collect(),
        );
        run_with(&srv, &rec, Caps::default()).unwrap();
        let sizes: Vec<usize> = srv
            .bodies
            .lock()
            .unwrap()
            .iter()
            .map(|b| b["messages"].to_string().len())
            .collect();
        let per_action = (sizes[n] - sizes[1]) / (n - 1);
        println!(
            "messages bytes per request: {sizes:?}; growth/action ≈ {per_action}B; screen {}B",
            screen(0).len()
        );
        // Without elision each action would add its whole screen (~850B)
        // to every later request.
        assert!(per_action < 300, "growth {per_action}B per action");
    }

    fn find_setting_skill() -> Skill {
        Skill::from_value(&json!({
            "name": "find-setting",
            "title": "Find a setting",
            "description": "Open a setting by searching in Settings",
            "instructions": "Open Settings, tap search, type the term, open the best match.",
            "params": [{"name": "term", "description": "Setting to find"}],
            "steps": [
                {"tool": "launch_intent", "args": {"uri": "android.settings.SETTINGS"}},
                {"tool": "tap", "args": {"text": "Search settings"}},
                {"tool": "tap", "args": {"text": "Allow"}, "optional": true},
                {"tool": "type_text", "args": {"text": "{{term}}", "submit": true}},
            ],
        }))
        .unwrap()
    }

    fn run_cfg(
        srv_url: &str,
        rec: &Rec,
        f: impl FnOnce(&mut AgentConfig),
    ) -> Result<String, AgentError> {
        let mut c = cfg(srv_url);
        f(&mut c);
        Agent::new(
            c,
            rec.clone(),
            rec.clone(),
            Arc::new(AtomicBool::new(false)),
        )
        .run()
    }

    #[test]
    fn skills_are_indexed_read_and_run_by_the_model() {
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[(
                "c1",
                "skill",
                json!({"name": "find-setting"}),
            )])),
            Reply::Sse(calls_turn(&[(
                "c2",
                "run_skill",
                json!({"name": "find-setting", "params": {"term": "Wi-Fi"}}),
            )])),
            Reply::Sse(text_turn("opened")),
        ]);
        let rec = Rec::default()
            .on(
                "tap",
                vec![
                    json!({"ok": true}),
                    json!({"ok": false, "error": "no matching element"}),
                ],
            )
            .on(
                "type_text",
                vec![json!({"ok": true, "screen": "app: settings\n[9] item: Wi-Fi @5,5"})],
            );
        run_cfg(&srv.url, &rec, |c| c.skills = vec![find_setting_skill()]).unwrap();

        let bodies = srv.bodies.lock().unwrap().clone();
        let system = bodies[0]["messages"][0]["content"].as_str().unwrap();
        assert!(system
            .contains("\n- find-setting(term) [runs]: Open a setting by searching in Settings"));
        let offered: Vec<&str> = bodies[0]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["function"]["name"].as_str().unwrap())
            .collect();
        assert!(offered.contains(&"skill") && offered.contains(&"run_skill"));

        // `skill` returned the instructions.
        let t1 = tool_contents(&bodies[1]);
        assert!(t1[0].contains("Open Settings, tap search"));

        // run_skill: params filled, optional failing step skipped, only the
        // final step observed.
        let calls = rec.calls.lock().unwrap().clone();
        let names: Vec<&str> = calls.iter().map(|c| c.0.as_str()).collect();
        assert_eq!(names, vec!["launch_intent", "tap", "tap", "type_text"]);
        let last: Value = serde_json::from_str(&calls[3].1).unwrap();
        assert_eq!(last["text"], "Wi-Fi");
        assert!(last.get("observe").is_none());
        let first: Value = serde_json::from_str(&calls[0].1).unwrap();
        assert_eq!(first["observe"], false);
        let t2 = tool_contents(&bodies[2]);
        assert!(t2[1].contains("\"ok\":true") && t2[1].contains("\"skill\":\"find-setting\""));
        assert!(t2[1].ends_with("[screen]\napp: settings\n[9] item: Wi-Fi @5,5"));

        let events = rec.events.lock().unwrap().clone();
        let skill_events: Vec<(String, bool)> = events
            .iter()
            .filter(|e| e["type"] == "skill")
            .map(|e| {
                (
                    e["action"].as_str().unwrap().to_string(),
                    e["ok"].as_bool().unwrap(),
                )
            })
            .collect();
        assert_eq!(
            skill_events,
            vec![("read".into(), true), ("run".into(), true)]
        );
    }

    #[test]
    fn skill_run_from_ui_needs_no_llm_when_steps_succeed() {
        let srv = mock(vec![]);
        let rec = Rec::default();
        let out = run_cfg(&srv.url, &rec, |c| {
            c.skills = vec![find_setting_skill()];
            c.start_skill = Some(StartSkill {
                name: "find-setting".into(),
                params: json!({"term": "Battery"}),
                llm_fallback: true,
            });
        })
        .unwrap();
        assert!(out.contains("Find a setting"));
        assert!(srv.bodies.lock().unwrap().is_empty(), "zero LLM requests");
        assert_eq!(rec.calls.lock().unwrap().len(), 4);
        let done = rec
            .events
            .lock()
            .unwrap()
            .iter()
            .rev()
            .find(|e| e["type"] == "done")
            .cloned()
            .unwrap();
        assert_eq!(done["skill"], "find-setting");
    }

    #[test]
    fn failed_skill_hands_over_to_the_llm_with_context() {
        let srv = mock(vec![Reply::Sse(text_turn("finished it by hand"))]);
        let rec = Rec::default()
            .on(
                "tap",
                vec![json!({"ok": false, "error": "no matching element"})],
            )
            .on(
                "screen",
                vec![json!({"ok": true, "screen": "app: settings"})],
            );
        let out = run_cfg(&srv.url, &rec, |c| {
            c.skills = vec![find_setting_skill()];
            c.start_skill = Some(StartSkill {
                name: "find-setting".into(),
                params: json!({"term": "Battery"}),
                llm_fallback: true,
            });
        })
        .unwrap();
        assert_eq!(out, "finished it by hand");
        let bodies = srv.bodies.lock().unwrap().clone();
        assert_eq!(bodies.len(), 1);
        let msgs = bodies[0]["messages"].as_array().unwrap();
        // prompt, synthetic run_skill call, its result with a nudge.
        assert_eq!(
            msgs[msgs.len() - 2]["tool_calls"][0]["function"]["name"],
            "run_skill"
        );
        let result = msgs.last().unwrap()["content"].as_str().unwrap();
        assert!(result.contains("Continue the task manually"));
        assert!(result.ends_with("[screen]\napp: settings"));
    }

    #[test]
    fn failed_skill_without_llm_reports_why() {
        let srv = mock(vec![]);
        let rec = Rec::default().on(
            "tap",
            vec![json!({"ok": false, "error": "no matching element"})],
        );
        let err = run_cfg(&srv.url, &rec, |c| {
            c.skills = vec![find_setting_skill()];
            c.start_skill = Some(StartSkill {
                name: "find-setting".into(),
                params: json!({"term": "Battery"}),
                llm_fallback: false,
            });
        })
        .unwrap_err();
        assert!(err.to_string().contains("no matching element"), "{err}");
        assert!(srv.bodies.lock().unwrap().is_empty());
    }

    #[test]
    fn run_skill_validates_before_running_anything() {
        let srv = mock(vec![
            Reply::Sse(calls_turn(&[(
                "c1",
                "run_skill",
                json!({"name": "find-setting"}),
            )])),
            Reply::Sse(text_turn("ok")),
        ]);
        let rec = Rec::default();
        // Shell-less, a11y-less run: a skill step needing a11y is refused too.
        run_cfg(&srv.url, &rec, |c| c.skills = vec![find_setting_skill()]).unwrap();
        assert!(rec.calls.lock().unwrap().is_empty());
        let t = tool_contents(&srv.bodies.lock().unwrap()[1]);
        assert!(t[0].contains("missing parameter `term`"), "{}", t[0]);

        let srv = mock(vec![
            Reply::Sse(calls_turn(&[(
                "c1",
                "run_skill",
                json!({"name": "find-setting", "params": {"term": "x"}}),
            )])),
            Reply::Sse(text_turn("ok")),
        ]);
        let rec = Rec::default();
        run_cfg(&srv.url, &rec, |c| {
            c.skills = vec![find_setting_skill()];
            c.caps.a11y = false;
        })
        .unwrap();
        assert!(rec.calls.lock().unwrap().is_empty(), "nothing half-run");
        let t = tool_contents(&srv.bodies.lock().unwrap()[1]);
        assert!(t[0].contains("isn't available now"), "{}", t[0]);
    }
}
