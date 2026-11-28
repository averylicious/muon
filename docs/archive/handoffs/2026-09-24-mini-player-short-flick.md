# Handoff: recognise short upward flicks on the mini player

2026-09-24. Fix for #43's QA on Canary .134 (issue #43 comment 5795045596): short intentional upward
flicks on the mini player returned or barely revealed the player, while longer motions worked. Every
other .134 check passed.

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, frontend owner. Astra (GPT-6, Codex
desktop) coordinates, reviews and owns CI. Branch `codex/mini-player-short-flick`, worktree
`/home/avery/.codex/worktrees/muon-short-flick/muon`, based on main
`ac7a345427bd0e4ce14c9c94bd69035d6669276e` (includes #98, #99 and #56). **Independent of #100.**

## Cause, from pinned sources rather than the video

1. **Too few samples reads as no velocity.** The 2D `VelocityTracker` fits each axis with `Lsq2`,
   whose `minSampleSize` is 3. With fewer samples inside its window, `calculateVelocity` returns 0
   (ui 1.9.3, `VelocityTracker.kt:259-273`). The detector recorded only moves after the touch slop,
   one position per event, with no touch-down, no batched historical points and no lift. A brief flick
   delivering the slop-crossing event and at most one more before lifting therefore measured as
   motionless, so it could only commit on distance, which a short flick never has.
2. **The lift was timed by processing, not by the event.** `detectVerticalDragGestures` exposes
   neither the touch-down, the batched points nor the up event (foundation 1.9.3,
   `DragGestureDetector.kt:529-558`), so #99's expiry compared the last move with
   `SystemClock.uptimeMillis()` at processing time. A late release could expire a real flick.
3. **The slop was excluded from a flick's travel,** so a short flick had even less to count towards the
   24 dp minimum.

The video is consistent with this, but it is a source-grounded mechanism, not a device measurement.

## Fix

The mini player uses the same structure as `detectVerticalDragGestures` — `awaitEachGesture`,
`awaitFirstDown(requireUnconsumed = false)`, `awaitVerticalTouchSlopOrCancellation`, then
`awaitVerticalDragOrCancellation` until the lift — which are all public in foundation 1.9.3. What
changes is what it records, in a new pure `FlickTrace`:

- the touch-down; the slop-crossing event's batched `historical` points and position (the slop helper
  does **not** expose the intermediate events before that crossing, so those are not recorded); each
  later move with its historical points; and the lift, as a sample only if it moved;
- positions in the mini player's own coordinates, which do not move while it carries a preview, so
  they are the finger's movement alone;
- the lift's own event time for the expiry (#99's 40 ms rule, unchanged), not processing time;
- travel from touch-down, so the part of a flick spent inside the slop counts.

Only the release **decision** uses the trace. The sheet still follows the post-slop travel exactly as
before, so tracking and slop are unchanged. The thresholds are unchanged too: this fixes the
measurement rather than lowering the bar.

## Astra review of `305e701`, and the fix

Astra found two issues, both confirmed in the pinned sources and fixed:

1. **Another finger could be read as this one.** `awaitDragOrUp` hands a gesture over to another
   pressed pointer when the tracked finger lifts (`DragGestureDetector.kt:794-800`), and the slop
   helper does the same before the slop. `record` then fed the other finger's absolute Y into the same
   trace, fabricating travel and velocity. `FlickTrace` now belongs to the finger that touched down: a
   sample from any other finger abandons it, and an abandoned trace reports no travel or velocity. The
   detector cancels at the slop if the crossing belongs to another finger (nothing has begun yet), and
   mid-drag on any handoff, ending an owned preview without committing. Handoff is not supported; the
   gesture simply ends.
2. **The sampling was overclaimed, and sparse traces needed a better estimator.** The handoff said
   every sample was recorded; the slop helper hides the events before its crossing, and the wording now
   says exactly what is recorded. Working through a genuine three-event trace (down, crossing, lift at
   the same position) also showed that `Lsq2` cannot handle it: with the unchanged lift as a third
   point, the exact quadratic fit reads a stop, and without it there are only two points and `Lsq2`
   returns nothing. The trace now uses the tracker's `Impulse` strategy (`VelocityTracker1D(
   isDataDifferential = false)`, public in ui 1.9.3, minimum two samples) and adds a sample only where
   the finger moved, so an unchanged lift contributes its time to the expiry but not a fake stop.

Late callbacks after Back are still refused by `playerPreviewOwned`; cancellation still commits nothing.

## Preserved

Downward drags begin no preview; taps and the play/next buttons (slop is not crossed, nothing is
consumed); Back and preview tokens (`playerPreviewOwned`), same-frame protection (`SheetTurn`);
cancellation (a consumed or cancelled pointer ends the preview without committing); teardown ending an
owned preview; stationary-hold expiry. The fullscreen dismissal is untouched (`PlayerDismissDrag.kt` is
not modified).

## Overlap with #100, for whoever merges second

#100 (spring settling) also edits the mini player's release. After this PR, its change belongs in the
new `release(upMillis)` function: compute `val velocity = trace.releaseVelocity(upMillis)` once, pass
it to `playerPreviewOpens`, and before `if (opens) current()` add #100's
`if (mine) sheet.settleTo(if (opens) 0f else 1f, sheetFractionVelocity(velocity, sheet.height))`.
#100's `SystemClock` use in `MiniPlayer.kt` is no longer needed there. #100's `PlayerDismissDrag.kt`
changes do not overlap this PR.

## Validated vs pending

- `MiniPlayerFlickTest` runs realistic sparse traces through the real trackers and release policy:
  two post-slop samples read as zero velocity under the old `Lsq2` recording (the old failure); a brief
  90 px / 40 ms flick opens, counting its slop; **a genuine three-event down / crossing / lift trace
  with no historical points opens, with the lift unchanged and with it moving**; the same flick held
  before lifting returns; the lift is judged by its own event time; a fast tiny jab, a downward flick
  and a mostly sideways gesture do not open; a real flick cannot open for a stale or ineligible gesture;
  **a far-apart second finger's position is never read as travel, and a slop crossed by another finger
  is not this gesture**; a new gesture forgets the last.
- Not unit-testable here: the pointer loop itself (slop crossing, consumption, pointer switching).
- No local Android build. CI: triggered by the push, **not inspected**. Astra review and phone QA
  pending.

## Manual QA (Canary from this PR's run)

1. Short, quick upward flicks on the mini player — the kind that failed on .134 — open the player.
2. A short **slow** pull returns; a flick followed by holding still before lifting returns.
3. A very brief jab does not open; a downward flick does nothing and does not dim the library.
4. Tapping the mini player opens it; play/pause and next still work and never open it.
5. Longer pulls and flicks still behave as on .134, and the sheet still tracks the finger.
6. Back during a held upward drag, rapid close/reopen, queue emptying and disconnect: as on .134.
7. The fullscreen top-bar dismissal is unchanged.

## Exact pending state

PR #101 on `codex/mini-player-short-flick`. First head `305e701` was blocked by Astra's review; the fix
head is recorded in issue #40 and the PR (not here, to avoid a self-referencing SHA) and has **not**
been CI-checked or re-reviewed. #100 (spring settling) remains separate; the merge overlap above
still applies, with `release()` computing velocity from `trace.releaseVelocity(upMillis)`.

## Status

Clean and idle once pushed.
