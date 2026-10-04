package com.boxagent.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.agent.AgentState
import com.boxagent.app.agent.ChatMsg
import com.boxagent.app.agent.PendingConfirm
import com.boxagent.app.agent.ToolCallUi
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.skills.Skill
import com.boxagent.app.ui.components.Chip
import com.boxagent.app.ui.components.CircleIconButton
import com.boxagent.app.ui.components.IconTile
import com.boxagent.app.ui.components.ListGroup
import com.boxagent.app.ui.components.ListRow
import com.boxagent.app.ui.components.MarkdownText
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.StatusPill
import com.boxagent.app.ui.components.Tag
import com.boxagent.app.ui.components.ToolLook
import com.boxagent.app.ui.theme.BwShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the chat needs to draw, decoupled from the app's services. */
data class ChatUi(
    val state: AgentState,
    val pendingConfirm: PendingConfirm? = null,
    val shellOnline: Boolean = false,
    val a11yOn: Boolean = false,
    val quickSkills: List<Skill> = emptyList(),
    val title: String? = null,
)

class ChatActions(
    val onSend: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onConfirm: (approved: Boolean, always: Boolean) -> Unit = { _, _ -> },
    val onAnswer: (String) -> Unit = {},
    val onSaveRecap: () -> Unit = {},
    val onDismissRecap: () -> Unit = {},
    val onQuickSkill: (Skill) -> Unit = {},
)

@Composable
fun ChatScreen(
    app: BoxAgentApp,
    onSaveAsSkill: (Skill) -> Unit,
    onQuickSkill: (Skill) -> Unit,
) {
    val state by app.agent.state.collectAsState()
    val pending by app.toolRunner.pending.collectAsState()
    val daemon by app.daemon.status.collectAsState()
    val skills by app.skills.all.collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.conversationId) {
        title = if (state.conversationId > 0) {
            app.db.conversations().all().first().firstOrNull { it.id == state.conversationId }?.title
        } else null
    }

    ChatContent(
        ui = ChatUi(
            state = state,
            pendingConfirm = pending,
            shellOnline = daemon.shell == ShellState.ONLINE,
            a11yOn = A11yService.isGranted(ctx),
            quickSkills = skills.filter { it.active }.take(8),
            title = title,
        ),
        actions = ChatActions(
            onSend = app.agent::send,
            onStop = app.agent::cancel,
            onNew = app.agent::newConversation,
            onHistory = { showHistory = true },
            onConfirm = app.toolRunner::resolveConfirm,
            onAnswer = app.agent::answerAsk,
            onSaveRecap = {
                state.recap?.let { r ->
                    onSaveAsSkill(com.boxagent.app.skills.SkillCodec.fromRun(r.prompt, r.summary, r.steps))
                }
                app.agent.dismissRecap()
            },
            onDismissRecap = app.agent::dismissRecap,
            onQuickSkill = onQuickSkill,
        ),
    )

    if (showHistory) {
        HistoryDialog(app, onPick = {
            scope.launch { app.agent.loadConversation(it) }
            showHistory = false
        }, onDismiss = { showHistory = false })
    }
}

@Composable
fun ChatContent(ui: ChatUi, actions: ChatActions) {
    val state = ui.state
    var input by rememberSaveable { mutableStateOf("") }
    val empty = state.messages.isEmpty() && state.currentAssistantText.isEmpty()

    Column(Modifier.fillMaxSize().imePadding()) {
        ChatHeader(ui, actions)

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (empty) {
                item(key = "empty") {
                    ChatEmptyState(ui, onPrompt = { input = it }, onSkill = actions.onQuickSkill)
                }
            }
            items(state.messages, key = { it.id }) { msg ->
                Box(Modifier.animateItem()) { MessageRow(msg) }
            }
            if (state.currentAssistantText.isNotEmpty()) {
                item(key = "stream") {
                    MarkdownText(state.currentAssistantText, Modifier.fillMaxWidth())
                }
            }
            if (state.running && state.currentAssistantText.isEmpty()) {
                item(key = "working") { WorkingRow(state) }
            }
            if (!state.running && state.recap != null) {
                item(key = "recap") {
                    RecapCard(onSave = actions.onSaveRecap, onDismiss = actions.onDismissRecap)
                }
            }
        }
        LaunchedEffect(state.messages.size, state.currentAssistantText.length, state.recap) {
            val n = listState.layoutInfo.totalItemsCount
            if (!empty && n > 0) listState.animateScrollToItem(n - 1)
        }

        AnimatedVisibility(
            visible = state.error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, modifier = Modifier.size(16.dp))
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PendingCards(ui, actions)
        InputDock(
            value = input,
            onChange = { input = it },
            running = state.running,
            onSend = {
                actions.onSend(input)
                input = ""
            },
            onStop = actions.onStop,
        )
    }
}

