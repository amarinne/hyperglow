---
title: "LyricProducer technical contract"
status: draft
tags:
  - "architecture"
  - "lyrics"
  - "provider"
  - "xposed"
---

## Purpose
Define the contract between HyperGlow's lyrics ingress (Spicy EX bridge, lyricon subscriber) and its projection consumers (`AodProjectionEngine`, `SystemUiLyricProjection`), so multiple producers feed the AOD/lockscreen surface through one boundary.

## Scope and Authority
In scope: the `LyricProducer` interface, the `LyricProducerState` it emits, the `LyricProducerArbiter` selection/fallback behavior, and lifecycle/error contracts. Out of scope: rendering inside projection consumers, lyricon SDK internals, Spicy EX internals. Authority: binds all code that produces or consumes lyric state on the projection path.

## Subject
The `LyricProducer` boundary: an abstraction over a lyrics source that emits a `LyricProducerState` observable, plus the arbiter that selects the active producer.

## Contract Surface
- `interface LyricProducer { val id: LyricSource; val state: StateFlow<LyricProducerState?>; val connection: StateFlow<ProducerConnection>; fun start(context: Context); fun stop() }`
- `enum LyricSource { SPICY, LYRICON }`
- `enum ProducerConnection { CONNECTED, RECONNECTED, DISCONNECTED, CONNECT_TIMEOUT }`
- `data class LyricProducerState(producerId, generation, sequence, title, artist, album, line, romanizedLine, translatedLine, words: List<LyricWord>?, lineIndex, positionMs, durationMs, sampledAtElapsedMs, speed, playing, renderModes: RenderModes, receivedAtElapsedMs)`
- `data class LyricWord(begin: Long, end: Long, text: String)`
- `class LyricProducerArbiter { val active: StateFlow<LyricProducerState?>; fun setPreference(source: LyricSource) }`
- Projection consumers depend on `LyricProducerArbiter.active`; they MUST NOT depend on `SpicyBridgeStore.state`.

## Normative Behavior
1. The arbiter MUST expose exactly one `active` state flow; at most one producer's state MUST be visible to projection consumers at any instant.
2. WHEN the user-selected producer reports `CONNECTED` or `RECONNECTED` and a non-stale state, the arbiter MUST forward that producer's state as `active`.
3. WHEN the selected producer reports `DISCONNECTED` or its state exceeds `STALE_AFTER_MS = 3000ms` (matching `SpicyBridgeStore.STALE_AFTER_MS`), the arbiter MUST clear `active` to null and MAY fall back to the next connected producer.
4. WHEN the user changes the source preference, the arbiter MUST stop emitting the previous producer's state within one frame and MUST begin emitting the newly selected producer's state only after it reports `CONNECTED`.
5. A `LyricProducer` MUST normalize its ingress payload into `LyricProducerState` before emitting; the `spotify:track:` constraint MUST remain internal to `SpicyBridgeStateReducer` and MUST NOT be re-imposed at the boundary.
6. The lyricon producer MUST compute the active `RichLyricLine` and per-word progress from `SharedMemory` position before emitting state, because lyricon delivers the full `Song`.
7. Render modes for the lyricon producer MUST be sourced from `CustomizationRepository` / `AodRenderPreferences`, because lyricon `Song` carries no render-mode fields.
8. Projection consumers MAY read `active` reactively but MUST NOT call producer methods directly.

## Constraints and Invariants
- Single-active-producer invariant: `active` equals producerA.state.value XOR producerB.state.value XOR null at every emission.
- Staleness threshold is uniform: `STALE_AFTER_MS = 3000ms` for both producers.
- The lyricon producer requires API >= 27; below it the producer MUST be a no-op (mirroring `LyriconFactory.createSubscriber` returning `EmptyLyriconSubscriber`).
- The lyricon producer requires lyricon's Xposed module active in `com.android.systemui`; its absence MUST NOT crash HyperGlow.

## Failure Behavior
1. IF the selected producer fails to connect within its timeout (lyricon: 3s x 3 retries per `CONNECT_TIMEOUT_MS` / `MAX_RETRY_COUNT`), THEN the arbiter MUST surface `CONNECT_TIMEOUT` to `RuntimeStatusPolicy` and clear `active`.
2. IF a producer receives a malformed payload, the producer MUST drop it internally and MUST NOT emit null `active` as a side effect; the previous valid state remains until staleness.
3. IF the active producer's host process (System UI) dies, the lyricon producer MUST report `DISCONNECTED` and the arbiter MUST clear `active` within `STALE_AFTER_MS`.

## Conformance
An implementation conforms when: (a) all Normative Behavior clauses hold under unit test with fixture-driven producers; (b) the single-active-producer invariant is asserted by an arbiter test covering selection, fallback, preference-switch, and timeout; (c) `./gradlew :app:testDebugUnitTest` passes with the Spicy EX path retaining pre-change behavior (no `spotify:track:` regression, no stuck lyric on producer loss).