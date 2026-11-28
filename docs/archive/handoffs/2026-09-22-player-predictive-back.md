# Handoff: predictive Back preview for Now Playing

2026-09-22. Frontend slice of #43, handed off open. Companion to the coordinator handoff
(`docs/coordinator-handoff.md`, PR #66) — this file covers only this slice.

## Ownership

Claude Opus 5 (`claude-opus-5`), Claude Code CLI, high effort, as frontend implementer. Astra
(GPT-6, Codex desktop) coordinates, owns the backend and reviews. No other agent edited this
branch.

Files changed, all frontend:

- `app/src/main/java/dev/avery/muon/PlayerBack.kt` (new) — gesture state, the handler and the
  preview modifier.
- `app/src/test/java/dev/avery/muon/PlayerBackTest.kt` (new) — the preview's arithmetic.
- `app/src/main/java/dev/avery/muon/MuonApp.kt` — `backTarget`/`playerGestureCommits`, the single
  Back decision both handlers consult; the player overlay remembers the preview inside its
  transition.
- `app/src/test/java/dev/avery/muon/BackTargetTest.kt` (new) — Back priority and when a held
  gesture may act.
- `app/src/main/java/dev/avery/muon/Motion.kt` — Material's predictive-back easing, added beside
  the existing motion values.

`NowPlayingScreen.kt` is deliberately untouched, so the artwork swipe, scrolling and the visible
collapse button are unchanged. No backend file is touched, so this does not overlap Astra's
metadata work.

## PR, base and head

- PR: [#82](https://github.com/averylicious/muon/pull/82), targeting `main`.
- Branch `codex/player-predictive-back`, worktree `/home/avery/.codex/worktrees/muon-predictive-back/muon`.
- Base: PR #81 head `2983ac09303a3ff5c53bc543cd84ee45f1303ec9`. **#81 should merge first.**
- Head at handoff: see the PR body, which carries the exact diff range.

## Astra's review finding, and the fix

Astra reviewed the source and found that the first implementation was wrong about in-flight
gestures, and that PR #82's description overclaimed. `PredictiveBackHandler` passes the lambda it
holds **at the moment a gesture starts** to a coroutine that keeps it (`OnBackInstance` takes
`onBack` as a constructor argument), and `setIsEnabled` does not cancel a gesture that is already
active. A held gesture therefore outlived the state it closed over: opening Lyrics over the player
mid-gesture and then completing it closed the player rather than the Lyrics on top of it.

Fixed by reading the action when the gesture is let go (`rememberUpdatedState`) instead of
capturing it, by making where Back goes a single decision (`backTarget`) that both handlers
consult, and by refusing to navigate when the gesture's player has gone underneath it
(`playerGestureCommits`). A restore animation left over from a previous gesture is now also
cancelled when a new gesture starts.

Residual, stated plainly: the action is the one from the latest composition, so a state change and
a gesture completing inside the same frame can still use the previous frame's answer. That is the
ordinary Compose contract and is bounded to a frame, not to the length of the gesture.

## Checks actually run

- Read the pinned sources to confirm behavior rather than assuming it: `PredictiveBackHandler` and
  `BackHandler` in activity-compose 1.11.0, `BackEventCompat` and `OnBackPressedDispatcher` in
  activity 1.11.0, and `PredictiveBack`/`ModalBottomSheet`/`SearchBar` in material3 1.4.0.
- Confirmed `androidx.activity:activity` is a `compile`-scope dependency of `activity-compose`, so
  `BackEventCompat` is on the compile classpath.
- **No local compile, test or lint run.** This machine has no Gradle cache and no installed
  `platforms;android-36`; per AGENTS.md the Android build belongs to Actions.

## Pending

- CI: Actions run 35686386948 (number 100) passed on head `575ea9caecefed3ed8ef4a7d5b261a6d630efe58`,
  as reported by Astra, who owns CI. The later fix commit is **not** covered by that run; its own
  run was triggered by the push and is **not inspected** here.
- Astra source review: one round done, and it found the in-flight gesture issue above. The fix
  itself has not been reviewed.
- Manual QA: pending the user. Steps are in PR #82 — the ten checks cover held, cancelled and
  completed gestures, Lyrics priority, the collapse button, closing from a non-Library tab,
  reopening inside the closing animation, artwork swipe, queue/disconnect cleanup, and 3-button
  navigation. Do not install an APK before its run passes and the artifact SHA-256 is verified.

## Unfinished work in this slice

None. The branch is committed, pushed and idle; there are no uncommitted edits and no half-applied
changes. Known open questions are recorded as limitations in PR #82, chiefly that the shrink, drift
and corner distances are unverified on a device and may want tuning after QA.

## Next action

One slice at a time, on the user's authorisation. Remaining 2.0 frontend work is unchanged: the
rest of batch 6 (drag to open and dismiss the overlay), #45 album and artist pages, #46 song
actions, #47 Queue (needs the queue-model decisions Q1/Q2/Q4), #48 search bar, #51 Connect, and
#44 Albums/Artists (needs #23 and the grouping contract).

Usage telemetry: not available to this agent. Astra relays the rate-limit events.
