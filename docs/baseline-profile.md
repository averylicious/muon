# Baseline Profile (#83)

A Baseline Profile lists the classes and methods Muon uses on its most common paths: startup, the
library lists, an album and an artist page, and Now Playing. Android compiles those ahead of time
when the app is installed, instead of interpreting them on first use, which makes launch and first
scrolls smoother. Muon ships it in `app/src/main/baseline-prof.txt`. AGP packages it into every
build, R8 rewrites it for the release build, and `androidx.profileinstaller` installs it on phones
updated outside Play, such as through Obtainium.

## Recording it

The profile is recorded on a real phone, against the user's real Tauon library, and re-recorded when
those paths change a lot. Recording never touches Canary or Stable.

1. Run the **Baseline Profile tools** workflow (Actions → Run workflow). It also runs on pushes that
   change `baselineprofile/`. It uploads `baseline-profile-tools-<sha>` with two debug-signed APKs:
   - `app-benchmark.apk`: **Muon Benchmark** (`dev.avery.muon.benchmark`), Stable's code, unminified and profileable, in its own package;
   - `baselineprofile-benchmark.apk`: the generator (`dev.avery.muon.baselineprofile`), the journeys in `BaselineProfileGenerator.kt`.
2. On an Android 13+ phone on the same network as Tauon, with the user's go-ahead for device access:
   ```bash
   adb install -r app-benchmark.apk
   adb install -r baselineprofile-benchmark.apk
   adb shell cmd media_session volume --stream 3 --set 0        # a song plays; note the volume first
   adb shell am instrument -w -e class dev.avery.muon.baselineprofile.BaselineProfileGenerator \
       dev.avery.muon.baselineprofile/androidx.test.runner.AndroidJUnitRunner
   ```
3. Pull the result, reported in the instrumentation output (under
   `/sdcard/Android/media/dev.avery.muon.baselineprofile/`). Save `…-baseline-prof.txt` as it is, as
   `app/src/main/baseline-prof.txt`. It covers Compose and Media3 on these paths as well as Muon's
   own code.
4. Restore the volume, then uninstall both tools:
   `adb uninstall dev.avery.muon.benchmark` and `adb uninstall dev.avery.muon.baselineprofile`.
   These are the only packages ever uninstalled; Canary and Stable are never touched.
