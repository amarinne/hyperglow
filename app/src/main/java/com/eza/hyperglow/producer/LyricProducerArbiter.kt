package com.eza.hyperglow.producer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Holds exactly one live lyric source.
 *
 * Exactly one, deliberately. Sources are not merged, and a source that disconnects or fails
 * identity validation is not replaced by another: keepalive authority has to stay attributable to
 * the source the user chose, and a silent failover would make it ambiguous at the moment it matters
 * most — a screen held awake by something the user did not select. Switching is an explicit act,
 * and the outgoing producer is stopped before the incoming one starts.
 */
internal class LyricProducerArbiter(private val producers: List<LyricProducer>) {
    private val mutableActive = MutableStateFlow(producers.first())

    val active: StateFlow<LyricProducer> = mutableActive.asStateFlow()

    val activeSource: LyricSource get() = mutableActive.value.source

    /**
     * One event per state or document change on the active producer, carrying the state. Switching
     * producers resubscribes, so the engine sees the new source's current state immediately and
     * never a blend of the two.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val updates: Flow<LyricProducerState?> = mutableActive.flatMapLatest { producer ->
        combine(producer.state, producer.document) { state, _ -> state }
    }

    /**
     * State is withheld unless the active producer has validated the identity of the source it is
     * bound to. Enforced here rather than trusted to each producer: this is the one place every
     * read passes through, so an unvalidated source cannot reach projection or keepalive even if a
     * producer implementation is wrong about itself.
     */
    fun currentState(): LyricProducerState? =
        mutableActive.value.takeIf(::isValidated)?.state?.value

    fun currentDocument(): LyricProducerDocument? =
        mutableActive.value.takeIf(::isValidated)?.document?.value

    fun connection(source: LyricSource): ProducerConnection =
        producers.firstOrNull { it.source == source }?.connection?.value
            ?: ProducerConnection.DISCONNECTED

    fun isCurrentActive(candidate: LyricProducerState): Boolean =
        mutableActive.value.let { isValidated(it) && it.isCurrentActive(candidate) }

    private fun isValidated(producer: LyricProducer): Boolean =
        producer.connection.value == ProducerConnection.VALIDATED

    fun expireIfStale(): Boolean = mutableActive.value.expireIfStale()

    fun clearDocument() = mutableActive.value.clearDocument()

    fun start(source: LyricSource = LyricSource.SPICY) {
        select(source)
        mutableActive.value.start()
    }

    fun select(source: LyricSource) {
        val next = producers.firstOrNull { it.source == source } ?: return
        val current = mutableActive.value
        if (current === next) return
        current.stop()
        mutableActive.value = next
        next.start()
    }

    fun stop() {
        mutableActive.value.stop()
    }

    companion object {
        /**
         * Spotify is the default source. It is the one under first-party control and the one every
         * verified behavior was measured against.
         */
        val Default = LyricProducerArbiter(listOf(SpicyLyricProducer))
    }
}
