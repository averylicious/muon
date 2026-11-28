# Overlay exit admission and mini player error copy — 2026-10-05

Base main `cee7864fcf6b44436d1b739cf88d364b376a65f5`, branch `codex/overlay-exit-admission`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Implementation and self-check by the author, **not independent review**. Follows findings 1 and 2 of the separate source-only UI focus boundary report (`eb20d15`, `docs/audits/2026-10-05-ui-focus-boundary.md`, not on this base). Not compiled locally: Android CI is the first compile. Manual QA is **pending user testing**.

## Source evidence

- **Compose's published sources at BOM `2025.10.00`,** as checked by the coordinator:
  - **animation-android 1.9.3**, sources SHA256 `8b6272d8af64354c29e237c850400776d04c3553536745d3faf99317f71f238c`. `AnimatedVisibility.kt` 668-777 keeps exiting content composed until its post-exit state. `EnterExitTransition.createModifier` applies geometry and graphics only, with no semantics or touch guard.
  - **ui 1.9.3**, sources SHA256 `9fa260e2e6d2a8e312103a4409ffe8f0ec93cab07bb9c682c03347a9c71ad8ee`. In `InnerNodeCoordinator.kt` 208-237, the last sibling that is hit blocks other paths.
- **The pattern is already in use:** `PlayerHost` (`MuonApp.kt`) clears the content's semantics and puts a last-sibling `matchParentSize().pointerInput(Unit) {}` cover over it when the player isn't logically open.

## Changes

1. **`FullScreenOverlay`** (`MuonApp.kt`), used by Lyrics and Queue, now matches `PlayerHost` while `visible` is false:
   - its content is wrapped in `clearAndSetSemantics {}`;
   - a non-semantic cover is laid over it to take new touches.

   **Unchanged:**
   - open content;
   - the enter and exit transitions and `safeDrawingPadding`;
   - Back (still the one `backTarget` decision);
   - the overlay state and gestures.

   Reopening during the exit turns `visible` true again, which removes the cover and restores the semantics. The KDoc now names Queue as well as Lyrics.
2. **`MiniPlayer` error line:** "Playback interrupted · tap to retry" becomes "Playback interrupted · open player to retry".
   - **Unchanged:** tapping still opens Now Playing, and the "Retry stream" action there is unchanged. No automatic retry was added.

## Limits

- **Not covered:**
  - **Keyboard focus:** `clearAndSetSemantics` removes accessibility semantics, not hardware keyboard or D-pad focus.
  - **Touches already in progress:** a touch that began before the close keeps its own stream; the cover only refuses new touches.
- **Not established from source:** TalkBack traversal, the timing of the exit, and how it looks on the phones.
- **No test added:** there is no Compose UI harness. A unit test would only restate the boolean or the copied layout.

## Manual checks (pending user testing)

- **Closing lyrics and queue:** tap where a lyrics Retry or a queue row was during the slide-down; nothing on the outgoing screen should react. With TalkBack, swipe right after closing: focus shouldn't land on the outgoing screen.
- **Quick reopening:** close and immediately reopen lyrics or the queue. The screen should come back fully usable, with semantics and touch restored.
- **Error flow:** in the mini player's error state, the line says "open player to retry". Tapping opens Now Playing, and "Retry stream" there retries.
- **Normal use:** lyrics and queue open, scroll and work as before, and Back steps down through Now Playing.

**Checks run locally:** `git diff --check` and the CI prose check. No Gradle build, no device.
