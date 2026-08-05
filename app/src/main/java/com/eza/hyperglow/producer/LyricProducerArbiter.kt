package com.eza.hyperglow.producer

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Selects which [LyricProducer] feeds the projection pipeline, enforcing the single-active-
 * producer invariant from the contract spec.
 *
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * 1. Exposes exactly one [active] flow; at most one producer's state is visible at any instant.
 * 2. WHEN the selected producer is CONNECTED/RECONNECTED and non-stale, forward its state.
 * 3. WHEN the selected producer is DISCONNECTED or its state is stale, clear `active` to null
 *    and MAY fall back to the next connected producer.
 * 4. WHEN the user changes preference, stop emitting the previous producer's state within one
 *    frame and begin emitting the newly selected producer's state only after it reports
 *    CONNECTED.
 *
 * The arbiter is the only entry point projection consumers (AodProjectionEngine) should read;
 * they MUST NOT read SpicyBridgeStore.state directly.
 *
 * Threading: all producer state observations happen on [Dispatchers.Default]; [active] is a
 * [StateFlow] so collectors are race-free. [setPreference] is thread-safe.
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so staleness can be advanced without Android.
 */
class LyricProducerArbiter(
    private val spicy: LyricProducer,
    private val lyricon: LyricProducer,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    /**
     * The single active producer state consumed by projection. Null means "no current lyrics"
     * (no producer connected, or the connected producer's state is stale).
     *
     * Invariant: at every emission, this equals exactly one of the producers' current state
     * (the active one) or null — never a mix.
     */
    val active: StateFlow<LyricProducerState?> get() = mutableActive.asStateFlow()

    /** The currently selected source. Drives which producer is forwarded when healthy. */
    val preference: StateFlow<LyricSource> get() = mutablePreference.asStateFlow()

    private val mutableActive = MutableStateFlow<LyricProducerState?>(null)
    private val mutablePreference = MutableStateFlow(LyricSource.SPICY)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var arbitrationJob: Job? = null
    private var staleSweepJob: Job? = null
    private var started = false

    /**
     * Start observing producers. Idempotent. Called once at app startup
     * (see HyperGlowApplication, wired in Phase 2 integration).
     */
    fun start(context: Context) {
        if (started) return
        started = true
        spicy.start(context)
        lyricon.start(context)
        arbitrationJob = scope.launch { arbitrateLoop(context) }
        staleSweepJob = scope.launch { staleSweepLoop() }
        AppLog.i("LyricProducerArbiter", "started")
    }

    /** Stop observing and clear active state. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        arbitrationJob?.cancel(); arbitrationJob = null
        staleSweepJob?.cancel(); staleSweepJob = null
        spicy.stop()
        lyricon.stop()
        mutableActive.value = null
        scope.cancel()
        AppLog.i("LyricProducerArbiter", "stopped")
    }

    /**
     * Change the preferred source. Per spec: stops emitting the previous producer's state
     * within one frame; begins emitting the newly selected producer's state only after it
     * reports CONNECTED. The next arbitration tick applies the switch.
     */
    fun setPreference(source: LyricSource) {
        if (mutablePreference.value == source) return
        AppLog.i("LyricProducerArbiter", "preference ${mutablePreference.value} -> $source")
        // Clear immediately so a stale state from the old producer cannot leak during the
        // gap before the new producer reports CONNECTED (spec: stop within one frame).
        mutableActive.value = null
        mutablePreference.value = source
    }

    private suspend fun arbitrateLoop(context: Context) {
        // Collect both producers' connection and state, recomputing `active` on any change.
        // We re-read .value on each tick rather than combine() to keep the staleness check
        // time-aware (combine would not re-emit purely due to elapsed time).
        var lastActiveSignature: String? = null
        while (scope.isActive) {
            val next = computeActiveOnce()
            // Only write on actual change to avoid redundant StateFlow emissions.
            val sig = next?.let { "${it.producerId}:${it.generation}:${it.sequence}" }
            if (sig != lastActiveSignature) {
                AppLog.i(
                    "LyricProducerArbiter",
                    "active changed: ${lastActiveSignature ?: "null"} -> ${sig ?: "null"}"
                )
                mutableActive.value = next
                lastActiveSignature = sig
            }
            delay(ARBITRATION_TICK_MS)
        }
    }

    /**
     * Single arbitration pass: returns the state that should be `active` right now, based on
     * the current preference, both producers' connection/state, and the staleness clock.
     *
     * Exposed for unit tests so the selection/fallback/switch logic can be verified
     * deterministically without driving the async loop.
     */
    internal fun computeActiveOnce(): LyricProducerState? {
        val pref = mutablePreference.value
        val preferred = producer(pref)
        val preferredConn = preferred.connection.value
        val preferredState = preferred.state.value
        val now = clock()
        return when {
            preferredConn == ProducerConnection.CONNECTED ||
                preferredConn == ProducerConnection.RECONNECTED -> {
                if (preferredState != null && !isStale(preferredState)) {
                    AppLog.i(
                        "LyricProducerArbiter",
                        "select: pref=$pref conn=$preferredConn producer=${preferredState.producerId} " +
                            "gen=${preferredState.generation} seq=${preferredState.sequence} " +
                            "age=${now - preferredState.receivedAtElapsedMs}ms"
                    )
                    preferredState
                } else {
                    // Preferred connected but no/stale state: try fallback before null.
                    val reason = when {
                        preferredState == null -> "nullState"
                        isStale(preferredState) -> "stale(age=${now - preferredState.receivedAtElapsedMs}ms)"
                        else -> "unknown"
                    }
                    AppLog.i(
                        "LyricProducerArbiter",
                        "select: pref=$pref conn=$preferredConn but $reason -> fallback"
                    )
                    fallbackState(pref)
                }
            }
            else -> {
                // Preferred disconnected/timeout: fall back to the other producer.
                AppLog.i(
                    "LyricProducerArbiter",
                    "select: pref=$pref conn=$preferredConn (not connected) -> fallback"
                )
                fallbackState(pref)
            }
        }
    }

    private fun fallbackState(excluded: LyricSource): LyricProducerState? {
        val otherSource = other(excluded)
        val other = producer(otherSource)
        val otherConn = other.connection.value
        if (otherConn != ProducerConnection.CONNECTED &&
            otherConn != ProducerConnection.RECONNECTED) {
            AppLog.i(
                "LyricProducerArbiter",
                "fallback: $otherSource conn=$otherConn (not connected) -> null"
            )
            return null
        }
        val otherState = other.state.value
        if (otherState == null) {
            AppLog.i("LyricProducerArbiter", "fallback: $otherSource connected but nullState -> null")
            return null
        }
        val now = clock()
        return if (isStale(otherState)) {
            AppLog.i(
                "LyricProducerArbiter",
                "fallback: $otherSource stale(age=${now - otherState.receivedAtElapsedMs}ms) -> null"
            )
            null
        } else {
            AppLog.i(
                "LyricProducerArbiter",
                "fallback: $otherSource producer=${otherState.producerId} " +
                    "gen=${otherState.generation} seq=${otherState.sequence} " +
                    "age=${now - otherState.receivedAtElapsedMs}ms"
            )
            otherState
        }
    }

    private fun staleSweepLoop() = scope.launch {
        // Independently clear `active` if the currently-forwarded state goes stale between
        // arbitration ticks (e.g. producer stopped emitting but didn't disconnect).
        while (scope.isActive) {
            val current = mutableActive.value
            if (current != null && isStale(current)) {
                AppLog.i("LyricProducerArbiter", "active state went stale, clearing")
                mutableActive.value = null
            }
            delay(STALE_SWEEP_TICK_MS)
        }
    }

    private fun producer(source: LyricSource): LyricProducer =
        if (source == LyricSource.SPICY) spicy else lyricon

    private fun other(source: LyricSource): LyricSource =
        if (source == LyricSource.SPICY) LyricSource.LYRICON else LyricSource.SPICY

    private fun isStale(state: LyricProducerState): Boolean {
        // Uniform staleness: now - receivedAtElapsedMs > staleAfterMs.
        // receivedAtElapsedMs uses the same clock (SystemClock.elapsedRealtime in production).
        val now = clock()
        return now - state.receivedAtElapsedMs > state.staleAfterMs
    }

    companion object {
        /** How often the arbitration loop re-evaluates which producer is active. */
        private const val ARBITRATION_TICK_MS = 100L
        /** How often the stale-sweep checks the forwarded state. */
        private const val STALE_SWEEP_TICK_MS = 500L
    }
}
