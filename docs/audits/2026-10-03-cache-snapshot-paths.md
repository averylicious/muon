# #179 S3 cache snapshot paths — 2026-10-03

Inspected main `fff143b630521268ec6b1f829ae335e0884ad176`, branch `codex/cache-snapshot-paths`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). Author investigation and tests, **not independent review**. Test-only: no production change, device, user cache or music.

Pinned source: Media3 1.11.0 `media3-datasource` sources jar. Its sha256, `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`, matches the [volume report](2026-10-02-volume-catalog-evidence.md); it was read with Python's `zipfile`.

## Source facts

- **Who renames on a touch:** `SimpleCache.touchSpan` (`SimpleCache.java:637-661`) does nothing unless the evictor requests touches.
  - With a file index (`fileIndex`, present when a `DatabaseProvider` is given and the legacy index isn't preferred, 207-209), it updates the index and passes `updateFile = false`.
  - Only with no file index does `CachedContent.setLastTouchTimestamp` rename the file to a new timestamped name (`CachedContent.java:223-241`; name from `SimpleCacheSpan.getCacheFile`, 51-52).
  - Either way, the old `CacheSpan` is replaced by a new object. The javadoc says the passed span "becomes invalid".
- **How Media3 reports it:** the public `Cache.Listener.onSpanTouched(cache, oldSpan, newSpan)` (`Cache.java:77`) is called for each registered key listener, then the evictor (`SimpleCache.java:758-766`). `addListener` returns the current spans (279-290).
- **Main's relevance:**
  - main builds every cache as `SimpleCache(folder, evictor, database)` (`OfflineStore.kt:82`), so there is a file index, and **ordinary touches do not rename files on main**;
  - the card uses `NoOpCacheEvictor`, which doesn't request touches (`NoOpCacheEvictor.java:30-31`);
  - the phone's `PlayedSongEvictor` does (`PlayedCache.kt:55`), so a read replaces the phone span object but keeps its path.

  The Oct 2 [feasibility report](2026-10-02-cache-snapshot-feasibility.md) implied touches could rename files on main; it has been corrected.

## Controls added to `RemovableCacheCharacterizationTest` (CI pending)

Both use disposable temporary folders, native SQLite, the existing `seed` helper, the production `PlayedSongEvictor` (it requests touches but never evicts the non-played test key), and a public `Cache.Listener`. There's no reflection, private API or filename parsing.

1. **`databaseBackedTouchReplacesTheSpanButKeepsItsFile`:** this is main's phone shelf configuration. One read touch should reach the listener once, as (captured span, new span):
   - the new span is the one returned and is a different object;
   - both spans have the original file;
   - the snapshot now holds the new span, which is also what `getCachedSpans` returns;
   - the payload bytes are intact.
2. **`legacyIndexTouchRenamesTheFileAndAListenerFollowsIt`:** a control using the public constructor with a null `DatabaseProvider`, so there's no file index. After one read touch:
   - the listener receives (captured span, new span), and the new span is the one returned;
   - the new timestamp is greater, and the file has a new name;
   - the captured path no longer exists;
   - the snapshot, updated by the listener, points at the renamed file, which holds the exact payload.

**Clock gap:** `SimpleCache` stamps touches with `System.currentTimeMillis()`.
- I couldn't confirm from pinned sources whether Robolectric 4.16.1 instruments Media3 classes to control that clock. Its sources weren't available here, and the download needed approval.
- So the legacy case doesn't use Robolectric clock control. It waits, without sleeping and with a one-second deadline, until the real wall clock has passed the captured timestamp, so the touch can't land in the same millisecond.
- A wall clock stepping backwards, or Robolectric unexpectedly controlling Media3's clock, would show up as a failed rename assertion, not as a Media3 behaviour change. The database case doesn't depend on time.

## Limits

These controls are not durable capture, complete capture, crash-safe capture, an atomic metadata snapshot or a migration fix.
- The snapshot lives in test memory. A listener misses changes made before it registered, while the process wasn't running, or after release.
- Listener callbacks run under the cache lock, and the listener sees span objects, not content metadata.
- File and byte changes outside Media3, card disappearance, and index loss on release (characterized earlier) are unaffected.

#179 remains open. Nothing here deletes, releases or migrates user caches, and there's no catalog, schema or hot-swap.
