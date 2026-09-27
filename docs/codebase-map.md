# Muon codebase map

Orientation for agents working on this repository. It describes where behaviour lives, what must not
break, and where the unverified edges are. It is not a tutorial and not a changelog; keep it current
when the shape of the app changes, not when individual lines do.

## Shape

A single-module Android app. One activity, one Compose tree, one media session. No DI framework, no
navigation library, no repository layer — deliberately, per AGENTS.md.

The UI layer is one file per screen plus a few shared files, all in `dev.avery.muon`. Composables
that are called from another file are `internal`; the rest stay `private`.

```
MainActivity ──binds──▶ PlaybackService (Media3 MediaSessionService)
      │                        │
      │ MediaController        │ ExoPlayer + OkHttp datasource
      ▼                        ▼
   MuonApp ◀──state── LibraryModel (AndroidViewModel)
      │                        │
      │                        └──▶ TauonApi ──HTTP──▶ Tauon desktop (LAN)
      └──▶ tabs: Library │ Search │ Settings, or Connect when there is no server
             └──▶ overlay: Now Playing │ Lyrics, above whichever tab is showing
```

| File | Holds |
|---|---|
| `MainActivity.kt` | Activity lifecycle, `MediaController` binding, edge-to-edge and system bar style |
| `PlaybackService.kt` | The Media3 session and player; owns the queue and survives the UI |
| `ReplayGain.kt` | Volume normalization (#97): reads ReplayGain tags, sets the player's volume, remembers each song's gain |
| `MuonApp.kt` | The shell: three tabs, the Now Playing overlay above them, the scaffold, and queue start |
| `PlaybackState.kt` | `PlaybackUi`, the ticking position, and the player listener |
| `LibraryScreen.kt`, `SearchScreen.kt`, `NowPlayingScreen.kt`, `LyricsScreen.kt`, `SettingsScreen.kt`, `ConnectScreen.kt` | One file per screen |
| `TrackList.kt`, `MiniPlayer.kt`, `Components.kt` | Shared UI: the track list and row, the mini player, and the icon, control, error and busy pieces |
| `LibraryModel.kt` | Connection state, playlists, tracks, busy/error/progress; owns `connection` prefs |
| `TauonApi.kt` | HTTP calls, JSON parsing, response size limits. `Transport` holds the shared OkHttp client |
| `ServerEndpoint.kt` | **Security boundary.** Parses and validates a server address |
| `ServerDiscovery.kt` | NSD lookup of `_tauon-remote._tcp` |
| `Artwork.kt` | Cover fetch, decode at the size it is drawn, and the in-memory `LruCache` |
| `Appearance.kt` | Palette choice and Pure black; owns `appearance` prefs |
| `MuonTheme.kt` | Colour schemes and the black-surface override |
| `MuonTypography.kt` | Google Sans Flex and the Material 3 type roles; see `docs/fonts.md` |
| `MuonIcons.kt` + `res/drawable/ic_*.xml` | The icon set; `iconRes` maps a kind string to a drawable |
| `Motion.kt` | Animation durations and easing |
| `WindowLayout.kt` | When a short, wide window goes sideways: the navigation rail and the two-pane Now Playing |
| `PlaybackModes.kt`, `PlaybackProgress.kt`, `TrackSearch.kt`, `TrackIdentity.kt`, `MediaVolume.kt` | Pure helpers, all unit-tested |

## State ownership

- **`LibraryModel`** survives configuration changes and owns everything about the server and library.
  It is the only writer of the `connection` preferences, and `disconnect()` **clears that whole file**.
- **`AppearanceSettings`** deliberately uses a *separate* `appearance` preferences file for that
  reason. Do not merge them.
- **`PlaybackService`** owns the queue and playback modes. The UI is a view onto it and may be
  destroyed and rebuilt at any time; modes are not persisted across process death.
- **`PlaybackState`** in `PlaybackState.kt` splits event-driven state (`PlaybackUi`) from the moving
  position (`MutableLongState`) on purpose. Putting the position back into `PlaybackUi` would make
  every position tick recompose the whole tree — that was the bug fixed in PR #4.

## Invariants — do not break these

1. **`ServerEndpoint.parse` is the only way an address becomes a URL.** It rejects public, multicast
   and ambiguous addresses. Every network call in the app goes through an endpoint it produced.
   Weakening it widens the app's only attack surface; its tests are the contract.
2. **Tauon's API is unauthenticated and LAN-only.** Never expose it, tunnel it, or add a code path
   that reaches a non-private host.
3. **Package IDs and signing identities are fixed.** `dev.avery.muon` (Canary) and
   `dev.avery.muon.release` (Stable) install side by side and must keep doing so. Never change them,
   the keystores, or the Obtainium update paths to make something else easier.
4. **State must not be signalled by colour alone.** Every toggle carries a shape cue *and* a
   `stateDescription`. This came out of review and applies to anything new.
5. **Response bodies are size-capped** (16 MiB JSON, 4 MiB artwork). Keep the caps when touching
   `TauonApi` or `Artwork`.
6. **Unavailable tracks are never queued.** `startQueue` filters on `playable` before building media
   items.

## Performance notes

The UI has had one real performance regression and one unmeasured complaint. Both are worth knowing
before changing the list.

- **Position ticking** is isolated to the widgets that draw it. The mini player's progress bar reads
  it in the *draw* phase via a lambda, so it never recomposes.
- **Search** is debounced and filtered on `Dispatchers.Default`, wrapped in `key(all)` so results from
  a replaced library are dropped immediately rather than staying tappable.
- **Artwork** is seeded from the `LruCache` during composition, so a row scrolling back into view
  draws instantly. Animations must not undo this — the cross-fade is skipped for cached bitmaps.
- **Track list keys** are track identity, not list index (`TrackIdentity.kt`), which is what makes
  item animation correct.
- **Open and unmeasured: issue #23.** Artwork decodes with `inSampleSize` targeting 1000px into 52dp
  slots, against a 12 MB cache, with a network call per row entering composition during a fling. That
  is the leading hypothesis for the scroll complaint and it predates the recent UI work. It needs
  profiling, not more reasoning.

## What tests actually cover

Unit tests are plain JVM JUnit — no Robolectric, no instrumentation in CI.

- **Covered:** endpoint validation, repeat-mode cycling and icon mapping, volume normalisation,
  search matching and empty-state text, track key identity, palette parsing and the Android-12 gate,
  progress fractions, icon kind mapping.
- **Not covered by anything automated:** every layout, every animation, artwork decoding, Compose
  effect lifetimes, persistence across process death, insets, font scales, and all playback on a
  real device. `DirectPlaybackTest` exists but instrumentation does not run in CI.
- **Consequence:** CI proves the app compiles, its pure logic holds, and its APKs are signed and
  identifiable. It proves nothing about how the app looks or feels. Say so in PR descriptions.

## Open threads

| Thread | Where | State |
|---|---|---|
| Scroll frame pacing | #23 | Astra's. Needs measurement; do not guess at modifiers again |
| Backend correctness audit | #26 | Astra's |
| Chip row overflow affordance | #11 | Partly reverted — the fade cost per-frame work. `e6f059d` on `codex/library-screen` has the exact removal to restore or replace |
| Canary launcher label truncation | #29 | Waiting on a naming decision from the user |
| Spacing and typography tokens | #15 | Best done after screen work settles, or it gets rewritten |
| Insets, landscape, font scales | #16 | Code fixes are cheap; the value is device verification |

## Suggested order for a deeper audit

1. `ServerEndpoint.kt` and `TauonApi.kt` — the security boundary and the only untrusted input.
2. `PlaybackService.kt` and queue construction in `MuonApp.startQueue` — duplicate track IDs resolve
   to the first occurrence, which is a known rough edge.
3. `LibraryModel.connect()` — cancellation, partial loads, and what the UI shows when a refresh fails
   after a successful load.
4. `Artwork.kt` — the decode sizing behind #23.
5. Everything else in the screen files, which is presentation and is best judged on a device.
