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

    fun dumpTree(maxDepth: Int = 30, packageFilter: String = ""): JSONObject {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val filtered = mutableListOf<AccessibilityNodeInfo>()
        windows.filter { it.isActive }.forEach { w ->
            w.root?.let { r ->
                if (packageFilter.isEmpty() || r.packageName == packageFilter) filtered.add(r)
                else roots.add(r)
            }
        }
        val all = if (filtered.isNotEmpty()) filtered else (rootInActiveWindow?.let { listOf(it) } ?: emptyList())
        val arr = JSONArray()
        all.forEach { arr.put(nodeToJson(it, 0, maxDepth)) }
        return JSONObject()
            .put("foreground", foregroundPackage())
            .put("windows", arr)
    }

    private fun nodeToJson(n: AccessibilityNodeInfo, depth: Int, max: Int): JSONObject {
        val o = JSONObject()
        o.put("class", n.className?.toString()?.substringAfterLast('.') ?: "")
        n.viewIdResourceName?.let { o.put("id", it.substringAfterLast('/')) }
        n.text?.let { o.put("text", it.toString().take(80)) }
        n.contentDescription?.let { o.put("desc", it.toString().take(80)) }
        val r = Rect(); n.getBoundsInScreen(r)
        o.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
        val caps = JSONArray()
        if (n.isClickable) caps.put("click")
        if (n.isScrollable) caps.put("scroll")
        if (n.isEditable) caps.put("edit")
        if (n.isCheckable) caps.put("check")
        if (n.isLongClickable) caps.put("longclick")
        if (n.isEnabled && caps.length() > 0 || n.isEditable) o.put("caps", caps)
        if (depth < max) {
            val kids = JSONArray()
            for (i in 0 until min(n.childCount, 50)) {
                n.getChild(i)?.let { kids.put(nodeToJson(it, depth + 1, max)) }
            }
            if (kids.length() > 0) o.put("children", kids)
        }
        return o
    }

    fun findNodes(
        text: String?,
        desc: String?,
        resId: String?,
        clickableOnly: Boolean,
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val textRe = text?.toRegex(RegexOption.IGNORE_CASE)
        val descRe = desc?.toRegex(RegexOption.IGNORE_CASE)
        val idRe = resId?.toRegex()
        val roots = windows.filter { it.isActive }.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        fun matches(n: AccessibilityNodeInfo): Boolean {
            if (clickableOnly && !n.isClickable) return false
            if (textRe == null && descRe == null && idRe == null) return false
            var ok = true
            textRe?.let { ok = ok && (n.text?.toString()?.contains(it) == true) }
            descRe?.let { ok = ok && (n.contentDescription?.toString()?.contains(it) == true) }
            idRe?.let { ok = ok && (n.viewIdResourceName?.contains(it) == true) }
            return ok
        }
        fun walk(n: AccessibilityNodeInfo) {
            if (matches(n)) {
                val r = Rect(); n.getBoundsInScreen(r)
                out.add(JSONObject()
                    .put("text", n.text?.toString()?.take(80) ?: "")
                    .put("desc", n.contentDescription?.toString()?.take(80) ?: "")
                    .put("id", n.viewIdResourceName ?: "")
                    .put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                    .put("clickable", n.isClickable))
            }
            for (i in 0 until min(n.childCount, 80)) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return out.take(50)
    }

    private fun findFirstNode(
        text: String?,
        desc: String?,
        resId: String?,
    ): AccessibilityNodeInfo? {
        val textRe = text?.toRegex(RegexOption.IGNORE_CASE)
        val descRe = desc?.toRegex(RegexOption.IGNORE_CASE)
        val idRe = resId?.toRegex()
        val roots = windows.filter { it.isActive }.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            var ok = textRe == null && descRe == null && idRe == null
            textRe?.let { ok = ok || n.text?.toString()?.contains(it) == true }
            descRe?.let { ok = ok || n.contentDescription?.toString()?.contains(it) == true }
            idRe?.let { ok = ok || n.viewIdResourceName?.contains(it) == true }
            if (ok && (textRe != null || descRe != null || idRe != null)) {
                found = n; return
            }
            for (i in 0 until min(n.childCount, 80)) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return found
    }

    private fun findFirstEditable(
        text: String?,
        desc: String?,
        resId: String?,
    ): AccessibilityNodeInfo? {
        val roots = windows.filter { it.isActive }.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        val textRe = text?.toRegex(RegexOption.IGNORE_CASE)
        val descRe = desc?.toRegex(RegexOption.IGNORE_CASE)
        val idRe = resId?.toRegex()
        var found: AccessibilityNodeInfo? = null
        var fallback: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            if (n.isEditable) {
                if (fallback == null) fallback = n
                val sel = (textRe != null && n.text?.toString()?.contains(textRe) == true) ||
                        (descRe != null && n.contentDescription?.toString()?.contains(descRe) == true) ||
                        (idRe != null && n.viewIdResourceName?.contains(idRe) == true)
                if (sel) { found = n; return }
            }
            for (i in 0 until min(n.childCount, 80)) n.getChild(i)?.let { walk(it) }
        }
        roots.forEach { walk(it) }
        return found ?: if (textRe == null && descRe == null && idRe == null) fallback else null
    }

    // ------------------------------------------------------------------
    // Gestures

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gd = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(gd, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) = cont.resume(true)
                override fun onCancelled(g: GestureDescription) = cont.resume(false)
            }, null)
            if (!ok) cont.resume(false)
        }

    private fun nodeCenter(selector: Triple<String?, String?, String?>): Pair<Float, Float>? {
        val node = findFirstNode(selector.first, selector.second, selector.third) ?: return null
        val r = Rect(); node.getBoundsInScreen(r)
        return r.exactCenterX() to r.exactCenterY()
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, 60)
    }

    suspend fun tapOrNode(
        x: Float?, y: Float?,
        text: String?, desc: String?, resId: String?,
    ): Pair<Boolean, String> {
        if (text != null || desc != null || resId != null) {
            val node = findFirstNode(text, desc, resId) ?: return false to "no matching node"
            if (node.isClickable && node.isEnabled) {
                // Prefer semantic click — more reliable than coordinates.
                var n: AccessibilityNodeInfo? = node
                while (n != null && !(n.isClickable && n.isEnabled)) n = n.parent
                if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    return true to "node_click"
                }
            }
            val r = Rect(); node.getBoundsInScreen(r)
            val ok = tap(r.exactCenterX(), r.exactCenterY())
            return ok to "gesture"
        }
        if (x != null && y != null) return tap(x, y) to "gesture"
        return false to "no target"
    }

    suspend fun longPress(x: Float?, y: Float?, text: String?, desc: String?, durationMs: Long): Boolean {
        val (cx, cy) = if (x != null && y != null) x to y
            else nodeCenter(Triple(text, desc, null)) ?: return false
        val path = Path().apply { moveTo(cx, cy) }
        return gesture(path, durationMs)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(path, durationMs)
    }

    suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean, percent: Int): Boolean {
        val span = (percent.coerceIn(1, 100) / 100f) * 400f
        val from = if (zoomIn) 60f else span
        val to = if (zoomIn) span else 60f
        val p1 = Path().apply { moveTo(cx - from, cy); lineTo(cx - to, cy) }
        val p2 = Path().apply { moveTo(cx + from, cy); lineTo(cx + to, cy) }
        return suspendCancellableCoroutine { cont ->
            val gd = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0, 300))
                .addStroke(GestureDescription.StrokeDescription(p2, 0, 300))
                .build()
            val ok = dispatchGesture(gd, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) = cont.resume(true)
                override fun onCancelled(g: GestureDescription) = cont.resume(false)
            }, null)
            if (!ok) cont.resume(false)
        }
    }

    suspend fun scroll(direction: String, times: Int): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2f
        val h = dm.heightPixels / 2f
        val d = dm.heightPixels * 0.35f
        repeat(times) {
            // Direction is the finger's travel direction.
            val ok = when (direction.lowercase()) {
                "up" -> swipe(w, h + d, w, h - d, 350)
                "down" -> swipe(w, h - d, w, h + d, 350)
                "left" -> swipe(w + d, h, w - d, h, 350)
                "right" -> swipe(w - d, h, w + d, h, 350)
                else -> false
            }
            if (!ok) return false
            delay(120)
        }
        return true
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
        val deadline = System.currentTimeMillis() + timeoutMs
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
