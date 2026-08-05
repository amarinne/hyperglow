# HyperGlow Architecture

Status: optional lockscreen + AOD package contract

## Ownership

`HyperGlow` owns Xiaomi lockscreen/AOD lyric delivery and module-owned rendering surfaces:

```text
Spotify / Spicy EX
  -> versioned Binder, provider fallback
  -> HyperGlow app process
  -> local playback projection and row selection
  -> UID-validated callback
  -> one validated SystemUI projection
      -> non-interactive lockscreen renderer under keyguard_translation_info
      -> non-measuring ViewGroupOverlay on the full-screen SystemUI AODView root
```

Package identity:

- application ID: `com.eza.hyperglow`
- producer Binder: `com.eza.hyperglow.bridge.SpicyLyricBridgeService`
- provider authority: `com.eza.hyperglow.spicybridge`
- SystemUI callback service: `com.eza.hyperglow.aod.AodLyricBridgeService`
- Xposed scope: `com.android.systemui`

Lockscreen and AOD use separate physical renderer instances backed by one immutable lyric snapshot.
Views are never reparented between hosts. Xiaomi retains ownership of parent visibility, keyguard
authentication, doze, and brightness. On exact verified AOD modes, the optional scene coordinator
temporarily owns native AOD content/lyric burn-in timing and placement while lyrics are active. The
native target is Xiaomi's clock container, including custom-image styles. Xiaomi's natural target is
cached and restored when the lyric scene ends.

## Exclusions

- HyperLyric notification listener and notification renderer
- HyperLyric MediaSession/provider lyric sources
- island hooks and general lyric UI
- frame-level IPC or continuous animation
- stock Xiaomi AOD content replacement, reparenting, measurement, styling, or lifecycle mutation
- arbitrary third-party code, classes, resources, scripts, or Android views in SystemUI
- writing Xiaomi lockscreen/AOD configuration settings

## Surface Contract

- SystemUI owns one Binder client and one cached surface-neutral lyric snapshot.
- Lockscreen and AOD subscribe independently and receive the same content/timing revision.
- The lockscreen renderer is inserted at child index `0` of `keyguard_translation_info`, remains
  visual-only, and inherits Xiaomi parent alpha/scale/swipe/bouncer behavior.
- The default lockscreen policy uses Xiaomi's `getClockBottom()` anchor and places the lyric card
  below visible notification/media/zen content inside the remaining bottom-safe region.
- Native notification layout, top padding, translation, animation, measurement, and scrolling are
  never modified. Insufficient space hides optional rows, shrinks lyrics to the bounded minimum,
  then fails closed.
- Lockscreen collision refreshes are latest-only per display frame. An eligible pre-draw sampler
  watches notification/media translation, alpha, scroll, clip bounds, actual height, and clip amounts;
  unchanged frames do not run the full geometry/reflection scan.
- The optional card scrim follows current rendered row bounds rather than the full canvas. Its
  horizontal footprint follows visible media-card bounds when available, with a bounded 92% fallback.
  The full-screen stack host is never treated as content geometry.
- The AOD renderer remains in the inner `AODView` root overlay. It never participates in Xiaomi
  clock-container measurement.
- Shared line-level frame drawing traverses prebuilt layout rows with indexed loops and scalar fill
  math; it does not build filtered row/width/progress collections per frame. Ruby base-run slicing is
  still residual layout debt tracked by the audit.
- AOD lifetime suppression is owned by a permanent SystemUI power coordinator and is active only
  while an AOD surface is attached and the validated projection requests keepalive. Canvas
  visibility, layout success, and linkage presentation do not control Xiaomi lifetime policy.
  A configured 5-minute, 10-minute, 30-minute, 1-hour, 2-hour, or indefinite session deadline bounds
  that keepalive intent; indefinite is the default. The deadline is owned by the projection power
  session policy, which stops requesting keepalive once it elapses, so the coordinator releases
  Xiaomi lifetime suppression through the existing intent path instead of a second timer. The
  deadline spans continuous eligible Spotify playback and is not reset by track/document/heartbeat
  updates. Lockscreen-only playback cannot suppress `smartHide()` or `hideDoze()`.
- Linkage uses two renderer instances and bounded geometry/alpha handoff. Stock linkage keeps the
  lockscreen instance as the semantic source through Xiaomi's bright SystemUI clock morph. The AOD
  instance is immediately visible opposite the exact rendered SystemUI clock-morph bounds when they
  are available. A conservative 35% top reserve is the fallback only when that exact view is absent.
  This bright collision phase follows physical clock/display state independently from lyric handoff
  eligibility, so paused retained lyrics remain safe even when the current projection is hidden.
  Normal geometry begins when an API display listener observes `DOZE`. No elapsed-time fallback
  initiates a visual transfer; a missed callback leaves the visible safe layout intact. Xiaomi
  remains authoritative for native parent animation and display-state timing.
