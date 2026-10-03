package com.boxagent.app.bridge

/**
 * JNI bridge into libboxagent.so (boxagent-core).
 * All string-returning functions yield `{"ok":true,"data":{...}}` or
 * `{"ok":false,"error":"..."}` — one contract for callers.
 */
object Core {
    init {
        System.loadLibrary("boxagent")
    }

    external fun nativeGenerateAdbKey(): String
    external fun nativeAdbPubkey(pem: String): String
    external fun nativePair(host: String, port: Int, code: String, pem: String): String
    external fun nativeSpawnDaemon(
        pem: String,
        host: String,
        port: Int,
        daemonB64: String,
        remotePath: String,
        socket: String,
        token: String,
    ): String
    external fun nativeAdbShell(pem: String, host: String, port: Int, cmd: String): String
    external fun nativeToolCatalog(): String
    external fun nativePingLlm(configJson: String): String
    external fun nativeStartAgent(configJson: String, callbacks: AgentCallbacks): Long
    external fun nativeCancel(id: Long)
}

/**
 * Implemented in Kotlin, invoked from Rust agent threads.
 * All methods may block — they run on a dedicated Rust-side thread.
 */
interface AgentCallbacks {
    /** Streaming event, JSON object: type = text_delta|tool_call|tool_result|done|error|warn */
    fun onEvent(json: String)
    /** Execute one tool call; return JSON result `{"ok":true,...}`/`{"ok":false,...}` */
    fun executeTool(name: String, argsJson: String): String
    /** Blocking user question; returns the answer text. */
    fun askUser(question: String): String
}
