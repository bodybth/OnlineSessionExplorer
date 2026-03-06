package com.body777.fileexp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("ose_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("boot_start", false)) {
                AppState.log("BOOT", "Boot completed — auto-starting server")
                ServerService.start(context)
            }
        }
    }
}
