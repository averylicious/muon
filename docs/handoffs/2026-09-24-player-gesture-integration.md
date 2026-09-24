# Handoff: player gesture and playback-preference integration (#100 + #101 + #103)

2026-09-24. Integration only — no new feature and no threshold change. Claude Opus 5.5
(`claude-opus-5-5`), Claude Code CLI, medium effort, as frontend owner and integrator of this branch.
Astra (GPT-6, Codex desktop) coordinates and owns final review, CI and merge.

## Inputs

- Main `ac7a345427bd0e4ce14c9c94bd69035d6669276e` (merge of #56 album metadata; published as Canary
  .136).
- PR #101 head `fc96c91a58c16e3863b5d3ec85bfdb8bb85c1e86` — short upward flick recognition; CI run 139
  passed and source-reviewed with no blockers, as verified by Astra.
- PR #100 head `3088dd4410b210f2d9e6539073d67e4285f31658` — spring settling with the opposite-bound fix;
  CI run 137 passed, including the real-animation tests, and source-reviewed with no blockers.

Branch `codex/player-gesture-integration`, worktree
`/home/avery/.codex/worktrees/muon-gesture-integration/muon`. Both PR heads are merged with `--no-ff`,
#101 first, then #100, so their commit ancestry is preserved and GitHub can mark both merged.

## The one conflict: the mini player's release

Only `MiniPlayer.kt` conflicted. #101 replaced `detectVerticalDragGestures` with its own gesture loop and a
`release(upMillis)` function; #100 had added a spring settle to the old `onDragEnd`. The resolution keeps
#101's loop unchanged and puts #100's settle into `release`:

- one velocity, `trace.releaseVelocity(upMillis)` — the finger-bound, event-time, `Impulse` measurement
  from #101 — is used both for the open/return **decision** and, converted by `sheetFractionVelocity`,
  for the **spring's initial velocity**;
- `if (mine) sheet.settleTo(if (opens) 0f else 1f, …)` runs before `current()` and `endPreview()`,
  exactly as #100 ordered it, so the presentation keeps the settle or replaces it if the open is refused;
- #100's `SystemClock` and 2D-tracker code in the old `onDragEnd` are gone with it.

Preserved from both: #101's finger-identity guard (handoff abandons the trace and commits nothing),
preview tokens and Back ownership (`playerPreviewOwned`), same-frame protection (`SheetTurn`), teardown
ending an owned preview; #100's `settleSheet` wrong-bound fix (velocity only towards the target, carried
velocity read before cancelling, one continuation from rest), bounds, `SheetSpring` and the dismiss
drag's settle. Every threshold is unchanged. #56's metadata is included through main.

## What a build of this branch adds over Canary .136

- Opening, closing and returning to the anchor settle on a non-bouncy spring carrying the release's
  speed, and cannot stop at the wrong edge (#100).
- Short, quick upward flicks on the mini player open the player; a second finger can no longer be read as
  the first (#101).

Everything else — album metadata, direction guard, grabber anchoring, release intent and velocity expiry
— is already in .136.

## Checks

- Read the merged `MiniPlayer.kt` against both PRs; no conflict markers remain; no stray `SystemClock`
  or old tracker references. **No local Android build.** The merged result's tests (`MiniPlayerFlickTest`,
  `SheetSettleTest`, `SheetSpringAnimationTest`, the existing suites) run first in CI.
- CI for this branch: triggered by the push, **not inspected**. Astra reviews the final combined diff.
- Phone QA: pending, for the combined behaviour: #101's manual steps (short flicks, hold, jab, downward,
  taps, second finger) and #100's (spring feel, reopen near the end of a close, animations off).

## Added afterwards: #103, shuffle and repeat persistence

PR #103 head `9ac6005bc335e614fcf50b1feac5175be2fa5adf` (source-reviewed; its own CI was still pending
when merged here) is merged into this branch with `--no-ff` on top of the #100/#101 integration. It
touches only `PlaybackService.kt`, the new `PlaybackModePreferences.kt`, its test and its handoff, and
merged **without conflicts**. Shuffle and repeat are restored before the media session exists and saved
per mode on every change (see `2026-09-24-playback-mode-preferences.md`, including the disclosed
`apply()` durability limit).

A build of this branch therefore adds over Canary .136: #100's spring settling, #101's short-flick
recognition, and #103's shuffle/repeat persistence. Current main (Canary .136) contains none of them.

## CI failure and correction

Run 141 (`35961042810`) on #103's head `9ac6005` **failed** in `compileDebugUnitTestKotlin`:
`PlaybackModePreferencesTest.FakePlayer`'s `var shuffle` / `var repeat` generate JVM setters
`setShuffle(Boolean)` / `setRepeat(Int)` that clashed with the fake's own methods of the same names. #103
was corrected at `9a551adbffb15306c36e2628ea83d18a484601e7` (test-only rename to `changeShuffle` /
`changeRepeat`; behaviour unchanged) and that correction is merged here. App code was not affected.

## Exact pending state

- Previous integration head `cdbe5a9` had CI run 140 pending; #103's run 141 **failed** (above) and is superseded by the
  correction. **Nothing is claimed as passed here.** The merge of #103 needs its own new CI run on this branch's head (recorded in
  issue #40, not here, to avoid a self-referencing SHA).
- Astra owns the final combined review, CI, merge and Canary publication. No device QA has been recorded
  for any of the three changes.
- Collection readiness (#44 comment 5808414738) is a set of **proposals**, not approved decisions; the
  earlier Q3 status may be stale until the original decision is reviewed. No new screen scope here.

## Status

Clean and idle once pushed.
