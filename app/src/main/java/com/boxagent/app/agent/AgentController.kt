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
import com.boxagent.app.service.A11yService
import com.boxagent.app.service.AgentService
import com.boxagent.app.skills.Skill
import com.boxagent.app.skills.SkillCodec
import com.boxagent.app.skills.SkillRepository
import com.boxagent.app.skills.SkillStep
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
    /** Title of the skill this run was started from, if any. */
    val skillRun: String? = null,
    /** The last finished run, offered as "Save as skill". */
    val recap: RunRecap? = null,
)

/** A finished run, in the shape needed to turn it into a skill. */
data class RunRecap(val prompt: String, val summary: String, val steps: List<SkillStep>)

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
    private val skills: SkillRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    @Volatile private var agentHandle: Long = -1
    /** UI ids for LazyColumn keys — one sequence for live AND loaded rows. */
    private val msgCounter = AtomicLong(0)
    private val assistantBuf = StringBuilder()
    private val callbacks = Callbacks()
    @Volatile private var runPrompt = ""

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
        launchRun { startRun(prompt, apiKey, null, null) }
    }

    /**
     * Run a skill from the Skills screen. Step-based skills execute without
     * the LLM (zero tokens when every step works); on failure the LLM takes
     * over when a key is configured. Instruction-only skills need the LLM.
     */
    fun runSkill(skill: Skill, params: Map<String, String>, allowAi: Boolean = true) {
        val apiKey = secrets.apiKey
        if (apiKey.isEmpty() && !skill.runnable) {
            _state.update { it.copy(error = context.getString(R.string.error_no_api_key)) }
            return
        }
        val prompt = buildString {
            append(context.getString(R.string.skill_run_prompt, skill.title))
            if (params.isNotEmpty()) {
                append(" (")
                append(params.entries.joinToString { "${it.key}: ${it.value}" })
                append(')')
            }
        }
        val start = JSONObject()
            .put("name", skill.name)
            .put("params", JSONObject(params as Map<*, *>))
            .put("llm_fallback", apiKey.isNotEmpty() && allowAi)
        launchRun { startRun(prompt, apiKey, start, skill) }
    }

    fun dismissRecap() = _state.update { it.copy(recap = null) }

    /** Claim the run synchronously (a double tap can't start two), then
     *  start it off the main thread. */
    private fun launchRun(start: suspend () -> Unit) {
        var claimed = false
        _state.update {
            if (it.running) it
            else {
                claimed = true
                it.copy(running = true, error = null, recap = null)
            }
        }
        if (!claimed) return
        scope.launch {
            runCatching { start() }.onFailure { e ->
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

    private suspend fun startRun(prompt: String, apiKey: String, startSkill: JSONObject?, skill: Skill?) {
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
            // Custom instructions; the operating guide is built into the core.
            .put("instructions", settings.systemPrompt.first())
            .put("device_context", deviceContext())
            .put("capabilities", capabilities())
            .put("prompt", prompt)
            .put("history", history)
            .put("max_steps", settings.maxSteps.first())
            .put("max_wall_ms", settings.maxWallMs.first())
            // Stable per-conversation key: providers route it to a warm
            // prefix cache instead of recomputing the whole prompt.
            .put("prompt_cache_key", "boxagent-c$convId")
            .put("compact_tools", settings.compactTools.first())

        // Skills, most relevant to the app in front first. A skill started
        // from the UI is included even when it's a draft under review.
        val offered = skills.forRun(A11yService.instance?.foregroundPackage()).toMutableList()
        if (skill != null && offered.none { it.name == skill.name }) offered += skill
        config.put("skills", SkillCodec.coreArray(offered))
        startSkill?.let { config.put("start_skill", it) }

        // Element refs start at [1] for every run.
        A11yService.instance?.resetRefs()
        toolRunner.beginTrace()
        runPrompt = prompt
        synchronized(assistantBuf) { assistantBuf.setLength(0) }
        _state.update {
            it.copy(
                running = true, conversationId = convId, steps = 0, error = null,
                skillRun = skill?.title, recap = null,
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

    /**
     * What this run can use — the core offers only matching tools, so the
     * model neither pays tokens for nor tries unusable ones. The shell
     * counts as available when it can be respawned from the saved endpoint.
     */
    private suspend fun capabilities(): JSONObject {
        val a11y = A11yService.instance != null || A11yService.isGranted(context)
        val shell = daemon.status.value.shell == ShellState.ONLINE ||
            settings.adbPort.first() > 0
        return JSONObject()
            .put("a11y", a11y)
            .put("shell", shell)
            .put("vision", settings.vision.first())
            .put("learn", settings.learnSkills.first())
    }

    /** One stable line of device facts (date, not time: it sits in the
     *  cached prompt prefix). */
    private fun deviceContext(): String {
        val dm = android.util.DisplayMetrics()
        runCatching {
            val dmgr = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            @Suppress("DEPRECATION")
            dmgr.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        }
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), " +
            "screen ${dm.widthPixels}x${dm.heightPixels}, " +
            "locale ${java.util.Locale.getDefault().toLanguageTag()}, " +
            "date ${java.time.LocalDate.now()}"
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
                    val skillTitle = _state.value.skillRun
                    // A skill that ran without the LLM: say so in the UI's
                    // language (the core's text is English).
                    val text = if (e.has("skill") && skillTitle != null) {
                        context.getString(R.string.skill_done_no_ai, skillTitle)
                    } else e.optString("text")
                    flushAssistant()
                    val steps = toolRunner.traceSnapshot()
                    val recap = if (skillTitle == null) RunRecap(runPrompt, text, steps) else null
                    _state.update {
                        // task_done's summary was never streamed — show it.
                        val lastAssistant = it.messages.lastOrNull { m -> m.role == "assistant" }
                        val msgs = if (text.isNotEmpty() && lastAssistant?.text != text) {
                            it.messages + ChatMsg(nextId(), "assistant", text)
                        } else it.messages
                        it.copy(
                            running = false, pendingAsk = null, messages = msgs,
                            recap = recap, skillRun = null,
                        )
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
                                running = false, pendingAsk = null, skillRun = null,
                                messages = it.messages + ChatMsg(
                                    nextId(), "system", context.getString(R.string.chat_stopped),
                                ),
                            )
                        } else {
                            it.copy(running = false, pendingAsk = null, skillRun = null, error = msg)
                        }
                    }
                    endRun(null)
                }
                "usage" -> {
                    // Normalized by the core: {prompt, completion, cached, requests}.
                    val u = e.optJSONObject("usage") ?: return
                    val t = e.optJSONObject("total")
                    val cached = u.optLong("cached")
                    val text = buildString {
                        append("↑").append(fmtK(u.optLong("prompt")))
                        append(" · ↓").append(fmtK(u.optLong("completion")))
                        if (cached > 0) append(" · cache ").append(fmtK(cached))
                        if (t != null && t.optInt("requests") > 1) {
                            append(" · Σ ↑").append(fmtK(t.optLong("prompt")))
                            append(" ↓").append(fmtK(t.optLong("completion")))
                        }
                    }
                    _state.update { it.copy(usageText = text) }
                }
                "skill" -> {
                    val name = e.optString("name")
                    val run = e.optString("action") == "run"
                    val ok = e.optBoolean("ok")
                    scope.launch { runCatching { skills.recordUse(name, run, ok) } }
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
