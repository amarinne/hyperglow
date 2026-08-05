package com.eza.hyperglow.producer

import android.content.Context
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeStore
import com.eza.hyperglow.bridge.SpicyBridgeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Wraps the existing [SpicyBridgeStore] (+ [SpicyBridgeDocumentStore]) as a [LyricProducer], so
 * the projection pipeline can consume lyrics through the producer-agnostic boundary.
 *
 * Design notes:
 * - [state] is derived from [SpicyBridgeStore.state] combined with [SpicyBridgeDocumentStore.state]:
 *   each [SpicyBridgeState] is mapped to a [LyricProducerState] with the active-row fields
 *   (`lyricKind`/`line`/`words`/`lineStartMs`/`lineEndMs`/`alignedRight`/`ruby`/`layoutGroups`/
 *   `hasTimedLyrics`/`nextLineStartMs`/`lineIndex`) computed from the matching
 *   [SpicyBridgeDocument] via [SpicyBridgeDocument.primaryRowAt] at the **sampled position**
 *   (`state.positionMs`), per spec clause 9. The `spotify:track:` constraint stays enforced inside
 *   `SpicyBridgeStateReducer` (it never reaches this wrapper).
 * - [connection] is always [ProducerConnection.CONNECTED]: the Spicy bridge is an in-process
 *   ContentProvider/AIDL surface, so it has no async connect/disconnect lifecycle the way the
 *   lyricon subscriber does. It is "connected" whenever the app process is running.
 * - [start]/[stop] are no-ops for the Spicy path: [SpicyBridgeStore] is a process-global
 *   singleton already started by the bridge service; the producer only exposes it. This keeps
 *   the interface symmetric with the lyricon producer without changing Spicy's lifecycle.
 *
 * Spec clause 9: the Spicy producer populates the active-row fields from
 * [SpicyBridgeDocumentStore] (computing the active row via `primaryRowAt` at the sampled
 * position) so `AodProjectionEngine` can consume `arbiter.active` as its sole ingress.
 */
