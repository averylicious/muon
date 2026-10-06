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

(Filled in at the end.)

## Coordinator review checkpoint (before first compile)

The first allocated Claude run ended at its configured turn cap, not quota exhaustion (latest runtime event 48% five-hour /43% weekly used). Its production draft is preserved; tests and compile remain pending. GPT-6/Codex review identified a blocking gap in the proposed destructive ownership rule above: `requestId == customCacheKey` does not exclude another legacy/unknown row whose different request ID uses that same custom key. Played-prefix resources may also be claimed by such rows. Those bytes must remain preserved through removal, move completion, automatic played eviction/clear and listener cleanup. Merely restricting which selected row is removable does not protect an alias from another owner's deletion. This review supersedes the proposed rule until corrected and tested against actual cache/index fixtures. No CI, device, independent whole-change review or #213 completion claimed.
