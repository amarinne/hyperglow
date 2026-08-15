# Lockscreen + AOD Behavior Spec

Status: implementation contract

This document extends `PARITY-SPEC.md`. AOD rendering continues to follow the existing parity
contract. This spec defines surface visibility, privacy, continuity, customization, and fallback.

## Shared snapshot

- SystemUI uses one validated Binder client and one immutable surface-neutral lyric snapshot. Binder state is decoded synchronously into owned immutable data under the shared count, UTF-8, and encoded-body limits before main-thread delivery.
- Lockscreen and AOD render separate views from the same content, row, timing anchor, and track
  generation.
- A newly attached surface receives the cached latest snapshot immediately.
- Stale expiry, Binder death, caller failure, or invalid payload hides every subscriber. A hidden
  state explicitly marked as a real Spotify pause may retain the last valid lyric snapshot under the
  shared bounded policy below. Terminal hidden state clears it.
- Producer state is ordered by generation then sequence: an older generation, or a lower sequence
  within the same generation, is discarded as out of order. A repeat of the current sequence is
  discarded only when it carries the same displayed text. The same sequence with revised title,
  artist, lyric, transliteration, or translation is a correction — the producer reprocessed the
  playing song — and replaces the held state, so text cannot outlive the setting that produced it.
- State/configuration carry the app user ID; a SystemUI user switch clears/rebinds and rejects the
  previous user's cached payload.
- AOD keepalive and lockscreen screen-on policy remain independent. Neither can activate from the
  other surface alone.
- `playbackActive` comes only from the active lyric source and is transported explicitly. A source is
  a process whose identity the producer has validated — the Spicy bridge validates Spotify's caller
  UID — and only a validated source can activate lyric keepalive. Validation is a property of the
  source process, not of whichever player that process reports: a source that cannot establish its
  own identity contributes nothing, and no media player reaches keepalive except through a validated
  source.
- Exactly one source is active at a time. Spotify is the default, and is the only source today.
  Sources are never merged, and a source that disconnects or fails validation is never replaced —
  a silent failover would leave the screen held awake by something the user did not choose.
  Switching sources stops the outgoing producer before the incoming one starts.
- Live lyrics require `playbackActive=true`. The one shared `After Spotify pauses` setting applies to
  lockscreen and AOD: clear immediately, 5 seconds, 10 seconds, 30 seconds, or keep indefinitely.
  The default is 5 seconds. Only the active validated source can start or extend the timer.

## Lockscreen visibility and privacy

Lockscreen lyrics are off by default for existing users. Showing media text while locked requires
explicit opt-in.

The lockscreen scene is visible only when all conditions pass:

```text
feature enabled
supported package versions and required symbol signatures
default Xiaomi lockscreen theme
primary display
keyguard showing
not bouncer/auth entry
fresh visible snapshot
minimum safe scene area
```

The view is visual-only: not clickable, focusable, long-clickable, touch-intercepting, or
accessibility-focusable. Xiaomi parent alpha/visibility remains authoritative.

The default lockscreen scene uses Xiaomi's `getClockBottom()` anchor. The optional built-in card
scrim is vertically tight to currently rendered rows, unions outgoing/incoming bounds during lyric
transitions, and follows visible media-card width when available. A bounded 92% width and dark-card
opacity are used when native media width is unavailable.

Lockscreen card lifetime follows Xiaomi's stock Spotify media player:

```text
visible MiuiMediaHeaderView + current valid lyric snapshot -> show live card
visible MiuiMediaHeaderView + eligible Spotify pause inside configured timeout -> show frozen card
MiuiMediaHeaderView hidden -> hide lyric card
MiuiMediaHeaderView removed -> discard frozen card
projection disconnect/stale/invalid state -> discard frozen card
```

