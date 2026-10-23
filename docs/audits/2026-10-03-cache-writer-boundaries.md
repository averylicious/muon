# #179 S3 cache writer boundaries — 2026-10-03

Inspected main `8dea8cabf7089614acfaa6e07e7d4e6336f6b80d`, branch `codex/cache-writer-boundaries`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author investigation and tests, **not independent review**. Test-only and report-only: no production change, device, user data, network or build. #179 is open.

**Question:** which threads and callbacks can change a shelf's cache or download index, and what would a supported quiescence point (a moment when none of them is changing anything) require before a snapshot or catalog can be trusted?

## Pinned sources

Media3 1.11.0, the version in `app/build.gradle.kts`. The jars were read from `muon-volume-catalog/build/sources/` with Python's `zipfile`, without modifying them:

| Jar | sha256 |
| --- | --- |
| `media3-datasource` sources | `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| `media3-exoplayer` sources | `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |
| `media3-database` sources | `1baca4ff0a32e76d08b92ed5d33e0278aa34a9e9c537edbc2fd39573e8ec46a3` |

Line numbers count `\n`-separated lines. Python's `splitlines()` also breaks on other characters and can be off by one in these files.

## Main vs open PRs

Main `8dea8ca` has **none** of these:
- #264's card availability (`CardAvailability.kt`, `canMove`, `deliverMovedCopy`);
- #234's move ownership (`DownloadMoveOwnership`);
- #281's byte check (`sameBytes`).

They are open, and together in #289. Everything below describes main unless marked otherwise.

## Mutation owners on main

| Owner (thread) | What it changes | Symbol |
| --- | --- | --- |
| Cache init (`ExoPlayer:SimpleCacheInit`) | Creates the folder and UID, loads the index, deletes unrecognized files | `SimpleCache` constructor and `initialize` (#179 S2 report) |
| Any cache caller | Missing or short span files are dropped from the index on lookup, file start or release | `SimpleCache.getSpan` 672-687 (`removeStaleSpans` at 682); `startFile` 380-385; `release` 262-276 (`removeStaleSpans` 267, `contentIndex.store` 269); `removeStaleSpans` 724-736 |
| Playback loading thread | Reads through `OfflineDataSource`→`Shelf.source`; lookups can drop stale spans; the phone's `PlayedSongEvictor` gets touches | `OfflineStore.playbackSource`/`route` 177-185, `routeOfflineRequest`, `downloadedOn` 168 |
| `copier` executor (phone cache only) | Writes played copies and their metadata, resizes, clears `played:` keys | `copyPlayed` 195-211, `setCacheLimit` 214-219, `clearPlayed` 222-225; `PlayedSongEvictor.evict` → `removeResource` (`PlayedCache.kt` 86-90) |
| `mover` executor | `CacheWriter` into the other shelf, then queues Add on the main thread | `move` 314-331, `copy` 334-343 |
| Each `DownloadManager`'s internal thread and `Task` threads | Download (`ProgressiveDownloader.download` 146-166, via `CacheWriter`), remove (`remove` 208-209 → `removeResource`), index updates | `DownloadManager` (thread created at 251) |
| Main thread `Handler` | Queued move Adds and progress; `record`/`removed`; `watch` sends leftover removes on completion | `move` 323-329; `watch` 141-157 (removes at 147-148, the bootstrap read at 152-156 posts records) |
| Download services | `getDownloadManager` binds each service class to a manager, cached in a static helper map; the card service falls back to the phone manager | `MuonDownloadService.kt` 24, 43; `DownloadService.onCreate` 586-609, `clearDownloadManagerHelpers` 581-582 |

The phone's database index serves both shelves (`ExoPlayerDownloads`, the `card` index, and both caches' UID tables; S2 report).

## Source-backed limits on a quiescence point

1. **Pausing or idling a manager stops only its own tasks.**
   - `pauseDownloads` (442-444) moves downloads to queued, and `isIdle` (294-296) reports only that manager's own tasks and pending messages.
   - Neither affects the `mover` or `copier` executors, playback reads and their stale-span removal, `watch`'s bootstrap executor, already queued main-thread messages, or the other shelf's manager.
2. **`DownloadManager.release` does not join running downloads.** The internal `release` (940-955) calls `Task.cancel(true)` (1316-1329: `downloader.cancel()` plus `interrupt()`) and quits its thread, without waiting for the task threads to finish. The public `release` (504-530) waits only for that internal step. A cancelled downloader may still be inside a cache call after `release` returns, and DownloadService's static helper map can still hold the old manager (S4).
3. **Each cache call is atomic on its own, a sequence of calls is not.** Every `SimpleCache` call is `synchronized`, but a catalog built from several calls (`getKeys`, `getCachedSpans`, `getContentMetadata`, file copies) can interleave with other writers between them. Even a stale-span lookup during capture is itself a mutation.
4. **Metadata changes are not announced.**
   - `applyContentMetadataMutations` (500-511) updates and stores the index with no listener call.
   - `Cache.Listener` has only `onSpanAdded`, `onSpanRemoved` and `onSpanTouched` (`Cache.java` 47-77).
   - **Control 1 below.**
