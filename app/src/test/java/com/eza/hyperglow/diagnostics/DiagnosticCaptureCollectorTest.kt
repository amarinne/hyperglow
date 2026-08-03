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
                command.contains("lspd") -> {
                    lsposedCommand = command
                    DiagnosticRootCommandResult(
                        0,
                        "other module\nHyperGlow token=secret spotify:track:abc123 " +
                            "https://private.example\ncom.eza.hyperglow active"
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
