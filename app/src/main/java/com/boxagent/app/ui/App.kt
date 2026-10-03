package com.boxagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.ui.components.StatusDot
import com.boxagent.app.ui.screens.ChatScreen
import com.boxagent.app.ui.screens.LogsScreen
import com.boxagent.app.ui.screens.OnboardingScreen
import com.boxagent.app.ui.screens.SettingsScreen
import com.boxagent.app.ui.screens.StatusScreen
import com.boxagent.app.ui.screens.ToolsScreen
import com.boxagent.app.ui.theme.BoxAgentTheme

private enum class Tab(val labelRes: Int) {
    CHAT(R.string.tab_agent),
    TOOLS(R.string.tab_tools),
    STATUS(R.string.tab_status),
    LOGS(R.string.tab_logs),
    SETTINGS(R.string.tab_settings),
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
    var tab by remember { mutableStateOf(Tab.CHAT) }
    val daemonStatus by app.daemon.status.collectAsState()
    val agentState by app.agent.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (tab == Tab.CHAT) stringResource(R.string.app_name)
                else stringResource(tab.labelRes),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (agentState.running) {
                Text(
                    stringResource(R.string.step_count, agentState.steps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusDot(
                on = daemonStatus.shell == ShellState.ONLINE,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Hairline()

        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.CHAT -> ChatScreen(app)
                Tab.TOOLS -> ToolsScreen(app)
                Tab.STATUS -> StatusScreen(app)
                Tab.LOGS -> LogsScreen(app)
                Tab.SETTINGS -> SettingsScreen(app)
            }
        }

        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Tab.entries.forEach { t ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clickable { tab = t },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(t.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (t == tab) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .height(2.dp)
                                .fillMaxWidth(0.4f)
                                .background(
                                    if (t == tab) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}
