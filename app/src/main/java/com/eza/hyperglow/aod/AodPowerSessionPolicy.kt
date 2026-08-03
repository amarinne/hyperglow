package com.eza.hyperglow.aod

internal data class AodPowerSessionDecision(
    val keepAlive: Boolean,
    val presentationLeaseActive: Boolean
)

internal class AodPowerSessionPolicy(
    private val songChangeLeaseMs: Long = DEFAULT_SONG_CHANGE_LEASE_MS
) {
    private var session: ProjectionSessionIdentity? = null
    private var leaseUntilElapsedMs = 0L
    private var keepAliveStartedAtElapsedMs: Long? = null

    @Synchronized
    fun resolve(
        state: SpicyPowerSessionState,
        nowElapsedMs: Long,
        persistentKeepAlive: Boolean
    ): AodPowerSessionDecision {
        if (!state.playing || !state.aodEnabled || !state.keepAwake) {
            clear()
            return AodPowerSessionDecision(false, false)
        }
        if (session != state.session) {
            session = state.session
            leaseUntilElapsedMs = nowElapsedMs + songChangeLeaseMs.coerceAtLeast(0L)
        }
        val leaseActive = nowElapsedMs < leaseUntilElapsedMs
        val requested = persistentKeepAlive || leaseActive
        if (!requested) return AodPowerSessionDecision(false, false)
        val startedAt = keepAliveStartedAtElapsedMs ?: nowElapsedMs.also {
            keepAliveStartedAtElapsedMs = it
        }
        val durationMs = normalizeKeepAwakeDurationMs(state.keepAliveDurationMs)
        if (durationMs > 0L && nowElapsedMs - startedAt >= durationMs) {
            return AodPowerSessionDecision(false, false)
        }
        return AodPowerSessionDecision(
            keepAlive = true,
            presentationLeaseActive = leaseActive && !persistentKeepAlive
        )
    }

    @Synchronized
    fun clear() {
        session = null
        leaseUntilElapsedMs = 0L
        keepAliveStartedAtElapsedMs = null
    }

    companion object {
        const val DEFAULT_SONG_CHANGE_LEASE_MS = 8_000L
    }
}

internal data class SpicyPowerSessionState(
    val session: ProjectionSessionIdentity,
    val playing: Boolean,
    val aodEnabled: Boolean,
    val keepAwake: Boolean,
    val keepAliveDurationMs: Long = -1L
)
