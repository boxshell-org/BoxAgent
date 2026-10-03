package com.boxagent.app.agent

import android.content.Context
import com.boxagent.app.R
import com.boxagent.app.bridge.AgentCallbacks
import com.boxagent.app.bridge.Core
import com.boxagent.app.data.Secrets
import com.boxagent.app.data.Settings
import com.boxagent.app.data.db.AppDb
import com.boxagent.app.data.db.Conversation
import com.boxagent.app.data.db.Message
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

data class ToolCallUi(
    val id: String,
    val name: String,
    val args: String,
    var result: String? = null,
    var durationMs: Long = 0,
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    @Volatile private var agentHandle: Long = -1
    private var msgCounter = 0L
    private var pendingToolCalls = mutableMapOf<String, ToolCallUi>()
    private var assistantBuf = StringBuilder()
    private val callbacks = Callbacks()

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
                messages = msgs.map { m ->
                    ChatMsg(id = m.id, role = m.role, text = m.content)
                },
            )
        }
    }

    fun send(prompt: String) {
        if (_state.value.running || prompt.isBlank()) return
        val secretsApiKey = secrets.apiKey
        if (secretsApiKey.isEmpty()) {
            _state.update {
                it.copy(error = context.getString(R.string.error_no_api_key))
            }
            return
        }
        scope.launch { startRun(prompt, secretsApiKey) }
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
        db.messages().insert(Message(conversationId = convId, role = "user", content = prompt))

        val history = historyJson(convId)
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

        assistantBuf = StringBuilder()
        pendingToolCalls.clear()
        _state.update {
            it.copy(
                running = true, conversationId = convId, steps = 0, error = null,
                messages = it.messages + ChatMsg(++msgCounter, "user", prompt),
                currentAssistantText = "",
            )
        }
        com.boxagent.app.service.AgentService.start(
            context,
            context.getString(R.string.notif_running_task),
        )

        agentHandle = Core.nativeStartAgent(config.toString(), callbacks)
        if (agentHandle < 0) {
            _state.update {
                it.copy(running = false, error = context.getString(R.string.error_agent_start))
            }
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
    }

    fun answerAsk(text: String) {
        _state.value.pendingAsk?.answer?.complete(text)
        _state.update { it.copy(pendingAsk = null) }
    }

    private fun appendAssistantDelta(delta: String) {
        assistantBuf.append(delta)
        _state.update { it.copy(currentAssistantText = assistantBuf.toString()) }
    }

    private fun flushAssistant(final: Boolean = false) {
        val text = assistantBuf.toString()
        if (text.isEmpty()) return
        _state.update {
            it.copy(
                currentAssistantText = "",
                messages = it.messages + ChatMsg(++msgCounter, "assistant", text, streaming = !final),
            )
        }
        assistantBuf = StringBuilder()
    }

    private fun endRun(finalText: String?, error: String?) {
        val convId = _state.value.conversationId
        scope.launch {
            if (!finalText.isNullOrEmpty() && convId > 0) {
                db.messages().insert(
                    Message(conversationId = convId, role = "assistant", content = finalText),
                )
                db.conversations().touch(convId, finalText.take(48))
            }
            com.boxagent.app.service.AgentService.stop(context)
        }
        agentHandle = -1
    }

    private inner class Callbacks : AgentCallbacks {
        override fun onEvent(json: String) {
            val e = JSONObject(json)
            when (e.optString("type")) {
                "text_delta" -> appendAssistantDelta(e.optString("text"))
                "assistant_text" -> {
                    // non-streamed assistant text accompanying tool calls
                    val t = e.optString("text")
                    if (t.isNotEmpty() && assistantBuf.isEmpty()) {
                        _state.update {
                            it.copy(messages = it.messages +
                                ChatMsg(++msgCounter, "assistant", t))
                        }
                    } else flushAssistant()
                }
                "tool_call" -> {
                    flushAssistant()
                    val call = ToolCallUi(
                        id = e.optString("id"),
                        name = e.optString("name"),
                        args = e.optString("args"),
                    )
                    pendingToolCalls[call.id] = call
                    _state.update {
                        it.copy(
                            steps = it.steps + 1,
                            messages = it.messages + ChatMsg(
                                ++msgCounter, "tool", call.name,
                                toolCalls = it.messages.lastOrNull { m -> m.role == "tool" }
                                    ?.toolCalls.orEmpty() + call,
                            ),
                        )
                    }
                }
                "tool_result" -> {
                    val id = e.optString("id")
                    pendingToolCalls[id]?.let { c ->
                        c.result = e.optString("result")
                        c.durationMs = e.optLong("duration_ms")
                    }
                    _state.update { it.copy(messages = it.messages.toList()) }
                }
                "done" -> {
                    val text = e.optString("text")
                    flushAssistant(final = true)
                    _state.update { it.copy(running = false) }
                    endRun(text.ifEmpty { assistantBuf.toString() }, null)
                }
                "error" -> {
                    val msg = e.optString("message")
                    flushAssistant()
                    _state.update { it.copy(running = false, error = msg) }
                    endRun(null, msg)
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
                            ChatMsg(++msgCounter, "system", e.optString("message")))
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