@Composable
private fun ChatHeader(ui: ChatUi, actions: ChatActions) {
    val state = ui.state
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ui.title ?: stringResource(R.string.chat_new_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                state.running && state.skillRun != null ->
                    stringResource(R.string.chat_skill_running, state.skillRun)
                state.running -> stringResource(R.string.chat_step, state.steps)
                state.usageText.isNotEmpty() -> state.usageText
                else -> null
            }
            AnimatedContent(sub, label = "sub", transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }) { s ->
                if (s != null) {
                    Text(
                        s,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        CircleIconButton(Icons.Rounded.History, stringResource(R.string.chat_history), actions.onHistory)
        CircleIconButton(
            Icons.Rounded.EditNote, stringResource(R.string.chat_new), actions.onNew,
            enabled = !state.running,
        )
    }
}

@Composable
private fun ChatEmptyState(ui: ChatUi, onPrompt: (String) -> Unit, onSkill: (Skill) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(Icons.Rounded.AutoAwesome, size = 52.dp, inverted = true)
            Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(stringResource(R.string.cap_shell), ui.shellOnline)
                StatusPill(stringResource(R.string.cap_a11y), ui.a11yOn)
            }
        }
        if (ui.quickSkills.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCaption(stringResource(R.string.chat_quick_skills))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.quickSkills.forEach { s ->
                        Chip(
                            s.title,
                            icon = if (s.runnable) Icons.Rounded.Bolt else Icons.Rounded.AutoAwesome,
                            onClick = { onSkill(s) },
                        )
                    }
                }
            }
        }
        val suggestions = listOf(
            Icons.Rounded.Tune to stringResource(R.string.suggest_1),
            Icons.Rounded.BatteryStd to stringResource(R.string.suggest_2),
            Icons.Rounded.Image to stringResource(R.string.suggest_3),
            Icons.Rounded.Apps to stringResource(R.string.suggest_4),
        )
        ListGroup(header = stringResource(R.string.chat_try)) {
            suggestions.forEachIndexed { i, (icon, text) ->
                ListRow(
                    title = text,
                    icon = icon,
                    divider = i < suggestions.lastIndex,
                    onClick = { onPrompt(text) },
                ) {
                    Icon(
                        Icons.Rounded.NorthEast, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun MessageRow(msg: ChatMsg) {
    when (msg.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        "tool" -> ToolTimeline(msg.toolCalls)
        "system" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                msg.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(BwShape.Pill)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        else -> MarkdownText(msg.text, Modifier.fillMaxWidth().padding(end = 12.dp))
    }
}

/** A run's tool calls as one card; sub-steps of act/skills are indented. */
@Composable
private fun ToolTimeline(calls: List<ToolCallUi>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            calls.forEach { call -> key(call.id) { ToolRow(call) } }
        }
    }
}

@Composable
private fun ToolRow(call: ToolCallUi) {
    var expanded by remember { mutableStateOf(false) }
    val sub = call.id.contains('.')
    val (ok, err) = ToolLook.outcome(call.result)
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(start = if (sub) 30.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sub) {
                Box(Modifier.width(1.dp).height(22.dp).background(c.outline))
                Spacer(Modifier.width(10.dp))
            }
            IconTile(ToolLook.icon(call.name), size = if (sub) 24.dp else 28.dp, inverted = !ok)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ToolLook.label(call.name), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    val detail = ToolLook.detail(call.name, call.args)
                    if (detail.isNotEmpty()) {
                        Text(
                            "  $detail",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (err != null) {
                    Text(err, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                }
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = call.result == null,
                transitionSpec = { scaleIn(tween(200)) togetherWith scaleOut(tween(120)) },
                label = "status",
            ) { running ->
                if (running) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp), strokeWidth = 1.6.dp, color = c.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (call.durationMs > 0) {
                            Text(
                                if (call.durationMs >= 1000) "%.1fs".format(call.durationMs / 1000.0)
                                else "${call.durationMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Icon(
                            if (ok) Icons.Rounded.Check else Icons.Rounded.Close,
                            null,
                            tint = if (ok) c.onSurfaceVariant else c.onSurface,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surfaceVariant)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonoText(call.args.take(600))
                call.result?.let { MonoText(it.take(1500), maxLines = 30) }
            }
        }
    }
}

@Composable
private fun WorkingRow(state: AgentState) {
    val t = rememberInfiniteTransition(label = "working")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val a by t.animateFloat(
                    0.2f, 1f,
                    infiniteRepeatable(tween(560, delayMillis = i * 150), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(Modifier.size(6.dp).alpha(a).clip(BwShape.Pill).background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(
            state.skillRun?.let { stringResource(R.string.chat_skill_running, it) }
                ?: stringResource(R.string.chat_working),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecapCard(onSave: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(onClick = onSave).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Rounded.AutoAwesome, size = 34.dp, inverted = true)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.skill_save_from_run), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.skill_save_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CircleIconButton(Icons.Rounded.Close, stringResource(R.string.dismiss), onDismiss, size = 34.dp)
        }
    }
}

@Composable
private fun PendingCards(ui: ChatUi, actions: ChatActions) {
    AnimatedVisibility(
        visible = ui.pendingConfirm != null,
        enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(160)),
    ) {
        ui.pendingConfirm?.let { pc -> ConfirmCard(pc.tool, pc.argsJson, pc.risk, actions.onConfirm) }
    }
    AnimatedVisibility(
        visible = ui.state.pendingAsk != null,
        enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(160)),
    ) {
        ui.state.pendingAsk?.let { ask -> AskCard(ask.question, actions.onAnswer) }
    }
}

@Composable
private fun DecisionCard(icon: ImageVector, strong: Boolean, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            IconTile(icon, size = 36.dp, inverted = strong)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun ConfirmCard(
    tool: String,
    args: String,
    risk: String,
    onResolve: (approved: Boolean, always: Boolean) -> Unit,
) {
    DecisionCard(Icons.Rounded.Shield, strong = risk == "destructive") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.approve_action), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ToolLook.label(tool),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Tag(riskText(risk), strong = risk == "destructive")
            }
            MonoText(
                args.take(400),
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .heightIn(max = 110.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.deny), onClick = { onResolve(false, false) }, filled = false)
                PillButton(stringResource(R.string.allow), onClick = { onResolve(true, false) })
                Text(
                    stringResource(R.string.always),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(BwShape.Pill)
                        .clickable { onResolve(true, true) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun riskText(risk: String) = stringResource(
    when (risk) {
        "readonly" -> R.string.risk_readonly
        "destructive" -> R.string.risk_destructive
        else -> R.string.risk_moderate
    },
)

@Composable
private fun AskCard(question: String, onAnswer: (String) -> Unit) {
    var answer by rememberSaveable { mutableStateOf("") }
    DecisionCard(Icons.Rounded.QuestionAnswer, strong = false) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.chat_assistant_asks), style = MaterialTheme.typography.titleMedium)
            Text(question, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillTextField(
                    answer, { answer = it }, stringResource(R.string.chat_answer_hint),
                    Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                CircleIconButton(
                    Icons.Rounded.ArrowUpward, stringResource(R.string.send),
                    onClick = { onAnswer(answer); answer = "" },
                    filled = true, enabled = answer.isNotBlank(), size = 38.dp,
                )
            }
        }
    }
}

@Composable
private fun PillTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 1,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InputDock(
    value: String,
    onChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        PillTextField(
            value, onChange, stringResource(R.string.chat_input_hint),
            Modifier.weight(1f), enabled = !running, maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = running,
            transitionSpec = { scaleIn(tween(180)) togetherWith scaleOut(tween(120)) },
            label = "sendStop",
        ) { r ->
            if (r) {
                CircleIconButton(Icons.Rounded.Stop, stringResource(R.string.stop), onStop, filled = true)
            } else {
                CircleIconButton(
                    Icons.Rounded.ArrowUpward, stringResource(R.string.send), onSend,
                    filled = true, enabled = value.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun HistoryDialog(app: BoxAgentApp, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val convs = remember { mutableStateOf(emptyList<com.boxagent.app.data.db.Conversation>()) }
    LaunchedEffect(Unit) {
        convs.value = app.db.conversations().all().first().take(30)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.conversations), style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn {
                items(convs.value, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(c.id) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.History, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            c.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    )
}
