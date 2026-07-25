package com.eza.hyperglow.aod

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodStateWireCodecTest {
    @Test
    fun validSnapshotAndKeepAliveRoundTripWithoutAndroidBundle() {
        val snapshot = snapshotMessage(
            value = snapshotValue(
                original = "line",
                romanized = "romanized",
                translated = "translated",
                metadata = "track · artist",
                words = listOf(AodStateWireWord("li", "ri", 10L, 20L, true, 0, 2)),
                ruby = listOf(AodStateWireRuby(0, 2, "reading")),
                layoutGroups = listOf(AodStateWireLayoutGroup(0, 4, "phrase", true, 0.75))
            )
        )
        val snapshotEnvelope = AodStateWireCodec.encode(snapshot)

        assertEquals(snapshot, snapshotEnvelope?.let(AodStateWireCodec::decode))

        val keepAlive = AodStateWireMessage.KeepAlive(
            revision = 7L,
            userId = 10,
            updatedAtElapsedMs = 900L,
            keepAlive = true,
            wakeSignal = 44L,
            playbackActive = true
        )
        val keepAliveEnvelope = AodStateWireCodec.encode(keepAlive)
            ?.copy(body = byteArrayOf(1, 2, 3))

        assertEquals(keepAlive, keepAliveEnvelope?.let(AodStateWireCodec::decode))
    }

    @Test
    fun exactCollectionLimitsRoundTripAndOverLimitsFailClosedBeforeMapping() {
        val exact = snapshotMessage(
            value = snapshotValue(
                original = "x".repeat(500),
                words = List(AodStateWireLimits.MAX_WORDS) { index ->
                    AodStateWireWord("w", "r", index.toLong(), index + 1L, false, index, index + 1)
                },
                ruby = List(AodStateWireLimits.MAX_RUBY) { index ->
                    AodStateWireRuby(index, index + 1, "r")
                },
                layoutGroups = List(AodStateWireLimits.MAX_LAYOUT_GROUPS) { index ->
                    val start = index % 499
                    AodStateWireLayoutGroup(start, start + 1, "word", true, 0.5)
                }
            )
        )
        val exactEnvelope = AodStateWireCodec.encode(exact)

        assertEquals(exact, exactEnvelope?.let(AodStateWireCodec::decode))
        assertNull(
            AodStateWireCodec.encode(
                exact.copy(value = exact.value.copy(words = exact.value.words + exact.value.words.first()))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                exact.copy(value = exact.value.copy(ruby = exact.value.ruby + exact.value.ruby.first()))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                exact.copy(
                    value = exact.value.copy(
                        layoutGroups = exact.value.layoutGroups + exact.value.layoutGroups.first()
                    )
                )
            )
        )

        val body = requireNotNull(exactEnvelope?.body)
        for ((offset, limit) in listOf(
            8 to AodStateWireLimits.MAX_WORDS,
            12 to AodStateWireLimits.MAX_RUBY,
            16 to AodStateWireLimits.MAX_LAYOUT_GROUPS
        )) {
            val malformed = body.copyOf()
            ByteBuffer.wrap(malformed).putInt(offset, limit + 1)
            assertNull(AodStateWireCodec.decode(exactEnvelope.copy(body = malformed)))
        }
    }

    @Test
    fun aggregateAsciiAndMultibyteOverflowFailClosed() {
        val asciiOverflow = snapshotMessage(
            value = snapshotValue(
                words = List(100) {
                    AodStateWireWord("x".repeat(500), "", 0L, 1L, false, -1, -1)
                }
            )
        )
        val multibyteOverflow = snapshotMessage(
            value = snapshotValue(
                words = List(34) {
                    AodStateWireWord("界".repeat(500), "", 0L, 1L, false, -1, -1)
                }
            )
        )

        assertNull(AodStateWireCodec.encode(asciiOverflow))
        assertNull(AodStateWireCodec.encode(multibyteOverflow))
    }

    @Test
    fun bodyCeilingTruncationUnknownVersionAndUnknownKindFailClosed() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val body = requireNotNull(envelope.body)

        assertNull(
            AodStateWireCodec.decode(
                envelope.copy(body = ByteArray(AodStateWireLimits.MAX_ENCODED_BODY_BYTES + 1))
            )
        )
        assertNull(AodStateWireCodec.decode(envelope.copy(body = body.copyOf(body.size - 1))))
        val unknownBodyVersion = body.copyOf()
        ByteBuffer.wrap(unknownBodyVersion).putInt(4, 99)
        assertNull(AodStateWireCodec.decode(envelope.copy(body = unknownBodyVersion)))
        assertNull(
            AodStateWireCodec.decode(
                envelope.copy(protocol = AodStateWireContract.PROTOCOL_VERSION + 1)
            )
        )
        assertNull(AodStateWireCodec.decode(envelope.copy(kind = 99)))
    }

    @Test
    fun malformedStringsNonfiniteValuesAndInvalidRangesFailClosed() {
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(speed = Float.NaN))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        original = "line",
                        ruby = listOf(AodStateWireRuby(0, 9, "reading"))
                    )
                )
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        weight = "x".repeat(AodStateWireLimits.MAX_STYLE_CHARS + 1)
                    )
                )
            )
        )

        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val body = requireNotNull(envelope.body).copyOf()
        body[28] = 2
        assertNull(AodStateWireCodec.decode(envelope.copy(body = body)))
    }

    @Test
    fun unsupportedStylesAndUnboundedPlaybackValuesFailClosed() {
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue().copy(lineSyncFillMode = "Diagonal"))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue().copy(transitionMode = "Slide"))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue().copy(durationMs = 0L))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue().copy(
                        durationMs = AodStateWireLimits.MAX_MEDIA_DURATION_MS + 1L
                    )
                )
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(speed = AodStateWireLimits.MAX_PLAYBACK_SPEED + 0.1f)
                )
            )
        )
    }

    @Test
    fun malformedUtf16FailsClosedInsteadOfReplacingCharacters() {
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(original = "line\uD83D"))
            )
        )
    }

    @Test
    fun senderAndReceiverUseSameLimitsContract() {
        val exactText = "x".repeat(AodStateWireLimits.MAX_LYRIC_CHARS)
        val exact = snapshotMessage(value = snapshotValue(original = exactText))
        val envelope = requireNotNull(AodStateWireCodec.encode(exact))

        assertEquals(exact, AodStateWireCodec.decode(envelope))
        assertTrue(requireNotNull(envelope.body).size <= AodStateWireLimits.MAX_ENCODED_BODY_BYTES)
        assertEquals(48 * 1024, AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES)
        assertEquals(64 * 1024, AodStateWireLimits.MAX_ENCODED_BODY_BYTES)
    }

    @Test
    fun encodedEnvelopeOwnsBodyBytes() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val first = requireNotNull(envelope.body)
        val second = requireNotNull(requireNotNull(AodStateWireCodec.encode(snapshotMessage())).body)

        assertFalse(first === second)
        assertArrayEquals(first, second)
    }

    private fun snapshotMessage(
        value: AodStateWireSnapshot = snapshotValue()
    ) = AodStateWireMessage.Snapshot(
        revision = 7L,
        userId = 10,
        updatedAtElapsedMs = 800L,
        keepAlive = true,
        wakeSignal = 33L,
        playbackActive = true,
        value = value
    )

    private fun snapshotValue(
        original: String = "line",
        romanized: String = "",
        translated: String = "",
        metadata: String = "track",
        speed: Float = 1f,
        words: List<AodStateWireWord> = emptyList(),
        ruby: List<AodStateWireRuby> = emptyList(),
        layoutGroups: List<AodStateWireLayoutGroup> = emptyList(),
        weight: String = "Medium"
    ) = AodStateWireSnapshot(
        trackGeneration = 12L,
        aodEnabled = true,
        lockscreenEnabled = true,
        seamlessTransitionEnabled = true,
        positionFollowingEnabled = true,
        burnInPattern = "static_bottom",
        burnInIntervalMs = 60_000L,
        original = original,
        romanized = romanized,
        translated = translated,
        metadata = metadata,
        alignedRight = true,
        lineLevelSync = true,
        lineStartMs = 10L,
        lineEndMs = 20L,
        durationMs = 1_000L,
        positionMs = 100L,
        sampledAtElapsedMs = 700L,
        speed = speed,
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups,
        weight = weight,
        textSizeMode = "normal",
        textSizeCustom = 100,
        secondaryMode = "Main only",
        animationMode = "Gradient",
        glowMode = "Off",
        motionMode = "Fluid",
        lineSyncFillMode = "Top to bottom",
        overflowMode = "Wrap",
        transitionMode = "Fade up",
        fontFamily = "noto",
        alignmentMode = "auto",
        metadataVisible = true,
        metadataAnchor = "top",
        adaptiveSectioning = true
    )
}
