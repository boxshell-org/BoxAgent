//! JNI surface called from `com.boxagent.app.bridge.Core`.
//!
//! String-returning functions never throw; errors are reported as
//! `{"ok":false,"error":"..."}` JSON so Kotlin has one contract.

use crate::agent::EventSink;
use crate::{adb_ops, agent, llm, tools};
use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

fn jstr(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn get_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

fn ok(v: Value) -> String {
    json!({"ok": true, "data": v}).to_string()
}

fn err<E: std::fmt::Display>(e: E) -> String {
    json!({"ok": false, "error": e.to_string()}).to_string()
}

fn guarded<F: FnOnce() -> String>(f: F) -> String {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| err("native panic"))
}

/// A Kotlin callback that throws leaves the exception pending; every later
/// JNI call on this thread (and the detach) is then illegal — CheckJNI
/// aborts the process. Describe + clear it so the agent can carry on.
fn clear_exception(env: &mut JNIEnv) -> bool {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        true
    } else {
        false
    }
}

/// Config strings come from text fields — stray whitespace or a pasted
/// newline in a key makes an invalid `Authorization` header.
fn cfg_str(v: &Value, key: &str) -> String {
    v[key].as_str().unwrap_or_default().trim().to_string()
}

/// `host:port` from the UI or mDNS. Plain `format!("{host}:{port}")` breaks
/// on IPv6 literals (needs brackets) and zone ids (`fe80::1%wlan0`), which
/// NsdManager hands out — go through getaddrinfo for anything that isn't a
/// bare IP.
fn sock_addr(host: &str, port: jint) -> Result<SocketAddr, String> {
    use std::net::{IpAddr, ToSocketAddrs};
    let host = host.trim().trim_start_matches('[').trim_end_matches(']');
    let port = u16::try_from(port)
        .ok()
        .filter(|p| *p > 0)
        .ok_or_else(|| format!("bad port {port}"))?;
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    (host, port)
        .to_socket_addrs()
        .map_err(|e| format!("bad addr {host}:{port}: {e}"))?
        .next()
        .ok_or_else(|| format!("bad addr {host}:{port}"))
}

