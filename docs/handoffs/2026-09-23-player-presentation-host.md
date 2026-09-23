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

## Status

Design checkpoint committed before implementation. See the sections below for what was implemented.
