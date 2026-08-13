package com.eza.hyperglow.diagnostics

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.setDiagnosticLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DiagnosticCaptureSession(
    val reportId: String,
    val category: HyperGlowReportCategory,
    val description: String,
    val startedAtUtcMillis: Long,
    val startedAtElapsedMillis: Long,
    val previousDiagnosticLogging: Boolean
)

internal data class FinishedDiagnosticCapture(
    val session: DiagnosticCaptureSession,
    val finishedAtUtcMillis: Long,
    val data: CapturedDiagnosticData
)

internal enum class DiagnosticCaptureLifecycleEvent {
    START,
    FINISH,
    CANCEL,
    TIMEOUT,
    SUCCESSFUL_UPLOAD
}

internal data class DiagnosticCaptureLifecycleAction(
    val diagnosticLoggingEnabled: Boolean,
    val deleteActiveCaptureState: Boolean,
    val deletePendingDraft: Boolean
)

internal fun resolveDiagnosticCaptureLifecycleAction(
    event: DiagnosticCaptureLifecycleEvent,
    previousDiagnosticLogging: Boolean
): DiagnosticCaptureLifecycleAction = when (event) {
    DiagnosticCaptureLifecycleEvent.START -> DiagnosticCaptureLifecycleAction(
        diagnosticLoggingEnabled = true,
        deleteActiveCaptureState = false,
        deletePendingDraft = true
    )
    DiagnosticCaptureLifecycleEvent.FINISH -> DiagnosticCaptureLifecycleAction(
        diagnosticLoggingEnabled = previousDiagnosticLogging,
        deleteActiveCaptureState = true,
        deletePendingDraft = false
    )
    DiagnosticCaptureLifecycleEvent.CANCEL,
    DiagnosticCaptureLifecycleEvent.TIMEOUT -> DiagnosticCaptureLifecycleAction(
        diagnosticLoggingEnabled = previousDiagnosticLogging,
        deleteActiveCaptureState = true,
        deletePendingDraft = true
    )
    DiagnosticCaptureLifecycleEvent.SUCCESSFUL_UPLOAD -> DiagnosticCaptureLifecycleAction(
        diagnosticLoggingEnabled = previousDiagnosticLogging,
        deleteActiveCaptureState = true,
        deletePendingDraft = true
    )
}

internal fun isDiagnosticCaptureExpired(startedAtElapsedMillis: Long, nowElapsedMillis: Long): Boolean =
    startedAtElapsedMillis < 0L || nowElapsedMillis < startedAtElapsedMillis ||
        nowElapsedMillis - startedAtElapsedMillis >= DiagnosticLimits.CAPTURE_TTL_MS

internal object DiagnosticCaptureManager {
    private const val PREFS = "diagnostic_capture"

    fun activeSession(context: Context): DiagnosticCaptureSession? {
        expireIfNeeded(context)
        return readSession(context)
    }

    fun start(
        context: Context,
        category: HyperGlowReportCategory,
        description: String
    ): DiagnosticCaptureSession? {
        require(description.isNotBlank() && description.utf8Size() <= DiagnosticLimits.DESCRIPTION_BYTES)
        if (!cancel(context)) return null
        val previousDiagnosticLogging = DiagnosticLoggingPreferences.read(context)
        val startAction = resolveDiagnosticCaptureLifecycleAction(
            DiagnosticCaptureLifecycleEvent.START,
            previousDiagnosticLogging = previousDiagnosticLogging
        )
        if (startAction.deletePendingDraft) DiagnosticDraftStore.clear(context)
        val session = DiagnosticCaptureSession(
            reportId = DiagnosticReportId.generate(),
            category = category,
            description = description,
            startedAtUtcMillis = System.currentTimeMillis(),
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
            previousDiagnosticLogging = previousDiagnosticLogging
        )
        if (!writeSession(context, session)) return null
        if (!setDiagnosticLogging(context, startAction.diagnosticLoggingEnabled)) {
            clearSession(context)
            return null
        }
        scheduleTimeout(context, session.startedAtElapsedMillis)
        return session
    }