The frozen card projects playback position once at eligible Spotify pause receipt, then uses
`speed=0`. A 0-second timeout clears immediately. Finite timers use the original pause edge and are
not extended by replayed hidden messages or another player. The pause edge is the first hidden edge
of the retention episode, held by each surface until a visible snapshot or a terminal hidden state
ends the episode; the publish time carried by each message is not that edge, because the producer
republishes the same paused state whenever Spotify revises it. A still-playing hidden edge opens its
own episode and its frozen card expires on the 30-second transport-gap bound, so a gap the producer
keeps republishing cannot present indefinitely. Indefinite retention still clears on the terminal
conditions above.
Lockscreen may suppress automatic dim/sleep only when its explicit keep-awake setting is enabled,
the keyguard lyric scene and stock Spotify media player are visible, playback is active, and the
bouncer/authentication UI is absent. Pause, player removal, bouncer entry, surface loss, transition
to AOD, or feature disable releases the screen-on request immediately. Manual power-button sleep
remains authoritative. AOD separately preserves the last visible snapshot and position-following
state so the lyric scene and managed clock do not snap back immediately on pause. A confirmed Spotify pause
releases AOD keepalive and policy-hide suppression; Xiaomi may sleep normally while the frozen visual
snapshot remains available. Resume or a new visible snapshot replaces it normally.

When notifications are present, collision geometry comes from Xiaomi's stack-local child layout
state, not the full-screen stack host or parent-transformed global rectangles. Row height uses
`actualHeight` plus clip amounts/bounds when available. Invisible/alpha-zero children remain
reserved only during an active linkage transition; stable stale rows are ignored. `avoid` places the lyric card below the
measured native notification block inside the remaining bottom-safe region. Native notification top
padding, translation, animation, measurement, and scrolling are never modified. Optional rows hide
first, lyrics shrink to the bounded minimum, and insufficient/unknown geometry fails closed.

## AOD visibility and lifetime

- Existing AOD display behavior and migration are preserved.
- The renderer uses the inner AOD root overlay and never enters stock clock measurement.
- Native AOD position updates trigger coalesced lyric geometry refresh. The managed controller target is
  movement/scheduling authority. Collision authority prefers the visible exact `AnimationHelper`
  clock view, then the visible exact `AODUpdatePositionController.mTargetView`, then the managed
  requested target. This covers both SystemUI's bright morph and the AOD plugin's delayed/crossfaded
  rendering after `DOZE`. Recursive rendered-descendant unions remain forbidden.
- Custom-image and unmanaged AOD scenes may use measured native-content geometry before controller updates.
  Stock linkage scenes render immediately during the bright phase using exact SystemUI clock-morph
  bounds or the bounded 35% fallback, then adopt deterministic managed geometry at the verified dim
  seam.
- On exact verified normal/linkage position modes, the experimental scene coordinator may translate
  Xiaomi's native AOD content container and own burn-in timing only while lyrics are active. In
  normal mode that container includes both stock clock styles and custom-image styles.
- The `AOD clock or image` setting presents the existing policy as one choice. `Follow Xiaomi`
  leaves native-content translation to Xiaomi while HyperGlow observes the exact target and keeps
  the lyric canvas clear. Fixed and moving choices make HyperGlow the translation authority for the
  same clock-or-image container with the selected pattern.
- Xiaomi movement callbacks remain observed so its latest natural target is cached, but their
  translation is suppressed during module ownership. The default `static_bottom` pattern moves the
  native clock-or-image container to the verified bottom zone once and holds it there while lyrics
  are active. Optional
  bounded timers select six-zone, four-corner, or vertical-swap positions at 30 s, 1 min, 2 min, or
  5 min intervals.
- Xiaomi linkage slot zero and later burn-in positions are fixed-grid coordinates, not random. The
  module registers the position controller at AOD-root attach and derives the initial natural target
  after valid layout instead of waiting for Xiaomi's delayed first `updateTranslation()` callback.
- Lyrics resolve inside the free region physically opposite the authoritative clock bounds. A
  managed dynamic-zone change is transactional: fade lyrics out for 150 ms, wait for Xiaomi's exact
  `DozeHost.updatePosition()` animation-completion callback, apply the destination geometry once,
  then fade lyrics in for 180 ms. A bounded 1500 ms timeout fails forward if the OEM callback is
  missed. The canvas does not continuously cross the clock path. Static managed placement remains
  visible without this movement transaction.
- Stock linkage has two physical ownership phases. While the display remains `ON`, the SystemUI
  lockscreen renderer remains the semantic source beside Xiaomi's morphing keyguard clock. Because
  Xiaomi may hide that source parent early, the prepared AOD renderer is immediately visible in the
  region opposite the exact rendered bounds captured from
  `AnimationHelper.mClockAnima.mAllContainer` (falling back to `mClockView`). Only when that exact
  view is unavailable does placement reserve the conservative top 35%. When
  `DozeService.setDozeScreenState(DOZE)` applies the
  dimmed display state, or the attached AOD root observes physical display state `DOZE`/
  `DOZE_SUSPEND`, authority settles directly into normal AOD geometry without a second module slide
  animation. The AOD root registers a display listener rather than relying only on an OEM hook.
  There is no timer-driven visual transfer: if the OEM callback is missed, the already-visible bright
  safe layout remains instead of moving early or disappearing. Waking
  before dim cancels back to the lockscreen source. Custom-image AOD retains its existing transition
  behavior.
