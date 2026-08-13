# HyperGlow Style Guide

Status: canonical implementation guide

This guide codifies the dominant safe patterns in the module. It is subordinate to
`docs/ARCHITECTURE.md` and `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`. Those documents define behavior;
this document defines how that behavior is implemented and reviewed.

The strictest rules apply to code loaded into `com.android.systemui`: failures there can affect the
lockscreen, AOD, authentication flow, memory pressure, and device power. Do not mass-format or
rewrite working code merely to satisfy a preference. Correct deviations when they affect
correctness, lifecycle safety, trust boundaries, power, readability, or maintainability.

## 1. Package boundaries and visibility

### Package ownership

| Package | Ownership |
|---|---|
| `bridge` | Spotify/Spicy producer endpoints, caller validation, transport ordering, document decoding, and app-process bridge stores. |
| `aod` | App-process playback projection, render preferences, SystemUI callback service, state/configuration publication, demo control, and persisted Xiaomi capability reporting. |
| `customization` | Versioned declarative models, migration, normalization, compilation, persistence, and editor state. |
| `ui` | Activity, Compose settings/editor, preview orchestration, import/export UI, and user-triggered diagnostics. It does not own wire validation or SystemUI lifecycle policy. |
| `root` | Code loaded into SystemUI or deliberately shared with the app preview. Runtime hooks, projection, surface controllers, placement, transitions, and renderers live here. |
| `root.aod` | AOD hook, overlay surface, renderer, lifetime guard, position updates, and stock-widget coordination. |
| `root.lockscreen` | Visual-only lockscreen host, geometry, notification collision, and lockscreen-only widgets. |
| `root.projection` | One SystemUI Binder client/session, immutable lyric snapshot, replay, freshness, user isolation, and subscriber fan-out. |
| `root.surface` | Pure surface environment, placement, collision, and surface policy decisions. |
| `root.transition` | Linkage direction hooks, transition state/tokens, geometry conversion, freeze, reversal, timeout, and cleanup. |
| `root.capability` | Exact-version and exact-symbol capability resolution. |
| `root.customization` | SystemUI wire extraction and second-stage defensive configuration validation. |

Rules:

- Preserve process boundaries. SystemUI runtime code must not read app-process preferences, perform
  app repository work, or create a second bridge connection. It consumes bounded Binder data through
  `SystemUiLyricProjection`.
- App preview may reuse deterministic renderer, placement, capability-model, and snapshot types from
  `root`; that reuse does not authorize app-process services or persistence inside SystemUI paths.
- Hooks observe/install/intercept. Controllers own mutable lifecycle. Pure geometry, normalization,
  and policy belong in testable helpers or resolver/model files.
- Do not add a shared package or abstraction only to move types. Extract a shared model when it removes
  a real dependency violation or repeated mapping and remains safe in both processes.

### Visibility

- Use the narrowest visibility that supports the real call graph.
- `private` is the default for fields, constants, callbacks, reflection helpers, and file-local
  implementation details.
- SystemUI implementation types are normally `internal`. Manifest components, the Xposed entry
  class, and framework-required overrides remain externally visible as required.
- App-process models used across package boundaries may use module visibility appropriate to current
  serialization/framework constraints. Do not make a declaration public solely for a unit test.
- Mutable state stays private to its owner. Expose immutable snapshots, read-only collections, or
  operations rather than mutable properties.

## 2. Models, enums, constants, and string protocols

- Use immutable `data class` values for snapshots, wire payloads after extraction, configuration,
  geometry, placement inputs/results, capability reports, and transition transforms.
- Model an exhaustive state machine with `enum class` when states carry no payload and with a sealed
  interface/class when variants carry different data. Keep transition logic exhaustive.
- Collections stored in snapshots are owned and exposed as `List`, `Set`, or `Map`. Copy mutable
  framework/caller collections before storing them.
- Use `object` for a genuine process singleton or stateless namespace, not to hide unrelated mutable
  responsibilities.

String values are unavoidable at AIDL/`Bundle`, JSON, preferences, Intent, and UI-label boundaries.
Treat them as protocols, not casual strings:

