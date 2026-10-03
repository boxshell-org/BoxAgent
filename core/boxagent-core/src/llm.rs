//! OpenAI-compatible chat-completions client with SSE streaming and
//! function/tool-calling accumulation.

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use thiserror::Error;

#[derive(Debug, Clone)]
pub struct LlmConfig {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    pub temperature: f64,
    pub max_tokens: u32,
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

fn endpoint(base: &str) -> String {
    format!("{}/chat/completions", base.trim_end_matches('/'))
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
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|e| LlmError::Network(e.to_string()))?;

    let body = json!({
        "model": cfg.model,
        "messages": messages,
        "tools": tools,
        "temperature": cfg.temperature,
        "max_tokens": cfg.max_tokens,
        "stream": true,
    });

    let resp = client
        .post(endpoint(&cfg.base_url))
        .bearer_auth(&cfg.api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| LlmError::Network(e.to_string()))?;

    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        return Err(LlmError::Http { status, body });
    }

    let mut acc = AssistantMsg::default();
    let mut buf = String::new();
    let mut stream = resp.bytes_stream();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| LlmError::Stream(e.to_string()))?;
        buf.push_str(&String::from_utf8_lossy(&chunk));

        // SSE events are separated by blank lines.
        while let Some(pos) = buf.find("\n\n").or_else(|| buf.find("\r\n\r\n")) {
            let sep = if buf[pos..].starts_with("\r\n") { 4 } else { 2 };
            let event = buf[..pos].to_string();
            buf.drain(..pos + sep);
            for line in event.lines() {
                let line = line.trim();
                if let Some(data) = line.strip_prefix("data:") {
                    let data = data.trim();
                    if data == "[DONE]" {
                        continue;
                    }
                    parse_delta(data, &mut acc, &mut on_delta)?;
                }
            }
        }
    }

    Ok(acc)
}

fn parse_delta<F: FnMut(&str)>(
    data: &str,
    acc: &mut AssistantMsg,
    on_delta: &mut F,
) -> Result<(), LlmError> {
    let v: Value =
        serde_json::from_str(data).map_err(|e| LlmError::Stream(format!("{e}: {data}")))?;
    let Some(choice) = v["choices"].get(0) else {
        return Ok(());
    };

    if let Some(fr) = choice["finish_reason"].as_str() {
        acc.finish_reason = Some(fr.to_string());
    }

    let delta = &choice["delta"];
    if let Some(c) = delta["content"].as_str() {
        acc.content.push_str(c);
        on_delta(c);
    }

    if let Some(calls) = delta["tool_calls"].as_array() {
        for call in calls {
            let idx = call["index"].as_u64().unwrap_or(0) as usize;
            while acc.tool_calls.len() <= idx {
                acc.tool_calls.push(ToolCall {
                    id: String::new(),
                    name: String::new(),
                    arguments: String::new(),
                });
            }
            let slot = &mut acc.tool_calls[idx];
            if let Some(id) = call["id"].as_str() {
                slot.id = id.to_string();
            }
            if let Some(f) = call["function"].as_object() {
                if let Some(n) = f["name"].as_str() {
                    slot.name.push_str(n);
                }
                if let Some(a) = f["arguments"].as_str() {
                    slot.arguments.push_str(a);
                }
            }
        }
    }
    Ok(())
}

/// Non-streaming probe used to validate a saved config.
pub async fn ping(cfg: &LlmConfig) -> Result<String, LlmError> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(20))
        .build()
        .map_err(|e| LlmError::Network(e.to_string()))?;
    let body = json!({
        "model": cfg.model,
        "messages": [{"role": "user", "content": "ping"}],
        "max_tokens": 4,
        "stream": false,
    });
    let resp = client
        .post(endpoint(&cfg.base_url))
        .bearer_auth(&cfg.api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| LlmError::Network(e.to_string()))?;
    if !resp.status().is_success() {
        return Err(LlmError::Http {
            status: resp.status().as_u16(),
            body: resp.text().await.unwrap_or_default(),
        });
    }
    let v: Value = resp
        .json()
        .await
        .map_err(|e| LlmError::Stream(e.to_string()))?;
    Ok(v["choices"][0]["message"]["content"]
        .as_str()
        .unwrap_or("")
        .to_string())
}
