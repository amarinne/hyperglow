# HyperGlow Diagnostic Data Policy

HyperGlow sends a diagnostic report only after you open **Report a problem**, review the included
data, accept this policy, and tap **Upload once**. There are no background uploads, analytics,
remote configuration, automatic GitHub issues, cookies, or embedded API credentials.

## Included data

- Your description and chosen category.
- HyperGlow, System UI, Xiaomi AOD, Spotify, Android, device, build, and locale metadata.
- HyperGlow capability/symbol results and allowlisted runtime settings.
- Current song title, artist, album, Spotify track URI, lyric provider/language/timing information,
  and bounded current original/transliterated/translated lyric lines when available.
- If you explicitly run guided capture: filtered HyperGlow logs, allowed-process crash excerpts, and
  HyperGlow-only LSPosed lines.
- If guided capture runs with root: a fixed process snapshot for SystemUI and HyperGlow containing
  USER, UID, PID, and bounded process name; selected framework evidence under `/data/adb`, including
  presence of the LSPosed log directories, matching module `module.prop` id/name/version/versionCode,
  detected root-solution marker (`ksu`, `ap`, or `magisk`), and only manager package names matching
  `lsposed`, `lspatch`, or `edxposed`.

## Never included

- Artwork identifiers.
- Spotify tokens, cookies, account details, Android ID, serial, IMEI, or Wi-Fi SSID.
- Full logcat, unfiltered LSPosed logs, screenshots, imported customization files, arbitrary files,
  or a complete installed-app inventory. Framework evidence is limited to the fixed paths and
  selected package-name patterns listed above.
- Your source IP in the application or report record. Network infrastructure may process it
  normally while handling the HTTPS request.

## Storage and retention

Reports are private. Accepted report data is retained indefinitely until a maintainer manually
deletes or redacts it. There is no automatic expiry. Temporary report data on the phone expires after
30 minutes and is deleted after cancellation or successful upload.

If the intake cannot map a report onto its known fields, it stores the report exactly as your phone
sent it instead of discarding it, so a newer app version is never silently dropped. That stored copy
holds only what this policy already describes, is private in the same way as every other report, and
a maintainer can delete or redact it on request.

The report ID is a private-storage reference, not a public download key. It cannot retrieve report
contents from the intake endpoint.

## GitHub issues

Opening GitHub creates a separate public draft containing your description, report ID, HyperGlow
version, device model, compatibility summary, song identity, provider, language, and timing type.
Lyric text, private diagnostic logs, and settings are not added to the GitHub issue. Screenshots can
be attached manually in GitHub when useful.

To request deletion or redaction, open a HyperGlow issue with the report ID and the requested action.
Do not post additional private diagnostic data in GitHub.
