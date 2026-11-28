# Muon codebase map

Orientation for agents working on this repository: where behaviour lives, what must not break, and where the unverified edges are. Keep it current when the shape of the app changes, not when individual lines do.

## Shape

A single-module Android app (`app/`, package `dev.avery.muon`, flat). One activity, one Compose tree, one media session. No DI framework, no navigation library, no repository layer — deliberately, per AGENTS.md. Composables called from another file are `internal`; the rest stay `private`. `baselineprofile/` generates the Baseline Profile ([notes](baseline-profile.md)); `mediaentryprobe/` is a diagnostic-only QA fixture ([CI](ci.md)).

```
MainActivity ──binds──▶ PlaybackService (Media3 MediaSessionService)
      │                        │
      │ MediaController        │ ExoPlayer ── OfflineDataSource ──▶ saved copy, or OkHttp stream
      ▼                        ▼
   MuonApp ◀──state── LibraryModel (AndroidViewModel)
      │                        │
      │                        └──▶ TauonApi ──HTTP──▶ Tauon desktop (LAN)
      └──▶ tabs: Library │ Search │ Settings, or Connect when there is no server
             └──▶ overlays: Now Playing (grows from the mini player) │ Lyrics │ Queue
```

### App shell, playback and library

| Files | Hold |
|---|---|
| `MainActivity.kt`, `MuonApp.kt` | Activity lifecycle and `MediaController` binding; the shell: tabs, overlays, scaffold, queue start and Back targets |
| `PlaybackService.kt`, `PlaybackSessionCallback.kt`, `PlaybackQueuePlayer.kt` | The Media3 session and player; owns the queue and survives the UI. Trusted system controllers operate the existing queue only |
| `PlaybackState.kt`, `PlaybackModes.kt`, `PlaybackModePreferences.kt`, `PlaybackProgress.kt` | `PlaybackUi`, the ticking position and listener; shuffle/repeat and their remembered choice |
| `LiveQueue.kt`, `QueueModel.kt`, `QueueInsertion.kt`, `QueueRemovalUndo.kt`, `QueueScreen.kt` | Building, inserting into and undoing changes to the queue; the Queue overlay |
| `ReplayGain.kt` | Volume normalization (#97) |
| `LibraryModel.kt`, `LibraryLoad*.kt`, `LibraryProjections.kt`, `LibraryGroups.kt`, `LibraryAlbums.kt`, `LibraryArtists.kt`, `LibrarySorting.kt`, `LibraryPreferences.kt`, `LibraryPages.kt` | Connection state, bounded library loads, worker-made album/artist/song views, sort order and which page the Library tab shows |
| `TauonApi.kt`, `TauonJson.kt`, `TauonTracklist.kt`, `TrackAlbumMetadata.kt` | HTTP calls, strict JSON parsing and response size limits ([album metadata](album-metadata.md)) |
| `ServerEndpoint.kt` | **Security boundary.** Parses and validates a server address |
| `ServerDiscovery.kt`, `DiscoveryState.kt`, `LanProbe*.kt`, `LocalNetwork.kt` | NSD discovery ([contract](discovery.md)), the /24 LAN probe and Android 17's local network permission |
| `Artwork.kt`, `ArtworkCache.kt`, `LiveArtwork.kt`, `NotificationArtwork.kt` | Cover fetch, decode at the drawn size, memory `LruCache` and disk cache, and session/notification artwork |
| `TrackIdentity.kt`, `TrackSearch.kt`, `TrackCredits.kt`, `TrackTitle.kt`, `MediaVolume.kt` | Pure helpers, unit-tested |

### Screens and Gen 3 UI

| Files | Hold |
|---|---|
| `LibraryScreen.kt`, `AlbumScreens.kt`, `ArtistPage.kt`, `SearchScreen.kt`, `NowPlayingScreen.kt`, `LyricsScreen.kt`, `SettingsScreen.kt`, `ConnectScreen.kt`, `SavedScreen.kt` | One file per screen (`SavedScreen` is On this device) |
| `TrackList.kt`, `MiniPlayer.kt`, `PlayerPanel.kt`, `Components.kt`, `SongActions.kt`, `SheetAction.kt` | The track list and row, the mini player, the landscape side panel, shared controls, and the song panel (long press or ⋮) |
| `PlayerMorph.kt`, `MiniPlayerDrag.kt`, `PlayerDismissDrag.kt`, `PlayerBack.kt` | Material's container transform from mini player to Now Playing, the drags that open and close it, and its predictive Back |
| `PageBackPeek.kt`, `PageToolbar.kt`, `SharedMotion.kt` | Predictive Back on Library pages (#658), the floating Play/Shuffle toolbar on album and artist pages, and shared cover transitions |
| `FrostedGlass.kt`, `BlurBehind.kt`, `CoverBackdrop.kt`, `ArtworkTheme.kt` | Frosted mini player and navigation bar, the library blurred behind the player, Now Playing on its blurred cover, and cover-derived colours |
| `JoinedGroup.kt`, `ExpressiveShapes.kt`, `WavySeek.kt`, `SwipeToQueue.kt`, `NowPlayingBars.kt` | Joined row groups with the current row as a pill, shape morphs, the wavy seek bar, swipe-to-queue, and the playing-bars indicator |
| `RecentAlbums.kt`, `RecentSearches.kt` | Jump back in (last ten albums, per server) and Search's recent searches (at most eight) |
| `AlphabetScroller.kt`, `ScrollIndicator.kt`, `ArtworkSwipe.kt`, `MediaVolumeSlider.kt` | The draggable A–Z scroller, the passive indicator, swipe-to-skip and the volume slider |
| `Appearance.kt`, `MuonTheme.kt`, `MuonTypography.kt`, `MuonIcons.kt` + `res/drawable/ic_*.xml`, `Motion.kt`, `WindowLayout.kt` | Palette, Pure black and the **Expressive motion** / **Blur** switches; colour schemes; type ([fonts](fonts.md)); icons ([generator](design/icons/README.md)); motion tokens; the sideways rail and two-pane layout |

Most Gen 3 behaviour sits behind Settings → **Material 3 Expressive experiment**, two switches that default on so each part can be compared off on the same phone: **Expressive motion** (springs, the player morph, connected button groups, joined lists, wavy progress) and **Blur** (Now Playing's cover backdrop on every version; the library blur behind the player and the frosted bars need Android 12+, and the bars are frosted in portrait only). The cookie cover placeholder, the A–Z tick and the time-of-day greeting are not behind either switch. The branch history is in the [archived experiment notes](archive/expressive-experiment.md).

### Offline storage

Saved copies (#112, #213): downloads, the played-song cache and SD card shelves.

- **Entry points:** `OfflineStore.kt` (shelves per volume, each with its own cache, download index and service), `OfflineDataSource.kt` / `OfflineRoute.kt` (play a request from the shelf it names, or stream), `MuonDownloadService.kt`, `PlayedCache.kt` / `PlayedCopy*.kt`, `CardAvailability.kt` and `OfflineUi.kt`.
- **Saved copies:** `Saved*.kt` — catalog and paging for On this device, presentation, saved queue order, cover I/O, digests, the storage barrier and migration dialog (*Update storage format*).
- **Legacy Media3 storage:** `Legacy*.kt`, `CacheMigration*.kt`, `Barrier*.kt`, `Download*.kt` — the default backend, its read-only projection, retirement journals and bounded byte/mark ledgers.
- **Partition storage:** `Partition*.kt`, `OwnedPartitionShelf.kt`, `StorageBackendSelection.kt`, `StorageStartup*.kt` — an explicit opt-in backend (`PARTITION_OPT_IN_AVAILABLE`), chosen once at process start before any cache opens. No live swap, fallback or automatic opt-in.

These files carry ownership, journal and cancellation contracts in their doc comments; read them before editing, and see the [issue-state index](issues/README.md) for open storage work (#646, #401).

## State ownership

- **`LibraryModel`** survives configuration changes and owns the server and library. It is the only writer of the `connection` preferences, and `disconnect()` **clears that whole file**.
- **`AppearanceSettings`** deliberately uses a *separate* `appearance` preferences file for that reason. Do not merge them.
- **`PlaybackService`** owns the queue. The UI is a view onto it and may be rebuilt at any time. Play modes are remembered; the queue does not survive process death.
- **`PlaybackState`** splits event-driven state (`PlaybackUi`) from the moving position (`MutableLongState`) on purpose: putting the position back into `PlaybackUi` makes every tick recompose the tree (PR #4).
- Muon's preferences and saved data are excluded from backup and device transfer (checked in CI).

## Invariants — do not break these

1. **`ServerEndpoint.parse` is the only way an address becomes a URL.** It rejects public, multicast and ambiguous addresses; discovery results pass through it too. Its tests are the contract.
2. **Tauon's API is unauthenticated and LAN-only.** Never expose it, tunnel it, or add a code path that reaches a non-private host.
3. **Package IDs and signing identities are fixed.** `dev.avery.muon` (Canary) and `dev.avery.muon.release` (Stable) install side by side. Never change them, the keystores or the Obtainium update paths.
4. **State must not be signalled by colour alone.** Every toggle carries a shape cue *and* a `stateDescription`.
5. **Response bodies are size-capped** (16 MiB JSON, 4 MiB artwork). Keep the caps when touching `TauonApi` or `Artwork`.
6. **Unavailable tracks are never queued**, and a queue is built in full or refused, never silently truncated.

## Performance notes

- **Position ticking** is isolated to the widgets that draw it; the mini player's progress reads it in the *draw* phase.
- **Search** is debounced and filtered on `Dispatchers.Default`, keyed on the library so results from a replaced library are dropped.
- **Artwork** is seeded from the memory cache during composition, so a returning row draws instantly; the cross-fade is skipped for cached bitmaps. List thumbnails are kept as their own small decodes on disk.
- **Track list keys** are track identity (`TrackIdentity.kt`), not list index.
- **Blur** costs a GPU pass per frame while the player or frosted bars are shown; the cover backdrop is blurred once per song.

## What tests actually cover

Unit tests are JVM JUnit under `app/src/test`; there are no Compose UI tests, and `DirectPlaybackTest` (instrumentation) does not run in CI.

- **Covered:** endpoint validation, discovery bookkeeping, library loads and projections, queue construction and undo, play modes, volume normalization, search, track identity, palette and theme gates, motion/blur helpers, artwork sizing and caches, and the offline storage journals, ledgers and ownership contracts.
- **Not covered:** layouts, animations, Compose effect lifetimes, insets, font scales, and all playback on a real device.
- **Consequence:** CI proves the app compiles, its pure logic holds and its APKs are signed and identifiable. It proves nothing about how the app looks or feels. Say so in PR descriptions.

## Suggested order for a deeper audit

1. `ServerEndpoint.kt` and `TauonApi.kt` — the security boundary and the only untrusted input.
2. `PlaybackService.kt`, `LiveQueue.kt` and queue construction in `MuonApp`.
3. `LibraryModel.connect()` — cancellation, partial loads and failed refreshes after a good load.
4. `OfflineStore.kt` and the storage owners — the largest and most stateful subsystem.
5. The screen files, which are presentation and best judged on a device.
