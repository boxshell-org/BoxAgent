package com.boxagent.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.boxagent.app.ui.components.ToolLook
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.skills.Skill
import com.boxagent.app.skills.SkillCodec
import com.boxagent.app.skills.SkillParam
import com.boxagent.app.skills.SkillSource
import com.boxagent.app.skills.SkillStep
import com.boxagent.app.ui.components.BottomPanel
import com.boxagent.app.ui.components.BwSwitch
import com.boxagent.app.ui.components.Chip
import com.boxagent.app.ui.components.CircleIconButton
import com.boxagent.app.ui.components.EmptyState
import com.boxagent.app.ui.components.FormField
import com.boxagent.app.ui.components.IconTile
import com.boxagent.app.ui.components.LargeTitle
import com.boxagent.app.ui.components.ListGroup
import com.boxagent.app.ui.components.ListRow
import com.boxagent.app.ui.components.MarkdownText
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SearchField
import com.boxagent.app.ui.components.SegmentedControl
import com.boxagent.app.ui.components.Tag
import kotlinx.coroutines.launch

private sealed interface SkillsPage {
    data object Library : SkillsPage
    data class Detail(val name: String) : SkillsPage
    data class Editor(val skill: Skill, val isNew: Boolean) : SkillsPage
}

fun Skill.icon(): ImageVector = when {
    draft -> Icons.Rounded.RateReview
    runnable -> Icons.Rounded.Bolt
    else -> Icons.AutoMirrored.Rounded.MenuBook
}

@Composable
fun SkillSource.label(): String = stringResource(
    when (this) {
        SkillSource.BUILTIN -> R.string.skill_src_builtin
        SkillSource.USER -> R.string.skill_src_user
        SkillSource.RECORDED -> R.string.skill_src_recorded
        SkillSource.AGENT -> R.string.skill_src_agent
        SkillSource.IMPORTED -> R.string.skill_src_imported
    },
)

/**
 * Skills tab. [incoming] opens the editor with a prepared draft (from
 * "Save as skill"); [runRequest] opens the run panel for a skill chosen
 * elsewhere (chat's quick skills).
 */
@Composable
fun SkillsScreen(
    app: BoxAgentApp,
    incoming: Skill?,
    onIncomingConsumed: () -> Unit,
    runRequest: Skill?,
    onRunRequestConsumed: () -> Unit,
    onRunStarted: () -> Unit,
) {
    val skills by app.skills.all.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<SkillsPage>(SkillsPage.Library) }
    var runTarget by remember { mutableStateOf<Skill?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    val hasAi = remember { app.secrets.apiKey.isNotEmpty() }

    LaunchedEffect(incoming) {
        incoming?.let {
            page = SkillsPage.Editor(it.copy(name = app.skills.uniqueName(it.name)), isNew = true)
            onIncomingConsumed()
        }
    }
    LaunchedEffect(runRequest) {
        runRequest?.let { runTarget = it; onRunRequestConsumed() }
    }

    fun run(skill: Skill, params: Map<String, String>, allowAi: Boolean) {
        runTarget = null
        app.agent.runSkill(skill, params, allowAi)
        onRunStarted()
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState !is SkillsPage.Library
                (slideInHorizontally(tween(280)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(280)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160)))
            },
            contentKey = { it::class },
            label = "skillsPage",
        ) { p ->
            when (p) {
                SkillsPage.Library -> SkillsLibrary(
                    skills = skills,
                    onOpen = { page = SkillsPage.Detail(it.name) },
                    onToggle = { s, on -> scope.launch { app.skills.setEnabled(s, on) } },
                    onNew = {
                        editorError = null
                        page = SkillsPage.Editor(
                            Skill(name = "", title = "", source = SkillSource.USER), isNew = true,
                        )
                    },
                    onImport = { importMsg = null; showImport = true },
                )
                is SkillsPage.Detail -> {
                    val s = skills.firstOrNull { it.name == p.name }
                    if (s == null) {
                        LaunchedEffect(Unit) { page = SkillsPage.Library }
                    } else {
                        SkillDetail(
                            skill = s,
                            onBack = { page = SkillsPage.Library },
                            onRun = { runTarget = s },
                            onEdit = { editorError = null; page = SkillsPage.Editor(s, isNew = false) },
                            onShare = { shareSkill(ctx, app.skills.exportText(listOf(s)), s.title) },
                            onDelete = { deleting = s },
                            onApprove = { scope.launch { app.skills.approve(s) } },
                            onToggle = { on -> scope.launch { app.skills.setEnabled(s, on) } },
                        )
                    }
                }
                is SkillsPage.Editor -> SkillEditor(
                    initial = p.skill,
                    isNew = p.isNew,
                    error = editorError,
                    onCancel = {
                        page = if (p.isNew) SkillsPage.Library else SkillsPage.Detail(p.skill.name)
                    },
                    onSave = { edited ->
                        scope.launch {
                            app.skills.save(edited).fold(
                                onSuccess = { page = SkillsPage.Detail(it.name); editorError = null },
                                onFailure = { editorError = it.message },
                            )
                        }
                    },
                )
            }
        }

        BottomPanel(visible = runTarget != null, onDismiss = { runTarget = null }) {
            runTarget?.let { RunSkillPanel(it, hasAi, onRun = { params, ai -> run(it, params, ai) }) }
        }
        BottomPanel(visible = showImport, onDismiss = { showImport = false }) {
            ImportPanel(
                message = importMsg,
                onImport = { text ->
                    scope.launch {
                        app.skills.importText(text).fold(
                            onSuccess = { n -> importMsg = ctx.getString(R.string.skill_imported, n) },
                            onFailure = { e -> importMsg = e.message },
                        )
                    }
                },
            )
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.skill_delete_confirm, s.title), style = MaterialTheme.typography.titleLarge) },
            confirmButton = {
                PillButton(stringResource(R.string.skill_delete), onClick = {
                    scope.launch { app.skills.delete(s.id) }
                    deleting = null
                    page = SkillsPage.Library
                })
            },
            dismissButton = {
                PillButton(stringResource(R.string.cancel), onClick = { deleting = null }, filled = false)
            },
        )
    }
}

