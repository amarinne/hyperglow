package com.eza.hyperglow.producer

import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.bridge.SpicyBridgeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Spotify source, backed by the Spicy bridge.
 *
 * The bridge already refuses payloads from callers whose UID is not Spotify's, so a state that
 * reaches these stores has been identity-checked at the boundary. This producer holds no state of
 * its own; it names the bridge as a source so the engine can consume one contract.
 */
internal object SpicyLyricProducer : LyricProducer {
    override val source = LyricSource.SPICY

    override val state: StateFlow<LyricProducerState?> = SpicyBridgeStore.state

    override val document: StateFlow<LyricProducerDocument?> = SpicyBridgeDocumentStore.state

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)

    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    override fun isCurrentActive(candidate: LyricProducerState): Boolean =
        SpicyBridgeStore.isCurrentActive(candidate)

    override fun expireIfStale(): Boolean = SpicyBridgeStore.expireIfStale()

    override fun clearDocument() = SpicyBridgeDocumentStore.clear()

    override fun start() {
        mutableConnection.value = ProducerConnection.VALIDATED
    }

    override fun stop() {
        mutableConnection.value = ProducerConnection.DISCONNECTED
    }
}
