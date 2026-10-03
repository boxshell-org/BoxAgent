//! OpenAI-compatible chat-completions client with SSE streaming and
//! function/tool-calling accumulation.

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::Duration;
use thiserror::Error;

#[derive(Debug, Clone)]
pub struct LlmConfig {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    pub temperature: f64,
    pub max_tokens: u32,
    /// Stable per-conversation key for provider-side prefix-cache routing
    /// (OpenAI `prompt_cache_key`; ignored by servers that don't know it).
    pub prompt_cache_key: String,
    /// Optional `reasoning_effort` passthrough ("low"/"medium"/"high").
    pub reasoning_effort: String,
}

fn is_anthropic_compat(cfg: &LlmConfig) -> bool {
    cfg.base_url.contains("anthropic")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: String,
}

#[derive(Debug, Clone, Default)]
pub struct AssistantMsg {
    pub content: String,
    pub tool_calls: Vec<ToolCall>,
    pub finish_reason: Option<String>,
    /// `usage` object from the final stream chunk (when requested).
    pub usage: Option<Value>,
}

#[derive(Debug, Error)]
pub enum LlmError {
    #[error("http {status}: {body}")]
    Http { status: u16, body: String },
    #[error("network: {0}")]
    Network(String),
    #[error("bad stream: {0}")]
    Stream(String),
}

const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
/// Max silence between response chunks. A total-request timeout would cut
/// off long (reasoning) generations that are still making progress.
const READ_TIMEOUT: Duration = Duration::from_secs(120);
/// Optional request params that some OpenAI-compatible servers or models
/// reject (o-series: `max_tokens`/`temperature`; many gateways:
/// `stream_options`/`parallel_tool_calls`). A 400 naming one drops it —
/// `max_tokens` is renamed to `max_completion_tokens` — and retries.
const OPTIONAL_PARAMS: &[&str] = &[
    "stream_options",
    "parallel_tool_calls",
    "prompt_cache_key",
    "reasoning_effort",
    "temperature",
    "max_tokens",
    "max_completion_tokens",
];
const MAX_ADAPT_RETRIES: usize = 4;
/// Cap on a non-SSE fallback body / error body we keep in memory.
const MAX_FALLBACK_BODY: usize = 4 * 1024 * 1024;

fn endpoint(base: &str) -> String {
    format!("{}/chat/completions", base.trim().trim_end_matches('/'))
}

fn client(total: Option<Duration>) -> Result<reqwest::Client, LlmError> {
    let mut b = reqwest::Client::builder()
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(READ_TIMEOUT);
    if let Some(t) = total {
        b = b.timeout(t);
    }
    b.build().map_err(net_err)
}

/// reqwest's Display hides the cause ("error sending request for url");
/// walk the source chain so users see "connection refused" / DNS errors.
fn net_err(e: reqwest::Error) -> LlmError {
    let mut msg = e.to_string();
    let mut src = std::error::Error::source(&e);
    while let Some(s) = src {
        let s_msg = s.to_string();
        if !msg.contains(&s_msg) {
            msg.push_str(": ");
            msg.push_str(&s_msg);
        }
        src = s.source();
    }
    LlmError::Network(msg)
}

fn clip(s: String, max: usize) -> String {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}…", &s[..end])
}

/// Drop (or rename) one optional parameter the server rejected. Returns
/// false when the error names nothing we can adapt.
fn adapt_body(body: &mut Value, status: u16, err: &str) -> bool {
    if status != 400 && status != 422 {
        return false;
    }
    let Some(obj) = body.as_object_mut() else {
        return false;
    };
    for p in OPTIONAL_PARAMS {
        if !obj.contains_key(*p) || !err.contains(p) {
            continue;
        }
        let v = obj.remove(*p).unwrap_or(Value::Null);
        if *p == "max_tokens" {
            obj.insert("max_completion_tokens".into(), v);
        }
        tracing::warn!("llm: server rejected `{p}`, retrying without it");
        return true;
    }
    false
}

