package com.boxagent.app.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.boxagent.app.MainActivity
import com.boxagent.app.R
import kotlin.math.hypot

/**
 * Emergency-stop bubble: a small draggable ball shown over other apps
 * while a run is live. Tapping it force-stops the run and brings the app
 * forward. Needs SYSTEM_ALERT_WINDOW — [show] quietly no-ops without it.
 */
class FloatStop(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BallView? = null

    /** Invoked on tap — the owner cancels the run. */
    var onStop: () -> Unit = {}

    // Where the user last parked it, kept across shows.
    private var spotX: Int? = null
    private var spotY: Int? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val d = context.resources.displayMetrics
        val size = (46 * d.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = spotX ?: (d.widthPixels - size - (10 * d.density).toInt())
            y = spotY ?: (d.heightPixels * 0.38f).toInt()
        }
        val v = BallView(context, wm, lp, { x, y -> spotX = x; spotY = y }) {
            onStop()
            // A visible overlay window makes the app foreground-eligible,
            // so this activity start is allowed from anywhere.
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
                )
            }
        }
        v.contentDescription = context.getString(R.string.float_stop_a11y)
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /**
     * Black disc with a white stop square — the send button's language.
     * A faint ring keeps the edge legible on black wallpapers. Drags
     * freely, snaps to the nearest screen edge on release.
     */
    @SuppressLint("ViewConstructor")
    private class BallView(
        context: Context,
        private val wm: WindowManager,
        private val lp: WindowManager.LayoutParams,
        private val onPlaced: (Int, Int) -> Unit,
        private val onClick: () -> Unit,
    ) : View(context) {

        private val d = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0141414.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val glyphRect = RectF()

        init {
            // TalkBack can reach the ball; it's the run's emergency stop.
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            c.drawCircle(r, r, r - ring.strokeWidth, fill)
            c.drawCircle(r, r, r - ring.strokeWidth / 2, ring)
            val g = width * 0.17f
            glyphRect.set(r - g, r - g, r + g, r + g)
            c.drawRoundRect(glyphRect, 2.2f * d, 2.2f * d, glyph)
        }

        private var downX = 0f
        private var downY = 0f
        private var baseX = 0
        private var baseY = 0
        private var dragging = false
        private var snap: ValueAnimator? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snap?.cancel()
                    dragging = false
                    downX = e.rawX
                    downY = e.rawY
                    baseX = lp.x
                    baseY = lp.y
                    alpha = 0.72f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        lp.x = baseX + dx.toInt()
                        lp.y = (baseY + dy.toInt()).coerceIn(0, maxY())
                        runCatching { wm.updateViewLayout(this, lp) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    alpha = 1f
                    if (dragging) snapToEdge() else performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    alpha = 1f
                    if (dragging) snapToEdge()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        // TalkBack activation lands here too.
        override fun performClick(): Boolean {
            onClick()
            return true
        }

        // Keep the ball out from under the gesture bar.
        private fun maxY() =
            resources.displayMetrics.heightPixels - height - (48 * d).toInt()

        private fun snapToEdge() {
            val screenW = resources.displayMetrics.widthPixels
            val target = if (lp.x + width / 2 < screenW / 2) 0 else screenW - width
            onPlaced(target, lp.y)
            snap?.cancel()
            snap = ValueAnimator.ofInt(lp.x, target).apply {
                duration = 140
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    lp.x = it.animatedValue as Int
                    runCatching { wm.updateViewLayout(this@BallView, lp) }
                }
                start()
            }
        }
    }
}
