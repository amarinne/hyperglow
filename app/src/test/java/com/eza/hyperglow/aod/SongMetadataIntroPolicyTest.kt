package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataIntroPolicyTest {
    @Test
    fun openingInterludeShowsTheFullIntroOnce() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = 9_000L)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 5_999L, nextStart = 9_000L)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 6_000L, nextStart = 9_000L)))
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 9_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
    }

    @Test
    fun openingInterludeEndingEarlyCutsTheIntroAndDoesNotOweAnother() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = 4_500L)))
        // The first lyric arrives before the five seconds are up. The intro is finished, not
        // interrupted, so a later interlude must not replay it.
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 4_500L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 20_000L, position = 20_000L, nextStart = 30_000L)
            )
        )
    }

    @Test
    fun songOpeningOnALyricShowsNothingUntilTheNextInterlude() {
        val policy = SongMetadataIntroPolicy()

        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 500L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 30_000L, position = 30_000L, nextStart = 40_000L)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 34_999L, position = 34_999L, nextStart = 40_000L)
            )
        )
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 35_000L, position = 35_000L, nextStart = 40_000L)
            )
        )
    }

    @Test
    fun unresolvedOpeningLeadsWithTheTitleRatherThanThePlaceholder() {
        val policy = SongMetadataIntroPolicy()

        // The document has not arrived at the song change. Withholding here left the placeholder
        // note leading instead of the song, so the intro starts and the opening decides its fate.
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 100L, lyricState = SongIntroLyricState.UNKNOWN)
            )
        )
        assertTrue(policy.shouldShowLargeMetadata(input(now = 900L, nextStart = 9_000L)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 5_099L, nextStart = 9_000L)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 5_100L, nextStart = 9_000L)))
    }

    @Test
    fun provisionalLeadCutByAnImmediateLyricStillOwesAFullIntro() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 100L, lyricState = SongIntroLyricState.UNKNOWN)
            )
        )
        // The document lands and the song was already singing. The lead yields the row at once.
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 700L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        // Nothing was ever confirmed as an interlude, so the real intro is still owed.
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 30_000L, position = 30_000L, nextStart = 40_000L)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 34_999L, position = 34_999L, nextStart = 40_000L)
            )
        )
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 35_000L, position = 35_000L, nextStart = 40_000L)
            )
        )
    }

    @Test
    fun confirmedInterludeCutByTheFirstLyricIsNotOwedAgain() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 100L, lyricState = SongIntroLyricState.UNKNOWN)
            )
        )
        // The opening is confirmed as a real interlude, so this intro counts as shown.
        assertTrue(policy.shouldShowLargeMetadata(input(now = 500L, nextStart = 4_000L)))
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 4_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 30_000L, position = 30_000L, nextStart = 40_000L)
            )
        )
    }

    @Test
    fun openingGapTooShortToReadIsTreatedAsOpeningOnALyric() {
        val policy = SongMetadataIntroPolicy()

        assertFalse(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = 2_500L)))
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 2_500L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 12_000L, position = 12_000L, nextStart = 20_000L)
            )
        )
    }

    @Test
    fun untimedSongShowsTheIntroImmediately() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 100L, lyricState = SongIntroLyricState.NONE)
            )
        )
    }

    @Test
    fun generationChangeAllowsNextSongIntro() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, nextStart = null)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 6_000L, nextStart = null)))
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 7_000L, nextStart = null, generation = 8)
            )
        )
    }

    @Test
    fun producerLineTextBeforeTheDocumentDoesNotEndTheLead() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 100L, lyricState = SongIntroLyricState.UNKNOWN, openingResolved = false)
            )
        )
        // The producer's line text still describes the moment before the change. Treating it as an
        // active lyric took the intro away exactly as the song change finalized.
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 600L, lyricState = SongIntroLyricState.ACTIVE, openingResolved = false)
            )
        )
        // The document lands and the song really does open on an interlude, so the lead holds.
        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_200L, nextStart = 12_000L)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 5_099L, nextStart = 12_000L)))
        assertFalse(policy.shouldShowLargeMetadata(input(now = 5_100L, nextStart = 12_000L)))
    }

    private fun input(
        now: Long,
        position: Long = now,
        nextStart: Long? = null,
        lyricState: SongIntroLyricState = SongIntroLyricState.INTERLUDE,
        generation: Int = 7,
        openingResolved: Boolean = true
    ) = SongMetadataIntroInput(
        session = ProjectionSessionIdentity("producer", generation, "spotify:track:$generation"),
        metadataAvailable = true,
        lyricState = lyricState,
        positionMs = position,
        nextLyricStartMs = nextStart,
        speed = 1f,
        nowElapsedMs = now,
        openingResolved = openingResolved
    )
}
