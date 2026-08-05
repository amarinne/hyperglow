---
title: "Product requirements: lyricon lyrics source"
status: draft
tags:
  - "lyrics"
  - "provider"
  - "xposed"
---

## Vision

HyperGlow renders any music player's lyrics on the HyperOS lock screen and AOD — not just Spotify. By subscribing to lyricon's aggregated lyrics feed, HyperGlow inherits lyricon's multi-player provider ecosystem while retaining its own AOD rendering, customization, and burn-in/placement features.

## Problem Statement

HyperGlow's lyrics input is hard-coupled to Spicy EX hooking Spotify. `CallerValidator.isSpotify` (@app/src/main/java/com/eza/hyperglow/bridge/CallerValidator.kt) restricts bridge callers to `com.spotify.music`, and `SpicyBridgeStateReducer.accept` (@app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt) rejects any `trackUri` not starting with `spotify:track:`. Users of the players lyricon already supports (光锥音乐, BBPlayer, Kanade, QZ Music, LunaBeat, etc.) cannot use HyperGlow. There is no abstraction for an alternative producer: the bridge, the state store, and the projection consumers all assume the Spicy EX payload shape.

## Goals and Success Metrics

Goal: add lyricon as a selectable lyrics source in HyperGlow, consuming lyrics via lyricon's subscriber SDK, with the existing Spicy EX path preserved as a peer source.

Success metrics:
- With lyricon's Xposed module active and a supported player playing, HyperGlow's AOD/lockscreen renders the current lyric line, karaoke fill, translation, and transliteration. [assumption] Rendering parity with the Spicy-EX-sourced path, verified by an instrumented test driven by a serialized `Song` fixture.
- After selecting "lyricon" as source and launching a supported player, lyrics appear within one connection cycle (lyricon `CONNECT_TIMEOUT_MS` = 3s, up to 3 retries). [assumption] measured end-to-end on a Xiaomi 14 (`houji`).
- When lyricon is absent/inactive or the device is below API 27, HyperGlow falls back to the Spicy EX source with no regression: no crash, no stale lyric stuck on the AOD surface.
- `./gradlew :app:testDebugUnitTest` is green, with new coverage for `Song`-to-state adaptation, active-line selection from position, and producer arbitration.

## Requirements

- R1 — Integrate lyricon subscriber SDK + lyric-model artifacts (`0.1.71`) via jitpack in @app/build.gradle.kts.
- R2 — A lyrics-source selector (Spicy EX / lyricon), persisted in HyperGlow preferences and exposed in the UI.
- R3 — A lyricon subscriber component that registers, subscribes to the active player, and surfaces connection state (connected/reconnected/disconnected/timeout) to `RuntimeStatusPolicy` and the diagnostics UI.
- R4 — An adapter translating lyricon `Song` + `SharedMemory` position into the state consumed by `AodProjectionEngine` / `SystemUiLyricProjection`, including active-line and per-word karaoke selection from `RichLyricLine.begin/end` and `LyricWord.begin/end`.
- R5 — A generalized producer abstraction replacing the Spotify-only `CallerValidator` + `spotify:track:` constraint, with an arbiter that picks the active producer per the selector and recovers on producer loss.
- R6 — Graceful degradation: lyricon inactive/absent or API < 27 → no-op subscriber, fall back to Spicy EX or idle, and clear the AOD surface on source loss (no stuck lyric).
- R7 — Styling for lyricon-sourced lyrics taken from HyperGlow's `CustomizationRepository` / `AodRenderPreferences`, since lyricon `Song` carries no render modes.
- R8 — No regression to the Spicy EX path; the arbiter MUST NOT let both producers drive the AOD surface simultaneously.