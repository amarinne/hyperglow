package com.eza.hyperglow.producer

import android.content.Context

/**
 * Process-wide holder for the [LyricProducerArbiter] and its two producers.
 *
 * Created once at app startup (see [HyperGlowApplication]); projection consumers read
 * [arbiter].[LyricProducerArbiter.active] instead of `SpicyBridgeStore.state` directly,
 * per the `lyric-producer-contract` spec.
 *
 * Phase 2 status: the arbiter is started and `active` is exposed, but `AodProjectionEngine`
 * still consumes `SpicyBridgeStore.state` for its `project()` internals (it needs the Spicy
 * document store for per-word timing). The engine's switch to `arbiter.active` as its sole
 * ingress is Phase 3, once the lyricon producer emits real state — doing it now would decouple
 * the engine from `SpicyBridgeDocumentStore` (the per-word karaoke source) prematurely.
 *
 * Until then, the Spicy producer wraps `SpicyBridgeStore.state` 1:1, so `arbiter.active`
 * mirrors `SpicyBridgeStore.state` for the Spicy path and the lyricon path is a no-op
 * (DISCONNECTED/null). This keeps the Spicy path regression-free while the boundary is in place.
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
