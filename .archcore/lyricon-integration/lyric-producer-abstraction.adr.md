---
title: "Adopt LyricProducer abstraction with lyricon subscriber as second producer"
status: accepted
tags:
  - "architecture"
  - "lyrics"
  - "provider"
  - "xposed"
---

## Context
HyperGlow's lyrics ingress is hard-bound to Spicy EX hooking Spotify. `CallerValidator.isSpicy` gates both bridge entry points (@app/src/main/java/com/eza/hyperglow/bridge/SpicyLyricBridgeProvider.kt, @app/src/main/java/com/eza/hyperglow/bridge/SpicyLyricBridgeService.kt), and `SpicyBridgeStateReducer.accept` rejects any `trackUri` not starting with `spotify:track:` (@app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt). The projection consumers `AodProjectionEngine` and `SystemUiLyricProjection` observe `SpicyBridgeStore.state` directly, so no second producer can feed the AOD/lockscreen surface without editing those consumers. Adding lyricon as a source therefore requires a producer-agnostic boundary between ingress and projection.

## Decision
Adopt a `LyricProducer` abstraction as the single ingress-to-projection boundary, with `SpicyBridgeStore` and a new `LyriconSubscriberController` (backed by the lyricon subscriber SDK `0.1.71`) as its two implementations, mediated by a `LyricProducerArbiter` that selects the active producer per user preference and on producer loss.

## Alternatives Considered
1. Inline lyricon as a second `SpicyBridgeStore` payload — rejected because the lyricon `Song` model carries a full `List<RichLyricLine>` and position over `SharedMemory`, not the single-line `SpicyBridgeState` with `sampledAtElapsedMs`; forcing it through `SpicyBridgeStateReducer` would discard per-word karaoke timing and require faking `spotify:track:` URIs to pass the existing validator.
2. Per-consumer branching in `AodProjectionEngine` / `SystemUiLyricProjection` — ruled out because it duplicates producer-selection logic at every projection site and re-introduces the Spotify-only coupling at each call site, contradicting the multi-producer goal.
3. Defer the boundary until on-device coexistence with lyricon's SystemUI hooks is validated — deferred because the boundary decision is independent of that risk; the abstraction can ship behind the Spicy EX path and absorb lyricon when device testing passes.

## Consequences
- [expected] Projection consumers depend on one `LyricProducer`-typed observable, removing the direct `SpicyBridgeStore.state` dependency from `AodProjectionEngine` and `SystemUiLyricProjection`.
- Enables a second producer (lyricon) without touching projection code; future producers (e.g., MediaSession-based) plug into the same boundary.
- Adds one indirection layer and an arbiter with switching and lifecycle logic; [expected] arbiter unit tests cover selection, fallback, and the single-active-producer invariant.
- Tradeoff: the lyricon producer gains a new active-line-selection responsibility (compute current line from `SharedMemory` position) that the Spicy path does not have, increasing test surface.

## Superseded when
- A third producer is added whose payload shape cannot be normalized into the `LyricProducerState` contract (e.g., requires streaming partial lyrics) — then the boundary must be widened or split.
- lyricon's subscriber SDK changes its transport away from the SystemUI-injected central service (e.g., adds a standard ContentProvider), making the `SharedMemory` position poll unnecessary.