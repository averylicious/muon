# Handoff: give back the top inset while the player is dragged down

2026-09-23. Spacing fix on #43 from Canary .113 QA: during a downward drag there was a large blank
band between the panel's rounded top and the collapse/handle row. The user likes the dimming.

## Ownership

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort (dispatched with
`--model claude-opus-5-5 --effort medium`; the effort is not observable from inside the session), as
frontend implementer. Astra (GPT-6, Codex desktop) coordinates, owns CI and review.

- `app/src/main/java/dev/avery/muon/PlayerDismissDrag.kt` — `playerInsetReclaimed` and
  `Modifier.reclaimTopInset`.
- `app/src/main/java/dev/avery/muon/MuonApp.kt` — the player's host applies it; Lyrics does not.
- `app/src/test/java/dev/avery/muon/PlayerDismissDragTest.kt` — three inset policy tests.

## Branch and base

- Branch `codex/player-drag-top-inset`, worktree `/home/avery/.codex/worktrees/muon-drag-inset/muon`.
- Base: PR #89 head `52eee8e984a9169d1ecb91f03bd830d29614377f` (run 113 passed). **#89 is a
  prerequisite**, stacked on #88 → #87 → #86 → #85. The commit carrying this note is the whole slice.

## Cause

The overlay host pads its content with `safeDrawingPadding()`. When the sheet is dragged, the whole
sheet translates down, carrying the top system inset with it, so the status bar's height reappeared
as blank space above the 12 dp design padding — which the screenshot's proportions match.

## Fix

As the sheet's top edge drops below the screen's physical top, that much of the top inset no longer
covers anything. The content now rises by `min(drop, topInset)` — nothing at rest, never more than
the inset. Horizontal cutout protection, bottom navigation protection and the ordinary 12 dp design
padding are untouched, and Lyrics passes no drag state, so its inset is unchanged.

**The constraint trap, explicitly avoided.** Shrinking the padding frame by frame would change the
content's constraints. In this source that would change `BoxWithConstraints`' `maxHeight`, so:
`dragHeight` changes and restarts the dismiss detector (`pointerInput(state, height)`) mid-drag; and
`scrollable` (`maxHeight < 600.dp * fontScale`) could flip between the fixed and scrolling layouts,
jumping the artwork. The fix therefore moves **placement only**, with the lambda form of
`Modifier.offset`, reading the drag in the placement phase. The content keeps the size it was measured
at, the detector is never restarted, and nothing is read in composition. No negative padding, no
hard-coded status bar height, no pixels taken from the screenshot marker.

Visible consequence for QA: over the first `topInset` pixels of a drag, the sheet's edge comes down
to meet the content while the content itself stays put; after that, both move together.

## Verified

- Pinned `foundation-layout` 1.9.3 sources: `WindowInsets.getTop(density)`, the lambda
  `Modifier.offset(Density.() -> IntOffset)`, and `WindowInsets.safeDrawing`.
- Three policy tests: an open player keeps its whole top inset; the inset is given back as the
  sheet drops, but never beyond it; with no top inset (landscape, a window away from the status bar),
  or an upward drag, the content does not rise. They do not verify placement on a device.
- **No local build** — no Gradle cache and no installed `platforms;android-36`.

## Pending

CI triggered by the push, **not inspected** (Astra owns it). Astra review pending. Phone QA pending.

Preserved: predictive Back (its own layer; no drag offset means no shift), drag cancellation and
reopen (the shift follows #85's drag offset, which settles to zero and resets on reopen), the
committed-exit offset, the scrim and the rounded or outlined edge.

## Status

Clean and idle once pushed. Stop after this slice.
