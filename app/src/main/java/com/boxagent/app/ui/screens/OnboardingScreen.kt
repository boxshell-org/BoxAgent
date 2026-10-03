package com.boxagent.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.StatusPill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided setup: developer mode → pair → connect+spawn → a11y → battery.
 * Detection-driven: each page polls its capability and advances itself.
 */
@Composable
fun OnboardingScreen(app: BoxAgentApp) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val st by app.daemon.status.collectAsState()
    val a11yOn = rememberA11yState()
    val pager = rememberPagerState(pageCount = { 3 })

    var host by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
    ) {
        Text("BoxAgent", style = MaterialTheme.typography.displayMedium)
        Text(
            "Three steps to give it hands.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .padding(top = 32.dp),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> StepPage(
                    number = "1",
                    title = "Pair with wireless debugging",
                    body = "Settings → Developer Options → Wireless Debugging → " +
                        "“Pair with pairing code”. Enter the port and code shown there.",
                    status = { StatusPill("paired", st.shell != ShellState.OFFLINE || note?.contains("paired") == true) },
                    content = {
                        Field("Host", host) { host = it }
                        Field("Pairing port", pairPort) { pairPort = it }
                        Field("Pairing code", pairCode) { pairCode = it }
                    },
                    action = {
                        PillButton(if (busy) "Pairing…" else "Pair", enabled = !busy && pairPort.isNotEmpty() && pairCode.isNotEmpty()) {
                            busy = true
                            scope.launch {
                                app.daemon.pair(host, pairPort.toIntOrNull() ?: 0, pairCode)
                                    .onSuccess { note = "paired: $it" }
                                    .onFailure { note = it.message }
                                busy = false
                            }
                        }
                    },
                )
                1 -> StepPage(
                    number = "2",
                    title = "Spawn the daemon",
                    body = "Now enter the connect port — the one on the main Wireless " +
                        "Debugging screen (different from the pairing port).",
                    status = {
                        StatusPill(
                            "shell", st.shell == ShellState.ONLINE,
                            if (st.shell == ShellState.ONLINE) "uid 2000" else "",
                        )
                    },
                    content = {
                        Field("Host", host) { host = it }
                        Field("Connect port", connectPort) { connectPort = it }
                    },
                    action = {
                        PillButton(if (busy) "Connecting…" else "Connect & spawn", enabled = !busy && connectPort.isNotEmpty()) {
                            busy = true
                            scope.launch {
                                app.daemon.connectAndSpawn(host, connectPort.toIntOrNull() ?: 0)
                                    .onFailure { note = it.message }
                                busy = false
                            }
                        }
                    },
                )
                else -> StepPage(
                    number = "3",
                    title = "Accessibility & battery",
                    body = "Enable BoxAgent in Accessibility Settings so it can read " +
                        "screens and tap. Exclude it from battery optimisation so the " +
                        "agent survives in the background.",
                    status = { StatusPill("a11y", a11yOn) },
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Enable a11y", filled = false) {
                                ctx.startActivity(
                                    Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            PillButton("Battery", filled = false) {
                                ctx.startActivity(
                                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${ctx.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    action = {
                        PillButton("Finish", enabled = st.shell == ShellState.ONLINE) {
                            scope.launch {
                                app.settings.setOnboarded(true)
                            }
                        }
                    },
                )
            }
        }

        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PillButton("Skip for now", filled = false) {
                scope.launch { app.settings.setOnboarded(true) }
            }
            Text(
                "${pager.currentPage + 1} / 3",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Auto-advance on success
    LaunchedEffect(st.shell, pager.currentPage) {
        if (pager.currentPage == 0 && note?.startsWith("paired") == true) {
            delay(600); pager.animateScrollToPage(1)
        } else if (pager.currentPage == 1 && st.shell == ShellState.ONLINE) {
            delay(600); pager.animateScrollToPage(2)
        }
    }
}

@Composable
private fun rememberA11yState(): Boolean {
    var on by remember { mutableStateOf(A11yService.isEnabled) }
    LaunchedEffect(Unit) {
        while (true) {
            on = A11yService.isEnabled
            delay(1000)
        }
    }
    return on
}

@Composable
private fun StepPage(
    number: String,
    title: String,
    body: String,
    status: @Composable () -> Unit,
    content: @Composable () -> Unit,
    action: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                number,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 12.dp),
            )
            status()
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        action()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
