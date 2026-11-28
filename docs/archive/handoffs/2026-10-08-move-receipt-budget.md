# #253 S1: a move batch copies only what its receipts can hand over — 2026-10-08

One bounded slice (S1 of `docs/audits/2026-10-08-aggregate-memory-plan.md`, which is on the #390 worktree) on `codex/move-receipt-budget-oct8` from `d0df73a64b3758375424293fca9638dad2b5dee4`. Source and tests only, reviewed by the coordinator before commit; latest-head checks recorded on the PR. No local build: GitHub Actions will be the first compile and test run, and **none of the tests below has run**. No device, network or experiment use.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort explicitly selected; implementation. Not independently reviewed.

## Defect (verified in source at the base)

`DownloadMoveReceipts` holds at most `capacity` receipts (default 128). `moveBatch` copied every safe completed source and kept every successful copy's full `DownloadRequest` in `copiedRequests`. `handOver` then called `remember` per copy: past the capacity it failed, so each excess copy got its own "another move is still pending" toast. Its target bytes stayed unindexed, and its full request had been held for nothing.

## Change

- **`DownloadMoveReceipts.available()`**, synchronized, returns `capacity - pending`, floored at 0. Pending receipts are never evicted or invalidated to make room.
- **`moveBatch`:**
  - It reads `room = store.moves.available()` once at the admitted start, on the mover. Admission already requires `!hasPending`, and during the exclusion only this batch's own hand-over (which runs later) calls `remember`, so `room` stays valid. It is still read, not assumed.
  - Before each row, if `copiedRequests.size >= room`, the rest of the rows are counted as deferred and the loop stops. Deferred rows get no `movable` evaluation, no copy and no bytes.
  - Only **successful** copies take a place. Kept (unsafe or aliased) rows and failed or stopped copies take none.
  - One aggregate notice comes after the kept and failed notices: "1 more copy stays where it is. / N more copies stay where they are. Move again once this move finishes."
  - With `room == 0`: no copy, no Add, no receipt; only that notice.
- **Unchanged:** `movable`, exact request equality, `copy` and the strict sink, epochs, tokens, shelf binding, admission, removal and acknowledgement, cancellation (`keepGoing` and `#234` revocation), the single main-thread release and hand-over, and the existing kept, failed and pending messages.
- **Progress:** `DownloadMarks.moving` still counts out of all completed IDs. On deferral it stops at the last row the batch processed and is then cleared by the final step as before. It never counts deferred rows as moved.
- `copiedRequests` is now bounded by the free receipt capacity, at most 128 full requests.

## Tests (added to `DownloadMoveCharacterizationTest`: real native-SQLite indexes, disposable SimpleCaches, the actual `OfflineStore.move`; the store is replaced with `DownloadMoveReceipts(capacity)`)

1. **Capacity 2, three sole-owned sources:**
   - Exactly two Adds, each a source's exact request, with matching target bytes and receipts.
   - The third source has no target spans, row or receipt, and its record and bytes are unchanged. Every source row and its bytes are kept, and the single deferral notice is shown. `available() == 0`.
   - After the two finish (target rows recorded, originals removed, receipts finished), a new move copies and adds exactly the remaining one, with no new notice.
2. **Capacity 2:** three valid sources, plus a kept aliased pair (`saved/shared` and `unknown-alias`), plus a finished row with no bytes (the copy fails before writing).
   - Exactly two Adds, both valid. The target spans exist only for those two.
   - The deferral notice is shown, and every source row is kept.
   - Earlier start times force kept/failed rows before the valid rows, testing that they cannot consume slots. Ordering among the valid rows is irrelevant.
3. **Capacity 2, three sources; one copied song removed before the hand-over** (refused as Busy and revoked, #234): only the other copy is added. The revoked copy did not free a place for a third, and `available() == 1`.
4. **Capacity 0:** no command, no target key, source unchanged, the deferral notice, no receipt.

The inherited suites are unchanged, including the move, cancellation, strict-sink, delivery, admission and census tests. No separate `DownloadMoveReceiptsTest` was added: `available()` is exercised through the production batch.

## Limits

- **Memory still not bounded:** each request's own metadata (`request.data`) inside the ≤`capacity` held requests; per-row cursor allocation; target bytes from a stopped or failed copy, which are kept unindexed as before.
- With more than `capacity` eligible songs, a user must move again after the current receipts finish. That takes several moves for a large library, as the notice says. This is UAT-visible: a move of more than 128 songs on disposable data is pending user testing.
- This does not complete #253. S2–S9 in the plan remain.


Coordinator GPT-6 (Codex desktop; exact variant/effort not exposed) focused source review and test corrections: force unsafe/failed rows before valid rows using actual index start-time ordering, assert a single aggregate toast, and reject invalid negative fixture capacities. This is review of this focused implementation, not a new independent review of all inherited changes. New tests capture service intents; the retry fixture simulates completed index/receipt settlement. Existing real-service acknowledgment fixtures cover actual delivery separately.
