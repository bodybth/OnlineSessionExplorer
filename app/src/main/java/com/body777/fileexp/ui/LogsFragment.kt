package com.body777.fileexp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.body777.fileexp.AppState
import com.body777.fileexp.R
import com.body777.fileexp.ServerService
import com.body777.fileexp.databinding.FragmentLogsBinding

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val logListener: (String) -> Unit = { line ->
        activity?.runOnUiThread {
            if (line.isEmpty()) {
                binding.tvLogs.text = ""
            } else {
                binding.tvLogs.append("$line\n")
                binding.scrollLogs.post { binding.scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore existing log lines
        val existing = AppState.logLines.joinToString("\n")
        if (existing.isNotEmpty()) binding.tvLogs.text = "$existing\n"

        // Register listener
        AppState.addLogListener(logListener)

        // Observe server state
        AppState.serverRunning.observe(viewLifecycleOwner) { running ->
            updateServerUi(running)
        }
        AppState.serverUrl.observe(viewLifecycleOwner) { url ->
            binding.tvUrl.text = if (url.isNotEmpty()) url else "Server not running"
            binding.cardUrl.visibility = if (url.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnToggleServer.setOnClickListener {
            val running = AppState.serverRunning.value == true
            if (running) {
                ServerService.stop(requireContext())
            } else {
                ServerService.start(requireContext())
            }
        }

        binding.btnClearLogs.setOnClickListener {
            AppState.clearLogs()
            binding.tvLogs.text = ""
        }

        binding.tvUrl.setOnClickListener {
            val url = AppState.serverUrl.value ?: return@setOnClickListener
            if (url.isNotEmpty()) openBrowser(url)
        }

        binding.btnCopyUrl.setOnClickListener {
            val url = AppState.serverUrl.value ?: return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Server URL", url))
            Toast.makeText(requireContext(), "URL copied!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServerUi(running: Boolean) {
        if (running) {
            binding.btnToggleServer.text = "Stop Server"
            binding.btnToggleServer.setBackgroundColor(resources.getColor(R.color.color_stop, null))
            binding.tvServerStatus.text = "● Running"
            binding.tvServerStatus.setTextColor(resources.getColor(R.color.color_online, null))
        } else {
            binding.btnToggleServer.text = "Start Server"
            binding.btnToggleServer.setBackgroundColor(resources.getColor(R.color.color_start, null))
            binding.tvServerStatus.text = "● Stopped"
            binding.tvServerStatus.setTextColor(resources.getColor(R.color.color_offline, null))
            binding.cardUrl.visibility = View.GONE
        }
    }

    private fun openBrowser(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(requireContext(), "No browser found", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        AppState.removeLogListener(logListener)
        _binding = null
        super.onDestroyView()
    }
}