/// POST with the unsupported-parameter fallback. `body` keeps the adapted
/// shape so later turns don't repeat the failed attempt.
async fn post(
    client: &reqwest::Client,
    cfg: &LlmConfig,
    body: &mut Value,
) -> Result<reqwest::Response, LlmError> {
    let mut retries = 0;
    loop {
        let mut req = client.post(endpoint(&cfg.base_url)).json(&*body);
        if !cfg.api_key.is_empty() {
            req = req.bearer_auth(&cfg.api_key);
        }
        let resp = req.send().await.map_err(net_err)?;
        let status = resp.status();
        if status.is_success() {
            return Ok(resp);
        }
        let code = status.as_u16();
        let text = resp.text().await.unwrap_or_default();
        if retries < MAX_ADAPT_RETRIES && adapt_body(body, code, &text) {
            retries += 1;
            continue;
        }
        return Err(LlmError::Http {
            status: code,
            body: clip(text, 2000),
        });
    }
}

/// Request body for one streamed turn.
pub fn build_body(cfg: &LlmConfig, messages: &[Value], tools: &Value) -> Value {
    let mut body = json!({
        "model": cfg.model,
        "messages": messages,
        "tools": tools,
        "temperature": cfg.temperature,
        "max_tokens": cfg.max_tokens,
        "stream": true,
        // Ask for usage in the last chunk (cached token counts included)
        "stream_options": {"include_usage": true},
        // Batching independent calls into one turn avoids whole-context resends
        "parallel_tool_calls": true,
    });
    if !cfg.prompt_cache_key.is_empty() {
        body["prompt_cache_key"] = json!(cfg.prompt_cache_key);
    }
    if !cfg.reasoning_effort.is_empty() {
        body["reasoning_effort"] = json!(cfg.reasoning_effort);
    }
    // Anthropic-style breakpoints: cache the static prefix (system prompt and
    // the tool catalog) on endpoints that honor `cache_control`.
    if is_anthropic_compat(cfg) {
        for m in body["messages"].as_array_mut().into_iter().flatten() {
            if m["role"] == "system" {
                let text = m["content"].clone();
                m["content"] = json!([{
                    "type": "text", "text": text,
                    "cache_control": {"type": "ephemeral"},
                }]);
            }
        }
        if let Some(ts) = body["tools"].as_array_mut() {
            if let Some(last) = ts.last_mut() {
                last["cache_control"] = json!({"type": "ephemeral"});
            }
        }
    }
    body
}

/// Line-oriented SSE splitter over raw bytes. Splitting on `\n` before
/// UTF-8 decoding keeps multi-byte characters (CJK, emoji) that straddle
/// network chunks intact.
#[derive(Default)]
pub struct SseParser {
    buf: Vec<u8>,
    /// Whether any `data:` line was seen (else the body may be plain JSON).
    pub saw_data: bool,
    /// Every byte when no `data:` line has appeared yet (bounded), for the
    /// non-streaming fallback.
    raw: Vec<u8>,
}

impl SseParser {
    /// Feed bytes; returns the complete `data:` payloads, in order.
    pub fn push(&mut self, chunk: &[u8]) -> Vec<String> {
        if !self.saw_data && self.raw.len() < MAX_FALLBACK_BODY {
            self.raw.extend_from_slice(chunk);
        }
        self.buf.extend_from_slice(chunk);
        let mut out = Vec::new();
        while let Some(nl) = self.buf.iter().position(|&b| b == b'\n') {
            let line: Vec<u8> = self.buf.drain(..=nl).collect();
            self.take_line(&line, &mut out);
        }
        out
    }

    /// Flush a trailing line that had no final newline.
    pub fn finish(&mut self) -> Vec<String> {
        let mut out = Vec::new();
        let rest = std::mem::take(&mut self.buf);
        if !rest.is_empty() {
            self.take_line(&rest, &mut out);
        }
        out
    }

    fn take_line(&mut self, line: &[u8], out: &mut Vec<String>) {
        let line = String::from_utf8_lossy(line);
        let line = line.trim_end_matches(['\n', '\r']);
        if let Some(d) = line.strip_prefix("data:") {
            self.saw_data = true;
            self.raw.clear();
            out.push(d.trim().to_string());
        }
    }

    fn raw_body(&self) -> &[u8] {
        &self.raw
    }
}

