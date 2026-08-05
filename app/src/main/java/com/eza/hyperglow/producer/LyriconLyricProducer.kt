package com.eza.hyperglow.producer

import android.content.Context
import android.os.Build
import com.eza.hyperglow.AppLog
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [LyricProducer] backed by the lyricon subscriber SDK.
 *
 * Phase 2 scope: lifecycle, connection mapping, and the listener surface are wired against the
 * real SDK API. The heavy work — polling `SharedMemory` for position and computing the active
 * [RichLyricLine] + per-word progress — is intentionally left as TODO for Phase 3, because it
 * requires the lyricon Xposed module to be active in `com.android.systemui` to validate.
 *
 * Contract notes (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * - Requires API >= 27 (O_MR1). Below that, `LyriconFactory.createSubscriber` returns
 *   `EmptyLyriconSubscriber`, so this producer is a no-op (spec: API<27 → no-op).
 * - Requires lyricon's Xposed module active in SystemUI; its absence MUST NOT crash HyperGlow.
 *   Until connected, [connection] stays DISCONNECTED and [state] stays null, so the arbiter
 *   falls back to the Spicy producer automatically.
 * - [connection] is driven 1:1 by [ConnectionListener] callbacks.
 * - Render modes are sourced from [AodRenderPreferences] / [CustomizationRepository] (the
 *   lyricon `Song` carries no render-mode fields).
 *
 * Why a skeleton now: the boundary (LyricProducer) and arbitration (LyricProducerArbiter) can
 * be validated with the Spicy path alone; the lyricon producer plugs in without touching
 * projection code once its ingress is implemented.
 */
class LyriconLyricProducer : LyricProducer {

    override val id: LyricSource = LyricSource.LYRICON

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    private val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private var subscriber: LyriconSubscriber? = null
    private var contextRef: Context? = null
    private var positionPollJob: Job? = null
    private var started = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
                mutableState.value = null
            }
        }

        override fun onSongChanged(song: Song?) {
            if (song == null) {
                AppLog.i("LyriconLyricProducer", "onSongChanged: null (cleared)")
                currentSong = null
                mutableState.value = null
                return
            }
            AppLog.i(
                "LyriconLyricProducer",
                "onSongChanged: id=${song.id} name=${song.name} artist=${song.artist} " +
                    "duration=${song.duration}ms lines=${song.lyrics?.size ?: 0}"
            )
            currentSong = song
            // TODO(phase3): emit an initial LyricProducerState from `song` using the last known
            // position; the position-poll loop below keeps it updated. For now we only stash the
            // song so the poll loop (once implemented) can compute the active line.
        }

        override fun onReceiveText(text: String?) {
            // Plain-text lyrics (no timestamps). Out of scope for karaoke AOD; ignore.
            AppLog.i("LyriconLyricProducer", "onReceiveText: len=${text?.length} (ignored)")
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            AppLog.i("LyriconLyricProducer", "onPlaybackStateChanged: playing=$isPlaying")
            isPlayingState = isPlaying
            // TODO(phase3): re-emit state with updated `playing`; position projection in the
            // arbiter/engine already handles paused vs playing via `speed`.
        }

        override fun onPositionChanged(position: Long) {
            // Position is also delivered via SharedMemory polling in LyriconSubscriberImpl; this
            // callback is the low-frequency signal. The high-frequency poll is started in start().
            AppLog.i("LyriconLyricProducer", "onPositionChanged: pos=${position}ms")
            currentPositionMs = position
            // TODO(phase3): recompute active RichLyricLine for `position` and emit state.
        }

        override fun onSeekTo(position: Long) {
            AppLog.i("LyriconLyricProducer", "onSeekTo: pos=${position}ms")
            currentPositionMs = position
            // TODO(phase3): immediately re-emit (seek invalidates projected position).
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
            // HyperGlow controls translation display via its own CustomizationRepository; ignore
            // the lyricon-side toggle to avoid double-toggling.
        }

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
            // Same as above: romanization display is owned by HyperGlow's render modes.
        }
    }

    // --- Ingress state, updated by playerListener. Phase 3 reads these in the poll loop. ---
    @Volatile private var currentSong: Song? = null
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var isPlayingState: Boolean = false

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
        sub.register()
        startPositionPoll()
        AppLog.i("LyriconLyricProducer", "start: registered with central service, polling started")
    }

    override fun stop() {
        if (!started) {
            AppLog.i("LyriconLyricProducer", "stop: not started (no-op)")
            return
        }
        started = false
        AppLog.i("LyriconLyricProducer", "stop: cancelling poll + unregistering")
        positionPollJob?.cancel(); positionPollJob = null
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
        scope.cancel()
        AppLog.i("LyriconLyricProducer", "stop: done")
    }

    /**
     * Phase 3: poll the subscriber's SharedMemory for high-frequency position updates, compute
     * the active [RichLyricLine] from `currentSong.lyrics` for `currentPositionMs`, and emit a
     * [LyricProducerState] with per-word [LyricWord] timing. Render modes come from
     * [AodRenderPreferences] / [CustomizationRepository].
     *
     * Skeleton today: no-op loop that keeps the coroutine alive without emitting, so the
     * producer stays DISCONNECTED/null-state and the arbiter cleanly falls back to Spicy.
     */
    private fun startPositionPoll() {
        positionPollJob = scope.launch {
            while (isActive) {
                // TODO(phase3):
                //   1. Read position from SharedMemory (subscriber.positionBytes / mapped buffer)
                //      — faster than onPositionChanged for smooth karaoke.
                //   2. val song = currentSong ?: continue
                //   3. val line = song.lyrics?.primaryRowAt(currentPositionMs) ?: continue
                //   4. val words = line.words?.map { it.toLyricWord() }
                //   5. val renderModes = AodRenderPreferences.read(contextRef) + CustomizationRepository.snapshot()
                //   6. mutableState.value = buildState(song, line, words, renderModes)
                kotlinx.coroutines.delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    @Suppress("unused") // referenced by Phase 3 TODO above
    private fun RichLyricLine.toLyricWords(): List<LyricWord>? = words?.map { w ->
        // io.github.proify.lyricon.lyric.model.LyricWord has begin/end/text; boundaryAfter is
        // a Spicy-specific concept and defaults to false here.
        LyricWord(
            text = w.text.orEmpty(),
            romanized = "", // roma lives at RichLyricLine.ruma level, not per-word
            startMs = w.begin,
            endMs = w.end,
            boundaryAfter = false
        )
    }

    companion object {
        /** High-frequency position poll interval. Matches AodProjectionEngine's 100ms tick. */
        private const val POSITION_POLL_INTERVAL_MS = 100L
    }
}