- Define keys, protocol versions, limits, package/class names, preference keys, and stable wire values
  once in the owning codec/store/contract.
- Preserve exact spelling and case across producer and consumer copies of a protocol. AIDL method
  order and callback transaction order are ABI.
- Validate closed producer values at ingress where compatibility permits. Otherwise normalize through
  one explicit table and fail to a documented safe value. Unknown values must not accidentally enable
  a feature because they are merely nonblank.
- Decode closed internal state to enums when it removes repeated string comparisons. Do not add an
  enum layer that immediately converts back to strings everywhere.
- Keep UI labels separate from wire identifiers. User-facing text may change; stored/wire identifiers
  require migration.
- Constants use `UPPER_SNAKE_CASE`. Include units or meaning where ambiguity exists, such as `_MS`,
  `_BYTES`, `_COUNT`, `_FRACTION`, `_VERSION_CODE`, `KEY_`, or `TAG`.
- Time fields end in `Ms`; monotonic timestamps include `Elapsed` when useful. Coordinates and bounds
  name their coordinate space when local/window/stack distinctions matter.

Large immutable protocol snapshots are acceptable. At construction and mapping sites:

- Use named arguments for long constructors and whenever adjacent arguments share the same type.
- Use distinct field names and sentinel values in mapping tests so transposed values cannot pass.
- Group fields only when the group is coherent and reused or materially reduces mapping/lifecycle
  risk. Avoid wrappers that only move a long argument list elsewhere.

## 3. Nullability, validation, bounds, and fail-closed behavior

- Accept null at Android, Binder, reflection, JSON, preference, or view-discovery boundaries. Convert
  it immediately to a validated value, documented default, or disabled capability.
- Avoid `!!` in production. A missing view, symbol, payload field, user, or geometry input is a normal
  compatibility condition, not proof that null is impossible.
- Validate before expensive allocation or decode: caller UID/package, protocol version, user ID,
  generation, sequence, revision, payload byte/character length, list count, text length, timing,
  source range, hash shape, enum/string membership, and finite numeric values.
- Character and UTF-8 byte limits are separate. Configuration remains below the 64 KiB hard limit;
  lyric/document counts and text use their existing explicit bounds.
- Check `Float`/`Double.isFinite()` before clamping. Clamp positions and durations only after rejecting
  impossible or hostile values at the trust boundary.
- Use `SystemClock.elapsedRealtime()` for freshness, projection anchors, retry/timeout logic, and
  lifecycle timing. Wall-clock time is only for human evidence timestamps.
- Validation returns a complete valid value or rejects/fails closed. Do not partially update global
  state before all required checks pass.
- Clear/stale/disconnect/user-switch results must not retain renderable content that can be replayed.
- Unknown Xiaomi versions, symbols, themes, displays, modes, notification geometry, or coordinate
  transforms disable only the dependent feature. They never guess a nearby member, reveal lockscreen
  content, mutate stock UI, or activate AOD keepalive/clock ownership.
- Optional content degrades in the documented order: hide optional widgets/rows, shrink within bounds,
  then hide the custom scene. Never force stock layout to make room.

## 4. Thread ownership

### App process

- Binder/provider ingress validates and publishes owned immutable state.
- Gzip, JSON/document decode, large-input hashing, SAF I/O, and shell/process work run off main.
- Mutable projection session state has one explicit serialized owner. Acceptable implementations are
  synchronized access plus generation rechecks, a single coroutine event loop, or a dedicated serial
  dispatcher. Multiple `Dispatchers.Default` coroutines must not publish from the same session state
  without a final current-generation/current-state check.
- `Job.cancel()` is not synchronization. A terminal pause/clear/release must prevent an already-running
  projection from publishing a newer visible state afterward, using join/serialization or a generation
  check immediately before publication.

### SystemUI process

- View creation, attachment, measurement/layout calls, visibility, animation, `Handler` queues,
  surface-controller state, and transition state are main-thread owned.
- Binder callbacks may run on Binder threads. They validate/extract an owned bounded message, then post
  latest-only work to main. They do not touch views.
