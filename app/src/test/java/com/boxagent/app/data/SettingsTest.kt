package com.boxagent.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun legacyDefaultPromptsReadAsNoCustomInstructions() {
        val v2 = "You are BoxAgent, an operator running on the user's Android phone. " +
            "You act only through the provided tools. Read before you act: use " +
            "ui_tree to see the screen, screenshot when coordinates are unclear. " +
            "Prefer small verifiable steps. For destructive operations, explain " +
            "what you are about to do first. Reuse recent tool results instead " +
            "of re-calling a tool when nothing changed; prefer ui_find over " +
            "ui_tree and ui_tree over screenshot when possible. Always respond " +
            "in the same language the user writes in. When finished, call " +
            "task_done with a concise summary."
        assertEquals("", Settings.customInstructions(v2))
        // Whitespace differences (e.g. re-saved from a text field) still match.
        assertEquals("", Settings.customInstructions("  " + v2.replace(". ", ".\n") + "\n"))
        assertEquals("", Settings.customInstructions(null))
        assertEquals("Prefer WeChat.", Settings.customInstructions("  Prefer WeChat. "))
        // A user-edited prompt is kept as-is.
        val edited = v2 + " Never open TikTok."
        assertEquals(edited, Settings.customInstructions(edited))
    }
}