- Unknown package versions or missing required symbol signatures disable only dependent features.

## Display Layout Contract

Bridge document version 1 accepts optional row-level `layoutGroups`. Each group carries UTF-16
source range, lexical kind, keep-together intent, and confidence. AOD projects these fields through
Binder without changing timed words. The module-local persisted `Adaptive sectioning` preference
defaults on: lexical chunks and secondary-row tokens are balanced across the required line count;
Japanese particles stay with preceding phrases, Chinese dictionary phrases stay together, and Korean
authored spaces remain break points. Groups wider than the canvas may emergency-break. With the
preference off, AOD ignores layout groups and restores upstream behavior: timed words wrap greedily
in source order without balancing, but transported fragments marked as parts of the same lexical word
remain indivisible. Untimed text uses `Paint.breakText`, and each secondary row stays a single clipped
line. Transported transliteration segments keep their original word timings even on that single line;
wrapping policy never converts them to row-global progress.

Each surface profile independently selects a main-lyric wrap limit of 1 through 5 lines or no user
limit. The renderer applies that limit before layout; no-limit layout remains bounded by the validated
500-character/128-word snapshot. Surface safe areas, maximum-height policy, optional-row removal, and
fail-closed placement remain authoritative. Transliteration and translation rows are static; their
profile toggle selects bright or dimmed presentation and never changes timing cadence. Line-level
left-to-right approximation traverses the cumulative widths of wrapped main rows sequentially. The
explicit whole-block compatibility mode is the sole exception: it retains the existing simultaneous
X sweep across every visible lyric row. Word/syllable timing remains unchanged.

## Security And Lifecycle

- Producer endpoints accept only UIDs containing `com.spotify.music`.
- AOD callback accepts only system UID containing `com.android.systemui`.
- Protocol, sequence, payload size, document identity, row count, word count, timing, and text bounds fail closed. Document pipes use the declared compressed byte count as an exact frame boundary and reject short/truncated frames.
- Closed producer render-mode strings are normalized once at bridge ingress. Current values are
  preserved, known legacy aliases are canonicalized, unknown values use producer-safe defaults, and
  custom text size is clamped before state enters projection/rendering.
- The producer retains at most one bounded immutable state and one compressed document for the
  current session. Each Binder connection receives state first, then document, once; normal service
  process death uses Android's existing automatic reconnect without creating a duplicate bind.
  Explicit clear, generation retirement, and disable discard both retained payloads.
- App-to-SystemUI lyric state keeps the `onState(Bundle)` ABI but carries a versioned scalar envelope; full snapshots use one encoded body bounded to 48 KiB aggregate UTF-8 text and 64 KiB encoded bytes. Hidden and keepalive messages remain scalar-only.
- The scalar envelope carries Spotify `playbackActive` explicitly. Power policy never infers pause
  from lyric visibility, media rows, another media player, or renderer state.
- A SystemUI user switch clears cached state, rejects old-user payloads, and rebinds the app service
  with the selected Android `UserHandle`; it does not keep targeting the owner-user app instance.
- Live lyric snapshots render only while the UID-validated Spotify projection explicitly reports
  playback active. A real Spotify pause may freeze the last valid snapshot at `speed=0` for one shared
  lockscreen/AOD timeout: 0, 5, 10, or 30 seconds, or indefinitely; the default is 5 seconds. A confirmed
  Spotify pause releases keepalive so Xiaomi may sleep normally. Loading edges and every other
  non-playing edge are first published as still-playing transport grace; a pause is confirmed only
  when the producer stays non-playing on the same session past the bounded confirmation window, which
  keeps a song change from releasing lifetime between two tracks. Other media players cannot start or
  extend either policy. Media-player removal, stale state, invalid data, Binder death, detach, or
  missing Xiaomi symbols clears output and restores stock AOD placement.
- Lockscreen lyrics require explicit opt-in and hide for unsupported/custom themes, secondary
  displays, bouncer/auth entry, stale state, or insufficient safe geometry.
- While visible playback is active, including loading/no-lyrics fallback, a 4-second bounded heartbeat
  refreshes snapshot freshness.
  It pulses Xiaomi's AOD draw wake lock for 5.5 seconds only when AOD keepalive is enabled.
- Device-verified `MiuiShowStyleController.smartHide()` and `hideDoze()` policy calls are suppressed
  only during the independent Spotify-playing AOD power session or a bounded playing-state transport
  grace. Each playing song
  generation receives an 8-second presentation lease; actual timed lyrics or the explicit unsynced
  override upgrade that lease to keepalive subject to the configured session duration. A finite
  duration begins on the inactive-to-active keepalive edge and does not reset on song generations or
  heartbeat traffic. Pause/stop ends the session immediately; a later eligible playback session may
  start a new timer. A suppressed hide replays when power intent expires, the duration elapses, or
  bridge state becomes stale/cleared, and only when the captured controller generation and identity
  are still current.