- State shared between Binder and main threads uses one clear strategy: synchronization around the
  pending message/generation, or immutable message passing. Do not mix guarded and unguarded access.
- Do not call subscribers, framework callbacks, renderer callbacks, or Binder methods while holding a
  monitor when those calls can re-enter. Snapshot targets/state under lock, then call out.
- Document non-obvious ownership in class KDoc or a focused comment. `@MainThread`/`@WorkerThread` may
  add tooling signal, but annotations do not replace synchronization.

## 5. Handler, Runnable, and generation lifecycle

- Cancellable delayed/repeating work uses a stable `Runnable` instance or scheduler handle so the
  exact callback can be removed. A local one-shot is acceptable only when it cannot outlive or be
  superseded by its owner.
- Remove a pending replacement before posting the new one. Coalesce high-frequency geometry/state
  updates to latest-only work.
- Use a monotonically increasing generation/token whenever the target can be replaced: Binder binding,
  AOD attachment, lockscreen blueprint, user session, linkage handoff, animation, stale expiry, or
  delayed Xiaomi policy replay.
- A callback verifies its captured generation/token before touching owner state or a View. Where state
  can change during work, recheck before the final publication/mutation.
- Detach, unbind, clear, user switch, Binder death, feature disable, and controller replacement cancel
  pending work, advance generation where applicable, clear pending payloads, and release references.
- Self-rescheduling frame/heartbeat work checks active/visible/attached state before work and before
  reposting. It stops when content is static, hidden, stale, or detached.
- A completed delayed callback clears its owner field in `finally` when the field still points to that
  callback. Do not retain an old View, Xiaomi controller, Activity, Binder callback, or plugin class
  loader after completion.
- Retries and timeouts are named, bounded, and lifecycle-cancellable. No unbounded fast retry loop.

## 6. Binder callbacks and immutable payload extraction

- One-way Binder callbacks return quickly. No JSON decode, disk I/O, package-manager query,
  reflection, view work, large hashing, or renderer mapping occurs before return.
- Never retain the Binder-delivered `Bundle`, `Parcel`, mutable parcelable collection, or framework
  object after the callback returns.
- Preferred path: synchronously extract bounded primitives/strings into an immutable wire payload,
  reject malformed data, store only the latest payload for the current binding generation, and post
  it to main.
- Lyric state keeps the callback `Bundle` only as an ABI envelope. Full snapshots use one bounded
  encoded body; hidden and keepalive messages remain scalar-only. The Binder-delivered `Bundle` and
  encoded body are released before callback return, and only the decoded immutable message reaches
  `SystemUiLyricProjection`. Configuration likewise uses immutable primitive extraction because delayed
  `Bundle` copying previously caused a SystemUI OOM regression.
- Validate the configuration envelope before JSON decode: protocol, user, nonnegative revision,
  lowercase SHA-256 shape, character length, and UTF-8 byte length. SystemUI validates decoded schema
  again.
- Register/unregister, disconnect, binding death, null binding, and retry use one idempotent reset
  pattern and reject stale connection callbacks by generation and identity.
- Every exported producer/callback endpoint validates caller UID/package. Cached verdicts remain
  bounded and never convert an unknown caller into an allowed caller.
- Additive AIDL methods/fields require explicit compatibility and tests. Never reorder an existing
  AIDL callback method.

## 7. Reflection, exceptions, and logging

### Reflection and hooks

- Reflect exact verified class names, method names, parameter types, fields, and package version
  profiles. Capability detection and hook installation describe the same symbol contract.
- Missing optional symbols disable a capability. Missing required symbols abort only that hook family.
  Do not scan similarly named methods or silently hook a neighboring overload.
- Search superclasses only for a documented Xiaomi hierarchy variation.
- Set accessibility in the lookup/install path. Cache stable `Method`/`Field` objects used afterward.
  Never reflect from `onDraw` or a frame callback.
- Hook installers are idempotent per class loader. Deduplication references are weak where plugin reload
  must remain collectible.
- Hooks preserve Xiaomi ownership and call `chain.proceed()` exactly as required. Argument replacement
  uses supported libxposed APIs; do not mutate immutable `chain.args`.

