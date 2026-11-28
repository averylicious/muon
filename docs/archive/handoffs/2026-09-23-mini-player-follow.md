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

## Implemented

The design above was implemented as written, in one code commit after the design commit.

- `PlayerDismissDrag.kt` — `PlayerSheet.previewing`, `beginPreview` (holds the sheet and returns the
  token) and `endPreview`; `playerPreviewOwned` and `playerPreviewOpens`.
- `MiniPlayer.kt` — the resisted lift and its animation are gone; the drag begins a preview, moves
  the sheet from its takeover baseline, refuses moves once no longer owned, and on release opens the
  player and ends the preview together, or just ends it. A detector torn down mid-preview ends the
  preview it owns.
- `MiniPlayerDrag.kt` — only the 48 dp threshold and `miniDragOpens` remain.
- `MuonApp.kt` — the sheet's height is measured on the root `Box`; the driver is keyed on
  `(playerShown, sheet.previewing)`; the scrim also shows during a preview; a second `BackHandler`,
  enabled only while previewing, ends the preview; the mini player receives the sheet.

## Branch and base

- Branch `codex/mini-player-follow`, worktree `/home/avery/.codex/worktrees/muon-mini-follow/muon`.
- Base: PR #91 head `f2d733a7391ad26ffaad3267b87ef84a977aad79` (Astra re-review, no remaining
  blocker; run 117 passed). **#91 is a prerequisite**, stacked on #90 → #89 → #88 → #87 → #86 → #85.
- Two commits: the design checkpoint, then the implementation with this completed note.

## Validated vs pending

- Tests: the mini-player tests are restated for the sheet (it follows the finger with no resistance;
  pulling down does nothing; opening needs the threshold), and new policy tests cover a preview opening
  only while still owned — not after Back, not across a new presentation, not after eligibility is lost
  — late moves refused, and where the sheet already was not counting towards opening. These do not
  verify Compose input, the pointer stream surviving the scrim, or rendering.
- Read the diff; the APIs are those already verified in earlier slices. **No local build** — no Gradle
  cache and no installed `platforms;android-36`.
- CI: triggered by the push, **not inspected**; Astra owns final-head CI and review.
- Astra review: pending. Phone QA: pending.

Needs the device most: that the drag carries on after the scrim appears over the mini player; Back
mid-preview with the finger still down (then keep moving and let go — nothing should happen); the
queue emptying or disconnecting mid-preview; rotating mid-preview; grabbing the sheet again while it
is settling away; tapping controls and TalkBack on the partly risen player; and opening with system
animations turned off, which should jump straight open or closed.

Known limits: the player's content is first composed on the first frame of a preview, which may cost
that one frame; and the scrim fades in over 250 ms rather than tracking the finger.

## Astra review of `afd9ede`, and the fix

Astra found two blocking cases, both fixed in the head after `afd9ede`.

1. **A preview could end unseen and strand the sheet.** The driver was keyed only on the booleans
   `(playerShown, previewing)`. A short or cancelled preview that began and ended before a frame
   observed it left those keys exactly as they were, so `present(false)` never ran and a queued move
   could leave the sheet part-way. And a preview's token was the presentation's generation, so a
   preview cancelled and restarted before anything was presented shared its token with the one
   before, letting an old detector own the new preview.
   Fixed with `SheetTurn`, one immutable value in snapshot state: every preview gets a generation of
   its own when it begins, as every presentation does, and every preview that ends adds to a
   completion count. The driver now keys on `(playerShown, previewing, completions)`, so each ending
   is observed even if composition never saw the preview running. Accepted and refused opens keep
   their meaning: an accepted open writes `playerOpen` in the same event that ends the preview, so the
   driver presents it open; a refused one leaves it closed, so the driver puts the sheet away.
2. **The library stayed in TalkBack's tree under a rising preview.** Background semantics were
   cleared only for `overlayOpen`. They are now cleared for `overlayOpen || previewing`, and return
   as soon as a preview is cancelled. Only the ancestor's semantics modifier changes, so the mini
   player's pointer detector carrying the preview is not recreated. A sheet settling away after a
   cancel is already out of the tree and under #91's cover, and a fully closed sheet is unmounted, so
   nothing closed is left inaccessible and nothing open is left hidden.

Tests: `SheetTurnTest` exercises real sequences on the pure value — a preview begun and ended unseen
still completes; a restarted preview never shares its token; Back ends a preview for a finger still
down; a presentation retires earlier tokens, including a dismiss drag's; ending twice counts once;
and presenting does not look like a completed preview, so settling cannot retrigger itself. The
ordering sits in a pure value rather than tests constructing the sheet, because the unit-test
classpath has only JUnit and the sheet holds Android-backed Compose state.

## Status

Clean and idle once pushed. Stop after this slice; no further feature work this cycle.
