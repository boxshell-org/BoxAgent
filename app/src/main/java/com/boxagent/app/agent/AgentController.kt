package com.boxagent.app.agent

import android.content.Context
import com.boxagent.app.R
import com.boxagent.app.bridge.AgentCallbacks
import com.boxagent.app.bridge.Core
import com.boxagent.app.daemon.DaemonManager
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.data.Secrets
import com.boxagent.app.data.Settings
import com.boxagent.app.data.db.AppDb
import com.boxagent.app.data.db.Conversation
import com.boxagent.app.data.db.Message
import com.boxagent.app.service.AgentService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Immutable: results arrive as a new copy so Compose sees the change. */
data class ToolCallUi(
    val id: String,
    val name: String,
    val args: String,
    val result: String? = null,
    val durationMs: Long = 0,
)

data class ChatMsg(
    val id: Long,
    val role: String,                 // user | assistant | system | tool
    val text: String,
    val toolCalls: List<ToolCallUi> = emptyList(),
    val streaming: Boolean = false,
)

data class PendingAsk(val question: String, val answer: CompletableDeferred<String>)

private fun fmtK(n: Long): String =
    if (n >= 1000) "%.1fk".format(java.util.Locale.US, n / 1000.0) else n.toString()

data class AgentState(
    val running: Boolean = false,
    val conversationId: Long = 0,
    val messages: List<ChatMsg> = emptyList(),
    val currentAssistantText: String = "",
    val pendingAsk: PendingAsk? = null,
    val steps: Int = 0,
    val error: String? = null,
    /** Last request's token usage summary, e.g. "↑9.1k (cached 6.2k) ↓0.4k". */
    val usageText: String = "",
)

/**
 * Kotlin endpoint of the Rust agent loop: owns conversation state,
 * implements AgentCallbacks (called from Rust threads), bridges events
 * into StateFlow and persists finished turns.
 */
