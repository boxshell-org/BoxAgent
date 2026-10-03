package com.boxagent.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.boxagent.app.BoxAgentApp

/** Restart the runtime after reboot when the daemon had been online. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? BoxAgentApp ?: return
        app.onBootRestore()
    }
}