/// One streaming chat completion. `on_delta` receives assistant text pieces
/// as they arrive; tool-call arguments are accumulated silently.
pub async fn chat_stream<F>(
    cfg: &LlmConfig,
    messages: &[Value],
    tools: &Value,
    mut on_delta: F,
) -> Result<AssistantMsg, LlmError>
where
    F: FnMut(&str),
{
    let client = client(None)?;
    let mut body = build_body(cfg, messages, tools);
    let resp = post(&client, cfg, &mut body).await?;

    let mut acc = AssistantMsg::default();
    let mut sse = SseParser::default();
    let mut stream = resp.bytes_stream();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(net_err)?;
        for data in sse.push(&chunk) {
            handle_data(&data, &mut acc, &mut on_delta)?;
        }
    }
    for data in sse.finish() {
        handle_data(&data, &mut acc, &mut on_delta)?;
    }

    // Servers that ignore `stream: true` (some local/llama.cpp builds when
    // tools are present) answer with one plain JSON completion.
    if !sse.saw_data {
        let raw = sse.raw_body();
        if raw.iter().all(|b| b.is_ascii_whitespace()) {
            return Err(LlmError::Stream("empty response".into()));
        }
        let v: Value = serde_json::from_slice(raw).map_err(|e| {
            LlmError::Stream(format!(
                "{e}: {}",
                clip(String::from_utf8_lossy(raw).into_owned(), 300)
            ))
        })?;
        parse_completion(&v, &mut acc, &mut on_delta)?;
    }

    Ok(acc)
}

fn handle_data<F: FnMut(&str)>(
    data: &str,
    acc: &mut AssistantMsg,
    on_delta: &mut F,
) -> Result<(), LlmError> {
    if data.is_empty() || data == "[DONE]" {
        return Ok(());
    }
    parse_delta(data, acc, on_delta)
}

fn error_message(v: &Value) -> Option<String> {
    let e = v.get("error")?;
    if e.is_null() {
        return None;
    }
    Some(
        e.get("message")
            .and_then(Value::as_str)
            .map(String::from)
            .unwrap_or_else(|| e.to_string()),
    )
}

/// Tool-call `arguments` normally arrive as a JSON string; a few servers
/// send an object instead.
fn args_text(a: &Value) -> Option<String> {
    match a {
        Value::String(s) => Some(s.clone()),
        Value::Null => None,
        other => Some(other.to_string()),
    }
}

pub(crate) fn parse_delta<F: FnMut(&str)>(
    data: &str,
    acc: &mut AssistantMsg,
    on_delta: &mut F,
) -> Result<(), LlmError> {
    let v: Value = serde_json::from_str(data)
        .map_err(|e| LlmError::Stream(format!("{e}: {}", clip(data.to_string(), 300))))?;

    // Mid-stream failures arrive as `data: {"error": {...}}`.
    if let Some(msg) = error_message(&v) {
        return Err(LlmError::Stream(msg));
    }
    // Final chunk may carry usage without choices.
    if v["usage"].is_object() {
        acc.usage = Some(v["usage"].clone());
    }
    let Some(choice) = v["choices"].get(0) else {
        return Ok(());
    };

    if let Some(fr) = choice["finish_reason"].as_str() {
        acc.finish_reason = Some(fr.to_string());
    }

    // Note: `Value` indexing (`v["k"]`) is total and yields Null for a
    // missing key; `Map` indexing panics. Stick to `Value`/`get` here —
    // OpenAI omits `id`/`name` after a tool call's first chunk.
    let delta = &choice["delta"];
    if let Some(c) = delta["content"].as_str() {
        if !c.is_empty() {
            acc.content.push_str(c);
            on_delta(c);
        }
    }

    if let Some(calls) = delta["tool_calls"].as_array() {
        for call in calls {
            let id = call["id"].as_str().unwrap_or("");
            let idx = match call["index"].as_u64() {
                Some(i) => i as usize,
                // No index (some non-OpenAI servers): a new id opens a new
                // call, anything else continues the latest one.
                None => match acc.tool_calls.last() {
                    Some(last) if id.is_empty() || last.id == id => acc.tool_calls.len() - 1,
                    _ => acc.tool_calls.len(),
                },
            };
            while acc.tool_calls.len() <= idx {
                acc.tool_calls.push(ToolCall {
                    id: String::new(),
                    name: String::new(),
                    arguments: String::new(),
                });
            }
            let slot = &mut acc.tool_calls[idx];
            if !id.is_empty() {
                slot.id = id.to_string();
            }
            let f = &call["function"];
            if let Some(n) = f["name"].as_str() {
                // Some servers repeat the full name on every chunk.
                if slot.name != n {
                    slot.name.push_str(n);
                }
            }
            match &f["arguments"] {
                Value::String(a) => slot.arguments.push_str(a),
                Value::Null => {}
                other => slot.arguments = other.to_string(),
            }
        }
    }
    Ok(())
}