class AgentController(
    private val context: Context,
    private val settings: Settings,
    private val secrets: Secrets,
    private val toolRunner: ToolRunner,
    private val db: AppDb,
    private val daemon: DaemonManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    @Volatile private var agentHandle: Long = -1
    /** UI ids for LazyColumn keys — one sequence for live AND loaded rows. */
    private val msgCounter = AtomicLong(0)
    private val assistantBuf = StringBuilder()
    private val callbacks = Callbacks()

    private fun nextId() = msgCounter.incrementAndGet()

    fun newConversation() {
        if (_state.value.running) return
        _state.update { it.copy(conversationId = 0, messages = emptyList(), error = null) }
    }

    suspend fun loadConversation(id: Long) {
        if (_state.value.running) return
        val msgs = withContext(Dispatchers.IO) { db.messages().forConversation(id).first() }
        _state.update {
            it.copy(
                conversationId = id,
                error = null,
                messages = msgs.map { m ->
                    // DB row ids would collide with live ids as list keys.
                    ChatMsg(id = nextId(), role = m.role, text = m.content)
                },
            )
        }
    }

    fun send(prompt: String) {
        if (prompt.isBlank()) return
        val apiKey = secrets.apiKey
        if (apiKey.isEmpty()) {
            _state.update {
                it.copy(error = context.getString(R.string.error_no_api_key))
            }
            return
        }
        // Claim the run synchronously so a double tap can't start two.
        var claimed = false
        _state.update {
            if (it.running) it
            else {
                claimed = true
                it.copy(running = true, error = null)
            }
        }
        if (!claimed) return
        scope.launch {
            runCatching { startRun(prompt, apiKey) }.onFailure { e ->
                _state.update {
                    it.copy(
                        running = false,
                        error = e.message ?: context.getString(R.string.error_agent_start),
                    )
                }
                agentHandle = -1
                idleService()
            }
        }
    }

    private suspend fun startRun(prompt: String, apiKey: String) {
        val st = _state.value
        var convId = st.conversationId
        if (convId == 0L) {
            convId = db.conversations().insert(
                Conversation(title = prompt.take(48)),
            )
        }
        toolRunner.conversationId = convId
        // History first: the prompt itself is sent separately by the core —
        // reading after the insert would send it twice.
        val history = historyJson(convId)
        db.messages().insert(Message(conversationId = convId, role = "user", content = prompt))

        val config = JSONObject()
            .put("base_url", settings.baseUrl.first())
            .put("api_key", apiKey)
            .put("model", settings.model.first())
            .put("temperature", settings.temperature.first())
            .put("max_tokens", settings.maxTokens.first())
            .put("system_prompt", settings.systemPrompt.first())
            .put("prompt", prompt)
            .put("history", history)
            .put("max_steps", settings.maxSteps.first())
            .put("max_wall_ms", settings.maxWallMs.first())
            // Stable per-conversation key: providers route it to a warm
            // prefix cache instead of recomputing the whole prompt.
            .put("prompt_cache_key", "boxagent-c$convId")
            .put("compact_tools", settings.compactTools.first())

        synchronized(assistantBuf) { assistantBuf.setLength(0) }
        _state.update {
            it.copy(
                running = true, conversationId = convId, steps = 0, error = null,
                messages = it.messages + ChatMsg(nextId(), "user", prompt),
                currentAssistantText = "",
            )
        }
        AgentService.start(
            context,
            context.getString(R.string.notif_running_task),
        )

        agentHandle = Core.nativeStartAgent(config.toString(), callbacks)
        if (agentHandle < 0) {
            _state.update {
                it.copy(running = false, error = context.getString(R.string.error_agent_start))
            }
            idleService()
        }
    }

    private suspend fun historyJson(convId: Long): JSONArray {
        val msgs = db.messages().forConversation(convId).first()
        val arr = JSONArray()
        msgs.filter { it.role == "user" || it.role == "assistant" }
            .takeLast(16)
            .forEach {
                arr.put(
                    JSONObject()
                        .put("role", it.role)
                        .put("content", it.content.take(1500)),
                )
            }
        return arr
    }

    fun cancel() {
        val h = agentHandle
        if (h >= 0) Core.nativeCancel(h)
        // The agent thread may be parked on the user — release it so the
        // cancel flag is seen right away instead of never.
        _state.value.pendingAsk?.answer?.complete("")
        _state.update { it.copy(pendingAsk = null) }
        toolRunner.cancelPending()
    }

    fun answerAsk(text: String) {
        _state.value.pendingAsk?.answer?.complete(text)
        _state.update { it.copy(pendingAsk = null) }
    }

    private fun appendAssistantDelta(delta: String) {
        val text = synchronized(assistantBuf) { assistantBuf.append(delta).toString() }
        _state.update { it.copy(currentAssistantText = text) }
    }

    /** Move streamed text into the message list as a finished row. */
    private fun flushAssistant() {
        val text = synchronized(assistantBuf) {
            assistantBuf.toString().also { assistantBuf.setLength(0) }
        }
        _state.update {
            it.copy(
                currentAssistantText = "",
                messages = if (text.isEmpty()) it.messages
                    else it.messages + ChatMsg(nextId(), "assistant", text),
            )
        }
    }

    private fun endRun(finalText: String?) {
        val convId = _state.value.conversationId
        agentHandle = -1
        scope.launch {
            if (!finalText.isNullOrEmpty() && convId > 0) {
                db.messages().insert(
                    Message(conversationId = convId, role = "assistant", content = finalText),
                )
                // Keep the prompt-derived title; just bump recency.
                db.conversations().bump(convId)
            }
            idleService()
        }
    }

    /** After a run: hand the FGS back to watchdog duty, or stop it. */
    private suspend fun idleService() {
        val watchdog = runCatching { settings.keepWatchdog.first() }.getOrDefault(false)
        if (watchdog && daemon.status.value.shell == ShellState.ONLINE) {
            AgentService.start(
                context,
                context.getString(R.string.notif_watchdog_active),
                wake = false,
            )
        } else {
            AgentService.stop(context)
        }
    }

    private inner class Callbacks : AgentCallbacks {
        override fun onEvent(json: String) {
            val e = runCatching { JSONObject(json) }.getOrNull() ?: return
            when (e.optString("type")) {
                "text_delta" -> appendAssistantDelta(e.optString("text"))
                "assistant_text" -> {
                    // Text accompanying tool calls: already streamed, or (on
                    // non-streaming servers) only delivered here.
                    val streamed = synchronized(assistantBuf) { assistantBuf.isNotEmpty() }
                    val t = e.optString("text")
                    if (streamed) flushAssistant()
                    else if (t.isNotEmpty()) {
                        _state.update {
                            it.copy(messages = it.messages + ChatMsg(nextId(), "assistant", t))
                        }
                    }
                }
                "tool_call" -> {
                    flushAssistant()
                    val call = ToolCallUi(
                        id = e.optString("id"),
                        name = e.optString("name"),
                        args = e.optString("args"),
                    )
                    _state.update {
                        val last = it.messages.lastOrNull()
                        // Consecutive calls share one card group.
                        val msgs = if (last != null && last.role == "tool") {
                            it.messages.dropLast(1) +
                                last.copy(toolCalls = last.toolCalls + call)
                        } else {
                            it.messages + ChatMsg(nextId(), "tool", call.name, toolCalls = listOf(call))
                        }
                        it.copy(steps = it.steps + 1, messages = msgs)
                    }
                }
                "tool_result" -> {
                    val id = e.optString("id")
                    val result = e.optString("result")
                    val dur = e.optLong("duration_ms")
                    _state.update {
                        it.copy(messages = it.messages.map { m ->
                            if (m.role != "tool" || m.toolCalls.none { c -> c.id == id }) m
                            else m.copy(toolCalls = m.toolCalls.map { c ->
                                if (c.id == id) c.copy(result = result, durationMs = dur) else c
                            })
                        })
                    }
                }
                "done" -> {
                    val text = e.optString("text")
                    flushAssistant()
                    _state.update {
                        // task_done's summary was never streamed — show it.
                        val lastAssistant = it.messages.lastOrNull { m -> m.role == "assistant" }
                        val msgs = if (text.isNotEmpty() && lastAssistant?.text != text) {
                            it.messages + ChatMsg(nextId(), "assistant", text)
                        } else it.messages
                        it.copy(running = false, pendingAsk = null, messages = msgs)
                    }
                    endRun(text)
                }
                "error" -> {
                    val msg = e.optString("message")
                    flushAssistant()
                    toolRunner.cancelPending()
                    _state.update {
                        if (msg == "cancelled") {
                            it.copy(
                                running = false, pendingAsk = null,
                                messages = it.messages + ChatMsg(
                                    nextId(), "system", context.getString(R.string.chat_stopped),
                                ),
                            )
                        } else {
                            it.copy(running = false, pendingAsk = null, error = msg)
                        }
                    }
                    endRun(null)
                }
                "usage" -> {
                    val u = e.optJSONObject("usage") ?: return
                    val inp = u.optLong("prompt_tokens")
                    val outp = u.optLong("completion_tokens")
                    val cached = u.optJSONObject("prompt_tokens_details")
                        ?.optLong("cached_tokens") ?: 0
                    _state.update {
                        it.copy(
                            usageText = "↑${fmtK(inp)} · ↓${fmtK(outp)}" +
                                if (cached > 0) " · cache ${fmtK(cached)}" else "",
                        )
                    }
                }
                "warn" -> {
                    _state.update {
                        it.copy(messages = it.messages +
                            ChatMsg(nextId(), "system", e.optString("message")))
                    }
                }
            }
        }

        override fun executeTool(name: String, argsJson: String): String =
            toolRunner.executeBlocking(name, argsJson)

        override fun askUser(question: String): String {
            val deferred = CompletableDeferred<String>()
            _state.update { it.copy(pendingAsk = PendingAsk(question, deferred)) }
            return kotlinx.coroutines.runBlocking { deferred.await() }
        }
    }
}
