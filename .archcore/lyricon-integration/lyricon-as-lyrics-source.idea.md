---
title: "Use lyricon as an alternative lyrics source for HyperGlow"
status: draft
tags:
  - "lyrics"
  - "provider"
  - "xposed"
---

## Idea

HyperGlow today receives lyrics only from Spicy EX, which hooks Spotify exclusively. The constraint is enforced twice: `CallerValidator.isSpotify` gates both bridge entry points (@app/src/main/java/com/eza/hyperglow/bridge/SpicyLyricBridgeProvider.kt, @app/src/main/java/com/eza/hyperglow/bridge/SpicyLyricBridgeService.kt), and `SpicyBridgeStateReducer.accept` rejects any `trackUri` not starting with `spotify:track:` (@app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt). Users running other music players get no lyrics.

lyricon (`io.github.proify.lyricon`) is an Xposed status-bar-lyrics module whose provider-plugin ecosystem (LyricProvider) already adapts many players (光锥音乐, Flamingo, BBPlayer, MobiMusic, Kanade, QZ Music, 棉花音乐, LunaBeat, and others). lyricon exposes a subscriber SDK: an external app registers against lyricon's central service (Xposed-injected into `com.android.systemui`) and receives the active player's `Song` plus playback position.

This idea: make HyperGlow a *subscriber* of lyricon's central service, so any player lyricon supports becomes a lyrics source for HyperGlow's AOD/lockscreen projection — alongside the existing Spicy EX path, not replacing it.

## Value

- Decouples HyperGlow's lyrics supply from Spotify/Spicy EX. One integration inherits lyricon's entire multi-player provider ecosystem.
- Users keep HyperGlow's AOD/lockscreen rendering (line/word/syllable karaoke, transliteration, translation, burn-in movement, placement, raise-to-AOD) while sourcing lyrics from whichever player lyricon has adapted.
- No requirement on Spicy EX for non-Spotify users; Spicy EX remains the path for Spotify.

## Possible Implementation

Depend on lyricon's subscriber SDK + lyric-model artifacts (version `0.1.71`, published via jitpack per `com.vanniktech.maven.publish` in lyricon's `settings.gradle.kts`). Drive the subscriber from `LyriconFactory.createSubscriber(context)` → `register()` → `subscribeActivePlayer(listener)` against the `LyriconSubscriber` API. In `IActivePlayerListener.onSongChanged(byte[])`, deserialize `Song` (kotlinx.serialization) and translate it into the state consumed by `AodProjectionEngine` / `SystemUiLyricProjection` (@app/src/main/java/com/eza/hyperglow/aod/AodProjectionEngine.kt, @app/src/main/java/com/eza/hyperglow/root/projection/SystemUiLyricProjection.kt). Track the active line from position read via `IRemoteService.getActivePlayerPositionMemory()` (a `SharedMemory`) against `RichLyricLine.begin/end`. Generalize the bridge beyond the Spotify-only `CallerValidator` into a multi-producer arbiter (Spicy EX vs lyricon). [assumption] A `LyricProducer` abstraction wrapping `SpicyBridgeStore` for the Spicy path and a new lyricon producer is the right factoring; exact shape deferred to the spec.

## Risks and Constraints

- lyricon's lyrics IPC is Xposed-mediated: the central service runs inside `com.android.systemui`, placed by lyricon's Xposed module. If that module is inactive, the directed-broadcast handshake (action `io.github.proify.lyricon.lyric.bridge.REGISTER_SUBSCRIBER`) is unhandled and no lyrics flow. There is no standard-Android (ContentProvider/exported-service) fallback. HyperGlow MUST degrade gracefully.
- Requires Android 8.1 (API 27, `O_MR1`); below it `LyriconFactory.createSubscriber` returns an `EmptyLyriconSubscriber` no-op.
- Model gap: lyricon `Song` carries no render modes (weight/textSize/animation/glow/font/transition). `SpicyBridgeRenderModes` today arrives from Spicy EX. lyricon-sourced lyrics MUST be styled from HyperGlow's own `CustomizationRepository` / `AodRenderPreferences`.
- lyricon delivers the full `Song` (a `List<RichLyricLine>`); Spicy EX pushes one current line at a time. HyperGlow gains a new responsibility: compute the active line (and per-word karaoke progress) from position.
- Position over `SharedMemory` is high-frequency and poll-based; HyperGlow's current staleness model (`SpicyBridgeStore.STALE_AFTER_MS = 3000ms`, push-based `sampledAtElapsedMs`) needs a parallel poller for the lyricon path.
- Both HyperGlow and lyricon hook System UI. [assumption] Coexistence on the AOD surface is compatible, but lifecycle ordering (which module's hooks own the surface) is unverified and must be tested on device.
- GPL boundary: HyperGlow is GPL-3.0 (@LICENSE); lyricon is also GPL-3.0. Depending on lyricon's SDK artifacts (rather than copying source) keeps the boundary clean; confirm the SDK's license and distribution terms before shipping.