private fun shareSkill(ctx: Context, json: String, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_SUBJECT, "BoxAgent skill: $title")
        .putExtra(Intent.EXTRA_TEXT, json)
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------- library

@Composable
fun SkillsLibrary(
    skills: List<Skill>,
    onOpen: (Skill) -> Unit,
    onToggle: (Skill, Boolean) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    val drafts = skills.count { it.draft }
    val shown = skills.filter { s ->
        val q = query.trim().lowercase()
        (q.isEmpty() || q in s.title.lowercase() || q in s.description.lowercase() || q in s.name) &&
            when (filter) {
                1 -> s.runnable && !s.draft
                2 -> s.draft
                else -> true
            }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            LargeTitle(
                stringResource(R.string.skills_title),
                subtitle = stringResource(
                    R.string.skills_subtitle, skills.size, skills.count { it.runnable && it.active },
                ),
            ) {
                CircleIconButton(Icons.Rounded.FileDownload, stringResource(R.string.skill_import), onImport)
                CircleIconButton(Icons.Rounded.Add, stringResource(R.string.skill_new), onNew, filled = true, size = 36.dp)
            }
        }
        item(key = "search") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.skills_search))
                SegmentedControl(
                    listOf(
                        stringResource(R.string.skills_filter_all),
                        stringResource(R.string.skills_filter_runnable),
                        stringResource(R.string.skills_filter_review) + if (drafts > 0) " · $drafts" else "",
                    ),
                    filter, { filter = it },
                )
            }
        }
        if (drafts > 0 && filter != 2) {
            item(key = "review") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { filter = 2 },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.RateReview, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pluralStringResource(R.plurals.skills_review_banner, drafts, drafts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        if (skills.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.Rounded.AutoAwesome,
                    stringResource(R.string.skills_empty_title),
                    stringResource(R.string.skills_empty_body),
                ) {
                    PillButton(stringResource(R.string.skill_new), onClick = onNew)
                }
            }
        } else if (shown.isEmpty()) {
            item(key = "nomatch") {
                Text(
                    stringResource(R.string.skills_none_match),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        items(shown, key = { it.id.takeIf { id -> id != 0L } ?: it.name }) { s ->
            Box(Modifier.padding(horizontal = 20.dp).animateItem()) {
                SkillCard(s, onClick = { onOpen(s) }, onToggle = { onToggle(s, it) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillCard(skill: Skill, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.outline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp)) {
            IconTile(skill.icon(), size = 42.dp, inverted = skill.runnable && skill.active)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (skill.draft) Tag(stringResource(R.string.skill_draft), strong = true)
                    else Tag(skill.source.label())
                }
                if (skill.description.isNotEmpty()) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    skill.params.forEach { ParamCapsule(it.name) }
                    val rate = skill.successRate
                    val stats = listOfNotNull(
                        if (skill.runnable) pluralStringResource(R.plurals.skill_steps, skill.steps.size, skill.steps.size)
                        else stringResource(R.string.skill_instructions_only),
                        when {
                            rate != null -> stringResource(R.string.skill_runs_stat, skill.runs, rate)
                            skill.uses > 0 -> stringResource(R.string.skill_used_stat, skill.uses)
                            else -> null
                        },
                    )
                    MetaText(stats.joinToString("  ·  "))
                }
            }
            if (!skill.draft) {
                Spacer(Modifier.width(8.dp))
                BwSwitch(skill.enabled, onToggle)
            }
        }
    }
}

/** `{query}` — a parameter, in a small mono capsule. */
@Composable
private fun ParamCapsule(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun MetaText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- detail

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillDetail(
    skill: Skill,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onApprove: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack)
            Spacer(Modifier.weight(1f))
            CircleIconButton(Icons.Rounded.Share, stringResource(R.string.skill_share), onShare)
            CircleIconButton(Icons.Rounded.Edit, stringResource(R.string.skill_edit), onEdit)
            CircleIconButton(Icons.Rounded.Delete, stringResource(R.string.skill_delete), onDelete)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Hero
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(skill.icon(), size = 60.dp, inverted = skill.runnable)
                Text(skill.title, style = MaterialTheme.typography.headlineLarge)
                if (skill.description.isNotEmpty()) {
                    Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (skill.draft) Tag(stringResource(R.string.skill_draft), strong = true)
                    Tag(skill.source.label())
                    Tag(
                        if (skill.runnable) pluralStringResource(R.plurals.skill_steps, skill.steps.size, skill.steps.size)
                        else stringResource(R.string.skill_instructions_only),
                    )
                    Tag("/${skill.name}")
                }
            }

            if (skill.draft) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = c.surfaceVariant,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.skill_draft_note), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(stringResource(R.string.skill_approve), onClick = onApprove)
                            PillButton(stringResource(R.string.skill_discard), onClick = onDelete, filled = false)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    stringResource(if (skill.runnable) R.string.skill_run else R.string.skill_run_ai),
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(if (skill.runnable) R.string.skill_no_ai_note else R.string.skill_guide_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            if (skill.params.isNotEmpty()) {
                ListGroup(header = stringResource(R.string.skill_sec_params)) {
                    skill.params.forEachIndexed { i, p ->
                        ListRow(
                            title = p.name,
                            subtitle = listOfNotNull(
                                p.description.takeIf { it.isNotEmpty() },
                                p.default?.takeIf { it.isNotEmpty() }?.let { "= $it" },
                            ).joinToString("  ").ifEmpty { null },
                            divider = i < skill.params.lastIndex,
                        ) {
                            if (!p.required) Tag(stringResource(R.string.skill_optional))
                        }
                    }
                }
            }

            if (skill.runnable) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(stringResource(R.string.skill_sec_steps))
                    StepsTimeline(skill.steps)
                }
            }

            if (skill.instructions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(stringResource(R.string.skill_sec_instructions))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = c.surfaceContainerLow,
                        border = BorderStroke(1.dp, c.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MarkdownText(skill.instructions, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (skill.apps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(stringResource(R.string.skill_sec_apps))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        skill.apps.forEach { Chip(it) }
                    }
                }
            }

            ListGroup(header = stringResource(R.string.skill_sec_usage)) {
                ListRow(
                    title = stringResource(R.string.skill_used_stat, skill.uses),
                    subtitle = skill.successRate?.let { stringResource(R.string.skill_runs_stat, skill.runs, it) },
                    divider = !skill.draft,
                ) {}
                if (!skill.draft) {
                    ListRow(title = stringResource(R.string.skill_enabled)) {
                        BwSwitch(skill.enabled, onToggle)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/** Numbered steps joined by a hairline rail. */
@Composable
fun StepsTimeline(steps: List<SkillStep>) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = c.surfaceContainerLow,
        border = BorderStroke(1.dp, c.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            steps.forEachIndexed { i, st ->
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(c.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = c.onPrimary)
                        }
                        if (i < steps.lastIndex) {
                            Box(Modifier.width(1.dp).height(18.dp).background(c.outline))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        Modifier.weight(1f).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            ToolLook.icon(st.tool), null,
                            tint = c.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val detail = SkillCodec.describe(st).substringAfter(" · ", "")
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(ToolLook.label(st.tool))
                                }
                                if (detail.isNotEmpty()) {
                                    withStyle(SpanStyle(color = c.onSurfaceVariant)) { append("  $detail") }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (st.optional) {
                            Spacer(Modifier.width(6.dp))
                            Tag(stringResource(R.string.skill_optional))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- run / import

@Composable
fun RunSkillPanel(skill: Skill, hasAi: Boolean, onRun: (params: Map<String, String>, allowAi: Boolean) -> Unit) {
    val values = remember(skill.name) {
        mutableStateListOf(*skill.params.map { it.default.orEmpty() }.toTypedArray())
    }
    var aiFallback by remember { mutableStateOf(hasAi) }
    val missing = skill.params.indices.any { skill.params[it].required && values[it].isBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(skill.icon(), size = 40.dp, inverted = skill.runnable)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.skill_run_title, skill.title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(if (skill.runnable) R.string.skill_no_ai_note else R.string.skill_guide_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        skill.params.forEachIndexed { i, p ->
            FormField(
                label = p.name + if (p.required) "" else " · " + stringResource(R.string.skill_optional),
                value = values[i],
                onChange = { values[i] = it },
                placeholder = p.description,
            )
        }
        if (skill.runnable && hasAi) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.skill_ai_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                BwSwitch(aiFallback, { aiFallback = it })
            }
        }
        PillButton(
            stringResource(if (skill.runnable) R.string.skill_run else R.string.skill_run_ai),
            onClick = {
                onRun(
                    skill.params.mapIndexedNotNull { i, p ->
                        values[i].trim().takeIf { it.isNotEmpty() }?.let { p.name to it }
                    }.toMap(),
                    aiFallback,
                )
            },
            enabled = !missing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ImportPanel(message: String?, onImport: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.skill_import_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.skill_import_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormField("", text, { text = it }, minLines = 5, maxLines = 10, mono = true, placeholder = "{ \"boxagent_skill\": 1, … }")
        AnimatedVisibility(message != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.skill_import_paste), filled = false, onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text = it }
            })
            PillButton(stringResource(R.string.skill_import), onClick = { onImport(text) }, enabled = text.isNotBlank())
        }
    }
}

// ---------------------------------------------------------------- editor

private class StepDraft(tool: String, args: String, optional: Boolean) {
    var tool by mutableStateOf(tool)
    var args by mutableStateOf(args)
    var optional by mutableStateOf(optional)
}

private class ParamDraft(name: String, description: String, required: Boolean, default: String) {
    var name by mutableStateOf(name)
    var description by mutableStateOf(description)
    var required by mutableStateOf(required)
    var default by mutableStateOf(default)
}

@Composable
fun SkillEditor(
    initial: Skill,
    isNew: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (Skill) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var name by remember { mutableStateOf(initial.name) }
    var nameTouched by remember { mutableStateOf(!isNew || initial.name.isNotEmpty()) }
    var description by remember { mutableStateOf(initial.description) }
    var instructions by remember { mutableStateOf(initial.instructions) }
    var apps by remember { mutableStateOf(initial.apps.joinToString(", ")) }
    val params = remember {
        mutableStateListOf(*initial.params.map {
            ParamDraft(it.name, it.description, it.required, it.default.orEmpty())
        }.toTypedArray())
    }
    val steps = remember {
        mutableStateListOf(*initial.steps.map { StepDraft(it.tool, prettyArgs(it.argsJson), it.optional) }.toTypedArray())
    }

    fun build() = initial.copy(
        title = title.trim(),
        name = name.trim(),
        description = description.trim(),
        instructions = instructions.trim(),
        apps = apps.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
        params = params.filter { it.name.isNotBlank() }.map {
            SkillParam(it.name.trim(), it.description.trim(), it.required, it.default.takeIf { d -> d.isNotEmpty() })
        },
        steps = steps.filter { it.tool.isNotBlank() }.map {
            SkillStep(it.tool.trim(), compactArgs(it.args), it.optional)
        },
    )
    val problems = remember(title, name, description, instructions, apps,
        params.map { it.name }, steps.map { it.tool + it.args }) {
        SkillCodec.validate(build())
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.Rounded.Close, stringResource(R.string.cancel), onCancel)
            Text(
                stringResource(if (isNew) R.string.skill_editor_new else R.string.skill_editor_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            PillButton(stringResource(R.string.save), onClick = { onSave(build()) }, enabled = problems.isEmpty())
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(12.dp),
                )
            }
            FormField(stringResource(R.string.skill_f_title), title, {
                title = it
                if (!nameTouched) name = SkillCodec.slugify(it)
            })
            FormField(stringResource(R.string.skill_f_name), name, { name = it; nameTouched = true }, mono = true)
            FormField(stringResource(R.string.skill_f_description), description, { description = it })
            FormField(stringResource(R.string.skill_f_instructions), instructions, { instructions = it }, minLines = 4)
            FormField(stringResource(R.string.skill_f_apps), apps, { apps = it }, mono = true)

            SectionHeader(stringResource(R.string.skill_sec_params))
            params.forEachIndexed { i, p ->
                EditorCard(
                    index = null,
                    title = p.name.ifBlank { "…" }.let { "{$it}" },
                    onDelete = { params.removeAt(i) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormField(stringResource(R.string.skill_f_param_name), p.name, { p.name = it }, Modifier.weight(1f), mono = true)
                        FormField(stringResource(R.string.skill_f_param_default), p.default, { p.default = it }, Modifier.weight(1f))
                    }
                    FormField(stringResource(R.string.skill_f_param_desc), p.description, { p.description = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.skill_optional), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        BwSwitch(!p.required, { p.required = !it })
                    }
                }
            }
            Chip(stringResource(R.string.skill_add_param), icon = Icons.Rounded.Add, onClick = {
                params += ParamDraft("", "", true, "")
            })

            SectionHeader(stringResource(R.string.skill_sec_steps))
            steps.forEachIndexed { i, st ->
                EditorCard(
                    index = i + 1,
                    title = ToolLook.label(st.tool.ifBlank { "…" }),
                    onDelete = { steps.removeAt(i) },
                    onUp = if (i > 0) ({ steps.add(i - 1, steps.removeAt(i)) }) else null,
                    onDown = if (i < steps.lastIndex) ({ steps.add(i + 1, steps.removeAt(i)) }) else null,
                ) {
                    FormField(stringResource(R.string.skill_f_step_tool), st.tool, { st.tool = it }, mono = true)
                    FormField(stringResource(R.string.skill_f_step_args), st.args, { st.args = it }, minLines = 2, mono = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.skill_optional), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        BwSwitch(st.optional, { st.optional = it })
                    }
                }
            }
            Chip(stringResource(R.string.skill_add_step), icon = Icons.Rounded.Add, onClick = {
                steps += StepDraft("tap", "{\n  \"text\": \"\"\n}", false)
            })

            if (problems.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    problems.forEach {
                        Text("• $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EditorCard(
    index: Int?,
    title: String,
    onDelete: () -> Unit,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index != null) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onUp != null) CircleIconButton(Icons.Rounded.KeyboardArrowUp, null, onUp, size = 32.dp)
                if (onDown != null) CircleIconButton(Icons.Rounded.KeyboardArrowDown, null, onDown, size = 32.dp)
                CircleIconButton(Icons.Rounded.Delete, stringResource(R.string.skill_delete), onDelete, size = 32.dp)
            }
            content()
        }
    }
}

private fun prettyArgs(json: String): String =
    runCatching { org.json.JSONObject(json).toString(2) }.getOrDefault(json)

private fun compactArgs(text: String): String =
    runCatching { org.json.JSONObject(text.ifBlank { "{}" }).toString() }.getOrDefault(text)
