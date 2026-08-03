package com.eza.hyperglow.diagnostics

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploaderTest {
    private val reportId = "R1-00000000000000000000000000"

    @Test
    fun successfulReceiptRequiresMatchingReportId() {
        val success = DiagnosticUploadResponseMapper.map(
            201,
            """{"reportId":"$reportId","receivedAtUtc":"2026-08-01T00:00:00Z","rawExpiresAtUtc":null,"retentionPolicy":"indefinite"}""",
            reportId
        )
        val invalid = DiagnosticUploadResponseMapper.map(
            201,
            """{"reportId":"R1-11111111111111111111111111","receivedAtUtc":"x","rawExpiresAtUtc":null,"retentionPolicy":"indefinite"}""",
            reportId
        )

        assertTrue(success is DiagnosticUploadResult.Success)
        assertEquals(false, (success as DiagnosticUploadResult.Success).idempotentRetry)
        assertEquals(
            DiagnosticUploadResult.Failure.Kind.INVALID_RESPONSE,
            (invalid as DiagnosticUploadResult.Failure).kind
        )
    }

    @Test
    fun identicalRetryAndTimeoutRemainExplicit() {
        val retry = DiagnosticUploadResponseMapper.map(
            200,
            """{"reportId":"$reportId","receivedAtUtc":"2026-08-01T00:00:00Z","rawExpiresAtUtc":null,"retentionPolicy":"indefinite"}""",
            reportId
        ) as DiagnosticUploadResult.Success

        assertTrue(retry.idempotentRetry)
        assertEquals(
            DiagnosticUploadResult.Failure.Kind.TIMEOUT,
            mapDiagnosticUploadException(SocketTimeoutException()).kind
        )
    }

    @Test
    fun expiringOrUnknownRetentionReceiptIsRejected() {
        val expiring = DiagnosticUploadResponseMapper.map(
            201,
            """{"reportId":"$reportId","receivedAtUtc":"2026-08-01T00:00:00Z","rawExpiresAtUtc":"2026-08-31T00:00:00Z","retentionPolicy":"indefinite"}""",
            reportId
        )
        val unknown = DiagnosticUploadResponseMapper.map(
            201,
            """{"reportId":"$reportId","receivedAtUtc":"2026-08-01T00:00:00Z","rawExpiresAtUtc":null,"retentionPolicy":"unknown"}""",
            reportId
        )

        assertEquals(
            DiagnosticUploadResult.Failure.Kind.INVALID_RESPONSE,
            (expiring as DiagnosticUploadResult.Failure).kind
        )
        assertEquals(
            DiagnosticUploadResult.Failure.Kind.INVALID_RESPONSE,
            (unknown as DiagnosticUploadResult.Failure).kind
        )
    }

    @Test
    fun responseCodesMapWithoutAutomaticRetry() {
        val expected = mapOf(
            400 to DiagnosticUploadResult.Failure.Kind.INVALID_REPORT,
            409 to DiagnosticUploadResult.Failure.Kind.REPORT_ID_COLLISION,
            413 to DiagnosticUploadResult.Failure.Kind.REQUEST_TOO_LARGE,
            429 to DiagnosticUploadResult.Failure.Kind.RATE_LIMITED,
            503 to DiagnosticUploadResult.Failure.Kind.STORAGE_UNAVAILABLE,
            500 to DiagnosticUploadResult.Failure.Kind.SERVER_ERROR,
            307 to DiagnosticUploadResult.Failure.Kind.REDIRECT_REJECTED
        )

        expected.forEach { (status, kind) ->
            val result = DiagnosticUploadResponseMapper.map(status, "", reportId)
            assertEquals(kind, (result as DiagnosticUploadResult.Failure).kind)
        }
    }

}
