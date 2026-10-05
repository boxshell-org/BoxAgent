package com.boxagent.app

import android.app.Application
import com.boxagent.app.agent.AgentController
import com.boxagent.app.agent.Notifier
import com.boxagent.app.agent.ToolRunner
import com.boxagent.app.daemon.AutoOutcome
import com.boxagent.app.daemon.DaemonManager
import com.boxagent.app.daemon.ShellState
import com.boxagent.app.data.Secrets
import com.boxagent.app.data.Settings
import com.boxagent.app.data.db.AppDb
import com.boxagent.app.data.db.AuditEntry
import com.boxagent.app.service.AgentService
import com.boxagent.app.skills.SkillRepository
import com.boxagent.app.agent.ToolCatalog
import com.boxagent.app.util.LocaleHelper
import com.boxagent.app.vscreen.VScreenManager
import com.boxagent.app.work.HealthWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BoxAgentApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Service locator — the app is small enough to skip a DI framework.
    lateinit var settings: Settings private set
    lateinit var secrets: Secrets private set
    lateinit var db: AppDb private set
    lateinit var daemon: DaemonManager private set
    lateinit var skills: SkillRepository private set
    lateinit var vscreen: VScreenManager private set
    lateinit var toolRunner: ToolRunner private set
    lateinit var agent: AgentController private set

    override fun onCreate() {
        super.onCreate()
        // Native code (adb_client key file) uses std::env::temp_dir(), which
        // on Android falls back to /data/local/tmp — not writable by apps.
        // Not cacheDir: the FileProvider shares that, and the ADB private
        // key passes through here.
        runCatching {
            val tmp = java.io.File(noBackupFilesDir, "tmp").apply { mkdirs() }
            android.system.Os.setenv("TMPDIR", tmp.absolutePath, true)
        }
        settings = Settings(this)
        secrets = Secrets(this)
        db = AppDb.get(this)
        daemon = DaemonManager(this, settings, secrets)
        skills = SkillRepository(db, settings) {
            runCatching { ToolCatalog.all().map { it.name }.toSet() }.getOrNull()
        }
        vscreen = VScreenManager(this, settings, secrets, daemon)
        toolRunner = ToolRunner(this, daemon, settings, db, skills, vscreen)
        agent = AgentController(this, settings, secrets, toolRunner, db, daemon, skills)

        LocaleHelper.init(this)
        Notifier.ensureChannel(this)
        HealthWorker.schedule(this)

        // Built-in skills, in the UI language (once per built-in).
        appScope.launch {
            runCatching { skills.seedBuiltins(java.util.Locale.getDefault().language) }
        }
        // Reconnect to a live daemon; when there isn't one, climb the
        // auto-connect ladder (stored endpoint → localhost adb → mDNS).
        // Only pairing ever needs a human — everything before it is
        // silent. Then start the FGS when shell is online so the watchdog
        // survives backgrounding.
        appScope.launch {
            if (daemon.reconnect() == null) daemon.autoConnect()
            daemon.status.collect { st ->
                if (st.shell == ShellState.ONLINE && settings.keepWatchdog.first()) {
                    AgentService.start(
                        this@BoxAgentApp,
                        getString(R.string.notif_watchdog_active),
                        wake = false,
                    )
                }
            }
        }
    }

    /** Called from HealthWorker: bring shell back without a human when
     *  possible (silent ladder — never prompts for pairing). */
    suspend fun healthCheck() {
        val st = daemon.status.value
        val alive = st.shell == ShellState.ONLINE && daemon.client?.ping() == true
        if (!alive && daemon.everConnected()) daemon.autoConnect()
        // Logging the check is best-effort — a Room hiccup shouldn't make
        // the worker report failure and retry.
        runCatching {
            db.audit().insert(
                AuditEntry(kind = "lifecycle", detail = "health check ${daemon.status.value.shell}", ok = true),
            )
        }
    }

    /** BootReceiver: the receiver only gets seconds, Wi-Fi (and so
     *  wireless debugging) tens of seconds — hold the process with the
     *  foreground service while the restore runs, then release it. */
    fun onBootRestore(done: () -> Unit = {}) {
        if (!daemon.everConnected()) {
            done()
            return
        }
        AgentService.start(this, getString(R.string.notif_restoring_boot), wake = false)
        done()
        appScope.launch { restoreAfterBoot() }
    }

    private suspend fun restoreAfterBoot() {
        // Daemons die with the reboot; wireless debugging is off until we
        // (with WRITE_SECURE_SETTINGS) or the user turn it back on, and
        // needs Wi-Fi up first. Retry over ~2 minutes.
        var online = false
        for (attempt in 0 until BOOT_ATTEMPTS) {
            if (daemon.autoConnect() == AutoOutcome.Online) {
                online = true
                break
            }
            delay(BOOT_RETRY_MS)
        }
        if (online) {
            AgentService.start(
                this@BoxAgentApp,
                getString(R.string.notif_restored_boot),
                wake = false,
            )
        } else {
            AgentService.stop(this@BoxAgentApp)
            Notifier.post(
                this@BoxAgentApp,
                getString(R.string.notif_attention_title),
                getString(R.string.notif_attention_body),
            )
        }
    }

    private companion object {
        const val BOOT_ATTEMPTS = 8
        const val BOOT_RETRY_MS = 15_000L
    }
}
