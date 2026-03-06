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
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.AsyncRunner
import fi.iki.elonen.NanoHTTPD.ClientHandler
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import org.bouncycastle.x509.X509V3CertificateGenerator

class ServerService : Service() {

    inner class LocalBinder : Binder() { fun getService() = this@ServerService }
    private val binder = LocalBinder()

    private var fileServer: FileServer? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        const val CHANNEL_ID  = "ose_server_channel"
        const val NOTIF_ID    = 1
        const val ACTION_STOP = "com.body777.fileexp.STOP_SERVER"
        const val KS_PASS     = "ose_keystore_2024"

        // Thread pool: min 4, max 32 concurrent connections, idle threads live 30s
        private const val POOL_CORE = 4
        private const val POOL_MAX  = 32
        private const val POOL_IDLE = 30L

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        fun stop(ctx: Context)  = ctx.stopService(Intent(ctx, ServerService::class.java))
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ose_prefs", MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val port     = prefs.getInt("port", 8001)
        val dir      = prefs.getString("serve_dir", "/sdcard") ?: "/sdcard"
        val password = prefs.getString("password", "702152") ?: "702152"
        val customIp = prefs.getString("custom_ip", "") ?: ""
        val useHttps = prefs.getBoolean("use_https", false)

        startForeground(NOTIF_ID, buildNotification("Starting server…", ""))

        fileServer?.stop()
        fileServer = FileServer(applicationContext, port, dir, password).also { srv ->
            // ── Thread pool async runner ──────────────────────────────────────
            srv.setAsyncRunner(PooledRunner())
            AppState.log("SERVER", "Thread pool: $POOL_CORE–$POOL_MAX threads")

            try {
                if (useHttps) {
                    val sslFactory = buildSslFactory()
                    if (sslFactory != null) {
                        srv.makeSecure(sslFactory, null)
                        AppState.log("HTTPS", "TLS enabled with self-signed cert")
                    } else {
                        AppState.log("HTTPS", "⚠️ Keystore failed — using HTTP")
                    }
                }

                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                val scheme = if (useHttps) "https" else "http"
                val ip     = customIp.ifEmpty { getLocalIp() }
                val url    = "$scheme://$ip:$port"
                AppState.log("SERVER", "Started on $url  (dir=$dir, ip=${if (customIp.isNotEmpty()) "manual" else "auto"}, tls=$useHttps)")
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
        fileServer?.stop(); fileServer = null
        AppState.serverRunning.postValue(false)
        AppState.serverUrl.postValue("")
        AppState.log("SERVER", "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun refreshConfig() {
        val dir = prefs.getString("serve_dir", "/sdcard") ?: "/sdcard"
        val pwd = prefs.getString("password", "702152") ?: "702152"
        fileServer?.updateConfig(dir, pwd)
    }

    // ── Pooled async runner ───────────────────────────────────────────────────
    /**
     * Replaces NanoHTTPD's default one-thread-per-connection approach with a
     * bounded thread pool. This handles burst traffic much better and avoids
     * thread exhaustion under load.
     */
    private inner class PooledRunner : AsyncRunner {
        private val pool = ThreadPoolExecutor(
            POOL_CORE, POOL_MAX,
            POOL_IDLE, TimeUnit.SECONDS,
            LinkedBlockingQueue(256),
            { r -> Thread(r, "OSE-HTTP").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy()   // if queue full, run on calling thread
        ).also { it.allowCoreThreadTimeOut(true) }

        private val running = java.util.concurrent.CopyOnWriteArrayList<ClientHandler>()

        override fun closeAll() {
            running.toList().forEach { runCatching { it.close() } }
        }

        override fun closed(clientHandler: ClientHandler) {
            running.remove(clientHandler)
        }

        override fun exec(clientHandler: ClientHandler) {
            running.add(clientHandler)
            pool.execute {
                try { clientHandler.run() }
                finally { running.remove(clientHandler) }
            }
        }
    }

    // ── HTTPS / SSL ───────────────────────────────────────────────────────────
    // Generates a self-signed RSA cert entirely at runtime and loads it into
    // an in-memory KeyStore. This avoids all PKCS12 compatibility issues
    // (PBES2 / AES-256 MAC errors) caused by modern keystore formats on Android.
    @Suppress("DEPRECATION")
    private fun buildSslFactory(): javax.net.ssl.SSLServerSocketFactory? {
        return try {
            // Generate 2048-bit RSA key pair
            val kpg  = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp   = kpg.generateKeyPair()

            // Build a self-signed X.509 certificate valid for 10 years
            val now     = System.currentTimeMillis()
            val subject = X500Principal("CN=OSE-Server, O=OnlineSessionExplorer")
            val gen     = X509V3CertificateGenerator().apply {
                setSerialNumber(BigInteger.valueOf(now))
                setSubjectDN(subject)
                setIssuerDN(subject)
                setNotBefore(Date(now))
                setNotAfter(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
                setPublicKey(kp.public)
                setSignatureAlgorithm("SHA256WithRSAEncryption")
            }
            val cert = gen.generate(kp.private)

            // Load into an in-memory JKS KeyStore — JKS is always available on Android
            val pass = KS_PASS.toCharArray()
            val ks   = KeyStore.getInstance("JKS")
            ks.load(null, pass)
            ks.setKeyEntry("ose", kp.private, pass, arrayOf(cert))

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, pass)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            AppState.log("HTTPS", "Self-signed cert generated OK")
            ctx.serverSocketFactory
        } catch (e: Exception) {
            AppState.log("ERROR", "SSL setup failed: ${e.message}")
            null
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "File Server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Online Session Explorer server status"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
    }

    private fun buildNotification(title: String, url: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, ServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(url.ifEmpty { "Online Session Explorer" })
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(title: String, url: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(title, url))
    }

    // ── Network ───────────────────────────────────────────────────────────────
    private fun getLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address)
                        return addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
