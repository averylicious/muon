# Handoff: drag the mini player up to open Now Playing

2026-09-22. Frontend slice of #43, handed off open. Companion to the coordinator handoff
(`docs/coordinator-handoff.md`, PR #66) and to the predictive Back note
(`docs/handoffs/2026-09-22-player-predictive-back.md`, PR #82).

## Ownership

Claude Opus 5 (`claude-opus-5`), Claude Code CLI, high effort, as frontend implementer. Astra
(GPT-6, Codex desktop) coordinates, owns CI and review. No other agent edited this branch.

- `app/src/main/java/dev/avery/muon/MiniPlayerDrag.kt` (new) — the drag policy: direction,
  resistance, lift limit, open threshold.
- `app/src/test/java/dev/avery/muon/MiniPlayerDragTest.kt` (new) — that policy.
- `app/src/main/java/dev/avery/muon/MiniPlayer.kt` — the gesture and the lift it draws.

`MuonApp.kt`, `NowPlayingScreen.kt` and `PlayerBack.kt` are untouched, so nothing here overlaps
PR #82's files, and no backend file is touched.

## Branch, base and commit

- PR: [#84](https://github.com/averylicious/muon/pull/84), targeting `main`.
- Branch `codex/mini-player-drag-open`, worktree
  `/home/avery/.codex/worktrees/muon-mini-player-drag/muon`.
- Base: PR #82 head `97766a1105e9463c1533fbcff3a748d1ef4dbcf8` — reviewed by Astra, Actions run
  35711052198 (#101) passed on it. **#82 is a prerequisite and should merge first.**
- Slice commit: `aebc2ebe4a1969c037c07b61687fbb1e7c427f9d`. Exact diff range
  `97766a1105e9463c1533fbcff3a748d1ef4dbcf8..aebc2ebe4a1969c037c07b61687fbb1e7c427f9d`.

## Tested

- Five unit tests on the drag policy: a downward pull neither moves the surface nor opens the
  player; the lift follows the finger but gives less than asked; it stops at its limit rather than
  opening a hole in the chrome; opening takes a pull past the threshold; and the decision is taken
  from the finger's travel, not the surface's, which is the pair most likely to be wrongly merged
  later.
- Confirmed `Dp.toPx()` inside a `pointerInput` block against shipping code in this repo
  (`NowPlayingScreen.kt:242`).

## Pending

- CI: triggered by the push, **not inspected**. Compilation, unit tests, lint and both APK builds
  are unverified here; nothing may be called a pass. Astra owns verification.
- Astra source review: not started for this slice.
- Phone QA: pending the user; twelve steps are in PR #84, covering slop, cancellation, repeated
  quick pulls, the inner buttons, TalkBack, queue-emptied cleanup and Back.

Not covered by any test, and needing the device: touch slop, gesture cancellation, settling,
rapid re-interaction and the interaction with the play and next buttons are Compose pointer-input
behaviour. The lift, resistance and threshold distances are unverified on a real screen.

## Status

Clean and idle. Everything is committed and pushed; there are no uncommitted edits, and no work is
half-applied.

## Next steps

Scope was kept to opening. Still to do on #43 and around it: swipe or drag **down** to dismiss the
player, which this slice deliberately leaves alone, and then #45 album and artist pages, #46 song
actions, #47 Queue (needs the queue-model decisions Q1/Q2/Q4), #48 search bar, #51 Connect, and
#44 Albums/Artists (needs #23 and the grouping contract). One slice at a time, on the user's
authorisation.