- Bright clock collision state is physical presentation state, not semantic lyric-linkage state. It
  remains active when playback is paused and the current projection is hidden but an authorized
  retained AOD snapshot is restored. `SNAPSHOT_NOT_VISIBLE`, disabled lockscreen lyrics, or another
  semantic handoff rejection cannot make managed dim geometry override the still-morphing bright
  clock. Every observed default-display state change coalesces a geometry refresh, so entering
  `DOZE` also leaves the conservative bright slot even when no semantic transition is active.
- Outside managed placement, custom-image and unmanaged AOD scenes may still use compact measured
  stock-clock bounds. During managed placement, rendered/controller unions are forbidden because
  Xiaomi crossfade descendants and stale samples can reserve unrelated content or switch geometry
  authority mid-movement. Exact physical bounds use strict priority: SystemUI morph view, AOD
  controller target view, managed target. A physical bound is cleared when that exact view or any
  ancestor becomes hidden/transparent.
- Xiaomi may hide the stock media row before bright linkage finishes. Only while the lockscreen
  renderer is the active forward-handoff source may it retain the already-authorized frozen/latest
  snapshot without stock-media presence; normal stable lockscreen privacy policy is unchanged.
- Super-wallpaper, flip, unknown modes, invalid geometry, missing symbols, or inactive lyrics pass
  through Xiaomi's original translation unchanged.
- Disabling the feature, stock Spotify media-player removal, stale/disconnected projection, Binder
  failure, or failed surface eligibility releases static/moving ownership, cancels any module timer,
  and restores Xiaomi's last unmodified translation target. An eligible Spotify pause retains the
  frozen AOD scene and current managed clock placement only for the shared configured timeout.
- A playing song-generation change starts an 8-second presentation lease and emits a wake event so
  synced and unsynced songs may briefly present song-change metadata. Presentation policy shows the
  title and artist at lyric size for five seconds or until the opening interlude ends, whichever comes
  first, then morphs or crossfades to persistent small song info when enabled; otherwise it removes
  the title/artist. A song whose opening is already known to be an active lyric, or a gap shorter
  than three seconds, presents no intro then and defers one full intro to the next interlude with at
  least three seconds available. The opening is not known at the song change itself, because metadata
  arrives before the timed document; the intro leads there rather than showing the placeholder note,
  and that start is provisional. Only the timed document settles it. An interlude confirms it, and
  the intro then runs its course and is consumed even if the first lyric ends it early. A
  document-backed lyric already under way does not confirm it: the intro yields the row at once and
  the full intro is still owed at the next qualifying interlude. Producer line text arriving before
  the document does not settle anything, because at a song change it still describes the moment
  before the change rather than this song's opening.
- `Show song info on song change` turns the whole intro off. It is a presentation choice and changes
  nothing else: the song-change lease, the wake event, keepalive, and AOD lifetime are unaffected,
  and the lyric row simply carries its normal content through the opening.
- An instrumental gap arrives as an `INTERLUDE` row rather than as an absence of rows, so a row
  covering the playhead is not evidence of singing. Interlude rows are interludes for every intro
  decision, and the length of an interlude is measured to the next sung row, not to the end of the
  interlude row itself. This state is generation-bound and consumed at most once per song. It does not alter
  playback, pause retention, wake identity, keepalive, or AOD lifetime policy. The first accepted timed
  document for that generation emits a second wake event, allowing a synced track to restore AOD
  after an earlier unsynced track timed out. The exact verified wake broker calls Xiaomi's
  `DozeHost.fireAodState(true, "reason_keycode_goto")` only while the device is non-interactive.
- Default keepalive additionally requires a `Line` or `Syllable` document containing at least one
  positive-duration row. Timed-document arrival upgrades the current presentation lease to persistent
  keepalive without a false gap. Static, missing, loading, no-lyrics, and degenerate zero-duration
  documents release naturally when the lease expires. `Also keep AOD active without timed lyrics`
  upgrades those untimed states to persistent keepalive. The main keep-awake preference must be on
  for lease, timed, and override modes.
