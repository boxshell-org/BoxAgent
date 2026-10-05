package com.boxagent.app.vscreen

import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.boxagent.app.R

/**
 * Backdrop of the virtual screen. Keeps the display from ever being empty
 * — an empty public virtual display mirrors the default display, so a
 * screenshot would hand the user's real screen to the model — and is the
 * agent's "home" there (HOME does nothing on a display without system
 * decorations). Shell launches it (`am start --display`), hence exported;
 * it shows nothing sensitive and finishes itself if it ever lands on the
 * physical screen (display teardown on builds that migrate tasks).
 */
class VScreenHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (onPhysicalScreen()) {
            finish()
            return
        }
        // BACK here would empty the display (→ mirroring); stay put.
        onBackPressedDispatcher.addCallback(this) {}
        setContentView(
            TextView(this).apply {
                text = getString(R.string.vscreen_home_text)
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(Color.rgb(0x7a, 0x7a, 0x7a))
                setBackgroundColor(Color.BLACK)
                val pad = (32 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (onPhysicalScreen()) finish()
    }

    private fun onPhysicalScreen(): Boolean =
        runCatching { display?.displayId }.getOrNull()
            .let { it == null || it == Display.DEFAULT_DISPLAY }
}
