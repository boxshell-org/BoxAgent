package com.boxagent.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.skills.Skill
import com.boxagent.app.ui.components.LargeTitle
import com.boxagent.app.ui.screens.ChatScreen
import com.boxagent.app.ui.screens.OnboardingScreen
import com.boxagent.app.ui.screens.SettingsScreen
import com.boxagent.app.ui.screens.SkillsScreen
import com.boxagent.app.ui.screens.StatusScreen
import com.boxagent.app.ui.screens.ToolsScreen
import com.boxagent.app.ui.theme.BoxAgentTheme

enum class Tab(val labelRes: Int, val icon: ImageVector, val iconSelected: ImageVector) {
    CHAT(R.string.tab_agent, Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    SKILLS(R.string.tab_skills, Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    TOOLS(R.string.tab_tools, Icons.Outlined.Handyman, Icons.Rounded.Handyman),
    STATUS(R.string.tab_status, Icons.Outlined.MonitorHeart, Icons.Rounded.MonitorHeart),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun App(app: BoxAgentApp) {
    val theme by app.settings.theme.collectAsState(initial = "system")
    val onboarded by app.settings.onboarded.collectAsState(initial = null)

    BoxAgentTheme(theme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (onboarded) {
                null -> {}
                false -> OnboardingScreen(app)
                else -> MainShell(app)
            }
        }
    }
}

@Composable
private fun MainShell(app: BoxAgentApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    // Hand-offs between tabs: a run to save as a skill, a skill to run.
    var skillDraft by remember { mutableStateOf<Skill?>(null) }
    var runRequest by remember { mutableStateOf<Skill?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(260)) { if (forward) it / 5 else -it / 5 } +
                        fadeIn(tween(220)))
                        .togetherWith(
                            slideOutHorizontally(tween(260)) { if (forward) -it / 5 else it / 5 } +
                                fadeOut(tween(160)),
                        )
                },
                label = "tabContent",
            ) { t ->
                when (t) {
                    Tab.CHAT -> ChatScreen(
                        app,
                        onSaveAsSkill = { skillDraft = it; tab = Tab.SKILLS },
                        onQuickSkill = { s ->
                            // No inputs needed: just run it here.
                            if (s.params.none { it.required && it.default == null }) {
                                app.agent.runSkill(s, emptyMap())
                            } else {
                                runRequest = s
                                tab = Tab.SKILLS
                            }
                        },
                    )
                    Tab.SKILLS -> SkillsScreen(
                        app,
                        incoming = skillDraft,
                        onIncomingConsumed = { skillDraft = null },
                        runRequest = runRequest,
                        onRunRequestConsumed = { runRequest = null },
                        onRunStarted = { tab = Tab.CHAT },
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                        LargeTitle(stringResource(t.labelRes))
                        Box(Modifier.weight(1f)) {
                            when (t) {
                                Tab.TOOLS -> ToolsScreen(app)
                                Tab.STATUS -> StatusScreen(app)
                                else -> SettingsScreen(app)
                            }
                        }
                    }
                }
            }
        }
        TabBar(tab, onSelect = { tab = it })
    }
}

@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Hairline()
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t -> TabItem(t, t == selected) { onSelect(t) } }
        }
    }
}

@Composable
private fun TabItem(t: Tab, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "tabPress",
    )
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        tween(200),
        label = "tabColor",
    )
    Column(
        Modifier
            .scale(scale)
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (selected) t.iconSelected else t.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Text(
            stringResource(t.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}
