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

        val paused = AodStateWireMessage.Hidden(
            revision = 8L,
            userId = 10,
            updatedAtElapsedMs = 901L,
            keepAlive = false,
            wakeSignal = 0L,
            pauseRetentionEligible = true
        )
        assertEquals(paused, AodStateWireCodec.encode(paused)?.let(AodStateWireCodec::decode))
    }

    @Test
    fun suppressAndRotateFlagsRoundTrip() {
        val snapshot = snapshotMessage(
            value = snapshotValue().copy(
                suppressStockAodContent = true,
                aodRotateWithDevice = true,
                aodRotationMode = AOD_ROTATION_MODE_AUTO
            )
        )
        val decoded = requireNotNull(AodStateWireCodec.encode(snapshot))
            .let(AodStateWireCodec::decode) as AodStateWireMessage.Snapshot

        assertEquals(snapshot, decoded)
        assertTrue(decoded.value.suppressStockAodContent)
        assertTrue(decoded.value.aodRotateWithDevice)
        assertEquals(AOD_ROTATION_MODE_AUTO, decoded.value.aodRotationMode)
    }

    @Test
    fun canvasAnchorRoundTrip() {
        val snapshot = snapshotMessage(
            value = snapshotValue().copy(aodCanvasAnchor = 0.2f)
        )
        val decoded = requireNotNull(AodStateWireCodec.encode(snapshot))
            .let(AodStateWireCodec::decode) as AodStateWireMessage.Snapshot

        assertEquals(snapshot, decoded)
        assertEquals(0.2f, decoded.value.aodCanvasAnchor, 0f)
    }

    @Test
    fun rotationSettleRoundTrip() {
        val snapshot = snapshotMessage(
            value = snapshotValue().copy(aodRotationSettleMs = 5_000L)
        )
        val decoded = requireNotNull(AodStateWireCodec.encode(snapshot))
            .let(AodStateWireCodec::decode) as AodStateWireMessage.Snapshot

        assertEquals(snapshot, decoded)
        assertEquals(5_000L, decoded.value.aodRotationSettleMs)
    }

    @Test
    fun landscapeFieldsRoundTrip() {
        val snapshot = snapshotMessage(
            value = snapshotValue().copy(
                aodCanvasAnchorLandscape = 0.2f,
                aodLandscapeTextScale = 1.5f,
                aodCanvasPaddingDp = 16,
                aodCanvasPaddingXPercent = 5f,
                aodCanvasPaddingYPercent = 10f,
                aodCanvasPaddingPortraitXPercent = 3f,
                aodCanvasPaddingPortraitYPercent = 4f,
                aodCanvasPaddingLandscapeXPercent = 6f,
                aodCanvasPaddingLandscapeYPercent = 7f
            )
        )
        val decoded = requireNotNull(AodStateWireCodec.encode(snapshot))
            .let(AodStateWireCodec::decode) as AodStateWireMessage.Snapshot

        assertEquals(snapshot, decoded)
        assertEquals(0.2f, decoded.value.aodCanvasAnchorLandscape, 0f)
        assertEquals(1.5f, decoded.value.aodLandscapeTextScale, 0f)
        assertEquals(16, decoded.value.aodCanvasPaddingDp)
        assertEquals(5f, decoded.value.aodCanvasPaddingXPercent, 0f)
        assertEquals(10f, decoded.value.aodCanvasPaddingYPercent, 0f)
        assertEquals(3f, decoded.value.aodCanvasPaddingPortraitXPercent, 0f)
        assertEquals(4f, decoded.value.aodCanvasPaddingPortraitYPercent, 0f)
        assertEquals(6f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
        assertEquals(7f, decoded.value.aodCanvasPaddingLandscapeYPercent, 0f)
    }

    @Test
    fun concurrentSecondLineRoundTrips() {
        val snapshot = snapshotMessage(
            value = snapshotValue().copy(
                secondLine = AodStateWireSecondLine(
                    text = "second",
                    romanized = "second reading",
                    translated = "second translation",
                    alignedRight = true,
                    lineStartMs = 15L,
                    lineEndMs = 25L,
                    words = listOf(
                        AodStateWireWord("sec", "seku", 15L, 25L, false, 0, 3)
                    ),
                    ruby = listOf(AodStateWireRuby(0, 3, "reading")),
                    layoutGroups = listOf(
                        AodStateWireLayoutGroup(0, 6, "word", true, 0.5)
                    )
                )
            )
        )
        val decoded = requireNotNull(AodStateWireCodec.encode(snapshot))
            .let(AodStateWireCodec::decode) as AodStateWireMessage.Snapshot

        assertEquals(snapshot, decoded)
        assertEquals("second", decoded.value.secondLine?.text)
        assertEquals(15L, decoded.value.secondLine?.lineStartMs)
        assertEquals("sec", decoded.value.secondLine?.words?.singleOrNull()?.text)
    }

    @Test
    fun bodyVersionEightDecodesWithoutConcurrentLine() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val bodyV9 = requireNotNull(envelope.body)
        val bodyV8 = ByteBuffer.allocate(bodyV9.size - 1).apply {
            put(bodyV9, 0, bodyV9.size - 1)
            putInt(4, 8)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV8))
            as AodStateWireMessage.Snapshot

        assertNull(decoded.value.secondLine)
        assertEquals(2f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
    }

    @Test
    fun bodyVersionSevenUsesSharedPairForBothOrientations() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage(
            value = snapshotValue().copy(
                aodCanvasPaddingXPercent = 5f,
                aodCanvasPaddingYPercent = 10f
            )
        )))
        val bodyV8 = requireNotNull(envelope.body)
        val bodyV7 = ByteBuffer.allocate(bodyV8.size - 17).apply {
            put(bodyV8, 0, bodyV8.size - 17)
            putInt(4, 7)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV7))
            as AodStateWireMessage.Snapshot

        assertEquals(5f, decoded.value.aodCanvasPaddingPortraitXPercent, 0f)
        assertEquals(10f, decoded.value.aodCanvasPaddingPortraitYPercent, 0f)
        assertEquals(5f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
        assertEquals(10f, decoded.value.aodCanvasPaddingLandscapeYPercent, 0f)
    }

    @Test
    fun bodyVersionSixDerivesPercentPaddingFromLegacyDp() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage(
            value = snapshotValue().copy(aodCanvasPaddingDp = 16)
        )))
        val bodyV8 = requireNotNull(envelope.body)
        val bodyV6 = ByteBuffer.allocate(bodyV8.size - 25).apply {
            put(bodyV8, 0, bodyV8.size - 25)
            putInt(4, 6)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV6))
            as AodStateWireMessage.Snapshot

        assertEquals(16, decoded.value.aodCanvasPaddingDp)
        assertEquals(4f, decoded.value.aodCanvasPaddingXPercent, 0f)
        assertEquals(4f, decoded.value.aodCanvasPaddingYPercent, 0f)
        assertEquals(4f, decoded.value.aodCanvasPaddingPortraitXPercent, 0f)
        assertEquals(4f, decoded.value.aodCanvasPaddingPortraitYPercent, 0f)
        assertEquals(4f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
        assertEquals(4f, decoded.value.aodCanvasPaddingLandscapeYPercent, 0f)
    }

    @Test
    fun rotationModeRoundTrip() {
        val snapshot = snapshotMessage(
            value = snapshotValue().copy(aodRotationMode = AOD_ROTATION_MODE_LANDSCAPE)
        )
        val decoded = requireNotNull(AodStateWireCodec.encode(snapshot))
            .let(AodStateWireCodec::decode) as AodStateWireMessage.Snapshot

        assertEquals(snapshot, decoded)
        assertEquals(AOD_ROTATION_MODE_LANDSCAPE, decoded.value.aodRotationMode)
    }

    @Test
    fun bodyVersionFiveDerivesRotationModeFromLegacyFlag() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage(
            value = snapshotValue().copy(aodRotateWithDevice = true)
        )))
        val bodyV8 = requireNotNull(envelope.body)
        val bodyV5 = ByteBuffer.allocate(bodyV8.size - 37).apply {
            put(bodyV8, 0, bodyV8.size - 37)
            putInt(4, 5)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV5))
            as AodStateWireMessage.Snapshot

        assertEquals(AOD_ROTATION_MODE_AUTO, decoded.value.aodRotationMode)
        assertEquals(2f, decoded.value.aodCanvasPaddingXPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingYPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingPortraitXPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
    }

    @Test
    fun bodyVersionFourDecodesWithoutLandscapeFields() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val bodyV8 = requireNotNull(envelope.body)
        val bodyV4 = ByteBuffer.allocate(bodyV8.size - 49).apply {
            put(bodyV8, 0, bodyV8.size - 49)
            putInt(4, 4)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV4))
            as AodStateWireMessage.Snapshot

        assertEquals(0.5f, decoded.value.aodCanvasAnchorLandscape, 0f)
        assertEquals(1f, decoded.value.aodLandscapeTextScale, 0f)
        assertEquals(8, decoded.value.aodCanvasPaddingDp)
        assertEquals(2f, decoded.value.aodCanvasPaddingXPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingYPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingPortraitXPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
        assertEquals(AOD_ROTATION_MODE_PORTRAIT, decoded.value.aodRotationMode)
    }

    @Test
    fun bodyVersionThreeDecodesWithoutRotationSettle() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val bodyV8 = requireNotNull(envelope.body)
        val bodyV3 = ByteBuffer.allocate(bodyV8.size - 57).apply {
            put(bodyV8, 0, bodyV8.size - 57)
            putInt(4, 3)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV3))
            as AodStateWireMessage.Snapshot

        assertEquals(1_000L, decoded.value.aodRotationSettleMs)
        assertEquals(0.5f, decoded.value.aodCanvasAnchor, 0f)
    }

    @Test
    fun bodyVersionTwoDecodesWithoutCanvasAnchorOrSettle() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val bodyV8 = requireNotNull(envelope.body)
        val bodyV2 = ByteBuffer.allocate(bodyV8.size - 61).apply {
            put(bodyV8, 0, bodyV8.size - 61)
            putInt(4, 2)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV2))
            as AodStateWireMessage.Snapshot

        assertEquals(0.5f, decoded.value.aodCanvasAnchor, 0f)
        assertEquals(1_000L, decoded.value.aodRotationSettleMs)
        assertEquals("line", decoded.value.original)
    }

    @Test
    fun bodyVersionOneDecodesWithoutSuppressOrRotateFlags() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val bodyV2 = requireNotNull(envelope.body)
        // Fixed prefix with default snapshotValue(): magic(4) version(4) counts(12)
        // trackGeneration(8) booleans(4) burnInPattern(4 + 13) interval(8).
        // Trailing anchor, settle, landscape anchor/scale, padding, mode,
        // padding percent, and oriented padding are stripped.
        val flagsOffset = 4 + 4 + 12 + 8 + 4 + 4 + "static_bottom".length + 8
        val bodyV1 = ByteBuffer.allocate(bodyV2.size - 63).apply {
            put(bodyV2, 0, flagsOffset)
            putInt(4, 1)
            put(bodyV2, flagsOffset + 2, bodyV2.size - flagsOffset - 2 - 61)
        }.array()
        val decoded = AodStateWireCodec.decode(envelope.copy(body = bodyV1))
            as AodStateWireMessage.Snapshot

        assertFalse(decoded.value.suppressStockAodContent)
        assertFalse(decoded.value.aodRotateWithDevice)
        assertEquals(0.5f, decoded.value.aodCanvasAnchor, 0f)
        assertEquals(1_000L, decoded.value.aodRotationSettleMs)
        assertEquals(0.5f, decoded.value.aodCanvasAnchorLandscape, 0f)
        assertEquals(1f, decoded.value.aodLandscapeTextScale, 0f)
        assertEquals(8, decoded.value.aodCanvasPaddingDp)
        assertEquals(2f, decoded.value.aodCanvasPaddingXPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingYPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingLandscapeXPercent, 0f)
        assertEquals(2f, decoded.value.aodCanvasPaddingLandscapeYPercent, 0f)
        assertEquals(AOD_ROTATION_MODE_PORTRAIT, decoded.value.aodRotationMode)
        assertEquals("static_bottom", decoded.value.burnInPattern)
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
