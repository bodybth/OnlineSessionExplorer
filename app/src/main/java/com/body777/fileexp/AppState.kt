package com.body777.fileexp

import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide singleton for sharing state between ServerService and UI fragments.
 */
object AppState {

    val serverRunning = MutableLiveData(false)
    val serverUrl = MutableLiveData("")
    val logLines = mutableListOf<String>()

    private val logListeners = mutableListOf<(String) -> Unit>()
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "[${sdf.format(Date())}] [$tag] $message"
        logLines.add(line)
        if (logLines.size > 500) logLines.removeAt(0)
        logListeners.toList().forEach { it(line) }
    }

    fun addLogListener(l: (String) -> Unit) {
        if (!logListeners.contains(l)) logListeners.add(l)
    }

    fun removeLogListener(l: (String) -> Unit) {
        logListeners.remove(l)
    }

    fun clearLogs() {
        logLines.clear()
        logListeners.toList().forEach { it("") }   // empty string = clear signal
    }
}
