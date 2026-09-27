# Material 3 Expressive experiment (Canary only)

This branch, `claude/project-thread-ef0w2w`, tries Material 3 Expressive on Muon Canary. It never merges to `main`, and Stable is untouched. Test builds come from the branch's own **Android APKs** runs: the `app-debug-<commit>` artifact updates Muon Canary in place.

## What changed in the build

- **Material 3 1.5.0-alpha29**, named directly, because the newest Compose BOM (2026.09.00) still maps material3 to 1.4.0 stable. The alpha pulls in the Compose 1.13 foundation, runtime and animation alphas.
- **AGP 9.4.1 and Gradle 9.6.0.** The alpha's AAR metadata requires AGP 9.1 or newer and compileSdk 37. AGP 9 compiles Kotlin itself, so the modules no longer apply `org.jetbrains.kotlin.android`; the root still declares it (not applied) to pin the Kotlin Gradle Plugin at the project's version.
- AGP 9 defaults that had to be restored: `resValues` (the launcher labels) is enabled in `app`, and `android.onlyEnableUnitTestForTheTestedBuildType=false` keeps CI's release unit tests.

## What to try on the phone

**Settings → Material 3 Expressive experiment** has one switch per part, so each can be compared with it off on the same phone.

- **Expressive motion.** Material's own controls use `MotionScheme.expressive()`. Muon's transitions use Expressive's springs instead of eased tweens: what moves or resizes (overlays, pages, the rail, the mini player, list reordering) uses the default spatial spring, which overshoots a little; what fades or changes colour uses the effects springs, which don't. Full-screen overlays (Lyrics, Queue) use the spatial stiffness without the overshoot, so their far edge never lifts off the screen. Pull to refresh and the Connect scan show the shape-morphing `LoadingIndicator`. The player sheet's own settle spring is unchanged.
- **Blur behind the player.** The library blurs under Now Playing in proportion to how far the player has risen, so dragging it or previewing it from the mini player blurs as the finger moves. The song actions sheet blurs the library behind it too. The existing dimming stays on top. Blur needs Android 12 or newer; older phones keep the dimming alone.

## Known limits

- Blur is Compose's `RenderEffect` on the library's own layer, not the system's cross-window blur, so it matches Android 17's look rather than reproducing SystemUI's tuning. It costs a GPU pass per frame while the player is up; watch for dropped frames on the Poco.
- Alpha APIs can change or vanish before 1.5.0 is stable. Anything adopted from here needs re-checking against the stable artifact.