- `Keep AOD active for` bounds the continuous Spotify-playing lifetime session to 5 minutes,
  10 minutes, 30 minutes, 1 hour, 2 hours, or Indefinitely; Indefinitely is the default. A finite
  timer begins when keepalive first becomes active inside a continuous playing streak. Song changes,
  document changes, wake events, transport grace, and freshness heartbeats do not reset it, and a
  presentation-lease gap inside the same playing streak does not re-anchor it. Expiry releases Xiaomi
  lifetime suppression while playback may continue; Spotify pause/stop still releases immediately.
  The next eligible playback session after that non-playing edge may start a new timer.
- Xiaomi lifetime suppression is independent of canvas visibility, layout, and linkage ownership.
  It requires only an attached AOD surface, validated keepalive intent, and the exact lifetime
  capability. Expiry of the configured duration withdraws that keepalive intent at the projection,
  so no separate SystemUI timer exists. The duration is lifetime policy only; it does not
  alter wake identity, presentation leases, content capability, pause retention, or renderer state.
  Draw-wake renewal remains a separate renderer concern.
- A transient hidden edge explicitly marked as Spotify still playing starts a bounded 30-second
  power grace after any snapshot carrying validated keepalive intent. Timed lyrics and untimed
  sessions held by `Also keep AOD active without timed lyrics` are equally eligible; lyric timing is
  content capability and never gates lifetime policy. The next visible snapshot cancels the grace
  without replaying Xiaomi hide policy. Paused/non-playing state releases immediately. This prevents short
  producer/status gaps from turning AOD off mid-song; stale/disconnect still releases immediately.
- A null producer-state edge retains the one bounded timed document in app memory for the same
  30-second transport grace. Only a returning state with the exact producer, generation, track URI,
  and duration may reuse it. A different session clears it immediately; an abandoned gap clears it
  when the grace expires. Explicit producer clear still discards state and document immediately.
- SystemUI transport retention uses the same absolute edge and retires the frozen scene atomically
  at expiry. Keepalive heartbeats cannot restart a hidden grace, leave an expired scene positioned,
  or alternate Xiaomi stock and managed placement. A fresh visible projection may start normally.
- Detached-surface recovery is one-shot per wake identity. If Xiaomi tears AOD down again during the
  same song phase, heartbeats do not repeatedly wake, attach, and reposition it; a new song or timed-
  document wake identity may make one fresh recovery attempt.
- A still-playing transport gap carries the session's existing keepalive intent, not a withdrawal.
  Publishing playback without keepalive reads as a withdrawal at the SystemUI coordinator and
  releases Xiaomi lifetime suppression for the length of the gap, which is what a song change must
  never do. The coordinator's own power grace remains a backstop for that window rather than the
  only defence. A confirmed pause or a lost session carries no intent and releases normally.
- A non-playing `loading` edge during song replacement is projected as that bounded still-playing
  transport gap. Every other non-playing edge is provisional: Spotify reports the ending track as
  `ready`/not playing roughly a second before the next generation arrives, so the edge is first
  projected as the same still-playing transport gap and only becomes real pause retention when the
  producer is still non-playing on the same session after a bounded 1.5-second confirmation window.
  A resumed producer or a new session inside that window cancels the pending pause, so a song change
  never releases AOD lifetime or replays Xiaomi hide policy. The window opens once per session; a
  producer that keeps publishing while paused must not reopen it.
- Ready, loading, and no-lyrics visible playback all receive the same 4-second freshness heartbeat;
  unchanged fallback snapshots are refreshed instead of expiring after 5 seconds.
- The wake broker retains the latest verified `DozeHost` as one bounded recovery reference across
  AOD plugin teardown. If a persistent session has no attached AOD surface, each bounded heartbeat
  may retry the same wake identity until Xiaomi recreates the surface. Interactive-screen requests
  remain suppressed and the system AOD master setting remains authoritative.
- A keepalive edge that arrives while Xiaomi is already hiding AOD cannot be suppressed: the policy
  hide has run, its alarm can no longer be cancelled, and a wake delivered mid-animation only re-arms
  Xiaomi's own timer. That single race re-asserts the current wake identity once, on the first
  powered-off AOD display edge within the bounded hide-animation window after an inactive-to-active
  lifetime edge, and only while the surface stays attached. Recovery is armed by that guard edge only
  when AOD was still presenting, is consumed by the first off edge, and requires a fresh guard
  activation to re-arm. A session that has never dispatched a wake carries no identity and does not
  recover.
