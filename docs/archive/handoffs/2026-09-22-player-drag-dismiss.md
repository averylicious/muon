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
- Commits: `e044a345fdef09f578bcfe1582d12246cae585a8` (the slice),
  `f7aed921abf4f00330d18ad3094429e323c3d7cd` (stop the commit settle fighting the closing
  animation), `97e86d08e0d81738baf8358f0d68a3c7f9c559fb` (Astra's first two findings) and the head
  below (the compile failure and Astra's follow-up findings). The exact diff range is from the
  base to the current head, which this note is committed in.

## Deliberately limited region

Only the bar holding the collapse button drags. Content scrolling, the artwork's horizontal swipe,
the seek and volume sliders and every button keep their own gestures. Widening the drag to the
whole player needs a nested-scroll design and was not attempted here.

## Astra's review findings, and the fixes

Astra reviewed `81a895e1c7dc9cf62089ff8bb6adf3afde7e6a74` and raised two P2 lifecycle findings,
both fixed in `97e86d08e0d81738baf8358f0d68a3c7f9c559fb`.

1. **A visibility reset did not invalidate the drag in progress.** The detector is keyed only by
   height and the offset is held outside the player's composition, so a drag survived the closing
   animation; closing and reopening under a finger that never lifted could let the old accumulator
   dismiss the new presentation, and outgoing content still accepted new drags. Every appearance
   and disappearance now counts as a new presentation, recorded when the visibility changes, and a
   drag commits only if it ends on the presentation it began on and took hold while the player was
   actually on screen (`playerDismissCommits`).
2. **Pointer-input restart could strand the offset.** The restore was a child of the pointer-input
   coroutine, so resizing the window mid-drag cancelled it while the hoisted offset stayed non-zero
   and `playerShown` never changed. The drag and its settle now run on the app's own scope, held by
   `PlayerDismiss`, and the detector puts the surface back when it is cancelled or restarted — but
   not when a drag has just committed, so the parting offset is still preserved and a closing
   player is not snatched back.

A follow-up review of `97e86d0` found the first attempt incomplete, and CI run 105 failed to
compile. All three points are fixed in the head below.

3. **`NowPlayingScreen.kt` did not compile.** `maxHeight` could not be resolved through the nested
   implicit receiver inside a density block within the column. The height is now taken in the
   `BoxWithConstraints` scope itself, before any other receiver is entered, and converted there.
4. **The generation guard protected only the commit.** An old drag's `onVerticalDrag` kept moving
   the surface after a visibility change. The detector is now torn down with the player: the drag
   state is handed to the player only while it is on screen (`dismiss.takeIf { playerShown }`) and
   the detector is keyed on it, so a stale gesture cannot move or restore a newer presentation.
   The commit guard is kept as well.
5. **Cleanup skipped a queued move when the surface was at rest.** A `moveTo` could still be
   waiting to run and would land after the detector exited. Cleanup is now unconditional: the
   detector gives up its own work whatever the surface shows, a move cancels the previous one
   before scheduling itself, and a new drag cancels a settle left by the detector before it. Only
   a committed dismissal still keeps its parting offset.

Astra's findings are fixed but **not yet re-reviewed**, and full-device behaviour was not
established by those reviews.

## Validation performed

- Seven unit tests: the drag policy — following the finger down; upward and reversed drags resting
  where they began; never falling past the screen, including an unmeasured one; the close
  threshold; and that the finger's travel rather than the surface's decides — plus the lifecycle
  policy: a drag does not put away a presentation it did not begin on, and one on the right player
  still has to be long enough.
- These are pure policy tests. They do **not** verify Compose pointer input or lifecycle: that the
  detector is really torn down with the player, that its cleanup runs on resize, and that the app
  scope outlives it are only established on a device. A player on its way off now takes no drag
  because it has no detector at all, which is a structural property rather than a testable one.
- Re-checked the staleness and transform reasoning against the #82 fix: the close action is read at
  drag end and guarded by live `playerShown`, and the drag draws in its own layer so it does not
  compete with the Back preview's scale, drift and corners.

## Pending

- CI: triggered by the push, **not inspected**. Compilation, unit tests, lint and both APK builds
  are unverified here; nothing may be called a pass. Astra owns verification.
- Astra source review: two rounds, on `81a895e` and `97e86d0`. The fixes for the follow-up round
  are **not** reviewed. CI run 105 failed to compile `97e86d0`; the compile fault is fixed here but
  no run has been inspected since.
- Phone QA: pending the user; fourteen steps are in PR #85, covering slop, cancellation, repeated
  drags, scrolling, the artwork swipe, the sliders, TalkBack, Lyrics, quick close and reopen,
  queue-emptied and disconnect cleanup, and the combination with the Back gesture.

Not covered by any test, and needing the device: touch slop, cancellation, rapid re-interaction,
the two layers together, the bar's drag against the surrounding scroll in short or narrow windows,
and both of Astra's paths — window resizing mid-drag, and closing and reopening the player under a
finger that never lifted. The 96.dp threshold and the 1:1 follow are untuned on real hardware.

## Status

Clean and idle. Everything is committed and pushed; nothing is uncommitted or half-applied.

## Remaining #43 work

The player still **opens** with the existing animation rather than following the finger through a
full open transition — #84 lifts the mini player and hands over to that animation. Dismissing from
anywhere other than the top bar is also still open. Both need the nested-scroll and full-transition
design these slices avoid, and should be planned deliberately rather than grown from here. After
that: #45 album and artist pages, #46 song actions, #47 Queue (needs Q1/Q2/Q4), #48 search bar,
#51 Connect, #44 Albums/Artists (needs #23 and the grouping contract).
