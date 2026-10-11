# #179 S1 card availability containment — 2026-10-01

Worktree `/home/avery/.codex/worktrees/muon-card-availability`, branch `codex/card-availability-containment`, from main `07a72e8586f6988f94ff5c2a5a11ebe868abee61`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort high as assigned by the coordinator. Coordinator source review requested the second revision: guard the originating completion and both move endpoints, distinguish missing from empty, remove survival guarantees, and test actual route/list/remove wiring. This branch is committed for CI, not yet runtime-verified. Claude performed no phone access, push, PR or merge. Coordinator owns CI/PR integration; no card-device QA yet. Not compiled locally: CI is the first compile and test run. Refreshed destination: `065d1c0bf691d61a0215e1392820ba62414c99ca`. Coordinator review/integration: GPT-6, Codex desktop, effort not reported; author/contributor review, not independent authorship-free review. Continues slice S1 of the [storage preservation design](2026-10-01-storage-preservation-design.md). **It does not close #179.**

## What is established, and what is not

**Established:** when the card Muon opened with is observably unavailable, **new decisions skip it**. They don't route to it, list it, download to it, remove from it, move to or from it, or start its service. These decisions do not themselves release, recreate or delete its cache, download index or manager.

**Not established:** that the card's songs survive, or come back intact. Three known risks remain:
- a card that disappears during a cache operation already under way can still lose the cache's mapping ([cache characterization](2026-09-29-cache-characterization.md));
- a different card at the same path is not told apart;
- every check is a snapshot, not a lock.

The UI and code comments no longer claim that downloads are kept or that they return.

## The check

`cardPresent(folder)` is true when Muon's own folder on the card picked at store creation is a directory, and `Environment.getExternalStorageState(File)` reports `MEDIA_MOUNTED` for it. That is the same mounted-state call `cardFolder` uses to pick the card. Nothing is created to find out. A read-only, unmounted, removed or unknown state counts as unavailable. The phone is always available. There is no mount listener: the check runs at each decision.

## Decisions with the card unavailable

| Decision | Behaviour |
| --- | --- |
| Routing (`routeOfflineRequest` → `servingShelf`, `downloadedOn`) | The card isn't read. The song streams; offline, it is unavailable. |
| Listing (`downloadedSongs`, `downloadedLibrary`) | The card's songs aren't listed. |
| `add`, with Store on SD card on (or chosen with no card found) | Refused with a message. **Not** queued, and not sent to the phone. The user is told to try again later or turn the setting off. |
| `remove` / `removeAll` | Nothing is sent to the card's service; ids its index still records are reported as not removed. |
| Completion callback (`watch` → `leftoverCopies`) | Removes leftovers only if the shelf that reported completion *and* the leftover's shelf are both available. A completion reported for an absent card can't remove the phone's copy. |
| `move` | Refused when the card is missing or unavailable. Rechecked before each song. The hand-over callback (`deliverMovedCopy`) requires both source and destination to still be available. |
| `resume` | The card's service isn't started. |
| `downloadsOn(card = true)` | Null for a missing or unavailable card, rather than 0 for empty. |
| Settings | Shows "SD card not available · its downloads aren't shown or changed". The switch can still be turned off; no move is offered. The footnotes don't promise a return. |

**Untouched:**
- the card's `SimpleCache`, its `DefaultDownloadIndex` (`card`) and its `DownloadManager`;
- the `DownloadService` helper map, and `MuonCardDownloadService`'s `card ?: phone` selection;
- `record`/`removed`, which read the download index on the phone only, and the download marks;
- all retained files, records and tables.

## Tests (CI is their first run)

- **`CardAvailabilityTest`**, plain JVM with fake shelves:
  - serving and listing;
  - the download target, including a card that's chosen but absent;
  - the removal plan;
  - leftovers: kept while the leftover's shelf is unavailable, and nothing removed when completion is reported for an absent card;
  - the move gate;
  - stale hand-overs, for both a destination and a source that went;
  - absent folder: here `Environment` is the stub that throws, so this only shows the check fails closed and creates nothing. It is not a mount-state test.
- **`CardAvailabilityRouteTest`**, Robolectric with real disposable `SimpleCache`s and native-SQLite `DefaultDownloadIndex`es, like the existing card-index fixture:
  - `cardPresent` against `ShadowEnvironment`'s per-folder state: mounted, read-only, unmounted, removed, mounted but folder missing (not created), and unregistered.
  - Wiring through `routeOfflineRequest`, `OfflineStore.downloadedOn`, `downloadedSongs`, `downloadsOn` and `remove`. An originally completed card song routes to the card. After "removal", it routes to the phone with the request unchanged, while the phone's own download still routes and lists, the card's count is null, and remove sends intents only to `MuonDownloadService`. Removal here means renaming the cache folder away and marking it unmounted.
  - After restoring the folder, the card's in-memory cache still maps the span. That shows these decisions didn't trigger a stale-span scan while the files were gone.
- **Source check:** `ShadowEnvironment.getExternalStorageState(File)` in Robolectric 4.16.1 returns the state registered for a containing path, and null otherwise. It does not model Android's `StorageManager` volume lookup, so real ROM behaviour during removal is unverified.
- **Not run:** no network, downloader, real card or mount event. The `watch` callback and the `move` executor run only through the pure decisions; there's no fixture for them.

## Remaining limits and blockers

- **Not prevented:** mid-operation disappearance (a playback read, a card download, one song's copy). These checks are not atomic: blocker for #179 (S3).
- **No mount listener:** download marks still show card songs while the card is away, and Settings reads availability once per visit.
- **Card identity (S2):** a card inserted after store creation isn't adopted, and a different card reusing the same path isn't told apart.
- **Card service (S4):**
  - `MuonCardDownloadService` still falls back to the phone manager when no card shelf exists;
  - a card service that is already running keeps its manager;
  - a system-restarted service is not gated by `resume`.
- **Phone side effects:**
  - a card song played while the card is away may get a played copy on the phone, which duplicates it later;
  - a remove for an id held only on the absent card is still sent to the phone, where it does nothing.
- **Integration:** #234 and #237 also edit `move`/`copy`. Reconcile them with `canMove`/`deliverMovedCopy` rather than taking either side whole. #212, #240 and #248 are not included; preserve their semantics when integrating.

## QA (separately authorized, disposable copied audio only)

On the Poco with a card holding no user-only copies:
1. eject while idle, then test play online and offline, the offline list, Download (card chosen and not chosen), Remove, Remove all, and Move both ways;
2. reinsert the same card and check what is there;
3. launch with the card absent while Store on SD card is on: check the Settings row, the refusals, and turning it off.

Abrupt removal mid-operation is S6, not a pass criterion for this slice.

Pinned shadow source checked by coordinator: [Robolectric 4.16.1 ShadowEnvironment](https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/shadows/framework/src/main/java/org/robolectric/shadows/ShadowEnvironment.java). SDK34 shadow state registration is fixture evidence only, not Android17/Poco mount behavior.