- Display power is otherwise Xiaomi's to own. A sensor or pocket pause, a deliberate sleep, an
  expired session, and a released lease all reach the same powered-off edge, and re-waking them
  fights Xiaomi in a self-sustaining loop that relights the panel every few seconds. Keepalive never
  treats a powered-off AOD display as a standing reason to wake, and the wake broker's minimum
  request interval is not a substitute for that bound.
- A confirmed Spotify pause releases the lifetime guard once, at most one confirmation window after
  the edge; its frozen card may remain only for the
  shared configured timeout, until Xiaomi sleeps, or until the stock media player is removed,
  whichever ends presentation first.
- Lockscreen attachment or visibility alone never suppresses Xiaomi hide policy.

## Lockscreen customization gestures

- `Block lock screen customization` is off by default and independent of lyric visibility.
- When those exact Xiaomi symbols resolve, suppression is limited to
  `KeyguardEditorHelper.onTouchEvent(MotionEvent)`, the final `tryStartEditActivity()` launch gate,
  and `LockScreenMagazineController.handleSingleClickEvent()`.
- The setting blocks the editor long press and the wallpaper-carousel single-tap preview.
- No generic lockscreen touch listener is replaced. Swipe, notification, media, power, fingerprint,
  biometric, and accessibility paths remain stock.
- Missing methods, unknown package versions, or a disabled setting pass through unchanged.

## Raise gesture remap

- HyperOS `Raise to wake` remains the sensor master switch. The module does not force-enable it and
  does not register a second pickup sensor.
- When `Raise to show AOD` is enabled and the raise-to-AOD capability resolved, only SystemUI wake calls whose
  detail is `com.android.systemui:PICK_UP` are remapped. The module first requests AOD through the
  verified Xiaomi `DozeHost.fireAodState(true, "reason_keycode_goto")` state-machine seam, then
  always suppresses the full wake. If AOD is already sustained by active lyrics, the request is
  effectively redundant and the existing AOD remains visible.
- The remap is global for this owner device and does not depend on Spotify, lyrics, media state, or
  either lyric surface being enabled.
- Power-button, fingerprint, double-tap, notification, biometric, camera, and application wake
  reasons always pass through unchanged.
- With the setting enabled, an unavailable wake host leaves the device non-interactive rather than
  entering the lockscreen. Missing wake-hook symbols, unknown package versions, a disabled module
  setting, or disabled HyperOS `Raise to wake` retain stock behavior.

## Continuity

- Linkage uses separate lockscreen and AOD renderers; no view reparenting.
- Handoff state is bounded, reversible, and protected by monotonic tokens.
- Row selection may freeze for at most 600 ms while word/fill timing continues from the same elapsed
  time anchor.
- Track-generation change cancels the freeze immediately.
- Source and target rectangles are captured in window coordinates. Missing geometry degrades to an
  alpha-only handoff; missing target degrades to native attach/detach visibility.
- Reverse handoff waits only for a short bounded target-geometry stabilization, then starts the
  lockscreen lyric from a small positive Y offset and slides it upward while Xiaomi reveals the
  parent. The target animation must not complete while the lyric view is still invisible.
- Forward handoff preserves the AOD target alpha animation through geometry and wake refreshes;
  layout/wake callbacks must not reset the target to alpha `1` mid-transition.
- Normal line enter/exit animation is suppressed during handoff and resumes after settle.
- Xiaomi native keyguard parent animation is never overridden.
- Lockscreen reveal animates the complete card container as one unit. Text, adaptive background,
  outline, and media progress share the same alpha and upward translation timeline.

## Declarative customization

- Documents are versioned data, not plugins.
- App-process compilation performs migration, normalization, capability filtering, limits, and
  stable revision hashing. SystemUI validates again.
- Configuration target size is below 32 KiB; hard maximum is 64 KiB.
- Total widgets target at most 8; AOD visible widgets at most 4.
- Unknown widgets are dropped. No valid lyric widget falls back to the built-in safe profile.
- Lockscreen `backgroundStyle` accepts only `auto`, `card`, or `none`; AOD always resolves it to
  `none`.
