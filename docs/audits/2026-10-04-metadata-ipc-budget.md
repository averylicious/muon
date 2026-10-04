# #253 single-song metadata across Media3 IPC — 2026-10-04

Inspected main `6759b01288973a1c046bb52aab69dd786bf13809`, branch `codex/metadata-ipc-budget`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading by the author, **not independent review**. Report only: no app or test change, no device, no user data. #253 remains open.

**Question:** what is the largest per-song metadata representation Muon passes through Media3 session and download-service IPC? Does pinned Media3 split lists but leave individual items unchecked?

## Sources

Media3 1.11.0 (`app/build.gradle.kts` 82-84), read with Python's `zipfile` and never executed:

| Source | SHA256 |
| --- | --- |
| `media3-session-1.11.0-sources.jar` (Google Maven, fetched read-only) | `9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715` |
| `media3-common-1.11.0-sources.jar` (Google Maven, fetched read-only) | `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e` |
| `exoplayer.jar` sources (local) | `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |

**AOSP** `platform/frameworks/base` at `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`, fetched read-only:
- `core/java/android/text/TextUtils.java`, SHA256 `fd53998a45330fa0dd1434eb3ae8c081a7a75c8698e57a04bf28663bcb16c354`
- `core/java/android/os/Parcel.java`, SHA256 `7e2505541149b81276e601f738b3d833f85ddbbdd595f7c2281c1baf98c77393`

Neither is verified as the phones' builds.

**Robolectric 4.16.1** `shadows-framework` sources, SHA256 `977c225559953d772cff539dff0ccf4bb757f297e6a18620cd609ba077108409`.

## Muon's per-song representation on main

- **`encodeSong`** (`OfflineDownloads.kt` 91-92): the version tag and seven fields joined by NUL, converted with `toByteArray()`. Android's default charset is UTF-8.
- **Two consumers:**
  - `TauonTrack.mediaItem` (`PlaybackService.kt` 110-120) puts title, artist, album and album artist into `MediaMetadata` (artist and album artist via `displayCredits`), **and** the whole `encodeSong` record into the metadata extras (`SONG_EXTRA`, 119). Each media item therefore carries every text field **twice**.
  - `OfflineStore.add` (233) puts the same record into `DownloadRequest.data`, which `sendAddDownload` (234) sends in an Intent.
- **Bound on main:** none per song. The only cap is the 16 MiB whole-playlist response (`TauonApi.kt` 32). A single field can therefore reach the megabyte range.
- **Pending #302:** adds `requireTrackMetadataBudget` (16 KiB for the encoded record, and separately for the displayed UTF-8 fields) at JSON decode (#302 `TauonApi.kt` 64). With it, each media item stays at about **2 × 16 KiB plus overhead**. #302 is open pending user QA, so main is unbounded.

## Media3 transport (pinned sources)

**Lists split, items don't:**
- **Item lists from a controller:** `setMediaItems` sends them through `BundleListRetriever` (`MediaControllerImplBase` 903-913, and further uses at 933/958/1041/1064/1446). Each item is `toBundleIncludeLocalConfiguration`, so the metadata and its extras are included (`MediaItem` 2399-2420; `MediaMetadata` 1522-1523).
- **Timeline windows:** sent the same way (`Timeline` 1493-1494).
- **Splitting rule:** `BundleListRetriever.onTransact` (78-86) keeps writing bundles while `reply.dataSize() < C.SUGGESTED_MAX_IPC_SIZE`. The check runs **before** each write. The list is split across transactions, but **one item is always written whole**, whatever its size.
- **What the suggested size is:** `C.SUGGESTED_MAX_IPC_SIZE` (116-117) is `IBinder.getSuggestedMaxIpcSizeBytes()` on API 30+, otherwise 64 KiB. It is a suggestion, not an enforced limit.

**Not split at all:**
- **`PlayerInfo`:** `toBundleForRemoteProcess` sends the session, old and new position infos (951-959), each of which can include the current `MediaItem` (`Player.PositionInfo.toBundle` 482-483). It also sends the player's `MediaMetadata` (1028).
- **Copies per transaction:** the current song's record can appear up to four times in one controller transaction, subject to command filtering (`MediaSessionStub` 2672-2704).
- **Legacy and platform sessions:**
  - `truncateListBySize`, with a 256 KiB limit (`MediaUtils` 44, 71-90), drops whole items from **browse** lists (`MediaLibraryServiceLegacyStub` 458). It never shrinks an item.
  - The legacy queue relies on the framework's `ParceledListSlice` (`MediaSessionLegacyStub` 1683-1685).
  - `setMetadata` (1241-1243) sends one item.
- **Downloads:** `DownloadRequest.writeToParcel` (`DownloadRequest.java` 366-379) writes `data` as one byte array inside the Intent.

**Platform encoding:** `MediaMetadata` puts text as CharSequences (1411-1420). AOSP `TextUtils.writeToParcel` writes those with `writeString8`, which is UTF-8 (843-848). `Parcel.writeString` uses UTF-16 (1335-1336). For ASCII, each copy is about one byte per character; for CJK, about three.

## In-process versus cross-process

`PlaybackService` has no `android:process`, so Muon's own UI controller shares its process. `BundleListRetriever.getList` returns an in-process list directly (96-99). Whether every other Media3 call on that connection also skips parcelling wasn't traced. **The cross-process receivers are:**
- external controllers, such as the system media UI, Bluetooth and Auto;
- the legacy platform session;
- the start-service request from `sendAddDownload`.

## Verified versus hypothesis

- **Verified in source:**
  - Lists are split, but no item size is checked, on the inspected session paths.
  - `PlayerInfo` carries current-item copies in a single bundle.
  - Muon duplicates each field (metadata text plus extras record).
  - Main has no per-song bound.
- **Hypotheses, not shown:**
  - A specific song size causes `TransactionTooLargeException`, a dropped update or a crash.
  - Real binder buffer usage. The kernel and shared buffer, concurrent transactions, oneway handling and vendor builds all matter, and none was measured.

## Why there is no Parcel test

A Robolectric Parcel byte count would **not** measure Android's representation. `ShadowParcel` sizes every string as UTF-16 (`writeString` 748-754), and maps `nativeWriteString8` to that same path (1153-1155). Real `TextUtils` writes UTF-8. A JVM test would therefore misstate text-field growth, especially multibyte against ASCII, so no test was added.

## Smallest next change, and QA

- **The per-song bound already exists as #302's metadata budget:** it refuses oversized tags at decode, doesn't truncate, and leaves retained records alone. That slice should land through #302's user QA rather than as a new cap here.
- **After #302:** an emulator or instrumented Parcel measurement (not Robolectric) of one bounded `MediaItem` and `PlayerInfo` could confirm the roughly 4 × 32 KiB worst case. The test should cover ASCII and CJK, and must not induce failures.
- **Compatibility:** records already stored in download requests and played-copy metadata are read by `decodeSong` and must stay readable. No migration, rewriting or deletion.
- **User QA:** the #302 metadata and artwork identity checks already listed. No phone work was done here.

**Checks run locally:** `git diff --check` and the CI prose check. No Gradle build.
