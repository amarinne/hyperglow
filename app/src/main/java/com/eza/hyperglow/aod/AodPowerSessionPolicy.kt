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
        return AodPowerSessionDecision(
            keepAlive = persistentKeepAlive || leaseActive,
            presentationLeaseActive = leaseActive && !persistentKeepAlive
        )
    }

    @Synchronized
    fun clear() {
        session = null
        leaseUntilElapsedMs = 0L
    }

    companion object {
        const val DEFAULT_SONG_CHANGE_LEASE_MS = 8_000L
    }
}

internal data class SpicyPowerSessionState(
    val session: ProjectionSessionIdentity,
    val playing: Boolean,
    val aodEnabled: Boolean,
    val keepAwake: Boolean
)
