package com.boxagent.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.agent.ToolCatalog
import com.boxagent.app.agent.ToolSpec
import com.boxagent.app.ui.Hairline
import com.boxagent.app.ui.components.MonoText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.StatusPill
import com.boxagent.app.ui.components.bwTextFieldColors
import com.boxagent.app.ui.theme.BwShape
import com.boxagent.app.ui.components.ListGroup
import com.boxagent.app.ui.components.ListRow
import com.boxagent.app.ui.components.SearchField
import com.boxagent.app.ui.components.Tag
import com.boxagent.app.ui.components.ToolLook
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(app: BoxAgentApp) {
    var selected by remember { mutableStateOf<ToolSpec?>(null) }
    val tools = remember { ToolCatalog.all() }
    ToolsList(tools, onSelect = { selected = it })
    selected?.let { tool ->
        ToolDialog(app, tool, onDismiss = { selected = null })
    }
}

/** Catalog grouped by backend, searchable. Stateless (screenshot-tested). */
@Composable
fun ToolsList(tools: List<ToolSpec>, onSelect: (ToolSpec) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = tools.filter {
        query.isEmpty() || it.name.contains(query, true) || it.summary.contains(query, true)
    }
    val groups = listOf(
        "a11y" to stringResource(R.string.tools_group_ui),
        "shell" to stringResource(R.string.tools_group_shell),
        "meta" to stringResource(R.string.tools_group_agent),
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
    ) {
        item(key = "search") {
            SearchField(query, { query = it }, stringResource(R.string.search_tools), Modifier.padding(top = 4.dp))
        }
        groups.forEach { (backend, title) ->
            val inGroup = filtered.filter { it.backend == backend }
            if (inGroup.isNotEmpty()) {
                item(key = backend) {
                    ListGroup(header = "$title · ${inGroup.size}") {
                        inGroup.forEachIndexed { i, tool ->
                            ListRow(
                                title = ToolLook.label(tool.name),
                                // Summary often just repeats the label —
                                // show the call name then.
                                subtitle = tool.summary.takeIf {
                                    !it.equals(ToolLook.label(tool.name), ignoreCase = true)
                                } ?: tool.name,
                                icon = ToolLook.icon(tool.name),
                                divider = i < inGroup.lastIndex,
                                onClick = { onSelect(tool) },
                            ) {
                                if (tool.risk != "readonly") {
                                    Tag(riskLabel(tool.risk), strong = tool.risk == "destructive")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: String) {
    Surface(
        shape = BwShape.Pill,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = if (risk == "destructive") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
    ) {
        Text(
            riskLabel(risk),
            style = MaterialTheme.typography.labelSmall,
            color = if (risk == "destructive") MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ToolDialog(app: BoxAgentApp, tool: ToolSpec, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var args by remember { mutableStateOf(defaultArgs(tool)) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val pending by app.toolRunner.pending.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                RiskBadge(tool.risk)
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tool.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MonoText(prettySchema(tool, stringResource(R.string.no_params)), Modifier.fillMaxWidth())
                TextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.args_json)) },
                    colors = bwTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                )
                if (pending != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.deny), filled = false, onClick = {
                            app.toolRunner.resolveConfirm(false, false)
                        })
                        PillButton(stringResource(R.string.approve), onClick = {
                            app.toolRunner.resolveConfirm(true, false)
                        })
                    }
                }
                result?.let {
                    MonoText(it, Modifier.fillMaxWidth(), maxLines = 30)
                }
            }
        },
        confirmButton = {
            PillButton(
            if (running) stringResource(R.string.running_btn)
            else stringResource(R.string.run),
            enabled = !running, onClick = {
                running = true
                result = null
                scope.launch {
                    result = runCatching {
                        org.json.JSONObject(app.toolRunner.execute(tool.name, args)).toString(2)
                    }.getOrElse { it.message ?: "error" }
                    running = false
                }
            })
        },
        dismissButton = { PillButton(stringResource(R.string.close), filled = false, onClick = onDismiss) },
    )
}

private fun prettySchema(tool: ToolSpec, noParams: String): String {
    val props = tool.parameters.optJSONObject("properties") ?: return noParams
    val sb = StringBuilder()
    val req = tool.parameters.optJSONArray("required")
    props.keys().forEach { k ->
        val p = props.getJSONObject(k)
        val required = req?.let { (0 until it.length()).any { i -> it.getString(i) == k } } == true
        sb.append("  $k: ${p.optString("type")}${if (required) " *" else ""} — ${p.optString("description")}\n")
    }
    return sb.toString().ifEmpty { noParams }
}

private fun defaultArgs(tool: ToolSpec): String {
    val props = tool.parameters.optJSONObject("properties") ?: return "{}"
    val req = tool.parameters.optJSONArray("required")
    val out = org.json.JSONObject()
    props.keys().forEach { k ->
        val required = req?.let { (0 until it.length()).any { i -> it.getString(i) == k } } == true
        if (required) {
            val type = props.getJSONObject(k).optString("type")
            out.put(
                k,
                when (type) {
                    "integer" -> 0
                    "boolean" -> false
                    else -> ""
                },
            )
        }
    }
    return out.toString()
}

@Composable
private fun riskLabel(risk: String): String = stringResource(
    when (risk) {
        "readonly" -> R.string.risk_readonly
        "destructive" -> R.string.risk_destructive
        else -> R.string.risk_moderate
    },
)
