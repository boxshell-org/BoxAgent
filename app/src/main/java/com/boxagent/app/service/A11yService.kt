package com.boxagent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastPackage = event.packageName?.toString() ?: lastPackage
        }
    }

    override fun onInterrupt() {}

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

    fun foregroundPackage(): String =
        rootInActiveWindow?.packageName?.toString()?.ifEmpty { lastPackage } ?: lastPackage

    /** Roots to search: the active window(s), else the active-window root. */
    private fun activeRoots(): List<AccessibilityNodeInfo> =
        windows.filter { it.isActive }.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }

    fun dumpTree(maxDepth: Int = 30, packageFilter: String = ""): JSONObject {
        // A package filter may name a non-active window (IME, overlay,
        // split screen) — search all of them for it.
        val byPkg = if (packageFilter.isEmpty()) emptyList()
            else windows.mapNotNull { it.root }.filter { it.packageName == packageFilter }
        val roots = byPkg.ifEmpty { activeRoots() }
        val budget = intArrayOf(MAX_TREE_NODES)
        val arr = JSONArray()
        roots.forEach { r -> nodeToJson(r, 0, maxDepth, budget).forEach(arr::put) }
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
            for (i in 0 until min(n.childCount, 50)) {
                n.getChild(i)?.let { kids += nodeToJson(it, depth + 1, max, budget) }
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
            textRe?.let { if (n.text?.toString()?.contains(it) != true) return false }
            descRe?.let { if (n.contentDescription?.toString()?.contains(it) != true) return false }
            idRe?.let { if (n.viewIdResourceName?.contains(it) != true) return false }
            return true
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
            for (i in 0 until min(n.childCount, 80)) n.getChild(i)?.let { walk(it) }
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
                if (n.isVisibleToUser) { visible = n; return }
                if (any == null) any = n
            }
            for (i in 0 until min(n.childCount, 80)) n.getChild(i)?.let { walk(it) }
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
            if (n.isEditable) {
                if (n.isFocused && focused == null) focused = n
                if (fallback == null) fallback = n
                val sel = (textRe != null && n.text?.toString()?.contains(textRe) == true) ||
                        (descRe != null && n.contentDescription?.toString()?.contains(descRe) == true) ||
                        (idRe != null && n.viewIdResourceName?.contains(idRe) == true) ||
                        // Empty fields often expose their placeholder as hint.
                        (textRe != null && n.hintText?.toString()?.contains(textRe) == true)
                if (sel) { found = n; return }
            }
            for (i in 0 until min(n.childCount, 80)) n.getChild(i)?.let { walk(it) }
        }
        activeRoots().forEach { walk(it) }
        return found ?: if (textRe == null && descRe == null && idRe == null) focused ?: fallback else null
    }

    // ------------------------------------------------------------------
    // Gestures

    private suspend fun dispatch(gd: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gd, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) = cont.resume(true)
                override fun onCancelled(g: GestureDescription) = cont.resume(false)
            }, null)
            if (!ok) cont.resume(false)
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
        val dm = android.util.DisplayMetrics()
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(c(x), c(y)) }
        return gesture(path, 60)
    }

    suspend fun tapOrNode(
        x: Float?, y: Float?,
        text: String?, desc: String?, resId: String?,
    ): Pair<Boolean, String> {
        if (text != null || desc != null || resId != null) {
            val node = findFirstNode(text, desc, resId) ?: return false to "no matching node"
            // Labels are rarely clickable themselves — click the nearest
            // clickable ancestor (bounded, so we don't hit a whole-screen
            // container), which is more reliable than coordinates.
            var n: AccessibilityNodeInfo? = node
            var hops = 0
            while (n != null && !(n.isClickable && n.isEnabled) && hops < MAX_CLICK_HOPS) {
                n = n.parent; hops++
            }
            if (n != null && n.isClickable && n.isEnabled &&
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true to "node_click"
            }
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.isEmpty) return false to "node has no on-screen bounds"
            return tap(r.exactCenterX(), r.exactCenterY()) to "gesture"
        }
        if (x != null && y != null) return tap(x, y) to "gesture"
        return false to "no target"
    }

    suspend fun longPress(
        x: Float?, y: Float?, text: String?, desc: String?, resId: String?, durationMs: Long,
    ): Boolean {
        val (cx, cy) = if (x != null && y != null) x to y else {
            val node = findFirstNode(text, desc, resId) ?: return false
            val r = Rect(); node.getBoundsInScreen(r)
            r.exactCenterX() to r.exactCenterY()
        }
        val path = Path().apply { moveTo(c(cx), c(cy)) }
        return gesture(path, durationMs)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(c(x1), c(y1)); lineTo(c(x2), c(y2)) }
        return gesture(path, durationMs)
    }

    suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean, percent: Int): Boolean {
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

    /**
     * `direction` is where to move in the content: "down" reveals what is
     * below (finger travels up), like scrolling a page. With [text], the
     * swipe runs inside that node's nearest scrollable ancestor.
     */
    suspend fun scroll(direction: String, text: String?, times: Int): Pair<Boolean, String> {
        val area = Rect()
        if (text != null) {
            val node = findFirstNode(text, null, null) ?: return false to "no matching node"
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isScrollable) n = n.parent
            (n ?: node).getBoundsInScreen(area)
            if (area.isEmpty) return false to "node has no on-screen bounds"
        } else {
            val (w, h) = screenSize()
            area.set(0, 0, w, h)
        }
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * 0.35f
        val dy = area.height() * 0.35f
        repeat(times.coerceIn(1, 10)) {
            val ok = when (direction.lowercase()) {
                "down" -> swipe(cx, cy + dy, cx, cy - dy, 350)
                "up" -> swipe(cx, cy - dy, cx, cy + dy, 350)
                "right" -> swipe(cx + dx, cy, cx - dx, cy, 350)
                "left" -> swipe(cx - dx, cy, cx + dx, cy, 350)
                else -> return false to "direction must be up, down, left or right"
            }
            if (!ok) return false to "gesture cancelled"
            delay(150)
        }
        return true to if (text != null) "node" else "screen"
    }

    suspend fun typeText(text: String, selText: String?, selDesc: String?, selId: String?): Pair<Boolean, String> {
        val node = findFirstEditable(selText, selDesc, selId)
            ?: return false to "no editable node found"
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(80)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ok to if (ok) "set_text" else "set_text_failed"
    }

    fun globalKey(name: String): Boolean = when (name.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    suspend fun waitFor(
        text: String?, desc: String?, resId: String?, pkg: String?, timeoutMs: Long,
    ): Boolean {
        if (pkg == null && text == null && desc == null && resId == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0, 60_000)
        while (System.currentTimeMillis() < deadline) {
            if (pkg != null && foregroundPackage() == pkg) return true
            if ((text != null || desc != null || resId != null) &&
                findFirstNode(text, desc, resId) != null) return true
            delay(200)
        }
        return false
    }

    suspend fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(shot: ScreenshotResult) {
                        runCatching {
                            val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                            shot.hardwareBuffer.close()
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false) ?: hw
                            val baos = ByteArrayOutputStream()
                            bmp?.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                            cont.resume(baos.toByteArray())
                        }.onFailure { cont.resume(null) }
                    }
                    override fun onFailure(errorCode: Int) = cont.resume(null)
                },
            )
        }
    }

    companion object {
        private const val MAX_TREE_NODES = 250
        private const val MAX_CLICK_HOPS = 4

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
