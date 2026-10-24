# Baseline Profile (#83)

A Baseline Profile lists the classes and methods Muon uses on its most common paths: startup, the
library lists, an album and an artist page, and Now Playing. Android compiles those ahead of time
when its shipped profile is installed, reducing interpretation on first use. Whether this improves
Muon startup or scrolling still needs matched measurements. Muon ships it in `app/src/main/baseline-prof.txt`. AGP packages it into every
build, R8 rewrites it for the release build, and `androidx.profileinstaller` installs it on phones
updated outside Play, such as through Obtainium.

The current profile was recorded on 2026-09-27 on the Pixel 8 (Android 17) against the user's
962-song library, from commit `ef5db40`. It is about 32,000 rules, 2,500 of them Muon's own, covering
startup, all three library lists, an album and an artist page, the mini player, Now Playing and the
Queue. It predates both the Material 3 Expressive layout and the checks below, so nothing yet shows
that every one of those journeys actually ran when it was recorded.

## What a recording checks

The generator rejects a run when a required journey step is missing. Each step waits up to
10 seconds (30 for a song to start streaming) for where it should land, and stops with a message
naming what did not appear:

- the library's Songs, Albums and Artists buttons, and a list to fling in each;
- an album page and an artist page, by their back buttons ("Back to albums", "Back to artists"), and
  the library again once each is closed;
