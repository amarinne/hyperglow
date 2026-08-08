# HyperGlow Diagnostic Reporting Spec

Status: implementation contract

HyperGlow can submit one user-triggered private report to the shared diagnostic intake. The app
never uploads in the background, creates a GitHub issue automatically, reads arbitrary files, or
accepts server-controlled hook configuration.

## User flow

1. The user opens `Report a problem`, selects a category, and enters a nonblank description bounded
   to 4,000 UTF-8 bytes. The multiline field is top-aligned, uses a disappearing task placeholder,
   and shows a live compact byte counter at its bottom end.
2. Compatibility reports run a fixed root-access check and use current metadata immediately when
   setup is healthy. If setup is failed because `capability_report` or `systemui_hook` is missing,
   Compatibility starts the same guided capture as runtime failures:
   start, reproduce outside HyperGlow, reopen diagnostics, then finish.
3. The app shows a readable, pretty-printed view of the included JSON plus a concise link to the
   public diagnostic data policy before upload.
4. The user accepts retention and manually uploads once. A manual retry reuses the same random
   `R1-` Crockford Base32 report ID.
5. A successful receipt shows the report ID and data-policy link. The GitHub action opens a formatted
   public issue draft containing the description, report ID, app version, device model, and
   compatibility summary only. The submitted JSON remains viewable and may be explicitly exported
   through Android's system file picker.

Pending local report data expires after 30 minutes. Cancellation, timeout, or successful upload
restores the diagnostic-logging value that existed before capture and deletes temporary report data.

## Intake contract

- URL comes from build-time `DIAGNOSTIC_INTAKE_URL`; official builds use
  `https://reports.eza.dpdns.org/v1/reports`. The legacy DXF host proxies to the same intake without
  redirects during migration.
- HTTPS `POST`, `application/json`, no redirects, credentials, embedded secret, automatic retry, or
  networking dependency. Request deadline: 15 seconds.
- Client report limit: 384 KiB UTF-8.
- Envelope versions are `envelopeVersion=1`, `product=hyperglow`, and
  `productReportVersion=2`. The intake accepts any positive product report version, validates known
  allowlisted fields, and drops unknown fields; envelope version remains the transport boundary.
- Success accepts `201` for a new report and `200` for an identical-ID retry. `400`, `409`, `413`,
  `429`, `503`, redirects, timeouts, and other 5xx responses remain distinct user-visible failures.
- The intake does not reject a report for failing schema validation. A payload the contract cannot
  map, and a report past the rolling global record cap, are stored verbatim as a quarantined row for
  later maintainer mapping or pruning, and still answer `201` with a normal receipt carrying the
  client's own report ID. Client behavior is unchanged; schema drift no longer loses a report.
- A request body over the size cap is still rejected, as are transport-level failures. Proxy limits
  remain authoritative for rate, concurrency, and body size.
- The public endpoint relies on proxy limits, the request size cap, and private maintainer triage. It
  does not authenticate an app installation.

## Collected data

All accepted report data is retained indefinitely until a maintainer manually deletes or redacts it:

- HyperGlow version/build type;
- manufacturer, brand, model, device, product, Android build/security/fingerprint, locales;
- fixed-allowlist Xiaomi properties;
- HyperGlow, SystemUI, Xiaomi AOD, and Spotify package versions;
- capability protocol/age, effective profile state, raw symbol probes, resolved capabilities;
- configured surface flags, callback presence, and privacy-safe Spotify producer status/age;
- capture outcome, root status, command failures, and truncation flags.
- bounded setup state and failure keys for root, SystemUI hook/report, profile support, Spotify
  producer bridge, and required package presence/version metadata.
- current Spotify track URI, title, artist, album, lyric provider/source, detected language, timing
  type, current line index, and bounded original/transliterated/translated lyric lines when present;
- user description;
- filtered HyperGlow logs;
- fixed SystemUI/HyperGlow process snapshot (`USER`, `UID`, `PID`, and bounded process name),
  prepended to those logs;
- fixed `/data/adb` framework evidence: LSPosed directories, matching module `module.prop`
  identity/version fields, root-solution markers, and selected LSPosed/LSPatch manager package names;
- allowed-process crash excerpt;
- HyperGlow-only LSPosed lines;
- fixed allowlist of runtime-setting values.

Never serialize artwork identity, Spotify credentials, cookies, account identity, Android ID,
serial, IMEI, Wi-Fi SSID, a complete installed-app inventory, customization documents, arbitrary
files, screenshots, full logcat, or unfiltered LSPosed logs. The process/framework evidence above
is explicitly fixed and allowlisted.
Known URI, URL, credential, and throwable-message patterns are redacted from captured lines.
Screenshots may be attached manually to the separately opened public GitHub issue when useful.

## Guided capture

Capture stores wall and elapsed start times plus the previous diagnostic-logging state. It enables
the existing runtime logging flag and publishes configuration to SystemUI. The active-capture
instruction tells the user to restart SystemUI inside the capture window so boot markers are
included. A non-exported alarm
expires the capture after 30 minutes; process startup also handles timeout or elapsed-clock reset.

Finish runs only fixed root commands. User text never enters a command. Each command has a five-second
timeout:

- the newest bounded HyperGlow-tagged main/system logcat slice (`-t 4000`), maximum 160 KiB;
- fixed SystemUI/HyperGlow process listing (`USER`, `UID`, `PID`, and bounded process name), merged
  into that bounded log section;
- crash-buffer blocks whose process is HyperGlow, SystemUI, or Spotify, maximum 64 KiB;
- lines from the newest LSPosed module log only, after a fixed 512 KiB tail bound, containing
  `HyperGlow` or `com.eza.hyperglow`, maximum 64 KiB.

Root denial produces a metadata-only report. Oversized sections preserve the first 25% and newest
75% with an explicit truncation marker. Line-based sections discard partial boundary lines so a
retained LSPosed fragment cannot lose its module-identity prefix.

SystemUI bootstrap emits a small fixed-stage series whenever trace logging is compiled in. These
events do not depend on bridge-delivered runtime diagnostic configuration, since they diagnose the
case where that bridge never connects. The first event includes the APK build code and declared
Xposed minimum/target API. Events contain only fixed stage names plus bounded build,
probe-count/profile, user, attempt, and process-class scalars; they contain no lyric, media, or
payload data.

## Compatibility status

Capability protocol v2 carries report time, effective profile state, experimental state, raw symbol
probes, and resolved capabilities. The app accepts v1 while app and SystemUI processes transition.

Runtime status is one of:

- `No SystemUI report`;
- `<n>/<total> hooks available`, the resolved capability count, reported as `available`;
- `Unsupported profile`.

A build is described by how much of it resolved rather than by how its SystemUI/AOD version pair
compares against any reference device. `Verified profile`, `Verified profile missing symbols`,
`Experimental eligible`, and `Experimental active` were produced by that comparison, which is
retired. They remain decodable so a report written by an older build, or sent by a hook process that
has not restarted, still classifies, and they still count as runnable.

Unsupported surfaces preserve stored configuration but cannot present it as active. Runtime-dependent
controls are disabled; appearance editors remain available.
