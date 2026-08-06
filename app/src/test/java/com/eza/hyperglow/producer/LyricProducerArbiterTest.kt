package com.eza.hyperglow.producer

import com.eza.hyperglow.bridge.SpicyBridgeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricProducerArbiterTest {
    @Test
    fun spotifyIsTheDefaultSource() {
        val arbiter = LyricProducerArbiter(listOf(producer(LyricSource.SPICY), producer(OTHER)))

        assertEquals(LyricSource.SPICY, arbiter.activeSource)
    }

    @Test
    fun switchingStopsTheOutgoingProducerBeforeStartingTheIncomingOne() {
        val spicy = producer(LyricSource.SPICY)
        val other = producer(OTHER)
        val arbiter = LyricProducerArbiter(listOf(spicy, other))
        arbiter.start()

        arbiter.select(OTHER)

        assertEquals(listOf("start", "stop"), spicy.calls)
        assertEquals(listOf("start"), other.calls)
        assertEquals(OTHER, arbiter.activeSource)
    }

    @Test
    fun onlyTheActiveProducerIsRead() {
        val spicy = producer(LyricSource.SPICY)
        val other = producer(OTHER)
        spicy.publish(state("spotify:track:a"))
        other.publish(state("other:track:b"))
        val arbiter = LyricProducerArbiter(listOf(spicy, other))
        arbiter.start()

        assertEquals("spotify:track:a", arbiter.currentState()?.trackUri)

        arbiter.select(OTHER)

        assertEquals("other:track:b", arbiter.currentState()?.trackUri)
    }

    @Test
    fun aDisconnectedSourceIsNotReplacedByAnother() {
        val spicy = producer(LyricSource.SPICY)
        val other = producer(OTHER)
        other.publish(state("other:track:b"))
        val arbiter = LyricProducerArbiter(listOf(spicy, other))
        arbiter.start()

        spicy.connect(ProducerConnection.DISCONNECTED)

        assertEquals(LyricSource.SPICY, arbiter.activeSource)
        assertNull(arbiter.currentState())
    }

    @Test
    fun anUnvalidatedSourceContributesNothing() {
        val spicy = producer(LyricSource.SPICY)
        val candidate = state("spotify:track:a")
        spicy.publish(candidate)
        val arbiter = LyricProducerArbiter(listOf(spicy))
        arbiter.start()

        assertEquals(candidate, arbiter.currentState())
        assertTrue(arbiter.isCurrentActive(candidate))

        spicy.connect(ProducerConnection.REJECTED)

        assertNull(arbiter.currentState())
        assertNull(arbiter.currentDocument())
        assertFalse(arbiter.isCurrentActive(candidate))
    }

    @Test
    fun selectingAnAbsentSourceLeavesTheActiveOneRunning() {
        val spicy = producer(LyricSource.SPICY)
        val arbiter = LyricProducerArbiter(listOf(spicy))
        arbiter.start()

        arbiter.select(OTHER)

        assertEquals(LyricSource.SPICY, arbiter.activeSource)
        assertEquals(listOf("start"), spicy.calls)
    }

    private fun producer(source: LyricSource) = FakeProducer(source)

    private fun state(trackUri: String) = SpicyBridgeState(
        producerId = "producer",
        generation = 1,
        sequence = 1,
        status = "ready",
        trackUri = trackUri,
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "",
        line = "line",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = 100,
        durationMs = 1_000,
        sampledAtElapsedMs = 100,
        speed = 1f,
        playing = true,
        receivedAtElapsedMs = 100
    )

    private class FakeProducer(override val source: LyricSource) : LyricProducer {
        val calls = mutableListOf<String>()
        private val mutableState = MutableStateFlow<LyricProducerState?>(null)
        private val mutableDocument = MutableStateFlow<LyricProducerDocument?>(null)
        private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)

        override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()
        override val document: StateFlow<LyricProducerDocument?> = mutableDocument.asStateFlow()
        override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

        fun publish(value: LyricProducerState) {
            mutableState.value = value
        }

        fun connect(value: ProducerConnection) {
            mutableConnection.value = value
        }

        override fun isCurrentActive(candidate: LyricProducerState) = mutableState.value == candidate

        override fun expireIfStale(): Boolean = false

        override fun clearDocument() {
            mutableDocument.value = null
        }

        override fun start() {
            calls += "start"
            mutableConnection.value = ProducerConnection.VALIDATED
        }

        override fun stop() {
            calls += "stop"
            mutableConnection.value = ProducerConnection.DISCONNECTED
        }
    }

    private companion object {
        /**
         * Lyricon has no producer yet, so these exercise the arbitration rules against a fake. That
         * is the point: the rules are the contract the real producer will land against.
         */
        val OTHER = LyricSource.LYRICON
    }
}
