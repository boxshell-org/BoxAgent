package com.boxagent.app.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Wireless-debugging pairing without leaving the system dialog.
 *
 * The 6-digit code lives in Settings' "Pair device with pairing code"
 * dialog, and adbd's pairing service stops the moment that dialog
 * closes — which is exactly what switching to BoxAgent to type the code
 * does on most builds. So this foreground service watches mDNS for this
 * phone's pairing service and takes the code through an inline-reply
 * notification, typed from the shade with the dialog still open
 * underneath (Shizuku's approach). Connect + daemon spawn follow on
 * their own.
 */
class PairingService : Service() {

    sealed interface Phase {
        data object Searching : Phase
        data class Found(val port: Int) : Phase
        data object Pairing : Phase
        data object Connecting : Phase
        data object Done : Phase
        data class Failed(val message: String, val port: Int) : Phase
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoverJob: Job? = null
    private var pairJob: Job? = null
    private var timeoutJob: Job? = null
    @Volatile private var port = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish(null)
                return START_NOT_STICKY
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()
                    ?.filter { it.isDigit() }.orEmpty()
                // The process may have been recycled since the notification
                // went up — the port rides along in the reply intent.
                intent.getIntExtra(EXTRA_PORT, -1).takeIf { it > 0 }?.let { port = it }
                startForegroundCompat(build(_phase.value))
                onCode(code)
            }
            else -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val first = if (port > 0) Phase.Found(port) else Phase.Searching
        startForegroundCompat(build(first))
        update(first)
        if (discoverJob?.isActive != true) {
            val app = application as BoxAgentApp
            discoverJob = scope.launch {
                NsdHelper(app).discover(NsdHelper.TYPE_PAIRING).collect { ep ->
                    if (ep.port <= 0) return@collect
                    port = ep.port
                    // A re-opened dialog announces a new port; don't stomp
                    // on a pairing/connect that is already under way.
                    when (_phase.value) {
                        Phase.Searching, is Phase.Found, is Phase.Failed -> update(Phase.Found(ep.port))
                        else -> {}
                    }
                }
            }
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            if (_phase.value !is Phase.Pairing && _phase.value !is Phase.Connecting) {
                finish(getString(R.string.pair_notif_timeout))
            }
        }
    }

    private fun onCode(code: String) {
        val p = port
        if (code.length < 6 || p <= 0) {
            update(Phase.Failed(getString(R.string.pair_notif_bad_code), p))
            return
        }
        if (pairJob?.isActive == true) return
        val app = application as BoxAgentApp
        pairJob = scope.launch {
            update(Phase.Pairing)
            val r = app.daemon.pair(NsdHelper.LOOPBACK, p, code)
            if (r.isFailure) {
                update(Phase.Failed(r.exceptionOrNull()?.message ?: "?", p))
                return@launch
            }
            update(Phase.Connecting)
            discoverJob?.cancel()
            val out = app.daemon.connectAfterPair { _, _ -> }
            if (out == AutoOutcome.Online) {
                update(Phase.Done)
                delay(4_000)
                finish(null)
            } else {
                finish(getString(R.string.pair_notif_connect_failed))
            }
        }
    }

    /** Stop; [message] (if any) stays behind as a plain notification. */
    private fun finish(message: String?) {
        discoverJob?.cancel()
        timeoutJob?.cancel()
        port = -1
        _phase.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (message != null) {
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    NOTIF_ID + 1,
                    base().setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setOngoing(false).setAutoCancel(true)
                        .setContentIntent(openApp(this))
                        .build(),
                )
            }
        }
        stopSelf()
    }

    private fun update(p: Phase) {
        _phase.value = p
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, build(p)) }
    }

    private fun startForegroundCompat(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun base(): NotificationCompat.Builder {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.pair_notif_title))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
    }

    private fun build(p: Phase?): Notification {
        val b = base().setOngoing(true)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun text(t: String) = b.setContentText(t).setStyle(NotificationCompat.BigTextStyle().bigText(t))
        when (p) {
            null, Phase.Searching -> {
                text(getString(R.string.pair_notif_searching))
                b.setContentIntent(openWirelessDebugging(this))
                b.addAction(0, getString(R.string.cancel), stop)
            }
            is Phase.Found, is Phase.Failed -> {
                text(
                    if (p is Phase.Failed) getString(R.string.pair_notif_failed, p.message)
                    else getString(R.string.pair_notif_found),
                )
                val input = RemoteInput.Builder(KEY_CODE)
                    .setLabel(getString(R.string.pair_notif_code_label))
                    .build()
                val reply = PendingIntent.getService(
                    this, 1,
                    Intent(this, PairingService::class.java).setAction(ACTION_REPLY)
                        .putExtra(EXTRA_PORT, if (p is Phase.Found) p.port else (p as Phase.Failed).port),
                    // RemoteInput needs a mutable intent to carry the text.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(
                    NotificationCompat.Action.Builder(0, getString(R.string.pair_notif_enter), reply)
                        .addRemoteInput(input)
                        .setAllowGeneratedReplies(false)
                        .build(),
                )
                b.addAction(0, getString(R.string.cancel), stop)
                // Heads-up so the action shows over the Settings dialog.
                b.setPriority(NotificationCompat.PRIORITY_HIGH)
            }
            Phase.Pairing -> text(getString(R.string.pair_notif_pairing)).setProgress(0, 0, true)
            Phase.Connecting -> text(getString(R.string.pair_notif_connecting)).setProgress(0, 0, true)
            Phase.Done -> text(getString(R.string.pair_notif_done))
                .setOngoing(false).setContentIntent(openApp(this))
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        _phase.value = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "adb_pairing"
        private const val NOTIF_ID = 40
        private const val KEY_CODE = "code"
        private const val EXTRA_PORT = "port"
        private const val ACTION_REPLY = "com.boxagent.app.pair.REPLY"
        private const val ACTION_STOP = "com.boxagent.app.pair.STOP"
        private const val TIMEOUT_MS = 10 * 60_000L

        private val _phase = MutableStateFlow<Phase?>(null)
        /** Live pairing phase for the UI; null when not running. */
        val phase: StateFlow<Phase?> = _phase

        /** False when notifications are off — the inline reply would be
         *  invisible, so callers fall back to the in-app form. */
        fun canRun(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** Start watching for the pairing dialog and open Wireless
         *  debugging so the user can tap "Pair device with pairing code". */
        fun start(context: Context) {
            val i = Intent(context, PairingService::class.java)
            runCatching { context.startForegroundService(i) }
            runCatching { context.startActivity(wirelessDebuggingIntent()) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PairingService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        /** Developer options, scrolled to (and highlighting) Wireless
         *  debugging where Settings honours the fragment-args key. */
        fun wirelessDebuggingIntent(): Intent =
            Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")

        private fun openWirelessDebugging(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 3, wirelessDebuggingIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun openApp(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context, 4, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.pair_notif_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(null, null) },
            )
        }
    }
}
