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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.service.A11yService
import com.boxagent.app.ui.components.PillButton
import com.boxagent.app.ui.components.SectionLabel
import com.boxagent.app.ui.components.StatusDot
import com.boxagent.app.ui.components.StatusPill
import com.boxagent.app.ui.components.bwTextFieldColors
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
    var pairedOk by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
    ) {
        Text("BoxAgent", style = MaterialTheme.typography.displayMedium)
        Text(
            stringResource(R.string.onboard_tagline),
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
                    title = stringResource(R.string.step1_title),
                    body = stringResource(R.string.step1_body),
                    status = {
                        StatusPill(
                            stringResource(R.string.paired),
                            pairedOk || st.shell == ShellState.CONNECTING ||
                                st.shell == ShellState.ONLINE,
                        )
                    },
                    content = {
                        Field(stringResource(R.string.host), host) { host = it }
                        Field(stringResource(R.string.pairing_port), pairPort) { pairPort = it }
                        Field(stringResource(R.string.pairing_code), pairCode) { pairCode = it }
                    },
                    action = {
                        PillButton(
                            stringResource(if (busy) R.string.pairing else R.string.pair),
                            enabled = !busy && pairPort.isNotEmpty() && pairCode.isNotEmpty(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    app.daemon.pair(
                                        host.trim(),
                                        pairPort.trim().toIntOrNull() ?: 0,
                                        pairCode.filter { it.isDigit() },
                                    )
                                        .onSuccess {
                                            pairedOk = true
                                            note = ctx.getString(R.string.paired) + ": $it"
                                        }
                                        .onFailure { note = it.message }
                                    busy = false
                                }
                            },
                        )
                    },
                )
                1 -> StepPage(
                    number = "2",
                    title = stringResource(R.string.step2_title),
                    body = stringResource(R.string.step2_body),
                    status = {
                        StatusPill(
                            stringResource(R.string.cap_shell), st.shell == ShellState.ONLINE,
                            if (st.shell == ShellState.ONLINE) "uid 2000" else "",
                        )
                    },
                    content = {
                        Field(stringResource(R.string.host), host) { host = it }
                        Field(stringResource(R.string.connect_port), connectPort) { connectPort = it }
                    },
                    action = {
                        PillButton(
                            stringResource(if (busy) R.string.connecting else R.string.connect_spawn),
                            enabled = !busy && connectPort.isNotEmpty(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    app.daemon.connectAndSpawn(
                                        host.trim(),
                                        connectPort.trim().toIntOrNull() ?: 0,
                                    )
                                        .onFailure { note = it.message }
                                    busy = false
                                }
                            },
                        )
                    },
                )
                else -> StepPage(
                    number = "3",
                    title = stringResource(R.string.step3_title),
                    body = stringResource(R.string.step3_body),
                    status = { StatusPill(stringResource(R.string.cap_a11y), a11yOn) },
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(stringResource(R.string.enable_a11y), filled = false, onClick = {
                                ctx.startActivity(
                                    Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            })
                            PillButton(stringResource(R.string.battery), filled = false, onClick = {
                                ctx.startActivity(
                                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${ctx.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            })
                        }
                    },
                    action = {
                        PillButton(
                            stringResource(R.string.finish),
                            enabled = st.shell == ShellState.ONLINE,
                            onClick = {
                                scope.launch {
                                    app.settings.setOnboarded(true)
                                }
                            },
                        )
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
            PillButton(stringResource(R.string.skip_for_now), filled = false, onClick = {
                scope.launch { app.settings.setOnboarded(true) }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    StatusDot(on = i <= pager.currentPage, size = 7.dp)
                }
            }
        }
    }

    // Auto-advance on success
    LaunchedEffect(st.shell, pager.currentPage) {
        if (pager.currentPage == 0 && pairedOk) {
            delay(600); pager.animateScrollToPage(1)
        } else if (pager.currentPage == 1 && st.shell == ShellState.ONLINE) {
            delay(600); pager.animateScrollToPage(2)
        }
    }
}

@Composable
private fun rememberA11yState(): Boolean {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(A11yService.isGranted(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            on = A11yService.isGranted(ctx)
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
        colors = bwTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
