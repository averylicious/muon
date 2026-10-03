# Combined storage QA candidate — 2026-10-03

Branch `codex/storage-qa-oct3`, base main `28a1329be3fe2d6c8833cd194f241a22768c15ca`. Author and integrator: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). Author integration and self-check, **not independent review**. No phone, push, PR, build or main change.

## Status: integrated, pending CI

The first attempt was blocked: `git merge` needed approval (checkpoint commit `c68e741`). The runner then allowed `git merge` for this branch only, and the three components were merged in order by exact commit with `--no-edit`. Their own branches are untouched.

| Order | PR | Component head | Merge commit | Conflicts |
| --- | --- | --- | --- | --- |
| 1 | #264 card availability (S1) | `215f2ea762fb10d1083607ddd38d3ec0c96c68ec` | `526e49935a67f2f36316b909a8fd5549741569d1` | none |
| 2 | #281 byte verification | `2b7ad536acd2b4f500a9a38418e1d3a5cface2b3` | `75704608c015f4face4075ce0c0eb6b46c76e2d5` | `OfflineStore.kt`: #281's `sameBytes`/`readFully` beside #264's `removeAll` |
| 3 | #234 move publication ownership | `370f74d2d43684cec7a313db3fb7b1feb79fb901` | `1f28ecc8b89dc38e33db09ec82d9c1f4fdfd5724` | `OfflineStore.kt` `remove`, `move`, `removeAll`; `DownloadMoveCharacterizationTest.kt` class comment |

None of #212, #237, #240, #248, or the network, queue, library or alpha changes are included.

## Resolutions (both intents kept; no file taken wholesale)

- **#281 onto #264:** keep #281's helpers, then #264's `removeAll`.
- **`remove`:** `moveOwnership.remove(ids)` runs first, for **every** id, including ids withheld on an unavailable card, so a move in flight can't revive them. Then #264's `removalPlan` commands, and the notice for withheld ids.
- **`removeAll`:** `moveOwnership.removeAll()` runs first, then #264's available-shelves-only commands and notice.
- **`move`:**
  - #264's early `canMove` refusal returns before `moveOwnership.begin()`, so a refused move registers nothing. Git placed `begin()` there automatically.
  - The worker is #234's `try`/`finally`, which posts `finish(batch)` and clears progress, around #264's loop with its per-song `canMove` break.
  - A song is copied only while `moveOwnership.permits` it, and `copy` keeps #281's byte check before the length is set or the result counts as success.
  - The main-thread hand-over is `deliverMovedCopy(from, to) { moveOwnership.publish(batch, id) { sendAddDownload(...) } }`: Add needs both shelves available **and** the batch still owning that song.
- **`mover` area:** #234's `moveOwnership` field sits beside #264's nullable `downloadsOn`, merged automatically.
- **Test class comment:** removal cases reject stale publication (#234), missing-span cases still characterize an unfixed risk, and target-prefix cases assert the byte check (#281). Every component's tests are kept:
  - **Removal:** #234's renamed removal tests.
  - **Byte check:** #281's prefix and comparison-block tests (`differentUnindexedTargetPrefixIsRejectedWithoutAnAddAndKept`, `identicalUnindexedTargetPrefixIsReusedAndAdded`, `identicalPrefixAcrossSeveralComparisonBlocksIsAdded`, `mismatchAfterTheFirstComparisonBlockIsRejectedWithoutDeletingBytes`).
  - **Existing move cases:** the missing-span, failed-prefix and unknown-length cases.
  - **Card availability:** `CardAvailabilityTest` and `CardAvailabilityRouteTest`.
  - **Ownership:** `DownloadMoveOwnershipTest`.

**Interaction checked by reading, so no new test:** #234's removal tests expect two remove commands. #264's `removalPlan` sends every id to each available shelf, and the move fixture's shelves default to available (`Shelf(..., present = { true })`). A test for the withheld-id invalidation isn't needed: with the card unavailable, `canMove` and `deliverMovedCopy` already stop any hand-over involving it.

## Checks run (lightweight only)

- The branch contains main `28a1329` and all three component heads (`git branch --contains`).
- `python3 tools/check_branch_policy.py --base main --head 1f28ecc… --source codex/storage-qa-oct3` passed. This used local refs only, with no fetch, so recheck after refreshing `origin`.
- No conflict markers remain, and `git diff --check` is clean.
- **Not run:** Gradle, unit tests or a build. CI is the first compile and test run.

## Limitations

This is containment only, not a #179 fix.
- **Card removal:** a card disappearing during a cache read, copy or download can still lose the cache's mapping.
- **Card identity:** a different card at the same path isn't told apart (S2).
- **Card service:** the service fallback to the phone (S4) remains.
- **Running copies:** remove or Remove all doesn't cancel a copy already under way. Its target spans stay un-indexed, which is not cleanup, by design (#230).
- **Snapshots, not locks:** the byte check is one extra full read of both copies per song, at that moment only. The availability and ownership checks are snapshots too.
- **Not durable:** move ownership is in memory and doesn't survive the process.

## Device QA (pending; coordinator only)

Existing downloads aren't protected yet, so QA on this candidate is limited to **non-destructive checks with the card mounted**. **No card removal or eject, and no Remove all, on user data.** Card-absent and Remove-all scenarios need a separately prepared card holding only disposable copied audio, with originals kept elsewhere, and a separate go-ahead. They aren't part of this pass.

Mounted-card checks, using disposable copied songs downloaded for the test:
1. Download a few songs to the phone and to the card. They play online and offline, and Settings counts and status look right.
2. Move a test song phone→card, then card→phone. It plays from its new place, the original goes only after the copy completes, and nothing is listed twice.
3. During a move of test songs, remove **one test song**. It doesn't reappear, and the other songs finish moving.

## Next validation

1. Push this branch and open a PR. That's the coordinator's step; it wasn't done here.
2. CI on head `1f28ecc` or later, the first compile and run.
3. Review the combined `OfflineStore.kt`.
4. Then the mounted-card checks above. The destructive scenarios wait for download protection.
