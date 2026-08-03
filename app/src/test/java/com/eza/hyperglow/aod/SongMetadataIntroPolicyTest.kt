package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataIntroPolicyTest {
    @Test
    fun longOpeningInterludeShowsThreeSecondIntroOnce() {
        val policy = SongMetadataIntroPolicy(durationMs = 3_000L)

        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = 5_000L)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 3_999L, nextStart = 5_000L)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 4_000L, nextStart = 5_000L)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 5_000L, lyricState = SongIntroLyricState.ACTIVE)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 10_000L, nextStart = null)))
    }

    @Test
    fun shortOpeningInterludeDefersFullIntroUntilLaterGap() {
        val policy = SongMetadataIntroPolicy(durationMs = 3_000L)

        assertFalse(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = 3_500L)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 3_500L, lyricState = SongIntroLyricState.ACTIVE)))
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 6_000L, position = 6_000L, nextStart = 10_000L)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 8_999L, position = 8_999L, nextStart = 10_000L)
            )
        )
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 9_000L, position = 9_000L, nextStart = 10_000L)
            )
        )
    }

    @Test
    fun unknownOpeningCanAbortWithoutConsumingDeferredIntro() {
        val policy = SongMetadataIntroPolicy(durationMs = 3_000L)

        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, lyricState = SongIntroLyricState.UNKNOWN)
            )
        )
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 2_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 5_000L, position = 5_000L, nextStart = 9_000L)
            )
        )
    }

    @Test
    fun generationChangeAllowsNextSongIntro() {
        val policy = SongMetadataIntroPolicy(durationMs = 3_000L)

        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = null)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 4_000L, nextStart = null)))
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 5_000L, nextStart = null, generation = 8)
            )
        )
    }

    private fun input(
        now: Long,
        position: Long = now,
        nextStart: Long? = null,
        lyricState: SongIntroLyricState = SongIntroLyricState.INTERLUDE,
        generation: Int = 7
    ) = SongMetadataIntroInput(
        session = ProjectionSessionIdentity("producer", generation, "spotify:track:$generation"),
        metadataAvailable = true,
        lyricState = lyricState,
        positionMs = position,
        nextLyricStartMs = nextStart,
        speed = 1f,
        nowElapsedMs = now
    )
}
