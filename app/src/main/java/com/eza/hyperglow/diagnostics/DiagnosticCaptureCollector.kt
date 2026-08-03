package com.eza.hyperglow.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class DiagnosticRootCommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val outputTruncated: Boolean = false
)

internal fun interface DiagnosticRootCommandRunner {
    fun run(command: String, timeoutMs: Long): DiagnosticRootCommandResult
}

internal data class CapturedDiagnosticData(
    val outcome: String,
    val rootAccessStatus: String,
    val logs: String,
    val crashExcerpt: String,
    val lsposedLines: String,
    val commandFailures: List<String>,
    val truncationFlags: Map<String, Boolean>
)

internal class DiagnosticCaptureCollector(
    private val runner: DiagnosticRootCommandRunner
) {
    fun collect(startedAtUtcMillis: Long): CapturedDiagnosticData {
        val rootAccessStatus = checkDiagnosticRootAccess(runner)
        if (rootAccessStatus != "granted") {
            return CapturedDiagnosticData(
                outcome = "metadata_only_root_denied",
                rootAccessStatus = rootAccessStatus,
                logs = "",
                crashExcerpt = "",
                lsposedLines = "",
                commandFailures = listOf("root_access"),
                truncationFlags = emptyMap()
            )
        }

        val timestamp = LOGCAT_TIME_FORMATTER.format(
            Instant.ofEpochMilli(startedAtUtcMillis).atZone(ZoneId.systemDefault())
        )
        val commands = listOf(
            "logs" to "logcat -d -b main -b system -v threadtime -T '$timestamp' " +
                "-s HyperGlow:V '*:S'",
            "crash" to "logcat -d -b crash -v threadtime -T '$timestamp'",
            "lsposed" to LSPOSED_COMMAND
        )
        val results = commands.associate { (name, command) ->
            name to runner.run(command, DiagnosticLimits.COMMAND_TIMEOUT_MS)
        }
        val failures = results.filterValues { it.timedOut || it.exitCode != 0 }.keys.toList()
        val rawLogs = sanitizeDiagnosticLines(results.getValue("logs").output)
        val rawCrash = filterAllowedCrashBlocks(results.getValue("crash").output)
        val rawLsposed = sanitizeDiagnosticLines(
            filterHyperGlowModuleLines(results.getValue("lsposed").output)
        )
        val logs = truncateDiagnosticLines(rawLogs, DiagnosticLimits.LOGCAT_BYTES)
        val crash = truncateDiagnosticLines(rawCrash, DiagnosticLimits.CRASH_BYTES)
        val lsposed = truncateDiagnosticLines(rawLsposed, DiagnosticLimits.LSPOSED_BYTES)
        val flags = linkedMapOf(
            "diagnosticEventsAndLogs" to (
                logs.truncated || results.getValue("logs").outputTruncated
                ),
            "crashExcerpt" to (
                crash.truncated || results.getValue("crash").outputTruncated
                ),
            "lsposedModuleLines" to (
                lsposed.truncated || results.getValue("lsposed").outputTruncated
                )
        )
        return CapturedDiagnosticData(
            outcome = if (failures.isEmpty()) "captured" else "partial_capture",
            rootAccessStatus = "granted",
            logs = logs.text,
            crashExcerpt = crash.text,
            lsposedLines = lsposed.text,
            commandFailures = failures,
            truncationFlags = flags
        )
    }

    companion object {
        private const val LSPOSED_COMMAND =
            "f=\$(ls -1t /data/adb/lspd/log/modules_*.log " +
                "/data/adb/lspd/log/verbose/modules_*.log 2>/dev/null | head -n 1); " +
                "[ -n \"\$f\" ] && tail -c 524288 \"\$f\" | " +
                "grep -E 'HyperGlow|com\\.eza\\.hyperglow'; exit 0"
        private val LOGCAT_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "MM-dd HH:mm:ss.SSS",
            Locale.US
        )
    }
}

