# #179 generation ownership map — 2026-10-04

Inspected main `12ab1e44f4e4de9d91eff383bdd8f117f29818e0`, branch `codex/cache-generation-ownership-map`. Compared against the open candidates:
- #302 `78b1e13b4a726f2c1ce7af93166d1936c8ffea60`, which contains #240 bootstrap, #264 availability, #234 move publication, #281 copy-byte check, #237 played-copy cancellation and budget, and #297 accounting;
- #312 `9fa135f4d4eebfae1738034749af02dfed7c5c8d` (bootstrap executor shutdown).

Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). **Documentation only:** source reading, not independent review. No app change, device, network or test run.

**Purpose:** before any production generation stop (a point where one shelf's cache, index and services stop changing), list every current owner that touches a shelf's cache, index or services, and what each would need. Line numbers are main's unless marked #302.

**Evidence levels:**
- **Proven:** source read at the commits above, plus earlier controls:
  - #296 downloader admission prototype;
  - #305 service-helper controls;
  - #309 Loader release;
  - #310 source close;
  - #311 per-open reader admission.
- **Recommendation:** proposed, not implemented.
- **Device I/O:** needs a physical, disposable card.

## Ownership and admission matrix (main)

Columns: **Owner** (where it lives), **Touches**, **Thread / lifetime**, **Stop or receipt on main** (and what #302 adds), **Covered by #311?**, and **Recommendation**.

| Owner | Touches | Thread / lifetime | Stop or receipt on main (#302 adds) | #311? | Recommendation |
| --- | --- | --- | --- | --- | --- |
| **Store construction:** `OfflineStore.get`/`create` 78-135, `shelf()` 80-88 | `SimpleCache(folder, evictor, database)` (82: starts init, can create the folder and UID); `DefaultDownloadIndex(database, ""/"card")` (84); a manager with an **unretained** `newFixedThreadPool(2)` (85) | main, `@Synchronized`; lives for the process; never released | None. #302 adds `cardPresent`, but construction is unchanged | No | A per-shelf generation object owns cache, manager, download executor and evictor. Never construct a cache to probe a card (S2) |
| **Shared database:** one `StandaloneDatabaseProvider` (91) serves both shelves' indexes and both caches' tables | SQLite | process | None | No | Never close or reopen it per generation; a generation stop must not touch the shared provider |
| **Download workers:** manager internal thread, task threads, the pool at 85 | cache writes, index writes | manager threads; release doesn't join (#293/#294) | None. #296 prototype only, test-local | No | Adopt #296 per shelf: retained executor, admission gate around `DefaultDownloaderFactory`, stop manager commands → close gate → bounded drain → shut down executor |
| **Download services:** `MuonDownloadService.kt` 24, 43 (card falls back to phone) | static Media3 helper map, `onStartCommand` intents, restarts | service lifetime; helper outlives the service | None. #305: clearing the map doesn't rebind a live service; destroy then clear selects the replacement | No | S4 owner: freeze app commands, stop both services, then rebind at a verified stopped point. The global clear affects phone and card together |
| **Command senders:** `add` 228-236, `remove` 285-288, `removeAll` 345-347, `resume` 294-296, move Add 326, `watch` leftover removes 147-148 | service intents | main | None. #302 skips an unavailable card (`availableShelves`, `removalPlan`, `leftoverCopies`) | No | Generation-scoped command admission; intents already queued are covered only at the service owner |
| **Manager listener:** `watch` 141-151 (never removed) | `store.record`/`removed`, cross-shelf removes | main, via the manager's looper; lives as long as the manager | None. #302: availability guard plus #240 `changed` | No | Remove the listener at stop, or stamp callbacks with the generation and drop stale ones |
| **Startup index scan:** `watch` 152-156 | index read, then `main.post { record }` | a one-shot executor, then a main post | None. #312 adds orderly shutdown; #240 filters newer events | No | An epoch check inside the posted runnable; a late post must not publish an old generation |
| **`record`/`removed`:** `create` 103-127 | `DownloadMarks`, byte totals, artwork executor (101, unretained; network fetch into the phone `downloads-art` folder) | main + artwork executor | None | No | Epoch on marks and totals; artwork is keyed by ID across shelves, so it isn't a cache owner, but its late tasks need the same epoch |
| **Playback reads:** `playbackSource`/`route` 177-185 → `OfflineDataSource` 22-38 → `Shelf.source` | `CacheDataSource` reads, stale-span removal, index lookups via `completed()`, phone played-copy metadata | Loader thread; the same instance is reopened; the open source is held until close | None. #309: Loader release receipt; #310: `uri == null` is no receipt | **Yes**, test-local: per-open lease, acquired before routing, uncertainty on a failed close | First production lease target. Covers this entry point only |
| **Played copies:** `copyPlayed` 195-211 | phone-cache writes via `CacheWriter` + OkHttp; `downloaded()` reads **both** shelves' indexes, the card's included, from the copier | `copier` (persistent); per-song tasks | None. #302 adds `PlayedCopyWork` cancellation, coalescing and budget | No | Copier admission plus a generation check for its card-index read; keep it separate from phone-only maintenance |
| **Played maintenance:** `setCacheLimit` 214-219, `clearPlayed` 222-225, `PlayedSongEvictor` (`PlayedCache.kt` 45-90) | phone cache only (`removeResource`); `report` posts `PlayedCacheState` | `copier`; evictor callbacks run under the cache lock on any writer's thread | None. #302 routes these through `PlayedCopyWork` | No | Belongs to the phone generation; don't mix it into a card gate |
| **Mover:** `move` 314-331, `copy` 334-343 | source index read, `from.cache` read and `to.cache` write (direct `CacheDataSource`), Add to `to.service`, progress posts | `mover` (persistent) + main posts | None. #302 adds `canMove`, `deliverMovedCopy`, move batch ownership and `sameBytes` | No: direct `CacheDataSource`, not `OfflineDataSource` | A lease on **both** generations, held until the batch finishes; stamp its posts |
| **Index and library readers:** `downloadedSongs` 242-264, `downloadedLibrary` 270-283 (`LibraryModel` 67/98 on `Dispatchers.IO`); `downloadsOn` 302-306 (Settings, main thread); `downloadedOn`/`downloaded` 168-171 | both shelves' indexes; phone cache keys and metadata | IO coroutines, main, copier, Loader | None. #302 lists available shelves only | No: an index read, not a DataSource | Their own short lease or a frozen catalog snapshot. **Not drained just because cache readers are** |
| **Settings card info:** `SettingsScreen` 116 (`cardFolder` re-resolved), `cardDescription` 351 | volume state, not cache | main | #302 keeps `made.cardFolder` | No | S2 identity, not a cache owner |
| **Offline flag:** `OfflineStore.offline` 67 (`PlaybackService` 47, `LibraryModel`) | gates `copyPlayed` | any | n/a | No | Not a generation; leave it alone |

## What the #311 per-open prototype covers, and what it misses

**Covers:** `OfflineDataSource` from before routing through the caller's completed close, including a failed open, reuse of the same instance, and an uncertain close.

**Misses every other row:**
- direct `CacheDataSource` use (mover, copier);
- index and library reads;
- downloader tasks;
- services and the helper map;
- manager listeners and main-thread `Handler` posts;
- the artwork executor;
- construction.

Clearing the helper map, releasing a manager or shutting down an executor is **not** a drain of workers, callbacks or readers.

## New findings

No new defect outside the pending fixes was found in this pass. One owner worth naming: `copyPlayed`'s `downloaded()` reads the **card** index from the phone-side copier, so a card stop must cover the copier as a card-index reader too.

## Next slices (independently reviewable, short)

1. **Inert per-shelf reader lease in `OfflineDataSource`** (source-testable now).
   - **Build:** port #311's lease onto a per-shelf generation object that is always admitting, so nothing calls `close` on it yet.
   - **Accept:** #311's six cases pass against the production class; no behaviour change; CI.
2. **Retained download executor plus downloader admission** (from #296, inert by default).
   - **Accept:** #296's control passes against production wiring, and the executor is never shut down before the gate drains.
   - **Prerequisite:** decide whether this lands on main or on top of #302.
3. **Generation stamp on publication:** `watch` listener callbacks, the startup post, move progress and Add posts, and `record`/`removed`.
   - **Prerequisite:** #302 and #312 integrated, so this composes with #240 `changed` and #234 batch ownership.
   - **Accept:** existing fixtures show a stale-generation post is dropped.
4. **Index-reader and copier/mover leases:** `downloadedSongs`/`Library`/`On`, the copier's card-index read, and a two-generation move lease. Source-testable with existing fixtures.
5. **Composed stop coordinator and S4 service owner:** freeze commands, stop services, release managers, drain every lease above, and only then return a "stopped" receipt.
   - **Needs** physical disposable-card QA (eject during read and download, remount) and the S2 identity and catalog decisions.

**Warning:** wiring an incomplete gate step by step must **not** enable cache release, reinitialization, migration or destructive adoption. That permission exists only when every owner above composes and the stopped receipt holds.

**Also required:**
- **#213:** no deleting or rekeying retained bytes or ambiguous IDs.
- **#230:** stale-target cleanup must honour shared span ownership.
- **Catalog durability:** `AtomicFile.finishWrite` returning is no durable-catalog receipt.
- **Unchanged:** trusted-LAN behaviour.

## Scope limits

- Source mapping only; no runtime counts.
- The playback service's own `ExoPlayer` and `Loader` ownership is covered by #309, not re-derived here.
- No device I/O evidence. #179 remains open.
