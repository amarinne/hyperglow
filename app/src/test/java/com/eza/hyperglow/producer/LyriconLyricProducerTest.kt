package com.eza.hyperglow.producer

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [LyriconLyricProducer]'s skeleton behavior: how SDK callbacks map to the boundary's
 * [ProducerConnection] and [LyricProducerState].
 *
 * Phase 3 status: the position-poll / active-line computation is not yet implemented, so we
 * verify the lifecycle/listener surface that IS in place — connection transitions and the
 * state-clearing semantics on disconnect/song-clear. The SDK's `ConnectionListener` and
 * `ActivePlayerListener` are driven directly via the producer's internal listener objects.
 *
 * (Listener callback params of type [LyriconSubscriber] are ignored by the producer's
 * callback bodies — only the connection/state transitions matter — so we pass a unchecked
 * null reference, which is safe because the parameter is never dereferenced.)
 */
class LyriconLyricProducerTest {

    private val producer = LyriconLyricProducer()

    // Cast helper: the listener bodies never dereference the subscriber arg.
    private val unusedSubscriber: LyriconSubscriber get() = null as LyriconSubscriber

    @Test
    fun initialConnectionIsDisconnectedAndStateIsNull() {
        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun connectionListener_onConnected_mapsToConnected() {
        producer.connectionListener.onConnected(unusedSubscriber)
        assertEquals(ProducerConnection.CONNECTED, producer.connection.value)
    }

    @Test
    fun connectionListener_onReconnected_mapsToReconnected() {
        producer.connectionListener.onReconnected(unusedSubscriber)
        assertEquals(ProducerConnection.RECONNECTED, producer.connection.value)
    }

    @Test
    fun connectionListener_onDisconnected_mapsToDisconnectedAndClearsState() {
        // First put something into the state via a song change, then disconnect.
        producer.playerListener.onSongChanged(song(name = "before disconnect"))
        // (Phase 3 will emit state on song change; today it stays null. Disconnect must still
        // guarantee null state regardless.)

        producer.connectionListener.onDisconnected(unusedSubscriber)

        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun connectionListener_onConnectTimeout_mapsToConnectTimeoutAndClearsState() {
        producer.connectionListener.onConnectTimeout(unusedSubscriber)

        assertEquals(ProducerConnection.CONNECT_TIMEOUT, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun playerListener_onSongChangedNull_clearsSongAndState() {
        producer.playerListener.onSongChanged(null)

        // State stays null (skeleton doesn't emit yet, but null song must not produce state).
        assertNull(producer.state.value)
    }

    @Test
    fun playerListener_onActiveProviderChangedNull_clearsState() {
        // A null provider means no active player; state must be cleared.
        producer.playerListener.onActiveProviderChanged(null)

        assertNull(producer.state.value)
    }

    @Test
    fun producerId_isLyricon() {
        // Sanity: the boundary identity is LYRICON so the arbiter routes it correctly.
        assertEquals(LyricSource.LYRICON, producer.id)
    }

    private fun song(
        id: String? = "song-1",
        name: String? = "Test Song",
        artist: String? = "Test Artist",
        duration: Long = 200_000L
    ): Song = Song(
        id = id,
        name = name,
        artist = artist,
        duration = duration,
        metadata = null,
        lyrics = null
    )
}
