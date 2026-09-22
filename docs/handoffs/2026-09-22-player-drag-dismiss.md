# Handoff: drag the player's top bar down to dismiss it

2026-09-22. Frontend slice of #43, handed off open. Follows
`docs/handoffs/2026-09-22-player-predictive-back.md` (PR #82) and
`docs/handoffs/2026-09-22-mini-player-drag.md` (PR #84).

## Ownership

Claude Opus 5 (`claude-opus-5`), Claude Code CLI, medium effort, as frontend implementer. Astra
(GPT-6, Codex desktop) coordinates, owns CI and review. No other agent edited this branch.

- `app/src/main/java/dev/avery/muon/PlayerDismissDrag.kt` (new) — the drag policy, the state it
  keeps, the layer it draws in and the gesture itself.
- `app/src/test/java/dev/avery/muon/PlayerDismissDragTest.kt` (new) — that policy.
- `app/src/main/java/dev/avery/muon/NowPlayingScreen.kt` — the top bar carries the drag.
- `app/src/main/java/dev/avery/muon/MuonApp.kt` — owns the drag state, resets it as the player
  appears, guards the close action, and applies the layer to the overlay surface.

No backend file is touched.

## Branch, base and commits

- PR: [#85](https://github.com/averylicious/muon/pull/85), targeting `main`.
- Branch `codex/player-drag-dismiss`, worktree `/home/avery/.codex/worktrees/muon-player-dismiss/muon`.
- Base: PR #84 head `b3ca7768af35d36fb46ab6d413d44164c4be1a4a` — Astra reviewed, Actions run
  35712616201 (#103) passed, artifact verified. **#84 is a prerequisite and should merge first;
  #84 in turn depends on #82.**
- Commits: `e044a345fdef09f578bcfe1582d12246cae585a8` (the slice) and
  `f7aed921abf4f00330d18ad3094429e323c3d7cd` (stop the commit settle fighting the closing
  animation). Exact diff range
  `b3ca7768af35d36fb46ab6d413d44164c4be1a4a..f7aed921abf4f00330d18ad3094429e323c3d7cd`.

## Deliberately limited region

Only the bar holding the collapse button drags. Content scrolling, the artwork's horizontal swipe,
the seek and volume sliders and every button keep their own gestures. Widening the drag to the
whole player needs a nested-scroll design and was not attempted here.

## Validation performed

- Five unit tests on the drag policy: following the finger down; upward and reversed drags resting
  where they began; never falling past the screen, including an unmeasured one; the close
  threshold; and that the finger's travel rather than the surface's decides, which is the pair most
  easily merged by mistake later.
- Re-checked the staleness and transform reasoning against the #82 fix: the close action is read at
  drag end and guarded by live `playerShown`, and the drag draws in its own layer so it does not
  compete with the Back preview's scale, drift and corners.

## Pending

- CI: triggered by the push, **not inspected**. Compilation, unit tests, lint and both APK builds
  are unverified here; nothing may be called a pass. Astra owns verification.
- Astra source review: not started for this slice.
- Phone QA: pending the user; fourteen steps are in PR #85, covering slop, cancellation, repeated
  drags, scrolling, the artwork swipe, the sliders, TalkBack, Lyrics, quick close and reopen,
  queue-emptied and disconnect cleanup, and the combination with the Back gesture.

Not covered by any test, and needing the device: touch slop, cancellation, rapid re-interaction,
the two layers together, and the bar's drag against the surrounding scroll in short or narrow
windows. The 96.dp threshold and the 1:1 follow are untuned on real hardware.

## Status

Clean and idle. Everything is committed and pushed; nothing is uncommitted or half-applied.

## Remaining #43 work

The player still **opens** with the existing animation rather than following the finger through a
full open transition — #84 lifts the mini player and hands over to that animation. Dismissing from
anywhere other than the top bar is also still open. Both need the nested-scroll and full-transition
design these slices avoid, and should be planned deliberately rather than grown from here. After
that: #45 album and artist pages, #46 song actions, #47 Queue (needs Q1/Q2/Q4), #48 search bar,
#51 Connect, #44 Albums/Artists (needs #23 and the grouping contract).
