package com.boxagent.app.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLiteTest {
    @Test
    fun parsesBlocksAndContinuations() {
        val md = """
            # Plan
            1. Open Settings and tap the search field (labels
               vary by device).
            2. Type the term
            - bullet

            Some **bold** text
            that wraps.
            ```
            adb shell id
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading("Plan"),
                MdBlock.Item("1.", "Open Settings and tap the search field (labels vary by device)."),
                MdBlock.Item("2.", "Type the term"),
                MdBlock.Item("•", "bullet"),
                MdBlock.Para("Some **bold** text\nthat wraps."),
                MdBlock.Code("adb shell id"),
            ),
            MarkdownLite.parse(md),
        )
    }

    @Test
    fun inlineSpansAndUnclosedMarkers() {
        val s = MarkdownLite.inline("Use `set-alarm` for **7:30**, *now* — 2 * 3", Color.Gray)
        assertEquals("Use set-alarm for 7:30, now — 2 * 3", s.text)
        assertEquals(3, s.spanStyles.size)
        assertEquals("a **b", MarkdownLite.inline("a **b", Color.Gray).text)
    }
}
