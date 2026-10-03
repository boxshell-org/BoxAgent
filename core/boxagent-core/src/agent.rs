//! The agent loop: prompt → LLM → tool calls (executed via callback into
//! Kotlin) → results fed back → final answer. Emits JSON events through an
//! event sink so the UI can stream progress.

use crate::llm::{self, LlmConfig};
use crate::tools::registry;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use thiserror::Error;

const MAX_TOOL_RESULT: usize = 8000;
const MAX_HISTORY_MSGS: usize = 80;
/// Rough byte budget for the rolling context (≈16k tokens of JSON text).
const MAX_CONTEXT_BYTES: usize = 64_000;
/// Tool results bigger than this that repeat unchanged collapse to a marker.
const DEDUP_MIN: usize = 64;
const CANCEL_POLL: Duration = Duration::from_millis(150);

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

    async fn run_async(&self, started: Instant) -> Result<String, AgentError> {
        let mut messages = Vec::new();
        messages.push(json!({"role": "system", "content": self.cfg.system_prompt}));
        messages.extend(self.cfg.history.iter().cloned());
        messages.push(json!({"role": "user", "content": self.cfg.prompt}));
        // system + history + this run's prompt: never trimmed.
        let head = messages.len();

        let tools = registry::openai_tools(self.cfg.compact_tools);
        let wall = Duration::from_millis(self.cfg.max_wall_ms);
        let mut steps = 0u32;
        let mut turn = 0u32;
        // tool name -> last result body, for collapsing repeated identical
        // outputs (e.g. ui_tree on an unchanged screen).
        let mut last_results: std::collections::HashMap<String, String> =
            std::collections::HashMap::new();

        loop {
            self.check_cancel()?;
            if steps >= self.cfg.max_steps {
                return Err(AgentError::StepLimit);
            }
            let Some(remaining) = wall.checked_sub(started.elapsed()) else {
                return Err(AgentError::TimeBudget);
            };
            turn += 1;

            let request = llm::chat_stream(&self.cfg.llm, &messages, &tools, |delta| {
                self.sink.emit(json!({"type": "text_delta", "text": delta}));
            });
            let mut assistant = tokio::select! {
                r = tokio::time::timeout(remaining, request) => match r {
                    Ok(r) => r?,
                    Err(_) => return Err(AgentError::TimeBudget),
                },
                _ = self.cancelled() => return Err(AgentError::Cancelled),
            };

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

            for call in &assistant.tool_calls {
                self.check_cancel()?;
                steps += 1;
                self.sink.emit(json!({
                    "type": "tool_call",
                    "id": call.id,
                    "name": call.name,
                    "args": call.arguments,
                }));

                let result = if call.name.is_empty() {
                    let r = json!({
                        "ok": false,
                        "error": "malformed tool call: missing function name",
                    })
                    .to_string();
                    self.sink.emit(json!({
                        "type": "tool_result",
                        "id": call.id,
                        "name": call.name,
                        "result": r,
                        "duration_ms": 0,
                    }));
                    r
                } else if call.name == "ask_user" {
                    let q = serde_json::from_str::<Value>(&call.arguments)
                        .ok()
                        .and_then(|v| v["question"].as_str().map(String::from))
                        .unwrap_or_else(|| call.arguments.clone());
                    let answer = self.executor.ask(&q);
                    // Stop pressed while the question was pending.
                    self.check_cancel()?;
                    let r = json!({"ok": true, "answer": answer}).to_string();
                    self.sink.emit(json!({
                        "type": "tool_result",
                        "id": call.id,
                        "name": call.name,
                        "result": r,
                        "duration_ms": 0,
                    }));
                    r
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
                            json!({"ok": true, "note": "unchanged from previous call"}).to_string()
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

            // Keep the window bounded by message count AND bytes. Rare; it
            // breaks the prefix cache either way. Once older results may be
            // gone, "unchanged" markers would point at nothing — reset them.
            if trim_context(&mut messages, head, MAX_HISTORY_MSGS, MAX_CONTEXT_BYTES) {
                last_results.clear();
            }
        }
    }
}

/// Bound the rolling context. `messages[..head]` (system, prior history and
/// this run's prompt) always stays; the newest assistant+tool groups that
/// fit are kept and older groups dropped whole — an assistant `tool_calls`
/// message is never separated from its `tool` replies (providers reject
/// orphans), and no mid-conversation system message is injected (several
/// providers reject those). Returns whether anything changed.
fn trim_context(messages: &mut Vec<Value>, head: usize, max_msgs: usize, max_bytes: usize) -> bool {
    let size = |m: &Value| m.to_string().len();
    let mut bytes: usize = messages.iter().map(size).sum();
    let mut count = messages.len();
    if count <= max_msgs && bytes <= max_bytes {
        return false;
    }
    // A group starts at every non-`tool` message after the head.
    let starts: Vec<usize> = (head..messages.len())
        .filter(|&i| messages[i]["role"] != "tool")
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
    }

    impl ToolExecutor for Rec {
        fn execute(&self, name: &str, args: &str) -> String {
            self.calls.lock().unwrap().push((name.into(), args.into()));
            json!({"ok": true, "tool": name}).to_string()
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
            system_prompt: "sys".into(),
            prompt: "open settings".into(),
            history: vec![json!({"role": "user", "content": "earlier"})],
            max_steps: 10,
            max_wall_ms: 30_000,
            compact_tools: true,
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
                json!({"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"type":"function","function":{"name":"ui_tree","arguments":"{}"}}]}}]}),
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
        assert_eq!(calls[1].0, "ui_tree");

        let bodies = srv.bodies.lock().unwrap().clone();
        assert_eq!(bodies.len(), 2);
        let first = bodies[0]["messages"].as_array().unwrap();
        // system, history, prompt — prompt exactly once.
        assert_eq!(first.len(), 3);
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
}
