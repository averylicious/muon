# Saved-entry key, alias and cover ownership: test-local controls

Base main `313591f9db34cd2ad715096c7ab99f979626a005`. This is test-local preparation for [#213](https://github.com/averylicious/muon/issues/213). It is the "Next bounded task" of the [production boundary plan](2026-10-05-saved-production-boundary.md).

Attribution: Claude Opus 5.5 (`claude-opus-5-5`) in Claude Code, High effort explicitly selected (the runtime does not report effort). Role: implementation and author self-check, not independent review. GPT-6 / Codex desktop (exact variant/effort not exposed) independently reviewed the test and surrounding app code, checked the pinned published downloader sources, and refined the limitations below; documentation additions are self-reviewed.

Scope: no app code, schema, migration, phone work or user-store access. CI is the first Kotlin compile and run of `SavedOwnershipControlTest`. Executed-case counts belong on the PR, not here.

## What the controls use

The fixtures are all disposable Robolectric files:

- a real `SimpleCache` and `DefaultDownloadIndex` for two shelves, with native SQLite;
- real `DownloadArt` cover files, stored under the same name `fetch` uses (no network);
- for removal, a real `DefaultDownloaderFactory` downloader's `remove()` followed by `DefaultDownloadIndex.removeDownload`. These are the two steps a removal takes.

No `DownloadManager` thread, network data source, server or device is involved. Assertions compare the actual index rows, cache keys, metadata, span files, bytes and cover files.

## Findings

1. **The request ID is the index's row key.** A second save under the live ID replaces legacy A's row and leaves A's bytes unowned. A prospective save therefore needs its own request ID as well as its own cache key. This is a characterization of `DefaultDownloadIndex.putDownload`; production's `DownloadManager` add/merge path was not exercised.
2. **A distinct opaque key keeps A and B separate, but proves nothing about identity.**
   - A key allocated against the readable index and cache skips an unindexed leftover rather than adopting it.
   - Legacy A, the leftover and fresh B all remain byte-exact.
   - Identical tags over different bytes cannot say which entry is the live song.
   - Any writer can index another row naming B's key, so the key is not authentication.
   - The test key counter is not a proposed format, and no trust may be derived from a key, UUID or tag.
3. **Unconditional removal of one alias is unsafe.** The real downloader removes the resource by cache key. Another `COMPLETED` row that names the same key then claims a copy whose bytes are gone.
4. **Conservative prospective rule.** Removal is exact only when all of the following hold:
   - the row is in this shelf's own index;
   - it has an explicit custom key;
   - it is not queued or downloading;
   - it is the only row claiming that key, including legacy rows whose key falls back to their URI;
   - every declared co-owner index of the same cache was readable.

   Otherwise the rule refuses. The refusal reasons are a missing row, a malformed row, a row in flight, an aliased key, or an incomplete census. A refusal leaves every row, key, metadata value and span unchanged. An undecodable tag does not block exact removal, because this decides ownership, not identity.
5. **A row on another shelf never owns this shelf's bytes.** Leftover phone bytes beside a card row are neither removable through the card row nor adopted as owned. Exact removal of the card entry leaves them untouched.
6. **Today's cover is per live ID.** Phone and card rows of one live ID share a single `DownloadArt` file, and `DownloadArt.remove(id)` takes it from both. The production `removed` callback checks for another completed record first; it is not exercised here.
7. **Prospective covers are owned per entry, named by shelf and request ID.**
   - Removing an exact entry deletes only its own cover.
   - An entry without its own cover has none: it does not adopt the legacy live-ID cover.
   - The legacy cover's owner set is unknown, so it is neither deleted nor adopted.
   - `DownloadArt.forArtwork` still serves the legacy cover for the live address, so a live view can show a cover that belongs to some unknown old entry. The application boundary must handle that.

## Limits and what remains open

- **The rule is a test-local function.** Its value is the real-record evidence above, not the function itself. The cover deletion in finding 7 is the test's own action, guided by that rule.
- **Not addressed:**
  - time-of-check to time-of-use between planning and removal;
  - concurrent `DownloadManager` work and #230 partial-target mutation ownership;
  - #179 lifetime and recovery;
  - restart persistence;
  - authenticated card identity (the shelf location is only a label);
  - which indexes are declared co-owners in production.
- **No migration, rekey, deletion of uncertain bytes, collision UX or cache release on a missing card is proposed or approved.**
- **Pinned-source check:** the coordinator inspected [Media3 exoplayer1.11.0 published sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar), matching the build file at this baseline. `DefaultDownloaderFactory.createDownloader` passes the request custom key to a progressive MediaItem; `ProgressiveDownloader` puts it in its DataSpec, then `remove` calls `cache.removeResource` using the cache-key factory. `DownloadManager.Task.run` calls downloader removal and `InternalHandler.onRemoveTaskStopped` deletes the index row. The test exercises those library effects, not the manager's asynchronous state machine, callback races or production removal listener.

## Next boundary

Use these controls as evidence for the combined application boundary described in the plan, on an isolated #302-plus-main branch. **Do not directly promote the test-local rule into production:** an owner census, atomic mutation/generation boundary, persistence and unavailable-shelf behavior still need design and verification. That application PR needs:

- per-entry request IDs, keys and covers for new saves;
- refusal rather than removal for aliased, uncensused or in-flight owners;
- no deletion or adoption of legacy covers or leftovers.

Live selection streams when retained identity is uncertain. Device QA is not needed for this test-only change.
