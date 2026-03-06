package com.body777.fileexp.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.body777.fileexp.R
import com.body777.fileexp.ServerService
import com.body777.fileexp.databinding.FragmentSettingsBinding
import java.net.Inet4Address
import java.net.NetworkInterface

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences("ose_prefs", 0)
        loadSettings()

        binding.tvLocalIp.text = getLocalIp()

        binding.btnDetectIp.setOnClickListener {
            val ip = getLocalIp()
            binding.etCustomIp.setText(ip)
            Toast.makeText(requireContext(), "Auto-detected: $ip", Toast.LENGTH_SHORT).show()
        }

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnRestart.setOnClickListener {
            saveSettings()
            ServerService.stop(requireContext())
            ServerService.start(requireContext())
            Toast.makeText(requireContext(), "Server restarting…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSettings() {
        binding.etServeDir.setText(prefs.getString("serve_dir", "/sdcard"))
        binding.etPort.setText(prefs.getInt("port", 8001).toString())
        binding.etPassword.setText(prefs.getString("password", "702152"))
        binding.etCustomIp.setText(prefs.getString("custom_ip", ""))
        binding.swHttps.isChecked = prefs.getBoolean("use_https", false)
        binding.swBootStart.isChecked = prefs.getBoolean("boot_start", false)
        when (prefs.getString("theme", "system")) {
            "light" -> binding.rgTheme.check(R.id.rb_light)
            "dark"  -> binding.rgTheme.check(R.id.rb_dark)
            else    -> binding.rgTheme.check(R.id.rb_system)
        }
    }

    private fun saveSettings() {
        val dir      = binding.etServeDir.text.toString().trim().ifEmpty { "/sdcard" }
        val port     = binding.etPort.text.toString().trim().toIntOrNull()?.coerceIn(1024, 65535) ?: 8001
        val password = binding.etPassword.text.toString()
        val customIp = binding.etCustomIp.text.toString().trim()
        val https    = binding.swHttps.isChecked
        val boot     = binding.swBootStart.isChecked
        val theme    = when (binding.rgTheme.checkedRadioButtonId) {
            R.id.rb_light -> "light"
            R.id.rb_dark  -> "dark"
            else          -> "system"
        }

        if (customIp.isNotEmpty() && !isValidIp(customIp)) {
            Toast.makeText(requireContext(), "Invalid IP — use format: 192.168.1.x", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putString("serve_dir", dir)
            .putInt("port", port)
            .putString("password", password)
            .putString("custom_ip", customIp)
            .putBoolean("use_https", https)
            .putBoolean("boot_start", boot)
            .putString("theme", theme)
            .apply()

        applyTheme(theme)

        val extra = buildList {
            if (https) add("HTTPS on")
            if (boot)  add("boot-start on")
            if (customIp.isNotEmpty()) add("IP: $customIp")
        }.joinToString(", ")

        Toast.makeText(requireContext(), "Saved${if (extra.isNotEmpty()) " ($extra)" else ""}", Toast.LENGTH_SHORT).show()
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun applyTheme(theme: String) {
        AppCompatDelegate.setDefaultNightMode(when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
            else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    private fun getLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "Unavailable"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address)
                        return addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {}
        return "Unavailable"
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
