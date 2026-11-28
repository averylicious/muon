# #253 compact index censuses — 2026-10-08

One bounded main-track #253 slice on `codex/resource-census-oct8` (base: open #387 `75c8046` plus main `5a123109`). Source and tests only, **coordinator source-reviewed; exact-head CI pending**. No local Gradle/Android build: GitHub Actions will be the first compile and test run. No device, signing or settings change. Coordinator fetched the pinned published Media3 1.11.0 exoplayer sources from Google Maven, SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`: DefaultDownloadIndex materializes the data blob in cursor.download; DownloadCursor is Closeable. No custom SQL/schema added.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort explicitly selected; implementation. Initial implementation source-reviewed by GPT-6 / Codex desktop (exact variant/effort not reported). Coordinator also changed ID lookup to a constant-time unique-row map and requires the actual census row/key for sole ownership; this correction is author self-reviewed, not independent whole-stack review.

## Finding

`OfflineStore.rows(shelf)` materialized every index row as a full `Download` (request, address, stored song record). The startup played-claims read, `takenNames`, `removeSavedNow`, `completeMovedCopyNow`, `moveBatch` (for the whole batch) and `removeAllNow` all held that list, although they need only IDs, effective keys, own-key status and states. The saved inventory's cursor/two-pass projection and the bootstrap `DownloadStatus` projection were already done and are unchanged.

## Change

- `SavedEntries.kt`:
  - **`IndexRow`** holds a row's request ID, effective key (`keyOf`), whether its custom key equals its ID, and its state.
  - **`IndexCensus`** holds every row, in every state, shown or not, with duplicate IDs marked ambiguous and key counts saturated at two. Unique-ID lookup is constant time, avoiding a full row scan for each move item. It provides `row(id)` (the single row with an ID), `soleOwner(row|id)` (the same #213 rule), `names(name)` and `onlyNaming(name)`.
  - **`forEachIndexRow`** projects one cursor row at a time. Its `use` closes the cursor on every exit, and a failed read is thrown, never a partial census.
  - **`indexCensus`** builds a census on top of `forEachIndexRow`.
  - **`movable`** now takes two censuses plus a reader for the target's full record. It requires the source record's custom key to equal its ID, the source to be the sole owner, and either nothing on the target naming the key or exactly one row naming it whose full record, read again, has an equal request.
  - **`PlayedClaims.keysIn(index)`** and **`read(index)`**: a failed read leaves the claims unknown.
  - **List overloads** (`soleOwner(List)`, `movable(List, List)`, `keysIn(List)`) remain for fixtures. Ownership/movability overloads delegate to the census rule; keysIn(List) remains a fixture helper.
- `OfflineStore.kt`:
  - **Startup played claims** use `claims.read(phone index)`. Only played keys are kept; a failure leaves the claims unknown, as before.
  - **`takenNames`** streams every ID and effective key from both indexes, including an unavailable card's persistent index, then adds both cache key sets. A failure throws, so the save is refused, as before.
  - **`removeSavedNow`** takes a compact census. It still requires the one row with that ID, the current locator key, and sole ownership. A failed census returns NotOwned.
  - **`completeMovedCopyNow`** requires both censuses to show the row COMPLETED and sole-owned. It then reads only that song's source and target records by ID and requires exact `request` equality with the receipt and the COMPLETED state. The epoch, length, extent, byte comparison and receipt queue/acknowledgement steps are unchanged. Any census or record failure returns false, which keeps both copies.
  - **`moveBatch`**:
    - It keeps compact source and target censuses and a list of completed IDs.
    - Per item, it reads the one source record again and requires it to still be COMPLETED. It reads the exact target record only when a target row names the key.
    - An unreadable or no-longer-finished row is counted with "kept" and left untouched.
    - The exclusion, copy stop, single main-thread release/hand-over, publication order and messages are unchanged. No source removal is queued during copy.
  - **`removeAllNow`** materializes the whole compact census before sending any command, then sends per-row removals only for sole owners. REMOVING rows are skipped, and hidden owners still block. A failed census sends nothing for that shelf.

## Tests (added to `DownloadMoveCharacterizationTest`, native SQLite + disposable SimpleCache, real `OfflineStore` calls)

The phone's index is now wrapped in `CensusFaults`. It passes everything through unless a test sets `failAtRow`. The manager's own state-filtered reads are never failed.

1. **Undisplayable alias with a 512 KiB record.** An older FAILED row has an ID too long for a handle and names the song's key.
   - Single removal returns NotOwned.
   - `removeAllNow` returns `0 to 2`.
   - A move sends no Add, writes nothing on the card and says "kept".
   - The alias's record, the song's request and its bytes are unchanged.
2. **Sole-owned row with a 512 KiB record.** Single removal and `removeAllNow` each send one Remove to the phone service. The stored record is unchanged.
3. **Completion with a large record.**
   - The move's Add carries the exact large request.
   - Completion is refused with no target row.
   - It is refused when the target row has the same ID, key and address but another record, and no command is sent.
   - With the exact target record, it sends exactly one Remove.
   - Both records are unchanged.
4. **Mismatched target at move admission.** A target row has the same ID and key but another record. No copy and no Add are made; "kept" is said and the target record is unchanged.
5. **Census failing at its second row.** Each of these fails safely:
   - single removal returns NotOwned;
   - Remove all sends nothing for the phone;
   - `takenNames` throws;
   - `PlayedClaims.read` stays unknown;
   - a move copies and adds nothing;
   - after a real copy, receipt and exact target, completion returns false with no command and both copies' bytes and rows intact.

   All six failed reads closed their real cursors. A later whole read makes the claims known.

The existing list-based census tests (`SavedOwnershipCensusTest`) still exercise the same rule through the delegating overloads. Exact-head CI pending; CI is the first execution.

## What this does and does not establish

- **Removed:** full raw source/target census lists. One full source record and one target record are read for the current move item, besides Media3 cursor decoding. Successful copiedRequests still retains metadata for the batch, so this is not elimination of all move-batch metadata retention.
- **Not bounded:**
  - the per-row native/SQLite cursor window and per-row `Download` allocation;
  - the number of IDs and keys a census holds, which grows with the index (no record cap was added, by design);
  - cache key sets and `takenNames`' set;
  - the inventory's display list;
  - the process-wide memory budget.

  No heap or performance measurement was made or is claimed.
- **`copiedRequests`** still holds each successfully copied request, including its stored record, until the single hand-over step. This is a separate retention limit, left as is so publication isn't redesigned.
- **Not changed here:**
  - `RetainedDownloadIndex.inspect` still collects full unfinished records.
  - The private `soleOwners` helper used by the inventory keeps its own copy of the rule.
- Censuses are still snapshots. The #213 writer argument (fresh `saved/` names; a move adds only `movable` rows) is what keeps them valid. Re-reading per item narrows the gap but is not a transaction.
- **Message semantics:** a source row that can't be read again, or is no longer COMPLETED, during a move is counted under the "kept … can't tell their bytes belong to them alone" message, not "couldn't be moved".

## Remaining #253 questions for the coordinator

1. Should `RetainedDownloadIndex.inspect` get the same compact projection, or does its restart-only, unfinished-only scope make it acceptable?
2. Is an aggregate budget wanted for names, cardinality, cache keys or the display list? That would be a policy decision, not this slice.
3. Should `copiedRequests` hold IDs only, re-reading each request at hand-over? That would change what is published if a row changed in between, so it needs a decision, not a quiet change.
4. The wording for a row that vanished mid-move (the message-semantics limitation above).
