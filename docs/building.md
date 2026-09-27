# Building Muon

Most builds happen in CI: every push builds signed APKs (see [CI and signing](ci.md)). Building locally is possible, but it's slow on the user's desktop, and agent sessions there usually lack the Android SDK. For agents, CI is the first real compile.

## Requirements

- JDK 17.
- The Android SDK platform and build tools named in `app/build.gradle.kts` (`compileSdk`, `buildToolsVersion`). Point to the SDK with `ANDROID_HOME`, or with `sdk.dir=` in an untracked `local.properties`.
- Nothing else. The Gradle wrapper pins its own distribution with a checksum, and every dependency version lives in the build files. Read versions there; they aren't repeated in the docs, where they would go stale.

## Commands

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. The build types are:

| Build type | App | Package | Signed with |
|---|---|---|---|
| `debug` | Muon β (Canary) | `dev.avery.muon` | the debug key |
| `release` | Muon (Stable), minified | `dev.avery.muon.release` | the release key |
| `benchmark` | Muon Benchmark, for recording the [Baseline Profile](baseline-profile.md) | `dev.avery.muon.benchmark` | the debug key |

Canary is built non-debuggable on purpose, so phone testing sees real performance.

## Signing

- **Debug key:** kept in the ignored `.local/debug.keystore`. Keep it: an APK signed with a different debug key can't update the installed Canary. A fresh checkout can generate a new one, so restore the original, or install a CI artifact instead.
- **Signed release build:** `python3 tools/build-signed.py` reads a local signing backup (`.local/signing/`) without putting passwords on the command line. Without it, `assembleRelease` builds an unsigned APK.
- **Recovery:** key recovery and the CI secrets are covered in [CI and signing](ci.md). Never commit keys or passwords.

## Tests

- **Unit tests** (`app/src/test`, JVM) cover the pure logic: sorting, grouping, ReplayGain, the colour scheme's contrast, offline bookkeeping and gesture maths. There are no Compose UI tests; the user does the phone QA.
- **Tauon probe:** `python3 tools/probe_tauon.py http://<tauon>:7814` finds a downloadable FLAC in Tauon's playlists and checks that range requests return the original bytes. It keeps what it fetches in the ignored `proof-private/`.
- **Device playback test:** `tools/device-proof.sh <adb-serial> http://<tauon>:7814 <track-id>` installs the debug and androidTest APKs and runs `DirectPlaybackTest` through the real MediaSession. It checks progress, seek, pause/resume, next/previous and the notification. It plays audio briefly, and it needs the user's go-ahead for device access.
- **Tauon's range handling:** `python3 tools/test_tauon_ranges.py` tests the installed Tauon's `send_file` on its own.
- **CI's own tests:** `tools/test_ci_scope.py` and `tools/test_release.py` guard the workflow's scope and publication rules.

The first real-track milestone was proved this way on the Pixel 8 before the full UI existed. See [validation](validation.md).
