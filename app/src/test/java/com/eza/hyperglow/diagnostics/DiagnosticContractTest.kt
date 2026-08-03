package com.eza.hyperglow.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticContractTest {
    @Test
    fun compatibilityCategoryUsesUnambiguousLabel() {
        assertEquals("Compatibility", HyperGlowReportCategory.COMPATIBILITY.displayName)
    }

    @Test
    fun reportIdUsesFixedCrockfordEncoding() {
        val id = DiagnosticReportId.fromBytes(ByteArray(16))

        assertEquals("R1-00000000000000000000000000", id)
        assertTrue(isValidDiagnosticReportId(id))
        assertTrue(isValidDiagnosticReportId(DiagnosticReportId.fromBytes(ByteArray(16) { -1 })))
        assertFalse(isValidDiagnosticReportId("R1-OOOOOOOOOOOOOOOOOOOOOOOOOO"))
    }

    @Test
    fun utf8TruncationKeepsFirstQuarterAndNewestThreeQuarters() {
        val source = "start-" + "中".repeat(80) + "-newest"

        val truncated = truncateDiagnosticText(source, 100)

        assertTrue(truncated.truncated)
        assertTrue(truncated.text.startsWith("start-"))
        assertTrue(truncated.text.endsWith("-newest"))
        assertTrue(truncated.text.contains("TRUNCATED"))
        assertTrue(truncated.includedBytes <= 100)
        assertFalse(truncated.text.contains('\uFFFD'))
    }

    @Test
    fun lineTruncationDoesNotKeepPartialNewestLogLine() {
        val source = (1..40).joinToString("\n") { index ->
            "HyperGlow line-$index " + "x".repeat(24)
        }

        val truncated = truncateDiagnosticLines(source, 240)
        val retained = truncated.text.lineSequence()
            .filter { it.isNotBlank() && !it.contains("TRUNCATED") }
            .toList()

        assertTrue(truncated.truncated)
        assertTrue(retained.isNotEmpty())
        assertTrue(retained.all { it.startsWith("HyperGlow line-") })
        assertTrue(truncated.text.endsWith("HyperGlow line-40 " + "x".repeat(24)))
        assertTrue(truncated.includedBytes <= 240)
    }

    @Test
    fun reportCodecRejectsOversizedDescription() {
        val report = sampleReport(description = "界".repeat(1_334))

        assertFalse(DiagnosticReportCodec.isValidReport(report))
    }

    @Test
    fun encodedReportContainsMediaEvidenceButNoCredentialFields() {
        val encoded = DiagnosticReportCodec.encode(sampleReport())

        assertTrue(encoded.contains("spotify:track:test"))
        assertTrue(encoded.contains("Current lyric"))
        listOf(
            "artwork",
            "imageId",
            "androidId",
            "serial",
            "imei",
            "wifiSsid",
            "authToken",
            "cookie",
            "screenshot"
        ).forEach { forbidden -> assertFalse(forbidden, encoded.contains(forbidden)) }
    }

    @Test
    fun githubIssueExcludesPrivateDiagnostics() {
        val report = sampleReport(
            description = "AOD disappears after a song change.",
            privateLog = "PRIVATE_LOG_SENTINEL"
        )

        val issue = buildHyperGlowGitHubIssue(report)

        assertTrue(issue.body.contains(report.reportId))
        assertTrue(issue.body.contains(report.description))
        assertTrue(issue.body.contains("## Description"))
        assertTrue(issue.body.contains("## Report details"))
        assertTrue(issue.body.contains("## Diagnostic data"))
        assertTrue(issue.body.contains(HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL))
        assertFalse(issue.body.contains("PRIVATE_LOG_SENTINEL"))
        assertFalse(issue.body.contains("keepAodActive"))
        assertTrue(issue.body.contains("Test song"))
        assertTrue(issue.body.contains("spotify:track:test"))
        assertFalse(issue.body.contains("Current lyric"))
    }

    @Test
    fun jsonPreviewIsPrettyPrintedButRoundTrips() {
        val report = sampleReport()
        val preview = DiagnosticReportCodec.encodePretty(report)

        assertTrue(preview.contains('\n'))
        assertTrue(preview.contains("    \"reportId\""))
        assertEquals(report, DiagnosticReportCodec.decodeOrNull(preview))
    }

    @Test
    fun readableJsonPreviewExpandsMultilineDiagnosticsWithoutChangingPayload() {
        val report = sampleReport(privateLog = "first event\nsecond event")
        val payload = DiagnosticReportCodec.encode(report)

        val preview = requireNotNull(DiagnosticJsonPreviewFormatter.format(payload))

        assertEquals("first event\nsecond event", preview.diagnosticEventsAndLogs)
        assertFalse(preview.reportJson.contains("rawDiagnostics"))
        assertTrue(preview.reportJson.contains("\n"))
        assertEquals(report, DiagnosticReportCodec.decodeOrNull(payload))
    }

    @Test
    fun readableJsonPreviewRejectsMalformedJson() {
        assertNull(DiagnosticJsonPreviewFormatter.format("not-json"))
    }

    @Test
    fun invalidPendingJsonIsRejected() {
        assertNull(DiagnosticReportCodec.decodeOrNull("{\"envelopeVersion\":99}"))
    }

    private fun sampleReport(
        description: String = "Compatibility problem",
        privateLog: String = "filtered module log"
    ) = DiagnosticReportEnvelope(
        reportId = "R1-00000000000000000000000000",
        createdAtUtc = "2026-08-01T00:00:00Z",
        category = HyperGlowReportCategory.COMPATIBILITY.wireValue,
        description = description,
        commonMetadata = DiagnosticCommonMetadata(
            appVersionName = "0.4.0",
            appVersionCode = 45,
            buildType = "debug",
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "Xiaomi 14",
            device = "houji",
            product = "houji_global",
            androidRelease = "16",
            androidApi = 36,
            androidSecurityPatch = "2026-07-01",
            androidDisplay = "BP2A.test",
            androidIncremental = "test",
            buildFingerprint = "xiaomi/houji/test:user/release-keys",
            xiaomiOsProperties = mapOf("ro.mi.os.version.name" to "OS3"),
            locales = listOf("en-US"),
            packageVersions = mapOf(
                "systemui" to DiagnosticPackageVersion(true, "16.0", 1L),
                "xiaomi_aod" to DiagnosticPackageVersion(true, "3.0", 2L)
            )
        ),
        productMetadata = HyperGlowProductMetadata(
            capabilityReportProtocol = 2,
            capabilityReportAgeMs = 100L,
            profileState = "unsupported_profile",
            rawSymbolProbes = mapOf("AOD_SURFACE_LIFECYCLE" to true),
            resolvedCapabilities = emptyList(),
            configuredSurfaces = mapOf("aod" to true, "lockscreen" to false),
            systemUiCallbackPresent = true,
            spotifyProducerStatePresent = true,
            spotifyProducerSafeStatus = "ready",
            spotifyProducerPlaying = true,
            spotifyProducerStateAgeMs = 50L,
            diagnosticLoggingAvailable = true,
            diagnosticLoggingEnabled = false,
            rootAccessStatus = "not_checked",
            currentMediaEvidence = DiagnosticMediaEvidence(
                present = true,
                trackUri = "spotify:track:test",
                title = "Test song",
                artist = "Test artist",
                album = "Test album",
                source = "hyperglow_bridge",
                provider = "Spicy Lyrics",
                language = "ja",
                timingType = "Syllable",
                lineIndex = 3,
                originalLine = "Current lyric",
                romanizedLine = "Current reading",
                translatedLine = "Current translation",
                stateAgeMs = 50L
            )
        ),
        capture = DiagnosticCaptureMetadata(
            outcome = "not_requested",
            startedAtUtc = null,
            finishedAtUtc = "2026-08-01T00:00:01Z",
            previousDiagnosticLoggingEnabled = null,
            rootAccessStatus = "not_checked",
            commandFailures = emptyList(),
            truncationFlags = emptyMap()
        ),
        rawDiagnostics = DiagnosticRawData(
            diagnosticEventsAndLogs = privateLog,
            crashExcerpt = "",
            lsposedModuleLines = "",
            runtimeSettings = mapOf("keepAodActive" to "true")
        )
    )
}