### Exceptions

- Catch the narrowest expected recoverable exception. `Exception` is acceptable at a trust or
  compatibility boundary when the operation is intentionally fail-closed and consequence is explicit.
- `runCatching` is acceptable for a small optional lookup, Binder call, bind/unbind, parse attempt, or
  capability probe. It is not a blanket wrapper for a long lifecycle/render mutation.
- Because `runCatching` catches `Throwable`, SystemUI mutation code must not suppress
  `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath`, or other VM-fatal errors. Prefer `try/catch
  (Exception)` there, or explicitly rethrow fatal errors before fallback handling.
- A recoverable failure rejects input, disables the dependent capability, performs idempotent cleanup,
  or schedules a bounded retry. Empty catches are forbidden.
- Put mandatory cleanup in `finally`. Cleanup is safe to call repeatedly and on partial state.

### Logging

- `error`: an invariant/operation failure that makes the dependent feature unusable or leaves cleanup
  uncertain.
- `warn`: rejected malformed input, optional symbol loss, bounded retry/fallback, or recoverable
  framework/Binder failure needing diagnosis.
- `info`: module/hook installation, capability summary, attach/detach, connection state, accepted
  linkage/ownership transition, and change-only geometry/eligibility decisions.
- Normal debug and release builds compile with `TRACE_LOGGING_AVAILABLE=true`, but persisted runtime
  diagnostic logging defaults off. The app publishes the effective flag through the compiled
  configuration bridge so app-process and SystemUI `info` tracing changes without a restart.
- `-PtraceLogging=false` is a hard compile-time ceiling for a deliberately stripped artifact. Runtime
  preferences cannot bypass it. `warn` and `error` remain unconditional in every build.
- A finite fixed-stage SystemUI bootstrap series is the sole `info` exception to the runtime trace
  gate when trace logging is compiled in. It exists to diagnose a missing bridge/configuration path;
  it may contain only a fixed stage name and bounded build, probe-count/profile, user, attempt, and
  process-class scalars.
- Diagnostic logging is runtime state, not appearance/profile state. Do not include it in imported or
  exported customization documents. Hot-path counters and diagnostic-string construction must check
  the runtime trace gate before doing work.
- Deduplicate or rate-limit recurring diagnostics. Never log per frame, every 16/100/250 ms tick,
  every heartbeat, or unchanged eligibility/geometry.
- Do not log full lyrics, metadata, imported JSON, Binder payloads, user identifiers, file content, or
  shell output. Log revision/generation/token and fail-closed consequence when useful.

## 8. View measure, layout, and draw separation

- Surface controllers discover Xiaomi hosts, compute transforms/placement, and attach, measure, and
  lay out module views. Render Views do not choose their host or mutate Xiaomi parents.
- AOD uses a manually measured/layout child in the inner `AODView` root overlay. It never enters
  `mTableModeContainer` measurement or changes stock clock measurement, content, style, or lifecycle.
- Lockscreen uses the verified index-0 child under `keyguard_translation_info`, remains visual-only,
  and inherits native parent alpha/scale/visibility. No click, long-click, focus, touch interception,
  or accessibility focus.
- `setContent`/configuration changes normalize values, resolve palette/typeface/style, snapshot outgoing
  transition state, and rebuild content-dependent layout.
- `onSizeChanged` and padding/size changes rebuild size-dependent layout.
- `onMeasure` determines the module View's own dimensions only. No reflection, Binder work, or parent
  mutation.
- `onDraw` reads cached layout/render state and performs elapsed-time projection, clipping, alpha,
  transforms, and draw calls. It does not load assets, query preferences, reflect, create Binder data,
  change layout parameters, call `requestLayout()`, or perform file/package I/O.
- Restore every Canvas save/saveLayer. When drawing outgoing/incoming snapshots, preserve and restore
  Paint/typeface/palette state so a new line cannot restyle the old snapshot.
- Name and convert coordinate spaces explicitly. Do not mix root-local, parent-local, stack-local, and
  window coordinates.
