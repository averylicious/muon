# #230 partial-target preservation — 2026-10-02

Inspected main `e24fa865d5fdf03ae5661aa4475a3a6ee5a8317a`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected. Initial source/design part changed no production code/tests and accessed no user cache. The later test-only follow-up is described below. GPT-6 (Codex desktop; effort not reported) independently checked the main copy path and pinned API facts, saved the report after Claude's managed checkout was read-only, and added the prerequisite qualifications below. These editorial additions are coordinator self-checks, not independent review of their own wording.

**Follow-up (same day, same author and effort):** this branch now adds two test-only characterization cases to `DownloadMoveCharacterizationTest`. They are described under *Move reuse characterization* below, and they are pending their first CI run. No production code changed.

**Pinned sources:** Media3 1.11.0 datasource/exoplayer jars from Google's Maven, sha256 recorded in the [volume-catalog evidence report](2026-10-02-volume-catalog-evidence.md) (#277). This slice re-read them for `SimpleCache`, `CacheDataSource`, `DownloadManager` and `ProgressiveDownloader`.

**Open PRs, not on main:** #234 (move publication ownership), #237 (played-copy worker) and #240 (download bootstrap ownership). Claude initially used the [Oct 1 handoffs](../handoffs/2026-10-01-source-audit.md); coordinator then fetched/read exact heads: #234 `370f74d2d43684cec7a313db3fb7b1feb79fb901`, #237 `b38e17f250d2c7a133a6ed8ee16bb2e3195a9609`, #240 `0304d629d85e836e3b3ed3a96621d923efc0da9d`. #234 rejects obsolete Add publication before/after a copy but does not abort a running copy or own its spans. #237 operates on optional `PLAYED_PREFIX` copies/maintenance, a separate key namespace; #240 suppresses obsolete UI/index-bootstrap marks. Neither establishes ownership for explicit move target spans.

## Main's move path

`OfflineStore.move` (`OfflineStore.kt:314-331`) works through each completed source download on one `mover` thread.
- `copy` (`334-343`) writes into the target cache under the **same key as an explicit download** (`id`, the `customCacheKey` set in `add`, `228-236`).
- It goes through `CacheWriter` with `createDataSourceForDownloading`.
- On success it posts `sendAddDownload` to the target service. On failure it does nothing more.

## Already reproduced (#230 characterization, not repeated)

`DownloadMoveCharacterizationTest.missingLaterSpanLeavesPartialDestinationBytesWithoutAnAddOrDownloadRecord` and [move-removal](2026-09-30-move-removal.md): a source span missing mid-copy leaves partial target spans, no target index row, no Add, and the source unchanged. The remove/removeAll-before-Add cases are the #234 publication problem.

## Source facts (pinned Media3, main)

1. **No owner per span.** `SimpleCache` spans carry key/position/length/file only. `removeResource(key)` removes **every committed span for the key**, whoever wrote it, and doesn't wait for writers of other ranges (`SimpleCache.java:446-451`).
2. **Concurrent writers to one key interleave.** Download data sources always set `FLAG_BLOCK_ON_CACHE` (`CacheDataSource.java:274-277`). `startReadWrite` waits for a locked range, then **reads** a span someone else committed instead of writing it (`SimpleCache.java:328-370`). A move copy and an explicit download of the same id on the same shelf therefore produce indistinguishable spans.
3. **Media3 can't clean un-indexed spans.** `DownloadManager.removeDownload` for an id with no index row logs "nonexistent" and returns without touching the cache (`DownloadManager.java:898-902`). `removeAllDownloads` only visits indexed downloads (`908-923`). Only an indexed removal reaches `ProgressiveDownloader.remove` → `removeResource` (`ProgressiveDownloader.java:208-209`).
4. **The configured size evictors do not budget these spans.** The card uses `NoOpCacheEvictor`, and the phone's `PlayedSongEvictor` evicts only `PLAYED_PREFIX` keys (`PlayedCache.kt:53-59`). Thus failed-copy spans are not reclaimed by those byte budgets. Later indexed removal may remove them; stale-file cleanup, manual filesystem changes and storage lifecycle are separate paths, so indefinite physical persistence is not asserted.
5. **A later explicit download adopts them (source-supported hypothesis).** By fact 2, an `add` of the same id on that shelf reads the retained partial spans instead of fetching them. Combined with #213 (Tauon renumbering), old bytes could be stitched into a different song. Not reproduced.

## Move reuse characterization (pending CI)

Two cases in `DownloadMoveCharacterizationTest` run the real `OfflineStore.move` on its existing `mover` thread. They use disposable `SimpleCache`s and native-SQLite indexes in temporary folders, and the fixture's existing reflection and FIFO barrier. There is no sleep, network, service, phone or user cache. These are **unsafe-outcome characterizations, not fixes**. CI is their first compile and run, so nothing here has passed yet.

