package com.body777.fileexp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("ose_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("boot_start", false)) return

            // On API 30+, MANAGE_EXTERNAL_STORAGE must be granted manually by the
            // user via Settings before the server can access /sdcard. If it hasn't
            // been granted yet, skip auto-start — the user will start it manually
            // after opening the app and granting storage access.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()) {
                AppState.log("BOOT", "Skipping auto-start: MANAGE_EXTERNAL_STORAGE not granted")
                return
            }

            AppState.log("BOOT", "Boot completed — auto-starting server")
            ServerService.start(context)
        }
    }
}