/// Fill `acc` from a non-streaming `chat.completion` object.
fn parse_completion<F: FnMut(&str)>(
    v: &Value,
    acc: &mut AssistantMsg,
    on_delta: &mut F,
) -> Result<(), LlmError> {
    if let Some(msg) = error_message(v) {
        return Err(LlmError::Stream(msg));
    }
    if v["usage"].is_object() {
        acc.usage = Some(v["usage"].clone());
    }
    let choice = &v["choices"][0];
    if choice.is_null() {
        return Err(LlmError::Stream(format!(
            "no choices in response: {}",
            clip(v.to_string(), 300)
        )));
    }
    if let Some(fr) = choice["finish_reason"].as_str() {
        acc.finish_reason = Some(fr.to_string());
    }
    let msg = &choice["message"];
    if let Some(c) = msg["content"].as_str() {
        if !c.is_empty() {
            acc.content.push_str(c);
            on_delta(c);
        }
    }
    for call in msg["tool_calls"].as_array().into_iter().flatten() {
        acc.tool_calls.push(ToolCall {
            id: call["id"].as_str().unwrap_or("").to_string(),
            name: call["function"]["name"].as_str().unwrap_or("").to_string(),
            arguments: args_text(&call["function"]["arguments"]).unwrap_or_default(),
        });
    }
    Ok(())
}