    suspend fun finish(context: Context): FinishedDiagnosticCapture? {
        val session = activeSession(context) ?: return null
        var restored = false
        val data = try {
            withContext(Dispatchers.IO) {
                DiagnosticCaptureCollector(DiagnosticRootProcessRunner)
                    .collect(session.startedAtUtcMillis)
            }
        } finally {
            restored = endSession(context, session, DiagnosticCaptureLifecycleEvent.FINISH)
        }
        if (!restored) return null
        return FinishedDiagnosticCapture(
            session = session,
            finishedAtUtcMillis = System.currentTimeMillis(),
            data = data
        )
    }

    fun cancel(context: Context): Boolean {
        val session = readSession(context)
        if (session != null) {
            return endSession(context, session, DiagnosticCaptureLifecycleEvent.CANCEL)
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            if (setDiagnosticLogging(context, prefs.getBoolean(KEY_PREVIOUS_LOGGING, false))) {
                clearSession(context)
                DiagnosticDraftStore.clear(context)
            } else {
                return false
            }
        }
        cancelTimeout(context)
        return true
    }

    fun expireIfNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false
        val session = readSession(context)
        if (session == null) {
            if (!setDiagnosticLogging(context, prefs.getBoolean(KEY_PREVIOUS_LOGGING, false))) {
                return false
            }
            clearSession(context)
            cancelTimeout(context)
            DiagnosticDraftStore.clear(context)
            return true
        }
        if (!isDiagnosticCaptureExpired(
                session.startedAtElapsedMillis,
                SystemClock.elapsedRealtime()
            )
        ) return false
        if (!endSession(context, session, DiagnosticCaptureLifecycleEvent.TIMEOUT)) return false
        return true
    }

    private fun endSession(
        context: Context,
        session: DiagnosticCaptureSession,
        event: DiagnosticCaptureLifecycleEvent
    ): Boolean {
        val action = resolveDiagnosticCaptureLifecycleAction(
            event,
            session.previousDiagnosticLogging
        )
        if (!setDiagnosticLogging(context, action.diagnosticLoggingEnabled)) return false
        if (action.deleteActiveCaptureState) clearSession(context)
        if (action.deletePendingDraft) DiagnosticDraftStore.clear(context)
        cancelTimeout(context)
        return true
    }

    private fun readSession(context: Context): DiagnosticCaptureSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val category = HyperGlowReportCategory.fromWireValue(
            prefs.getString(KEY_CATEGORY, "").orEmpty()
        ) ?: return null
        val reportId = prefs.getString(KEY_REPORT_ID, "").orEmpty()
        val description = prefs.getString(KEY_DESCRIPTION, "").orEmpty()
        if (!isValidDiagnosticReportId(reportId) ||
            description.isBlank() || description.utf8Size() > DiagnosticLimits.DESCRIPTION_BYTES
        ) return null
        return DiagnosticCaptureSession(
            reportId = reportId,
            category = category,
            description = description,
            startedAtUtcMillis = prefs.getLong(KEY_STARTED_WALL, -1L),
            startedAtElapsedMillis = prefs.getLong(KEY_STARTED_ELAPSED, -1L),
            previousDiagnosticLogging = prefs.getBoolean(KEY_PREVIOUS_LOGGING, false)
        )
    }

    private fun writeSession(context: Context, session: DiagnosticCaptureSession): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_REPORT_ID, session.reportId)
            .putString(KEY_CATEGORY, session.category.wireValue)
            .putString(KEY_DESCRIPTION, session.description)
            .putLong(KEY_STARTED_WALL, session.startedAtUtcMillis)
            .putLong(KEY_STARTED_ELAPSED, session.startedAtElapsedMillis)
            .putBoolean(KEY_PREVIOUS_LOGGING, session.previousDiagnosticLogging)
            .commit()

    private fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun scheduleTimeout(context: Context, startedAtElapsedMillis: Long) {
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            startedAtElapsedMillis + DiagnosticLimits.CAPTURE_TTL_MS,
            timeoutIntent(context)
        )
    }

    private fun cancelTimeout(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(timeoutIntent(context))
    }

    private fun timeoutIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DiagnosticCaptureTimeoutReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private const val KEY_ACTIVE = "active"
    private const val KEY_REPORT_ID = "report_id"
    private const val KEY_CATEGORY = "category"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_STARTED_WALL = "started_wall"
    private const val KEY_STARTED_ELAPSED = "started_elapsed"
    private const val KEY_PREVIOUS_LOGGING = "previous_logging"
}

class DiagnosticCaptureTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DiagnosticCaptureManager.expireIfNeeded(context)
    }
}