internal fun checkDiagnosticRootAccess(runner: DiagnosticRootCommandRunner): String {
    val result = runner.run("id -u", DiagnosticLimits.COMMAND_TIMEOUT_MS)
    return when {
        result.timedOut -> "error"
        result.exitCode == 0 && result.output.trim() == "0" -> "granted"
        result.exitCode < 0 && result.output.isBlank() -> "error"
        else -> "denied"
    }
}

internal fun filterHyperGlowModuleLines(output: String): String = output.lineSequence()
    .filter { line -> line.contains("HyperGlow") || line.contains("com.eza.hyperglow") }
    .joinToString("\n")

internal fun filterAllowedCrashBlocks(output: String): String {
    if (output.isBlank()) return ""
    val blocks = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    output.lineSequence().forEach { line ->
        if (isCrashBoundary(line) && current.isNotEmpty()) {
            blocks += current
            current = mutableListOf()
        }
        current += line
    }
    if (current.isNotEmpty()) blocks += current
    return blocks.asSequence()
        .filter { block -> block.any(::containsAllowedCrashIdentity) }
        .joinToString("\n") { block ->
            block.mapNotNull(::sanitizeCrashLine).joinToString("\n")
        }
}

internal fun sanitizeDiagnosticLines(output: String): String = output.lineSequence()
    .map(::redactDiagnosticSecrets)
    .joinToString("\n")

private fun sanitizeCrashLine(line: String): String? {
    val redacted = redactDiagnosticSecrets(line)
    val content = redacted.substringAfter(": ", redacted).trimStart()
    return when {
        content.contains("FATAL EXCEPTION") || content.startsWith("Process: ") ||
            content.startsWith("Cmdline: ") || content.startsWith("pid: ") ||
            content.startsWith("signal ") || content == "backtrace:" ||
            content.startsWith("#") || content.startsWith("at ") -> redacted
        content.startsWith("Caused by: ") || content.startsWith("Suppressed: ") ->
            redacted.replace(EXCEPTION_MESSAGE_REGEX, "$1: <message redacted>")
        EXCEPTION_CLASS_LINE_REGEX.containsMatchIn(content) ->
            redacted.replace(EXCEPTION_MESSAGE_REGEX, "$1: <message redacted>")
        else -> null
    }
}

private fun redactDiagnosticSecrets(line: String): String = line
    .replace(SPOTIFY_TRACK_URI_REGEX, "spotify:track:<redacted>")
    .replace(URL_REGEX, "<url redacted>")
    .replace(CREDENTIAL_REGEX, "$1=<redacted>")
    .replace(EXCEPTION_MESSAGE_REGEX, "$1: <message redacted>")

private fun isCrashBoundary(line: String): Boolean =
    line.contains("FATAL EXCEPTION") || line.startsWith("*** *** ***") ||
        line.contains("Fatal signal ")

private fun containsAllowedCrashIdentity(line: String): Boolean =
    ALLOWED_CRASH_PROCESS_REGEX.containsMatchIn(line)

private val SPOTIFY_TRACK_URI_REGEX = Regex("spotify:track:[A-Za-z0-9]+")
private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val CREDENTIAL_REGEX = Regex(
    "(?i)\\b(token|authorization|cookie|set-cookie)\\s*[=:]\\s*\\S+"
)
private val EXCEPTION_MESSAGE_REGEX = Regex(
    "((?:java|kotlin|android|com\\.[A-Za-z0-9_$.]+)\\.[A-Za-z0-9_$.]*(?:Exception|Error))(?::[^\\n]*)?"
)
private val EXCEPTION_CLASS_LINE_REGEX = Regex(
    "^(?:java|kotlin|android|com\\.[A-Za-z0-9_$.]+)\\.[A-Za-z0-9_$.]*(?:Exception|Error)"
)
private val ALLOWED_CRASH_PROCESS_REGEX = Regex(
    "(?:Process:|Cmdline:|>>>)\\s*" +
        "(?:com\\.eza\\.hyperglow|com\\.android\\.systemui|com\\.spotify\\.music)(?=[:,\\s<]|$)"
)
