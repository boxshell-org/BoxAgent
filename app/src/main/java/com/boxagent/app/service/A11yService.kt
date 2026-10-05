package com.boxagent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.boxagent.app.screen.Bounds
import com.boxagent.app.screen.Element
import com.boxagent.app.screen.RefTable
import com.boxagent.app.screen.Screen
import com.boxagent.app.screen.ScreenBuilder
import com.boxagent.app.screen.UiNode
import com.boxagent.app.vscreen.VScreenBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Accessibility capability: screen reading + gesture dispatch.
 * All ops are suspend functions called from the tool runner.
 */
class A11yService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile var lastPackage: String = ""
        private set

    /** Activity class from the last window-state change (header context). */
    @Volatile private var lastActivity: String = ""

    /** Uptime of the last UI-changing event — drives [settle]. */
    @Volatile private var lastEventAt: Long = 0

    private val refs = RefTable()
    /** Elements of the latest snapshot, by ref. Guarded by [refs]. */
    private var current: Map<Int, Element> = emptyMap()
    private var refsPackage = ""

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Only the display the agent works on counts: with a virtual
        // screen, the user scrolling their real screen must not keep
        // settle() waiting or rename the "current app" (and vice versa).
        val d = displayOf(event)
        if (d != Display.INVALID_DISPLAY && d != scopeDisplay()) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastPackage = event.packageName?.toString() ?: lastPackage
                // className is the activity for activity windows; widgets
                // (dialogs, popups) report framework classes — skip those.
                event.className?.toString()
                    ?.takeIf { !it.startsWith("android.") && !it.startsWith("androidx.") }
                    ?.let { lastActivity = it }
                lastEventAt = SystemClock.uptimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> lastEventAt = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {}

    /** The display tools act on: the virtual screen while one is attached. */
    private fun scopeDisplay(): Int =
        VScreenBridge.displayId.takeIf { it >= 0 } ?: Display.DEFAULT_DISPLAY

    /** windowId → displayId, for events before API 33 (no getDisplayId). */
    private val windowDisplay = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile private var windowDisplayAt = 0L

    private fun displayOf(e: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= 33) return e.displayId
        // Without a virtual screen everything is the default display —
        // skip the lookups entirely.
        if (VScreenBridge.displayId < 0) return Display.INVALID_DISPLAY
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowDisplay.clear()
        val wid = e.windowId
        if (wid < 0) return Display.INVALID_DISPLAY
        windowDisplay[wid]?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (now - windowDisplayAt > 300) {
            windowDisplayAt = now
            runCatching {
                val all = windowsOnAllDisplays
                for (i in 0 until all.size()) {
                    val disp = all.keyAt(i)
                    all.valueAt(i).forEach { windowDisplay[it.id] = disp }
                }
            }
        }
        return windowDisplay[wid] ?: Display.INVALID_DISPLAY
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Reading

    // ------------------------------------------------------------------
    // Virtual-display scoping (VScreenBridge is live when the agent runs
    // on a virtual screen; empty/negative id means physical as usual).

    /**
     * Windows the agent may see — virtual display only while scoped.
     * `getWindows()` is pinned to DEFAULT_DISPLAY in the framework, so
     * scoped reads must go through `getWindowsOnAllDisplays()` instead.
     */
    private fun windowsForOps(): List<AccessibilityWindowInfo> {
        val id = VScreenBridge.displayId
        if (id < 0) return runCatching { windows }.getOrDefault(emptyList())
        return runCatching { windowsOnAllDisplays?.get(id) }
            .getOrNull() ?: emptyList()
    }

    fun foregroundPackage(): String {
        if (VScreenBridge.displayId >= 0) {
            // The VD's top app, not whatever holds focus on the panel.
            val root = activeRoots().firstOrNull()
            return runCatching { root?.packageName?.toString() }.getOrNull()
                ?.ifEmpty { lastPackage } ?: lastPackage
        }
        return runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?.ifEmpty { lastPackage } ?: lastPackage
    }

    /**
     * Node accessors can throw (`SecurityException` while windows change,
     * `IllegalStateException` on a node that went stale mid-walk). One bad
     * node should cost its subtree at most — never the whole read.
     */
    private fun childAt(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        runCatching { n.getChild(i) }.getOrNull()

    private fun childCountOf(n: AccessibilityNodeInfo): Int =
        runCatching { n.childCount }.getOrDefault(0)

    private fun parentOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        runCatching { n.parent }.getOrNull()

    /** Roots to search: the active window(s), else the active-window root.
     *  Virtual-display windows often never carry isActive (focus stays
     *  with the panel's display group) — scoped mode falls back to the
     *  VD's application windows. */
    private fun activeRoots(): List<AccessibilityNodeInfo> {
        val wins = windowsForOps()
        val act = wins.filter { it.isActive }
            .mapNotNull { runCatching { it.root }.getOrNull() }
        if (act.isNotEmpty()) return act
        if (VScreenBridge.displayId >= 0) {
            return wins
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { runCatching { it.root }.getOrNull() }
        }
        return listOfNotNull(runCatching { rootInActiveWindow }.getOrNull())
    }

    /** Any app window on the scoped display? An empty virtual display
     *  mirrors the physical one, so its pixels must not be shown then. */
    fun hasScopeContent(): Boolean =
        windowsForOps().any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

    /**
     * Append [text] to the focused text field of the scoped display via
     * set-text — the virtual screen's fallback for characters key events
     * can't type (CJK, emoji). Searches the scoped windows only:
     * [findFocus] would happily return a field on the physical screen.
     */
    fun appendToFocused(text: String): Boolean {
        var target: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (target != null || depth > 60) return
            if (runCatching { n.isEditable && n.isFocused }.getOrDefault(false)) {
                target = n
                return
            }
            for (i in 0 until min(childCountOf(n), 80)) childAt(n, i)?.let { walk(it, depth + 1) }
        }
        activeRoots().forEach { walk(it, 0) }
        val node = target ?: return false
        val current = runCatching {
            // Placeholder text shows up as `text` on empty fields.
            if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        }.getOrDefault("")
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text,
            )
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Compact screen (the model's view) and refs

    /** Wait until the UI stops changing: [quietMs] without accessibility
     *  events, at least [minMs], at most [maxMs] (animations, clocks and
     *  video never go quiet). */
    suspend fun settle(minMs: Long = 150, quietMs: Long = 300, maxMs: Long = 2000) {
        val start = SystemClock.uptimeMillis()
        delay(minMs)
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - lastEventAt >= quietMs || now - start >= maxMs) return
            delay(50)
        }
    }

    /** Snapshot the active window into a numbered [Screen]; its refs
     *  become the ones `tap ref=…` etc. resolve against. */
    fun snapshot(): Screen {
        val roots = activeRoots()
        val pkg = roots.firstOrNull()?.packageName?.toString()?.ifEmpty { null } ?: lastPackage
        val budget = intArrayOf(MAX_SNAPSHOT_NODES)
        val tree = roots.mapNotNull { runCatching { toUiNode(it, 0, budget) }.getOrNull() }
        synchronized(refs) {
            if (pkg != refsPackage) {
                refs.forgetIdentities()
                refsPackage = pkg
            }
            val screen = ScreenBuilder.build(tree, header(pkg), refs)
            current = screen.elements.associateBy { it.ref }
            return screen
        }
    }

    fun screenText(): String = snapshot().render()

    /** New agent run: numbering restarts at 1. */
    fun resetRefs() = synchronized(refs) {
        refs.reset()
        current = emptyMap()
    }

    /** Live node for [ref], or null when it is gone from the screen. */
    private fun nodeFor(ref: Int): AccessibilityNodeInfo? {
        val e = synchronized(refs) { current[ref] } ?: return null
        val node = e.node.handle as? AccessibilityNodeInfo ?: return null
        return node.takeIf { runCatching { it.refresh() && it.isVisibleToUser }.getOrDefault(false) }
    }

    /** What [ref] points at, in replayable terms (see SkillCodec.portable). */
    fun describe(ref: Int): com.boxagent.app.skills.RefTarget? {
        val e = synchronized(refs) { current[ref] } ?: return null
        val n = e.node
        val own = (n.text ?: n.desc)?.trim()?.takeIf { it.isNotEmpty() && !n.editable }
        // Rows usually have no text of their own: their title is the first
        // part of the merged label.
        val title = e.label.substringBefore(" · ").takeIf { it.isNotEmpty() && !it.startsWith("#") }
        return com.boxagent.app.skills.RefTarget(
            text = own ?: title.takeIf { !n.editable },
            hint = n.hint?.trim()?.takeIf { it.isNotEmpty() },
            cx = e.bounds.cx,
            cy = e.bounds.cy,
            editable = n.editable,
        )
    }

    private fun staleRef(ref: Int) =
        "element [$ref] is not on the current screen — use refs from the latest screen"

    private fun header(pkg: String): String {
        val (w, h) = screenSize()
        val act = lastActivity.takeIf { lastPackage == pkg && it.isNotEmpty() }
            ?.substringAfterLast('.')
        val keyboard = runCatching {
            windowsForOps().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)
        return buildString {
            append("app: ").append(pkg.ifEmpty { "?" })
            act?.let { append('/').append(it) }
            append(" · ").append(w).append('x').append(h)
            if (keyboard) append(" · keyboard shown")
        }
    }

    private fun toUiNode(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode {
        budget[0]--
        val r = Rect().also { n.getBoundsInScreen(it) }
        val kids = if (depth >= 50 || budget[0] <= 0) emptyList() else buildList {
            for (i in 0 until min(childCountOf(n), 80)) {
                if (budget[0] <= 0) break
                childAt(n, i)?.let { c ->
                    runCatching { toUiNode(c, depth + 1, budget) }.getOrNull()?.let(::add)
                }
            }
        }
        val actions = n.actionList
        return UiNode(
            cls = n.className?.toString().orEmpty(),
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            viewId = n.viewIdResourceName,
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            selected = n.isSelected,
            focused = n.isFocused,
            enabled = n.isEnabled,
            visible = n.isVisibleToUser,
            password = n.isPassword,
            canScrollForward = actions.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD },
            canScrollBackward = actions.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD },
            children = kids,
            handle = n,
        )
    }

    fun dumpTree(maxDepth: Int = 30, packageFilter: String = ""): JSONObject {
        // A package filter may name a non-active window (IME, overlay,
        // split screen) — search all of them for it.
        val byPkg = if (packageFilter.isEmpty()) emptyList()
            else runCatching { windowsForOps().mapNotNull { it.root } }
                .getOrDefault(emptyList()).filter { it.packageName == packageFilter }
        val roots = byPkg.ifEmpty { activeRoots() }
        val budget = intArrayOf(MAX_TREE_NODES)
        val arr = JSONArray()
        roots.forEach { r ->
            runCatching { nodeToJson(r, 0, maxDepth, budget) }
                .getOrDefault(emptyList()).forEach(arr::put)
        }
        return JSONObject()
            .put("foreground", foregroundPackage())
            .put("windows", arr)
            .apply { if (budget[0] <= 0) put("truncated", true) }
    }

    /**
     * One node as JSON — or, for a bare layout container with no text, id
     * or capability, its children spliced into the parent. Invisible nodes
     * are skipped. Keeps a typical screen well inside the 8 KB tool-result
     * cap instead of being cut off mid-JSON.
     */
    private fun nodeToJson(
        n: AccessibilityNodeInfo, depth: Int, max: Int, budget: IntArray,
    ): List<JSONObject> {
        if (budget[0] <= 0 || (depth > 0 && !n.isVisibleToUser)) return emptyList()
        val text = n.text?.toString()?.takeIf { it.isNotBlank() }?.take(80)
        val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.take(80)
        val id = n.viewIdResourceName?.substringAfterLast('/')
        val caps = JSONArray()
        if (n.isEnabled) {
            if (n.isClickable) caps.put("click")
            if (n.isScrollable) caps.put("scroll")
            if (n.isCheckable) caps.put("check")
            if (n.isLongClickable) caps.put("longclick")
        }
        if (n.isEditable) caps.put("edit")
        val informative = depth == 0 || text != null || desc != null || id != null ||
            caps.length() > 0
        if (informative) budget[0]--

        val kids = mutableListOf<JSONObject>()
        if (depth < max) {
            for (i in 0 until min(childCountOf(n), 50)) {
                childAt(n, i)?.let {
                    kids += runCatching { nodeToJson(it, depth + 1, max, budget) }
                        .getOrDefault(emptyList())
                }
            }
        }
        if (!informative) return kids

        val o = JSONObject()
        o.put("class", n.className?.toString()?.substringAfterLast('.') ?: "")
        id?.let { o.put("id", it) }
        text?.let { o.put("text", it) }
        desc?.let { o.put("desc", it) }
        val r = Rect(); n.getBoundsInScreen(r)
        o.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
        if (caps.length() > 0) o.put("caps", caps)
        if (n.isCheckable) o.put("checked", n.isChecked)
        if (kids.isNotEmpty()) o.put("children", JSONArray(kids))
        return listOf(o)
    }

    /** Selector semantics shared by ui_find / tap / wait_for: every given
     *  field must match (AND); none given matches nothing. */
    private class Selector(text: String?, desc: String?, resId: String?) {
        private val textRe = pattern(text)
        private val descRe = pattern(desc)
        private val idRe = pattern(resId, ignoreCase = false)
        val isEmpty get() = textRe == null && descRe == null && idRe == null

        fun matches(n: AccessibilityNodeInfo): Boolean {
            if (isEmpty) return false
            return runCatching {
                textRe?.let { if (n.text?.toString()?.contains(it) != true) return@runCatching false }
                descRe?.let { if (n.contentDescription?.toString()?.contains(it) != true) return@runCatching false }
                idRe?.let { if (n.viewIdResourceName?.contains(it) != true) return@runCatching false }
                true
            }.getOrDefault(false)
        }
    }

    fun findNodes(
        text: String?,
        desc: String?,
        resId: String?,
        clickableOnly: Boolean,
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val sel = Selector(text, desc, resId)
        fun walk(n: AccessibilityNodeInfo) {
            if (out.size >= 50) return
            runCatching {
                if (sel.matches(n) && (!clickableOnly || n.isClickable)) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    out.add(JSONObject()
                        .put("text", n.text?.toString()?.take(80) ?: "")
                        .put("desc", n.contentDescription?.toString()?.take(80) ?: "")
                        .put("id", n.viewIdResourceName ?: "")
                        .put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                        .put("clickable", n.isClickable)
                        .put("visible", n.isVisibleToUser))
                }
            }
            for (i in 0 until min(childCountOf(n), 80)) childAt(n, i)?.let { walk(it) }
        }
        activeRoots().forEach { walk(it) }
        return out
    }

    /** First match, preferring nodes actually on screen. */
    private fun findFirstNode(
        text: String?,
        desc: String?,
        resId: String?,
    ): AccessibilityNodeInfo? {
        val sel = Selector(text, desc, resId)
        if (sel.isEmpty) return null
        var visible: AccessibilityNodeInfo? = null
        var any: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (visible != null) return
            if (sel.matches(n)) {
                if (runCatching { n.isVisibleToUser }.getOrDefault(false)) { visible = n; return }
                if (any == null) any = n
            }
            for (i in 0 until min(childCountOf(n), 80)) childAt(n, i)?.let { walk(it) }
        }
        activeRoots().forEach { walk(it) }
        return visible ?: any
    }

    private fun findFirstEditable(
        text: String?,
        desc: String?,
        resId: String?,
    ): AccessibilityNodeInfo? {
        val textRe = pattern(text)
        val descRe = pattern(desc)
        val idRe = pattern(resId, ignoreCase = false)
        var found: AccessibilityNodeInfo? = null
        var focused: AccessibilityNodeInfo? = null
        var fallback: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            if (runCatching { n.isEditable }.getOrDefault(false)) {
                if (runCatching { n.isFocused }.getOrDefault(false) && focused == null) focused = n
                if (fallback == null) fallback = n
                val sel = runCatching {
                    (textRe != null && n.text?.toString()?.contains(textRe) == true) ||
                        (descRe != null && n.contentDescription?.toString()?.contains(descRe) == true) ||
                        (idRe != null && n.viewIdResourceName?.contains(idRe) == true) ||
                        // Empty fields often expose their placeholder as hint.
                        (textRe != null && n.hintText?.toString()?.contains(textRe) == true)
                }.getOrDefault(false)
                if (sel) { found = n; return }
            }
            for (i in 0 until min(childCountOf(n), 80)) childAt(n, i)?.let { walk(it) }
        }
        activeRoots().forEach { walk(it) }
        return found ?: if (textRe == null && descRe == null && idRe == null) focused ?: fallback else null
    }

    // ------------------------------------------------------------------
    // Gestures

    private suspend fun dispatch(gd: GestureDescription): Boolean {
        // Bound the wait: on some ROMs the callback simply never fires and
        // the tool call (and the whole run) would hang forever. Longest
        // legal gesture = all strokes back to back.
        val strokesMs = (0 until gd.strokeCount)
            .sumOf { gd.getStroke(it).duration }
            .coerceIn(1, 60_000)
        return withTimeoutOrNull(strokesMs + 4_000) {
            suspendCancellableCoroutine { cont ->
                val ok = dispatchGesture(gd, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(g: GestureDescription) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!ok && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    /** Strokes must have non-negative coordinates and 1..max ms duration,
     *  or dispatchGesture throws. */
    private fun stroke(path: Path, durationMs: Long) =
        GestureDescription.StrokeDescription(
            path, 0, durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration()),
        )

    private fun c(v: Float) = v.coerceAtLeast(0f)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        dispatch(GestureDescription.Builder().addStroke(stroke(path, durationMs)).build())

    private fun screenSize(): Pair<Int, Int> {
        if (VScreenBridge.displayId >= 0 && VScreenBridge.width > 0) {
            return VScreenBridge.width to VScreenBridge.height
        }
        val dm = android.util.DisplayMetrics()
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    // Touch routing: while scoped to a virtual display, coordinates live
    // on that display and dispatchGesture can't reach them — the host
    // injects instead. Otherwise the a11y gesture path runs as before.
    private suspend fun touchTap(x: Float, y: Float): Boolean =
        VScreenBridge.touchBackend?.tap(x, y) ?: run {
            val path = Path().apply { moveTo(c(x), c(y)) }
            gesture(path, 60)
        }

    private suspend fun touchHold(x: Float, y: Float, durationMs: Long): Boolean =
        VScreenBridge.touchBackend?.hold(x, y, durationMs) ?: run {
            val path = Path().apply { moveTo(c(x), c(y)) }
            gesture(path, durationMs)
        }

    private suspend fun touchSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): Boolean =
        VScreenBridge.touchBackend?.swipe(x1, y1, x2, y2, durationMs) ?: run {
            val path = Path().apply { moveTo(c(x1), c(y1)); lineTo(c(x2), c(y2)) }
            gesture(path, durationMs)
        }

    suspend fun tap(x: Float, y: Float): Boolean = touchTap(x, y)

    /** Node named by a model selector: visible text first, then
     *  content-description (icons are usually labelled only by that). */
    private fun findByLabel(text: String?, desc: String?, resId: String?): AccessibilityNodeInfo? {
        if (text != null && desc == null && resId == null) {
            return findFirstNode(text, null, null) ?: findFirstNode(null, text, null)
        }
        return findFirstNode(text, desc, resId)
    }

    /** Click [node] or its nearest clickable ancestor (bounded, so a
     *  whole-screen container is never hit); gesture on it otherwise. */
    private suspend fun clickNode(node: AccessibilityNodeInfo): Pair<Boolean, String> {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null &&
            !runCatching { n.isClickable && n.isEnabled }.getOrDefault(false) &&
            hops < MAX_CLICK_HOPS
        ) {
            n = parentOf(n); hops++
        }
        if (n != null &&
            runCatching {
                n.isClickable && n.isEnabled &&
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        ) {
            return true to "node_click"
        }
        val r = Rect()
        if (!runCatching { node.getBoundsInScreen(r); r.isEmpty }.getOrDefault(true)) {
            return tap(r.exactCenterX(), r.exactCenterY()) to "gesture"
        }
        return false to "element has no on-screen bounds"
    }

    suspend fun tapOrNode(
        ref: Int?,
        x: Float?, y: Float?,
        text: String?, desc: String?, resId: String?,
    ): Pair<Boolean, String> {
        if (ref != null) {
            val node = nodeFor(ref) ?: return false to staleRef(ref)
            return clickNode(node)
        }
        if (text != null || desc != null || resId != null) {
            val node = findByLabel(text, desc, resId) ?: return false to "no matching element"
            return clickNode(node)
        }
        if (x != null && y != null) return tap(x, y) to "gesture"
        return false to "give ref, text, or x and y"
    }

    suspend fun longPress(
        ref: Int?, x: Float?, y: Float?, text: String?, desc: String?, resId: String?,
        durationMs: Long,
    ): Pair<Boolean, String> {
        val node = when {
            ref != null -> nodeFor(ref) ?: return false to staleRef(ref)
            x != null && y != null -> null
            else -> findByLabel(text, desc, resId) ?: return false to "no matching element"
        }
        if (node != null && runCatching {
            node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }.getOrDefault(false)) {
            return true to "node_long_click"
        }
        val (cx, cy) = if (node != null) {
            val r = Rect()
            if (runCatching { node.getBoundsInScreen(r) }.isSuccess && !r.isEmpty) {
                r.exactCenterX() to r.exactCenterY()
            } else return false to "element has no on-screen bounds"
        } else x!! to y!!
        return touchHold(cx, cy, durationMs) to "gesture"
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        touchSwipe(x1, y1, x2, y2, durationMs)

    suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean, percent: Int): Boolean {
        VScreenBridge.touchBackend?.let { return it.pinch(cx, cy, zoomIn, percent) }
        val span = (percent.coerceIn(1, 100) / 100f) * 400f
        val from = if (zoomIn) 60f else span
        val to = if (zoomIn) span else 60f
        val p1 = Path().apply { moveTo(c(cx - from), c(cy)); lineTo(c(cx - to), c(cy)) }
        val p2 = Path().apply { moveTo(c(cx + from), c(cy)); lineTo(c(cx + to), c(cy)) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(stroke(p1, 300))
                .addStroke(stroke(p2, 300))
                .build(),
        )
    }

    /** The biggest visible scrollable node — "the list" of a screen. */
    private fun mainScrollable(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || runCatching { !n.isVisibleToUser }.getOrDefault(true)) return
            runCatching {
                if (n.isScrollable) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    val area = r.width().toLong() * r.height()
                    if (area > bestArea) { best = n; bestArea = area }
                }
            }
            for (i in 0 until min(childCountOf(n), 80)) childAt(n, i)?.let { walk(it, depth + 1) }
        }
        activeRoots().forEach { walk(it, 0) }
        return best
    }

    /**
     * `direction` is where to move in the content: "down" reveals what is
     * below (finger travels up), like scrolling a page. Targets the list at
     * [ref] / containing [text], else the screen's main list. Uses the
     * list's own scroll action when it has one (exact, no gesture
     * physics); swipes otherwise.
     */
    suspend fun scroll(direction: String, ref: Int?, text: String?, times: Int): Pair<Boolean, String> {
        val dir = direction.lowercase()
        if (dir !in setOf("up", "down", "left", "right")) {
            return false to "direction must be up, down, left or right"
        }
        val target = when {
            ref != null -> nodeFor(ref) ?: return false to staleRef(ref)
            text != null -> findByLabel(text, null, null) ?: return false to "no matching element"
            else -> null
        }
        var list = target
        while (list != null && !runCatching { list.isScrollable }.getOrDefault(false)) {
            list = parentOf(list)
        }
        if (list == null && target == null) list = mainScrollable()

        val area = Rect()
        if (list != null && runCatching { list.getBoundsInScreen(area) }.isSuccess) {
            // bounds read into `area`
        } else {
            val (w, h) = screenSize()
            area.set(0, 0, w, h)
        }
        if (area.isEmpty) return false to "list has no on-screen bounds"
        var via = "gesture"
        repeat(times.coerceIn(1, 10)) {
            val acted = list?.let { performScroll(it, dir, area) } == true
            if (acted) via = "scroll_action" else {
                val cx = area.exactCenterX()
                val cy = area.exactCenterY()
                val dx = area.width() * 0.35f
                val dy = area.height() * 0.35f
                val ok = when (dir) {
                    "down" -> swipe(cx, cy + dy, cx, cy - dy, 350)
                    "up" -> swipe(cx, cy - dy, cx, cy + dy, 350)
                    "right" -> swipe(cx + dx, cy, cx - dx, cy, 350)
                    else -> swipe(cx - dx, cy, cx + dx, cy, 350)
                }
                if (!ok) return false to "gesture cancelled"
            }
            delay(150)
        }
        return true to via
    }

    /**
     * Scroll a list until [text]/[desc]/[resId] is visible, at most
     * [maxTimes] scrolls. The container is picked like [scroll] (ref or
     * [containerText], else the screen's main list). Returns found + via.
     */
    suspend fun scrollUntil(
        direction: String,
        text: String?, desc: String?, resId: String?,
        ref: Int?, containerText: String?, maxTimes: Int,
    ): Pair<Boolean, String> {
        if (text == null && desc == null && resId == null) {
            return false to "give text, desc or resource_id to stop on"
        }
        var used = 0
        for (i in 0 until maxTimes.coerceIn(1, 20)) {
            if (findByLabel(text, desc, resId) != null) {
                return true to "found" + if (used > 0) " after $used scrolls" else ""
            }
            val (ok, via) = scroll(direction, ref, containerText, 1)
            if (!ok) return false to via
            used++
            settle(minMs = 60, quietMs = 150, maxMs = 500)
        }
        return if (findByLabel(text, desc, resId) != null) {
            true to "found after $used scrolls"
        } else {
            false to "not found after $used scrolls"
        }
    }

    private fun centerOf(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val r = Rect()
        if (runCatching { node.getBoundsInScreen(r) }.isSuccess && !r.isEmpty) {
            return r.exactCenterX() to r.exactCenterY()
        }
        return null
    }

    /** Resolve the element selectors (ref / text-desc-id / x,y) into a
     *  screen point; null pairs are reported as `error`. */
    private fun pointFor(
        ref: Int?, x: Float?, y: Float?, text: String?, desc: String?, resId: String?,
    ): Pair<Pair<Float, Float>?, String?> {
        if (ref != null) {
            val n = nodeFor(ref) ?: return null to staleRef(ref)
            return centerOf(n)?.let { it to null } ?: (null to "element has no on-screen bounds")
        }
        if (text != null || desc != null || resId != null) {
            val n = findByLabel(text, desc, resId) ?: return null to "no matching element"
            return centerOf(n)?.let { it to null } ?: (null to "element has no on-screen bounds")
        }
        if (x != null && y != null) return (x to y) to null
        return null to "give ref, text, or x and y"
    }

    suspend fun doubleTap(
        ref: Int?, x: Float?, y: Float?, text: String?, desc: String?, resId: String?,
    ): Pair<Boolean, String> {
        val (pt, error) = pointFor(ref, x, y, text, desc, resId)
        val (cx, cy) = pt ?: return false to (error ?: "no target")
        if (!touchTap(cx, cy)) return false to "gesture cancelled"
        delay(110)
        return touchTap(cx, cy) to "gesture"
    }

    /**
     * Press-and-hold at the element/coords, then move to (toX,toY). On a
     * virtual display the host injects the sequence; otherwise a
     * two-stroke gesture (hold, continued into the move) does the drag.
     */
    suspend fun drag(
        ref: Int?, x: Float?, y: Float?, text: String?, desc: String?, resId: String?,
        toX: Float, toY: Float, durationMs: Long, holdMs: Long,
    ): Pair<Boolean, String> {
        val (pt, error) = pointFor(ref, x, y, text, desc, resId)
        val (sx, sy) = pt ?: return false to (error ?: "no target")
        VScreenBridge.touchBackend?.let {
            return it.drag(sx, sy, toX, toY, durationMs, holdMs) to "host"
        }
        val hx = c(sx) + 0.01f
        val hy = c(sy) + 0.01f
        val hold = Path().apply { moveTo(c(sx), c(sy)); lineTo(hx, hy) }
        val s1 = GestureDescription.StrokeDescription(
            hold, 0, holdMs.coerceIn(50, 5_000), true,
        )
        // A continued stroke must begin where the previous one ended.
        val move = Path().apply { moveTo(hx, hy); lineTo(c(toX), c(toY)) }
        val s2 = s1.continueStroke(move, 0, durationMs.coerceIn(50, 30_000), false)
        return dispatch(
            GestureDescription.Builder().addStroke(s1).addStroke(s2).build(),
        ) to "gesture"
    }

    /**
     * The element's text for copy_text: editable fields try select-all +
     * ACTION_COPY first (proper copy path, e.g. spans); the returned
     * String is a fallback the caller writes to the clipboard itself.
     * Returns (clipboardText or null, status): status "copied" means the
     * copy action already filled the clipboard.
     */
    suspend fun copyText(
        ref: Int?, text: String?, desc: String?, resId: String?,
    ): Pair<String?, String> {
        val node = when {
            ref != null -> nodeFor(ref) ?: return null to staleRef(ref)
            else -> findByLabel(text, desc, resId) ?: return null to "no matching element"
        }
        val content = runCatching {
            if (node.isShowingHintText) null
            else node.text?.toString() ?: node.contentDescription?.toString()
        }.getOrNull()
        if (runCatching { node.isEditable }.getOrDefault(false)) {
            val len = content?.length ?: 0
            if (len > 0) {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel) }
                delay(60)
                if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_COPY) }
                        .getOrDefault(false)) {
                    return null to "copied"
                }
            }
        }
        return content?.takeIf { it.isNotEmpty() }?.let { it to "text" }
            ?: (null to "element has no text")
    }

    private fun performScroll(n: AccessibilityNodeInfo, dir: String, area: Rect): Boolean {
        val directional = when (dir) {
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> AccessibilityAction.ACTION_SCROLL_LEFT
        }
        if (runCatching {
            n.actionList.any { it.id == directional.id } && n.performAction(directional.id)
        }.getOrDefault(false)) {
            return true
        }
        // Forward/backward run along the list's own axis only.
        val horizontal = area.width() > area.height()
        val alongAxis = if (horizontal) dir == "left" || dir == "right" else dir == "up" || dir == "down"
        if (!alongAxis) return false
        val forward = dir == "down" || dir == "right"
        return runCatching {
            n.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
        }.getOrDefault(false)
    }

    suspend fun typeText(
        text: String, ref: Int?, selText: String?, selDesc: String?, selId: String?,
        submit: Boolean, append: Boolean = false,
    ): Pair<Boolean, String> {
        val node = if (ref != null) {
            val n = nodeFor(ref) ?: return false to staleRef(ref)
            if (runCatching { n.isEditable }.getOrDefault(false)) n
            else editableIn(n) ?: return false to "element [$ref] is not a text field"
        } else {
            findFirstEditable(selText, selDesc, selId) ?: return false to "no editable field found"
        }
        if (!runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)) {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        }
        delay(80)
        val content = if (append) {
            val cur = runCatching {
                if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            }.getOrDefault("")
            cur + text
        } else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
        }
        if (!runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
                .getOrDefault(false)) {
            return false to "set_text_failed"
        }
        if (!submit) return true to "set_text"
        delay(80)
        runCatching { node.refresh() }
        return if (imeEnter(node)) true to "set_text+enter" else false to "set_text; enter not supported"
    }

    private fun editableIn(n: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (runCatching { n.isEditable }.getOrDefault(false)) return n
        if (depth > 8) return null
        for (i in 0 until min(childCountOf(n), 40)) {
            childAt(n, i)?.let { c -> editableIn(c, depth + 1)?.let { return it } }
        }
        return null
    }

    /** The keyboard's action key (enter / search / go / send) on [node]. */
    private fun imeEnter(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityAction.ACTION_IME_ENTER.id) }
            .getOrDefault(false)

    fun globalKey(name: String): Boolean = when (name.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "enter" -> runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()?.let { imeEnter(it) } == true
        else -> false
    }

    suspend fun waitFor(
        text: String?, desc: String?, resId: String?, pkg: String?, timeoutMs: Long,
        gone: Boolean = false,
    ): Boolean {
        if (pkg == null && text == null && desc == null && resId == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0, 60_000)
        while (System.currentTimeMillis() < deadline) {
            val present = (pkg != null && foregroundPackage() == pkg) ||
                ((text != null || desc != null || resId != null) &&
                    findByLabel(text, desc, resId) != null)
            if (present != gone) return true
            delay(200)
        }
        return false
    }

    /** Raw screenshot as a software bitmap (retries once on the 1 s
     *  rate limit). */
    private suspend fun captureBitmap(retry: Boolean = true): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        var code = 0
        val bmp = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(shot: ScreenshotResult) {
                            val sw = runCatching {
                                val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                                hw?.copy(Bitmap.Config.ARGB_8888, true).also { hw?.recycle() }
                            }.getOrNull()
                            shot.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(sw)
                        }
                        override fun onFailure(errorCode: Int) {
                            code = errorCode
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }
        if (bmp == null && retry && code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(1100)
            return captureBitmap(retry = false)
        }
        return bmp
    }

    /** Full-resolution JPEG (Tools playground / `screenshot` without marks). */
    suspend fun screenshot(): ByteArray? {
        val bmp = captureBitmap() ?: return null
        return ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, it)
            bmp.recycle()
        }.toByteArray()
    }

    /**
     * Vision screenshot: downscaled to [maxSide] and with every visible
     * element's ref drawn as a numbered box (set-of-marks), so the model
     * can answer "tap ref=12" from what it sees. Returns the JPEG and the
     * matching text screen.
     */
    suspend fun markedScreenshot(maxSide: Int = 1024): Pair<ByteArray, Screen>? {
        val screen = snapshot()
        val src = captureBitmap() ?: return null
        return markBitmap(src, screen, maxSide) to screen
    }

    /** Scales [src] to [maxSide], draws ref marks for [screen]'s shown
     *  elements and returns the JPEG. Works on any bitmap source — the
     *  physical-display capture or the virtual-display host's frame,
     *  which may arrive pre-scaled: [coordWidth] is the width of the
     *  space element bounds are in (defaults to the bitmap's own). */
    fun markBitmap(
        src: Bitmap, screen: Screen, maxSide: Int = 1024, coordWidth: Int = src.width,
    ): ByteArray {
        val fit = min(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val scale = fit * src.width / coordWidth.coerceAtLeast(1)
        val bmp = if (fit < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * fit).toInt(), (src.height * fit).toInt(), true,
            ).also { src.recycle() }
        } else src
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val box = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = MARK_COLOR; isAntiAlias = true
        }
        val tagBg = Paint().apply { style = Paint.Style.FILL; color = MARK_COLOR }
        val tagText = Paint().apply {
            color = Color.WHITE; textSize = 13f; isAntiAlias = true; isFakeBoldText = true
        }
        screen.elements.filter { it.ref in screen.shownRefs }.forEach { e ->
            val r = RectF(
                e.bounds.l * scale, e.bounds.t * scale, e.bounds.r * scale, e.bounds.b * scale,
            )
            canvas.drawRect(r, box)
            val label = e.ref.toString()
            val w = tagText.measureText(label) + 6f
            canvas.drawRect(r.left, r.top, r.left + w, r.top + 16f, tagBg)
            canvas.drawText(label, r.left + 3f, r.top + 13f, tagText)
        }
        val jpeg = ByteArrayOutputStream().also {
            out.compress(Bitmap.CompressFormat.JPEG, 60, it)
        }.toByteArray()
        out.recycle()
        return jpeg
    }

    companion object {
        private const val MAX_TREE_NODES = 250
        private const val MAX_SNAPSHOT_NODES = 1500
        private const val MAX_CLICK_HOPS = 4
        private val MARK_COLOR = Color.rgb(230, 30, 30)

        /** Model-supplied selector: regex when valid, else a literal —
         *  "Settings (Beta)" or "$5" must not throw. */
        private fun pattern(s: String?, ignoreCase: Boolean = true): Regex? {
            if (s.isNullOrEmpty()) return null
            val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return runCatching { Regex(s, opts) }.getOrElse { Regex(Regex.escape(s), opts) }
        }

        @Volatile var instance: A11yService? = null
            private set
        val isEnabled: Boolean get() = instance != null

        /**
         * Whether the user granted this service in system settings —
         * authoritative even before the system binds it to our process
         * (the bind can lag a grant by seconds, or a whole app lifetime
         * if the service hasn't been needed yet).
         */
        fun isGranted(ctx: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = android.content.ComponentName(ctx, A11yService::class.java)
                .flattenToShortString()
            return enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it)
                    ?.flattenToShortString() == self
            }
        }

        /** Wait briefly for the system to bind the service after a grant. */
        suspend fun awaitInstance(timeoutMs: Long = 3000): A11yService? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (instance == null && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            return instance
        }
    }
}