- Line-level progress keeps `None`, `Top to bottom`, and a main-only `Left to right` approximate mode,
  plus a distinct explicit whole-block compatibility mode. Approximate left-to-right progress treats
  all wrapped main-lyric rows as one continuous sequence: complete one visual row left-to-right, then
  continue on the next. Normal gradient/progress animation affects only the main lyric; ruby,
  transliteration, and translation remain static. The whole-block option alone preserves the current
  simultaneous sweep across all visible lyric rows and must not normalize to main-only. Each surface
  profile independently selects bright or dimmed secondary-text presentation. Word/syllable-level
  synchronization is unchanged.
- Main lyrics accept a per-surface wrap limit of 1, 2, 3, 4, 5, or no user limit. Text size up to 200%
  must use the selected limit rather than the old fixed three-line ceiling. Safe-area geometry,
  optional-row removal, bounded minimum size, and fail-closed placement remain authoritative.
- Each surface profile stores metadata size from 50% to 200% and ruby-reading visibility. Ruby is
  shown by default and, when disabled, reserves no drawing or layout height.
- During the generation-bound song intro, matching one-line title/artist text suppresses the duplicate
  metadata row and morphs into the persistent metadata position and size when the intro ends.
  Incompatible or wrapped geometry uses bounded crossfade. Neither path changes whole-surface alpha,
  stock-clock brightness, or placement authority.
- Imported data cannot name classes, resources, methods, paths, URLs, commands, or external bitmap
  sources.
- Reset restores the built-in safe profile.

Enabled fixed registry:

- lyrics;
- metadata;
- media_progress on lockscreen only.

Artwork accent, status text, spacer, and divider remain rejected until each has a real bounded
renderer, placement contract, privacy/power analysis, and device evidence. AOD progress/artwork
remain sanitized out.

AOD policy may further reduce luminance, bright area, animation, artwork, component count, or scene
size regardless of user/imported values.

## Migration

- Existing `aod_render` values populate the default AOD profile without visible regression.
- The initial lockscreen profile derives from AOD styling but remains disabled.
- Linked surface styling defaults on once lockscreen is enabled.
- Seamless transition defaults on only when both surfaces are enabled and linkage capabilities pass.
- Migration version is written only after successful validation/persistence.
- Legacy AOD preferences remain available for one rollback cycle.

## Capability fallback

The app displays explicit support state from the latest accepted capability report: no report, the
resolved capability count as `<n>/<total> hooks available`, or unsupported. Configured surface
preferences remain stored on unsupported profiles, but the app must describe them as unable to run and
disable runtime-dependent controls. Appearance editors remain usable for preview and future
configuration. The user can create a compatibility report containing package versions and bounded
raw-symbol evidence, offered whenever the count is short of the total.

A capability is granted when its exact symbols resolve, and nothing else gates it. The app does not
compare a build's SystemUI/AOD version pair against a reference device: a survey across five AOD
builds, four phone models, and a tablet found the pair predicted nothing the probes do not establish
directly, and could not separate a tablet shipping no AOD implementation from a phone, because both
report the same SystemUI version. Every build attempts its hooks and is described by what resolved.

Fail-closed is per symbol: an unresolved seam removes its own capability and leaves the rest, and a
build with no usable surface symbols is unsupported and runs nothing. A tablet that resolves the
lockscreen seams but no AOD seam therefore runs lockscreen lyrics and reports AOD as unavailable.

Verified, verified-with-missing-symbols, experimental-eligible, and experimental-active are retired
as live states. They are decoded only for reports written by an earlier build or sent by a SystemUI
process that has not restarted, and are treated as runnable when they appear.

Capability report protocol v2 includes the report timestamp, effective profile state, experimental
state, raw probe set, and resolved capability set. Protocol v1 remains accepted only for app/SystemUI
update transition compatibility.

Capabilities are independent:

```text
AOD_SURFACE
AOD_POSITION_UPDATES
AOD_LIFETIME_GUARD
AOD_WAKE_BROKER
LOCKSCREEN_HOST
LOCKSCREEN_GEOMETRY
LINKAGE_DIRECTION
LINKAGE_GEOMETRY
RAISE_TO_AOD
FULL_AOD
VIDEO_DEPTH
```

Matching includes SystemUI/AOD package versions and exact required symbol signatures. Unknown or
missing symbols disable only dependent behavior. Stock UI is never hidden, replaced, reparented,
remeasured, or restyled. Clock translation control is allowed only by the verified AOD scene policy
above and must fail back to Xiaomi's original target.
