# Move copy stops when its move stops owning it (#230)

Branch `codex/move-write-ownership-oct7`, base `90a2e709f56a3ba4130e04827135219959bf9e9a` (includes #373 `a77d577` and main `e1e723f`). Claude Opus 5.5 (`claude-opus-5-5`) in Claude Code, High explicitly selected (the runtime does not report effort). Role: implementation and author self-check; not independent review. Not compiled locally: GitHub Actions is the first compile and run.

## Defect

`OfflineStore.copy` writes a song into the other shelf's cache with Media3's `CacheWriter`. Before this change nothing could stop that writer once it started. If the move lost ownership of the song during the copy (Remove or Remove all, #234), or either shelf became unavailable (#179 S1), the copy still wrote every remaining byte into the destination key. The hand-over was refused afterwards, so the whole song was written for nothing and left behind, unindexed.

## Change

- `copy` takes a `keepGoing` check. `move` passes `canMove(from, to) && moveOwnership.permits(batch, id)`.
- The check runs before the first write and in the `CacheWriter` progress callback, which is called after each cached block. Once it fails, `copy` throws `MoveCopyStopped` (an `IOException`), and the move counts the song as not moved. The user gets the existing "couldn't be moved; saved entry kept" notice.
- This uses the same throw-from-progress-callback pattern as `copyPlayedWithinLimit` (`PlayedCopyTooLarge`), which CI already exercises. That pattern relies on `CacheWriter.cache()` closing its data source before rethrowing, which also commits and releases what the sink wrote. The pinned `CacheWriter` source was not on disk for this pass; the claim rests on that existing compiled, tested use, not on a fresh source read.

## What it does not do

- **Nothing is deleted, truncated or relabelled.** Bytes already written stay unindexed under the destination key. A later move compares them with the source in the existing preflight (#363) and fills only the rest, or refuses them if they differ.
- **Not exclusion.** The check runs between blocks: up to one block may still be written after ownership is lost, and other writers of the key are not excluded. The post-copy comparison and extent checks still decide the result.
- **No cleanup or receipt.** There is no cleanup of partial output, and no claim that a stopped copy left nothing behind. Ownership of failed partial output (who may delete it, when) is still open, together with late-writer exclusion and restart recovery (#179).

## Test

`DownloadMoveCharacterizationTest.removalDuringTheCopyStopsWritingKeepsWrittenBytesAndARetryResumesThem` uses the real native-SQLite indexes and caches and the actual `move`. It removes the song from the move's ownership at a counted card availability check that falls inside the writer loop of a payload of eight 128 KiB blocks plus 17 bytes. It then asserts, from the bytes:

- no Add was sent and no target row was made;
- some, but not all, bytes were written;
- those bytes are exactly a prefix of the source, and the source and its row are untouched;
- a separate later move resumes from the kept prefix, writes the exact full copy and sends exactly one Add.

The trigger depends on the number of card availability checks before the writer (move start, batch loop, copy start). If that count changes, the byte assertions fail visibly rather than passing vacuously.

## Next

1. Run CI to confirm the test.
2. Define ownership of failed partial destination bytes: a recorded owner (per move attempt), so cleanup or retry can be exact instead of relying on a later byte comparison.
3. Exclude or detect late writers to the same key during a copy.

## Coordinator source review

GPT-6 / Codex desktop (exact variant and effort not reported) reviewed the implementation and surrounding move/admission paths at `65a188efab78a1af2b30f357267fdea6d05f6059`. Pinned Google Maven `media3-datasource-1.11.0-sources.jar` SHA-256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`: CacheWriter calls progress before reads, after resolving length and after each read; readBlockToCache catches a callback exception and closes the data source before rethrowing. This is cooperative cancellation, not writer exclusion or transactional rollback; partial destination bytes remain. Repository hook directory has no active hooks; normal coordinator commits retain default hook behavior. CI compilation/native tests still pending.
