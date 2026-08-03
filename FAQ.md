# HyperGlow FAQ

### It does not work on my phone

Submit a compatibility report:

**HyperGlow → Report a problem → Compatibility**

Current testing covers one device only:

- Xiaomi 14 (`houji`).
- Android 16.
- SystemUI `16.03.251211.r` (`202501210`).
- Xiaomi AOD `DEV-2327.0.0.1-03022115` (`22327001`).

Other Xiaomi models and software versions may behave differently. Compatibility is not guaranteed.
Submit the report above so support can be evaluated from the exact SystemUI and AOD versions.

### Requirements

- Xiaomi HyperOS.
- Rooted LSPosed.
- Spicy EX active in Spotify.
- HyperGlow scoped to System UI.
- System AOD enabled for AOD lyrics.

### Which LSPosed scope?

System UI only.

Spicy EX uses a separate Spotify-only scope.

### No capability report appears

1. Launch HyperGlow once.
2. Verify LSPosed scope.
3. Reload the hooks or restart SystemUI.
4. Reopen HyperGlow.

Still missing? Submit a compatibility report.

### No lyrics appear

Check:

- Spotify actively playing.
- Spicy EX enabled.
- HyperGlow bridge enabled in Spicy EX.
- HyperGlow profile supported.
- Spotify and SystemUI bridge connections present.
- Current track has usable lyrics.

### Does it support non-Xiaomi devices?

No.

### Does Spicy EX Lite work?

Yes.

### Battery or burn-in risk?

Extended AOD use consumes more power and increases display-retention risk. Prefer a finite duration
and suitable movement mode.

### Does HyperGlow download lyrics?

No. Lyrics arrive locally from Spicy EX.

Network access is used only for an explicit diagnostic upload.

### Are diagnostics uploaded automatically?

No. Any diagnostic report upload requires your manual confirmation.
