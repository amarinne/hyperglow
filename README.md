<div align="center">

# HyperGlow

Animated lock screen and always-on display lyrics for HyperOS 3.

Requires root, LSPosed and [Spicy EX](https://github.com/amarinne/spicy-ex).

<img src="assets/aod-clock.gif" width="240" alt="AOD lyrics with the stock clock">
<img src="assets/aod-image.gif" width="240" alt="AOD lyrics with a custom image">
<img src="assets/lockscreen.gif" width="240" alt="Lock screen lyrics">

</div>

## Features

- Spicy EX lyrics on the HyperOS lock screen and AOD.
- Line-, word- and syllable-synchronized karaoke.
- Transliteration and translation with Spicy EX Full.

- AOD clock placement and burn-in movement.
- Keep AOD active while lyrics are visible.
- Keep the lock screen awake while music is playing.
- Raise to show AOD instead of the full lock screen.

## Requirements

- Rooted HyperOS 3.
- [LSPosed](https://github.com/LSPosed/LSPosed).
- Spotify.
- Spicy EX Lite or Full with **Publish lyrics to HyperGlow** enabled.

## Install

APK from [Releases](https://github.com/amarinne/hyperglow/releases).

1. Enable Spicy EX for **Spotify** in LSPosed.
2. Enable HyperGlow in LSPosed.
3. Enable **Publish lyrics to HyperGlow** in Spicy EX.
4. Set HyperGlow battery usage to **No restrictions**.

> [!NOTE]
> Tested on Xiaomi 14 (`houji`).
> Will eat battery.
> `Raise to show AOD` requires the system **Raise to wake** option enabled.

## Build

JDK 21 and an Android SDK are required. No credentials or accounts are needed — every dependency
resolves from Google's Maven repository and Maven Central.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

This source tree tracks development and is usually ahead of the latest release, so a build from
`main` may contain changes that have not been through device testing yet. Releases are published
after debugging settles or once enough feature work has accumulated; install those if you want the
tested version.

That produces an installable debug APK under `app/build/outputs/apk/debug/`. Released builds are
signed with a private key that is not in this repository, so a build from source will not share the
signing lineage of the published releases: installing your own build over a release requires
uninstalling first, and the LSPosed module has to be re-enabled afterwards.

## Contributing

Read the specs in [`docs/`](docs/) before changing behavior — `ARCHITECTURE.md` for process
boundaries and trust, `LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` for lockscreen/AOD visibility, lifetime, and
power rules, `STYLE_GUIDE.md` for conventions. They are the contract; code that contradicts them is
a bug even when it works.

This repository is generated from a private working repository, so a few things are worth knowing
before opening a pull request:

- Changes are limited to what exists here. A pull request that adds files outside this tree cannot
  be integrated as written.
- `README.md`, `FAQ.md`, and `.gitignore` are generated. Edits to them are lost; raise the change in
  an issue instead.
- Every accepted change is verified on the maintainer's device before release. Unit tests passing is
  necessary, not sufficient — anything touching SystemUI hooks, AOD power, or geometry needs
  hardware verification that cannot run in CI.
- Large or architectural changes are worth discussing in an issue first, so the design can be
  checked against the specs before you build it.

## License

[GPL-3.0](LICENSE). See [NOTICE](NOTICE).
