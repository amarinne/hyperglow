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
            "logs" to "logcat -d -b main -b system -v threadtime -t 4000 " +
                "-s HyperGlow:V '*:S'",
            "systemui_processes" to SYSTEM_UI_PROCESS_COMMAND,
            "framework" to FRAMEWORK_EVIDENCE_COMMAND,
            "crash" to "logcat -d -b crash -v threadtime -T '$timestamp'",
            "lsposed" to LSPOSED_COMMAND
        )
        val results = commands.associate { (name, command) ->
            name to runner.run(command, DiagnosticLimits.COMMAND_TIMEOUT_MS)
        }
        val failures = results.filterValues { it.timedOut || it.exitCode != 0 }.keys.toList()
        val rawLogs = buildString {
            val processResult = results.getValue("systemui_processes")
            val systemUiProcesses = filterSystemUiProcessLines(processResult.output)
            append("systemui_processes=")
                .append(readSystemUiProcessStatus(processResult))
                .append('\n')
            if (systemUiProcesses.isNotBlank()) {
                append("SystemUI process snapshot:\n")
                append(systemUiProcesses)
                append('\n')
            }
            val frameworkEvidence = sanitizeDiagnosticLines(
                results.getValue("framework").output
            )
            if (frameworkEvidence.isNotBlank()) {
                append("Xposed framework evidence:\n")
                append(frameworkEvidence)
                append('\n')
            }
            append(sanitizeDiagnosticLines(results.getValue("logs").output))
        }
        val rawCrash = filterAllowedCrashBlocks(results.getValue("crash").output)
        val lsposedOutput = results.getValue("lsposed").output
        val rawLsposed = buildString {
            append("lsposed_log=").append(readLsposedLogStatus(lsposedOutput)).append('\n')
            append(sanitizeDiagnosticLines(filterHyperGlowModuleLines(lsposedOutput)))
        }
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
        /**
         * Streams matches straight out of the log. A shell variable cannot hold the match set:
         * `printf` is an external binary here, so a large capture fails with `Argument list too
         * long` and silently emits nothing. Bootstrap markers are emitted first so the boot path
         * survives byte-bound truncation, which keeps the oldest prefix and the newest tail.
         */
        private const val LSPOSED_COMMAND =
            "f=\$(ls -1t /data/adb/lspd/log/modules_*.log " +
                "/data/adb/lspd/log/verbose/modules_*.log 2>/dev/null | head -n 1); " +
                "if [ -z \"\$f\" ]; then echo lsposed_log=absent; exit 0; fi; " +
                "echo lsposed_log=present; " +
                "tail -c 524288 \"\$f\" | grep -E 'bootstrap=' | tail -n 200; " +
                "tail -c 524288 \"\$f\" | " +
                "grep -E 'HyperGlow|com\\.eza\\.hyperglow' | grep -v 'bootstrap='; " +
                "exit 0"
        /**
         * `ETIME` is the staleness check for the module log. The LSPosed log survives reboots and
         * upgrades, so a bootstrap marker proves only that the module loaded at some point. A
         * marker older than the current SystemUI process means it did not load into the SystemUI
         * that is running now.
         */
        private const val SYSTEM_UI_PROCESS_COMMAND =
            "ps -A -o USER,UID,PID,ETIME,NAME >/dev/null 2>&1 || " +
                "{ echo systemui_processes_unavailable; exit 0; }; " +
                "ps -A -o USER,UID,PID,ETIME,NAME 2>/dev/null | " +
                "grep -E 'com\\.android\\.sys|com\\.eza\\.hypergl' || true"
        private const val FRAMEWORK_EVIDENCE_COMMAND =
            "if [ -d /data/adb/lspd ]; then echo lspd_dir=present; " +
                "else echo lspd_dir=absent; fi; " +
                "if [ -d /data/adb/lspd/log ]; then echo lspd_log_dir=present; " +
                "else echo lspd_log_dir=absent; fi; " +
                "find /data/adb/modules -maxdepth 2 -type f " +
                "-name module.prop \\( -path '*zygisk*' -o -path '*lsposed*' " +
                "-o -path '*lspatch*' \\) -print 2>/dev/null | " +
                "while read -r f; do echo module_prop=\$f; " +
                "grep -E '^(id|name|version|versionCode)=' \"\$f\"; done; " +
                "found_root=0; " +
                "if [ -d /data/adb/ksu ]; then echo root_solution=ksu path=/data/adb/ksu; found_root=1; fi; " +
                "if [ -d /data/adb/ap ]; then echo root_solution=ap path=/data/adb/ap; found_root=1; fi; " +
                "if [ -d /data/adb/magisk ]; then echo root_solution=magisk path=/data/adb/magisk; found_root=1; fi; " +
                "if [ \"\$found_root\" -eq 0 ]; then echo root_solution=none_detected; fi; " +
                "echo manager_packages=listed; " +
                "pm list packages 2>/dev/null | " +
                "grep -Ei 'package:(org\\.lsposed|io\\.github\\.lsposed|org\\.meowcat\\.edxposed" +
                "|de\\.robv\\.android\\.xposed|org\\.lsposed\\.lspatch|com\\.android\\.shell\\.lsposed)' " +
                "|| true"
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
    .filter {
        line -> line.contains("HyperGlow") || line.contains("com.eza.hyperglow")
    }
    .joinToString("\n")

internal fun filterSystemUiProcessLines(output: String): String = output.lineSequence()
    .map(String::trim)
    .filter { line -> SYSTEM_UI_PROCESS_LINE.containsMatchIn(line) }
    .joinToString("\n")

internal fun readSystemUiProcessStatus(result: DiagnosticRootCommandResult): String = when {
    result.timedOut || result.exitCode != 0 -> "unavailable"
    result.output.contains("systemui_processes_unavailable") -> "unavailable"
    filterSystemUiProcessLines(result.output).isBlank() -> "empty"
    else -> "matched"
}

/**
 * The command streams matches instead of counting them, so `matched` and `empty` are decided from
 * the retained lines rather than a shell-side marker. A shell-side match marker can disagree with
 * the retained lines when the command emits its status but then fails to emit the lines.
 */
internal fun readLsposedLogStatus(output: String): String = when {
    output.contains("lsposed_log=absent") -> "absent"
    !output.contains("lsposed_log=present") -> "unknown"
    filterHyperGlowModuleLines(output).isBlank() -> "empty"
    else -> "matched"
}

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
private val SYSTEM_UI_PROCESS_LINE = Regex(
    "\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+" +
        "(?:com\\.android\\.systemui|com\\.android\\.sys|com\\.eza\\.hyperglow|com\\.eza\\.hypergl)" +
        "(?::[A-Za-z0-9_.-]+)?(?:\\s|$)"
)
