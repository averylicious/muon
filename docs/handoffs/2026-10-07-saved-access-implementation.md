# Saved access implementation (#213) — 2026-10-07

Branch `codex/saved-access-oct7`, base `448a9ba641caa647fe11b9c098a2db2f923a3de0`. That base is main `718a0bb` plus the open #302, with the reviewed #362 (reader containment) and #363 (move preflight) merged in. Claude Opus 5.5 (`claude-opus-5-5`) in Claude Code, High effort selected at launch (the runtime does not report it). Role: implementation and author self-check. Independent review is pending. Nothing has been compiled or run locally; GitHub Actions is the first compile.

**Status: CHECKPOINT, implementation in progress.** This file is updated as work lands. Until the final section says otherwise, treat everything below as intent, not shipped behavior.

## Chosen contract (minimal)

1. **A saved entry is named by where its bytes are, never by the Tauon track number.**
   - The handle is `muon-saved:1:<shelf>:<source>:<request>:<key>`.
   - `shelf` is `phone` or `card`. `source` is `download` (a download index row) or `played` (a played-copy cache resource).
   - `request` and `key` are unpadded URL-safe Base64 of the row's request ID and its cache key. A played copy has an empty request.
   - Parsing is strict: version 1 only, bounded length, canonical re-encoding, a non-empty key, and a played key must carry the played prefix.
   - The handle is the saved MediaItem's media ID and its URI. It is a locator. It is not authentication and not proof of audio identity.
2. **Live items always stream.** A live `/api1/file/N` request is never redirected to a download or a played copy, online or offline, because nothing establishes that saved bytes are the live audio.
3. **Saved items play cache-only from their own shelf and key.**
   - The reader has no upstream, so a missing or partial byte fails with an `IOException` and nothing is fetched from Tauon.
   - An unavailable or absent card fails the same way.
   - Saved playback skips the played-copy step. It never asks Tauon for lyrics. Its normalization gain is remembered under its own handle, not under any live ID.
4. **Session admission:** only Muon's own UID can add items. A saved item must have a canonical handle equal to its URI and media ID, and must name an existing row whose key matches, or an existing played resource, on a present shelf. That point check runs off the main thread. External controllers keep their restricted command set.
5. **New saves are independent entries.**
   - An explicit save gets the request ID and cache key `saved/<random UUID>`, checked to be absent from the target shelf's index and cache before it is queued.
   - A new played copy gets the key `played:saved/<random UUID>`.
   - Old keys, rows and bytes are untouched and are never merged into or rebound. The UUID provides collision-free naming only, never verification.
6. **Ownership condition for destructive actions on a download entry:** the row is on an available shelf, in that shelf's own index, and its custom cache key equals its request ID. Every Muon writer keeps that invariant (legacy rows use `origin/id` for both; new rows use `saved/<uuid>` for both), and request IDs are unique within an index, so no other row on that shelf can claim the key. Anything else (a mismatched or missing key, a missing row, an unavailable card) is refused with a visible notice, and its bytes are kept.
   - A played copy's key is never claimed by an index row. Removing one runs on the played-copy worker, so it cannot interleave with a copy being written.
   - This is a structural argument over Muon's own writers. It is not an atomic census or lock, and it does not drain readers.
7. **Covers:**
   - A new save owns its cover, stored under a name derived from its own request ID. The cover is fetched once when the save is requested, from the address it was saved from.
   - Legacy live-ID covers are not shown for saved entries, not served to live views, not deleted, and not adopted.
   - Live artwork no longer falls back to saved covers.
8. **UI:**
   - Live rows no longer show download badges. Their action is "Save a copy", and none of them can remove a saved copy.
   - A separate Saved copies list shows every entry on the phone, on an available card, and every played copy. Each row is labelled "Unverified" in text, with its shelf and completeness, and has Play and Remove.
   - While Tauon is unreachable, the library shows this list.

## Changed paths

Production (first draft complete; not compiled):