class SpicyLyricProducer : LyricProducer {
    override val id: LyricSource = LyricSource.SPICY

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val mutableConnection = MutableStateFlow(ProducerConnection.CONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    override val state: StateFlow<LyricProducerState?> =
        combine(SpicyBridgeStore.state, SpicyBridgeDocumentStore.state) { spicy, document ->
            spicy?.let { toProducerState(it, document) }
        }.onEach { producer ->
            if (producer == null) {
                AppLog.i("SpicyLyricProducer", "ingest: state cleared")
            } else {
                AppLog.i(
                    "SpicyLyricProducer",
                    "ingest: producer=${producer.producerId} gen=${producer.generation} " +
                        "seq=${producer.sequence} status=${producer.status} " +
                        "track=${producer.trackUri} playing=${producer.playing} " +
                        "kind=${producer.lyricKind} lineIdx=${producer.lineIndex} " +
                        "pos=${producer.positionMs}/${producer.durationMs}ms"
                )
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SpicyBridgeStore.state.value?.let {
                toProducerState(it, SpicyBridgeDocumentStore.state.value)
            }
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

    /**
     * Maps a [SpicyBridgeState] (+ the current [SpicyBridgeDocument], if any) into a
     * [LyricProducerState], computing the active-row fields per spec clause 9.
     *
     * The active row is selected via [SpicyBridgeDocument.primaryRowAt] at the **sampled
     * position** [SpicyBridgeState.positionMs] (NOT a forward-projected position): the producer
     * boundary only sees the sample the ingress delivered, and projection MUST NOT re-select the
     * line (spec clause 8). Forward position projection for display remains the engine's job via
     * [projectToDisplay] / [com.eza.hyperglow.aod.AodProjectionEngine.projectedPosition].
     *
     * @param spicy the ingress state.
     * @param document the current document (may be null or belong to a different session).
     */
    internal fun toProducerState(
        spicy: SpicyBridgeState,
        document: SpicyBridgeDocument?
    ): LyricProducerState {
        val position = spicy.positionMs
        val matchedDocument = document?.takeIf { it.matches(spicy) }
        val timedDocument = matchedDocument?.takeIf { isTimedDocumentType(it.type) }
        val noLyrics = spicy.status == "no_lyrics"
        val unsynced = matchedDocument != null && timedDocument == null
        val hasTimedLyrics = !noLyrics && timedDocument?.let(::hasActualLyricTiming) == true
        // Active row at the sampled position (null during interludes or when there is no timed
        // document). Suppressed entirely under no_lyrics so projection falls back to "♪".
        val row = timedDocument?.primaryRowAt(position)?.takeUnless { noLyrics }
        val lineIndex = row?.let { r -> timedDocument!!.rows.indexOfFirst { it === r } } ?: -1

        // lyricKind: NONE covers both "no lyrics" and "no document" (the latter lets projection
        // use the producer's `line` field as an untimed fallback one-liner). UNSYNCED is a
        // non-timed document. LINE/SYLLABLE come from the timed document's type.
        val lyricKind = when {
            noLyrics || matchedDocument == null -> LyricKind.NONE
            unsynced -> LyricKind.UNSYNCED
            isLineLevelDocumentType(timedDocument!!.type) -> LyricKind.LINE
            else -> LyricKind.SYLLABLE
        }

        // When a document is present but there is no active row (interlude), clear the line text
        // so projection classifies it as INTERLUDE rather than reusing the ingress `line`. When
        // there is no document at all, keep the ingress `line` so projection's fallback-line
        // branch can use it.
        val hasDocument = matchedDocument != null
        val line = row?.text ?: if (hasDocument) "" else spicy.line
        val romanizedLine = row?.romanized ?: if (hasDocument) "" else spicy.romanizedLine
        val translatedLine = row?.translated ?: if (hasDocument) "" else spicy.translatedLine

        // words: null for LINE / no-row / no-document (line-level), non-null only for a SYLLABLE
        // document with an active row (per-word karaoke timing).
        val words = if (timedDocument != null && row != null &&
            !isLineLevelDocumentType(timedDocument.type)
        ) {
            row.words.map(::toProducerWord)
        } else {
            null
        }

        val nextLineStartMs = timedDocument?.rows?.asSequence()
            ?.map { it.startMs }
            ?.filter { it > position }
            ?.minOrNull()

        return LyricProducerState(
            producerId = spicy.producerId,
            generation = spicy.generation,
            sequence = spicy.sequence,
            status = spicy.status,
            trackUri = spicy.trackUri,
            title = spicy.title,
            artist = spicy.artist,
            album = spicy.album,
            imageId = spicy.imageId,
            line = line,
            romanizedLine = romanizedLine,
            translatedLine = translatedLine,
            lineIndex = lineIndex,
            positionMs = spicy.positionMs,
            durationMs = spicy.durationMs,
            sampledAtElapsedMs = spicy.sampledAtElapsedMs,
            speed = spicy.speed,
            playing = spicy.playing,
            receivedAtElapsedMs = spicy.receivedAtElapsedMs,
            words = words,
            renderModes = ProducerRenderModes(
                weight = spicy.liveCardWeight,
                textSize = spicy.liveCardTextSize,
                textSizeCustom = spicy.liveCardTextSizeCustom,
                secondary = spicy.liveCardSecondaryMode,
                animation = spicy.liveCardAnimation,
                glow = spicy.liveCardGlow,
                lineSyncFill = spicy.liveCardLineSyncFill,
                overflow = spicy.liveCardOverflow,
                transition = spicy.liveCardTransition,
                font = spicy.lyricsFont
            ),
            lyricKind = lyricKind,
            alignedRight = row?.alignedRight == true,
            lineStartMs = row?.startMs ?: 0L,
            lineEndMs = row?.fillEndMs ?: 0L,
            ruby = row?.ruby?.map { LyricRuby(it.start, it.end, it.reading) } ?: emptyList(),
            layoutGroups = row?.layoutGroups?.map {
                LyricLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
            } ?: emptyList(),
            hasTimedLyrics = hasTimedLyrics,
            nextLineStartMs = nextLineStartMs
        )
    }

    private fun toProducerWord(word: SpicyBridgeWord) = LyricWord(
        text = word.text,
        romanized = word.romanized,
        startMs = word.startMs,
        endMs = word.endMs,
        boundaryAfter = word.boundaryAfter,
        sourceStart = word.sourceStart,
        sourceEnd = word.sourceEnd
    )

    private fun isTimedDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true) || type.equals("Syllable", ignoreCase = true)

    private fun isLineLevelDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true)

    private fun hasActualLyricTiming(document: SpicyBridgeDocument): Boolean =
        isTimedDocumentType(document.type) && document.rows.any { it.endMs > it.startMs }
}