- a song playing (a Pause button), Now Playing ("Collapse the player"), the Queue ("Back to Now
  Playing") and the return to Now Playing; then the song paused and Now Playing closed.

Which album, artist and song are opened is still chosen by position: the topmost whole row or tile
of the view's largest list, skipping its order button. The page check above is what confirms the
tap went where it should. If a step fails, the generator pauses Muon Benchmark's song when it can,
and reports the original failure. A failed run must not replace the shipped profile.

## Recording it

The profile is recorded on a real phone, against the user's real Tauon library, and re-recorded when
those paths change a lot. Recording never touches Canary or Stable.

1. Run the **Baseline Profile tools** workflow (Actions → Run workflow), leaving **measurement** off.
   Check `Benchmark minified: false` in `BUILD.txt`. It also runs on pushes that
   change `baselineprofile/`. It uploads `baseline-profile-tools-<sha>` with two debug-signed APKs:
   - `app-benchmark.apk`: **Muon Benchmark** (`dev.avery.muon.benchmark`), Stable's code, unminified and profileable, in its own package;
   - `baselineprofile-benchmark.apk`: the generator (`dev.avery.muon.baselineprofile`), the journeys in `BaselineProfileGenerator.kt`.
2. On an Android 13+ phone on the same network as Tauon, with the user's go-ahead for device access.
   Install Muon Benchmark, open it once and connect it to Tauon, typing the address if the scan finds
   nothing (it didn't on 2026-09-27). The recording starts from the library, not the Connect screen.
   On Android 17 the generator grants Muon Benchmark local network access itself, and stops if the
   grant does not hold; earlier versions have no such permission, and it skips the step. Keep the
   phone upright: the journeys expect the phone layout, with its mini player.
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
   On failure, fix what its message names and run it again. Older exported files may remain; use only
   the output reported by a successful run, never a leftover file.
4. Restore the volume, whether or not the run passed, then uninstall both tools:
   `adb uninstall dev.avery.muon.benchmark` and `adb uninstall dev.avery.muon.baselineprofile`.
   These are the only packages ever uninstalled; Canary and Stable are never touched.


## Matched first-frame experiment

This is a prepared experiment, not a measured speedup. It compares the **same APK** with
`CompilationMode.None()` and `Partial(BaselineProfileMode.Require, warmupIterations = 0)` using
`StartupTimingMetric`, cold process starts and ten iterations each. A missing shipped profile fails
the required-profile mode. Both modes still use Android's compiled framework code.

The controlled journey is fresh Connect-screen **first frame**, on API37+ with an API37+ target and Benchmark's LAN
permission denied. It excludes changing server responses/discovery and does not measure a loaded
library, time to usable playback, scrolling or total app benefit. Benchmark storage opt-in stays
disabled. Filesystem/OS caches are not flushed; document that state and counterbalance run order.
No user Stable/Canary package or phone-wide network setting is changed.

1. Obtain current authorization for device testing and for clearing the disposable **Muon Benchmark**
   app's data. This deletes its connection/test downloads, not Stable/Canary data. Never target the
   user's frozen RC. The instrumentation requires an explicit reset argument and has a fixed package.
2. Dispatch **Baseline Profile tools** with **measurement** on. Download its two APKs together and
   verify their checksums, commit and `Benchmark minified: true` in `BUILD.txt`. This build keeps the
   existing Benchmark package, uses release minification/resource shrinking and is non-debuggable/
   profileable. It replaces only an existing Benchmark install. Recording and measurement helpers
   reject the opposite target mode. Keep one pair of APKs for the entire A/B comparison.
3. Install that pair on a physical API37+ phone in its active owner user (work/secondary users are refused). Keep device/API, battery/charging, thermals, display
   settings and background load controlled and recorded; use an idle phone. Do not suppress benchmark
   configuration errors to obtain numbers. Run only this class, not the whole instrumentation APK:
   ```bash
   adb shell am instrument -w -e class dev.avery.muon.baselineprofile.StartupBenchmark \
       -e muonAllowBenchmarkDataReset true \
       dev.avery.muon.baselineprofile/androidx.test.runner.AndroidJUnitRunner
   ```
   Each test clears Benchmark data **before** the compilation mode is applied, denies only its LAN
   permission, checks the Connect destination and stops only Benchmark afterward. No audio plays.
   Do not grant its LAN permission or seed a library during this first-frame experiment.
4. Repeat batches in both orders (select `StartupBenchmark#coldStartupWithRequiredShippedProfile`
   and `StartupBenchmark#coldStartupWithoutAheadOfTimeCompilation` individually when counterbalancing).
   Keep the APK hash unchanged. Review the generated JSON/trace results, individual samples,
   medians/distributions and thermal warnings. Retain raw local results; inspect traces for private
   host/device information before sharing. Neutral or negative results are valid.
5. Keep results separate from recorder traversal and later seeded-library/frame experiments. Do not
   replace `baseline-prof.txt` with measurement output. To record later, install recording-mode tools
   and reconnect Benchmark; its previous setup was deliberately cleared. Remove only the two tool
   packages after authorized testing if no longer needed.

CI compilation and APK inspection do not prove profile application or runtime benefit. See the
[maintained #83 state](audits/issues/83.md) for current heads and pending physical acceptance.


Both tool APKs now use the repository's pinned debug certificate. Earlier helper builds used a
runner-generated default key and may refuse an update from a new tool build. If that happens during
authorized testing, preserve any needed exported results first and remove only the old
`dev.avery.muon.baselineprofile` helper before installing the new pair. Never remove Stable/Canary
or change their signing identities to work around a tool installation error. The workflow verifies
both APK signatures, target mode, package identities and self-instrumentation before upload.

Recorder Queue lookup checks the landscape icon/portrait button, then tries at most four upward content swipes inside Benchmark's visible default-display window. It uses observed package-window bounds and refuses a detected foreground/default-display mismatch, instead of whole-display coordinates. A missing control still fails the journey; short-window/large-font traversal remains device acceptance, not a source/compile claim.

Install the two tool APKs from the same source-labelled artifact. CI embeds its full commit in both manifests and the helper; the upload gate checks it against the workflow source. Recording and measurement verify the installed target's matching commit and signing identity **before** changing Benchmark permissions or clearing its data. An old/mixed pair is refused. Local tools require an explicit `MUON_BENCHMARK_COMMIT` full40-character Git commit; the default `local` marker cannot authorize a run. This label prevents accidental source mixing, not independent authentication of compiler/dependency provenance.
