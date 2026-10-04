package com.boxagent.app.screen

/**
 * Platform-free screen model: an accessibility snapshot in, a compact,
 * numbered element list out. Kept free of Android types so it is unit
 * tested on the JVM; A11yService converts AccessibilityNodeInfo into
 * [UiNode] and keeps [UiNode.handle] to act on refs.
 *
 * Output (one line per element, reading order):
 *
 *     app: com.android.settings/SubSettings · 1080x2400
 *     [3] item: Network & internet · Mobile, Wi-Fi, hotspot @540,412
 *     [4] switch: Wi-Fi (on) @980,600
 *     [5] input (hint: Search settings, focused) @540,180
 *     - Recently used
 *
 * Interactive nodes get a ref and absorb the text of their non-interactive
 * descendants (a row's title + subtitle become one label), so a typical
 * screen costs a few hundred tokens instead of a nested JSON tree.
 */
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val width get() = r - l
    val height get() = b - t
    val isEmpty get() = width <= 0 || height <= 0
    override fun toString() = "$l,$t,$r,$b"
}

data class UiNode(
    val cls: String = "",
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val password: Boolean = false,
    val canScrollForward: Boolean = false,
    val canScrollBackward: Boolean = false,
    val children: List<UiNode> = emptyList(),
    /** Opaque handle to the platform node, for acting on a ref. */
    val handle: Any? = null,
)

/** One actionable element of a snapshot. */
data class Element(
    val ref: Int,
    val role: String,
    val label: String,
    val states: List<String>,
    val bounds: Bounds,
    val node: UiNode,
) {
    fun line(): String {
        val sb = StringBuilder("[").append(ref).append("] ").append(role)
        if (label.isNotEmpty()) sb.append(": ").append(label)
        if (states.isNotEmpty()) sb.append(" (").append(states.joinToString(", ")).append(')')
        sb.append(" @").append(bounds.cx).append(',').append(bounds.cy)
        return sb.toString()
    }
}

class Screen(
    val header: String,
    /** Rendered body lines (after caps). */
    val lines: List<String>,
    /** Every element of the snapshot, rendered or not. */
    val elements: List<Element>,
    /** Refs that made it into [lines] — what the model can see. */
    val shownRefs: Set<Int>,
    val hiddenLines: Int,
) {
    fun render(): String = buildString {
        append(header)
        lines.forEach { append('\n').append(it) }
        if (hiddenLines > 0) {
            append("\n… ").append(hiddenLines)
                .append(" more lines not shown (scroll, or tap by text)")
        }
    }
}

/**
 * Element identity → ref number. The same element keeps its number across
 * snapshots (so refs from a slightly older screen still hit the right
 * element, and a parallel `tap ref=3` + `tap ref=5` can't shift each
 * other); a changed or new element gets a fresh number — a ref never
 * silently moves to a different element.
 */
class RefTable {
    private val byKey = HashMap<String, Int>()
    private var next = 1

    fun refFor(key: String): Int = byKey.getOrPut(key) { next++ }

    /** New app in front: forget identities but keep counting, so stale
     *  refs from the previous app can't collide with new elements. */
    fun forgetIdentities() = byKey.clear()

    /** New agent run. */
    fun reset() {
        byKey.clear()
        next = 1
    }
}

object ScreenBuilder {
    const val MAX_LINES = 160
    const val MAX_CHARS = 5500
    private const val MAX_LABEL = 90
    private const val MAX_TEXT = 120
    private const val MAX_DEPTH = 60

    private class Pending(val node: UiNode, val ref: Int) {
        val parts = LinkedHashSet<String>()
        var hasInteractiveChild = false
    }

    private sealed interface Line
    private class ElemLine(val p: Pending) : Line
    private class TextLine(val text: String) : Line