The source fact behind them: `CacheWriter.cache` skips any range the **target** cache already holds for the key, and reads only the holes (`CacheWriter.java:125-137`, pinned 1.11.0 datasource).

1. **`unindexedTargetPrefixIsKeptAndCompletedFromTheSourceThenAdded`**
   - **Setup:** the target holds an un-indexed 200-byte prefix whose bytes differ at every position. The fully completed source holds 512 new bytes for the same key.
   - **Expected:** the target ends as the old prefix plus the source's suffix, fully cached with content length 512. The source spans and index row are unchanged, and one Add is posted to the card service.
   - **What it shows:** the move's own reuse. It does not show `DownloadManager` adopting spans for an explicit download, audio decoding, Tauon renumbering (#213) or user data loss.
2. **`failedMoveOverAnUnindexedTargetPrefixKeepsItAndTheSourceWithoutAnAdd`**
   - **Setup:** the missing-later-source-span setup, with an un-indexed 1,000-byte target prefix of distinct bytes.
   - **Expected:** no Add and no target index row. The target span at position 0 keeps exactly its earlier length and bytes, and the target is not fully cached. The source's first span and index row are retained.

If CI shows different behaviour, record the actual outcome instead of changing these expectations to match an assumption.

## Policy comparison

| | Retain on failure (main today, made explicit) | Cleanup with proven ownership |
| --- | --- | --- |
| Bytes at risk | None deleted | Any key-wide delete can remove a concurrent or later explicit download's committed spans (facts 1–2). The index can then say COMPLETED with bytes missing, because `Shelf.completed` trusts the index |
| Cost | Unaccounted storage (fact 4); adoption hazard (fact 5) | Needs proofs Media3 doesn't expose (below) |
| Implementable separately now | **Yes** as the current no-delete fallback. Optional accounting/failed-move reporting needs a separate design, and must not instantiate or open an absent cache to inspect it | **No** |

**Ownership that cleanup would need, and that is missing:**
- (a) A per-key lease that serializes `move`, explicit `add`/remove/removeAll handled on `DownloadManager`'s private internal thread, and every target-key writer. #237/#240 are independent improvements, not a target-key ownership lock. Media3 has no public check-and-remove that is atomic against `DownloadManager`.
- (b) A pre-copy snapshot proving the target had no spans and no index row for the key, **and** that nothing wrote between snapshot and cleanup.
- (c) #234's operation generation, so a late callback can't clean up or publish another operation's work.
- (d) #179 S1/S2 availability and identity, so no cleanup runs on an absent or replaced card.

**Candidate, not proposed as safe:** make the move an indexed target download, so a failure removes through `DownloadManager`. `addDownload` merges into an existing record (`DownloadManager.java:879-895`), so an explicit add during the move would merge into it, and the move's failure-removal would delete the user's requested download. It needs (a) and (c) first.

## Decision

**Retain bytes and defer deletion for this slice.** The inspected current path does not provide proof for safe automatic cleanup. This is not a proof that staging in a distinct namespace or a redesigned transaction protocol is impossible. Don't add `removeResource`, `removeSpan` or index edits to the move failure path.

**Possible separately scoped follow-ups (not implemented):**
- whether to add read-only accounting of un-indexed partial keys, with no deletion;
- whether a future explicit `add` should refuse to adopt un-indexed spans. Reconcile that with #213 identity work; no mandatory sequencing or settled behavior is claimed.

## Acceptance tests for any future policy

Reuse the `DownloadMoveCharacterizationTest` fixture; no new framework.
1. **Failure over existing target spans:** a second failed move over an earlier failed move's partial key leaves every earlier span byte-identical, with no index row and the source unchanged.
2. **Failure with a completed explicit target download of the same id:** the index stays COMPLETED, the bytes are exact and nothing is removed.
3. **Concurrent explicit add on the target during the copy:** use a barrier in the source read; the copy fails. The explicit download's spans survive and it completes with exact bytes.
4. **Remove or removeAll during the copy, then the copy fails:** no Add, nothing resurrected (#234), and no deletion beyond what the explicit removal itself did.
5. **Late copy success after remove, then a new explicit add:** no stale Add, and the new download's bytes are kept.
6. **Ownership proof:** any cleanup runs only with the proof (a)–(d) present. If ownership is ambiguous, the test asserts retention.
7. **Card unavailable:** no cleanup decision runs.

**Not claimed:** runtime, heap, device or storage-size effects. Facts 1–4 are read from source only; fact 5 is a hypothesis; the cleanup ownership rules are proposals.

Coordinator validation: reviewed Claude's test-only fixture diff, added explicit target-index absence assertions, checked whitespace/main destination. Latest-head full signed CI is pending; it is the first compile/execution of the new cases. No app behavior change or phone QA is required for this characterization; production fixes still need their own gates. #230 remains unresolved.
