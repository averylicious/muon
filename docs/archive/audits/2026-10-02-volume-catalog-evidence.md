# #179 S2 volume catalog source evidence — 2026-10-02

Inspected main: `3a47d52debb41ab63c5a87832249094d1d58c89a`, on branch `codex/volume-catalog-evidence`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as assigned (the runtime does not report effort). Original author source investigation; independent coordinator qualifications appear below.

**Not done:** no production code, fixture, database write, migration, deletion, device access, build, push or PR.

**Scope:** S2 of the [storage preservation design](2026-10-01-storage-preservation-design.md). Which public Android APIs (minSdk 28, compileSdk 37.0) can say whether a card is available, absent or unknown, and how their answers relate to Muon's shared `card` download index and the card's `SimpleCache` UID.

**Already established, not repeated here as new findings:**
- the shared `card` index across different card directories ([card-index characterization](2026-10-01-card-index.md), #256);
- cache index loss ([cache characterization](2026-09-29-cache-characterization.md), #192);
- S1 containment (#264, open, not on main).

## Sources inspected

| Source | Exact artifact | Checksum |
| --- | --- | --- |
| Media3 1.11.0 (the version in `app/build.gradle.kts`) | [media3-datasource sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource/1.11.0/media3-datasource-1.11.0-sources.jar) | sha256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| | [media3-exoplayer sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar) | sha256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |
| | [media3-database sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-database/1.11.0/media3-database-1.11.0-sources.jar) | sha256 `1baca4ff0a32e76d08b92ed5d33e0278aa34a9e9c537edbc2fd39573e8ec46a3` |
| Android SDK sources, compileSdk 37 minor 0 | [source-37.0_r02.zip](https://dl.google.com/android/repository/source-37.0_r02.zip) | sha1 `759b8d2ef7e3ee01bb1ddc7d524cb26ec90dc928` (matches Google's `repository2-3.xml`), sha256 `a853f452b8ba94933eb56ed4e0f622991f9d6de6e0ac19923aa47414d79b9129` |
| Android SDK sources, minSdk 28 | [sources-28_r01.zip](https://dl.google.com/android/repository/sources-28_r01.zip) | sha1 `5610e0c24235ee3fa343c899ddd551be30315255` (matches manifest), sha256 `7c05aecdc5b56a8d30837ff80885e95736e455aef4d442a5f5f39fe9652250f0` |
| AOSP vold `PublicVolume.cpp` | [android-9.0.0_r1](https://android.googlesource.com/platform/system/vold/+/refs/tags/android-9.0.0_r1/model/PublicVolume.cpp), [android-16.0.0_r1](https://android.googlesource.com/platform/system/vold/+/refs/tags/android-16.0.0_r1/model/PublicVolume.cpp) | decoded sha256 `82d0321ec723b514d31c6d9e6700325b6a189d92b4eaff4439b0a3d93b16146c` / `eb40a476ca7e921482d7b1ed60f7e1629384ffb1fcd369092c9c2a8baca0cc08` |
| AOSP `StorageManagerService.java` | [android-9.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/StorageManagerService.java), [android-16.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/StorageManagerService.java) | decoded sha256 `f4210dc53760753ef70d88766c98cdbe606a2879d604f9ffde28aa1219034fcb` / `073a918bf11db680f63ba5da2430a9e3032b70558b4c2e94bb3af8af33eea43b` |

The SDK zips contain framework client code only. The system service and vold were read at Android 9 and 16 AOSP tags. **Android 17 (API 37) service and vold behaviour is not inspected**, and no OEM ROM was. Line numbers below are from these artifacts.

## What main does today

- **Card selection:** `cardFolder` (`OfflineStore.kt:45-48`) takes the first non-primary `getExternalFilesDirs(null)` entry where `Environment.isExternalStorageRemovable(File)` holds and `Environment.getExternalStorageState(File) == MEDIA_MOUNTED`.
- **Card shelf:** `create` (`OfflineStore.kt:130-132`) opens `SimpleCache(<folder>/downloads, NoOpCacheEvictor, database)` and `DefaultDownloadIndex(database, "card")`, once per process.
- **Name only:** `cardDescription` (`OfflineStore.kt:351-353`) reads `getStorageVolume(File).getDescription`.
- **No identity is recorded:** main stores no volume UUID, cache UID or card generation anywhere.

## Media3 identities (source)

- **One phone database:** `StandaloneDatabaseProvider` opens `exoplayer_internal.db` (`StandaloneDatabaseProvider.java:39,51`) in app-private storage. Both the phone and card caches and both download indexes live there.
- **Download index has no attribution:**
  - The table is `"ExoPlayer" + "Downloads" + name`, so `ExoPlayerDownloadscard` (`DatabaseProvider.java:30`, `DefaultDownloadIndex.java:47,176-179`).
  - Its version row is keyed by `(FEATURE_OFFLINE=0, instance_uid "card")` (`DefaultDownloadIndex.java:292-302`, `VersionTable.java:48,62-69`).
  - Its columns (`DefaultDownloadIndex.java:51-65`) have no volume, path or cache UID. Only `id`, `custom_cache_key` and `data` (Muon's song record) identify the song.
- **Cache UID travels with the folder's bytes:**
  - `SimpleCache.initialize` (`SimpleCache.java:520-548`) creates the folder if missing, then reads `<hex>.uid` from it (`loadUid`, 774-788; a malformed name is deleted).
  - If there is none, it writes a new random non-negative UID file (`createUid`, 791-803).
  - The UID names the phone tables `ExoPlayerCacheIndex<hex>` and `ExoPlayerCacheFileMetadata<hex>` (`CachedContentIndex.java:738,780-781,971-972`; `CacheFileMetadataIndex.java:36,114-115,252-253`).
- **Constructing is not read-only:** the constructor locks the folder and starts `ExoPlayer:SimpleCacheInit`, which runs `initialize()` immediately (`SimpleCache.java:212-243`). Building a `SimpleCache` to "look at" a card creates its directory and, for a new folder, a new UID. `SimpleCache.delete` drops both UID tables (`SimpleCache.java:100-122`).

## Android public API at minSdk 28

**Public in both the 28 and 37.0 sources:**
- `StorageManager.getStorageVolume(File)`, `getStorageVolumes()`, `getUuidForPath(File)`;
- `StorageVolume.getUuid()`, `getState()`, `isRemovable()`, `isPrimary()`, `isEmulated()`, `getDescription(Context)`;
- `equals`/`hashCode`, which compare the path (`StorageVolume.java:478-489` at 37).

**Absent from the 28 sources** (need an SDK_INT guard): `getDirectory()`, `getStorageUuid()`, `getMediaStoreVolumeName()`, `getRecentStorageVolumes()`, `getStorageVolume(Uri)`.

| Question | Source answer | Status |
| --- | --- | --- |
| What identifies a portable card? | `getUuid()` returns `mFsUuid` (`StorageVolume.java:333-335`). For `TYPE_PUBLIC`, `buildStorageVolume` passes vold's `fsUuid` and leaves `getStorageUuid()` null (`VolumeInfo.java:421-464`; javadoc 321-322). vold reads `fsUuid` from the filesystem with `ReadMetadataUntrusted` (`PublicVolume.cpp` 9:56, 16:62) | Supported, 9/16/37 client |
| `getUuidForPath`? | It skips `TYPE_PUBLIC` (and `TYPE_STUB` at 37), then throws `FileNotFoundException` (28:753-775; 37:954-968) | **Not usable for a portable card** |
| How does Muon's folder path relate? | vold names the mount `/storage/<stableName>`: `stableName` is `fsUuid` if non-empty, else the volume id `public:<major>,<minor>` (`PublicVolume.cpp` 9:115-128; 16:53,95-99,129-140). `getExternalFilesDirs` builds `<volume path>/Android/data/<pkg>/files` from the volume list with `FLAG_FOR_WRITE` (`Environment.java` 28:90-98,134-135; 37:243-251,287-288) | Supported. With a UUID, the path embeds it; without one, the path comes from the device node, not from the card |
| Present / absent / unknown per path | `getStorageVolume(File)` matches canonical paths against `getVolumeList()` (flags 0) and returns null for none (37:1246-1316). `Environment.getExternalStorageState(File)` returns **`MEDIA_UNKNOWN`** for no volume (28:918-925; 37:1402-1409). `isExternalStorageRemovable(File)` throws (28:950-956) | Supported: per path, **absent and unknown look the same** |
| Is a per-path state the real state? | `getVolumeList(flags=0)` has no `FLAG_REAL_STATE`. The service reports `MEDIA_UNMOUNTED` when `!storagePermission && !realState` (SMS 9:2762-2767; 16:4042-4057). At 16 it also reports unmounted before the system user unlocks (4048-4050). `getStorageVolumes()` passes `FLAG_REAL_STATE \| FLAG_INCLUDE_INVISIBLE` (37:1343-1347) | Supported: the per-path state can be **reported, not real**. Whether `hasExternalStorage` is true for Muon is unverified: at 16 it is mount mode ≠ NONE (5072-5081); the 9 policies weren't read |
| Which volumes are listed? | `TYPE_PUBLIC` is listed when visible to the user, or when invisible with a path if `includeInvisible` (SMS 9:2745-2760; 16:4005-4040). How a removed card leaves `mVolumes` wasn't read | Inference: a card that isn't attached isn't listed. Unverified at 37/OEM |
| Recently seen but gone | `getRecentStorageVolumes()` (API 30+, 37:1377-1383) adds records seen within a week as `MEDIA_UNKNOWN`, path `/dev/null`, `getUuid()` = `fsUuid` (`VolumeRecord.java:101-122`). The service de-duplicates by `fsUuid` (SMS 16:3999,4071-4087) | Supported at 16/37. Not available at 28. Android itself treats `fsUuid` as the volume key |

## Supported and unsupported claims

| Case | What the evidence supports | What it does not |
| --- | --- | --- |
| Same card remounted | Conditional on the filesystem, UUID and cache folder remaining intact: same filesystem → same `fsUuid` → same `/storage/<uuid>` path → same Muon folder → same `<hex>.uid` → same cache tables. The shared `card` index is also unchanged | Device confirmation on the user's ROM; Android 17 vold/service; behaviour after bad removal or a filesystem check that rewrites metadata |
| Replacement card with a different filesystem UUID | Different `fsUuid` and path. Muon's folder is missing, so opening a cache would create a **new UID**. The `card` index still claims the old card's songs (#256, not new) | Nothing beyond that |
| Reformatted card with Muon's folder erased | The old `.uid` is gone; constructing a new cache would generate a new UID even if `fsUuid` were kept. A missing UID is Unknown before initialization, not proof of a new valid generation | That a reformat always changes `fsUuid`: that's formatter behaviour, not in these sources (hypothesis). Matching UUID alone is therefore not enough |
| Null/empty `fsUuid` | Path is `/storage/public:<major>,<minor>`. A different UUID-less card in the same slot gets the **same** path and folder name | Any Android-level identity. Only the folder's `.uid` file remains, and it's created on first open |
| Duplicated `fsUuid` (cloned card, or two cards sharing a 32-bit FAT serial) | Same path and, for a byte copy, the same `.uid`. Android's recent list de-duplicates them | Telling them apart: neither Android nor Media3 identity can. Two attached at once was not inspected (unverified) |
| Legacy `ExoPlayerDownloadscard` rows | No column ties a row to a volume or cache UID. Only an inference is possible: the key has complete spans in the currently attributed card cache | Assigning any row to a card from the index alone. Older cards' `ExoPlayerCacheIndex<hex>` tables may remain in the phone DB (not inspected on a device) |

## Read-only catalog sketch (design only, not implemented)

**One record per observed card generation:**
- a Muon-generated catalog id;
- `fsUuid` (nullable);
- the cache UID, read by **listing** `<folder>/downloads` for exactly one `*.uid` name, never by constructing a `SimpleCache`;
- the app folder path;
- first and last seen;
- an attribution state.

**Observation, each time Muon decides:**
- **Available:** `getStorageVolumes()` lists a removable, non-primary volume:
  - whose `getUuid()` is non-null and matches the record;
  - whose real `getState()` is `MEDIA_MOUNTED`;
  - whose path contains the folder;
  - and whose listed `.uid` matches.
- **Not observed (unavailable):** no listed volume has the record's `fsUuid`; this alone does not prove physical absence. On API 30+, `getRecentStorageVolumes` may say it was seen recently. It stays unavailable and is preserved, never treated as empty.
- **Unknown:** everything else. This includes a null UUID, `MEDIA_UNKNOWN`/`CHECKING`/`READ_ONLY`/`UNMOUNTED`/`BAD_REMOVAL`, a lookup exception, a folder that is missing (or no `.uid`), several `.uid` files or a mismatch, and a duplicate `fsUuid`.

**Preservation rules:**
1. Unknown and absent never create, open, release, delete or migrate anything.
2. Legacy `card` rows are classified read-only:
   - "evidenced on this generation" only when the attributed cache has the key's full length;
   - otherwise "unattributed".
   - Nothing is removed or rewritten.
3. A new generation never inherits another's rows.
4. Path, description and cache UID are never accepted alone as identity.

## Stop conditions before migration, release/recreate or service hot-swap

1. **No `SimpleCache` or `DownloadManager` for a card before it observes as Available.** Construction writes a directory and UID (`SimpleCache.java:212-243,520-548`).
2. **No writes to `ExoPlayerDownloadscard`, `ExoPlayerCacheIndex*` or `ExoPlayerVersions`.** No `SimpleCache.delete`, and no `release` of an absent card. These are the existing invariants, unchanged.
3. **Device evidence (S6, separately authorized, disposable card) is required** for `getStorageVolumes()` real state, `getUuid()` and paths across:
   - eject, bad removal and reinsert;
   - a different card;
   - a reformatted card;
   - a UUID-less card.
   It's also required for whether the per-path state in `cardFolder` reports unmounted for permission or lock reasons, on the user's ROM and Android 17.
4. **Product decisions are needed** for null-UUID and duplicate-UUID cards (see below).
5. **S3 (mapping preservation) and S4 (service helper ownership) still gate** any release, recreate or hot-swap. This catalog evidence doesn't lift them.
6. **Migration needs its own design and review:** where the catalog persists, its schema and an idempotent restart, after S2–S4.

## Fixture decision

Report only. A JVM fixture can't characterize the Android behaviour: Robolectric's shadows don't model vold or `StorageManagerService`. The Media3 UID behaviour is fully determined by the ~30 lines cited, so a test would only restate source or mirror a future catalog.

## Next bounded production decision

Choose the card identity rule for user approval:
- **(a)** Require both a non-null `fsUuid` and a single matching `.uid` for Available, and refuse card downloads on UUID-less cards; or
- **(b)** Accept UUID-less cards by `.uid` alone, with the slot-collision risk above.

Then a read-only catalog and legacy-row classifier, with no writes, could be specified for S2 implementation behind the stop conditions.

## Local state

The sources were downloaded to `build/sources/` (git-ignored by `**/build/`) and read with Python's `zipfile`/`base64`. They're left in place, since no deletion is permitted. The user's card bytes, metadata and experiment checkout were untouched.

## Independent coordinator review and remaining design gaps

GPT-6/Codex desktop, effort not reported, reviewed Claude's report against cited SDK37/AOSP16/Media3 sources and actual main call sites. Source hashes checked for session-relevant SDK37, datasource, exoplayer and decoded AOSP16 artifacts. Qualified remount/replacement/reformat assertions as conditional; no real card event or Android17 service/vold behavior confirmed. SDK37 `getStorageVolumes` client Javadoc distinguishes actively attached volumes in any mount state, with visibility to the calling user; lack of observation remains unavailable/unknown for preservation rather than evidence allowing deletion. A matching fsUuid/cache UID can support a catalog association; it is not proof of a physically distinct card, complete bytes or atomic availability. Path comparisons must use canonical component containment, not string-prefix matching, with read errors/races treated as Unknown.

The proposed Available rule plus the no-construction-before-Available rule deliberately cannot adopt a pristine new card (it has no existing UID). Initial creation/adoption therefore needs a separate explicit, reviewed transaction; do not resolve the circularity by constructing SimpleCache during observation or accepting an absent UID as an old volume. Reading complete cache spans for legacy-row classification is also not automatically read-only: current cache APIs may remove stale spans/persist mappings. S3 must identify a non-mutating catalog evidence path before that classifier runs. These are design blockers, not implemented behavior. No policy choice for UUID-less cards is settled by this report; preservation is the default while unsupported identity stays Unknown.
