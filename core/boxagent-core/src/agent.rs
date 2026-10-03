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

#[derive(Debug, Clone)]
pub struct AgentConfig {
    pub llm: LlmConfig,
    pub system_prompt: String,
    pub prompt: String,
    pub history: Vec<Value>,
    pub max_steps: u32,
    pub max_wall_ms: u64,
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

        let tools = registry::openai_tools();
        let mut steps = 0u32;

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

            let assistant = llm::chat_stream(
                &self.cfg.llm,
                &messages,
                &tools,
                |delta| {
                    self.sink
                        .emit(json!({"type": "text_delta", "text": delta}));
                },
            )
            .await?;

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
                    let out = self.executor.execute(&call.name, &call.arguments);
                    self.sink.emit(json!({
                        "type": "tool_result",
                        "id": call.id,
                        "name": call.name,
                        "result": truncate(&out, MAX_TOOL_RESULT),
                        "duration_ms": t0.elapsed().as_millis() as u64,
                    }));
                    out
                };

                messages.push(json!({
                    "role": "tool",
                    "tool_call_id": call.id,
                    "content": truncate(&result, MAX_TOOL_RESULT),
                }));
            }

            // Keep the window bounded: drop oldest tool messages, keeping
            // system + first user + last N messages.
            if messages.len() > MAX_HISTORY_MSGS {
                let keep_head = 2usize;
                let tail = MAX_HISTORY_MSGS - keep_head;
                messages = [
                    messages[..keep_head].to_vec(),
                    vec![json!({
                        "role": "system",
                        "content": "(older tool results trimmed for context budget)",
                    })],
                    messages[messages.len() - tail..].to_vec(),
                ]
                .concat();
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
