package com.eza.hyperglow.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCaptureCollectorTest {
    @Test
    fun rootDenialProducesMetadataOnlyReport() {
        var commands = 0
        val collector = DiagnosticCaptureCollector { _, _ ->
            commands++
            DiagnosticRootCommandResult(exitCode = 1, output = "permission denied")
        }

        val result = collector.collect(0L)

        assertEquals("metadata_only_root_denied", result.outcome)
        assertEquals("denied", result.rootAccessStatus)
        assertEquals(1, commands)
        assertTrue(result.logs.isEmpty())
    }

    @Test
    fun rootCheckDistinguishesGrantDenialAndExecutionError() {
        assertEquals(
            "granted",
            checkDiagnosticRootAccess { _, _ -> DiagnosticRootCommandResult(0, "0\n") }
        )
        assertEquals(
            "denied",
            checkDiagnosticRootAccess { _, _ -> DiagnosticRootCommandResult(1, "denied") }
        )
        assertEquals(
            "error",
            checkDiagnosticRootAccess { _, _ -> DiagnosticRootCommandResult(-1, "") }
        )
    }

    @Test
    fun captureFiltersCrashPackagesAndLsposedIdentity() {
        var lsposedCommand = ""
        var processCommand = ""
        var frameworkCommand = ""
        val collector = DiagnosticCaptureCollector { command, _ ->
            when {
                command == "id -u" -> DiagnosticRootCommandResult(0, "0\n")
                command.contains("-b crash") -> DiagnosticRootCommandResult(
                    0,
                    """
                    08-01 E AndroidRuntime: FATAL EXCEPTION: main
                    08-01 E AndroidRuntime: Process: com.other.app, PID: 1
                    08-01 E AndroidRuntime: private other crash
                    08-01 E AndroidRuntime: FATAL EXCEPTION: main
                    08-01 E AndroidRuntime: Process: com.android.systemui, PID: 2
                    08-01 E AndroidRuntime: java.lang.IllegalStateException
                    """.trimIndent()
                )
                command.contains("if [ -d /data/adb/lspd") -> {
                    frameworkCommand = command
                    DiagnosticRootCommandResult(
                        0,
                        "lspd_dir=present\nlspd_log_dir=present\n" +
                            "module_prop=/data/adb/modules/lsposed/module.prop\n" +
                            "id=lsposed\nversion=1.0\npackage:com.lsposed.manager"
                    )
                }
                command.contains("lspd") -> {
                    lsposedCommand = command
                    DiagnosticRootCommandResult(
                        0,
                        "other module\nHyperGlow token=secret spotify:track:abc123 " +
                            "https://private.example\ncom.eza.hyperglow active"
                    )
                }
                command.contains("ps -A -o USER,UID,PID,ETIME,NAME") -> {
                    processCommand = command
                    DiagnosticRootCommandResult(
                        0,
                        """
                        u0_a0 10000 123 02:29 com.android.systemui
                        u0_a1 10001 124 02:29 com.android.systemui:screenshot
                        u0_a2 10002 125 01:22:02 com.eza.hyperglow
                        u0_a3 10003 126 01:22:02 com.eza.hyperglow:ignored
                        u0_a5 10005 128 00:11 com.android.sys
                        u0_a6 10006 129 00:11 com.eza.hypergl
                        u0_a4 10004 127 03:00 com.other.app
                        malformed com.android.systemui
                        """.trimIndent()
                    )
                }
                else -> DiagnosticRootCommandResult(0, "08-01 I HyperGlow: safe event")
            }
        }

        val result = collector.collect(0L)

        assertEquals("captured", result.outcome)
        assertTrue(result.crashExcerpt.contains("com.android.systemui"))
        assertFalse(result.crashExcerpt.contains("com.other.app"))
        assertFalse(result.lsposedLines.contains("other module"))
        assertTrue(result.lsposedLines.contains("HyperGlow token=<redacted>"))
        assertTrue(result.lsposedLines.contains("spotify:track:<redacted>"))
        assertTrue(result.lsposedLines.contains("<url redacted>"))
        assertFalse(result.lsposedLines.contains("secret"))
        assertTrue(lsposedCommand.contains("tail -c 524288"))
        assertTrue(lsposedCommand.contains("head -n 1"))
        assertTrue(processCommand.contains("com\\.android\\.sys"))
        assertTrue(frameworkCommand.contains("/data/adb/modules"))
        assertTrue(frameworkCommand.contains("zygisk"))
        assertTrue(frameworkCommand.contains("root_solution"))
        assertTrue(frameworkCommand.contains("manager_packages"))
        assertTrue(result.logs.contains("systemui_processes=matched"))
        assertTrue(result.logs.contains("SystemUI process snapshot:"))
        assertTrue(result.logs.contains("u0_a0 10000 123 02:29 com.android.systemui"))
        assertTrue(result.logs.contains("u0_a1 10001 124 02:29 com.android.systemui:screenshot"))
        assertTrue(result.logs.contains("u0_a2 10002 125 01:22:02 com.eza.hyperglow"))
        assertFalse(result.logs.contains("com.other.app"))
        assertFalse(result.logs.contains("malformed com.android.systemui"))
        assertTrue(result.logs.contains("u0_a5 10005 128 00:11 com.android.sys"))
        assertTrue(result.logs.contains("u0_a6 10006 129 00:11 com.eza.hypergl"))
        assertTrue(result.logs.contains("Xposed framework evidence:"))
        assertTrue(result.logs.contains("lspd_dir=present"))
        assertTrue(result.lsposedLines.contains("lsposed_log=unknown"))
        assertEquals(1, result.lsposedLines.split("lsposed_log=").size - 1)
        // `printf` is an external binary on device, so a large match set dies with
        // `Argument list too long`. Every capture command must stream through a pipe instead.
        assertFalse(lsposedCommand.contains("printf"))
        assertFalse(processCommand.contains("printf"))
        assertFalse(frameworkCommand.contains("printf"))
        assertTrue(lsposedCommand.contains("bootstrap="))
    }

    @Test
    fun captureDeadlineHandlesTimeoutAndRebootClockReset() {
        assertFalse(isDiagnosticCaptureExpired(1_000L, 2_000L))
        assertTrue(
            isDiagnosticCaptureExpired(
                1_000L,
                1_000L + DiagnosticLimits.CAPTURE_TTL_MS
            )
        )
        assertTrue(isDiagnosticCaptureExpired(10_000L, 100L))
    }

    @Test
    fun lsposedStatusDistinguishesAbsentEmptyAndMatchedLogs() {
        assertEquals("absent", readLsposedLogStatus("lsposed_log=absent"))
        assertEquals("empty", readLsposedLogStatus("lsposed_log=present"))
        assertEquals("matched", readLsposedLogStatus("lsposed_log=present\nHyperGlow event"))
        assertEquals("unknown", readLsposedLogStatus(""))
    }

    @Test
    fun lsposedStatusReportsEmptyWhenTheLogHeaderHasNoRetainedLines() {
        // Regression: the command used to announce a match and then fail to emit the lines,
        // because `printf` is an external binary and the match set exceeded the argument limit.
        assertEquals(
            "empty",
            readLsposedLogStatus("lsposed_log=present\nprintf: Argument list too long")
        )
    }

    @Test
    fun processStatusDistinguishesUnavailableEmptyAndMatched() {
        assertEquals(
            "unavailable",
            readSystemUiProcessStatus(DiagnosticRootCommandResult(1, "permission denied"))
        )
        assertEquals(
            "unavailable",
            readSystemUiProcessStatus(
                DiagnosticRootCommandResult(0, "systemui_processes_unavailable")
            )
        )
        assertEquals(
            "empty",
            readSystemUiProcessStatus(DiagnosticRootCommandResult(0, "u0_a1 10001 1 00:11 other"))
        )
        assertEquals(
            "matched",
            readSystemUiProcessStatus(
                DiagnosticRootCommandResult(0, "u0_a1 10001 1 00:11 com.eza.hypergl")
            )
        )
    }

    @Test
    fun terminalCaptureEventsRestorePreviousLoggingAndDeleteRequiredState() {
        val start = resolveDiagnosticCaptureLifecycleAction(
            DiagnosticCaptureLifecycleEvent.START,
            previousDiagnosticLogging = false
        )
        val finish = resolveDiagnosticCaptureLifecycleAction(
            DiagnosticCaptureLifecycleEvent.FINISH,
            previousDiagnosticLogging = false
        )
        val cancel = resolveDiagnosticCaptureLifecycleAction(
            DiagnosticCaptureLifecycleEvent.CANCEL,
            previousDiagnosticLogging = true
        )
        val timeout = resolveDiagnosticCaptureLifecycleAction(
            DiagnosticCaptureLifecycleEvent.TIMEOUT,
            previousDiagnosticLogging = false
        )

        assertTrue(start.diagnosticLoggingEnabled)
        assertFalse(start.deleteActiveCaptureState)
        assertFalse(finish.diagnosticLoggingEnabled)
        assertTrue(finish.deleteActiveCaptureState)
        assertFalse(finish.deletePendingDraft)
        assertTrue(cancel.diagnosticLoggingEnabled)
        assertTrue(cancel.deletePendingDraft)
        assertFalse(timeout.diagnosticLoggingEnabled)
        assertTrue(timeout.deletePendingDraft)
    }
}
