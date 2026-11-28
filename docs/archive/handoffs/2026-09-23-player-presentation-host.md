# Handoff: one presentation host for the player (issue #43, slice A)

2026-09-23. Slice A of the plan recorded in
https://github.com/averylicious/muon/issues/40#issuecomment-5779229649: prepare the player's host
for finger-following opening **without changing user-facing gestures**. Slice B (driving the sheet
from the mini player) is not started here.

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, as frontend implementer. Astra
(GPT-6, Codex desktop) coordinates, owns CI and review.

## Design

### One vertical position owner

`PlayerSheet` (replacing `PlayerDismiss`) owns a single `Animatable` **position**: a fraction of the
sheet's own height, `0` open and `1` closed. It is the only thing that moves the player vertically:

- opening and closing animate it (replacing the player's `AnimatedVisibility` slide), and
- the top-bar dismiss drag moves it (replacing #85's separate pixel offset).

No additive translations remain. A fraction rather than pixels is deliberate: the sheet is mounted
before it has been measured, and a fraction stays correct if the window is resized mid-animation.
The sheet's measured height is recorded from `onSizeChanged` for converting finger travel.

### Four distinct states

- **Logical open:** `playerShown`, unchanged — still the only input to Back, Lyrics, the mini
  player's visibility and the tabs' accessibility clearing. Progress never decides navigation.
- **Previewing:** a drag is moving the position. Slice A has only the top-bar dismiss preview.
- **Settling:** an animation towards `0` or `1`, owned by the sheet.
- **Host presence:** `playerShown || position < 1`, read through `derivedStateOf`, so composition
  sees the boolean flip twice per transition and never the per-frame float.

Mounting on the logical open state, not on progress, avoids the zero-progress deadlock: an opening
sheet is composed at position `1` (off screen), measured, then animated in. A closing sheet stays
composed until the position reaches `1`, then leaves, which is where #82's predictive Back state is
disposed exactly as it was inside the old `AnimatedVisibility`. A sheet restored open after rotation
starts at `0`, so it does not replay its opening.

### Ownership of motion

The sheet has one job slot. Visibility changes (the driver) and drags both go through it. A drag
detector tracks the job it last launched and, when it is torn down, cancels and restores only if
that job is still the current one — so a closing animation already started by the driver is never
cancelled by a departing detector (which would strand the sheet half-way and keep it mounted), and a
move queued by the detector cannot survive a newer owner, because every owner cancels the slot first.
It restores only while the player is still logically open. This replaces #85's `committed` flag.

### Input and accessibility while leaving

While the sheet is mounted but no longer logically open, its content is cleared from semantics and
covered by a non-semantic touch blocker (`pointerInput(Unit) {}`, the idiom Material's `Surface`
uses), so partly visible controls cannot be tapped or focused on the way out. The scrim and the
sheet's own surface continue to block touches beneath.

### What stays the same

Tap to open, #84/#87's mini-player lift and trigger, #85's dismiss threshold, cancellation and
reopen rules, predictive Back (still its own layer), Lyrics (keeps its own `AnimatedVisibility`
host), the stationary scrim, rounded edge, pure black outline and #90's inset reclaim. Visible
difference: opening and closing are now a slide carrying the rounded edge, with no cross-fade.

### Back during an uncommitted opening preview (for slice B)

Slice B's preview will move the sheet while `playerShown` is still false. Back must then cancel the
preview (settle to `1`) and must not navigate the page underneath. Because Back already reads only
the logical state, that needs one handler enabled while previewing; it is recorded here so B does
not rediscover it.

## Implemented

The design above was judged bounded and implemented as designed, in one code commit after the
design commit.

- `app/src/main/java/dev/avery/muon/PlayerDismissDrag.kt` — `PlayerSheet` replaces `PlayerDismiss`;
  `playerSheetPresent` and `playerSheetFraction`; the drag layer (`playerSheet`) and inset reclaim
  read `position × height`; the dismiss detector converts finger travel to a fraction and tracks
  ownership of its own job instead of a `committed` flag.
- `app/src/main/java/dev/avery/muon/MuonApp.kt` — the player gets `PlayerHost`, driven by
  `LaunchedEffect(playerShown) { sheet.present(playerShown) }`; `FullScreenOverlay` returns to a
  plain host used only by Lyrics.
- `app/src/main/java/dev/avery/muon/NowPlayingScreen.kt` — the parameter type only.
- `app/src/test/java/dev/avery/muon/PlayerDismissDragTest.kt` — three policy tests.

Unchanged: Motion.kt (opening and closing keep the existing 250 ms curve; springs are for slice B),
MiniPlayer.kt, PlayerBack.kt, and every threshold.

## Branch and base

- Branch `codex/player-presentation-host`, worktree
  `/home/avery/.codex/worktrees/muon-presentation-host/muon`.
- Base: PR #90 head `f9994990925cfb026985affb527fb4bb81b2f5b1` (run 114 passed, artifact verified,
  Astra reviewed with no blockers). **#90 is a prerequisite**, stacked on #89 → #88 → #87 → #86 → #85.
- Two commits: the design checkpoint, then the implementation with this completed note. The exact
  range is from the base to the pushed head.

## Validated vs pending

- Three policy tests: an opening player is mounted before it has moved (the zero-progress deadlock);
  a closing player stays mounted until it has left, then is gone; an unmeasured sheet cannot be
  moved, and travel is clamped between open and closed. Existing dismiss, edge and inset tests are
  unchanged and still apply. These do not verify Compose input, rendering or lifecycle.
- Read the diff against the pinned APIs already verified in earlier slices (`Animatable`,
  `derivedStateOf`, lambda `offset`, `onSizeChanged`, `graphicsLayer`). **No local build** — no
  Gradle cache and no installed `platforms;android-36`.
- CI: triggered by the push, **not inspected**; Astra owns final-head CI and review.
- Astra review: pending. Phone QA: pending.

Manual checks that matter most, because no test reaches them: open and close by tap, collapse button,
Back and predictive Back; a top-bar drag short, long and cancelled; close and reopen quickly; rotate
with the player open (it must not replay its opening) and mid-animation; Lyrics in and out; empty the
queue and disconnect while open; try tapping controls and using TalkBack while the player slides away.

Visible difference to expect: opening and closing are a slide carrying the rounded edge, shadow and
pure-black outline, without the old cross-fade.

## Astra review of `df6ecff`, and the fix

Astra reviewed `df6ecff38f1fd7b4816e41389f4e6d77d7f00dd3` (CI run 115 passed) and found two issues,
both fixed in the head after it.

1. **Grabbing a moving sheet snapped it.** `onDragStart` stopped the opening or settle animation but
   reset the drag to zero, and each move derived the position from the new travel alone, so taking
   hold of the header part-way through opening or settling back snapped the panel to near the top.
   The detector now records the sheet's position at takeover as a **baseline** and places the sheet
   at baseline plus the finger's travel (`playerSheetDragged`). The 96 dp threshold still counts only
   the finger's own travel, so where the sheet already was never counts towards closing.
2. **A drag stopped without moving could strand the sheet.** `onDragStart` cancelled the running
   motion but owned nothing until the first move, so a detector torn down in between (a resize, or
   the player leaving) had no job of its own to restore. Takeover now *holds* the sheet where it is
   with a move of its own, so the detector owns the stopped motion from the start and teardown puts
   it back while the player is open. A newer presentation still replaces the job, so teardown never
   cancels it.

Ownership, as it now stands: takeover owns a hold; each move replaces it; a short, reversed or
cancelled drag owns the settle back; a committing drag gives ownership up to the presentation; the
presentation (`present`) always replaces whatever is running; teardown acts only on a job the
detector still owns, and only puts an open player back.

`playerDismissOffset` and `playerSheetFraction` were replaced by the baseline-aware
`playerSheetDragged` rather than left unused. Tests: the pixel-based drag tests are restated for it,
and three are added — a sheet grabbed mid-opening holds its place and can be carried either way; where
the sheet already was does not count towards closing; an unmeasured sheet stays where it was. The
drag-versus-surface test they supersede is removed. None of this verifies Compose input on a device.

## Status

Clean and idle once pushed. Stop after this slice; slice B is not started.
