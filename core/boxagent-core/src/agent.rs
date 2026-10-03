//! The agent loop: prompt → LLM → tool calls (executed via callback into
//! Kotlin) → results fed back → final answer. Emits JSON events through an
//! event sink so the UI can stream progress.

use crate::llm::{self, LlmConfig};
use crate::tools::registry;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;
use thiserror::Error;

const MAX_TOOL_RESULT: usize = 8000;
const MAX_HISTORY_MSGS: usize = 80;
/// Rough byte budget for the rolling context (≈16k tokens of JSON text).
const MAX_CONTEXT_BYTES: usize = 64_000;
/// Tool results bigger than this that repeat unchanged collapse to a marker.
const DEDUP_MIN: usize = 64;

#[derive(Debug, Clone)]
pub struct AgentConfig {
    pub llm: LlmConfig,
    pub system_prompt: String,
    pub prompt: String,
    pub history: Vec<Value>,
    pub max_steps: u32,
    pub max_wall_ms: u64,
    /// Send short `summary` strings instead of full `description` in tool
    /// schemas — much smaller `tools[]` payload on endpoints without
    /// server-side prompt caching.
    pub compact_tools: bool,
}

#[derive(Debug, Error)]
pub enum AgentError {
    #[error("llm: {0}")]
    Llm(#[from] llm::LlmError),
    #[error("cancelled")]
    Cancelled,
    #[error("step limit reached")]
    StepLimit,
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

    async fn run_async(&self, started: Instant) -> Result<String, AgentError> {
        let mut messages = Vec::new();
        messages.push(json!({"role": "system", "content": self.cfg.system_prompt}));
        messages.extend(self.cfg.history.iter().cloned());
        messages.push(json!({"role": "user", "content": self.cfg.prompt}));

        let tools = registry::openai_tools(self.cfg.compact_tools);
        let mut steps = 0u32;
        // tool name -> last result body, for collapsing repeated identical
        // outputs (e.g. ui_tree on an unchanged screen).
        let mut last_results: std::collections::HashMap<String, String> =
            std::collections::HashMap::new();

        loop {
            self.check_cancel()?;
            if steps >= self.cfg.max_steps {
                return Err(AgentError::StepLimit);
            }
            if started.elapsed().as_millis() as u64 > self.cfg.max_wall_ms {
                self.sink.emit(json!({
                    "type": "warn", "message": "time budget exceeded"
                }));
                return Err(AgentError::StepLimit);
            }

            let assistant = llm::chat_stream(&self.cfg.llm, &messages, &tools, |delta| {
                self.sink.emit(json!({"type": "text_delta", "text": delta}));
            })
            .await?;

            if let Some(u) = &assistant.usage {
                self.sink.emit(json!({"type": "usage", "usage": u}));
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

            for call in &assistant.tool_calls {
                self.check_cancel()?;
                steps += 1;
                self.sink.emit(json!({
                    "type": "tool_call",
                    "id": call.id,
                    "name": call.name,
                    "args": call.arguments,
                }));

                let result = if call.name == "ask_user" {
                    let q = serde_json::from_str::<Value>(&call.arguments)
                        .ok()
                        .and_then(|v| v["question"].as_str().map(String::from))
                        .unwrap_or_else(|| call.arguments.clone());
                    let answer = self.executor.ask(&q);
                    json!({"ok": true, "answer": answer}).to_string()
                } else if call.name == "task_done" {
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
                } else {
                    let t0 = Instant::now();
                    let out = sanitize_result(self.executor.execute(&call.name, &call.arguments));
                    let feed = match last_results.get(&call.name) {
                        // Identical large result again (unchanged screen etc.):
                        // feed a marker; the real content is still in context.
                        Some(prev) if *prev == out && out.len() > DEDUP_MIN => {
                            json!({"ok": true, "note": "unchanged from previous call"})
                                .to_string()
                        }
                        _ => out.clone(),
                    };
                    last_results.insert(call.name.clone(), out.clone());
                    self.sink.emit(json!({
                        "type": "tool_result",
                        "id": call.id,
                        "name": call.name,
                        "result": truncate(&feed, MAX_TOOL_RESULT),
                        "duration_ms": t0.elapsed().as_millis() as u64,
                    }));
                    feed
                };

                messages.push(json!({
                    "role": "tool",
                    "tool_call_id": call.id,
                    "content": truncate(&result, MAX_TOOL_RESULT),
                }));
            }

            // Keep the window bounded, by message count AND by bytes:
            // drop the oldest tool exchanges, keeping the head (system +
            // first user prompt). Rare; it breaks prefix cache either way.
            let too_many = messages.len() > MAX_HISTORY_MSGS;
            let too_big: usize = messages.iter().map(|m| m.to_string().len()).sum();
            if too_many || too_big > MAX_CONTEXT_BYTES {
                let keep_head = 2usize;
                let tail = (MAX_HISTORY_MSGS - keep_head).min(messages.len().saturating_sub(keep_head));
                messages = [
                    messages[..keep_head].to_vec(),
                    vec![json!({
                        "role": "system",
                        "content": "(older tool results trimmed for context budget)",
                    })],
                    messages[messages.len() - tail..].to_vec(),
                ]
                .concat();
                // Still over the byte budget (a few huge results): shrink
                // older tool messages to their first 200 chars.
                shrink_old_tool_results(&mut messages, MAX_CONTEXT_BYTES);
            }
        }
    }
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

/// Strip binary payloads (base64 screenshots / file dumps) from tool
/// results — a text-only chat model can't see them and they cost tens of
/// thousands of tokens every request. Keeps a short receipt instead.
fn sanitize_result(raw: String) -> String {
    let Ok(mut v) = serde_json::from_str::<Value>(&raw) else {
        return raw;
    };
    let Some(obj) = v.as_object_mut() else {
        return raw;
    };
    if let Some(b64) = obj.remove("data_b64") {
        let bytes = b64.as_str().map(|s| s.len() * 3 / 4).unwrap_or(0);
        obj.insert("bytes".into(), json!(bytes));
        obj.insert(
            "note".into(),
            json!("binary data omitted; use ui_tree/ui_find for screen structure"),
        );
    }
    v.to_string()
}

/// Second-pass shrink when the rolling context still exceeds `budget`:
/// older tool messages (all but the last 4) collapse to a 200-char head.
fn shrink_old_tool_results(messages: &mut Vec<Value>, budget: usize) {
    let tool_idx: Vec<usize> = messages
        .iter()
        .enumerate()
        .filter(|(_, m)| m["role"] == "tool")
        .map(|(i, _)| i)
        .collect();
    let keep_full = 4usize;
    for &i in tool_idx.iter().take(tool_idx.len().saturating_sub(keep_full)) {
        if let Some(c) = messages[i]["content"].as_str() {
            if c.len() > 200 {
                messages[i]["content"] = json!(truncate(c, 200));
            }
        }
    }
    // Final resort: if still over budget, hard-truncate every tool message.
    if messages.iter().map(|m| m.to_string().len()).sum::<usize>() > budget {
        for m in messages.iter_mut() {
            if m["role"] == "tool" {
                if let Some(c) = m["content"].as_str().map(String::from) {
                    m["content"] = json!(truncate(&c, 500));
                }
            }
        }
    }
}
