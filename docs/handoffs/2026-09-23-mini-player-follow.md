# Handoff: the player follows the finger up from the mini player (issue #43, slice B)

2026-09-23. Slice B of the plan in https://github.com/averylicious/muon/issues/40#issuecomment-5779229649,
built on slice A (#91). Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, as
frontend implementer. Astra (GPT-6, Codex desktop) coordinates, owns CI and review.

## Design

**What changes for the user.** Dragging the mini player up no longer lifts the mini player a little
against resistance; the whole player rises with the finger through #91's sheet. Letting go past the
same **48 dp of raw finger travel** opens it; anything less, a cancel, or Back puts it away. The
threshold is unchanged, because nothing requires changing it.

**Same single owner.** The mini player's drag moves `PlayerSheet.position` — the only vertical
position the player has. From wherever the sheet is at takeover (normally `1`, closed), each move
sets `playerSheetDragged(baseline, travel, height)`, exactly as #91's dismiss drag does. No additive
translation, and the resisted mini-player lift is removed rather than layered on top.

**No mount or measurement deadlock.** A preview begins while the player is logically closed and its
host not composed. The sheet's height is therefore measured from the app's root `Box` — always
composed and the same size as the sheet — not from the host. The first upward move can convert travel
to a position, which puts the sheet on screen, which mounts the host.

**Preview is its own state.** `PlayerSheet.previewing` is snapshot state that flips twice per gesture;
composition never reads the per-frame position. While previewing, the player is still logically
closed, so its partly visible content stays out of the accessibility tree and under the touch cover
from #91, and Back goes to a dedicated handler (below), not to the library underneath.

**One driver re-presents the logical state whenever a preview ends.**
`LaunchedEffect(playerShown, sheet.previewing) { if (!sheet.previewing) sheet.present(playerShown) }`.
A committed preview opens the player and ends the preview in the same event, so the driver animates
it open from where the finger left it. Every other ending — too short, cancelled, Back, eligibility
lost, or an open that was refused — ends the preview with the player still closed, so the same
driver animates it closed. None of them can leave the sheet stranded part-way, because the driver,
not the gesture, has the last word.

**Late callbacks are refused by token.** A preview records `sheet.generation` when it begins. Moves
and the commit act only while the sheet is still previewing **and** the generation is unchanged. Back
ends the preview at once, and `present` then bumps the generation, so a finger still down after Back
can neither move nor open the sheet.

**The originating gesture is not torn down.** The mini player stays composed and its detector keeps
its keys during a preview (the player is still logically closed), so the pointer stream that started
the drag carries on. A detector that is torn down anyway — the controller or queue going away, or
the mini player leaving — ends its own preview if it still owns it.

**Background taps are blocked while previewing** by showing #88's scrim for a preview too. It is
added above the library after the finger is already down; Compose keeps an existing pointer's hit
path for the rest of its stream, so the scrim stops new touches without cancelling the drag.

**Back during a preview** is a `BackHandler` enabled only while previewing and registered after the
app's own, so the dispatcher gives it the press: it cancels the preview and navigates nothing.

**Motion.** Opening and closing keep #91's existing 250 ms curve. A spring would also respect the
system animation scale and finish at once with animations off, but changing the curve for every
opening and closing is left to a tuning pass rather than bundled here.

## Status

Design checkpoint committed before implementation; the sections below record what was implemented.
