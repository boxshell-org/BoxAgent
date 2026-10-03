//! boxagent-core — Rust heart of BoxAgent.
//!
//! Built as `libboxagent.so` (cdylib). Owns the agent loop, the
//! OpenAI-compatible LLM client, the atomic tool catalog, the ADB ops layer
//! (`adb_client`) and the JNI surface; `adb-tls` supplies wireless pairing.

pub mod adb_ops;
pub mod agent;
pub mod jni;
pub mod llm;
pub mod tools;
