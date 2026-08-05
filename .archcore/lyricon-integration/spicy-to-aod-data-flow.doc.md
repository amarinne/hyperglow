---
title: "Spicy EX to AOD projection data-flow reference"
status: draft
tags:
  - "architecture"
  - "lyrics"
  - "provider"
  - "xposed"
---

## Overview
This reference documents the current end-to-end data flow that carries lyrics from the Spicy EX producer to the HyperOS AOD/lockscreen surface in HyperGlow. It spans two processes: the HyperGlow app process (Spicy EX ingress → projection) and the System UI process (Xposed-injected rendering). It is the baseline the `LyricProducer` abstraction (see @.archcore/lyricon-integration/lyric-producer-contract.spec.md) refactors, and the reference for the lyricon producer integration.

## Content

### Stage map

| Stage | Process | Component | File | Responsibility |
|---|---|---|---|---|
| 1 Ingress | app | `SpicyLyricBridgeProvider` / `SpicyLyricBridgeService` | @app/src/main/java/com/eza/hyperglow/bridge/SpicyLyricBridgeProvider.kt, @app/src/main/java/com/eza/hyperglow/bridge/SpicyLyricBridgeService.kt | Receive Spicy EX `Bundle` via ContentProvider `call()` and AIDL `ISpicyLyricBridge`; gate on `CallerValidator.isSpicy`; forward to store |
| 2 State store | app | `SpicyBridgeStore` + `SpicyBridgeStateReducer` | @app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt | Validate generation/sequence ordering, enforce `spotify:track:` prefix, staleness (`STALE_AFTER_MS = 3000ms`), expose `StateFlow<SpicyBridgeState?>` |
| 2b Document store | app | `SpicyBridgeDocumentStore` | @app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt | Hold timed-lyrics document (Line/Syllable rows) keyed by `producerId`/`generation`; consumed at projection time |
| 3 Projection engine | app | `AodProjectionEngine` | @app/src/main/java/com/eza/hyperglow/aod/AodProjectionEngine.kt | Combine `SpicyBridgeStore.state` + `SpicyBridgeDocumentStore.state`; pick active row from projected position; build `AodDisplayState`; publish to bridge |
| 4 Wire encode | app | `AodStateBridge` + `AodStateWireCodec` | @app/src/main/java/com/eza/hyperglow/aod/AodStateBridge.kt, @app/src/main/java/com/eza/hyperglow/aod/AodStateWire.kt | Normalize `AodDisplayState` → `AodStateWireMessage` (Snapshot/Hidden/KeepAlive, protocol v2) → `Bundle`; broadcast to registered `IAodLyricCallback`s via `RemoteCallbackList` |
| 5 IPC crossing | app↔SystemUI | `IAodLyricBridge` AIDL + `IAodLyricCallback` | @app/src/main/aidl/com/eza/hyperglow/aod/IAodLyricBridge.aidl, @app/src/main/aidl/com/eza/hyperglow/aod/IAodLyricCallback.aidl | Binder service `AodLyricBridgeService` (app) + callback stub (SystemUI side); one-way state/configuration delivery |
| 6 Wire client | SystemUI | `AodLyricClient` | @app/src/main/java/com/eza/hyperglow/root/aod/AodLyricClient.kt | `bindServiceAsUser` to `com.eza.hyperglow/.aod.AodLyricBridgeService`; decode `Bundle` on the Binder thread into immutable `AodStateWireMessage`; generation-bound handoff to main thread |
| 7 Projection aggregator | SystemUI | `SystemUiLyricProjection` | @app/src/main/java/com/eza/hyperglow/root/projection/SystemUiLyricProjection.kt | Order messages by `revision`/`updatedAtElapsedMs`; convert to `LyricSnapshot`; fan-out to `SystemUiLyricSubscriber`s; schedule stale expiry (`LYRIC_SNAPSHOT_FRESH_MS = 5000ms`) |
| 8 Renderers | SystemUI | `AodLyricCanvasView`, `AodSpicyAnimationView`, lockscreen hooks | @app/src/main/java/com/eza/hyperglow/root/aod/AodLyricCanvasView.kt, etc. | Consume `LyricSnapshot` via `onLyricSnapshot`; draw karaoke fill, words, ruby, layout groups; honor keep-alive and burn-in |

