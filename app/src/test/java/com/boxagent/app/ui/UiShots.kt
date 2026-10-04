package com.boxagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.boxagent.app.agent.AgentState
import com.boxagent.app.agent.ChatMsg
import com.boxagent.app.agent.PendingConfirm
import com.boxagent.app.agent.RunRecap
import com.boxagent.app.agent.ToolCallUi
import com.boxagent.app.skills.BuiltinSkills
import com.boxagent.app.skills.Skill
import com.boxagent.app.skills.SkillParam
import com.boxagent.app.skills.SkillSource
import com.boxagent.app.skills.SkillStep
import com.boxagent.app.ui.components.BottomPanel
import com.boxagent.app.ui.screens.ChatActions
import com.boxagent.app.ui.screens.ChatContent
import com.boxagent.app.ui.screens.ChatUi
import com.boxagent.app.ui.screens.RunSkillPanel
import com.boxagent.app.ui.screens.SkillDetail
import com.boxagent.app.ui.screens.SkillEditor
import com.boxagent.app.ui.screens.SkillsLibrary
import com.boxagent.app.ui.screens.ToolsList
import com.boxagent.app.agent.ToolSpec
import com.boxagent.app.ui.components.LargeTitle
import com.boxagent.app.ui.theme.BoxAgentTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot renders of the main screens (light + dark) with realistic
 * data. `gradle :app:recordPaparazziDebug` writes them to
 * src/test/snapshots/images for review.
 */
