# Handoff: spring settling for the player sheet

2026-09-23. Motion slice of #43: the player opens, closes and returns to its anchor on a spring that
carries the release's speed. Resisted overdrag and list overscroll are separate later slices.

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, frontend owner. Astra (GPT-6, Codex
desktop) coordinates, reviews and owns CI. Branch `codex/player-spring-settle`, worktree
`/home/avery/.codex/worktrees/muon-spring-settle/muon`, built on PR #99's final head
`a70641f56e03711db9453e045a63d9b9e8f66ea5` (CI run 133 passed, source reviewed). **Prerequisites: #98
then #99.** User QA on Canary .130 covers #98's direction and anchor fixes only.

## What changed

- **Spring instead of fixed curves** for every settle of the sheet: opening, closing, and returning to
  the anchor after a short pull. `SheetSpring` (in `Motion.kt`) is non-bouncy with
  `StiffnessMediumLow`, and its visibility threshold is 0.0005 of the sheet's height (about a pixel),
  so the spring runs until it has arrived rather than finishing with a visible jump.
- **The release's speed is handed on.** Each detector converts its expired-or-fresh release velocity
  (#99) into fractions of the sheet per second (`sheetFractionVelocity`) and starts the settle with it
  (`PlayerSheet.settleTo`). The presentation that follows a committed or returned release keeps that
  settle if it is already going the right way (`playerSheetKeepsSettle`), and otherwise replaces it —
  which is how a refused open still puts the sheet away.
- **Bounded to `0..1`** (`updateBounds`), so a settle cannot lift the sheet past open or oscillate
  around closed. *Correction:* the first revision claimed this alone made settling safe. It did not —
  see the review fix below: a bound reached on the **wrong** side also ends the animation.
- Direct finger tracking is untouched: drags still `snapTo`, with no animation between the finger and
  the sheet.

## Verified against pinned sources (animation-core 1.9.3)

`spring(dampingRatio, stiffness, visibilityThreshold)`; `Animatable.updateBounds` and
`animateTo(target, spec, initialVelocity)`; and `doAnimationFrameWithScale`, where a
duration scale of 0 plays the animation's whole duration at once, so springs end immediately when
system animations are off.

## Astra review of `52d03a2`, and the fix

Astra's blocker (https://github.com/averylicious/muon/pull/100#issuecomment-5794470992) reproduces in
the pinned source. `Animatable.runAnimation` clamps each frame, and **any** clamp ends the animation
with `BoundReached` — whichever bound it hit and whatever the target. A settle whose initial velocity
points away from its target (a reopen near the end of a close, or a return carrying the opposite
momentum) can reach the opposite bound and stop there, leaving the sheet closed while the player is
logically open, or the reverse. A cancelled animation resets its velocity only when it next runs
(`endAnimation`), so a new `animateTo`'s default initial velocity could still carry the old one.

Fixed in `settleSheet`:
- Initial velocity is handed on only when it points towards the target (`sheetSettleVelocity`). Speed
  towards the target is kept, so flick handoff is preserved, and an overshoot then stops at the target's
  own bound. A reopen mid-close now turns round from rest rather than carrying its closing speed.
- The presentation reads the carried velocity **before** cancelling the running settle, so it no longer
  depends on scheduling.
- If a bound short of the target still ends the animation, it finishes from rest, once. Cancellation by
  a drag or a newer presentation still ends it at once, including between the two steps, so no stale
  coroutine carries on over a newer presentation.

`SheetSpringAnimationTest` runs the real spring on a real bounded `Animatable` under a deterministic
1 ms frame clock: it reproduces the raw API stopping at the wrong edge (0.99, velocity +6, target 0 →
`BoundReached` at 1), then shows `settleSheet` arriving open and closed in both mirrored cases, and
speed towards the target ending exactly there. Plain JUnit runs Compose snapshot state, but I could not
run it here; CI is its first run.

## Preserved

#98's direction guard and grabber anchoring; #99's release intent and velocity expiry; preview
tokens, Back ownership and same-frame protection (`SheetTurn`); cancellation, controller loss and
rapid-reopen settling; host presence (the sheet settles exactly to `1` and unmounts); the scrim, which
still follows the logical state rather than the position; predictive Back and accessibility.

## Files

`Motion.kt`, `PlayerDismissDrag.kt`, `MiniPlayer.kt`, `SheetSettleTest.kt` and `SheetSpringAnimationTest.kt` (new), this note.

## Validated vs pending

- `SheetSettleTest`: a release already heading the right way keeps its speed; one going the other way,
  or none, is replaced; finger speed converts to the sheet's units with direction kept, and an expired
  flick or unmeasured sheet hands on nothing. `SheetSpringAnimationTest` runs the real bounded spring on a
  deterministic clock (see the review fix). Spring feel and animation-scale behaviour remain
  source-verified, not device-tested.
- No local Android build. CI: triggered by the push, **not inspected**. Astra review and phone QA
  pending. Stiffness and threshold are starting values; tuning remains pending.

## Manual QA

1. Flick the mini player up: the player carries on up at the flick's speed and settles smoothly, with
   no bounce and no gap at the bottom.
2. Pull up slowly past a third and let go: it springs open. Pull up a little and let go: it springs back.
3. From the open player, flick the top bar down: it continues down and closes cleanly; the dim fades
   and nothing is left over the library.
4. Pull the top bar down a little and let go: it springs back open with no overshoot above the top.
5. Close, then reopen during the close: it turns round (from rest) and opens.
5a. **Astra's case:** close, and tap the returning mini player just as the close finishes: the player must end fully open, never stuck closed with the library showing.
6. With system animations turned off (Developer options): every open, close and return jumps straight
   to its end.
7. Tap to open, collapse button, Back and predictive Back, Lyrics, queue emptying and disconnect: as on
   #99.

## Exact pending state

- PR #100, branch `codex/player-spring-settle`. First head `52d03a2` passed CI run 135 but is
  **blocked** by Astra's review; the fix head (recorded in issue #40, not here, to avoid a
  self-referencing SHA) has **not been CI-checked or re-reviewed**.
- Main is `b204397`, containing #98 and #99; Canary .134 is published and is the build to test. Do not
  recommend #100's artifacts until the fix passes CI and recheck.
- User QA on .134: all checks fine **except** short upward mini-player flick recognition (issue #43
  comment 5795045596). That is the next, separate slice, not part of this fix.
- Not in scope: short-flick recognition, resisted overdrag, list overscroll.

## Status

Clean and idle once pushed.
