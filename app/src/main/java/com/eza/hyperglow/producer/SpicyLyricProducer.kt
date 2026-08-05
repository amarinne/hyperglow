package com.eza.hyperglow.producer

import android.content.Context
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.bridge.SpicyBridgeRenderModes
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeStore
import com.eza.hyperglow.bridge.SpicyBridgeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Wraps the existing [SpicyBridgeStore] as a [LyricProducer], so the projection pipeline can
 * consume lyrics through the producer-agnostic boundary.
 *
 * Design notes:
 * - [state] is derived from [SpicyBridgeStore.state] by mapping each [SpicyBridgeState] to a
 *   [LyricProducerState]. The `spotify:track:` constraint stays enforced inside
 *   `SpicyBridgeStateReducer` (it never reaches this wrapper).
 * - [connection] is always [ProducerConnection.CONNECTED]: the Spicy bridge is an in-process
 *   ContentProvider/AIDL surface, so it has no async connect/disconnect lifecycle the way the
 *   lyricon subscriber does. It is "connected" whenever the app process is running.
 * - [start]/[stop] are no-ops for the Spicy path: [SpicyBridgeStore] is a process-global
 *   singleton already started by the bridge service; the producer only exposes it. This keeps
 *   the interface symmetric with the lyricon producer without changing Spicy's lifecycle.
 */
class SpicyLyricProducer : LyricProducer {
    override val id: LyricSource = LyricSource.SPICY

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val mutableConnection = MutableStateFlow(ProducerConnection.CONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    override val state: StateFlow<LyricProducerState?> =
        SpicyBridgeStore.state.map { spicy -> spicy?.let(::toProducerState) }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SpicyBridgeStore.state.value?.let(::toProducerState)
        )

    override fun start(context: Context) {
        // SpicyBridgeStore is already active process-wide; nothing to start.
        AppLog.i("SpicyLyricProducer", "start (no-op, SpicyBridgeStore is process-global)")
    }

    override fun stop() {
        // Intentionally not clearing SpicyBridgeStore: other surfaces (e.g. diagnostics) may
        // still read it. The arbiter handles clearing `active` on disconnect/stop.
        AppLog.i("SpicyLyricProducer", "stop (no-op)")
    }

    private fun toProducerState(spicy: SpicyBridgeState): LyricProducerState = LyricProducerState(
        producerId = spicy.producerId,
        generation = spicy.generation,
        sequence = spicy.sequence,
        status = spicy.status,
        trackUri = spicy.trackUri,
        title = spicy.title,
        artist = spicy.artist,
        album = spicy.album,
        imageId = spicy.imageId,
        line = spicy.line,
        romanizedLine = spicy.romanizedLine,
        translatedLine = spicy.translatedLine,
        lineIndex = spicy.lineIndex,
        positionMs = spicy.positionMs,
        durationMs = spicy.durationMs,
        sampledAtElapsedMs = spicy.sampledAtElapsedMs,
        speed = spicy.speed,
        playing = spicy.playing,
        receivedAtElapsedMs = spicy.receivedAtElapsedMs,
        // Spicy EX pushes line-level state without per-word timing in SpicyBridgeState;
        // per-word timing lives in SpicyBridgeDocumentStore and is consumed by the projection
        // engine directly. Null here means "line-level" per the LyricProducerState contract.
        words = null,
        renderModes = spicy.renderModes.toProducerRenderModes()
    )

    private fun SpicyBridgeRenderModes.toProducerRenderModes() = ProducerRenderModes(
        weight = weight,
        textSize = textSize,
        textSizeCustom = textSizeCustom,
        secondary = secondary,
        animation = animation,
        glow = glow,
        lineSyncFill = lineSyncFill,
        overflow = overflow,
        transition = transition,
        font = font
    )

    @Suppress("unused")
    private fun SpicyBridgeWord.toProducerWord() = LyricWord(
        text = text,
        romanized = romanized,
        startMs = startMs,
        endMs = endMs,
        boundaryAfter = boundaryAfter,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd
    )
}