class UiShots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private fun shot(dark: Boolean = false, tab: Tab? = null, content: @Composable () -> Unit) {
        paparazzi.snapshot {
            BoxAgentTheme(if (dark) "dark" else "light") {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.weight(1f)) { content() }
                    if (tab != null) TabBar(tab) {}
                }
            }
        }
    }

    private val library: List<Skill> = BuiltinSkills.forLocale("en").mapIndexed { i, s ->
        val runs = listOf(14, 3, 0, 6, 2, 0, 1, 0, 0, 0)[i]
        s.copy(id = i + 2L, uses = runs + (if (i == 7) 5 else 0), runs = runs,
            successes = (runs * 0.86).toInt(), enabled = i != 9)
    } + Skill(
        id = 1, name = "order-coffee", title = "Order my usual coffee",
        description = "Reorder the last coffee in the Starbucks app",
        instructions = "Open Starbucks, go to Order › Previous, add the top item, check out with the saved card.",
        params = listOf(SkillParam("size", "tall, grande or venti", required = false, default = "grande")),
        steps = listOf(
            SkillStep("app_launch", """{"name":"Starbucks"}"""),
            SkillStep("tap", """{"text":"Order"}"""),
            SkillStep("tap", """{"text":"Previous"}"""),
            SkillStep("tap", """{"text":"Close"}""", optional = true),
            SkillStep("tap", """{"text":"{{size}}"}"""),
        ),
        draft = true, source = SkillSource.AGENT,
    )

    private fun conversation(running: Boolean) = AgentState(
        running = running,
        conversationId = 3,
        steps = 6,
        usageText = "↑4.2k · ↓310 · cache 3.1k · Σ ↑11k ↓0.9k",
        messages = listOf(
            ChatMsg(1, "user", "Turn on dark theme and set an alarm for 7:30"),
            ChatMsg(2, "assistant", "I'll switch **Dark theme** on in Display settings, then use the `set-alarm` skill."),
            ChatMsg(
                3, "tool", "", toolCalls = listOf(
                    ToolCallUi("c1", "launch_intent", """{"uri":"android.settings.DISPLAY_SETTINGS"}""",
                        """{"ok":true}""", 820),
                    ToolCallUi("c2", "tap", """{"ref":7}""", """{"ok":true,"via":"node_click"}""", 410),
                    ToolCallUi("c3", "run_skill", """{"name":"set-alarm","params":{"hour":"7","minute":"30"}}""",
                        if (running) null else """{"ok":true}""", 1900),
                    ToolCallUi("c3.1", "launch_intent", """{"uri":"intent:#Intent;action=android.intent.action.SET_ALARM;end"}""",
                        """{"ok":true}""", 640),
                    ToolCallUi("c3.2", "tap", """{"text":"Save"}""",
                        if (running) null else """{"ok":false,"error":"no matching element"}""", 300),
                ),
            ),
            ChatMsg(4, "assistant", "Done:\n- Dark theme is **on**\n- Alarm set for **7:30** (the clock app asked to confirm)"),
        ),
        recap = if (running) null else RunRecap("Turn on dark theme and set an alarm for 7:30", "Done", emptyList()),
    )

    @Test fun chatEmpty() = shot(tab = Tab.CHAT) {
        ChatContent(
            ChatUi(AgentState(), shellOnline = true, a11yOn = true,
                quickSkills = library.filter { it.active }.take(5)),
            ChatActions(),
        )
    }

    @Test fun chatConversation() = shot(tab = Tab.CHAT) {
        ChatContent(ChatUi(conversation(false), shellOnline = true, a11yOn = true,
            title = "Dark theme + 7:30 alarm"), ChatActions())
    }

    @Test fun chatConversationDark() = shot(dark = true, tab = Tab.CHAT) {
        ChatContent(ChatUi(conversation(false), shellOnline = true, a11yOn = true,
            title = "Dark theme + 7:30 alarm"), ChatActions())
    }

    @Test fun chatConfirm() = shot(tab = Tab.CHAT) {
        ChatContent(
            ChatUi(
                conversation(true),
                pendingConfirm = PendingConfirm("settings_put",
                    """{"namespace":"system","key":"screen_brightness","value":"40"}""",
                    "destructive", CompletableDeferred()),
                title = "Dark theme + 7:30 alarm",
            ),
            ChatActions(),
        )
    }

    @Test fun skillsLibrary() = shot(tab = Tab.SKILLS) {
        SkillsLibrary(library, {}, { _, _ -> }, {}, {})
    }

    @Test fun skillsLibraryDark() = shot(dark = true, tab = Tab.SKILLS) {
        SkillsLibrary(library, {}, { _, _ -> }, {}, {})
    }

    @Test fun skillDetail() = shot {
        SkillDetail(library.first { it.name == "order-coffee" }, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun skillDetailBuiltin() = shot(dark = true) {
        SkillDetail(library.first { it.name == "find-setting" }, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun skillEditor() = shot {
        SkillEditor(library.first { it.name == "order-coffee" }, isNew = false, error = null, onCancel = {}, onSave = {})
    }

    @Test fun runPanel() = shot(tab = Tab.SKILLS) {
        Box {
            SkillsLibrary(library, {}, { _, _ -> }, {}, {})
            BottomPanel(visible = true, onDismiss = {}) {
                RunSkillPanel(library.first { it.name == "set-alarm" }, hasAi = true, onRun = { _, _ -> })
            }
        }
    }

    @Test fun tools() = shot(tab = Tab.TOOLS) {
        val t = { name: String, summary: String, risk: String, backend: String ->
            ToolSpec(name, summary, summary, risk, backend, org.json.JSONObject())
        }
        Column {
            LargeTitle("Tools")
            ToolsList(
                listOf(
                    t("screen", "Read the screen", "readonly", "a11y"),
                    t("tap", "Tap", "moderate", "a11y"),
                    t("type_text", "Type into field", "moderate", "a11y"),
                    t("scroll", "Scroll", "moderate", "a11y"),
                    t("shell_exec", "Run a shell command", "destructive", "shell"),
                    t("app_launch", "Open an app", "moderate", "shell"),
                    t("file_read", "Read a file", "readonly", "shell"),
                    t("run_skill", "Run a skill", "moderate", "meta"),
                    t("ask_user", "Ask the user", "readonly", "meta"),
                ),
                onSelect = {},
            )
        }
    }
}
