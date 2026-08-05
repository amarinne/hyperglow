package com.eza.hyperglow.producer

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Which lyrics source feeds the projection pipeline. Persisted in user preferences and
 * selected at runtime by [LyricProducerArbiter.setPreference].
 *
 * Adding a new producer requires (a) implementing [LyricProducer] and (b) registering it
 * with the arbiter. The `spotify:track:` URI constraint stays internal to the Spicy producer
 * and MUST NOT be re-imposed at this boundary.
 */
enum class LyricSource { SPICY, LYRICON }

/**
 * Connection state of a [LyricProducer]. The arbiter uses this to decide when to fall back.
 *
 * - [CONNECTED] / [RECONNECTED]: producer may emit non-stale [LyricProducerState].
 * - [DISCONNECTED] / [CONNECT_TIMEOUT]: arbiter clears `active` and MAY fall back.
 *
 * Reuses the lyricon subscriber SDK's `ConnectionListener` vocabulary so the lyricon producer
 * can forward its callbacks 1:1 (Phase 3).
 */
enum class ProducerConnection { CONNECTED, RECONNECTED, DISCONNECTED, CONNECT_TIMEOUT }

/**
 * One karaoke word inside the active lyric line, normalized at the producer boundary.
 *
 * Mirrors the fields [com.eza.hyperglow.bridge.SpicyBridgeWord] carries on the Spicy path and
 * the per-word timing [io.github.proify.lyricon.lyric.model.LyricWord] carries on the lyricon
 * path. Producers MUST map their ingress word model into this type before emitting state.
 */
data class LyricWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

/**
 * Render modes for the current state. The Spicy producer fills these from the Spicy EX
 * payload; the lyricon producer fills them from [com.eza.hyperglow.aod.AodRenderPreferences] /
 * [com.eza.hyperglow.customization.CustomizationRepository], because the lyricon `Song` model
 * carries no render-mode fields.
 */
data class ProducerRenderModes(
    val weight: String,
    val textSize: String,
    val textSizeCustom: Int,
    val secondary: String,
    val animation: String,
    val glow: String,
    val lineSyncFill: String,
    val overflow: String,
    val transition: String,
    val font: String
)

/**
 * Producer-agnostic lyrics state consumed by [com.eza.hyperglow.aod.AodProjectionEngine].
 *
 * This is the single ingress-to-projection boundary. Producers MUST normalize their ingress
 * payload into this shape before emitting. Fields mirror [com.eza.hyperglow.bridge.SpicyBridgeState]
 * so the Spicy path wraps without loss; the lyricon path computes `line`/`lineIndex`/`words`
 * from the active [io.github.proify.lyricon.lyric.model.RichLyricLine] for the current
 * `positionMs` (read from the subscriber's `SharedMemory`).
 *
 * `words` is null when the producer has no per-word timing (line-level lyrics); non-null for
 * syllable/word-level karaoke.
 *
 * `staleAfterMs` matches `SpicyBridgeStore.STALE_AFTER_MS = 3000ms` uniformly for both
 * producers (see the spec's uniform-staleness invariant).
 */
data class LyricProducerState(
    val producerId: String,
    val generation: Int,
    val sequence: Long,
    val status: String,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageId: String,
    val line: String,
    val romanizedLine: String,
    val translatedLine: String,
    val lineIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val playing: Boolean,
    val receivedAtElapsedMs: Long,
    val words: List<LyricWord>?,
    val renderModes: ProducerRenderModes,
    val staleAfterMs: Long = STALE_AFTER_MS
) {
    companion object {
        /**
         * Uniform staleness threshold for all producers. Mirrors
         * `SpicyBridgeStore.STALE_AFTER_MS`. The arbiter clears `active` when a state older
         * than this is still current.
         */
        const val STALE_AFTER_MS = 3_000L
    }
}

/**
 * A lyrics source feeding the projection pipeline through one normalized boundary.
 *
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * - Emits a nullable [LyricProducerState] via [state]; null means "no current state".
 * - Reports [connection] so the arbiter can fall back on disconnect/timeout.
 * - [start] / [stop] are idempotent and lifecycle-bound; called once by the arbiter.
 *
 * Implementations MUST NOT impose producer-specific constraints (e.g. `spotify:track:`) at
 * this interface — those stay internal to the producer.
 */
interface LyricProducer {
    val id: LyricSource
    val state: StateFlow<LyricProducerState?>
    val connection: StateFlow<ProducerConnection>

    fun start(context: Context)
    fun stop()
}