### Data shapes at each stage

- Stage 1 payload: a `Bundle` decoded by `SpicyBridgeStatePayload.from(bundle)` — keys: `protocolVersion`, `producerId`, `generation`, `sequence`, `status`, `trackUri`, `title`, `artist`, `album`, `imageId`, `line`, `romanizedLine`, `translatedLine`, `lineIndex`, `positionMs`, `durationMs`, `sampledAtElapsedMs`, `speed`, `playing`, plus render-mode keys (`liveCardWeight`, `liveCardTextSize`, `liveCardAnimation`, `lyricsFont`, ...).
- Stage 2 state: `SpicyBridgeState` (same fields + `receivedAtElapsedMs` + normalized `liveCard*` render modes). Reducer rejects when `trackUri` does not start with `spotify:track:`, `positionMs > durationMs`, `sampledAtElapsedMs` older than `MAX_SAMPLE_AGE_MS = 60000ms`, or generation/sequence not strictly newer.
- Stage 3 output: `AodDisplayState` (@app/src/main/java/com/eza/hyperglow/aod/AodStateBridge.kt) — a flat data class carrying `original`/`romanized`/`translated` text, `words: List<AodDisplayWord>`, `ruby`, `layoutGroups`, position/duration/speed, burn-in config, and the full set of style modes (`weight`, `textSizeMode`, `animationMode`, `glowMode`, `lineSyncFillMode`, `overflowMode`, `transitionMode`, `fontFamily`, `alignmentMode`, ...).
- Stage 4 wire: `AodStateWireMessage` sealed type — `Snapshot` (full `AodStateWireSnapshot`), `Hidden` (no content, lifecycle flags only), `KeepAlive` (refreshes `updatedAtElapsedMs` + `keepAlive`/`wakeSignal`/`playbackActive`/`pauseRetentionEligible` without resending lyric text). Protocol version 2; body capped at `MAX_ENCODED_BODY_BYTES = 64KB`, aggregate text at `MAX_AGGREGATE_TEXT_UTF8_BYTES = 48KB`, `MAX_WORDS = 128`, `MAX_RUBY = 128`, `MAX_LAYOUT_GROUPS = 256`, `MAX_LYRIC_CHARS = 500`, `MAX_METADATA_CHARS = 200`, `MAX_MEDIA_DURATION_MS = 24h`, `MAX_PLAYBACK_SPEED = 4x` (@app/src/main/java/com/eza/hyperglow/aod/AodStateWire.kt `AodStateWireLimits`).
- Stage 7 snapshot: `LyricSnapshot` (@app/src/main/java/com/eza/hyperglow/root/projection/LyricSnapshot.kt) — mirrors the wire snapshot plus `revision`/`userId`/`updatedAtElapsedMs`; `freezeAt(now, keepAlive)` projects position forward and zeroes speed for frozen presentation.

### Key control flows in `AodProjectionEngine`

