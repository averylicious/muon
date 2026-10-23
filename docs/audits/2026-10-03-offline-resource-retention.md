# Offline resource retention — 2026-10-03

Inspected main `8dea8cabf7089614acfaa6e07e7d4e6336f6b80d`. GPT-6 / Codex desktop, effort not reported; author source investigation/self-review, no independent review. This continues R9 from [the resource inventory](2026-10-02-resource-budget-inventory.md) and #253. No app code, caps, dependency changes, device access, heap measurement or performance claim.

## Pinned source provenance

Build files select Media3 1.11.0. Read its published [datasource](https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource/1.11.0/media3-datasource-1.11.0-sources.jar) and [exoplayer](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar) source JARs already retained by the volume-catalog audit; verified SHA256:

- datasource: `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`;
- exoplayer: `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`.

Line numbers below count literal newline characters. Source JAR review does not establish compiled-artifact equivalence or peak live heap. No advisories were refreshed by this report.

## R9: resolved source questions

### Cache metadata is resident, not merely disk-backed

`CachedContentIndex` owns `HashMap<String,CachedContent> keyToContent` (75,165). `initialize` calls storage.load into that map (198-210). DatabaseStorage.load reads every row, decodes its complete metadata and creates a CachedContent in the map (803-836). `CachedContent` retains a DefaultContentMetadata (49,61-71), whose map stores byte arrays (DefaultContentMetadata 40-50). SimpleCache.initialize invokes this load before directory scanning/removing empty keys (550-569).

Therefore **metadata for all persisted rows is materialized during loading**; metadata for the surviving indexed contents stays reachable with the cache. Stale/empty rows may subsequently be removed, so this does not mean every persisted row remains forever. Audio spans remain files; this is not a claim that SimpleCache loads every song's audio into RAM.

Muon's `OfflineStore.copyPlayed` writes the full encoded song record under `SONG_METADATA` on the **phone played-copy key** (195-211). Explicit download song records instead live in DownloadRequest.data in the download index (add,230-235); do not attribute the full record to every card cache key. Downloads can retain standard length/redirect metadata and index records separately.

`DefaultContentMetadata.get(key,byte[])` returns a defensive array copy (72-78). Muon's offline listing therefore copies each played record before decode (`downloadedSongs`,259), in addition to the cache's retained bytes. `get(key,String)` allocates a String (83-88). Content mutation copies are distinct from the limit on audio span bytes. The played-copy evictor's byte budget and a finite HTTP response do **not** establish an aggregate metadata/heap bound.

### DownloadManager does not normally retain completed downloads in its current list

The pinned public `getCurrentDownloads` contract explicitly excludes completed and failed states (413-419). InternalHandler.initialize reads only QUEUED,STOPPED,DOWNLOADING,REMOVING,RESTARTING (786-806). Main-thread `downloads` and internal-thread lists/snapshots hold current Download objects (617-640), not a resident list of every completed download by default.

This narrows the prior unverified R9 question. A large completed-download library must not be multiplied by a speculative always-resident manager list. It still has disk/index/cache metadata and Muon's explicit listing/bootstrap paths.

### Bulk operations load larger collections

- **Muon bootstrap:** OfflineStore.watch builds `known: ArrayList<Download>` from all indexed states, then captures it in a Handler-posted closure (152-156). Full records are retained until this batch drains; the persistent UI afterward mostly holds ID/mark/byte totals, not this list. Open #240 adds state ownership; it does not establish a batch memory budget.
- **Muon move:** OfflineStore.move reads every completed source download into an ArrayList (320-322), then holds it while copying sequentially and posting completions. The open move guards and #289 protect other invariants, not aggregate list size. Do not imply those fixes are already on main.
- **Media3 Remove all:** DownloadManager.InternalHandler.removeAllDownloads loads completed/failed records, appends their removing-state copies to current downloads and builds one updateList (908-935). That list is shared across the batch's DownloadUpdate messages; **do not count a fresh full metadata copy for every callback**. No user Remove-all test was performed.
- **Offline library:** downloadedSongs iterates completed records and retains decoded TauonTracks, then adds complete played copies. DownloadedLibrary first counts origins, then loads the selected offline library. Per-item bounds do not cap that aggregate result.

DefaultDownloadIndex.getDownloadForCurrentRow obtains the data BLOB then builds a DownloadRequest (446-477); DownloadRequest copies data in its constructor (221). This establishes a transient byte-array copy path, not a measured retained multiplier or heap estimate.

## Impact and limits

Confirmed source retention/copy routes refine #253's aggregate budget inventory. They are **not a reproduced OOM, memory leak, user-visible jank or reachable security exploit**. Legitimate downloaded collections may be large; choosing a cap without compatibility evidence could regress the app.

No song-count/byte/heap limit is chosen. #258's incoming per-record guard remains open (included in #290), and even adopting it would not bound legacy stored rows, parse-time DOM peaks, aggregate cache metadata or current/bulk lists. Recovery sidecar catalogs would add another retained representation and must be included before choosing an ownership design.

## Next bounded verification

1. A disposable cache/manager inventory may count encoded metadata bytes and current versus completed records for representative collections; counts are not heap size. Never load all user downloads as an OOM experiment.
2. Extend ordinary-tag compatibility fixtures to repeated playlists/50k songs before proposing any destructive or truncating policy.
3. Specify separate peak parsing, retained library, queue and offline-metadata budgets with preservation behavior; do not silently shorten queues, drop duplicate occurrences or delete old downloads.
4. Framework/remote queue retention, live heap/GC and timing remain unverified; phone acceptance of #289/#290 does not close #253.
