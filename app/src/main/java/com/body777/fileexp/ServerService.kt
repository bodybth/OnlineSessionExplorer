package com.body777.fileexp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD as NanoBase
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@ServerService
    }

    private val binder = LocalBinder()
    private var fileServer: FileServer? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        const val CHANNEL_ID = "ose_server_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.body777.fileexp.STOP_SERVER"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        fun stop(ctx: Context)  = ctx.stopService(Intent(ctx, ServerService::class.java))
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ose_prefs", MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }

        val port     = prefs.getInt("port", 8001)
        val dir      = prefs.getString("serve_dir", "/sdcard") ?: "/sdcard"
        val password = prefs.getString("password", "702152") ?: "702152"
        val customIp = prefs.getString("custom_ip", "") ?: ""

        startForeground(NOTIF_ID, buildNotification("Starting server…", ""))

        fileServer?.stop()
        fileServer = FileServer(applicationContext, port, dir, password).also {
            try {
                it.start(NanoBase.SOCKET_READ_TIMEOUT, false)
                val ip  = customIp.ifEmpty { getLocalIp() }
                val url = "http://$ip:$port"
                val ipSource = if (customIp.isNotEmpty()) "manual" else "auto"
                AppState.log("SERVER", "Started on $url  (dir=$dir, ip=$ipSource)")
                AppState.serverRunning.postValue(true)
                AppState.serverUrl.postValue(url)
                updateNotification("Running — $url", url)
            } catch (e: Exception) {
                AppState.log("ERROR", "Failed to start: ${e.message}")
                AppState.serverRunning.postValue(false)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        fileServer?.stop()
        fileServer = null
        AppState.serverRunning.postValue(false)
        AppState.serverUrl.postValue("")
        AppState.log("SERVER", "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── Notification ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "File Server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Online Session Explorer server status"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
    }

    private fun buildNotification(title: String, url: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(url.ifEmpty { "Online Session Explorer" })
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(title: String, url: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, url))
    }

    // ── Network ───────────────────────────────────────────────────────────────
    private fun getLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    // Allow FileServer config to be refreshed without restarting
    fun refreshConfig() {
        val dir      = prefs.getString("serve_dir", "/sdcard") ?: "/sdcard"
        val password = prefs.getString("password", "702152") ?: "702152"
        fileServer?.updateConfig(dir, password)
    }
}


