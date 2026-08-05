package com.eza.hyperglow.producer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [LyricProducer] backed by the lyricon subscriber SDK.
 *
 * Position delivery: the SDK polls its `SharedMemory` position buffer internally at ~60 Hz and
 * delivers each distinct value via [ActivePlayerListener.onPositionChanged] on `Dispatchers.Default`.
 * There is no public `SharedMemory`/`ByteBuffer` accessor, so this producer does NOT poll memory
 * itself — it consumes `onPositionChanged` directly (per spec clause 6, the position IS sourced
 * from SharedMemory, just delivered via the SDK's callback).
 *
 * Active-line selection: uses the SDK's [TimingNavigator] (binary search + sequential cache)
 * over the normalized song lyrics. [RichLyricLine] implements `ILyricTiming`, so
 * `TimingNavigator<RichLyricLine>` finds the current line for a position in O(log n).
 *
 * Render modes: the lyricon `Song` carries no render-mode fields, so per spec clause 5 they are
 * sourced from [CustomizationRepository.loadCompiled] (the AOD [CompiledSurfaceProfile]).
 * Snapshot is cached and refreshed on song change — never read at 60 Hz.
 *
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * - Requires API >= 27 (O_MR1). Below that, `LyriconFactory.createSubscriber` returns
 *   `EmptyLyriconSubscriber`, so this producer is a no-op (spec: API<27 → no-op).
 * - Requires lyricon's Xposed module active in SystemUI; its absence MUST NOT crash HyperGlow.
 *   Until connected, [connection] stays DISCONNECTED and [state] stays null, so the arbiter
 *   falls back to the Spicy producer automatically.
 * - The `spotify:track:` constraint is NOT imposed here (it stays in SpicyBridgeStateReducer);
 *   the track identity is a synthetic `lyricon:<songId|songName>`.
 *
 * Threading: all [ActivePlayerListener] callbacks arrive on Binder threads or `Dispatchers.Default`
 * (never main). [MutableStateFlow] is thread-safe, so emitting from any callback is safe.
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so [emit] can run without Android's [SystemClock].
 */
class LyriconLyricProducer(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : LyricProducer {

    override val id: LyricSource = LyricSource.LYRICON

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    private val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private var subscriber: LyriconSubscriber? = null
    private var contextRef: Context? = null
    private var started = false

    // --- Ingress state, updated by playerListener; read by emit(). @Volatile for cross-thread. ---
    @Volatile private var currentSong: Song? = null
    @Volatile private var navigator: TimingNavigator<RichLyricLine>? = null
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var currentLineIndex: Int = -1
    @Volatile private var cachedWords: List<LyricWord>? = null
    @Volatile private var renderModesSnapshot: ProducerRenderModes = defaultRenderModes()

    // Session/sequence for arbiter dedup (producerId:generation:sequence).
    @Volatile private var generation: Int = 0
    @Volatile private var sequence: Long = 0L

    internal val connectionListener = object : ConnectionListener {
        override fun onConnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "connected")
            mutableConnection.value = ProducerConnection.CONNECTED
        }

        override fun onReconnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "reconnected")
            mutableConnection.value = ProducerConnection.RECONNECTED
        }

        override fun onDisconnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "disconnected")
            mutableConnection.value = ProducerConnection.DISCONNECTED
            mutableState.value = null
        }

        override fun onConnectTimeout(s: LyriconSubscriber) {
            AppLog.w("LyriconLyricProducer", "connect timeout")
            mutableConnection.value = ProducerConnection.CONNECT_TIMEOUT
            mutableState.value = null
        }
    }

    internal val playerListener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            AppLog.i("LyriconLyricProducer", "provider=${providerInfo?.packageName}")
            if (providerInfo == null) {
                // No active player: clear state, let arbiter fall back / go idle.
                currentSong = null
                navigator = null
                currentLineIndex = -1
                cachedWords = null
                mutableState.value = null
            }
        }

        override fun onSongChanged(song: Song?) {
            if (song == null) {
                AppLog.i("LyriconLyricProducer", "onSongChanged: null (cleared)")
                currentSong = null
                navigator = null
                currentLineIndex = -1
                cachedWords = null
                mutableState.value = null
                return
            }
            AppLog.i(
                "LyriconLyricProducer",
                "onSongChanged: id=${song.id} name=${song.name} artist=${song.artist} " +
                    "duration=${song.duration}ms lines=${song.lyrics?.size ?: 0}"
            )
            // normalize() deep-copies and sorts lyrics by begin (asc, required by TimingNavigator),
            // dropping invalid lines. Safe to call on the SDK's instance (it doesn't mutate it).
            val normalized = song.normalize()
            currentSong = normalized
            generation++
            val lyrics = normalized.lyrics
            navigator = if (!lyrics.isNullOrEmpty()) {
                TimingNavigator(lyrics.toTypedArray())
            } else {
                null
            }
            currentLineIndex = -1
            cachedWords = null
            refreshRenderModes()
            // Emit initial state at the last known position (will be refined by onPositionChanged).
            emit()
        }

        override fun onReceiveText(text: String?) {
            // Plain-text lyrics (no timestamps). Out of scope for karaoke AOD; ignore.
            AppLog.i("LyriconLyricProducer", "onReceiveText: len=${text?.length} (ignored)")
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            AppLog.i("LyriconLyricProducer", "onPlaybackStateChanged: playing=$isPlaying")
            isPlayingState = isPlaying
            // Re-emit so the engine sees the new playing/speed without waiting for next position.
            emit()
        }

        override fun onPositionChanged(position: Long) {
            // High-frequency (~60 Hz) callback on Dispatchers.Default. This IS the SharedMemory
            // position, delivered by the SDK's internal poller. Compute the active line and emit.
            currentPositionMs = position
            recomputeAndEmit()
        }

        override fun onSeekTo(position: Long) {
            AppLog.i("LyriconLyricProducer", "onSeekTo: pos=${position}ms")
            currentPositionMs = position
            // Seek invalidates the navigator's sequential cache (playback jumped).
            navigator?.resetCache()
            currentLineIndex = -1
            cachedWords = null
            recomputeAndEmit()
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
            // HyperGlow controls translation display via its own CustomizationRepository; ignore
            // the lyricon-side toggle to avoid double-toggling.
            AppLog.i("LyriconLyricProducer", "onDisplayTranslationChanged: $isDisplayTranslation (ignored, owned by HyperGlow)")
        }

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
            // Same as above: romanization display is owned by HyperGlow's render modes.
            AppLog.i("LyriconLyricProducer", "onDisplayRomaChanged: $isDisplayRoma (ignored, owned by HyperGlow)")
        }
    }

    override fun start(context: Context) {
        if (started) {
            AppLog.i("LyriconLyricProducer", "start: already started (no-op)")
            return
        }
        started = true
        contextRef = context.applicationContext
        AppLog.i("LyriconLyricProducer", "start: api=${Build.VERSION.SDK_INT}")

        // API < 27: LyriconFactory returns EmptyLyriconSubscriber (no-op). Per spec, this
        // producer MUST be a no-op below API 27, so we skip registration entirely.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            AppLog.i("LyriconLyricProducer", "start: API < 27, producer is no-op")
            return
        }

        AppLog.i("LyriconLyricProducer", "start: creating subscriber")
        val sub = LyriconFactory.createSubscriber(context.applicationContext)
        subscriber = sub
        sub.addConnectionListener(connectionListener)
        val subscribed = sub.subscribeActivePlayer(playerListener)
        AppLog.i("LyriconLyricProducer", "start: subscribeActivePlayer=$subscribed")
        refreshRenderModes()
        sub.register()
        AppLog.i("LyriconLyricProducer", "start: registered with central service")
    }

    override fun stop() {
        if (!started) {
            AppLog.i("LyriconLyricProducer", "stop: not started (no-op)")
            return
        }
        started = false
        AppLog.i("LyriconLyricProducer", "stop: unregistering")
        subscriber?.let { sub ->
            runCatching {
                sub.unsubscribeActivePlayer(playerListener)
                sub.removeConnectionListener(connectionListener)
                sub.unregister()
                sub.destroy()
            }.onFailure { AppLog.w("LyriconLyricProducer", "stop: cleanup error", it) }
        }
        subscriber = null
        mutableConnection.value = ProducerConnection.DISCONNECTED
        mutableState.value = null
        AppLog.i("LyriconLyricProducer", "stop: done")
    }

    /**
     * Find the active line for [currentPositionMs] via [TimingNavigator], rebuild the per-word
     * cache only when the line changes, then emit a fresh [LyricProducerState].
     *
     * Called at ~60 Hz from [onPositionChanged]; the word-list allocation is amortized by
     * caching across position-only updates within the same line.
     */
    private fun recomputeAndEmit() {
        val nav = navigator ?: return emit() // no lyrics yet; emit metadata-only state
        val song = currentSong ?: return
        val pos = currentPositionMs
        val idx = nav.findTargetIndex(pos)
        if (idx < 0) {
            // Before the first line: no current line yet.
            if (currentLineIndex != -1) {
                currentLineIndex = -1
                cachedWords = null
            }
            emit()
            return
        }
        if (idx != currentLineIndex) {
            currentLineIndex = idx
            val line = nav.source[idx]
            cachedWords = line.toLyricWords()
            AppLog.i(
                "LyriconLyricProducer",
                "line changed: idx=$idx begin=${line.begin} end=${line.end} text=${line.text?.take(24)}"
            )
        }
        emit()
    }

    /**
     * Build and emit a [LyricProducerState] from the current ingress fields. Cheap: reuses
     * [cachedWords] (only rebuilt on line change) and [renderModesSnapshot] (only rebuilt on
     * song change). Safe to call at 60 Hz.
     */
    private fun emit() {
        val song = currentSong ?: run { mutableState.value = null; return }
        val now = clock()
        val line = currentLineIndex.let { idx ->
            if (idx < 0) null else navigator?.source?.getOrNull(idx)
        }
        sequence++
        mutableState.value = LyricProducerState(
            producerId = PRODUCER_ID,
            generation = generation,
            sequence = sequence,
            status = "ready",
            trackUri = "lyricon:${song.id ?: song.name}",
            title = song.name.orEmpty(),
            artist = song.artist.orEmpty(),
            album = "",
            imageId = "",
            line = line?.text.orEmpty(),
            romanizedLine = line?.roma.orEmpty(),
            translatedLine = line?.translation.orEmpty(),
            lineIndex = currentLineIndex,
            positionMs = currentPositionMs,
            durationMs = song.duration,
            sampledAtElapsedMs = now,
            speed = if (isPlayingState) 1f else 0f,
            playing = isPlayingState,
            receivedAtElapsedMs = now,
            words = cachedWords,
            renderModes = renderModesSnapshot
        )
    }

    /**
     * Refresh [renderModesSnapshot] from the AOD [CompiledSurfaceProfile]. Called on start and
     * song change — NOT at 60 Hz (compile is non-trivial). Per spec clause 5, the lyricon `Song`
     * carries no render modes, so they are sourced from HyperGlow's own customization.
     */
    @Synchronized
    private fun refreshRenderModes() {
        val ctx = contextRef ?: run {
            renderModesSnapshot = defaultRenderModes()
            return
        }
        renderModesSnapshot = runCatching {
            val compiled = CustomizationRepository.loadCompiled(ctx)
            val profile = compiled.profiles[SceneCompiler.SURFACE_AOD]
            profile?.toProducerRenderModes() ?: defaultRenderModes()
        }.onFailure {
            AppLog.w("LyriconLyricProducer", "refreshRenderModes failed, using defaults", it)
        }.getOrDefault(defaultRenderModes())
    }

    private fun CompiledSurfaceProfile.toProducerRenderModes() = ProducerRenderModes(
        weight = weight,
        textSize = textSize,
        textSizeCustom = textSizeCustom,
        secondary = secondaryMode,
        animation = animation,
        glow = glow,
        lineSyncFill = lineSyncFillMode,
        overflow = overflow,
        transition = transition.id,
        font = fontFamily
    )

    private fun RichLyricLine.toLyricWords(): List<LyricWord>? = words?.map { w ->
        // io.github.proify.lyricon.lyric.model.LyricWord has begin/end/text.
        // boundaryAfter is a Spicy-specific concept; default false (the engine treats word
        // boundaries from begin/end timing). roma is line-level (RichLyricLine.ruma), not per-word.
        LyricWord(
            text = w.text.orEmpty(),
            romanized = "",
            startMs = w.begin,
            endMs = w.end,
            boundaryAfter = false
        )
    }

    companion object {
        private const val PRODUCER_ID = "lyricon"

        /** Default render modes when customization is unavailable; matches SpicyBridgeState defaults. */
        private fun defaultRenderModes() = ProducerRenderModes(
            weight = "Medium",
            textSize = "normal",
            textSizeCustom = 100,
            secondary = "Main only",
            animation = "Karaoke fill",
            glow = "Off",
            lineSyncFill = "Top to bottom",
            overflow = "Wrap",
            transition = "Fade up",
            font = "spotify"
        )
    }
}
