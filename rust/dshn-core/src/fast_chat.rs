//! Zero-GC native Rust TLS + HTTP SSE streaming client for instant cold-start chat
//! (supporting text + multimodal base64 image content blocks without waiting for Ubuntu/Node/DSH).

use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use serde_json::Value;

static CANCEL_REQUESTED: AtomicBool = AtomicBool::new(false);

pub fn request_cancel() {
    CANCEL_REQUESTED.store(true, Ordering::SeqCst);
}

pub enum ChatStreamEvent<'a> {
    TextDelta(&'a str),
    ReasoningDelta(&'a str),
    Usage { prompt: i64, completion: i64, total: i64 },
    Done(&'a str),
    Error(String),
}

pub fn run_sse_stream<F>(
    endpoint_url: &str,
    api_key: &str,
    request_body_json: &str,
    mut on_event: F,
) -> bool
where
    F: FnMut(ChatStreamEvent<'_>),
{
    CANCEL_REQUESTED.store(false, Ordering::SeqCst);

    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(120))
        .build();

    let mut req = agent
        .post(endpoint_url)
        .set("Content-Type", "application/json")
        .set("Accept", "text/event-stream");
    if !api_key.trim().is_empty() {
        req = req.set("Authorization", &format!("Bearer {}", api_key.trim()));
    }

    let response = match req.send_string(request_body_json) {
        Ok(resp) => resp,
        Err(ureq::Error::Status(code, resp)) => {
            let body = resp.into_string().unwrap_or_default();
            let snippet: String = body.chars().take(400).collect();
            on_event(ChatStreamEvent::Error(format!("HTTP {code}: {snippet}")));
            return false;
        }
        Err(e) => {
            on_event(ChatStreamEvent::Error(format!("Network error: {e}")));
            return false;
        }
    };

    let mut reader = BufReader::with_capacity(32 * 1024, response.into_reader());
    let mut line_buf = String::with_capacity(4096);
    let mut finish_reason = String::from("stop");

    loop {
        if CANCEL_REQUESTED.load(Ordering::Relaxed) {
            on_event(ChatStreamEvent::Done("cancelled"));
            return true;
        }
        line_buf.clear();
        match reader.read_line(&mut line_buf) {
            Ok(0) => break,
            Ok(_) => {}
            Err(e) => {
                if CANCEL_REQUESTED.load(Ordering::Relaxed) {
                    on_event(ChatStreamEvent::Done("cancelled"));
                    return true;
                }
                on_event(ChatStreamEvent::Error(format!("Stream read error: {e}")));
                return false;
            }
        }

        let trimmed = line_buf.trim();
        if trimmed.is_empty() || trimmed.starts_with(':') {
            continue;
        }
        let payload = match trimmed.strip_prefix("data:") {
            Some(p) => p.trim(),
            None => continue,
        };
        if payload == "[DONE]" {
            break;
        }

        let Ok(json) = serde_json::from_str::<Value>(payload) else {
            continue;
        };

        if let Some(usage) = json.get("usage").and_then(Value::as_object) {
            let prompt = usage.get("prompt_tokens").and_then(Value::as_i64).unwrap_or(0);
            let completion = usage.get("completion_tokens").and_then(Value::as_i64).unwrap_or(0);
            let total = usage
                .get("total_tokens")
                .and_then(Value::as_i64)
                .unwrap_or(prompt + completion);
            if total > 0 {
                on_event(ChatStreamEvent::Usage { prompt, completion, total });
            }
        }

        if let Some(choice) = json
            .get("choices")
            .and_then(Value::as_array)
            .and_then(|arr| arr.first())
        {
            if let Some(delta) = choice.get("delta") {
                if let Some(reasoning) = delta.get("reasoning_content").and_then(Value::as_str) {
                    if !reasoning.is_empty() {
                        on_event(ChatStreamEvent::ReasoningDelta(reasoning));
                    }
                }
                if let Some(content) = delta.get("content").and_then(Value::as_str) {
                    if !content.is_empty() {
                        on_event(ChatStreamEvent::TextDelta(content));
                    }
                }
            }
            if let Some(fr) = choice.get("finish_reason").and_then(Value::as_str) {
                if !fr.is_empty() {
                    finish_reason.clear();
                    finish_reason.push_str(fr);
                }
            }
        }
    }

    let final_reason = if CANCEL_REQUESTED.load(Ordering::Relaxed) {
        "cancelled"
    } else {
        finish_reason.as_str()
    };
    on_event(ChatStreamEvent::Done(final_reason));
    true
}