    fun build(roots: List<UiNode>, header: String, refs: RefTable): Screen {
        val out = mutableListOf<Line>()

        fun visit(n: UiNode, agg: Pending?, depth: Int) {
            if (depth > MAX_DEPTH || (depth > 0 && !n.visible)) return
            val own = ownLabel(n)
            val interactive = (n.clickable || n.longClickable || n.editable ||
                n.checkable || n.scrollable) && !n.bounds.isEmpty
            if (interactive) {
                agg?.hasInteractiveChild = true
                val p = Pending(n, refs.refFor(identity(n)))
                if (own != null && !n.editable) p.parts += own
                out += ElemLine(p)
                // Lists don't absorb their items' text; controls do.
                val childAgg = if (n.scrollable && !n.clickable) null else p
                n.children.forEach { visit(it, childAgg, depth + 1) }
            } else {
                if (own != null) {
                    if (agg != null) agg.parts += own else out += TextLine(own)
                }
                n.children.forEach { visit(it, agg, depth + 1) }
            }
        }
        roots.forEach { visit(it, null, 0) }

        val elements = mutableListOf<Element>()
        val rendered = mutableListOf<Pair<String, Int?>>()
        var lastText: String? = null
        for (line in out) {
            when (line) {
                is TextLine -> {
                    if (line.text != lastText) rendered += "- ${line.text}" to null
                    lastText = line.text
                }
                is ElemLine -> {
                    lastText = null
                    val e = element(line.p) ?: continue
                    elements += e
                    rendered += e.line() to e.ref
                }
            }
        }

        val lines = mutableListOf<String>()
        val shown = HashSet<Int>()
        var chars = header.length
        var hidden = 0
        for ((text, ref) in rendered) {
            if (lines.size >= MAX_LINES || chars + text.length + 1 > MAX_CHARS) {
                hidden++
                continue
            }
            lines += text
            chars += text.length + 1
            ref?.let(shown::add)
        }
        return Screen(header, lines, elements, shown, hidden)
    }

    private fun element(p: Pending): Element? {
        val n = p.node
        var label = p.parts.joinToString(" · ").clip(MAX_LABEL)
        if (n.editable) label = n.text?.oneLine()?.clip(MAX_LABEL).orEmpty()
        val id = n.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        // An unlabeled plain container around other controls is noise.
        if (label.isEmpty() && id == null && p.hasInteractiveChild &&
            !n.editable && !n.checkable && !n.scrollable
        ) return null
        val role = role(n)
        if (label.isEmpty() && id != null) label = "#$id"
        return Element(p.ref, role, label, states(n, role), n.bounds, n)
    }

    private fun states(n: UiNode, role: String): List<String> {
        val s = mutableListOf<String>()
        if (n.checkable) {
            s += if (role == "switch") (if (n.checked) "on" else "off")
                else (if (n.checked) "checked" else "unchecked")
        }
        if (n.editable) {
            if (n.text.isNullOrBlank()) n.hint?.oneLine()?.takeIf { it.isNotEmpty() }
                ?.let { s += "hint: ${it.clip(40)}" }
            if (n.password) s += "password"
            if (n.focused) s += "focused"
        }
        if (n.selected) s += "selected"
        if (!n.enabled) s += "disabled"
        if (n.longClickable) s += "long-press"
        if (n.scrollable) {
            val horizontal = n.bounds.width > n.bounds.height
            val dirs = buildString {
                if (n.canScrollBackward) append(if (horizontal) "←" else "↑")
                if (n.canScrollForward) append(if (horizontal) "→" else "↓")
            }
            s += if (dirs.isEmpty()) "scroll" else "scroll $dirs"
        }
        return s
    }

    fun role(n: UiNode): String {
        val c = n.cls.substringAfterLast('.').lowercase()
        return when {
            n.editable || "edittext" in c || "autocomplete" in c -> "input"
            "switch" in c || "toggle" in c -> "switch"
            "radio" in c -> "radio"
            "checkbox" in c || "checkedtextview" in c -> "check"
            "seekbar" in c || "ratingbar" in c || "slider" in c -> "slider"
            "button" in c -> "button"
            c.startsWith("tab") && !c.startsWith("table") -> "tab"
            "webview" in c -> "web"
            n.scrollable || "recyclerview" in c || "listview" in c || "gridview" in c ||
                "scrollview" in c || "viewpager" in c -> "list"
            "image" in c -> "image"
            n.checkable -> "check"
            "textview" in c -> "text"
            else -> "item"
        }
    }

    private fun ownLabel(n: UiNode): String? =
        (n.text?.oneLine()?.takeIf { it.isNotEmpty() }
            ?: n.desc?.oneLine()?.takeIf { it.isNotEmpty() })
            ?.clip(MAX_TEXT)

    /** Identity for ref stability: what the element is and where. An
     *  input's typed text is not part of it — typing keeps the same ref. */
    private fun identity(n: UiNode): String {
        val what = if (n.editable) n.hint ?: n.desc else n.text ?: n.desc ?: n.hint
        return "${n.cls}|${n.viewId}|$what|${n.bounds}"
    }

    private fun String.oneLine() = replace(WS, " ").trim()

    private fun String.clip(max: Int) = if (length <= max) this else take(max - 1) + "…"

    private val WS = Regex("\\s+")
}
