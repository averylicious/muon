# #253 resource budget inventory — 2026-10-02

Branch `codex/resource-budget-inventory`, worktree `~/.codex/worktrees/muon-resource-inventory`, from main `3a47d52debb41ab63c5a87832249094d1d58c89a`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as assigned (the runtime does not report effort). Author investigation and fixture only: **no independent review**. Nothing compiled or run locally. CI is the first compile and run of the fixture. No phone, push, PR or merge. Astra owns the checkpoint, `STATE.md` and the runbook.

Scope: what main retains for one loaded library, and which single-item and aggregate IPC routes carry library metadata. No app code, limits or refactor. The issue body of #253 could not be read (`gh issue view` needed approval). The scope comes from the [source audit](../handoffs/2026-10-01-source-audit.md) and the [Oct 1 afternoon checkpoint](../handoffs/2026-10-01-audit-afternoon.md).

**Evidence levels:**
- **Verified (fixture):** asserted by `LibraryRetentionCharacterizationTest`. It's pending its first CI run, so treat it as verified only once CI passes.
- **Source:** read in main's code or in the pinned Media3 1.11.0 sources jars on Google's Maven ([session](https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.11.0/media3-session-1.11.0-sources.jar), [common](https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.0/media3-common-1.11.0-sources.jar)).
- **Hypothesis:** not checked in this slice.

## Bounds main actually has

