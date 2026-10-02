# #230 move byte verification — 2026-10-02

Base: main `b1df6cc3d5286f0a685379d51d98e08347899713`, branch `codex/move-byte-verification`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). Author check only, **not independent review**. Not compiled or run locally: CI is the first compile and test run. Storage QA on a phone is pending, and the PR stays open.

## Trigger

#280 showed it: when the target cache already held an un-indexed prefix with **different** bytes for the song's key, `OfflineStore.move` kept that prefix, filled only the rest from the source, set the length and posted Add. The pinned Media3 1.11.0 `CacheWriter.cache` skips any range the target already holds (`CacheWriter.java:125-137`). See the [partial-target report](2026-10-02-partial-target-preservation.md).

## Fix

In `OfflineStore.copy`, after `CacheWriter` finishes, `sameBytes` reads the source and target for the declared source length and compares them.
- Each side is read through a `CacheDataSource` built with no upstream factory, so it has no write sink and a placeholder upstream (`CacheDataSource.java:306-307,558`, pinned 1.11.0 datasource, sha256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`).
- The default flags don't block on the cache. So a missing or locked byte fails the read, and no replacement audio is fetched/written. Normal Media3 reads can touch cache metadata or reconcile stale spans, so this is not a nonmutating storage probe.
- Comparison uses two 64 KiB buffers, so memory doesn't grow with file length.
- Both readers are closed on every path, including failed opens and mismatches.

**On any mismatch, short target or read error,** `copy` throws before the final `applyContentMetadataMutations` and before Add, so the song stays where it was. Source and target bytes are left in place; nothing is deleted, released or migrated.

**A smaller alternative was considered and rejected:** checking the target for existing spans before copying would also refuse a legitimate identical resume. Comparing afterwards accepts that case.

## Tests (`DownloadMoveCharacterizationTest`; CI pending)

- **`differentUnindexedTargetPrefixIsRejectedWithoutAnAddAndKept`** replaces #280's unsafe case. It expects:
  - no Add and no target index row;
  - the old 200-byte span kept byte-identical;
  - the suffix `CacheWriter` wrote still present;
  - the source and its index row unchanged.
- **`identicalUnindexedTargetPrefixIsReusedAndAdded`:** a matching prefix gives exact bytes, content length 512 and one Add.
- Coordinator-added cases cover matching data across multiple64KiB comparison blocks and a differing byte after the first block; they exercise the production loop and preserve fixture source/target bytes. Coordinator also rejects nonpositive read progress instead of risking a loop if a reader violates its usual contract.
- **Unchanged:** the normal move, remove/removeAll, missing-later-span, failed-prefix and unknown-length cases. Both missing-span cases fail inside `CacheWriter` before the check runs.

## Limits (not addressed)

- **Extra I/O:** a full extra read of both copies per moved song.
- **Media3 may already have recorded the target's content length** while writing (`CacheDataSource.java:804-817`). Skipping Muon's final length update does not guarantee the target has no length metadata.
- **The check covers the declared source length only.** Target bytes beyond it are not compared.
- **A snapshot, not a lock:** a concurrent writer or remover can change either cache after the check (#234 publication, explicit add/remove ownership). It is not a durability or immutability guarantee.
- **Rejected copies stay un-indexed.** #230 cleanup, accounting and adoption by a later explicit download stay open.
- **Not addressed here:** absent or replaced card (#179 S1/S2), Tauon renumbering (#213), and #234/#237/#240, which remain separate open dependencies.

## Manual QA (pending, expendable duplicate downloads only)

1. Move to card and back with ordinary downloads: songs play, and nothing stays in both places.
2. On a separately authorized disposable-card fixture, test interruption and retry with expendable duplicates; preserve independent originals. Do not eject a card holding sole copies or treat closing an Activity as proof its worker stopped.
3. Confirm retained originals and correct playback after either direction. This Pixel has no removable card, so no real card/move QA was performed in this slice.

GPT-6 / Codex desktop (effort not reported) independently reviewed Claude's production change against pinned CacheDataSource semantics. Multi-block cases, nonpositive-read guard and metadata/QA qualifications are coordinator authorship/self-check. Latest-head CI pending; no local compile/runtime success claimed.
