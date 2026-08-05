---
title: "Implementation plan: lyricon lyrics source"
status: draft
tags:
  - "lyrics"
  - "provider"
  - "xposed"
---

## Goal

Implement lyricon as an alternative lyrics source for HyperGlow, consumed via lyricon's subscriber SDK, user-selectable, with graceful fallback to the existing Spicy EX path and no regression to it.

## Tasks

Phase 1 — Dependency & scaffold:
- Add the jitpack repository and lyricon subscriber + lyric-model artifacts (`0.1.71`) to @app/build.gradle.kts; confirm exact group/module coordinates.
- Add a `LyricSource` preference (SPICY, LYRICON) wired through the existing preferences surface.

Phase 2 — Generalized producer abstraction:
- Introduce a `LyricProducer` abstraction (active state: line, per-word progress, metadata, render modes, playing, position) backed by `SpicyBridgeStore` for the Spicy path.
- Refactor the projection consumers (`AodProjectionEngine`, `SystemUiLyricProjection`) to depend on a producer-agnostic observable rather than `SpicyBridgeStore` directly.
- Add a `LyricProducerArbiter` that selects the active producer per preference and switches on disconnect/failure.
- Lift the `spotify:track:` and `CallerValidator.isSpotify` constraints out of the shared path so a lyricon producer is not rejected.

Phase 3 — lyricon subscriber component:
- Implement `LyriconSubscriberController`: `LyriconFactory.createSubscriber(context)` → `register()` → `subscribeActivePlayer(listener)`; call `destroy()` on `HyperGlowApplication`/service teardown.
- Surface `ConnectionListener` state (onConnected/onReconnected/onDisconnected/onConnectTimeout) to `RuntimeStatusPolicy` and `DiagnosticsScreen`.
- Poll `IRemoteService.getActivePlayerPositionMemory()` (`SharedMemory`) for playback position.

Phase 4 — Song → projection adapter:
- Deserialize `onSongChanged(byte[])` into `Song`; map `RichLyricLine` (text/words/secondary/translation/roma) + `Song.name/artist/duration` into the generalized producer state.
- Active-line selection: given position from `SharedMemory`, find the `RichLyricLine` whose `[begin, end)` contains it; expose per-word karaoke progress from `LyricWord.begin/end`.
- Styling: source render modes from `CustomizationRepository` / `AodRenderPreferences`, not from the `Song`.

Phase 5 — Fallback & coexistence:
- API < 27 → rely on `EmptyLyriconSubscriber` no-op; lyricon inactive → disconnect → arbiter falls back to Spicy EX or idle; clear the AOD surface on source loss.
- Arbiter enforces single-active producer so both paths never drive the AOD surface simultaneously.

Phase 6 — Tests:
- Unit: `Song` fixture → generalized state; active-line selection across word/line boundaries; arbiter switching; staleness/timeout.
- Instrumented: serialized `Song` → AOD projection parity vs Spicy-EX-sourced rendering. [assumption] feasible without a live lyricon install using a fixture-driven subscriber stub.

## Acceptance Criteria

- User selects "lyricon" source; with lyricon active and a supported player playing, the AOD/lockscreen shows current line + karaoke + translation + transliteration.
- Switching source back to "Spicy EX" restores the existing Spotify path with no regression.
- lyricon absent/inactive or API < 27 → graceful idle/fallback; no crash, no stuck lyric on the AOD surface.
- `./gradlew :app:testDebugUnitTest` green, including new adapter/arbiter/selection tests.
- `./gradlew :app:assembleDebug` succeeds with the lyricon dependency resolved.

## Dependencies

- lyricon subscriber SDK + lyric-model artifacts `0.1.71` resolvable from jitpack. [assumption] exact artifact coordinates follow jitpack convention (`com.github.tomakino.lyricon:...`); confirm before Phase 1.
- lyricon Xposed module active in `com.android.systemui` on the target device for end-to-end lyrics flow (required for any real-device test).
- Root + LSPosed + (for the Spicy EX path) Spicy EX, per existing @README.md requirements.
- Android 8.1 (API 27) minimum for the lyricon subscriber path; HyperGlow's existing minSdk is unchanged for the Spicy EX path.