/// Non-streaming probe used to validate a saved config.
pub async fn ping(cfg: &LlmConfig) -> Result<String, LlmError> {
    let client = client(Some(Duration::from_secs(30)))?;
    let mut body = json!({
        "model": cfg.model,
        "messages": [{"role": "user", "content": "ping"}],
        "max_tokens": 16,
        "stream": false,
    });
    let resp = post(&client, cfg, &mut body).await?;
    let text = resp.text().await.map_err(net_err)?;
    let v: Value = serde_json::from_str(&text)
        .map_err(|e| LlmError::Stream(format!("{e}: {}", clip(text.clone(), 300))))?;
    if let Some(msg) = error_message(&v) {
        return Err(LlmError::Stream(msg));
    }
    Ok(v["choices"][0]["message"]["content"]
        .as_str()
        .unwrap_or("")
        .to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(lines: &[&str]) -> Result<(AssistantMsg, String), LlmError> {
        let mut acc = AssistantMsg::default();
        let mut streamed = String::new();
        let mut sink = |d: &str| streamed.push_str(d);
        for l in lines {
            parse_delta(l, &mut acc, &mut sink)?;
        }
        Ok((acc, streamed))
    }

    #[test]
    fn openai_tool_call_chunks_without_name_accumulate() {
        // Regression: indexing a serde_json `Map` with a missing key panics,
        // which killed the agent thread on OpenAI's 2nd tool-call chunk.
        let (acc, _) = run(&[
            r#"{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"tap","arguments":""}}]}}]}"#,
            r#"{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"x\":"}}]}}]}"#,
            r#"{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}"#,
            r#"{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"ui_tree","arguments":"{}"}}]}}]}"#,
            r#"{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"#,
            r#"{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}"#,
        ])
        .unwrap();
        assert_eq!(acc.tool_calls.len(), 2);
        assert_eq!(acc.tool_calls[0].id, "call_1");
        assert_eq!(acc.tool_calls[0].name, "tap");
        assert_eq!(acc.tool_calls[0].arguments, r#"{"x":1}"#);
        assert_eq!(acc.tool_calls[1].name, "ui_tree");
        assert_eq!(acc.finish_reason.as_deref(), Some("tool_calls"));
        assert!(acc.usage.is_some());
    }

    #[test]
    fn tool_calls_without_index_and_repeated_names() {
        let (acc, _) = run(&[
            r#"{"choices":[{"delta":{"tool_calls":[{"id":"a","function":{"name":"tap","arguments":"{\"x\""}}]}}]}"#,
            r#"{"choices":[{"delta":{"tool_calls":[{"function":{"name":"tap","arguments":":1}"}}]}}]}"#,
            r#"{"choices":[{"delta":{"tool_calls":[{"id":"b","function":{"name":"key","arguments":{"name":"back"}}}]}}]}"#,
        ])
        .unwrap();
        assert_eq!(acc.tool_calls.len(), 2);
        assert_eq!(acc.tool_calls[0].name, "tap");
        assert_eq!(acc.tool_calls[0].arguments, r#"{"x":1}"#);
        assert_eq!(acc.tool_calls[1].id, "b");
        assert_eq!(acc.tool_calls[1].arguments, r#"{"name":"back"}"#);
    }

    #[test]
    fn mid_stream_error_surfaces() {
        let err = run(&[
            r#"{"choices":[{"delta":{"content":"hi"}}]}"#,
            r#"{"error":{"message":"overloaded","type":"server_error"}}"#,
        ])
        .unwrap_err();
        assert!(err.to_string().contains("overloaded"), "{err}");
    }

    #[test]
    fn sse_parser_keeps_split_utf8_and_crlf() {
        let payload =
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\n\r\ndata: [DONE]\r\n\r\n";
        let bytes = payload.as_bytes();
        // Split inside the 3-byte encoding of 你.
        let cut = payload.find('你').unwrap() + 1;
        let mut p = SseParser::default();
        let mut datas = p.push(&bytes[..cut]);
        datas.extend(p.push(&bytes[cut..]));
        datas.extend(p.finish());
        assert_eq!(datas.len(), 2);
        let (acc, streamed) = run(&[&datas[0]]).unwrap();
        assert_eq!(acc.content, "你好");
        assert_eq!(streamed, "你好");
        assert_eq!(datas[1], "[DONE]");
    }

    #[test]
    fn sse_parser_flushes_unterminated_last_line() {
        let mut p = SseParser::default();
        assert!(p.push(b"data: {\"a\":1}").is_empty());
        assert_eq!(p.finish(), vec!["{\"a\":1}".to_string()]);
    }

    #[test]
    fn non_streaming_fallback_parses_completion() {
        let v: Value = serde_json::from_str(
            r#"{"choices":[{"message":{"role":"assistant","content":"ok","tool_calls":[{"id":"t","type":"function","function":{"name":"key","arguments":"{\"name\":\"home\"}"}}]},"finish_reason":"tool_calls"}]}"#,
        )
        .unwrap();
        let mut acc = AssistantMsg::default();
        parse_completion(&v, &mut acc, &mut |_| {}).unwrap();
        assert_eq!(acc.content, "ok");
        assert_eq!(acc.tool_calls[0].name, "key");
        assert_eq!(acc.tool_calls[0].arguments, r#"{"name":"home"}"#);
    }

    #[test]
    fn adapts_rejected_params() {
        let cfg = LlmConfig {
            base_url: "http://x/v1".into(),
            api_key: String::new(),
            model: "m".into(),
            temperature: 0.2,
            max_tokens: 100,
            prompt_cache_key: String::new(),
            reasoning_effort: String::new(),
        };
        let mut body = build_body(&cfg, &[], &json!([]));
        assert!(adapt_body(
            &mut body,
            400,
            "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."
        ));
        assert!(body.get("max_tokens").is_none());
        assert_eq!(body["max_completion_tokens"], 100);
        assert!(adapt_body(
            &mut body,
            400,
            "Unsupported value: 'temperature' does not support 0.2 with this model."
        ));
        assert!(body.get("temperature").is_none());
        // Unrelated errors and non-400s are left alone.
        assert!(!adapt_body(&mut body, 400, "invalid model"));
        assert!(!adapt_body(&mut body, 401, "stream_options"));
    }
}