- Keepalive sustains an attached AOD surface across Xiaomi smart-off and timing policy. It does not
  create an AOD surface when AOD is fully disabled in system settings.
- A generation-keyed wake signal is separately delivered through the exact Xiaomi
  `DozeHost.fireAodState(true, "reason_keycode_goto")` state-machine seam. This can restore a sleeping
  but enabled AOD for song-change presentation or later timed-lyric arrival; it does not bypass the
  system AOD master setting. The latest verified host is retained as one bounded SystemUI recovery
  reference across Xiaomi plugin teardown. While a persistent timed session is detached, bounded
  heartbeat retries reuse the current wake identity until an AOD surface returns.
- While lyrics are active, the experimental scene coordinator suppresses Xiaomi movement targets.
  The target is Xiaomi's native AOD content container, whether it currently shows a clock or custom
  image. The default static-bottom mode moves that container once to the verified bottom slot and
  holds it there while lyrics occupy the top free region. Static-top is the inverse fixed layout.
  Optional six-zone, four-corner, and vertical-swap schedules use bounded 30 s, 1 min, 2 min, or
  5 min intervals. The managed controller target is the movement/scheduling authority. Collision
  authority is ordered: the visible exact SystemUI morph clock, then the visible exact AOD
  position-controller `mTargetView`, then the managed requested target. This covers Xiaomi's
  bright-to-dim crossfade without assuming a requested translation has already rendered. Dynamic
  changes fade lyrics out, wait for Xiaomi's clock motion to settle, apply destination geometry once,
  then fade lyrics in. The full-width lyric canvas never traverses the clock's movement path.
- Xiaomi's latest natural target continues to be observed. It is restored immediately when lyrics
  clear/stale, the preference turns off, projection fails, or control becomes ineligible. Xiaomi
  then resumes natural scheduling without module timer activity.
- The lyric view lives in the AOD root overlay and never participates in `mTableModeContainer`
  measurement. Geometry reads and the dedicated verified translation hook are the only stock-clock
  interactions.

## User-triggered diagnostic reporting

The app process owns a dedicated Compose diagnostic destination, bounded metadata collection,
temporary guided-capture state, payload preview, HTTPS upload, and GitHub issue drafting. SystemUI
never performs network or repository work. It contributes only the UID-validated capability report
and privacy-safe `HyperGlow` log events already governed by the logging contract.

Capability protocol v2 adds report time, effective profile state, experimental state, raw exact-symbol
probe results, and the resolved capability set. The app accepts v1 during app/SystemUI process update
transitions. Raw probes include the AOD host container and lockscreen controller/host/geometry seams;
unknown profiles still resolve no runtime capabilities. Stored appearance preferences remain separate
from compatibility state.

Guided capture temporarily enables existing diagnostic logging and publishes the normal compiled
configuration. Finish executes only fixed, bounded root commands with no user-controlled shell text.
Root denial degrades to metadata-only reporting. The app process filters/redacts output, previews the
exact allowlisted JSON, uploads manually through one bounded `HttpURLConnection` request, and deletes
the temporary draft after cancellation, timeout, or success. The APK contains no intake credential.
See `docs/DIAGNOSTIC_REPORTING_SPEC.md`.

## Customization Boundary

Customization is versioned, declarative data compiled in the app process and defensively validated
again in SystemUI. SystemUI renders only a fixed internal widget registry. Imported documents may
select known widgets, anchors, semantic palette tokens, typography, main-lyric line limits, static
secondary-text brightness, and bounded transitions. They cannot name classes, resources, methods,
paths, URLs, commands, or executable code.

Lockscreen profiles also accept a bounded `backgroundStyle`: `auto`/`card` resolves to the built-in
noninteractive notification-style scrim; `none` remains transparent. AOD always sanitizes it to
`none`.

Migration preserves current AOD preferences. The initial lockscreen profile derives from those
values but remains disabled for existing users. Old preferences remain readable for one rollback
cycle after a successful migration.

## Observed Animation Capability

Device test (`0.1.9`, HyperOS AOD) confirms custom injected `View` animation is viable while the
surface remains attached. A per-word canvas loop using Spicy scale, vertical-offset, and glow
curves rendered as independent moving elements; it did not collapse into text swaps.

This raises the practical ceiling above conservative state-only estimates:

- Per-element transforms, alpha, glow, and sprite-style loops are viable.
- A 100 ms animation tick is visibly useful on this device.
- Smoothness still depends on AOD attachment and wake policy; detach, deep doze, or policy replay
  can interrupt the loop.
- Frame-perfect 60 FPS remains unverified and is not a contract.

## License

Initial transport and AOD surface code extracted and adapted from HyperLyric. HyperLyric is GPL-3.0; this package remains GPL-3.0. See `NOTICE` and `LICENSE`.
