package com.eza.hyperglow.diagnostics

import android.content.Context
import java.io.File

internal object DiagnosticDraftStore {
    private const val PREFS = "diagnostic_draft"
    private const val FILE_NAME = "diagnostic-report-pending.json"

    fun save(context: Context, report: DiagnosticReportEnvelope): Boolean {
        val encoded = DiagnosticReportCodec.encode(report)
        return try {
            File(context.cacheDir, FILE_NAME).writeText(encoded, Charsets.UTF_8)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_CREATED_AT, System.currentTimeMillis())
                .putString(KEY_REPORT_ID, report.reportId)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun load(context: Context): DiagnosticReportEnvelope? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val createdAt = prefs.getLong(KEY_CREATED_AT, -1L)
        val now = System.currentTimeMillis()
        if (createdAt < 0L || now < createdAt ||
            now - createdAt >= DiagnosticLimits.CAPTURE_TTL_MS
        ) {
            clear(context)
            return null
        }
        val report = try {
            val file = File(context.cacheDir, FILE_NAME)
            if (file.length() !in 1..DiagnosticLimits.CLIENT_BODY_BYTES.toLong()) {
                clear(context)
                return null
            }
            DiagnosticReportCodec.decodeOrNull(
                file.readText(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            null
        }
        if (report == null || report.reportId != prefs.getString(KEY_REPORT_ID, "")) {
            clear(context)
            return null
        }
        return report
    }

    fun clear(context: Context) {
        runCatching { File(context.cacheDir, FILE_NAME).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_REPORT_ID = "report_id"
}
