package com.boxagent.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.agent.ChatMsg
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.ui.Hairline
import com.boxagent.app.ui.components.BwCard
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.StatusPill
import com.boxagent.app.ui.theme.BwShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(app: BoxAgentApp) {
    val state by app.agent.state.collectAsState()
    val pendingConfirm by app.toolRunner.pending.collectAsState()
    val daemonStatus by app.daemon.status.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Conversation controls
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "New",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable(enabled = !state.running) { app.agent.newConversation() }
                    .padding(4.dp),
            )
            Text(
                "History",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { showHistory = true }
                    .padding(4.dp),
            )
            Text(
                "Conv ${if (state.conversationId > 0) "#${state.conversationId}" else "—"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
        }

        // Messages
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (state.messages.isEmpty() && state.currentAssistantText.isEmpty()) {
                item { EmptyState(app, daemonStatus.shell == ShellState.ONLINE, onPrompt = { input = it }) }
            }
            items(state.messages, key = { it.id }) { msg ->
                MessageRow(msg)
            }
            if (state.currentAssistantText.isNotEmpty()) {
                item {
                    MessageRow(
                        ChatMsg(-1, "assistant", state.currentAssistantText, streaming = true),
                    )
                }
            }
            if (state.running && state.currentAssistantText.isEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "working…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        androidx.compose.runtime.LaunchedEffect(state.messages.size, state.currentAssistantText) {
            if (state.messages.isNotEmpty() || state.currentAssistantText.isNotEmpty()) {
                listState.animateScrollToItem(
                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                )
            }
        }

        state.error?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // Pending confirmations and questions
        pendingConfirm?.let { pc ->
            ConfirmCard(
                tool = pc.tool,
                args = pc.argsJson,
                risk = pc.risk,
                onResolve = { approved, always ->
                    app.toolRunner.resolveConfirm(approved, always)
                },
            )
        }
        state.pendingAsk?.let { ask ->
            BwCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Column {
                    Text("Assistant asks", style = MaterialTheme.typography.labelLarge)
                    Text(ask.question, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatInput(
                            value = answer,
                            onChange = { answer = it },
                            modifier = Modifier.weight(1f),
                            placeholder = "answer…",
                        )
                        PillButton("Send", onClick = {
                            app.agent.answerAsk(answer)
                            answer = ""
                        }, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // Input dock
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatInput(
                value = input,
                onChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = "Ask BoxAgent to do something…",
                enabled = !state.running,
            )
            if (state.running) {
                PillButton("Stop", onClick = { app.agent.cancel() },
                    filled = false, modifier = Modifier.padding(start = 8.dp))
            } else {
                PillButton("Send", onClick = {
                    app.agent.send(input)
                    input = ""
                }, enabled = input.isNotBlank(), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (showHistory) {
        HistoryDialog(app, onPick = {
            scope.launch { app.agent.loadConversation(it) }
            showHistory = false
        }, onDismiss = { showHistory = false })
    }
}

@Composable
private fun ChatInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = BwShape.Pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        TextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.heightIn(min = 44.dp),
        )
    }
}

@Composable
private fun EmptyState(app: BoxAgentApp, shellOnline: Boolean, onPrompt: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "BoxAgent",
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            "An operator for this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("shell", shellOnline)
            StatusPill("a11y", A11yService.isEnabled)
        }
        Column(
            Modifier.padding(top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(
                "Open Settings and disable animations",
                "What's my battery level?",
                "Take a screenshot",
                "List my installed apps",
            ).forEach { s ->
                Surface(
                    shape = BwShape.Pill,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.clickable { onPrompt(s) },
                ) {
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: ChatMsg) {
    when (msg.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = BwShape.Card,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        "tool" -> ToolCallsColumn(msg)
        "system" -> Text(
            msg.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        else -> Column(Modifier.fillMaxWidth().padding(end = 24.dp)) {
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
            if (msg.streaming) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, BwShape.Pill),
                )
            }
        }
    }
}

@Composable
private fun ToolCallsColumn(msg: ChatMsg) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        msg.toolCalls.forEach { call ->
            var expanded by remember { mutableStateOf(false) }
            Surface(
                shape = BwShape.Utility,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            call.name,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            if (call.result != null) " · ${call.durationMs}ms" else " · running",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    if (expanded) {
                        MonoText(call.args.take(600), Modifier.padding(top = 6.dp))
                        call.result?.let {
                            MonoText(it.take(1200), Modifier.padding(top = 4.dp), maxLines = 24)
                        }
                    }
                }
            }
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
    BwCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Approve action", style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = BwShape.Pill,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        risk,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(tool, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            MonoText(
                args.take(400),
                Modifier
                    .padding(top = 4.dp)
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 96.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton("Deny", filled = false, onClick = { onResolve(false, false) })
                PillButton("Allow", onClick = { onResolve(true, false) })
                PillButton("Always", filled = false, onClick = { onResolve(true, true) })
            }
        }
    }
}

@Composable
private fun HistoryDialog(app: BoxAgentApp, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val convs = remember { mutableStateOf(emptyList<com.boxagent.app.data.db.Conversation>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        convs.value = app.db.conversations().all().first().take(30)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Conversations", style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn {
                items(convs.value, key = { it.id }) { c ->
                    Text(
                        c.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c.id) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
    )
}
