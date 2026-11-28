# Handoff: separate the dragged player from the library

2026-09-23. Frontend refinement of #43, answering the .110 QA feedback in
https://github.com/averylicious/muon/issues/43#issuecomment-5779792745: while dragging the player
down, it and the library shared one light surface, so the moving player did not read as a panel.

## Ownership

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort as requested (the effort
setting is not independently observable from here), as frontend implementer. Astra (GPT-6, Codex
desktop) coordinates, owns CI and review. No other agent edited this branch.

- `app/src/main/java/dev/avery/muon/MuonApp.kt` — a stationary scrim beneath the player.
- `app/src/main/java/dev/avery/muon/PlayerDismissDrag.kt` — rounded top corners and elevation on
  the existing drag layer.

## Branch and base

- Branch `codex/player-sheet-separation`, worktree
  `/home/avery/.codex/worktrees/muon-sheet-separation/muon`.
- Base: PR #87 head `f95fe799bf046ee7c64438d8e2a5a100eaeb1ab7` (run 111 passed; phone QA pending).
  **#87 is a prerequisite**, stacked on #86 and #85. The commit carrying this note is the whole
  slice; the exact range is from that base to it.

## What changed

- **Scrim:** Material's modal-sheet scrim, `colorScheme.scrim` at 0.32 (`ScrimTokens.ContainerOpacity`,
  verified in material3 1.4.0 sources), in its own `AnimatedVisibility` bound only to `playerShown`
  and drawn beneath the player. It does not translate with the sheet, fades with the player's
  visibility, and is removed entirely once the player has closed — nothing invisible is left over
  the library. It blocks touches beneath it with `pointerInput(Unit) {}`, the idiom Material's own
  `Surface` uses, and has no semantics, so no duplicate accessibility action is added.
- **Edge:** the #85 drag layer now also rounds the top corners (28.dp, matching the opening mockup)
  and adds Material's modal-sheet elevation (Level1, 1.dp). Both grow over the first pixels of a
  drag and are nothing at rest, so the open player shows no corners or shadow under the status bar.
  The shadow is drawn by the same layer, outside its clip.

Deliberately not done: no padding to mask the apparent whitespace (it was same-coloured surfaces),
no artwork tinting, no single-axis host refactor, no backend change.

## Boundaries preserved

Gesture thresholds and cancellation, #84/#87's mini-player lift, #82's predictive Back layer (its
own scale, drift and corners are untouched — the two layers still write different properties),
Lyrics layering (Lyrics is composed after and draws above both the scrim and the player), the
collapse button, TalkBack, and queue/disconnect cleanup. Cancelled, committed, close/reopen and
queue/disconnect paths need no new handling: the scrim follows `playerShown`, and the edge follows
#85's drag offset, which already carries those lifecycle rules.

## Validated vs pending

- Checked: the scrim opacity and elevation tokens and the `Surface` touch-blocking idiom against
  pinned material3 1.4.0 sources; the diff by reading it. **No local build** — no Gradle cache and
  no installed `platforms;android-36`.
- No new unit tests: this is decorative and bound to state that is already tested. A test here
  would only mirror the layout.
- CI: triggered by the push, **not inspected**. Astra owns final-head CI and review.
- Astra review: pending. Phone QA: pending.

Known limitations:

- **Pure black (AMOLED) theme:** the player and library are both black, a black scrim changes
  nothing, and shadows barely show, so separation there rests on the rounded corners' content edge
  alone. A tonal or outlined edge for that theme would be a follow-up decision.
- The scrim also appears behind the tap-to-open and Back animations and the predictive Back
  preview, not only the drag. That is intended, but it is a visible change there too.
- For the ~250 ms the scrim takes to fade out after closing, library touches are blocked, as they
  already are wherever the closing player still covers.
- The 28.dp corner and 1.dp elevation are untuned on a real device.

## Status

Clean and idle once pushed. Stop after this slice.

## Remaining #43 work

Unchanged: the full finger-following opening still needs the single-axis host proposed on
2026-09-22 (two slices: host the player on one presentation axis, then drive it from the mini
player). This slice deliberately does not start that refactor.
