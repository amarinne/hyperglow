package com.eza.hyperglow

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Root-readable mirror of the app-process trace.
 *
 * HyperOS drops this process's logcat output on the owner device, so `AppLog` alone is invisible in
 * a field report and every app-side decision has to be inferred from the SystemUI hook log. The
 * mirror exists only while diagnostic logging is on, is size-bounded, and keeps a single rotation so
 * a long session cannot fill the data partition.
 */
internal object DiagnosticTraceFile {
    internal const val FILE_NAME = "diagnostic-trace.log"
    internal const val ROTATED_FILE_NAME = "diagnostic-trace.log.1"
    internal const val MAX_BYTES = 512L * 1024L

    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var directory: File? = null

    fun setDirectory(directory: File?) {
        this.directory = directory
    }

    fun append(level: String, area: String, message: String) {
        val target = directory ?: return
        val line = "${format(System.currentTimeMillis())} $level [$area] $message"
        runCatching { write(target, line) }
    }

    @Synchronized
    private fun format(nowMs: Long): String = timestamp.format(Date(nowMs))

    @Synchronized
    private fun write(directory: File, line: String) {
        if (!directory.isDirectory) return
        val file = File(directory, FILE_NAME)
        if (shouldRotateTrace(file.length(), MAX_BYTES)) {
            File(directory, ROTATED_FILE_NAME).delete()
            file.renameTo(File(directory, ROTATED_FILE_NAME))
        }
        file.appendText("$line\n")
    }
}

internal fun shouldRotateTrace(sizeBytes: Long, maxBytes: Long): Boolean = sizeBytes >= maxBytes
