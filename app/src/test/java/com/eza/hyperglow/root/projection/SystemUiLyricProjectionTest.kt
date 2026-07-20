package com.eza.hyperglow.root.projection

import android.content.Context
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.aod.AodStateWireLayoutGroup
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.aod.AodStateWireRuby
import com.eza.hyperglow.aod.AodStateWireSnapshot
import com.eza.hyperglow.aod.AodStateWireWord
import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiLyricProjectionTest {
    @Test
    fun snapshotNormalizationBoundsPayloadAndRanges() {
        val normalized = normalizeLyricSnapshot(
            snapshot(
                original = "  " + "x".repeat(600),
                metadata = "m".repeat(250),
                positionMs = 2_000L,
                durationMs = 1_000L,
                speed = Float.NaN,
                words = List(140) { index ->
                    LyricWord("w".repeat(600), "r".repeat(600), -2L, -1L, false, index, index + 1)
                },
                ruby = listOf(LyricRuby(0, 2, "r"), LyricRuby(2, 3, "ok")),
                layoutGroups = listOf(LyricLayoutGroup(2, 3, "kind", true, 4.0))
            )
        )

        assertEquals(500, normalized.original.length)
        assertEquals(200, normalized.metadata.length)
        assertEquals(128, normalized.words.size)
        assertEquals(1_000L, normalized.durationMs)
        assertEquals(1_000L, normalized.positionMs)
        assertEquals(1f, normalized.speed)
        assertEquals(1.0, normalized.layoutGroups.single().confidence, 0.0)
    }

    @Test
    fun burnInSceneConfigurationNormalizesBeforeSystemUiUse() {
        val normalized = normalizeLyricSnapshot(
            snapshot().copy(burnInPattern = "unknown", burnInIntervalMs = 170_000L)
        )

        assertEquals("static_bottom", normalized.burnInPattern)
        assertEquals(120_000L, normalized.burnInIntervalMs)
    }

    @Test
    fun ownedWireSnapshotMapsEveryRetainedFieldBeforeProjectionUse() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(subscriber, null)
        val message = AodStateWireMessage.Snapshot(
            revision = 21L,
            userId = 0,
            updatedAtElapsedMs = 22L,
            keepAlive = true,
            wakeSignal = 23L,
            value = AodStateWireSnapshot(
                trackGeneration = 24L,
                aodEnabled = false,
                lockscreenEnabled = true,
                seamlessTransitionEnabled = false,
                positionFollowingEnabled = true,
                burnInPattern = "four_corner",
                burnInIntervalMs = 120_000L,
                original = "original",
                romanized = "romanized",
                translated = "translated",
                metadata = "metadata",
                alignedRight = true,
                lineLevelSync = true,
                lineStartMs = 25L,
                lineEndMs = 26L,
                durationMs = 100L,
                positionMs = 27L,
                sampledAtElapsedMs = 28L,
                speed = 1.25f,
                words = listOf(AodStateWireWord("word", "word-r", 29L, 30L, true, 0, 4)),
                ruby = listOf(AodStateWireRuby(0, 4, "ruby")),
                layoutGroups = listOf(
                    AodStateWireLayoutGroup(0, 4, "phrase", true, 0.75)
                ),
                weight = "Bold",
                textSizeMode = "large",
                textSizeCustom = 131,
                secondaryMode = "Both",
                animationMode = "Minimal",
                glowMode = "On",
                motionMode = "Fluid",
                lineSyncFillMode = "Left to right",
                overflowMode = "Clip",
                transitionMode = "None",
                fontFamily = "apple",
                alignmentMode = "end",
                metadataVisible = false,
                metadataAnchor = "bottom",
                adaptiveSectioning = false
            )
        )

        harness.sendState(message)

        val value = subscriber.snapshots.single()
        assertEquals(21L, value.revision)
        assertEquals(0, value.userId)
        assertEquals(24L, value.trackGeneration)
        assertEquals(22L, value.updatedAtElapsedMs)
        assertTrue(value.visible)
        assertFalse(value.aodEnabled)
        assertTrue(value.lockscreenEnabled)
        assertFalse(value.seamlessTransitionEnabled)
        assertTrue(value.keepAlive)
        assertTrue(value.positionFollowingEnabled)
        assertEquals("four_corner", value.burnInPattern)
        assertEquals(120_000L, value.burnInIntervalMs)
        assertEquals(23L, value.wakeSignal)
        assertEquals("original", value.original)
        assertEquals("romanized", value.romanized)
        assertEquals("translated", value.translated)
        assertEquals("metadata", value.metadata)
        assertTrue(value.alignedRight)
        assertTrue(value.lineLevelSync)
        assertEquals(25L, value.lineStartMs)
        assertEquals(26L, value.lineEndMs)
        assertEquals(100L, value.durationMs)
        assertEquals(27L, value.positionMs)
        assertEquals(28L, value.sampledAtElapsedMs)
        assertEquals(1.25f, value.speed)
        assertEquals(LyricWord("word", "word-r", 29L, 30L, true, 0, 4), value.words.single())
        assertEquals(LyricRuby(0, 4, "ruby"), value.ruby.single())
        assertEquals(LyricLayoutGroup(0, 4, "phrase", true, 0.75), value.layoutGroups.single())
        assertEquals("Bold", value.weight)
        assertEquals("large", value.textSizeMode)
        assertEquals(131, value.textSizeCustom)
        assertEquals("Both", value.secondaryMode)
        assertEquals("Minimal", value.animationMode)
        assertEquals("On", value.glowMode)
        assertEquals("Fluid", value.motionMode)
        assertEquals("Left to right", value.lineSyncFillMode)
        assertEquals("Clip", value.overflowMode)
        assertEquals("None", value.transitionMode)
        assertEquals("apple", value.fontFamily)
        assertEquals("end", value.alignmentMode)
        assertFalse(value.metadataVisible)
        assertEquals("bottom", value.metadataAnchor)
        assertFalse(value.adaptiveSectioning)
    }

    @Test
    fun futureWireTimestampFailsClosedBeforeProjectionAcceptance() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(subscriber, null)

        harness.sendState(
            AodStateWireMessage.Hidden(
                revision = 1L,
                userId = 0,
                updatedAtElapsedMs = 1_001L,
                keepAlive = false,
                wakeSignal = 0L
            )
        )

        assertTrue(subscriber.snapshots.isEmpty())
        assertNull(harness.projection.cachedSnapshot())
        assertTrue(isPlausibleWireTimestamp(1_000L, 0L))
        assertFalse(isPlausibleWireTimestamp(1_001L, 0L))
    }

    @Test
    fun staleAndDuplicateRevisionsAreRejected() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(subscriber, null)

        assertTrue(harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(2, 20))))
        assertFalse(harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(1, 30))))
        assertFalse(harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(2, 20))))
        assertTrue(harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(3, 10))))

        assertEquals(listOf(2L, 3L), subscriber.snapshots.map { it.revision })
    }

    @Test
    fun multipleSubscribersShareOneClientAndReplayLatestSnapshot() {
        val harness = Harness()
        val aod = RecordingSubscriber(LyricSurfaceKind.AOD)
        val lockscreen = RecordingSubscriber(LyricSurfaceKind.LOCKSCREEN)

        harness.projection.attach(aod, null)
        harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(1, 10)))
        harness.projection.attach(lockscreen, null)

        assertEquals(1, harness.client.bindCount)
        assertEquals(2, harness.projection.subscriberCount())
        assertEquals(listOf(1L), lockscreen.snapshots.map { it.revision })

        harness.projection.detach(aod)
        assertEquals(0, harness.client.unbindCount)
        harness.projection.detach(lockscreen)
        assertEquals(1, harness.client.unbindCount)
        assertNull(harness.projection.cachedSnapshot())
    }

    @Test
    fun keepAliveMergesWithoutReplayingContentAndDisconnectClears() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(subscriber, null)
        harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(4, 40)))

        assertTrue(
            harness.projection.accept(
                LyricProjectionMessage.KeepAlive(LyricKeepAliveSignal(4, 50, true, 9))
            )
        )
        assertEquals(1, subscriber.snapshots.size)
        assertEquals(1, subscriber.keepAlives.size)
        assertEquals(9L, harness.projection.cachedSnapshot()?.wakeSignal)

        harness.disconnect()
        assertEquals(1, subscriber.disconnects)
        assertNull(harness.projection.cachedSnapshot())
    }

    @Test
    fun clearSnapshotReplaysAsHiddenState() {
        val harness = Harness()
        val first = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(first, null)
        harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(1, 10)))
        harness.projection.accept(
            LyricProjectionMessage.Snapshot(snapshot(2, 20).copy(visible = false, original = ""))
        )
        val second = RecordingSubscriber(LyricSurfaceKind.LOCKSCREEN)
        harness.projection.attach(second, null)

        assertFalse(second.snapshots.single().visible)
    }

    @Test
    fun hiddenSnapshotKeepsLastVisibleContentForSurfaceReattachment() {
        val harness = Harness()
        val first = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(first, null)
        val visible = snapshot(1, 10).copy(original = "retained line")
        harness.projection.accept(LyricProjectionMessage.Snapshot(visible))
        harness.projection.accept(
            LyricProjectionMessage.Snapshot(
                snapshot(2, 20).copy(visible = false, original = "", keepAlive = false)
            )
        )

        assertFalse(harness.projection.cachedSnapshot()!!.visible)
        assertEquals("retained line", harness.projection.cachedVisibleSnapshot()?.original)
    }

    @Test
    fun nonAuthoritativeAodCannotRequestLifetimeOrWake() {
        assertFalse(shouldActivateAodLifetime(
            LyricSurfaceKind.LOCKSCREEN, true, true, true, false, true
        ))
        assertFalse(shouldActivateAodLifetime(
            LyricSurfaceKind.AOD, true, false, true, false, true
        ))
        assertFalse(shouldActivateAodLifetime(
            LyricSurfaceKind.AOD, true, true, false, false, true
        ))
        assertTrue(shouldActivateAodLifetime(
            LyricSurfaceKind.AOD, true, true, true, false, true
        ))
        assertTrue(shouldActivateAodLifetime(
            LyricSurfaceKind.AOD, true, true, false, true, true
        ))
        assertFalse(shouldRequestAodWake(true, false, true))
        assertFalse(shouldRequestAodWake(true, true, false))
        assertTrue(shouldRequestAodWake(true, true, true))
    }

    @Test
    fun configurationIsDeduplicatedAndReplayedToNewSubscribers() {
        val harness = Harness()
        val first = RecordingSubscriber(LyricSurfaceKind.AOD)
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        harness.projection.attach(first, null)

        assertTrue(harness.projection.acceptConfiguration(configuration))
        assertFalse(harness.projection.acceptConfiguration(configuration))
        val second = RecordingSubscriber(LyricSurfaceKind.LOCKSCREEN)
        harness.projection.attach(second, null)

        assertEquals(listOf(configuration.revision), first.configurations.map { it.revision })
        assertEquals(listOf(configuration.revision), second.configurations.map { it.revision })
    }

    @Test
    fun configurationUpdatesDiagnosticLoggingAndDisconnectDisablesIt() {
        val harness = Harness()
        val configuration = RuntimeCustomization.withDiagnosticLogging(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument()),
            diagnosticLogging = true,
            available = true,
            raiseToAod = true
        )

        assertTrue(harness.projection.acceptConfiguration(configuration))
        harness.disconnect()

        assertEquals(listOf(true, false), harness.diagnosticLoggingStates)
        assertEquals(listOf(true, false), harness.raiseToAodStates)
    }

    @Test
    fun userChangeDisablesDiagnosticLogging() {
        val harness = Harness()
        val configuration = RuntimeCustomization.withDiagnosticLogging(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument()),
            diagnosticLogging = true,
            available = true
        )
        harness.projection.acceptConfiguration(configuration)

        harness.projection.onUserChanged(10)

        assertEquals(listOf(true, false), harness.diagnosticLoggingStates)
    }

    @Test
    fun visibleSnapshotExpiresAndNotifiesWithoutDroppingConfiguration() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.LOCKSCREEN)
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        harness.projection.attach(subscriber, null)
        harness.projection.acceptConfiguration(configuration)
        harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(8, 10)))

        assertTrue(harness.projection.expireIfStale(5_011))
        assertNull(harness.projection.cachedSnapshot())
        assertNull(harness.projection.cachedVisibleSnapshot())
        assertEquals(configuration.revision, harness.projection.cachedCustomization()?.revision)
        assertEquals(1, subscriber.staleEvents)
    }

    @Test
    fun userChangeClearsAndRebindsSingleClient() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.AOD)
        harness.projection.attach(subscriber, null)
        harness.projection.accept(LyricProjectionMessage.Snapshot(snapshot(1, 10)))

        harness.projection.onUserChanged(10)

        assertEquals(2, harness.client.bindCount)
        assertEquals(listOf(0, 10), harness.client.boundUserIds)
        assertEquals(1, harness.client.unbindCount)
        assertEquals(1, subscriber.disconnects)
        assertNull(harness.projection.cachedSnapshot())
    }

    @Test
    fun userChangeRejectsPreviousUserState() {
        val harness = Harness()
        val subscriber = RecordingSubscriber(LyricSurfaceKind.LOCKSCREEN)
        harness.projection.attach(subscriber, null)
        harness.projection.onUserChanged(10)

        assertFalse(
            harness.projection.accept(
                LyricProjectionMessage.Snapshot(snapshot(1, 10).copy(userId = 0))
            )
        )
        assertTrue(
            harness.projection.accept(
                LyricProjectionMessage.Snapshot(snapshot(1, 10).copy(userId = 10))
            )
        )
        assertEquals(10, subscriber.snapshots.single().userId)
    }

    private fun snapshot(
        revision: Long = 1L,
        updatedAt: Long = 10L,
        original: String = "line",
        metadata: String = "track",
        positionMs: Long = 100L,
        durationMs: Long = 1_000L,
        speed: Float = 1f,
        words: List<LyricWord> = emptyList(),
        ruby: List<LyricRuby> = emptyList(),
        layoutGroups: List<LyricLayoutGroup> = emptyList()
    ) = LyricSnapshot(
        revision = revision,
        trackGeneration = 1L,
        updatedAtElapsedMs = updatedAt,
        visible = true,
        keepAlive = true,
        original = original,
        metadata = metadata,
        positionMs = positionMs,
        durationMs = durationMs,
        sampledAtElapsedMs = updatedAt,
        speed = speed,
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups
    )

    private class Harness {
        lateinit var client: FakeClient
        lateinit var sendState: (AodStateWireMessage) -> Unit
        lateinit var disconnect: () -> Unit
        val scheduler = FakeExpiryScheduler()
        val diagnosticLoggingStates = ArrayList<Boolean>()
        val raiseToAodStates = ArrayList<Boolean>()
        val projection = SystemUiLyricProjection(
            expiryScheduler = scheduler,
            elapsedRealtime = { 0L },
            processUserId = { 0 },
            setDiagnosticLogging = diagnosticLoggingStates::add,
            setRaiseToAod = raiseToAodStates::add
        ) { _, onState, onDisconnected ->
            sendState = onState
            disconnect = onDisconnected
            FakeClient().also { client = it }
        }
    }

    private class FakeExpiryScheduler : LyricExpiryScheduler {
        var action: (() -> Unit)? = null

        override fun schedule(delayMs: Long, action: () -> Unit) {
            this.action = action
        }

        override fun cancel() {
            action = null
        }
    }

    private class FakeClient : LyricProjectionClient {
        var bindCount = 0
        var unbindCount = 0
        val boundUserIds = ArrayList<Int>()

        override fun bind(hostContext: Context?, userId: Int) {
            bindCount++
            boundUserIds += userId
        }

        override fun unbind() {
            unbindCount++
        }
    }

    private class RecordingSubscriber(
        override val surfaceKind: LyricSurfaceKind
    ) : SystemUiLyricSubscriber {
        val snapshots = ArrayList<LyricSnapshot>()
        val keepAlives = ArrayList<LyricKeepAliveSignal>()
        val configurations = ArrayList<com.eza.hyperglow.customization.CompiledCustomization>()
        var disconnects = 0
        var staleEvents = 0

        override fun onLyricSnapshot(snapshot: LyricSnapshot) {
            snapshots += snapshot
        }

        override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
            keepAlives += signal
        }

        override fun onLyricProjectionDisconnected() {
            disconnects++
        }

        override fun onLyricProjectionStale() {
            staleEvents++
        }

        override fun onCustomization(
            configuration: com.eza.hyperglow.customization.CompiledCustomization
        ) {
            configurations += configuration
        }
    }
}
