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

- the touch-down, every event's batched `historical` points, the moves and the lift itself;
- positions in the mini player's own coordinates, which do not move while it carries a preview, so
  they are the finger's movement alone;
- the lift's own event time for the expiry (#99's 40 ms rule, unchanged), not processing time;
- travel from touch-down, so the part of a flick spent inside the slop counts.

Only the release **decision** uses the trace. The sheet still follows the post-slop travel exactly as
before, so tracking and slop are unchanged. The thresholds are unchanged too: this fixes the
measurement rather than lowering the bar.

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

- `MiniPlayerFlickTest` runs realistic sparse traces through the real `VelocityTracker` and release
  policy: two post-slop samples read as zero velocity (the old failure); a brief 90 px / 40 ms flick
  opens, counting its slop; the same flick held before lifting returns; the lift is judged by its own
  event time; a fast tiny jab, a downward flick and a mostly sideways gesture do not open; a real flick
  cannot open for a stale or ineligible gesture; a new gesture forgets the last.
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

## Status

Clean and idle once pushed. Reserve kept for one review fix; no further feature started.
