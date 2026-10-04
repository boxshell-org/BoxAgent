package com.boxagent.app

import android.app.Application
import com.boxagent.app.agent.AgentController
import com.boxagent.app.agent.Notifier
import com.boxagent.app.agent.ToolRunner
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
import com.boxagent.app.work.HealthWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        toolRunner = ToolRunner(this, daemon, settings, db, skills)
        agent = AgentController(this, settings, secrets, toolRunner, db, daemon, skills)

        LocaleHelper.init(this)
        Notifier.ensureChannel(this)
        HealthWorker.schedule(this)

        // Built-in skills, in the UI language (once per built-in).
        appScope.launch {
            runCatching { skills.seedBuiltins(java.util.Locale.getDefault().language) }
        }
        // Reconnect to a live daemon; start the FGS when shell is online so
        // the watchdog survives backgrounding.
        appScope.launch {
            daemon.reconnect()
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

    /** Called from HealthWorker + BootReceiver. */
    suspend fun healthCheck() {
        val st = daemon.status.value
        if (st.shell == ShellState.ONLINE) {
            val alive = daemon.client?.ping() == true
            if (!alive) daemon.reconnect()
        } else {
            daemon.reconnect()
        }
        // Logging the check is best-effort — a Room hiccup shouldn't make
        // the worker report failure and retry.
        runCatching {
            db.audit().insert(
                AuditEntry(kind = "lifecycle", detail = "health check ${daemon.status.value.shell}", ok = true),
            )
        }
    }

    fun onBootRestore(done: () -> Unit = {}) {
        appScope.launch {
            try {
                restoreAfterBoot()
            } finally {
                done()
            }
        }
    }

    private suspend fun restoreAfterBoot() {
        val had = daemon.reconnect() != null
        if (had) {
            AgentService.start(
                this@BoxAgentApp,
                getString(R.string.notif_restored_boot),
                wake = false,
            )
        } else {
            // Wireless debugging resets on reboot on many devices —
            // surface a repair prompt rather than silently failing.
            Notifier.post(
                this@BoxAgentApp,
                getString(R.string.notif_attention_title),
                getString(R.string.notif_attention_body),
            )
        }
    }
}
