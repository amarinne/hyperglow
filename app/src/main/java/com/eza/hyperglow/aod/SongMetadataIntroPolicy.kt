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
    val nowElapsedMs: Long,
    /**
     * Whether the timed document for this song has arrived. Until it has, an apparently active
     * lyric is only the producer's line text, which at a song change still describes the moment
     * before the intro — not evidence that this song opens on singing.
     */
    val openingResolved: Boolean = true
)

/**
 * Decides whether the song-change title/artist occupies the lyric row.
 *
 * Two rules, both about not interrupting lyrics and not flashing:
 *
 * - A song that opens on an interlude shows the intro for [durationMs], or until the interlude ends,
 *   whichever comes first. The intro is then done for that song either way.
 * - A song that opens straight into a lyric shows nothing, and waits for the next interlude long
 *   enough to be worth it.
 *
 * The opening is not known the instant a song changes: metadata arrives before the timed document.
 * The intro still leads there, because the alternative is leading with the placeholder note while
 * waiting, but that start is *provisional*. When the document lands it either confirms an interlude,
 * and the intro runs its course, or it reveals a lyric already under way, and the intro yields the
 * row immediately and is still owed in full at the next interlude. So a song that opens on singing
 * shows the title only for as long as nothing was known, and gets its real intro later.
 */
internal class SongMetadataIntroPolicy(
    private val durationMs: Long = DEFAULT_DURATION_MS,
    private val minimumInterludeMs: Long = DEFAULT_MINIMUM_INTERLUDE_MS
) {
    private enum class Phase { PENDING, SHOWING, DEFERRED, COMPLETE }

    private var session: ProjectionSessionIdentity? = null
    private var phase = Phase.PENDING
    private var startedAtElapsedMs = 0L
    private var provisional = false

    @Synchronized
    fun shouldShowLargeMetadata(rawInput: SongMetadataIntroInput): Boolean {
        // Only the timed document can say a song opens on singing. Before it lands, the producer's
        // line text still belongs to the moment before the change, so an active-looking opening is
        // really an unknown one. Reading it as active is what pulled the intro off the screen just
        // as the song change finished, during the very interlude it was supposed to occupy.
        val input = if (!rawInput.openingResolved &&
            rawInput.lyricState == SongIntroLyricState.ACTIVE
        ) {
            rawInput.copy(lyricState = SongIntroLyricState.UNKNOWN)
        } else {
            rawInput
        }
        if (session != input.session) {
            session = input.session
            phase = Phase.PENDING
            startedAtElapsedMs = 0L
            provisional = false
        }
        if (!input.metadataAvailable || phase == Phase.COMPLETE) return false

        if (phase == Phase.SHOWING) {
            // Only an opening that turns out to be an interlude confirms the lead. A lyric does not
            // confirm it — that is the case the provisional flag exists to catch.
            if (provisional &&
                (input.lyricState == SongIntroLyricState.INTERLUDE ||
                    input.lyricState == SongIntroLyricState.NONE)
            ) {
                provisional = false
            }
            val elapsedMs = (input.nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
            if (elapsedMs >= durationMs) {
                phase = Phase.COMPLETE
                startedAtElapsedMs = 0L
                return false
            }
            if (!canContinue(input)) {
                // A lyric wants the row. If the interlude had been confirmed, the intro simply ran
                // its course. If it had not, nothing was ever known to be an interlude, so this was
                // a lead-in over an unknown opening and a full intro is still owed at the next gap.
                phase = if (provisional) Phase.DEFERRED else Phase.COMPLETE
                startedAtElapsedMs = 0L
                provisional = false
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
        provisional = input.lyricState == SongIntroLyricState.UNKNOWN
        return true
    }

    private fun canStartInitial(input: SongMetadataIntroInput): Boolean = when (input.lyricState) {
        // Lead with the song title rather than the placeholder note. The document has not arrived,
        // so this start is provisional and may still owe a full intro once the opening is known.
        SongIntroLyricState.UNKNOWN -> true
        SongIntroLyricState.NONE -> true
        SongIntroLyricState.ACTIVE -> {
            phase = Phase.DEFERRED
            false
        }
        SongIntroLyricState.INTERLUDE -> {
            // An opening gap too short to be read is the same thing as opening on a lyric.
            if (availableInterludeMs(input) >= minimumInterludeMs) true else {
                phase = Phase.DEFERRED
                false
            }
        }
    }

    private fun canStartDeferred(input: SongMetadataIntroInput): Boolean = when (input.lyricState) {
        SongIntroLyricState.NONE -> true
        SongIntroLyricState.INTERLUDE -> availableInterludeMs(input) >= minimumInterludeMs
        SongIntroLyricState.UNKNOWN,
        SongIntroLyricState.ACTIVE -> false
    }

    /**
     * The intro ends when the interlude does. Only a lyric taking the row ends it early; a shrinking
     * gap does not, because the display is capped by [durationMs] rather than by the gap length.
     */
    private fun canContinue(input: SongMetadataIntroInput): Boolean =
        when (input.lyricState) {
            SongIntroLyricState.ACTIVE -> false
            SongIntroLyricState.INTERLUDE,
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
        const val DEFAULT_DURATION_MS = 5_000L
        const val DEFAULT_MINIMUM_INTERLUDE_MS = 3_000L
    }
}