5. **A writer admitted earlier is invisible until it commits.**
   - A held hole span (`startReadWrite` plus `startFile`, file written) doesn't appear in `getCachedSpans`.
   - `commitFile` (398-429) adds it later via `addSpan` → `notifySpanAdded` (694-697, 748-756).
   - So stopping new writers and then taking one snapshot is not a barrier: writers already admitted must be drained or awaited first. **Control 2 below.**
6. **`release` drops listeners first.** `release` clears listeners (line 266) before `removeStaleSpans` and store, so a listener-based catalog can't observe the release's own removals.

## Controls (`CacheSnapshotCompletenessTest`; CI pending, first compile)

Both are deterministic and run on a single thread. They use a real `SimpleCache`, native SQLite, disposable folders, public APIs only and a recording `Cache.Listener`. There's no mock, sleep, reflection or private table. They don't repeat #285's touch tests or #287's sidecar tests.

1. **`metadataOnlyChangeReachesNoSpanListenerWhileFreshReadsSeeIt`:** a healthy committed key with a content length and `custom_tag`. A snapshot holds both the returned metadata object and copied entries. After a metadata-only mutation, the expected results are:
   - no listener event;
   - a fresh `getContentMetadata` shows the new value;
   - the retained object and the copy both still show the old value;
   - the bytes, content length and `isCached` are unchanged.

   This qualifies the completeness of a listener-backed sidecar; it doesn't fix anything. Full enumeration relies on the pinned `DefaultContentMetadata` cast (constraint from #287).
2. **`anAdmittedUncommittedWriteIsMissedByACaptureAndLandsAfterIt`:**
   - **Setup:** the first half is committed. A writer holding the second half's hole has written its file but not committed it, and a non-blocking lock attempt on that range returns null.
   - **Capture:** taken then, it sees one span and 128 bytes, with no events.
   - **Expected after commit:** exactly one `added:128:128` event, and the key fully cached. A fresh recapture finds both spans and the exact 256 bytes.

## Ownership barriers a production quiescence point would need (not designed or implemented)

- **Admission:** one owner that refuses new download starts, moves, played copies, clears and resizes for a shelf generation. On main this is spread across `OfflineStore.add`, `move`, `copyPlayed`/`setCacheLimit`/`clearPlayed`, and service intents.
- **Drain:**
  - wait for the `mover` and `copier` executors to finish what they've already accepted;
  - make each shelf's managers report no active tasks, with the task threads actually stopped;
  - flush or invalidate queued main-thread completions (#234's batch ownership does this for move Adds; open);
  - quiesce or redirect playback reads.
- **No mutating lookups during capture:** reads used for capture shouldn't trigger stale-span removal on an absent or changing card. On main, any lookup can.
- **Service ownership:** the static helper map and the card service's phone fallback (S4).
- **Generation binding:** every completion and callback carries its shelf generation (S1/S2; #264 is open containment only).

## Next bounded task

A source-only characterization of `DownloadManager.release` and pause: a disposable fixture with a deterministic blocking `Downloader` showing whether a task thread is still running after `release()` or `pauseDownloads()` returns. That needs a test `DownloaderFactory` only; no production change and no sleep.

## Unresolved

#179 (loss during I/O, identity, service swap, recovery and migration), #213, #230 and #253 remain open. These controls are evidence for a design, not a barrier implementation. CI is the first compile and run of `CacheSnapshotCompletenessTest`.

## Independent coordinator review

GPT-6 / Codex desktop, effort not reported, independently checked the controls, actual main call sites and pinned datasource/exoplayer source hashes. Verified metadata mutation has no listener notification and retained metadata is replaced immutably; unfinished hole writes appear only at commit. DownloadManager's own Task.cancel comment explicitly permits ongoing cancellation after manager release; this is not proof of a live Muon downloader still writing after release. Public release waits for its internal handler and clears its own application messages, so the report's queued-callback concern is about independent OfflineStore handlers/workers, not claiming DownloadManager leaves every own callback intact.

Coordinator added finally-based hole-lock cleanup to the second fixture and removed a redundant arithmetic assertion; those own edits are self-checks. The fixture remains single-threaded interleaving evidence, not a real concurrent worker shutdown, absent-card, process-death or power-loss test. CI is the first compile/execution; final-head results belong on the PR. No production barrier, recovery design or fix was implemented.
