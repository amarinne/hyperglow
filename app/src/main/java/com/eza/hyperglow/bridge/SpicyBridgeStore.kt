package com.eza.hyperglow.bridge

import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpicyBridgeState(
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
    val liveCardWeight: String = "Medium",
    val liveCardTextSize: String = "normal",
    val liveCardTextSizeCustom: Int = 100,
    val liveCardSecondaryMode: String = "Main only",
    val liveCardAnimation: String = "Karaoke fill",
    val liveCardGlow: String = "Off",
    val liveCardLineSyncFill: String = "Top to bottom",
    val liveCardOverflow: String = "Wrap",
    val liveCardTransition: String = "Fade up",
    val lyricsFont: String = "spotify"
)

internal data class SpicyBridgeRenderModes(
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

internal fun normalizeSpicyBridgeRenderModes(
    candidate: SpicyBridgeRenderModes
): SpicyBridgeRenderModes = candidate.copy(
    weight = candidate.weight.takeIf { it in SPICY_WEIGHTS } ?: "Medium",
    textSize = candidate.textSize.takeIf { it in SPICY_TEXT_SIZES } ?: "normal",
    textSizeCustom = candidate.textSizeCustom.coerceIn(0, 500),
    secondary = when (candidate.secondary) {
        "Transliteration", "Translation", "Both" -> candidate.secondary
        "Romanization", "Romanized" -> "Transliteration"
        else -> "Main only"
    },
    animation = when (candidate.animation) {
        "Minimal", "Karaoke fill", "Spotlight word" -> candidate.animation
        "Full" -> "Spotlight word"
        else -> "Karaoke fill"
    },
    glow = candidate.glow.takeIf { it in SPICY_GLOW_MODES } ?: "Off",
    lineSyncFill = candidate.lineSyncFill.takeIf { it in SPICY_LINE_SYNC_FILL_MODES }
        ?: "Top to bottom",
    overflow = candidate.overflow.takeIf { it in SPICY_OVERFLOW_MODES } ?: "Wrap",
    transition = candidate.transition.takeIf { it in SPICY_TRANSITION_MODES } ?: "Fade up",
    font = candidate.font.takeIf { it in SPICY_FONTS } ?: "spotify"
)

private val SPICY_WEIGHTS = setOf("Regular", "Medium", "Bold")
private val SPICY_TEXT_SIZES = setOf("small", "normal", "large", "xlarge", "custom")
private val SPICY_GLOW_MODES = setOf("Off", "Word only", "Subtle line")
private val SPICY_LINE_SYNC_FILL_MODES = setOf("Top to bottom", "Left to right (sentence)")
private val SPICY_OVERFLOW_MODES = setOf("Wrap", "Scroll with lyric", "Clip")
private val SPICY_TRANSITION_MODES = setOf("Fade up", "Crossfade", "None")
private val SPICY_FONTS = setOf("spotify", "apple")

internal data class SpicyBridgeStatePayload(
    val protocolVersion: Int,
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
    val renderModes: SpicyBridgeRenderModes
) {
    companion object {
        fun from(bundle: Bundle) = SpicyBridgeStatePayload(
            protocolVersion = bundle.getInt("protocolVersion", -1),
            producerId = bundle.getString("producerId").orEmpty(),
            generation = bundle.getInt("generation", -1),
            sequence = bundle.getLong("sequence", -1L),
            status = bundle.getString("status").orEmpty(),
            trackUri = bundle.getString("trackUri").orEmpty(),
            title = bundle.getString("title").orEmpty(),
            artist = bundle.getString("artist").orEmpty(),
            album = bundle.getString("album").orEmpty(),
            imageId = bundle.getString("imageId").orEmpty(),
            line = bundle.getString("line").orEmpty(),
            romanizedLine = bundle.getString("romanizedLine").orEmpty(),
            translatedLine = bundle.getString("translatedLine").orEmpty(),
            lineIndex = bundle.getInt("lineIndex", -1),
            positionMs = bundle.getLong("positionMs", -1L),
            durationMs = bundle.getLong("durationMs", -1L),
            sampledAtElapsedMs = bundle.getLong("sampledAtElapsedMs", -1L),
            speed = bundle.getFloat("speed", Float.NaN),
            playing = bundle.getBoolean("playing", false),
            renderModes = SpicyBridgeRenderModes(
                weight = bundle.getString("liveCardWeight").orEmpty(),
                textSize = bundle.getString("liveCardTextSize").orEmpty(),
                textSizeCustom = bundle.getInt("liveCardTextSizeCustom", 100),
                secondary = bundle.getString("liveCardSecondaryMode").orEmpty(),
                animation = bundle.getString("liveCardAnimation").orEmpty(),
                glow = bundle.getString("liveCardGlow").orEmpty(),
                lineSyncFill = bundle.getString("liveCardLineSyncFill").orEmpty(),
                overflow = bundle.getString("liveCardOverflow").orEmpty(),
                transition = bundle.getString("liveCardTransition").orEmpty(),
                font = bundle.getString("lyricsFont").orEmpty()
            )
        )
    }
}

internal class SpicyBridgeStateReducer {
    var current: SpicyBridgeState? = null
        private set

    private var tombstoneProducerId = ""
    private var tombstoneGeneration = -1L

    fun accept(payload: SpicyBridgeStatePayload, now: Long): Boolean {
        if (payload.protocolVersion != SpicyBridgeStore.PROTOCOL_VERSION) return false
        if (payload.producerId.isBlank() || payload.producerId.length > 64 ||
            payload.generation < 0 || payload.sequence < 0L
        ) return false
        if (payload.producerId == tombstoneProducerId &&
            payload.generation <= tombstoneGeneration
        ) return false

        current?.let { accepted ->
            if (isStale(accepted, now)) current = null
        }
        current?.let { accepted ->
            if (payload.producerId == accepted.producerId &&
                (payload.generation < accepted.generation ||
                    payload.generation == accepted.generation &&
                    payload.sequence <= accepted.sequence)
            ) return false
        }

        if (!payload.trackUri.startsWith("spotify:track:") ||
            payload.trackUri.length > MAX_METADATA_LENGTH ||
            payload.positionMs < 0L ||
            payload.durationMs !in 1..MAX_MEDIA_DURATION_MS ||
            payload.positionMs > payload.durationMs ||
            payload.sampledAtElapsedMs < now - MAX_SAMPLE_AGE_MS ||
            payload.sampledAtElapsedMs > now + 1_000L ||
            !payload.speed.isFinite() || payload.speed !in 0f..4f ||
            payload.status !in SPICY_STATUSES ||
            payload.title.length > MAX_METADATA_LENGTH ||
            payload.artist.length > MAX_METADATA_LENGTH ||
            payload.album.length > MAX_METADATA_LENGTH ||
            payload.imageId.length > MAX_METADATA_LENGTH ||
            payload.line.length > MAX_TEXT_LENGTH ||
            payload.romanizedLine.length > MAX_TEXT_LENGTH ||
            payload.translatedLine.length > MAX_TEXT_LENGTH ||
            payload.renderModes.weight.length > 16 ||
            payload.renderModes.textSize.length > 16 ||
            payload.renderModes.secondary.length > 32 ||
            payload.renderModes.animation.length > 32 ||
            payload.renderModes.glow.length > 24 ||
            payload.renderModes.lineSyncFill.length > 32 ||
            payload.renderModes.overflow.length > 32 ||
            payload.renderModes.transition.length > 24 ||
            payload.renderModes.font.length > 16
        ) return false

        val renderModes = normalizeSpicyBridgeRenderModes(payload.renderModes)
        current = SpicyBridgeState(
            producerId = payload.producerId,
            generation = payload.generation,
            sequence = payload.sequence,
            status = payload.status,
            trackUri = payload.trackUri,
            title = payload.title,
            artist = payload.artist,
            album = payload.album,
            imageId = payload.imageId,
            line = payload.line,
            romanizedLine = payload.romanizedLine,
            translatedLine = payload.translatedLine,
            lineIndex = payload.lineIndex,
            positionMs = payload.positionMs,
            durationMs = payload.durationMs,
            sampledAtElapsedMs = payload.sampledAtElapsedMs,
            speed = payload.speed,
            playing = payload.playing,
            receivedAtElapsedMs = now,
            liveCardWeight = renderModes.weight,
            liveCardTextSize = renderModes.textSize,
            liveCardTextSizeCustom = renderModes.textSizeCustom,
            liveCardSecondaryMode = renderModes.secondary,
            liveCardAnimation = renderModes.animation,
            liveCardGlow = renderModes.glow,
            liveCardLineSyncFill = renderModes.lineSyncFill,
            liveCardOverflow = renderModes.overflow,
            liveCardTransition = renderModes.transition,
            lyricsFont = renderModes.font
        )
        return true
    }

    fun clear(producerId: String, generation: Long) {
        if (producerId.isBlank()) return
        if (producerId != tombstoneProducerId || generation > tombstoneGeneration) {
            tombstoneProducerId = producerId
            tombstoneGeneration = generation
        }
        current?.let { accepted ->
            if (producerId == accepted.producerId && generation >= accepted.generation) current = null
        }
    }

    fun expireIfStale(now: Long): Boolean {
        val accepted = current ?: return false
        if (!isStale(accepted, now)) return false
        current = null
        return true
    }

    private fun isStale(state: SpicyBridgeState, now: Long): Boolean =
        now - state.receivedAtElapsedMs > SpicyBridgeStore.STALE_AFTER_MS

    companion object {
        private const val MAX_TEXT_LENGTH = 8_192
        private const val MAX_METADATA_LENGTH = 512
        private const val MAX_MEDIA_DURATION_MS = 24L * 60L * 60L * 1000L
        private const val MAX_SAMPLE_AGE_MS = 60_000L
        private val SPICY_STATUSES = setOf("loading", "ready", "no_lyrics")
    }
}

object SpicyBridgeStore {
    const val PROTOCOL_VERSION = 1
    const val STALE_AFTER_MS = 3_000L

    private val mutableState = MutableStateFlow<SpicyBridgeState?>(null)
    val state = mutableState.asStateFlow()
    private val reducer = SpicyBridgeStateReducer()

    @Synchronized
    fun accept(payload: Bundle, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val accepted = reducer.accept(SpicyBridgeStatePayload.from(payload), now)
        mutableState.value = reducer.current
        return accepted
    }

    @Synchronized
    fun clear(producerId: String, generation: Long) {
        reducer.clear(producerId, generation)
        mutableState.value = reducer.current
    }

    @Synchronized
    fun expireIfStale(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val expired = reducer.expireIfStale(now)
        mutableState.value = reducer.current
        return expired
    }

    fun isCurrentActive(candidate: SpicyBridgeState, now: Long = SystemClock.elapsedRealtime()): Boolean =
        state.value === candidate && !isStale(candidate, now)

    private fun isStale(state: SpicyBridgeState, now: Long) =
        now - state.receivedAtElapsedMs > STALE_AFTER_MS
}
