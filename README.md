<div align="center">

# HyperGlow

Animated lock screen and always-on display lyrics for HyperOS 3.

Requires root, LSPosed and [Spicy EX](https://github.com/amarinne/spicy-ex).

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

JDK 21 and an Android SDK are required.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest :app:assembleDebug
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleRelease
```

## License

[GPL-3.0](LICENSE). See [NOTICE](NOTICE).