- **Per response:** `TauonApi.json` refuses a body over 16 MiB (`TauonApi.kt:32`).
- **Error text:** limited to 512 characters (`LibraryModel.kt:124`, #272).
- **Artwork:** limited to 4 MiB per download, and by the memory and disk caches.

There is **no** per-field, per-record, per-playlist, per-load or per-queue bound on library text. Grepping main's sources finds no 16 KiB guard. #258's incoming 16 KiB record/display-text guard is **open and not on main**: the afternoon checkpoint lists it under #257. I couldn't recheck it live because `gh pr view` needed approval. This report doesn't duplicate or assume it.

## Retained representations of one library

"Occurrence" means one song's entry in one playlist response. "Unique" means one per id after `distinctBy { it.id }`.

| # | Representation | Where | Grows with | Lifetime | Evidence |
| --- | --- | --- | --- | --- | --- |
| R1 | One `TauonTrack` per occurrence, each with its own tag `String`s | `TauonApi.tracks` (`TauonApi.kt:52-63`) → `LibraryModel.tracksByPlaylist` (`LibraryModel.kt:18,57`) | **Occurrences**: a song in k playlists is parsed and kept k times, including repeats inside one playlist | Until the next successful load or disconnect | Verified: 4 equal but distinct objects and title strings for one song across 3 playlists |
| R2 | Previous lists kept for failed playlists | `combineLoad(previous)` (`LibraryLoad.kt:17-26`), `LibraryModel.kt:53` | The failed playlists' sizes; one snapshot mixes generations | As R1 | Verified: the failed playlist's old list object is kept beside newly parsed lists |
| R3 | `allTracks`: flattened, then distinct by id | `LibraryModel.kt:25` (a getter, recomputed on each call) | Builds a transient flatten list over all occurrences and a distinct list over unique songs per call. Called for `LibrarySnapshot` (`MuonApp.kt:125`), Settings (`SettingsScreen.kt:41`) and progress (`LibraryModel.kt:60`) | The snapshot list lives as long as the snapshot; the rest is transient | Source. The fixture mirrors the expression: the first occurrence's object is kept |
| R4 | Album and artist groups: references plus key strings | `groupAlbums` / `groupArtists` (`LibraryGroups.kt:14-49`), held as `AlbumGroups` / `ArtistGroups` (`MuonApp.kt:152-161`) | Unique songs (references). Album keys copy the lowercased album and album artist, once per album; artist keys copy each credit | Per snapshot | Verified: one 12,001-character album yields a key longer than the album, containing its lowercased copy |
| R5 | Sorted songs, albums and artists; search results | `MuonApp.kt:137-169`, `TrackSearch.kt` | Unique songs, as references | Per snapshot and order | Source |
| R6 | Artwork identities: URL → `title\0artist\0album` | `artworkIdentities` (`ArtworkCache.kt:29-36`), published process-wide by `ArtworkIdentities.publish` (`MuonApp.kt:132`) | **Unique songs × (title + artist + album)**: a full extra copy of three tags per song | Until the next publish | Verified: 4 songs give 4 identities, each ending in the long album |
| R7 | Queue: one `MediaItem` per playable song, each with `MediaMetadata` and the **whole encoded record** in its extras (`SONG_EXTRA`) | `TauonTrack.mediaItem` (`PlaybackService.kt:110-120`), `startQueue` / `playAll` (`MuonApp.kt:263-280`) | **Queue length × record size**. Songs and Search queue the whole library (`MuonApp.kt:528,599`) | Until the queue is replaced | Verified: a 10,000-song queue holds 10,000 records that decode to the parsed tracks |
| R8 | In-process controller → session copies | `MediaControllerImplBase` builds `BundleListRetriever(toBundleList(… toBundleIncludeLocalConfiguration …))` (≈ lines 908-962). `BundleListRetriever.getList` returns that same list in process. `MediaSessionStub.setMediaItemsWithStartIndex` (≈ line 1328-1352) maps `MediaItem.fromBundle` | Queue length: one Bundle per item, then one more `MediaItem` per item in the session/player | During the call; then the player's timeline | Source. The services run in the app's process (no `android:process` in the manifest). Whether the extras' byte arrays are shared or copied in process: hypothesis |
| R9 | Offline records | `OfflineStore.add` puts a full record into each `DownloadRequest` (`OfflineStore.kt:228-235`). `downloadedSongs` decodes every completed record (`OfflineStore.kt:242-264`). Played copies keep the record in cache metadata (`SONG_METADATA`) | Downloads and played copies | Records persist on disk; decoded lists live per offline library | Source. That `SimpleCache`'s in-memory content index holds every key's metadata is a hypothesis, to check in the pinned `media3-datasource` sources |

Not inventoried: lyrics (one 16 MiB-capped string per open lyrics view), playlist names (R1-like, one per playlist), and Compose's own copies.

## IPC routes

### Single item

These are already characterized by the [metadata payload report](2026-10-01-metadata-payload.md) (#255). `BundleListRetriever.onTransact` checks reply size only *between* items, so one oversized item is written whole. The `DownloadService` add Intent writes the record as one byte array. Both were rechecked in the pinned common sources this slice.

**New this slice (source):** the R7 record also reaches **remote** controllers and the **framework** session. These rows were read in source but not run.

| Route | Path in the pinned sources | Status |
| --- | --- | --- |
| Remote controllers | `PlaybackSessionCallback` gives trusted system controllers `COMMAND_GET_TIMELINE` (`PlaybackSessionCallback.kt:25-26,76`). For a non-`MediaControllerStub` controller, `MediaSessionStub.onPlayerInfoChanged` sends `toBundleForRemoteProcess` (≈ line 2685-2691). `PlayerInfo` puts `timeline.toBundle(...)` (≈ line 974). `Timeline.toBundle` sends windows through a `BundleListRetriever` (≈ line 1493), and each window carries `mediaItem.toBundle` (≈ line 466) | **A whole-library queue is offered to each trusted remote controller as N windows, each with its record.** Chunking is per window; one oversized window is not split |
| Framework session queue | On a timeline change, `MediaSessionLegacyStub.updateQueue` (≈ line 1635-1686) converts the whole timeline into `QueueItem`s. `LegacyConversions.convertToMediaDescriptionCompat` copies `metadata.extras` into each description (≈ line 805-807, 871). The result goes to framework `MediaSession.setQueue` | Happens when `isQueueEnabled()` (≈ line 1262): `COMMAND_GET_TIMELINE` is available both to the legacy stub and from the player. Media3's comment says the framework uses `ParceledListSlice`. Whether this queue is enabled at runtime for Muon, and what `system_server` retains, are **hypotheses** (framework source not checked) |

### Aggregate

- **Queue:** a queue costs at least N × record across R7, R8 and the remote/framework routes, whatever the per-item bound.
  - #258's 16 KiB per record (if adopted) **does not bound this**. Arithmetic only, not measured: 10,000 maximal records would be about 160 MiB of record bytes before Bundle/Parcel overhead.
  - Ordinary tags are small. The fixture prints the real total for 10,000 short-tag songs in CI.
- **Downloads:** Download all sends one service Intent per playable track. That's many transactions, each bounded by one record.

## What the fixture shows (CI pending)

`app/src/test/java/dev/avery/muon/LibraryRetentionCharacterizationTest.kt` is Robolectric SDK 34. It drives a real loopback HTTP server through the actual `TauonApi` and the platform `org.json`, then `combineLoad`, `groupAlbums`, `groupArtists`, `artworkIdentities`, `mediaItem`, `encodeSong` and `decodeSong`. It mirrors two expressions that live in the view model and composable: `allTracks`, and the queue mapping.

1. **Wide bounded response:** 10,000 tracks, well under 16 MiB, kept whole and in order. A whole-library queue holds 10,000 records, each equal to its parsed track. It prints `responseBytes` and `queueRecordBytes`.
2. **Repeated songs:** one song four times across three playlists, including twice in one playlist. All occurrences are kept as distinct objects, so **duplicate occurrences are preserved**. Projection keeps the first occurrence's object, and grouping uses that same object. A refresh where playlist 2 returns HTTP 503 keeps its previous list object beside newly parsed lists (R2).
3. **Long accepted tag:** a 12,001-character album, on 4 songs listed across 2 playlists (6 occurrences). Main accepts it, and it's meant to stay under #258's proposed per-record guard; that guard's final rule isn't checked. The album appears as:
   - 6 separate strings;
   - a lowercased copy in the album key;
   - 4 artwork identities;
   - 4 encoded records once queued.

It does **not** show heap size, GC behaviour, time, a `TransactionTooLargeException`, remote-controller or framework behaviour, any phone result, or practical likelihood. It prints `MUON_RESOURCE_FIXTURE` lines to the CI test log, so the counts can be recorded on the PR.

## Proposed next budgets (for approval, not approved caps)

No numbers are proposed here. They should come from measured representation sizes for legitimate libraries. Proposed **dimensions** and **preservation behaviour**:

1. **Per record:** #258 (open) is the candidate. Decide it first, because the aggregate budgets build on it. Keep the rule on encoded record bytes, which is what crosses IPC, rather than per field.
2. **Per load:** the sum of encoded record bytes over **occurrences**, since retention scales with occurrences (R1) and not with unique songs.
   - On exceeding it: keep the previous library and the existing partial-load semantics (`combineLoad`), show a clear message, and never truncate tags or drop or de-duplicate occurrences.
   - A separate non-limit option for approval: share one `TauonTrack` per id inside a load while keeping every occurrence in its list. That keeps duplicates and cuts R1 toward unique songs. It's a refactor, so it needs its own slice.
3. **Per queue:** the sum of record bytes per `setMediaItems`.
   - Prefer the #255 direction: a compact reference in `SONG_EXTRA` instead of the full record, so R7, R8 and the remote/framework routes scale with ids rather than text.
   - Preserve played-copy metadata and offline listing, and keep Songs/Search playing on through the whole library (`MuonApp.kt:594-599`). Don't shorten the queue silently.
4. **Derived copies (R4, R6):** count them in the per-load figure. Don't add separate caps; R6 might key on a digest in a later design.

Compatibility floor: whatever is chosen must accept large legitimate libraries with ordinary tags. Measure 1k, 10k and 50k songs, and many playlists repeating the same songs, before setting a number.

## Proposed next slice

Measurement only, no limits:
1. Add 50k-song and many-playlist variants to the fixture's printout, sized from ordinary tags.
2. Check `SimpleCache`/`CachedContentIndex` metadata retention and `DownloadManager`'s in-memory download list in the pinned `media3-datasource` / `media3-exoplayer` sources (R9).
3. Check framework `MediaSession.setQueue` retention in AOSP, or keep it labelled unverified.
4. Then write a per-load/per-queue budget design for user approval, after #258 is decided.

## Permission failures and local state

- **Needed approval, not run:** `gh issue view 253`, `gh pr view 258`, `git branch -r`, `git check-ignore` and `unzip`. Listing `/tmp` was blocked.
- **Pinned sources:** downloaded the Media3 session and common sources jars to `build/media3-sources/` (git-ignored by `**/build/`), and read them with Python's `zipfile`. Left in place, since no deletion was permitted.
- **Usage telemetry:** none was available to this session, and OAuth/usage credentials weren't accessed.
