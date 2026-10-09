# Combined network, queue and library QA candidate — 2026-10-03

Branch `codex/interaction-qa-oct3`, base main `7bf5bed09ab82cf973272e2c5edc95b798efb7e3`. Integration and author self-check: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). **Not independent review:** the coordinator reviews the source next. User acceptance is pending. No phone, ADB, build, push, PR or main change.

**Purpose:** one candidate so the Pixel keeps the network fixes it already runs (.474) while queue and library interactions are tested.

## Components (merged in order by exact commit, `--no-edit`; component branches untouched)

| Order | PR | Component head | Merge commit | Textual conflicts |
| --- | --- | --- | --- | --- |
| 1 | #257 network candidate | `555bd0c2117a7e963cc5522ec7e7272acf733802` | `080efb0a5f226b63402dbbafa7fea7e03e164031` | none (`docs/STATE.md`, `docs/ci.md` auto-merged) |
| 2 | #267 queue candidate | `a6974c32a7cc674a3c756c678a4e5ea51be65f86` | `c13487a41929b5ae294ce83009f05b2d6e04211a` | none (`MuonApp.kt`, `PlaybackService.kt` auto-merged) |
| 3 | #273 library interactions | `18542f7593076925ce9f9a0726faf6f295bd7a5f` | `76dd6c2e9f8dee80507717d19a40899024ba10b6` | none (`MuonApp.kt` auto-merged) |

**Excluded:** storage (#264, #281, #234), notifications, artwork, #212, #237, #240, #248 and alpha branches. #257 brings its own `docs/STATE.md` note about the private playback service (#205), unchanged.

## Shared files and interactions (read, not run)

- **`MuonApp.kt`** gets one hunk from each component, in separate regions:
  - #257: a local-network grant connects only with a non-blank address (#278).
  - #267: `currentPlayer` and a `queueSong` Undo by insertion occurrence, applied only while the same controller is current.
  - #273: list and header reset only on a real online/offline change, not on recreation.

  No hunk rewrites another's.
- **`PlaybackService.kt`:**
  - #257 changes only the session's artwork loader client.
  - #267 makes `mediaItem()` add a fresh occurrence UUID.

  `queueOccurrence` copies the existing extras Bundle (`QueueOccurrence.kt`). So the `SONG_EXTRA` record that `copyPlayed` reads and the `mediaId` survive; the extra record grows only by one UUID string.
- **Sheet actions to queue:** #273's `SongActionsSheet.choose` runs the action only after the sheet is actually hidden and the coroutine wasn't cancelled (`completeSheetAction`), and ignores repeat taps. #267's `queueSong` is that action, so a cancelled sheet adds nothing and leaves no Undo. One accepted Play next or Add to queue makes exactly one occurrence for Undo.
- **Library loads to scroll reset:** #257's `LibraryLoads` cancels and stamps loads (`ensureCurrent`), so a stale load can no longer set `offline`. #273's reset keys on `model.offline` changing, so it can only fire for the current load's change; the two are compatible.
- **Controller reconnect:** a reconnect gives a new `MediaController`. #267's Undo is skipped when `currentPlayer !== p`, so it can't edit the wrong queue. #257's network-callback and private-service changes don't touch that path.

No new test was added. Each interaction above composes existing, separately tested pieces: `SheetActionTest`, `QueueInsertionTest`, `QueueRemovalUndoTest`, `QueueIntegrationTest`, `LibraryLoadsTest`, `NetworkRequestTest` and the rest of each component's tests, all kept. A combined test would only mirror Compose wiring, which can't be exercised without UI test infrastructure.

## Checks run (lightweight only)

- The branch contains main `7bf5bed` and all three component heads.
- `python3 tools/check_branch_policy.py --base main --head 76dd6c2e… --source codex/interaction-qa-oct3` passed. This used local refs only, with no fetch, so recheck after refreshing `origin`.
- `git diff --check` against main is clean, and there are no conflict markers.
- **Not run:** Gradle, unit tests, lint or a build. CI is the first compile and run of the combined code.

## Validation limits

- **Source reading only:** the interaction conclusions come from reading the source. Recomposition, controller timing, sheet animation and scrolling are confirmed only by the user's phone QA.
- **No inferences from source:** nothing here infers an Android or OEM bug, or any phone result.
- **Lockscreen deferred:** Pixel LOCKSCREEN QA is **deferred at the user's request**. Lockscreen, notification and headset behaviour of #257's private service is not assessed in this pass.

## Manual checklist (pending user QA on this candidate's CI artifact)

1. **Queue Undo:**
   - Play next, then Undo: only that inserted entry goes, even with the same song elsewhere in the queue.
   - Add to queue, reorder the queue, then Undo: the same occurrence goes.
   - Undo after the snackbar has gone isn't offered.
2. **Reconnect:** with a song queued and its Undo snackbar showing, force a controller reconnect (leave and return to the app, or restart playback). Undo then doesn't change the new queue.
3. **Sheet cancellation:**
   - Open a song's actions and swipe the sheet away mid-animation. Nothing is queued or downloaded.
   - Tap Play next twice quickly: one entry is added.
4. **Network:** connect, refresh, then disconnect during loading. No stale library or error appears afterwards. The empty-address local-network grant starts discovery (#278).
5. **Library artist scroll:**
   - Scroll an artist's page, open one of its albums, come back: the place is kept.
   - Open another artist: it starts at the top.
   - Rotate or recreate: positions are kept. Going offline or online: every top-level list starts at its top.
6. **Tiny viewport:** in a very short window (split screen or landscape), the A–Z scroller still works and doesn't overlap or crash.

## Next verification

1. The coordinator reviews the source, then pushes and opens a PR.
2. CI on head `76dd6c2e` or later is the first compile.
3. Then the user's phone QA on that artifact. User acceptance remains pending.
