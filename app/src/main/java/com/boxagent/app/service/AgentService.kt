package com.boxagent.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.boxagent.app.agent.Notifier

/**
 * Keep-alive foreground service. Holds a partial wake lock while a task is
 * in flight; the daemon watchdog (DaemonManager) lives on the app process.
 */
class AgentService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "active"
        ServiceCompat.startForeground(
            this,
            Notifier.FG_ID,
            Notifier.runningNotification(this, text),
            if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
        if (intent?.getBooleanExtra(EXTRA_WAKE, false) == true) acquireWake()
        return START_STICKY
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boxagent:task")
            .apply { acquire(30 * 60 * 1000L) }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_WAKE = "wake"

        fun start(context: Context, text: String, wake: Boolean = true) {
            val i = Intent(context, AgentService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_WAKE, wake)
            // From the background, startForegroundService can throw
            // (ForegroundServiceStartNotAllowedException) — degrade to a
            // normal start rather than crash the app.
            runCatching { context.startForegroundService(i) }
                .onFailure { runCatching { context.startService(i) } }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
