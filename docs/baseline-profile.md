# Baseline Profile (#83)

A Baseline Profile lists the classes and methods Muon uses on its most common paths: startup, the
library lists, an album and an artist page, and Now Playing. Android compiles those ahead of time
when the app is installed, instead of interpreting them on first use, which makes launch and first
scrolls smoother. Muon ships it in `app/src/main/baseline-prof.txt`. AGP packages it into every
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

1. Run the **Baseline Profile tools** workflow (Actions → Run workflow). It also runs on pushes that
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
