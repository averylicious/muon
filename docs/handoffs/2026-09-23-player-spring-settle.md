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
- **Bounded, so nothing can overshoot.** The position `Animatable` is bounded to `0..1`
  (`updateBounds`). An animation that reaches either end stops there with `BoundReached`. Past open it
  could otherwise lift the sheet off the bottom and show the library beneath, and around closed it
  could flicker the host in and out.
- Direct finger tracking is untouched: drags still `snapTo`, with no animation between the finger and
  the sheet.

## Verified against pinned sources (animation-core 1.9.3)

`spring(dampingRatio, stiffness, visibilityThreshold)`; `Animatable.updateBounds` and
`animateTo(target, spec, initialVelocity)`, whose initial velocity defaults to the current one, so a
reopen mid-close turns the sheet round without a jolt; and `doAnimationFrameWithScale`, where a
duration scale of 0 plays the animation's whole duration at once, so springs end immediately when
system animations are off.

## Preserved

#98's direction guard and grabber anchoring; #99's release intent and velocity expiry; preview
tokens, Back ownership and same-frame protection (`SheetTurn`); cancellation, controller loss and
rapid-reopen settling; host presence (the sheet settles exactly to `1` and unmounts); the scrim, which
still follows the logical state rather than the position; predictive Back and accessibility.

## Files

`Motion.kt`, `PlayerDismissDrag.kt`, `MiniPlayer.kt`, `SheetSettleTest.kt` (new), this note.

## Validated vs pending

- `SheetSettleTest`: a release already heading the right way keeps its speed; one going the other way,
  or none, is replaced; finger speed converts to the sheet's units with direction kept, and an expired
  flick or unmeasured sheet hands on nothing. The bounds, spring feel and animation-scale behaviour are
  source-verified, not unit- or device-tested.
- No local Android build. CI: triggered by the push, **not inspected**. Astra review and phone QA
  pending. Stiffness and threshold are starting values; tuning remains pending.

## Manual QA

1. Flick the mini player up: the player carries on up at the flick's speed and settles smoothly, with
   no bounce and no gap at the bottom.
2. Pull up slowly past a third and let go: it springs open. Pull up a little and let go: it springs back.
3. From the open player, flick the top bar down: it continues down and closes cleanly; the dim fades
   and nothing is left over the library.
4. Pull the top bar down a little and let go: it springs back open with no overshoot above the top.
5. Close, then reopen during the close: it turns round smoothly.
6. With system animations turned off (Developer options): every open, close and return jumps straight
   to its end.
7. Tap to open, collapse button, Back and predictive Back, Lyrics, queue emptying and disconnect: as on
   #99.

## Status

Clean and idle once pushed.
