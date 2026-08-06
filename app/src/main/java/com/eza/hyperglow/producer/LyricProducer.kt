package com.eza.hyperglow.producer

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeState
import kotlinx.coroutines.flow.StateFlow

/**
 * The projection input, named for what it is rather than where it came from. It is still the Spicy
 * bridge's payload class: the field set generalizes cleanly, and renaming it before a second
 * producer exists would be a large rename against a shape that a second source may yet change.
 */
internal typealias LyricProducerState = SpicyBridgeState

internal typealias LyricProducerDocument = SpicyBridgeDocument

/**
 * [SPICY] is the only implemented source. [LYRICON] is named but deliberately unbuilt: that bridge
 * does not validate which application registered as a lyric provider, so a source taken from it
 * could not be trusted to hold the screen awake without a trust boundary built here. The arbiter
 * treats a source with no registered producer as unselectable, so the name costs nothing and keeps
 * the arbitration rules testable against two real values.
 */
internal enum class LyricSource { SPICY, LYRICON }

/**
 * Whether a producer is bound to a source whose identity it has validated.
 *
 * [VALIDATED] is the only state that may publish. Identity is a property of the process a producer
 * binds to — the Spicy bridge validates Spotify's caller UID — and not of whichever player that
 * process happens to be reporting. A producer that cannot establish the identity of its own source
 * stays [REJECTED] and contributes nothing, rather than publishing unattributed playback into
 * keepalive.
 */
internal enum class ProducerConnection { DISCONNECTED, VALIDATED, REJECTED }

/**
 * One lyric source. Producers are independent and inert until started; the arbiter decides which
 * one is live. A producer never reaches around the arbiter to publish.
 */
internal interface LyricProducer {
    val source: LyricSource

    val state: StateFlow<LyricProducerState?>

    val document: StateFlow<LyricProducerDocument?>

    val connection: StateFlow<ProducerConnection>

    /** True while [candidate] is the live, non-stale state of a validated source. */
    fun isCurrentActive(candidate: LyricProducerState): Boolean

    /** Drops the retained state of a source that has gone quiet. True when something was dropped. */
    fun expireIfStale(): Boolean

    fun clearDocument()

    fun start()

    fun stop()
}
