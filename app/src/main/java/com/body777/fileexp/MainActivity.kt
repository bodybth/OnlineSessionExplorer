package com.body777.fileexp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.body777.fileexp.databinding.ActivityMainBinding
import com.body777.fileexp.ui.LogsFragment
import com.body777.fileexp.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logsFragment    = LogsFragment()
    private val settingsFragment = SettingsFragment()

    companion object {
        private const val REQ_LEGACY = 100
        private const val REQ_NOTIF  = 101
        private const val REQ_MANAGE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load initial fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, logsFragment, "logs")
                .add(R.id.fragment_container, settingsFragment, "settings").hide(settingsFragment)
                .commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_logs -> {
                    supportFragmentManager.beginTransaction()
                        .show(logsFragment).hide(settingsFragment).commit()
                    true
                }
                R.id.nav_settings -> {
                    supportFragmentManager.beginTransaction()
                        .show(settingsFragment).hide(logsFragment).commit()
                    true
                }
                else -> false
            }
        }

        checkPermissions()
        requestNotificationPermission()
    }

    // ── Permission handling ───────────────────────────────────────────────────
    private fun checkPermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) showManageStorageDialog()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val perms = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                val missing = perms.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LEGACY)
            }
        }
    }

    private fun showManageStorageDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage("Online Session Explorer needs full storage access to serve files from /sdcard.\n\nPlease tap 'Allow' and enable 'Allow access to manage all files' for this app.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }, REQ_MANAGE
                    )
                } catch (_: Exception) {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_MANAGE)
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                Toast.makeText(this, "Some directories may not be accessible", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == REQ_LEGACY) {
            val denied = results.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) Toast.makeText(this, "Storage permission denied — some folders may be inaccessible", Toast.LENGTH_LONG).show()
        }
    }
}
