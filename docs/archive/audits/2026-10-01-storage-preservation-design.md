# Storage preservation design boundary — 2026-10-01

Inspected main: `d60fe09fa0d401e69049a1d1da6d1084237ec0c1`. Continuation of #179, not an implemented hot-swap/data-recovery fix. GPT-6 / Codex desktop, effort not reported; source self-review. No phone, mount event or user media accessed.

## Evidence established and still missing

- [Original review](2026-09-28-storage.md) and [real-cache harness](2026-09-29-cache-characterization.md), merged #192, show the modeled index-loss mechanism. Cache span filenames need the content-ID mapping; absent files can cause removal, persistence and later deletion of unrecognized spans. Phone behavior remains unverified.
- [Card-index harness](2026-10-01-card-index.md), merged #256, shows actual different mounted disposable directories sharing completed records through the phone-side DefaultDownloadIndex named `card`. Same-card and distinct-index controls preserve expected tiny bytes. This is identity evidence, not an implemented namespace/migration or hot-swap reproduction.
- Main OfflineStore.create selects a card once, but Store.card is not updated for mount changes. Shelf.completed trusts a DownloadIndex record without checking the attached volume/bytes. Shelf selection, listing, removal, target downloads and move callbacks can all retain stale ownership.
- MuonCardDownloadService falls back to the phone manager when the card is absent. That is a storage-destination contract hazard, especially across process/service restart; actual command redirection has not been reproduced in this review. Keep distinct from arbitrary foreign intents: the download services are already private.

## Pinned dependency constraints

The current source version was checked in the build files, then the published [datasource](https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource/1.11.0/media3-datasource-1.11.0-sources.jar) and [exoplayer](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar) sources. These versions record this inspection only.

- SimpleCache.release clears listeners, removes stale spans, then persists the content index. Calling it on an absent card is not safe teardown merely because no reader is active.
- SimpleCache.removeSpanInternal invokes CachedContentIndex.maybeRemove before notifying span listeners. A listener cannot veto deletion. maybeRemove removes empty/unlocked content even when metadata remains; adding a metadata marker does not pin the mapping. storeIncremental then persists pending removals.
- DownloadService.onCreate caches one DownloadManagerHelper per concrete service class, calls getDownloadManager only when that helper is absent, and resumes that manager. Destroy/restart alone does not select a newly attached volume's manager.
- clearDownloadManagerHelpers clears the static map; it does not detach listeners from existing managers or stop existing service instances. Calling it while active is not an established safe swap. Its multi-user documentation is not proof of safe absent-cache disposal or per-volume semantics.

## Invariants before implementation

1. One owner per cache directory and per persistent volume index; a cache UID/download-index name, storage path and Android volume identity are related but not interchangeable. Reformatting/replacement must not inherit another card's completion.
2. Missing/uncertain storage is unavailable, not empty, successfully deleted, downloaded elsewhere or safe to rebuild. Preserve known metadata and phone playback; don't create new empty indexes/directories as proof of a returned old volume.
3. Every reader, download writer, move/copy, delete command, queued main-thread completion and service helper is bound to a shelf/volume generation. A late old-generation callback must not remove or publish another generation's bytes.
4. Preserve exact content-ID-to-key mappings and recoverable span/metadata information before any destructive stale scan/store/reopen. An app-level check before a cache call is containment only: the card can disappear during that call.
5. No automatic migration/deletion of ambiguous shared `card` records. A mounted new index is not evidence that old completed records belong to this volume. Preserve uncertain entries and expose recovery rather than guessing.

## Ordered, independently reviewable slices

| Slice | Outcome / verification | Explicit limit |
| --- | --- | --- |
| S1 Availability containment | Withdraw an unavailable card from new routing/listing/download/move/delete decisions; show unavailable status, retain phone routes; decision and stale-callback tests | Does not solve a disappearance already inside a cache operation; no release/recreate on loss |
| S2 Durable volume catalog | Model stable available/absent/unknown generations and distinct index ownership; read-only migration inventory for the shared `card` table | No migration writes until attribution is demonstrated; path/description/cache UID alone is not a portable volume identity |
| S3 Preservation feasibility | Disposable harness for mapping snapshots/recovery at actual mutation/reopen boundaries; choose a supported storage/cache ownership design or explicitly review a dependency change | Public Media3 APIs inspected so far do not establish an atomic veto/snapshot guarantee for every unexpected-removal operation; reflection/direct internal-table edits are not a production solution |
| S4 Service ownership | Exercise actual DownloadService reuse/restart and no-card fallback in a disposable service harness, then design generation-bound dispatch and coordinated quiescence | Don't clear helper maps/release managers while services or callbacks retain them; don't silently send card commands to phone |
| S5 Reattachment/migration | Same-card resumes existing identity; different/reformatted card cannot inherit it; interrupted migration restart is idempotent and preserves ambiguous data | Only after S2–S4 establish ownership and safe persistence; mounted checks alone are insufficient |
| S6 Device QA | Separately authorized disposable copied audio: graceful eject, abrupt removal during read/download/move/delete, same/different card, late insertion, process death and online/offline | User's sole media copy is never a reproduction fixture; passing JVM cases do not prove mount behavior |

S1 can be a useful containment PR with its limitations named; it must not close #179. S2/S3 may proceed in source/disposable harnesses without phone access. S4 must precede claims that live reattachment changes managers safely. A larger storage rewrite or dependency fork is not authorized by this design document alone.

## Other pending fixes and release gates

Preserve #212 played-byte budget, #234 move publication ownership, #237 worker cancellation, #240 bootstrap ownership and #248 exceptional metadata codec during integration. #230 partial target cleanup and #213 reused Tauon identity are independent data-preservation questions; a new volume namespace does not fix them. The #257 network candidate and #258 incoming-metadata guard stay open for phone QA. No Stable release/tag or experimental branch movement.

Next bounded source/harness task: S4 actual service-helper selection/no-card fallback characterization, then choose S1 containment boundaries. Production preservation remains unresolved; record unsupported assumptions as blockers rather than shipping a cleanup that loses indexes.