- Repeated geometry callbacks compare resolved bounds before replacing layout parameters or requesting
  layout. Prevent recursive `requestLayout()` loops.

## 9. Allocation policy for AOD/SystemUI hot paths

Hot paths include timed `onDraw` at 16 ms, demo ticks, media progress at 250 ms, position callbacks,
and repeated notification geometry callbacks.

- Move text measurement, wrapping, ruby/source mapping, row partitioning, typeface loading, palette
  resolution, and stable geometry construction to content/size/configuration changes.
- Reuse Paints, typefaces, arrays, and mutable Android geometry objects owned by the View/controller.
- Avoid collection pipelines (`map`, `filter`, `filterNot`, `buildList`), regex, substring creation,
  string interpolation, new `Rect`/`Paint`/`Bundle`, reflection, and diagnostic-string construction in
  steady-state frame code when an indexed loop or cached result is practical.
- Small bounded existing allocations are not proof of a user-visible defect, but are not the preferred
  pattern. Remove deterministic per-frame allocation when changing the affected code.
- Do not claim zero allocation, battery improvement, jank reduction, or safe software-layer cost from
  inspection alone. Use allocation/frame/device evidence.
- Only visible or transitioning surfaces schedule frames. Hidden/static/detached surfaces stop.
- Intentional recurring allocation or software-rendering cost in AOD/SystemUI needs a concise rationale
  and device evidence before broad enablement.

## 10. Naming, function structure, arguments, and comments

### Naming

- Types are nouns. `Controller` owns lifecycle/mutation; `Coordinator` combines owners; `Store` owns
  validated state; `Resolver`/`Engine` computes policy; `Codec` encodes/decodes; `Hook`
  installs/intercepts; `Snapshot`, `State`, `Environment`, and `Result` are immutable values.
- Functions are verbs. Predicates start with `is`, `has`, `can`, `should`, or `supports`.
- Boolean names are positive and scoped: `aodEnabled`, `sceneVisible`,
  `positionFollowingEnabled`. Avoid double negatives.
- Use `current`, `latest`, `pending`, `cached`, `expected`, and `active` only when their lifecycle
  meanings differ and are clear.

### Functions and files

- Prefer one primary responsibility per function and one primary production type per file. Related
  small immutable models/helpers may share a file.
- There is no mechanical line-count limit. Extract when a function mixes validation, decoding,
  persistence, lifecycle mutation, geometry, and drawing; when nesting obscures fail-closed exits; or
  when extraction creates a useful pure test seam.
- Large renderer/controller files may remain cohesive when splitting would duplicate mutable state or
  add allocation/indirection. New work should still isolate pure decisions from Android lifecycle code.
- Use early returns for failed guards and unsupported capabilities. Keep the successful path readable.

### Arguments

- Prefer named arguments in multiline calls and calls with multiple adjacent Booleans, Strings,
  numbers, nullable values, or collections.
- A parameter object is justified when a coherent group crosses several methods or boundaries, not
  only to satisfy an arbitrary argument count.
- Avoid sentinel-heavy nullable argument trains. Use a validated immutable value or sealed result when
  states have materially different meanings.

### User-facing copy

- Prefer one concise, self-explanatory heading, label, or row title. Do not add subtitles, helper text,
  or descriptive copy beneath headings, labels, cards, or settings by default.
- Add supporting copy only when the user asks for it, or when it is required to prevent misunderstanding
  or error: an unavailable or disabled capability, a power/performance cost, an external prerequisite,
  a nonobvious scope, or a destructive confirmation. Never use it to restate the heading.
- A preference `summary` that shows the current value or runtime state is not descriptive copy and is
  not covered by the rule above.
- Data-handling and policy disclosure in the diagnostic report flow is required consent text, not
  helper copy. Keep it complete.

### User-facing forms

- Multiline input starts at top/start. A task-focused placeholder disappears after input begins.
- Put a compact live limit counter inside the field at bottom/end. Reserve padding so text never
  overlaps it. Keep byte-bound contracts byte-accurate even when the counter copy is terse.
- Do not bury limits in placeholder prose or repeat policy explanations beside every action. Keep
  labels short and put detailed disclosure in the linked policy.
