# Muon 2.0 implementation map

How the approved mockups become code, in small resumable batches. **This is a proposal for the joint Astra and Claude planning session. It authorises nothing:** the user starts each batch.

Read [`HANDOFF.md`](HANDOFF.md) first. It holds the decisions, rejected alternatives and open questions; this file does not repeat them. The specification is [#40](https://github.com/averylicious/muon/issues/40).

- **Written:** 2026-09-14 by Claude Opus 5 (`claude-opus-5`), Claude Code desktop, implementation agent. Not yet reviewed.
- **Inspected commit:** app code at `main` `5d2e2db`. PR #52's head `ee93bc9` has no changes under `app/` (`git diff 5d2e2db ee93bc9 -- app` is empty).
- **Uncommitted changes:** none to tracked files. The checkout shows untracked sandbox placeholders at the root (`.bashrc`, `.gitconfig` and similar, all character devices) and local `.claude/`, `.idea/`, `.vscode/` directories. They are unrelated and were not touched.
- **Scope of inspection:** `MuonApp.kt`, `LibraryModel.kt`, `TauonApi.kt`, `PlaybackService.kt`, `Artwork.kt`, `MainActivity.kt`, `MuonTheme.kt`, `MuonIcons.kt`, `MediaVolumeSlider.kt`, `ServerDiscovery.kt`, the pure helpers, `app/build.gradle.kts`, the manifest, issues #23, #39 to #51, and the mockups. This is not a repository audit.

**Labels:** **Verified** means read in source at the inspected commit. **Gap** means the code does not provide something a mockup needs. **Proposed** means a suggestion, not a decision. **Unverified** means not checked against the artefact or API.

## 1. What exists and can be reused

All verified.

| Piece | Where | Reuse in 2.0 |
|---|---|---|
| Event state split from the ticking position | `MuonApp.kt`: `PlaybackUi`, `PlaybackState`, `rememberPlayback` | Keep as is. Now Playing, the mini player and Queue read `position` as a lambda. |
| Queue start, filtering out unplayable tracks | `MuonApp.kt`: local `startQueue` | Album and artist Play and Shuffle, and search results. It must keep filtering on `playable` (codebase map, invariant 6). |
| Media item construction | `PlaybackService.kt`: `TauonTrack.mediaItem(endpoint)` | Play next and Add to queue. It sets title, artist, album and artwork, but **not duration** (see B4). |
| Server-side check on every added item | `PlaybackService.kt`: `onAddMediaItems` | Controller `addMediaItem` and `setMediaItems` calls pass through it, so new queue actions inherit the URI validation. |
| Debounced, off-thread search with library reset | `MuonApp.kt`: `produceState` in `key(all)`; `TrackSearch.kt`: `searchTracks`, `searchEmptyText` | #48 keeps both, per the issue. |
| Stable list keys, including repeated tracks | `TrackIdentity.kt`: `trackKeys` | Songs, album and artist lists. Also a model for queue row keys. |
| Track row and loading placeholders | `MuonApp.kt`: `TrackRow`, `PlaceholderRows` | Songs, artist songs and search. `TrackRow` already has non-colour current-track marking and the 44dp duration column. |
| Artwork with a cache seed and no fade for cached images | `Artwork.kt`: `Artwork`, `artCache` | All art. Decode sizing is #23. |
| Volume with the floating percentage | `MediaVolumeSlider.kt`: `MediaVolumeSlider`; `MediaVolume.kt`: `rememberMediaVolumeController` | Move from `MediaVolumeDialog` into Now Playing. The approved bubble is already built. |
| Seek slider that scrubs without seeking | `MuonApp.kt`: `SeekControls` | Now Playing, restyled only. |
| Shuffle and repeat toggles with a dot and state description | `MuonApp.kt`: `ToggleControl`, `ShuffleControl`, `RepeatControl`; `PlaybackModes.kt` | Now Playing. Keep the dot and `stateDescription`. |
| Library load, refresh and error state | `LibraryModel.kt`: `busy`, `error`, `progress`, `connect()`; a failed refresh keeps the last library | `PullToRefreshBox` uses `isRefreshing = busy`. Settings shows *Refresh library* and the track count. No backend change. |
| Palette and Pure black | `Appearance.kt`: `AppearanceSettings`, `effectivePalette`, `dynamicColorAvailable`; `MuonApp.kt`: `AppearanceSection` | Settings becomes grouped rows. The pre-Android-12 fallback note must survive. |
| Lyrics fetch with retry | `MuonApp.kt`: `LyricsScreen`, which parses the endpoint and track ID out of `mediaId` | #50 restyles it. The `mediaId` format is therefore load-bearing (see Q4). |
| Motion tokens | `Motion.kt`: `motionShort`, `motionMedium`, `MotionEasing` | State changes. Gestures add springs (#40). |
| Discovery | `ServerDiscovery.kt`: callbacks `found(name, url)` and `message(String)`; every answer goes through `ServerEndpoint.parse` | #51 needs named states rather than message strings (B6). |

Dependencies verified in `app/build.gradle.kts`: compose-bom `2025.10.00`, activity-compose `1.11.0`, Media3 `1.11.0`, no navigation library, no image-loading library, minSdk 28, targetSdk 36.

## 2. Mockup to code

Each row lists the current code, the frontend gaps (all gaps) and what the frontend needs from the backend.

| Screen | Now | Frontend gaps | Needs from backend |
|---|---|---|---|
| **Tabs, mini player** (all) | `Screen` enum with a Playing tab; `NavigationBar` and `MiniPlayer` in `Scaffold.bottomBar`; a single `BackHandler` returns to Library | Three destinations; a back stack for overlay, Queue or Lyrics, and album or artist pages, since the `Screen` enum carries no arguments; no `snackbarHost` on the `Scaffold` | None |
| **10–11 Now Playing** | `NowPlaying` is a tab with a volume dialog; it already scrolls on short or narrow screens | An overlay above the `Scaffold` that shares a surface with the mini player; drag and spring; collapse button; predictive back (the manifest has no `enableOnBackInvokedCallback`; check whether targetSdk 36 still needs it); artwork swipe calling `seekToNextMediaItem` and `seekToPreviousMediaItem`, guarded by `PlaybackUi.next` and `previous`; inline volume; Lyrics and Queue row | None |
| **01 Songs, 04 Playlists** | `LibraryBar` with Refresh; `PlaylistChips` with *All music*; `TrackList` | Bold greeting that folds, reading scroll state in layout or draw; view switcher; `PullToRefreshBox`; remembered view (Q6); hide empty playlists; playlist rows with a count | None |
| **02 Albums, 03 Artists** | Nothing | Album grid, artist list, grouping helper | B1 (#23) before the grid; B2 grouping data; Q3 |
| **05 Album, 06 Artist** | Nothing | Pages, Play (filled) and Shuffle (tonal), numbered tracks, initials avatar, navigation arguments | B2: track number and album artist if Tauon provides them |
| **07–09 Search** | `SearchField` with `OutlinedTextField` and a Clear button | `SearchBar` expanding to full screen; suggestions of artists and albums at rest; results grouped into Songs and Albums; Back collapses the bar | B2 grouping, shared with #44 |
| **12 Lyrics** | `LyricsScreen` with a text back button | `LargeFlexibleTopAppBar` with the song as title and the artist as subtitle | Optional: a lyrics-availability hint (B7) |
| **13 Queue** | Nothing | Now playing card; *Next up* with count and total length; select; swipe to remove with Undo; drag handle to reorder | B3 queue state, B4 actions and durations; Q1, Q2, Q4 |
| **14 Song actions** | Nothing | Long press, `ModalBottomSheet`, snackbar wording, Undo | B4 insert actions; B2 for *Go to album* and *Go to artist* |
| **15–16 Settings** | `SettingsScreen` with cards and `PrivacyNote` | `LargeTopAppBar`; grouped `ListItem` rows in position shapes; Connected badge; Disconnect `AlertDialog` that still calls the existing disconnect lambda (stop, clear, `model.disconnect()`) | None. **Conflict:** mockup 15 has no privacy note, while today's Settings has one (Q8). |
| **17 Connect** | `ConnectScreen` with a manual scan button | Bold greeting, grouped address field, *On this network* list, *Scan again*, trusted-network note | B6 discovery states from #39 |

## 3. Backend data and actions the UI needs from Astra

A proposed list for the joint session. Nothing here changes ownership. **B** items are capabilities; **Q** items are decisions.

**B1 — Artwork decoded to display size (#23).** **Verified:** `fetchArtwork` in `Artwork.kt` samples to about 1000px for every call site, against a 12 MB `LruCache`; each row that enters composition starts a blocking `Call.execute()`. **Needed:** a size hint from the call site (52dp rows, album grid cells, full-width Now Playing), smaller cached bitmaps, and the other steps from the issue's second comment. **Proposed:** add the size as a *defaulted* parameter to `Artwork`, so #23 does not have to stack on #41 or rewrite call sites. **Must merge before batch 8.**

**B2 — Album and artist data.**
- **Verified:** `TauonApi.tracks` parses only `id`, `title`, `artist`, `album`, `duration`, `can_download` and `has_lyrics`.
- **Gap:** no album artist, track number, disc or year. The album page lists numbered tracks, and grouping by album name alone would merge different artists' albums that share a title.
- **Unverified:** whether Tauon's `/api1/tracklist` response includes those fields. **Ask Astra to check.** If it does, they need parsing with size and shape validation in `TauonApi.kt`. If not, number tracks by playlist order and group by album plus artist.
- **Proposed:** a pure grouping helper, for example `LibraryGrouping.kt`, with JVM unit tests, built from `LibraryModel.allTracks`. It needs no Tauon API change, as #44 says. Its ownership is Q3.

**B3 — Queue state (#47).**
- **Verified:** `PlaybackUi` holds only the current item and the shuffle and repeat flags; nothing exposes the timeline.
- **Needed:** the current entry; *Next up* in actual playback order, with per-entry artwork, title, artist and duration; and a stable identity per entry, even when a track appears twice.
- **Proposed:** a separate snapshot rebuilt on timeline, media-item-transition, shuffle and repeat events, kept out of `PlaybackUi` so a queue edit does not recompose the whole tree. Playback order would come from `Timeline.getNextWindowIndex(index, repeatMode, shuffleModeEnabled)` (**unverified** against the 1.11.0 artefact, since no local Gradle cache exists).

**B4 — Queue actions (#46, #47).**

| UI action | Proposed controller call | Notes |
|---|---|---|
| Select an entry | `seekTo(windowIndex, 0)` | |
| Remove, with Undo | `removeMediaItem`; Undo uses `addMediaItem(index, item)` | Undo after the timeline has changed needs a rule (Q2) |
| Reorder | `moveMediaItem(from, to)` | Indices in list order or shuffled order (Q1) |
| Play next | `addMediaItem(currentIndex + 1, item)` | Behaviour while shuffled (Q1); behaviour when nothing is playing (Q2) |
| Add to queue | `addMediaItem(item)` | Same |

- **All actions:** must skip tracks that are not `playable` and must go through `onAddMediaItems` (verified).
- **Gap:** `TauonTrack.mediaItem` sets no duration, so the queue's *34 minutes* cannot be computed from the controller.
- **Proposed:** set the duration in `MediaMetadata`, which is Astra's file (`PlaybackService.kt`). **Unverified:** that `MediaMetadata.Builder.setDurationMs` exists in 1.11.0.

**B5 — Library refresh.** No change needed. `connect()` doubles as refresh, keeps the last library on failure and exposes `busy` and `error` (verified). **Suspected gap:** Q7.

**B6 — Discovery states (#39, needed by #51).** **Verified:** `ServerDiscovery` reports free-text messages and found servers, and runs only on a button press. **Needed:** named states: searching, found one, found several, found none, unavailable, and moved to a new address. Also whether auto-connect has happened, so Connect can say so. Every address must still pass `ServerEndpoint.parse`.

**B7 — Optional lyrics availability.** `TauonTrack.hasLyrics` exists but is not carried into the media item, so Now Playing cannot hide or mark Lyrics for tracks without them. **Proposed only;** today's behaviour, a "No lyrics stored" message, is acceptable.

### Queue recommendations for the joint session

These are recommendations, not decisions.

- **Q1 Shuffle.**
  - *Next up* should follow shuffled playback order when shuffle is on, as #47's backend note says. Otherwise the screen lies about what plays next.
  - Reorder while shuffled is the hard case. The recommendation is to allow it only with shuffle off, or to decide explicitly how a move maps onto the shuffle order.
  - Play next while shuffled should still play next. That may need a custom shuffle order in `PlaybackService`, because the default order's insertion behaviour is **unverified**.
- **Q2 The playing item.**
  - The mockup keeps *Now playing* in its own card with no handle and no swipe. The recommendation is that the playing item is not removable or movable from Queue, which avoids defining mid-track removal.
  - Undo should reinsert at the old index, clamped to the new timeline.
  - Play next and Add to queue with an empty queue should start playback of that one track.
- **Q4 Duplicates.**
  - **Verified:** `mediaId` is `"${origin}/${id}"`, so repeated entries share it. Today the current-track highlight marks every copy, and `startQueue` starts at the first copy (`indexOfFirst`).
  - The recommendation is to key Queue rows by occurrence, the way `trackKeys` does, and to act by timeline index, **without changing the `mediaId` format**, because `LyricsScreen` parses it.

## 4. Proposed file ownership

Proposed only; to be agreed in the joint session.

| File | Proposed owner | Notes |
|---|---|---|
| `MuonApp.kt`, and the per-screen files split from it by #41 | Claude | Staying in package `dev.avery.muon` means only `private` changing to `internal`. #41 also updates `docs/codebase-map.md` (handoff follow-up 8). |
| `MuonTheme.kt`, `MuonIcons.kt`, `Motion.kt`, `res/font`, `res/drawable/ic_*`, `docs/licenses` | Claude | |
| `MediaVolumeSlider.kt` | Claude | `MediaVolume.kt` is unchanged. |
| `Artwork.kt` | **Shared** | Astra owns fetch, decode and cache (#23); Claude owns the composable's look. Agree the size parameter first. |
| `LibraryModel.kt`, `TauonApi.kt`, `ServerEndpoint.kt`, `ServerDiscovery.kt`, `PlaybackService.kt` | Astra | Any UI-driven change is requested, not made by Claude. |
| Grouping helper (B2) | **Undecided** (Q3) | Pure code with no I/O. Either agent can write it; the other reviews. |
| Queue snapshot and actions (B3, B4) | Astra | The UI consumes them. |
| Remembered library view (Q6) | Claude | Must not live in the `connection` preferences, which `disconnect()` clears. |

## 5. Acceptance checks for every UI batch

- **Automated (CI, Android APKs workflow):** build, unit tests, lint, APK signature and identity checks on the latest head. New pure helpers (grouping, queue order mapping, snackbar wording) get JVM tests. CI proves nothing visual (`docs/codebase-map.md`).
- **States:** loading uses placeholders, not an empty message. Errors show retry where the action can be retried. Empty states have a message. No player covers `controller == null`, which happens between `onStop` and `onStart`. A failed refresh must keep the last library visible.
- **Long metadata:** single-line ellipsis on rows; titles wrap to two lines at most. Durations keep their right edge.
- **Large fonts:** at 200% nothing clips or overlaps, and controls stay reachable (scroll if needed, as `NowPlaying` does today). Landscape, insets and tablets are **excluded**; they belong to #16.
- **Accessibility:**
  - Every icon-only control has a label.
  - No state is shown by colour alone (invariant 4).
  - Touch targets are at least 48dp.
  - Every gesture has a visible alternative: collapse button, Back, the Next and Previous buttons, the queue's remove action.
  - TalkBack can do each action.
- **Performance:** no scroll, gesture or position value read in composition (#23). List items keep `key` and `contentType`.
- **Themes:** light, dark and Pure black, with Material You and the Muon palette.
- **Match the mockups** at the approved commit `0d3f6d1`, allowing for the approximations in `README.md`.

## 6. Batches

- **Owner** is the implementer. For Claude's batches, Astra reviews and merges when the user asks. For Astra's batches, the user decides review.
- **The user** authorises the start, runs phone QA on the Canary artefact, and instructs merges.
- **Effort** is relative (S, M or L), with uncertainty (low, medium or high). There are no time or quota promises.
- Each batch is one PR to `main` unless stated.
- **Prerequisite, not a batch:** merge #52 on the user's instruction, after deciding whether to fix handoff follow-ups 1–4 first.

| # | Issue: slice | Owner | Depends on | Effort |
|---|---|---|---|---|
| 1 | #41: split `MuonApp.kt` | Claude | none | M, low |
| 2 | #42: typeface and type roles | Claude | 1 | M, medium |
| 3 | #42: icon set | Claude | 1 | S, low |
| 4 | #23: artwork decode | Astra | none (parallel) | M, high |
| 5 | #43: three tabs and overlay layout | Claude | 2, 3 | L, medium |
| 6 | #43: gestures and predictive back | Claude | 5 | M, high |
| 7 | #44: header, switcher, Songs, Playlists, pull to refresh | Claude | 2, 3; Q5, Q6 | M, medium |
| 8 | #44: Albums and Artists | Claude | 4 merged, 7; B2, Q3 | M, medium |
| 9 | #45: album and artist pages | Claude | 8 | M, low |
| 10 | #47: queue state and actions | Astra | Q1, Q2, Q4 | M, high |
| 11 | #46: song actions sheet | Claude | 9, 10 | S, low |
| 12 | #47: Queue screen, select, remove and Undo | Claude | 5, 10 | M, medium |
| 13 | #47: reorder by handle | Claude | 12 | M, high |
| 14 | #48: search bar | Claude | 3, 8 | M, medium |
| 15 | #49: Settings | Claude | 2, 3; Q8 | S, low |
| 16 | #50: Lyrics | Claude | 5 | S, low |
| 17 | #39: discovery states and auto-connect | Astra | none (parallel) | M, high |
| 18 | #51: Connect | Claude | 2, 3, 17 | S, medium |

The order follows #40's delivery sequence. Batches 15 and 16 can move earlier once their dependencies merge, and 4 and 17 can run whenever Astra is authorised. **Stacking:** none required. Where two batches are listed as depending on the same earlier one (2 and 3; 12 and 11), each targets `main` after that dependency merges.

### Batch 1 — #41: split `MuonApp.kt`, no visual change
- **Outcome:** one file per screen, plus shared components and the playback state, all in `dev.avery.muon`. The diff consists of moves and `private` becoming `internal`.
  - **Excludes:** any visual, behavioural, dependency or naming change; `ServerEndpoint`, `TauonApi`, `PlaybackService` and `LibraryModel`.
- **Files:** `MuonApp.kt` split into new files, for example `LibraryScreen.kt`, `SearchScreen.kt`, `NowPlaying.kt`, `LyricsScreen.kt`, `SettingsScreen.kt`, `ConnectScreen.kt`, `MiniPlayer.kt`, `TrackList.kt`, `PlaybackState.kt`, `Components.kt`. Also `docs/codebase-map.md`.
- **Decisions:** file names only.
- **Checks:** CI green. **QA:** a smoke test that play, search, lyrics and settings behave as before.
- **Done when:** the PR is open with CI green on its head and the codebase map is updated.
- **Checkpoint:** after each moved file compiles in CI, or at the end if pushed once.

### Batch 2 — #42: Google Sans Flex and type roles
- **Outcome:** static instances at weights 400, 500, 600 and 700 with roundness 100, and optical size handled as #40 specifies. Typography roles in `MuonTheme.kt`. Remove DM Serif Display and its licence, and add the Open Font License.
  - **Excludes:** icons, layouts and the greeting fold.
- **Files:** `MuonTheme.kt`, `res/font/*`, `docs/licenses/*`, and style call sites.
- **Decisions:** how optical size is applied (per-role instances or `FontVariation`), and a reproducible instancing command with recorded font version and hash. Both need recording in the PR.
- **Checks:** CI; APK size change reported. **QA:** headings and body text in both themes at 100% and 200% font scale.
- **Done when:** the PR is open with CI green and the font provenance recorded.

### Batch 3 — #42: icon set
- **Outcome:** flat-terminal drawables, plus 2.0's additions: play next, add to queue, album, artist, delete, drag handle, close, collapse, queue, lyrics. Also the level-meter *music* icon as a trial.
  - **Excludes:** using the new icons on screens that do not exist yet, and launcher icons.
- **Files:** `res/drawable/ic_*.xml`, `MuonIcons.kt`, `MuonIconsTest.kt`.
- **Decisions:** none beyond #40.
- **Checks:** CI (the `ICON_KINDS` test). **QA:** icons on existing screens look aligned and weighted.
- **Done when:** the PR is open and CI is green.

### Batch 4 — #23: artwork decode (Astra)
- **Outcome:** as the issue describes: measure, then fix what the measurement implicates; B1's size hint with a defaulted parameter.
  - **Excludes:** row layout and modifiers.
- **Files:** `Artwork.kt`.
- **Decisions:** the size parameter's shape (agree with Claude), and whether device measurement is authorised (AGENTS.md).
- **Checks:** CI, plus whatever measurement the user authorises. **QA:** a fast fling through an uncached library.
- **Done when:** the PR is open with CI green and the evidence recorded on #23.

### Batch 5 — #43: three tabs and the Now Playing overlay layout
- **Outcome:**
  - Remove the Playing tab.
  - Now Playing becomes an overlay opened by tapping the mini player, and closed by Back or the collapse button, using the existing tweens.
  - The new layout: flat seek, inline volume with the bubble, Lyrics and Queue row.
  - The mini player restyle.
  - A navigation back stack that later destinations can extend, and a `SnackbarHost`.
  - **Excludes:** drag gestures, predictive back, the artwork swipe (batch 6). The Queue button is hidden until batch 12. Library changes.
- **Files:** the navigation, Now Playing and mini player files from batch 1; `MuonApp.kt`; possibly `MediaVolumeSlider.kt`. `MediaVolumeDialog` is removed.
- **Decisions:** none new.
- **Checks:** CI. **QA:**
  - Open and close three ways.
  - Play, pause, seek and volume.
  - Back from each tab.
  - Large font on a short screen.
  - Error and buffering states.
- **Done when:** the PR is open with CI green and the QA list in the description.
- **Checkpoint:** after navigation compiles, before the Now Playing restyle.

### Batch 6 — #43: gestures and predictive back
- **Outcome:** drag the mini player up and the overlay down on springs, predictive back previewing the collapse, artwork swipe with a haptic at the commit point, and the animator scale honoured.
  - **Excludes:** layout changes.
- **Files:** the overlay and Now Playing files; possibly `AndroidManifest.xml` (`enableOnBackInvokedCallback`); `Motion.kt` for spring specs.
- **Decisions:** Q9, confirming the motion specifics.
- **Checks:** CI. **QA:**
  - Slow and fast drags.
  - Cancelling a drag halfway.
  - Swipe at the first and last track.
  - Predictive back from the overlay.
  - Animations off.
  - TalkBack can use the buttons instead of the gestures.
- **Done when:** the PR is open with CI green.

### Batch 7 — #44: header, switcher, Songs, Playlists and pull to refresh
- **Outcome:**
  - Bold greeting that folds on scroll.
  - View switcher with Songs and Playlists.
  - `PullToRefreshBox` replaces Refresh.
  - Remembered view.
  - Empty playlists hidden.
  - Busy and error states kept.
  - **Excludes:** Albums, Artists and grouping.
- **Files:** the Library screen file; a small preferences holder for the view (Q6).
- **Decisions:** Q5 (chips or segmented buttons) and Q6.
- **Checks:** CI; a unit test for view-preference parsing if added. **QA:**
  - Fold on slow and fast scroll.
  - Refresh success and failure.
  - Empty library, first load and a long playlist name.
  - Large font.
- **Done when:** the PR is open with CI green.

### Batch 8 — #44: Albums and Artists
- **Outcome:** grouping helper with tests, album grid and artist list.
  - **Excludes:** album and artist pages (batch 9).
- **Files:** the Library screen file and the grouping helper.
- **Depends on:** #23 merged.
- **Decisions:** Q3 and B2's data check.
- **Checks:** CI with grouping tests covering blank album, blank artist, shared album titles and multi-artist credits as decided. **QA:** fling the album grid on an uncached library; long names.
- **Done when:** the PR is open with CI green.

### Batch 9 — #45: album and artist pages
- **Outcome:** both pages with Play and Shuffle through `startQueue`, navigation arguments that survive rotation, and destinations reachable from the Albums and Artists views.
  - **Excludes:** the actions sheet.
- **Files:** new page files and navigation.
- **Decisions:** Q10 (hero weight) and track numbering (B2).
- **Checks:** CI. **QA:** Play and Shuffle; an album of unplayable tracks; Back; rotation.
- **Done when:** the PR is open with CI green.

### Batch 10 — #47: queue state and actions (Astra)
- **Outcome:** B3 and B4 as agreed in Q1, Q2 and Q4, with the duration added to media items.
  - **Excludes:** any UI.
- **Files:** a new queue file; `PlaybackService.kt` only as the decisions require.
- **Decisions:** Q1, Q2, Q4.
- **Checks:** CI; unit tests of the pure order and undo mapping. **QA:** covered through batches 11 and 12.
- **Done when:** the PR is open with CI green and the API documented in the PR for Claude.

### Batch 11 — #46: song actions sheet
- **Outcome:** long press opens the sheet with its four actions, a snackbar in the action's own words, and Undo.
  - **Excludes:** Queue screen changes.
- **Files:** a new sheet file and the list call sites.
- **Decisions:** snackbar wording per #46.
- **Checks:** CI; unit test for the wording. **QA:**
  - Each action.
  - Undo.
  - An unplayable track.
  - Shuffled queue.
  - TalkBack long-press action.
- **Done when:** the PR is open with CI green.

### Batch 12 — #47: Queue screen, select, remove and Undo
- **Outcome:** `LargeTopAppBar`, the Now playing card, *Next up* with count and total length, tap to play, swipe to remove with Undo, and the Now Playing Queue button shown.
  - **Excludes:** reorder.
- **Files:** a new Queue screen file and navigation.
- **Decisions:** none new once batch 10 is merged.
- **Checks:** CI. **QA:**
  - Shuffle on and off.
  - Remove the last entry.
  - Undo after the track changes.
  - Duplicate entries.
  - A 500-entry queue scroll.
  - Large font.
- **Done when:** the PR is open with CI green.

### Batch 13 — #47: reorder by handle
- **Outcome:** drag a row by its handle, with a keyboard or accessibility alternative (move up and down actions).
  - **Excludes:** everything else.
- **Uncertainty:** Compose foundation has no built-in reorderable lazy list, so this is hand-built.
- **Files:** the Queue screen file.
- **Decisions:** Q1 (reorder while shuffled).
- **Checks:** CI. **QA:** drag across the fold, drag while a track changes, TalkBack move actions.
- **Done when:** the PR is open with CI green.

### Batch 14 — #48: search bar
- **Outcome:** `SearchBar` expanding to full screen, suggestions at rest, results grouped into Songs and Albums, the same debounce and `key(all)` reset.
  - **Excludes:** recent searches.
- **Files:** the Search screen file; `TrackSearch.kt` if grouping of results is added there.
- **Checks:** CI; tests for grouped results. **QA:**
  - Type fast on a large library.
  - Refresh the library mid-search.
  - Back and clear.
  - No matches.
- **Done when:** the PR is open with CI green.

### Batch 15 — #49: Settings
- **Outcome:** `LargeTopAppBar`, grouped `ListItem` rows, the Connected badge, *Refresh library* with the track count, and the Disconnect `AlertDialog`.
  - **Excludes:** new settings.
- **Files:** the Settings screen file.
- **Decisions:** Q8 (the privacy note).
- **Checks:** CI. **QA:**
  - Cancel and confirm Disconnect.
  - Refresh failure.
  - The pre-Android-12 palette note (by reasoning; no device is available).
  - Large font.
- **Done when:** the PR is open with CI green.

### Batch 16 — #50: Lyrics
- **Outcome:** a collapsing title with the artist subtitle, large left-aligned text, and the not-time-synchronised note.
  - **Excludes:** B7.
- **Files:** the Lyrics screen file.
- **Checks:** CI. **QA:** long lyrics, no lyrics, fetch failure and retry, a track change while open.
- **Done when:** the PR is open with CI green.

### Batch 17 — #39: discovery states and auto-connect (Astra)
- **Outcome:** as the issue describes, exposing B6's named states.
  - **Excludes:** Connect UI.
- **Files:** `ServerDiscovery.kt`, `LibraryModel.kt`.
- **Decisions:** the issue's open questions.
- **Checks:** CI with `ServerEndpointTest` unchanged or strengthened. **QA:** one server, none, and a moved address.
- **Done when:** the PR is open with CI green.

### Batch 18 — #51: Connect
- **Outcome:** mockup 17 bound to B6's states; manual entry always available.
  - **Excludes:** discovery logic.
- **Files:** the Connect screen file.
- **Checks:** CI. **QA:** each discovery state, a manual address, an invalid address, and large font.
- **Done when:** the PR is open with CI green.

## 7. Decisions for the joint session

- **Q1** Queue under shuffle: *Next up* order, reorder, and play next.
- **Q2** The playing item and Undo: removing or moving the playing entry, reinsertion after the timeline changes, and actions on an empty queue.
- **Q3** Grouping: owner, the album key, and whether to split multi-artist credits (unresolved in the handoff).
- **Q4** Duplicate queue entries, and keeping the `mediaId` format.
- **Q5** Chips or segmented buttons for the Library view switcher (open in #40).
- **Q6** Where the remembered Library view is stored: a new preferences file, or `appearance`.
- **Q7** Whether a failed pull to refresh needs its own message when the error card already shows. Currently `ErrorCard` appears above every screen except Settings.
- **Q8** **Conflict:** mockup 15 drops the trusted-LAN note that today's Settings shows. Keep it (for example as a footer), or rely on Connect alone.
- **Q9** **Conflict:** #40's description and #43 specify predictive back and a haptic tick, but `HANDOFF.md` lists motion specifics as unresolved. Confirm, and set spring parameters.
- **Q10** Hero title weight on the album and artist pages.
- **Also:**
  - File ownership in section 4.
  - Batch order changes.
  - Handoff follow-ups 1–4 before #52 merges.
  - #11 and #29.
  - Whether #23 device measurement is authorised.

## 8. Execution protocol for both agents

1. Astra and Claude agree the next batch and its file ownership. **The user authorises** which batch or small group starts.
2. Work only in the authorised scope. **Never continue into the next batch automatically.**
3. Coordinate around concrete decisions, blockers and handoffs. Avoid repeated whole-codebase exploration, and long agent-to-agent discussion with no deliverable.
4. Save a checkpoint after each meaningful slice, and before switching owners, starting expensive validation, or waiting for review.
5. At the batch boundary, report the PR, exact commits, completed and pending checks, and the next proposed batch. Then **pause for the user**.
6. If one agent hits its limit, the other does not take over its role or widen scope. It finishes only authorised independent work, keeps the handoff intact, and reports what is blocked.
7. Never assume an agent resumes by itself when usage resets.

**Where checkpoints live (proposed):** in a *Checkpoint* section of the batch's PR description, replaced each time rather than appended. Before a PR exists, use a comment on the batch's issue. Commits carry the code; nothing is kept only in a conversation.

## 9. Interruption checkpoint template

```markdown
## Checkpoint: batch N, #ISSUE (slice)

- Updated: YYYY-MM-DD, by <model, client, role>
- Branch or worktree: codex/<topic> at <path>; last commit <full SHA>
- Done:
- Remaining:
- Uncommitted changes and why: none | <files, purpose>
- Checks run, with results: <command or workflow: pass | fail, excerpt>
- Workflow runs: <run URL>: running | failed | success | unverified
- Open decisions and blockers:
- Interrupted mid-edit: no | yes, <what state the files are in>
- Next action: <exact step>, owner: Claude | Astra | user
```

Save incrementally, not at a limit warning. Keep commits coherent, with no secrets and no unrelated work. If an interruption happens mid-edit, record it honestly.

## 10. Loading states, and what waits for a later revision

Raised by the user on 2026-09-19: is a skeleton loading system worth building, given that this is a LAN app? **Conclusion: 2.0 keeps the one placeholder that already exists and adds no loading design.** New treatments wait for a revision after 2.0, because there are no mockups for them.

**Where the waits actually are** (verified):

- **The first library load is the only long one.** `LibraryModel.connect()` runs sequentially: a version check, the playlist list, then one `/api1/tracklist/<id>` request per playlist, each parsed as JSON. The cost is the number of round trips and the parsing, not LAN latency.
- **Thumbnail loading**, which the user observed on canary.38. That is #23, not a loading state: `Artwork` already draws a placeholder box while a bitmap is missing.
- **Everything else is in memory.** Search filters `allTracks`, the album and artist grouping in #44 works on the same loaded list, and Queue reads the player. A placeholder on those screens would flash for a frame or two and read as jank.

**In 2.0, preserve rather than design:**

- Keep `PlaceholderRows` for the first library load, through the split in batch 1 and the Library work in batches 7 and 8. Albums and Artists may reuse the same shape in grid form, for the first load only.
- Keep the `Artwork` placeholder. #23 is the real fix for thumbnail lag.
- Pull to refresh keeps the last library on screen. No skeleton on refresh, and no skeleton for Queue, search or switching views.

**Deferred to a later revision, with no mockups today:**

- Skeleton lines for the Lyrics fetch, which is one request and currently shows *Loading lyrics…*.
- A delay of roughly 150ms before any placeholder appears, so a fast LAN never flashes one.
- **Slow, dropped or absent connections.** What the app should show when Tauon answers slowly, stops answering part-way through a load, or cannot be found. Today this is `friendlyError` text in an `ErrorCard`, the `progress` string, and #39's discovery states. **Verified behaviour worth designing for:** if one playlist request fails, `connect()` abandons the whole load, shows the error and keeps the previously loaded library, so a refresh is all-or-nothing.

The loading behaviour underneath the last of these is now tracked as **#53**; its visual treatment still needs mockups. None of this is in #40 or in any batch above.
