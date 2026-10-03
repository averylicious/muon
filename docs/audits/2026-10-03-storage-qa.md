# Combined storage QA candidate — 2026-10-03

Branch `codex/storage-qa-oct3`, base main `28a1329be3fe2d6c8833cd194f241a22768c15ca`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). Author analysis only, **not independent review**. No phone, push, PR or build.

## Status: integration NOT performed (blocked)

In this session, `git merge --no-edit <sha>` and the read-only `git merge-tree` both required approval, which this non-interactive session couldn't give. Building the merges with lower-level git commands would get around that check, so it wasn't done. **This branch contains this report only, no integrated code.** There's nothing to build or QA from it yet.

All three component commits exist locally, and their branches were left untouched:

| PR | Head | Merge base with main | Intent |
| --- | --- | --- | --- |
| #264 card availability (S1) | `215f2ea762fb10d1083607ddd38d3ec0c96c68ec` | `065d1c0` | New decisions skip an unavailable card (`CardAvailability.kt`: `canMove`, `deliverMovedCopy`, `removalPlan`, `availableShelves`, `leftoverCopies`) |
| #281 byte verification | `2b7ad536acd2b4f500a9a38418e1d3a5cface2b3` | `b1df6cc` | `copy` publishes only if `sameBytes` (two 64 KiB cache-only reads) matches. Nothing is deleted |
| #234 move publication ownership | `370f74d2d43684cec7a313db3fb7b1feb79fb901` | `fbfeaa2` | `DownloadMoveOwnership`: remove/removeAll stop older in-flight moves from posting Add |

## Expected merge behaviour (from diffs, not an executed merge)

Main has not changed `OfflineStore.kt`, `SettingsScreen.kt`, `OfflineRoute.kt` or `OfflineDataSource.kt` since #264's or #281's base. Since #234's base, main changed only `route`/`playedCopy` in `OfflineStore.kt`, which #234 doesn't touch. `DownloadMoveCharacterizationTest.kt` gained #280's cases, away from #234's removal-test hunks. So each PR alone should apply to main, and **the conflicts are between the components**.

**Recommended order:** #264, then #281, then #234.

1. **#264:** expected clean.
2. **#281 onto #264:** a likely adjacent conflict in `OfflineStore.kt` where #281 adds `sameBytes`/`readFully` straight after `copy` and #264 rewrites `removeAll` below it. Keep #281's helpers, then #264's `removeAll`.
3. **#234 onto both:** conflicts in `remove`, `move`, `removeAll` and the `mover` area of `OfflineStore.kt`, plus the class comment of `DownloadMoveCharacterizationTest`. Combine them as follows; don't take either file wholesale.
   - **`remove`:** call `moveOwnership.remove(ids)` first, for **all** ids, including those withheld on an unavailable card, so a move in flight can't bring any back. Then #264's `removalPlan` commands and its notice.
   - **`removeAll`:** call `moveOwnership.removeAll()` first, then #264's available-shelves-only commands and notice.
   - **`move`:** keep #264's early `canMove` refusal before `moveOwnership.begin()`, so a refused move registers nothing. In the worker:
     - wrap #264's loop in #234's `try`/`finally`, which posts `moveOwnership.finish(batch)` and clears progress;
     - keep #264's per-song `canMove` break;
     - copy only if `moveOwnership.permits(batch, id)`, and #281's check stays inside `copy`;
     - on the main thread, `deliverMovedCopy(from, to) { moveOwnership.publish(batch, id) { sendAddDownload(...) } }`, so availability and ownership must both hold at hand-over.
   - **`mover` area:** keep both #264's nullable `downloadsOn` and #234's `private val moveOwnership`.
   - **Test class comment:** removal cases reject stale publication (#234); missing-span cases characterize an unfixed risk; target-prefix cases assert the byte check (#281). Keep #234's renamed removal tests and #281's prefix tests.
   - **Remove counts still hold:** #234's removal tests expect two remove commands, and #264's `removalPlan` sends every id to each available shelf. The fixture's shelves are available by default (`Shelf(..., present = { true })`).

## Limitations (unchanged by integration)

This is containment only, not a #179 fix.
- **Card removal:** a card disappearing during a cache read, copy or download can still lose the cache's mapping.
- **Card identity:** a different card at the same path isn't told apart (S2).
- **Card service:** the service fallback to the phone (S4) remains.
- **A copy already running** isn't cancelled by remove/removeAll. Its target spans stay un-indexed, which is not cleanup, by design (#230).
- **Snapshots, not locks:** the byte check reads both copies at that moment, and the availability checks are snapshots too.
- **Not included:** #212, #237, #240, #248, and the network, queue, library or alpha changes.

## Manual QA checklist (pending; run only on the actual integrated head)

Use the POCO with an SD card holding **disposable copied audio only**, with the originals kept elsewhere.
1. **Moves:** move phone→card and card→phone. Songs play from their new place, the original goes only after the copy completes, and nothing is listed twice.
2. **Remove during a move:** remove one song while a move runs. It doesn't reappear, and the other songs finish. Repeat with Remove all; the list stays empty, and a later deliberate move or download works.
3. **Interrupted move:** start a move, interrupt it, then move again. The resumed move completes.
4. **Card unavailable:** with the card ejected while idle:
   - online and offline play;
   - the offline list;
   - Download, with the card chosen and with it not chosen;
   - Remove and Remove all;
   - Move in both directions, which should be refused with a notice.

   Then reinsert the same card and check its songs.
5. **Settings:** launch with the card absent and Store on SD card on. Check the Settings row, the refusals, and turning the setting off.
6. **Not a pass criterion:** abrupt removal during I/O (S6).

## Next validation

1. The coordinator (or a session with merge approval) runs the three `git merge --no-edit` steps in order and applies the resolutions above.
2. Review the combined diff of `OfflineStore.kt`.
3. Run `tools/check_branch_policy.py`, then CI, which is the first compile.
4. Update this report with the actual merge commits and the run before any device QA.
