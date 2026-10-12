# HyperGlow FAQ

## Compatibility

### Which phones are supported?

Current testing covers one device only:

- Xiaomi 14 (`houji`).
- Android 16.
- SystemUI `16.03.251211.r` (`202501210`).
- Xiaomi AOD `DEV-2327.0.0.1-03022115` (`22327001`).

Other Xiaomi models and software versions may behave differently. Compatibility is not guaranteed.

HyperGlow still attempts to run on untested builds. Each capability looks for the exact code it
needs, so a build that was never tested may work if it carries the same internals. If one part is
missing, only that part is disabled. The support state in settings shows which parts were found.

### When will HyperOS 4 be supported?

When my phone, the Xiaomi 14, gets a Xiaomi.eu HyperOS 4 ROM update. I need a real device to
inspect and test before I can maintain a new HyperOS generation.

### Can it work on Samsung, OnePlus, Oppo, or another phone?

I only maintain phones that I own and actively use. HyperGlow currently depends on Xiaomi HyperOS
internals. You are encouraged to fork the project and build the integration for your phone.

### It does not work on my phone

Submit a compatibility report:

**HyperGlow → Report a problem → Compatibility**

Include the exact device, Android version, SystemUI version, and AOD version. That information is
what makes an unsupported build fixable.

## Requirements and setup

### What do I need?

- Xiaomi HyperOS.
- Rooted LSPosed.
- Spicy EX active in Spotify.
- System AOD enabled for AOD lyrics.

### Do I need Spotify?

I only maintain integrations that I actually use, so Spotify is the maintained source here. If you
need Chinese app integration or features, check out the [CN+ fork](https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus).

### No capability report appears

1. Launch HyperGlow once.
2. Verify HyperGlow is enabled in LSPosed.
3. Reload the hooks or restart SystemUI.
4. Reopen HyperGlow.

Still missing? Submit a compatibility report.

### No lyrics appear

Check:

- Spotify is actively playing.
- Spicy EX is enabled.
- **Publish lyrics to HyperGlow** is enabled in Spicy EX.
- The HyperGlow profile is supported.
- Spotify and SystemUI bridge connections are present.
- The current track has usable lyrics.

## Power and privacy

### Is there a battery or burn-in risk?

Extended AOD use consumes more power and increases display-retention risk. Prefer a finite duration
and a suitable movement mode.

### Does HyperGlow download lyrics?

No. Lyrics arrive locally from Spicy EX.

Network access is used only for an explicit diagnostic upload.

### Are diagnostics uploaded automatically?

No. Any diagnostic report upload requires your manual confirmation.
