package com.eza.hyperglow.aod

import com.eza.hyperglow.bridge.SpicyBridgeDocument

/** One elapsed-time window per song outro; document and heartbeat refreshes do not renew it. */
internal class ResponseCreditOutroPolicy {
    private var session: ProjectionSessionIdentity? = null
    private var startedAtElapsedMs = -1L

    @Synchronized
    fun shouldShow(
        session: ProjectionSessionIdentity,
        document: SpicyBridgeDocument?,
        positionMs: Long,
        nowElapsedMs: Long,
        eligible: Boolean
    ): Boolean {
        // Missing documents are transport gaps, not new outro episodes.
        if (document == null) return false
        if (this.session != session) {
            this.session = session
            startedAtElapsedMs = -1L
        }
        var lastVocalEndMs = 0L
        for (row in document.rows) {
            if (row.role == "LEAD" && row.text.isNotBlank()) {
                lastVocalEndMs = maxOf(lastVocalEndMs, minOf(row.fillEndMs, row.endMs))
            }
        }
        if (positionMs < lastVocalEndMs + OUTRO_HOLD_MS) startedAtElapsedMs = -1L
        if (!eligible || !AodProjectionEngine.isTimedDocumentType(document.type) ||
            lastVocalEndMs <= 0L || positionMs < lastVocalEndMs + OUTRO_HOLD_MS ||
            document.responseCredit.isBlank() ||
            document.responseCredit.length > AodStateWireLimits.MAX_LYRIC_CHARS
        ) return false
        if (startedAtElapsedMs < 0L) startedAtElapsedMs = nowElapsedMs
        return nowElapsedMs >= startedAtElapsedMs &&
            nowElapsedMs - startedAtElapsedMs < OUTRO_VISIBLE_MS
    }

    companion object {
        const val OUTRO_HOLD_MS = 700L
        const val OUTRO_VISIBLE_MS = 10_000L
    }
}