// ------------------------------------------------------------------ keys

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeGenerateAdbKey(
    mut env: JNIEnv,
    _c: JObject,
) -> jstring {
    let out = guarded(|| match adb_tls::generate_key_pem() {
        Ok(pem) => ok(json!({"pem": pem})),
        Err(e) => err(e),
    });
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeAdbPubkey(
    mut env: JNIEnv,
    _c: JObject,
    pem: JString,
) -> jstring {
    let pem = get_string(&mut env, &pem);
    let out = guarded(|| match adb_tls::pubkey_line(&pem) {
        Ok(line) => ok(json!({"pubkey": line})),
        Err(e) => err(e),
    });
    jstr(&mut env, out)
}

// ------------------------------------------------------------------ pairing

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativePair(
    mut env: JNIEnv,
    _c: JObject,
    host: JString,
    port: jint,
    code: JString,
    pem: JString,
) -> jstring {
    let host = get_string(&mut env, &host);
    let code = get_string(&mut env, &code);
    let pem = get_string(&mut env, &pem);
    let out = guarded(|| {
        let addr = match sock_addr(&host, port) {
            Ok(a) => a,
            Err(e) => return err(e),
        };
        match adb_tls::pair(addr, &code, &pem) {
            Ok(r) => ok(json!({
                "guid": String::from_utf8_lossy(&r.device_guid),
            })),
            Err(e) => err(e),
        }
    });
    jstr(&mut env, out)
}

// ------------------------------------------------------------------ adb ops

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeSpawnDaemon(
    mut env: JNIEnv,
    _c: JObject,
    pem: JString,
    host: JString,
    port: jint,
    daemon_b64: JString,
    remote_path: JString,
    listen: JString,
    token: JString,
) -> jstring {
    let pem = get_string(&mut env, &pem);
    let host = get_string(&mut env, &host);
    let daemon_b64 = get_string(&mut env, &daemon_b64);
    let remote_path = get_string(&mut env, &remote_path);
    let listen = get_string(&mut env, &listen);
    let token = get_string(&mut env, &token);
    let out = guarded(|| {
        use base64::Engine;
        let bytes = match base64::engine::general_purpose::STANDARD.decode(&daemon_b64) {
            Ok(b) => b,
            Err(e) => return err(format!("b64: {e}")),
        };
        let addr = match sock_addr(&host, port) {
            Ok(a) => a,
            Err(e) => return err(e),
        };
        match adb_ops::spawn_daemon(&pem, addr, &bytes, &remote_path, &listen, &token) {
            Ok(out) => ok(json!({"output": out})),
            Err(e) => err(e),
        }
    });
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeAdbShell(
    mut env: JNIEnv,
    _c: JObject,
    pem: JString,
    host: JString,
    port: jint,
    cmd: JString,
) -> jstring {
    let pem = get_string(&mut env, &pem);
    let host = get_string(&mut env, &host);
    let cmd = get_string(&mut env, &cmd);
    let out = guarded(|| {
        let addr = match sock_addr(&host, port) {
            Ok(a) => a,
            Err(e) => return err(e),
        };
        match adb_ops::adb_shell(&pem, addr, &cmd) {
            Ok(out) => ok(json!({"output": out})),
            Err(e) => err(e),
        }
    });
    jstr(&mut env, out)
}

// ------------------------------------------------------------------ catalog

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeToolCatalog(
    mut env: JNIEnv,
    _c: JObject,
) -> jstring {
    let out = tools::registry::catalog().to_string();
    jstr(&mut env, out)
}

// ------------------------------------------------------------------ llm

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativePingLlm(
    mut env: JNIEnv,
    _c: JObject,
    config: JString,
) -> jstring {
    let config = get_string(&mut env, &config);
    let out = guarded(|| {
        let v: Value = match serde_json::from_str(&config) {
            Ok(v) => v,
            Err(e) => return err(format!("config json: {e}")),
        };
        let cfg = LlmConfig {
            base_url: cfg_str(&v, "base_url"),
            api_key: cfg_str(&v, "api_key"),
            model: cfg_str(&v, "model"),
            temperature: v["temperature"].as_f64().unwrap_or(0.2),
            max_tokens: v["max_tokens"].as_u64().unwrap_or(2048) as u32,
            prompt_cache_key: String::new(),
            reasoning_effort: String::new(),
        };
        let rt = match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(r) => r,
            Err(e) => return err(e),
        };
        match rt.block_on(llm::ping(&cfg)) {
            Ok(text) => ok(json!({"response": text})),
            Err(e) => err(e),
        }
    });
    jstr(&mut env, out)
}

use llm::LlmConfig;

// ------------------------------------------------------------------ agent

static AGENTS: OnceLock<Mutex<HashMap<u64, Arc<AtomicBool>>>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn agents() -> &'static Mutex<HashMap<u64, Arc<AtomicBool>>> {
    AGENTS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Kotlin `AgentCallbacks` adapter: JVM round-trip per tool call.
struct JniExecutor {
    jvm: JavaVM,
    callbacks: GlobalRef,
}

impl agent::ToolExecutor for JniExecutor {
    fn execute(&self, name: &str, args_json: &str) -> String {
        self.call(
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            "executeTool",
            &[name, args_json],
        )
        .unwrap_or_else(|e| json!({"ok": false, "error": format!("executor: {e}")}).to_string())
    }

    fn ask(&self, question: &str) -> String {
        self.call(
            "(Ljava/lang/String;)Ljava/lang/String;",
            "askUser",
            &[question],
        )
        .unwrap_or_else(|e| format!("(ask failed: {e})"))
    }
}

impl JniExecutor {
    fn call(&self, sig: &str, method: &str, args: &[&str]) -> Result<String, String> {
        let mut env = self
            .jvm
            .attach_current_thread()
            .map_err(|e| format!("attach: {e}"))?;
        let mut jargs = Vec::new();
        let mut jstrings = Vec::new();
        for a in args {
            let js = env.new_string(a).map_err(|e| format!("{e}"))?;
            jstrings.push(js);
        }
        for js in &jstrings {
            jargs.push(JValue::Object(js.as_ref()));
        }
        let ret = env.call_method(self.callbacks.as_obj(), method, sig, &jargs);
        if clear_exception(&mut env) {
            return Err(format!("java exception in {method}"));
        }
        let ret = ret.map_err(|e| format!("call: {e}"))?;
        let obj = ret.l().map_err(|e| format!("{e}"))?;
        let js = JString::from(obj);
        env.get_string(&js)
            .map(|s| s.into())
            .map_err(|e| format!("{e}"))
    }
}

struct JniSink {
    jvm: JavaVM,
    callbacks: GlobalRef,
}

impl agent::EventSink for JniSink {
    fn emit(&self, event: Value) {
        let _ = self.emit_inner(event);
    }
}

impl JniSink {
    fn emit_inner(&self, event: Value) -> Result<(), String> {
        let mut env = self
            .jvm
            .attach_current_thread()
            .map_err(|e| format!("{e}"))?;
        let js = env
            .new_string(event.to_string())
            .map_err(|e| format!("{e}"))?;
        let r = env.call_method(
            self.callbacks.as_obj(),
            "onEvent",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&js)],
        );
        clear_exception(&mut env);
        r.map(|_| ()).map_err(|e| format!("{e}"))
    }
}

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeStartAgent(
    mut env: JNIEnv,
    _c: JObject,
    config: JString,
    callbacks: JObject,
) -> jlong {
    let config = get_string(&mut env, &config);
    // Parse first: a bad config must not leak the callbacks GlobalRef.
    let v: Value = match serde_json::from_str(&config) {
        Ok(v) => v,
        Err(e) => {
            tracing::error!("config json: {e}");
            return -1;
        }
    };
    let jvm = match env.get_java_vm() {
        Ok(v) => v,
        Err(e) => {
            tracing::error!("no jvm: {e}");
            return -1;
        }
    };
    let callbacks = match env.new_global_ref(callbacks) {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("global ref: {e}");
            return -1;
        }
    };

    let cfg = agent::AgentConfig {
        llm: LlmConfig {
            base_url: cfg_str(&v, "base_url"),
            api_key: cfg_str(&v, "api_key"),
            model: cfg_str(&v, "model"),
            temperature: v["temperature"].as_f64().unwrap_or(0.2),
            max_tokens: v["max_tokens"].as_u64().unwrap_or(4096) as u32,
            prompt_cache_key: v["prompt_cache_key"].as_str().unwrap_or_default().into(),
            reasoning_effort: cfg_str(&v, "reasoning_effort"),
        },
        // `instructions` (custom text appended to the built-in guide);
        // older app builds sent the whole prompt as `system_prompt`.
        instructions: v["instructions"]
            .as_str()
            .or_else(|| v["system_prompt"].as_str())
            .unwrap_or_default()
            .into(),
        device_context: cfg_str(&v, "device_context"),
        caps: {
            let c = &v["capabilities"];
            let d = tools::registry::Caps::default();
            tools::registry::Caps {
                a11y: c["a11y"].as_bool().unwrap_or(d.a11y),
                shell: c["shell"].as_bool().unwrap_or(d.shell),
                vision: c["vision"].as_bool().unwrap_or(d.vision),
                // Derived by the agent from the skills it was given.
                skills: false,
                learn: c["learn"].as_bool().unwrap_or(d.learn),
            }
        },
        skills: crate::skills::parse_all(&v["skills"]),
        start_skill: v["start_skill"]["name"]
            .as_str()
            .map(|name| agent::StartSkill {
                name: name.to_string(),
                params: v["start_skill"]["params"].clone(),
                llm_fallback: v["start_skill"]["llm_fallback"].as_bool().unwrap_or(true),
            }),
        prompt: v["prompt"].as_str().unwrap_or_default().into(),
        history: v["history"].as_array().cloned().unwrap_or_default(),
        max_steps: v["max_steps"].as_u64().unwrap_or(40) as u32,
        max_wall_ms: v["max_wall_ms"].as_u64().unwrap_or(600_000),
        compact_tools: v["compact_tools"].as_bool().unwrap_or(true),
    };

    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let cancel = Arc::new(AtomicBool::new(false));
    if let Ok(mut m) = agents().lock() {
        m.insert(id, cancel.clone());
    }

    let jvm_ptr = jvm.get_java_vm_pointer() as usize;
    std::thread::spawn(move || {
        // Removes the agent-map entry even when setup code panics before
        // the guarded run below — a leaked entry would make cancel() a
        // no-op and the UI could wait on events that never come.
        struct MapGuard(u64);
        impl Drop for MapGuard {
            fn drop(&mut self) {
                if let Ok(mut m) = agents().lock() {
                    m.remove(&self.0);
                }
            }
        }
        let _guard = MapGuard(id);

        let raw_vm = || unsafe {
            JavaVM::from_raw(jvm_ptr as *mut jni::sys::JavaVM)
                .expect("JavaVM pointer was valid at spawn")
        };
        let err_sink = JniSink {
            jvm: raw_vm(),
            callbacks: callbacks.clone(),
        };
        let sink = JniSink {
            jvm: raw_vm(),
            callbacks: callbacks.clone(),
        };
        let exec = JniExecutor {
            jvm: raw_vm(),
            callbacks,
        };
        let a = agent::Agent::new(cfg, exec, sink, cancel.clone());
        // A panic must still end the run with an event — otherwise the UI
        // waits on "running" forever.
        let outcome = catch_unwind(AssertUnwindSafe(|| a.run()));
        let message = match outcome {
            Ok(Ok(_)) => None,
            Ok(Err(e)) => Some(e.to_string()),
            Err(p) => Some(format!(
                "internal error: {}",
                p.downcast_ref::<&str>()
                    .map(|s| s.to_string())
                    .or_else(|| p.downcast_ref::<String>().cloned())
                    .unwrap_or_else(|| "panic".into())
            )),
        };
        if let Some(message) = message {
            err_sink.emit(json!({"type": "error", "message": message}));
        }
    });
    id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_boxagent_app_bridge_Core_nativeCancel(
    _env: JNIEnv,
    _c: JObject,
    id: jlong,
) {
    if let Some(flag) = agents()
        .lock()
        .ok()
        .and_then(|m| m.get(&(id as u64)).cloned())
    {
        flag.store(true, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::sock_addr;

    #[test]
    fn sock_addr_accepts_v4_v6_and_brackets() {
        assert_eq!(
            sock_addr("127.0.0.1", 5555).unwrap().to_string(),
            "127.0.0.1:5555"
        );
        assert_eq!(
            sock_addr(" ::1 ", 37001).unwrap().to_string(),
            "[::1]:37001"
        );
        assert_eq!(
            sock_addr("[::1]", 37001).unwrap().to_string(),
            "[::1]:37001"
        );
        assert!(sock_addr("127.0.0.1", 0).is_err());
        assert!(sock_addr("127.0.0.1", 70000).is_err());
    }
}
