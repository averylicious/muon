# Move destination-write evidence (#230) — 2026-10-08

Branch `codex/move-completion-receipt-oct8`, base `fb8a1f1` (#384 head `7ae` merged with main `2a9346d`). Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort explicitly selected (the runtime does not report effort). Role: implementation and author self-check, not independent review. **Uncommitted**; the coordinator owns review, commit, push and CI. Not compiled locally: GitHub Actions is the first compile and run.

## Why

Pinned media3-datasource 1.11.0 (sources SHA-256 `a54ddd98…e4a`; `/tmp/muon-CacheDataSink.java`, `CacheWriter.java`, `CacheDataSource.java`, `TeeDataSource.java`):

- **`CacheDataSink.closeCurrentOutputStream` can't report a close failure.** It flushes, then calls `Util.closeQuietly(outputStream)`, then commits if the flush succeeded. A failure to close the file is never seen, and nothing asks for a sync. Wrapping `CacheDataSink.close()` can't observe that suppressed close.
- **`CacheWriter` closes quietly after a read or callback failure.** So a sink-close failure on that path is hidden from its caller. On the success path, its close does throw.
- **The move can supply its own sink.** `CacheDataSource.Factory.createDataSourceForDownloading` makes one write sink per source, from `setCacheWriteDataSinkFactory`. `TeeDataSource.close` closes the upstream first, then the sink.

## What changed

**`StrictMoveSink`** (new, `StrictMoveSink.kt`) is the move copy's cache write sink, in place of `CacheDataSink`.

- **One file at a time:** each `open` reserves one unfragmented span file with `cache.startFile`, written through one 64 KiB buffer.
- **`close()` order:**
  1. flush, then `FileDescriptor.sync`, then close; each failure is thrown;
  2. `cache.commitFile` only if all three succeeded and no earlier write or open failed.
- **Failure handling:**
  - The first failure is kept in `failure`, even when the caller closes quietly.
  - The output is closed whatever happened before.
  - A file that failed before its commit is deleted. It was reserved by this sink and never entered the cache.
  - A file whose **commit** failed is left alone, since the cache may partly know it.
  - Nothing else is deleted.
- **Evidence:** `clean` holds only when every opened file was committed and none is open.
- **Test seam:** `MoveFileOutput` / `MoveFileOutputs` is the file-output seam. `MoveFileOutputs.Real` is a `FileOutputStream` behind a `BufferedOutputStream`, and `sync()` is `stream.fd.sync()`.

**`OfflineStore.copy`:**

- The destination writer uses `setCacheWriteDataSinkFactory { StrictMoveSink(to.cache, moveOutputs) }`.
- After `CacheWriter.cache()` returns, the copy fails unless every sink it made is `clean`.
- The existing post-copy checks (`sameBytes` over the full length, and the extent checks) still follow.
- So the hand-over Add, and the later source removal, which needs a receipt, now require committed, synced and closed destination files.
- `OfflineStore.moveOutputs` (internal, default `Real`) exists only so fixtures can inject output failures.

**Unchanged:**

- The path where the destination already holds a complete, exact recorded copy writes no files, so its existing exact-copy validation still decides.
- Cancellation (`keepGoing`) is unchanged. A prefix committed before a stop or a failure is kept, as before. A file that failed its own flush, sync or close is not committed.
- Command admission, receipts, preflight and availability checks are unchanged.

## Tests (source only; nothing run)

**`StrictMoveSinkTest`** (new) runs on a real `SimpleCache` with native SQLite. Failures are injected only in the file output (wrapping the real output) or, once, the cache commit (a delegating `Cache`). It checks:

- **Healthy control:** a healthy file is flushed, synced, closed and committed exactly.
- **Flush, sync or close failure:** nothing is committed, the reserved file is gone, the output is closed, and the failure is kept.
- **Commit failure:** recorded, and the file is not deleted.
- **Write failure, then a quiet close:** nothing is committed and the output is closed.
- **Open failure:** nothing open, nothing reserved.
- **Through the actual `CacheDataSource` + `CacheWriter`:** the upstream fails after 64 KiB, so Media3 closes quietly and rethrows the read failure. The sink still holds the hidden close failure and commits nothing.

**`DownloadMoveCharacterizationTest`** (production `OfflineStore.move` with real caches and indexes; only the output is wrapped or injected):

- **Healthy move:** goes through the strict sink. One file is opened, then flushed, synced and closed once each, before exactly one Add.
- **Sync, then close failure:** nothing is committed to the destination, there is no Add and no target row, and the source bytes and row are kept. A later move with healthy outputs completes and adds once.
- **Teardown:** resets `moveOutputs`.
- **Existing tests kept:** the existing partial-retry, mismatch, cancellation, admission and receipt tests are unchanged. They now run through the strict sink with real outputs.

These still capture intents rather than delivering them.

## Limits

- **A sync is not durability.** It asks the platform to write the file out. It is not a transaction, crash recovery, or proof against a removed or failing card (#179, deferred).
- **No evidence about other writers or cache internals:** SimpleCache's index persistence after `commitFile`, a late writer to the same key, and what happens across a restart are all outside this slice.
- **Commit failures are not repaired.** Partial committed prefixes from stopped or failed copies are kept, not cleaned up. No orphan or unknown-file deletion is proposed or safe.
- **Not covered here (next coordinator slice):** queued source-removal ownership, acknowledgement and restart semantics after the hand-over.
- **#230 is not complete.**

## Changed paths

- `app/src/main/java/dev/avery/muon/StrictMoveSink.kt` (new)
- `app/src/main/java/dev/avery/muon/OfflineStore.kt`: `copy` uses the strict sink and checks its evidence; adds the `moveOutputs` seam and an `IOException` import.
- `app/src/test/java/dev/avery/muon/StrictMoveSinkTest.kt` (new)
- `app/src/test/java/dev/avery/muon/DownloadMoveCharacterizationTest.kt`: two tests, and the teardown reset.
- `docs/handoffs/2026-10-08-move-completion-receipt.md`
