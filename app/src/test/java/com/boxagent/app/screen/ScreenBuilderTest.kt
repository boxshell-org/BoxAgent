package com.boxagent.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBuilderTest {

    private fun b(l: Int, t: Int, r: Int, bt: Int) = Bounds(l, t, r, bt)

    /** A Settings-like screen: toolbar, search field, a list of rows. */
    private fun settings(rows: Int = 6, scrolledBy: Int = 0, extraRow: Boolean = false): UiNode {
        val items = (0 until rows).map { i ->
            val top = 400 + i * 200 - scrolledBy
            val title = "Option $i"
            val kids = mutableListOf(
                UiNode(cls = "android.widget.ImageView", bounds = b(40, top + 60, 120, top + 140)),
                UiNode(
                    cls = "android.widget.LinearLayout", bounds = b(160, top, 900, top + 200),
                    children = listOf(
                        UiNode(cls = "android.widget.TextView", text = title,
                            viewId = "android:id/title", bounds = b(160, top + 40, 900, top + 100)),
                        UiNode(cls = "android.widget.TextView", text = "Summary of $i",
                            viewId = "android:id/summary", bounds = b(160, top + 110, 900, top + 160)),
                    ),
                ),
            )
            if (i % 2 == 0) {
                kids += UiNode(
                    cls = "android.widget.Switch", viewId = "android:id/switch_widget",
                    checkable = true, clickable = true, checked = i == 0,
                    bounds = b(940, top + 60, 1040, top + 140),
                )
            }
            UiNode(
                cls = "android.widget.LinearLayout", clickable = true,
                bounds = b(0, top, 1080, top + 200), children = kids,
            )
        } + if (extraRow) listOf(
            UiNode(
                cls = "android.widget.LinearLayout", clickable = true,
                bounds = b(0, 2000, 1080, 2200),
                children = listOf(UiNode(cls = "android.widget.TextView", text = "New row",
                    bounds = b(160, 2040, 900, 2100))),
            ),
        ) else emptyList()
        return UiNode(
            cls = "android.widget.FrameLayout", bounds = b(0, 0, 1080, 2400),
            children = listOf(
                UiNode(
                    cls = "android.widget.LinearLayout", bounds = b(0, 0, 1080, 160),
                    children = listOf(
                        UiNode(cls = "android.widget.ImageButton", desc = "Navigate up",
                            clickable = true, bounds = b(0, 20, 120, 140)),
                        UiNode(cls = "android.widget.TextView", text = "Network & internet",
                            bounds = b(140, 40, 900, 120)),
                    ),
                ),
                UiNode(
                    cls = "android.widget.EditText", editable = true, focused = true,
                    hint = "Search settings", viewId = "com.android.settings:id/search",
                    clickable = true, bounds = b(40, 180, 1040, 300),
                ),
                UiNode(
                    cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true,
                    canScrollForward = true, viewId = "com.android.settings:id/recycler_view",
                    bounds = b(0, 380, 1080, 2400), children = items,
                ),
                UiNode(cls = "android.widget.TextView", text = "hidden", visible = false,
                    bounds = b(0, 0, 10, 10)),
            ),
        )
    }

    @Test
    fun rendersRowsAsOneLabelledElementEach() {
        val s = ScreenBuilder.build(listOf(settings()), "app: settings", RefTable())
        val text = s.render()
        val lines = text.lines()
        assertEquals("app: settings", lines[0])
        assertEquals("[1] button: Navigate up @60,80", lines[1])
        assertEquals("- Network & internet", lines[2])
        assertEquals(
            "[2] input: #search (hint: Search settings, focused) @540,240", lines[3],
        )
        assertEquals("[3] list: #recycler_view (scroll ↓) @540,1390", lines[4])
        // Row title + summary merge into one element; its switch is separate.
        assertEquals("[4] item: Option 0 · Summary of 0 @540,500", lines[5])
        assertEquals("[5] switch: #switch_widget (on) @990,500", lines[6])
        assertEquals("[6] item: Option 1 · Summary of 1 @540,700", lines[7])
        assertFalse("invisible nodes are skipped", text.contains("hidden"))
        assertEquals(0, s.hiddenLines)
        assertTrue(s.shownRefs.containsAll(listOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun refsAreStableAndNeverReusedForADifferentElement() {
        val refs = RefTable()
        val first = ScreenBuilder.build(listOf(settings()), "h", refs)
        val again = ScreenBuilder.build(listOf(settings(extraRow = true)), "h", refs)
        val byLabel1 = first.elements.associate { it.label to it.ref }
        val byLabel2 = again.elements.associate { it.label to it.ref }
        // Unchanged elements keep their numbers.
        for ((label, ref) in byLabel1) assertEquals(label, ref, byLabel2[label])
        // The new row gets a fresh number above every earlier one.
        val newRef = byLabel2.getValue("New row")
        assertTrue(newRef > byLabel1.values.max())

        // After scrolling, moved rows are new elements: old refs don't
        // silently point at whatever is now in that position.
        val scrolled = ScreenBuilder.build(listOf(settings(scrolledBy = 200)), "h", refs)
        val option1Before = byLabel1.getValue("Option 1 · Summary of 1")
        val option1After = scrolled.elements.first { it.label.startsWith("Option 1") }.ref
        assertNotEquals(option1Before, option1After)
    }

    @Test
    fun typingKeepsTheInputRef() {
        val refs = RefTable()
        fun field(text: String?) = UiNode(
            cls = "android.widget.EditText", editable = true, text = text,
            hint = "Message", bounds = b(0, 2000, 900, 2100),
        )
        val before = ScreenBuilder.build(listOf(field(null)), "h", refs).elements.single()
        val after = ScreenBuilder.build(listOf(field("hello")), "h", refs).elements.single()
        assertEquals(before.ref, after.ref)
        assertEquals("input (hint: Message)", before.line().substringAfter("] ").substringBefore(" @"))
        assertEquals("input: hello", after.line().substringAfter("] ").substringBefore(" @"))
    }

    @Test
    fun unlabeledContainersAreDroppedButIconButtonsKept() {
        val tree = UiNode(
            cls = "android.widget.FrameLayout", clickable = true, bounds = b(0, 0, 1080, 2400),
            children = listOf(
                UiNode(cls = "android.widget.ImageView", clickable = true,
                    viewId = "app:id/more", bounds = b(980, 40, 1060, 120)),
                UiNode(cls = "android.widget.ImageView", clickable = true,
                    bounds = b(0, 40, 80, 120)),
            ),
        )
        val lines = ScreenBuilder.build(listOf(tree), "h", RefTable()).render().lines()
        assertEquals(listOf("h", "[2] image: #more @1020,80", "[3] image @40,80"), lines)
    }

    @Test
    fun longScreensAreCappedWithACountOfHiddenLines() {
        val s = ScreenBuilder.build(listOf(settings(rows = 300)), "h", RefTable())
        assertTrue(s.render().length <= ScreenBuilder.MAX_CHARS + 80)
        assertTrue(s.hiddenLines > 0)
        assertTrue(s.render().endsWith("more lines not shown (scroll, or tap by text)"))
        // Hidden elements are not advertised as usable refs.
        assertTrue(s.shownRefs.size < s.elements.size)
    }

    /** The JSON tree `ui_tree` used to send (already pruned of empty
     *  layout nodes) — the baseline the compact format replaces. */
    private fun legacyJson(n: UiNode, depth: Int = 0): List<String> {
        if (depth > 0 && !n.visible) return emptyList()
        val caps = buildList {
            if (n.enabled) {
                if (n.clickable) add("\"click\"")
                if (n.scrollable) add("\"scroll\"")
                if (n.checkable) add("\"check\"")
                if (n.longClickable) add("\"longclick\"")
            }
            if (n.editable) add("\"edit\"")
        }
        val id = n.viewId?.substringAfterLast('/')
        val informative = depth == 0 || n.text != null || n.desc != null || id != null ||
            caps.isNotEmpty()
        val kids = n.children.flatMap { legacyJson(it, depth + 1) }
        if (!informative) return kids
        val fields = buildList {
            add("\"class\":\"${n.cls.substringAfterLast('.')}\"")
            id?.let { add("\"id\":\"$it\"") }
            n.text?.let { add("\"text\":\"$it\"") }
            n.desc?.let { add("\"desc\":\"$it\"") }
            add("\"bounds\":\"${n.bounds}\"")
            if (caps.isNotEmpty()) add("\"caps\":[${caps.joinToString(",")}]")
            if (n.checkable) add("\"checked\":${n.checked}")
            if (kids.isNotEmpty()) add("\"children\":[${kids.joinToString(",")}]")
        }
        return listOf("{${fields.joinToString(",")}}")
    }

    @Test
    fun compactScreenIsAFractionOfTheJsonTree() {
        val tree = settings(rows = 10)
        val compact = ScreenBuilder.build(listOf(tree), "app: com.android.settings", RefTable())
            .render()
        val json = "{\"windows\":[${legacyJson(tree).joinToString(",")}]}"
        // As a tool result the JSON was itself string-escaped once more.
        val jsonInResult = json.replace("\"", "\\\"")
        val ratio = compact.length.toDouble() / jsonInResult.length
        println("screen: compact=${compact.length}B json=${jsonInResult.length}B ratio=%.2f".format(ratio))
        assertTrue("ratio $ratio", ratio < 0.4)
    }
}