- Position projection: `projectedPosition(state, now)` = `positionMs + ((now - sampledAtElapsedMs) * speed)`, clamped to `[0, durationMs]` (@app/src/main/java/com/eza/hyperglow/aod/AodProjectionEngine.kt). Playing-only; paused state uses `positionMs` as-is.
- Active-row selection: `timedDocument.primaryRowAt(position)` (Line/Syllable documents only); falls back to `state.line` when no document is present and status is `ready`; `♪` placeholder for `loading`/`no_lyrics`/interlude.
- Republish gate: `shouldRepublish` (@app/src/main/java/com/eza/hyperglow/aod/AodStateBridge.kt) suppresses duplicates — republish only when non-position fields change or projected position drifts > 750ms from expected.
- Pause handling: a non-playing edge is provisional. `schedulePauseConfirmation` keeps playback active for `PAUSE_CONFIRM_MS = 1500ms` to bridge Spotify's ~1s gap before a track change; only a still-non-playing same-session state after the window commits to pause retention (`confirmedPauseSession`).
- Scheduler loop: when playing and `status == ready`, a 100ms tick loop (`ensureScheduler`) re-projects and republishes; keep-alive to `AodStateBridge.refreshVisibleState()` every `KEEP_ALIVE_INTERVAL_MS = 4000ms`. Loading/no-lyrics fallback uses `startStatusKeepAlive` at `FALLBACK_REFRESH_INTERVAL_MS = 1000ms`.
- Release: `TRANSITION_GRACE_MS = 1500ms` deferred release on null state; `releaseNow` publishes a `Hidden` `AodDisplayState` and invalidates the publication guard.
- Publication guard: `ProjectionPublicationGuard` uses a generation counter + `ProjectionSessionIdentity` (`producerId:generation:trackUri`) so a stale coroutine cannot overwrite a newer session's projection; `canPublish` also checks the candidate is still the current store value and the captured document has not changed.
- Customization refresh: `publishCustomizationIfDue` republishes `CompiledCustomization` every `CUSTOMIZATION_REFRESH_MS = 1000ms`; delivered to SystemUI via `AodStateBridge.publishConfiguration` (hash-deduplicated) and applied in `SystemUiLyricProjection.acceptConfiguration` (also toggles `RaiseToAod`, `DiagnosticLogging`, `LockscreenEditorGestureController`).

### SystemUI-side ordering and expiry

- `SystemUiLyricProjection.accept` rejects messages with `revision < lastRevision`, or equal revision with `updatedAtElapsedMs <= lastUpdatedAt`; rejects messages whose `userId` ≠ `expectedUserId` (per-user isolation).
- `LYRIC_SNAPSHOT_FRESH_MS = 5000ms` freshness: `scheduleExpiry` arms a main-thread `Handler` callback to `expireIfStale`, which clears the cached snapshot and notifies subscribers `onLyricProjectionStale` — distinct from the app-side 3000ms staleness, this governs how long SystemUI shows a lyric without a fresh message.
- `isPlausibleWireTimestamp` rejects messages more than `MAX_WIRE_FUTURE_SKEW_MS = 1000ms` ahead of `elapsedRealtime()` (clock-skew guard).
- On disconnect (`onBindingDied`/`onNullBinding`/`onServiceDisconnected`), `AodLyricClient.resetBindingAndRetry` unbinds, calls `onDisconnected`, and retries bind with exponential backoff `retryDelayMs` (1s base, 30s cap) — so SystemUI re-binds if the app service is replaced.

### Where the lyricon producer plugs in

The `LyricProducer` boundary (per the contract spec) sits between Stage 2 and Stage 3: `AodProjectionEngine` will consume `LyricProducerArbiter.active` instead of `SpicyBridgeStore.state` directly. Stages 3–8 (projection, wire encode, IPC, SystemUI rendering) are producer-agnostic and need no change for the lyricon path. The lyricon producer is responsible for Stage-1-and-2-equivalent work: deserialize `Song`, compute the active `RichLyricLine` from playback position (delivered via `ActivePlayerListener.onPositionChanged` ~60 Hz — the SDK polls its internal `SharedMemory` itself; the producer does not access `SharedMemory` directly), and emit a `LyricProducerState` whose shape `AodProjectionEngine` can project the same way it projects `SpicyBridgeState` today.

## Examples

### Republish-suppression example
`shouldRepublish(last, next)` returns false when only `positionMs`/`sampledAtElapsedMs` moved and the actual drift from expected is ≤ 750ms. This bounds Binder traffic during the 100ms scheduler tick to roughly one publish per 750ms of position drift, not 10/sec.

### Hidden-vs-KeepAlive example
When the user pauses, `releaseNow(playbackActive=false)` sends a `Hidden` message (clears the surface). When playback resumes within the same session, the scheduler republishes a `Snapshot`. `refreshVisibleState()` sends a `KeepAlive` (no lyric text, just updated timestamps and lifecycle flags) to extend SystemUI freshness without re-encoding the full snapshot.