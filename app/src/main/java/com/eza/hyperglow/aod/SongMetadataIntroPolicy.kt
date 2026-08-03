package com.eza.hyperglow.aod

internal enum class SongIntroLyricState {
    UNKNOWN,
    ACTIVE,
    INTERLUDE,
    NONE
}

internal data class SongMetadataIntroInput(
    val session: ProjectionSessionIdentity,
    val metadataAvailable: Boolean,
    val lyricState: SongIntroLyricState,
    val positionMs: Long,
    val nextLyricStartMs: Long?,
    val speed: Float,
    val nowElapsedMs: Long
)

internal class SongMetadataIntroPolicy(
    private val durationMs: Long = DEFAULT_DURATION_MS
) {
    private enum class Phase { PENDING, SHOWING, DEFERRED, COMPLETE }

    private var session: ProjectionSessionIdentity? = null
    private var phase = Phase.PENDING
    private var startedAtElapsedMs = 0L

    @Synchronized
    fun shouldShowLargeMetadata(input: SongMetadataIntroInput): Boolean {
        if (session != input.session) {
            session = input.session
            phase = Phase.PENDING
            startedAtElapsedMs = 0L
        }
        if (!input.metadataAvailable || phase == Phase.COMPLETE) return false

        if (phase == Phase.SHOWING) {
            val elapsedMs = (input.nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
            if (elapsedMs >= durationMs) {
                phase = Phase.COMPLETE
                startedAtElapsedMs = 0L
                return false
            }
            val remainingMs = durationMs - elapsedMs
            if (!canContinue(input, remainingMs)) {
                phase = Phase.DEFERRED
                startedAtElapsedMs = 0L
                return false
            }
            return true
        }

        val canStart = when (phase) {
            Phase.PENDING -> canStartInitial(input)
            Phase.DEFERRED -> canStartDeferred(input)
            else -> false
        }
        if (!canStart) return false

        phase = Phase.SHOWING
        startedAtElapsedMs = input.nowElapsedMs
        return true
    }

    private fun canStartInitial(input: SongMetadataIntroInput): Boolean = when (input.lyricState) {
        SongIntroLyricState.UNKNOWN,
        SongIntroLyricState.NONE -> true
        SongIntroLyricState.ACTIVE -> {
            phase = Phase.DEFERRED
            false
        }
        SongIntroLyricState.INTERLUDE -> {
            val availableMs = availableInterludeMs(input)
            if (availableMs >= durationMs) true else {
                phase = Phase.DEFERRED
                false
            }
        }
    }

    private fun canStartDeferred(input: SongMetadataIntroInput): Boolean = when (input.lyricState) {
        SongIntroLyricState.NONE -> true
        SongIntroLyricState.INTERLUDE -> availableInterludeMs(input) >= durationMs
        SongIntroLyricState.UNKNOWN,
        SongIntroLyricState.ACTIVE -> false
    }

    private fun canContinue(input: SongMetadataIntroInput, remainingMs: Long): Boolean =
        when (input.lyricState) {
            SongIntroLyricState.ACTIVE -> false
            SongIntroLyricState.INTERLUDE -> availableInterludeMs(input) >= remainingMs
            SongIntroLyricState.UNKNOWN,
            SongIntroLyricState.NONE -> true
        }

    private fun availableInterludeMs(input: SongMetadataIntroInput): Long {
        val nextStartMs = input.nextLyricStartMs ?: return Long.MAX_VALUE
        val playbackGapMs = (nextStartMs - input.positionMs).coerceAtLeast(0L)
        if (input.speed <= 0f || !input.speed.isFinite()) return Long.MAX_VALUE
        return (playbackGapMs / input.speed).toLong()
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 3_000L
    }
}