- `SavedEntries.kt` (new): handle codec, inventory, coverage, new-save names, entry cover address, saved MediaItem.
- `OfflineRoute.kt`: live requests are never redirected; saved handles route to their own shelf and key.
- `OfflineDataSource.kt`: saved handles read through `Shelf.savedSource`, which has no upstream and no sink.
- `OfflineStore.kt`:
  - `Shelf.savedSource`.
  - New saves use fresh `saved/<uuid>` IDs and keys, and fetch an entry-owned cover.
  - Played copies use fresh `played:saved/<uuid>` keys plus provenance; an unfinished copy is removed.
  - `savedEntries`, `savedLibrary`, `admitsSaved`, `removeSaved`/`removeSavedNow`.
  - Legacy cover fill-in and deletion removed; the live-ID `remove(ids)`, `downloadedSongs`, `downloadedLibrary`, `downloaded`, `downloadedOn` and `playedCopy` removed.
- `DownloadArt.kt`: entry-owned covers only. The legacy per-ID fetch, lookup and delete are gone.
- `Artwork.kt`: no saved-cover fallback for live URLs; `muon-saved-art:` URLs resolve only from the entry's own file.
- `PlaybackSessionCallback.kt` and `PlaybackService.kt`: saved admission (Muon's UID, canonical handle equal to the media ID, existence checked off the main thread); no played copy of a saved item.
- `QueueModel.kt` (`restoreUrl`): a saved handle restores as itself.
- `LyricsScreen.kt`: no Tauon lyrics request for a saved item.
- `OfflineUi.kt`, `SongActions.kt`, `TrackList.kt`, `AlbumScreens.kt`: no live-row badges, live-ID removal or Cancel; the album-page button is now "Save copies" and the song action is "Save a copy".
- `SavedScreen.kt` (new), `MuonApp.kt`, `LibraryModel.kt`, `SettingsScreen.kt`: the Saved copies list (offline library, and from Settings), Play, and Remove with confirmation.
- `CardAvailability.kt`, `OfflineDownloads.kt`: removed dead live-ID helpers (`servingShelf`, `removalPlan`, `downloadForStream`, `downloadProgress`, `OFFLINE_LIBRARY`).

Tests: in progress.

## Remaining checks and limits

### Claude completion pass (Claude Opus 5.5, High; not compiled, no test run)

**Contract 6 is superseded.** The `requestId == key` rule was replaced after review:

- **`soleOwner` census.** A download row's bytes may be deleted only when, in that shelf's index (all states), the row's key is its own request ID, is not a played key, and no other row names it.
- **Why the census holds until Media3 removes the bytes:** there are two production index writers, both verified by grep for `sendAddDownload`.
  - `add` uses a fresh `saved/<uuid>` that is absent from every row's ID and key in both indexes and from both caches (`takenNames`).
  - A move adds only rows that pass `movable`: the source row is sole owner, and the target holds no row naming that key except the same ID with the same key. Pinned `DownloadRequest.copyWithMergedRequest` takes the new request's key, so other cases are refused.
- **`PlayedClaims`.** The played-copy keys any phone index row names are read once at startup. They are never evicted, cleared, removed or cleaned up. Before that read finishes, nothing played is removed and no played copy is made.
- **Guarded callers:**
  - `removeSavedNow`, played and download;
  - `removeAllNow`, per-row census instead of Media3's remove-all;
  - the leftover removal after a move (`removeLeftoverNow`, census on the saver thread);
  - the move census, once per batch;
  - `PlayedSongEvictor`, `clearPlayed`, and the cleanup of an unfinished played copy;
  - entry covers: only `saved/` IDs, and only once no row of that ID is left on either shelf.

**Findings 1, 2 and 9 fixed in source:**

1. Live songs stream through `Shelf.stream`, a plain OkHttp source with no cache read or write. The test fixtures now replace `stream` in place of the old `source`.
2. `savedLibrary` no longer needs an origin. `showOffline` takes a nullable server, and `connected` includes offline. ConnectScreen counts every complete saved copy, played-only and unknown-origin included, off the main thread.
3. Finding 9: `fetchEntry` uses `Transport.metadataClient` (whole-call deadline) with the 4 MiB cap. `forEntry` refuses an empty or over-cap file before reading it.

**Tests (source-only; GitHub Actions is the first compile and run):**

- New `SavedOwnershipCensusTest`:
  - aliased, malformed, keyless and played-key rows are never sole owners;
  - a move refuses aliases and rebinding;
  - inventory removable flags;
  - the real evictor never evicts a claimed key, or anything before claims are known.
- Updated:
  - `RetainedIdentityCharacterizationTest`: live B streams over loopback even with a resource keyed by the exact live URI; saved A reads only by its handle with no request; bundle and Undo restore the exact handle.
  - `CardAvailabilityRouteTest`, `CardIndexCharacterizationTest`: missing card bytes fail cache-only; removal of an unavailable card's copy is refused.
  - `OfflineReaderContainmentTest`, `ReaderRouteCompositionTest`, `SourceCloseControlTest`: saved handles read through `savedSource`.
  - `PlayedCopyCharacterizationTest`: new played keys and provenance.
  - `SavedOwnershipControlTest`: the legacy-cover API is gone; the legacy file stays intact and is never served.
  - `DownloadArtTest`, `OfflineDownloadsTest`, `CardAvailabilityTest`: removed helpers dropped, replaced by real-index coverage.

**Pinned-source checks** (`/tmp/muon-oct7-saved-access-review/pinned-source`):

- `ProgressiveDownloader.remove` deletes by the key from the cache-key factory, i.e. the request's custom key.
- `copyWithMergedRequest` takes the new request's custom key.
- `Util.inferContentType` returns OTHER for an opaque `muon-saved:` URI, so it plays as progressive.
- `SimpleCache.getKeys` and `getCachedSpans` return copies.
- `CacheDataSource` with no upstream uses `PlaceholderDataSource`.
- Media3 1.11 has no ignore-cache-always flag, hence the plain stream factory.
- Not checked: `MediaSession.Callback` acceptance of an asynchronously completed `onAddMediaItems` future (session sources not extracted).

**Open findings from the coordinator review** (`docs/audits/2026-10-07-saved-draft-review.md`), not resolved here and still blocking a #213 claim:

- **4:** persisted metadata size in saved MediaItems.
- **5:** notification loader cannot read `muon-saved-art:`, so there is no cover in the notification.
- **6:** admission does not check card availability; the reader does.
- **7:** `refreshSaved` cancellation, and unbounded scans (#253).
- **8:** listener leftover removal is triggered by a same-ID completion. Removal is now census-gated per shelf, but two unrelated legacy rows with the same ID on phone and card can still trigger it when the other one completes; tracked, byte-verified move provenance is not implemented.

**Other limits:**

- Rows already `REMOVING` from an older app version are still processed by Media3 at startup.
- Played copies made before this version are listed but never resumed.
- Removing a copy while it is playing unlinks files under the open reader.
- No device, UI or Compose verification. The checkpoint describes source only.

**Manual QA (pending user):**

- live play while saved copies exist;
- Saved copies list, play and remove, including the refusal notices;
- Disconnect, then Open saved copies with only played copies;
- card out and in;
- move with mixed legacy rows;
- Clear the played cache;
- Undo of a saved queue item.

## Coordinator review checkpoint (before first compile)

The first allocated Claude run ended at its configured turn cap, not quota exhaustion (latest runtime event 48% five-hour /43% weekly used). Its production draft is preserved; tests and compile remain pending. GPT-6/Codex review identified a blocking gap in the proposed destructive ownership rule above: `requestId == customCacheKey` does not exclude another legacy/unknown row whose different request ID uses that same custom key. Played-prefix resources may also be claimed by such rows. Those bytes must remain preserved through removal, move completion, automatic played eviction/clear and listener cleanup. Merely restricting which selected row is removable does not protect an alias from another owner's deletion. This review supersedes the proposed rule until corrected and tested against actual cache/index fixtures. No CI, device, independent whole-change review or #213 completion claimed.

## Second source checkpoint

The second allocated run ended at its configured 45-turn cap, not quota exhaustion (82% five-hour /47% weekly used). It adds `soleOwner`/`movable` full-row checks, startup `PlayedClaims` protection for indexed played keys, guards around explicit/bulk removal and move/listener paths, and updates existing route/identity/cover/reader fixtures. These are uncompiled and require review of all production callers and new actual-mutation regression coverage. The original contract item 6 is superseded by these draft checks; it must not be treated as a final proof.

Additional GPT-6 draft findings and concrete next checks are preserved in `docs/audits/2026-10-07-saved-draft-review.md`. Live cache bypass, address-independent offline access, cross-shelf completion removal, safe persisted-metadata presentation and notification artwork need particular attention. No #213 closure, merge eligibility or manual QA claim.
