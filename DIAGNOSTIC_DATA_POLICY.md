# HyperGlow Diagnostic Data Policy

HyperGlow sends a diagnostic report only after you open **Report a problem**, review the included
data, accept this policy, and tap **Upload once**. There are no background uploads, analytics,
remote configuration, automatic GitHub issues, cookies, or embedded API credentials.

## Included data

- Your description and chosen category.
- HyperGlow, System UI, Xiaomi AOD, Spotify, Android, device, build, and locale metadata.
- HyperGlow capability/symbol results and allowlisted runtime settings.
- If you explicitly run guided capture: filtered HyperGlow logs, allowed-process crash excerpts, and
  HyperGlow-only LSPosed lines.

## Never included

- Lyrics, song titles, artists, albums, track URIs, or artwork identifiers.
- Spotify tokens, cookies, account details, Android ID, serial, IMEI, or Wi-Fi SSID.
- Full logcat, unfiltered LSPosed logs, screenshots, imported customization files, or arbitrary files.
- Your source IP in the application or NocoDB report record. Network infrastructure may process it
  normally while handling the HTTPS request.

## Storage and retention

Reports are private. Accepted report data is retained indefinitely until a maintainer manually
deletes or redacts it. There is no automatic expiry. Temporary report data on the phone expires after
30 minutes and is deleted after cancellation or successful upload.

The report ID is a private-storage reference, not a public download key. It cannot retrieve report
contents from the intake endpoint.

## GitHub issues

Opening GitHub creates a separate public draft containing your description, report ID, HyperGlow
version, device model, and compatibility summary. Private diagnostic logs and settings are not added
to the GitHub issue.

To request deletion or redaction, open a HyperGlow issue with the report ID and the requested action.
Do not post additional private diagnostic data in GitHub.
