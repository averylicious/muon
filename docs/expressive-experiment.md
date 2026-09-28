# Material 3 Expressive experiment (Canary only)

This branch, `claude/m3-expressive-alpha`, tries Material 3 Expressive on Muon Canary. It never merges to `main`, and Stable is untouched. Test builds come from the branch's own **Android APKs** runs: the `app-debug-<commit>` artifact updates Muon Canary in place.

## What changed in the build

- **Material 3 1.5.0-alpha29**, named directly, because the newest Compose BOM (2026.09.00) still maps material3 to 1.4.0 stable. The alpha pulls in the Compose 1.13 foundation, runtime and animation alphas.
- **AGP 9.4.1 and Gradle 9.6.0.** The alpha's AAR metadata requires AGP 9.1 or newer and compileSdk 37. AGP 9 compiles Kotlin itself, so the modules no longer apply `org.jetbrains.kotlin.android`; the root still declares it (not applied) to pin the Kotlin Gradle Plugin at the project's version.
- AGP 9 defaults that had to be restored: `resValues` (the launcher labels) is enabled in `app`, and `android.onlyEnableUnitTestForTheTestedBuildType=false` keeps CI's release unit tests.

## What to try on the phone

**Settings → Material 3 Expressive experiment** has one switch per part, so each can be compared with it off on the same phone.

- **Expressive motion** covers the motion and every Expressive component:
  - Material's own controls use `MotionScheme.expressive()`. Muon's transitions use Expressive's springs instead of eased tweens: what moves or resizes (overlays, pages, the rail, the mini player, list reordering) uses the default spatial spring, which overshoots a little; what fades or changes colour uses the effects springs, which don't. Full-screen overlays (Lyrics, Queue) use the spatial stiffness without the overshoot, so their far edge never lifts off the screen. The player sheet's own settle spring is unchanged.
  - The mini player grows into Now Playing and shrinks back into it: Material's container transform (`PlayerMorph.kt`). The player starts as the mini player's rectangle, grows to the whole screen and fades in over it, while the cover flies from the thumbnail to its place in Now Playing and the controls fade in behind it. Opening, closing and both drags morph, and the finger still moves the player one to one. Sideways, where the player is a side panel, it slides in as before.
  - Songs, Albums, Artists and Playlists are a connected button group (`ToggleButton`s), as is the cache limit in Settings, replacing chips and segmented buttons. The navigation bar is `ShortNavigationBar`, Expressive's flexible bar.
  - Now Playing's Previous, Play and Next are a `ButtonGroup`: the pressed button widens and squeezes its neighbours. Shuffle and Repeat are tonal toggle buttons that turn from round to a rounded square when on.
  - The play button, in Now Playing, the mini player and the side panel, morphs between `MaterialShapes`: a nine-sided cookie while paused, a rounded square while playing, turning a little as it goes.
  - Icon buttons, Play and Shuffle on albums, artists and playlists squash while pressed.
  - Settings rows are `SegmentedListItem`s, whose corners morph while pressed; Settings and Queue use `LargeFlexibleTopAppBar`, and Settings' subtitle names the installed build.
  - Pull to refresh and the Connect scan show the shape-morphing `LoadingIndicator`; connecting and downloads show wavy progress bars. While a track buffers, the seek bar itself turns into a wavy bar in its own place (a plain one with Expressive motion off), so nothing around it moves; it waits 300 ms first, so the brief stall at each skip does not flicker it. The seek bar is otherwise flat, as decided on #40.
- **Blur** covers two things:
  - Now Playing sits on its song's cover, blurred and stretched behind the whole player under a veil of the cover-tinted background, so each song glows in its own colours. It is blurred once per song on a 40 px copy, so it costs nothing per frame and works on every Android version. Pure black keeps its black player.
  - The library blurs behind the player in proportion to how far the player has risen, and behind the song actions sheet. This part needs Android 12 or newer.

## Known limits

- The library blur is Compose's `RenderEffect` on the library's own layer, not the system's cross-window blur, so it matches Android 17's look rather than reproducing SystemUI's tuning. It costs a GPU pass per frame while the player is up; watch for dropped frames on the Poco.
- Alpha APIs can change or vanish before 1.5.0 is stable. Anything adopted from here needs re-checking against the stable artifact.
- With Expressive motion on, a Settings switch row is announced as a switch, but the rows no longer carry the custom click labels ("Refresh library", "Disconnect") that the hand-built rows had.
- Frosted glass for the mini player, the tab bar and the song menu (the list's covers blurring through them) is not built yet. Compose has no built-in backdrop blur, so it needs a small layer-recording helper of our own.
