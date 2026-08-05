package com.eza.hyperglow.producer

import android.content.Context

/**
 * Process-wide holder for the [LyricProducerArbiter] and its two producers.
 *
 * Created once at app startup (see [HyperGlowApplication]); projection consumers read
 * [arbiter].[LyricProducerArbiter.active] instead of `SpicyBridgeStore.state` directly,
 * per the `lyric-producer-contract` spec.
 *
 * Phase 3 status: the lyricon producer now emits complete, engine-ready [LyricProducerState]
 * (active line + per-word progress via `TimingNavigator`, plus the row-level fields
 * `lyricKind`/`lineStartMs`/`lineEndMs`/`hasTimedLyrics`/`nextLineStartMs` — spec clause 6).
 * The Spicy producer still wraps `SpicyBridgeStore.state` 1:1 and emits the row-level fields at
 * defaults; its per-word timing lives in `SpicyBridgeDocumentStore`, which `AodProjectionEngine`
 * still reads directly for its `project()` internals on the Spicy path (spec clause 9 — the one
 * remaining deviation).
 *
 * The engine's switch to `arbiter.active` as its sole ingress is the final step: it requires
 * the Spicy producer to populate the row-level fields from `SpicyBridgeDocumentStore` (computing
 * the active row via `primaryRowAt`) so the engine can stop reading the document store. Until
 * then `arbiter.active` mirrors `SpicyBridgeStore.state` for the Spicy path, keeping it
 * regression-free.
 */
object LyricProducers {
    @Volatile private var instance: LyricProducerArbiter? = null

    val arbiter: LyricProducerArbiter
        get() = instance ?: error("LyricProducers not started; call start(context) first")

    @Synchronized
    fun start(context: Context) {
        if (instance != null) return
        val spicy = SpicyLyricProducer()
        val lyricon = LyriconLyricProducer()
        val arbiter = LyricProducerArbiter(spicy, lyricon)
        arbiter.start(context.applicationContext)
        instance = arbiter
    }

    /** Test/preview accessor; returns null before [start]. */
    fun arbiterOrNull(): LyricProducerArbiter? = instance
}