- Primary actions may use the full row. Secondary actions must accommodate translated labels with
  wrap-content height, bounded wrapping, or stacking; fixed-height clipping and overflow are defects.
- All user-facing form text comes from locale XML, including placeholders, counters, states, and
  actions.

### Comments and formatting

- Explain invariants, ABI constraints, Xiaomi quirks, coordinate spaces, lifecycle ownership,
  fail-closed reasons, sanctioned parity deltas, and device-observed workarounds.
- Do not narrate obvious Kotlin syntax.
- A Xiaomi workaround names the verified symbol/version context and points to the governing research,
  diagnostic, behavior spec, or implementation-status evidence.
- Temporary traces include a bounded purpose/removal condition and are removed after evidence is
  captured. Never leave lyric-content tracing enabled in production.
- Follow existing Kotlin formatting: four spaces, no tabs, standard naming, no wildcard imports, and
  formatter-friendly multiline calls with trailing commas where used. Do not reformat unrelated work.

## 11. Tests

- Test names are lower camel case and state behavior/condition/outcome, for example
  `activeNotificationStateWithoutCachedBoundsFailsClosed`.
- A nontrivial test separates arrange, act, and assert with blank lines. Keep one behavior under test.
- Use pure JVM tests for bounds, normalization, projection identity, placement, timing, row selection,
  source/ruby ranges, state machines, generation/token rejection, capability policy, and schema
  security.
- Extract the smallest pure decision helper instead of building mock-heavy Android tests.
- Boundary tests cover valid, missing, malformed, oversized, stale, duplicate/reordered,
  future-version, wrong-user, non-finite, invalid-range, and unknown-enum/value cases as applicable.
- Lifecycle tests cover current, stale, superseded, cancelled, detached, user-switched, disconnected,
  reversed, and timeout callbacks.
- DTO/codec mapping tests use distinct sentinel values for every same-typed field.
- A correctness fix adds the smallest focused regression test that would fail before the fix, then the
  relevant broader host gate runs.
- Host tests prove deterministic logic, not Xiaomi runtime behavior, visual parity, power, process
  memory, hook viability, or animation smoothness.

## 12. Documentation and device evidence

- Update the governing spec before or with a behavior change. Implementation status does not silently
  override architecture or behavior contracts.
- Update `docs/ARCHITECTURE.md` for package/process ownership, Binder flow, trust boundaries,
  capability gates, surface attachment, lifetime policy, or customization schema changes.
- Update `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` for visibility, privacy, collision, continuity,
  keepalive, fallback, or customization-policy changes.
- Update the relevant research/diagnostic record for Xiaomi symbol, hook, geometry, or version findings.
  Mark historical diagnostics as superseded rather than presenting them as current architecture.
- Private implementation status records exact host-test count/result, build result, APK SHA-256,
  install time, device/serial, SystemUI PID/restart reason, capability summary, observed behavior,
  crash/OOM/safe-mode result, and remaining device gates.
- Use evidence labels precisely:
  - `unit-tested` / `host-verified`: deterministic local tests only;
  - `trace-observed`: supported by runtime logs/trace, not necessarily visually confirmed;
  - `device-smoke-tested`: module loaded and basic flow survived;
  - `device-verified`: the named behavior was exercised on the named build/device with required
    visual/log/crash evidence.
- Never claim device verification from code review, host tests, an old APK, or a screenshot that does
  not exercise the relevant flow.
- Proprietary APKs, JADX output, device logs, screenshots, and temporary captures remain under ignored
  `research/`. Do not copy internal research artifacts into public release repositories.
- Changes affecting SystemUI memory, AOD cadence/power, burn-in movement, linkage, lockscreen privacy,
  notification geometry, software rendering, or Xiaomi reflection require fresh device evidence before
  broad enablement.

## 13. Review rule

Apply this guide prospectively and during touched-code review. Existing deviations are candidates for
focused audit findings, not permission to repeat them and not justification for unrelated cleanup.
Prefer the smallest coherent correction with focused tests and honest host/device evidence.
