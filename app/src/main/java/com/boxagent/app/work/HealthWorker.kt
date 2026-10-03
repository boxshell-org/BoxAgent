package com.boxagent.app.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.boxagent.app.BoxAgentApp
import com.boxagent.app.data.db.AuditEntry
import com.boxagent.app.service.AgentService
import java.util.concurrent.TimeUnit

/**
 * 15-minute health check: daemon reachable? service alive? If the daemon was
 * previously online and is now dead, attempt a reconnect/respawn.
 */
class HealthWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val app = applicationContext as? BoxAgentApp ?: return Result.success()
        return runCatching {
            kotlinx.coroutines.runBlocking { app.healthCheck() }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "daemon_health"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<HealthWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
