package com.eza.hyperglow.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

@Serializable
internal data class DiagnosticReportReceipt(
    val reportId: String,
    val receivedAtUtc: String,
    val rawExpiresAtUtc: String?,
    val retentionPolicy: String
)

internal sealed interface DiagnosticUploadResult {
    data class Success(
        val receipt: DiagnosticReportReceipt,
        val idempotentRetry: Boolean
    ) : DiagnosticUploadResult

    data class Failure(val kind: Kind) : DiagnosticUploadResult {
        enum class Kind {
            INVALID_REPORT,
            REPORT_ID_COLLISION,
            REQUEST_TOO_LARGE,
            RATE_LIMITED,
            STORAGE_UNAVAILABLE,
            SERVER_ERROR,
            REDIRECT_REJECTED,
            TIMEOUT,
            NETWORK,
            INVALID_RESPONSE
        }
    }
}

internal object DiagnosticUploadResponseMapper {
    private val json = Json { ignoreUnknownKeys = false }

    fun map(
        statusCode: Int,
        body: String,
        expectedReportId: String
    ): DiagnosticUploadResult = when (statusCode) {
        HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_OK -> {
            val receipt = runCatching {
                json.decodeFromString<DiagnosticReportReceipt>(body)
            }.getOrNull()
            if (receipt != null && receipt.reportId == expectedReportId &&
                isValidDiagnosticReportId(receipt.reportId) &&
                receipt.receivedAtUtc.length in 1..64 &&
                receipt.rawExpiresAtUtc == null &&
                receipt.retentionPolicy == RETENTION_POLICY_INDEFINITE
            ) {
                DiagnosticUploadResult.Success(
                    receipt = receipt,
                    idempotentRetry = statusCode == HttpURLConnection.HTTP_OK
                )
            } else {
                DiagnosticUploadResult.Failure(
                    DiagnosticUploadResult.Failure.Kind.INVALID_RESPONSE
                )
            }
        }
        HttpURLConnection.HTTP_BAD_REQUEST -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.INVALID_REPORT
        )
        HttpURLConnection.HTTP_CONFLICT -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.REPORT_ID_COLLISION
        )
        HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.REQUEST_TOO_LARGE
        )
        429 -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.RATE_LIMITED
        )
        HttpURLConnection.HTTP_UNAVAILABLE -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.STORAGE_UNAVAILABLE
        )
        in 300..399 -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.REDIRECT_REJECTED
        )
        in 500..599 -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.SERVER_ERROR
        )
        else -> DiagnosticUploadResult.Failure(
            DiagnosticUploadResult.Failure.Kind.INVALID_RESPONSE
        )
    }
}

private const val RETENTION_POLICY_INDEFINITE = "indefinite"

internal fun mapDiagnosticUploadException(error: Exception): DiagnosticUploadResult.Failure =
    DiagnosticUploadResult.Failure(
        if (error is SocketTimeoutException) {
            DiagnosticUploadResult.Failure.Kind.TIMEOUT
        } else {
            DiagnosticUploadResult.Failure.Kind.NETWORK
        }
    )

internal class DiagnosticUploader(private val endpoint: String) {
    suspend fun upload(report: DiagnosticReportEnvelope): DiagnosticUploadResult =
        withContext(Dispatchers.IO) {
            val body = try {
                DiagnosticReportCodec.encode(report)
            } catch (_: IllegalArgumentException) {
                return@withContext DiagnosticUploadResult.Failure(
                    DiagnosticUploadResult.Failure.Kind.INVALID_REPORT
                )
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (bytes.size > DiagnosticLimits.CLIENT_BODY_BYTES) {
                return@withContext DiagnosticUploadResult.Failure(
                    DiagnosticUploadResult.Failure.Kind.REQUEST_TOO_LARGE
                )
            }
            val url = try {
                URL(endpoint)
            } catch (_: Exception) {
                return@withContext DiagnosticUploadResult.Failure(
                    DiagnosticUploadResult.Failure.Kind.INVALID_REPORT
                )
            }
            if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null ||
                url.query != null || url.ref != null || url.path != REPORT_PATH
            ) {
                return@withContext DiagnosticUploadResult.Failure(
                    DiagnosticUploadResult.Failure.Kind.INVALID_REPORT
                )
            }
            executeWithDeadline(url, bytes, report.reportId)
        }

    private fun executeWithDeadline(
        url: URL,
        bytes: ByteArray,
        reportId: String
    ): DiagnosticUploadResult {
        val connectionRef = AtomicReference<HttpsURLConnection?>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HyperGlowDiagnosticUpload").apply { isDaemon = true }
        }
        val future = executor.submit<DiagnosticUploadResult> {
            executeRequest(url, bytes, reportId, connectionRef)
        }
        return try {
            future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            connectionRef.get()?.disconnect()
            future.cancel(true)
            DiagnosticUploadResult.Failure(DiagnosticUploadResult.Failure.Kind.TIMEOUT)
        } catch (_: Exception) {
            DiagnosticUploadResult.Failure(DiagnosticUploadResult.Failure.Kind.NETWORK)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun executeRequest(
        url: URL,
        bytes: ByteArray,
        reportId: String,
        connectionRef: AtomicReference<HttpsURLConnection?>
    ): DiagnosticUploadResult {
        var connection: HttpsURLConnection? = null
        try {
            connection = url.openConnection() as? HttpsURLConnection
                ?: return DiagnosticUploadResult.Failure(
                    DiagnosticUploadResult.Failure.Kind.INVALID_REPORT
                )
            connectionRef.set(connection)
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val responseBody = readResponseBody(connection, status)
            return DiagnosticUploadResponseMapper.map(status, responseBody, reportId)
        } catch (error: SocketTimeoutException) {
            return mapDiagnosticUploadException(error)
        } catch (error: IOException) {
            return mapDiagnosticUploadException(error)
        } catch (error: Exception) {
            return mapDiagnosticUploadException(error)
        } finally {
            connectionRef.compareAndSet(connection, null)
            connection?.disconnect()
        }
    }

    private fun readResponseBody(connection: HttpsURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: return ""
        return stream.use { input ->
            val buffer = ByteArray(RESPONSE_LIMIT_BYTES + 1)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset > RESPONSE_LIMIT_BYTES) return ""
            buffer.copyOf(offset).toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val REPORT_PATH = "/v1/reports"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val RESPONSE_LIMIT_BYTES = 32 * 1024
    }
}
