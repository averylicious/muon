# Main-branch audit

This is the continuation index for the independent main audit tracked in [#181](https://github.com/averylicious/muon/issues/181). Start with the [afternoon checkpoint](../handoffs/2026-10-01-audit-afternoon.md): #265/#269/#271/#272/#274 merged; #257/#264/#267/#273 remain open for their device gates and latest-head checks. Production storage/resource findings remain unresolved. Later PR/#181/#40 results supersede pending states; earlier dated reports are historical evidence.

The user authorized this work on 2026-09-27, alongside Claude and the user's experimental Material 3 work on a separate branch. Either agent may merge a blocker-free PR at its verified head. The audit may follow a finding across any component; the workstreams below organize evidence and handoffs rather than restricting agent ownership. Coordinate actual files before concurrent edits. No Stable release/tag, signing identity change, or phone access is implied.

Earlier evidence: [night-time checkpoint](../handoffs/2026-10-01-audit-close.md), [storage invariants/slices](2026-10-01-storage-preservation-design.md), [card service helper](2026-10-01-card-service.md), [card index](2026-10-01-card-index.md), [metadata payload](2026-10-01-metadata-payload.md), [source continuation](../handoffs/2026-10-01-source-audit.md), [progress/parked PRs](2026-09-30-progress.md), [build-crypto applicability](2026-10-01-build-crypto.md), [public readiness](2026-09-30-public-readiness.md) and [network entry points](2026-09-30-network-entry-points.md). These are dated inspections, not promises that every later revision is covered.

Latest continuation: [October2 checkpoint](../handoffs/2026-10-02-main-audit.md), [resource inventory](2026-10-02-resource-budget-inventory.md), [volume identity evidence](2026-10-02-volume-catalog-evidence.md), and [partial-target preservation](2026-10-02-partial-target-preservation.md). Resource/volume/move characterizations are evidence, not production fixes or measured performance. Open app fixes and manual gates are listed in the checkpoint and live PRs.

## Repository map

Production Kotlin paths below are relative to `app/src/main/java/dev/avery/muon/`. Corresponding pure-logic tests are under `app/src/test/java/dev/avery/muon/`. A test's existence is not proof of runtime coverage.

| Area | Entry points and related files | Important boundary |
| --- | --- | --- |
| App and lifecycle | `MainActivity`, `MuonApp`, `LocalNetwork`, `Appearance`, `LibraryPreferences`, `PlaybackModePreferences` | Activity/controller lifetime, permission refusal, connection changes, saved state |
| Tauon and discovery | `ServerEndpoint`, `TauonApi` / `Transport`, `ServerDiscovery`, `DiscoveryState`, `LanProbe`, `ConnectScreen` | Unauthenticated LAN HTTP, hostile/malformed responses, stale discovery, cancellation |
| Playback and queue | `PlaybackService`, `PlaybackState`, `PlaybackProgress`, `PlaybackModes`, `QueueModel`, `QueueShuffleOrder`, `ReplayGain`, `MediaVolume` | External controllers, URI validation, queue occurrence identity, focus/lifecycle and gain |
| Offline storage | `OfflineStore`, `OfflineDataSource`, `OfflineDownloads`, `MuonDownloadService`, `PlayedCache`, `DownloadArt`, `OfflineUi` | File/cache/index consistency, storage removal, full disk, move/delete, retained personal data |
| Library data | `LibraryModel`, `LibraryLoad`, `LibraryGroups`, `LibraryAlbums`, `LibraryArtists`, `LibrarySorting`, `TrackIdentity`, `TrackTitle`, `TrackCredits`, `TrackAlbumMetadata`, `TrackSearch` | Partial refresh, server identity, grouping/deduplication, untrusted metadata |
| Library navigation | `LibraryScreen`, `LibraryPages`, `AlbumScreens`, `ArtistPage`, `SearchScreen`, `TrackList`, `SongActions`, `QueueScreen`, `LyricsScreen` | Async results, navigation identity, scroll state, selection/action consistency |
| Artwork | `Artwork`, `ArtworkCache`, `ArtworkSwipe`, `ArtworkTheme`, `DownloadArt` | Response/decode limits, cache keys and eviction, cancellation, corrupted images |
| Player interaction | `MiniPlayer`, `MiniPlayerDrag`, `PlayerPanel`, `PlayerDismissDrag`, `PlayerBack`, `NowPlayingScreen`, `NowPlayingBars`, `MediaVolumeSlider` | Interrupted gestures, Back/drag coordination, accessibility, lifecycle resets |
| Presentation | `MuonTheme`, `MuonTypography`, `MuonIcons`, `Motion`, `SharedMotion`, `Components`, `AlphabetScroller`, `ScrollIndicator`, `WindowLayout`, `SettingsScreen` | Resource/alpha API compatibility, input/semantics, large fonts, main-thread work |
| Build and release | `.github/workflows/`, `tools/ci_scope.py`, `tools/publish-release.py`, `tools/verify-apks.py`, Gradle files, `baselineprofile/` | Trusted writers/signing, dependency integrity, artifact provenance, update channels |
| Platform privacy | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml` | Exported components, network policy, backup/transfer, stored metadata |

## Workstreams for later sessions

Choose a concrete question and leave a usable report or PR before taking on more. One window can finish part of a workstream; no fixed number of tasks fits every usage allowance.

1. **CI and release trust:** build/publication eligibility, tag/release failure recovery, secret-bearing jobs, action/Gradle dependency verification, branch policies, experimental-branch artifact/update behavior. First-pass CI selector fix is in the baseline report; a complete supply-chain audit is still open.
2. **Network and entry points:** all Tauon/Media3/artwork URL paths, controller permissions, response-size and time limits, cancellation, permission-denied discovery. Reproduce the known fresh-install scan problem separately from the subnet fix. First pass done 2026-09-30 ([report](2026-09-30-network-entry-points.md), [checkpoint](../handoffs/2026-09-30-network-audit.md)): source-confirmed media-button entry and queue-clearing external requests in `PlaybackService`; endpoint and request bounds held. Fresh-install discovery and device confirmation remain open.
3. **Offline consistency:** SD insertion/ejection/reinsertion, manager/cache lifecycle, partial downloads and failures, storage exhaustion, move/copy/delete ordering, offline fallback. Start here next because source inspection found a concrete lifecycle gap.
4. **Playback reliability:** queue/shuffle/repeat mutations and duplicates, gain changes, interrupted playback, audio focus, reconnect, process death. Preserve documented limitations until deliberately implemented; source tests do not prove audibility or system behavior.
5. **Library and UI correctness:** refresh races, endpoint changes, empty/partial data, artwork resource limits, navigation/selection state, gesture cancellation and accessibility. Coordinate experimental frontend overlap, but main fixes are not limited to backend files.
6. **Measurement and release readiness:** aggregate unresolved findings, dependency advisories against the actual resolved graph, user QA and controlled performance checks. Baseline Profile timing remains #83. Device measurements require new explicit authorization; no Stable promotion is part of the audit itself.

## Evidence and resumability

For each finding record: inspected SHA, file/symbol, trigger, actual/expected behavior, impact, source evidence or reproduction, confidence/unknowns, fix/test/PR links, and remaining manual checks. Distinguish a reproduced defect from a source-supported risk and a question awaiting investigation. Avoid claiming the whole app is secure because one review or CI run passed.

Before handoff, record exact branch/head, dirty files, processes/agent assignments, final-head checks and publication result. Put the next task here or in the linked issue; keep private logs, sessions, quotas and secrets out of the repository. Another agent should need only repository access, not the prior local session. If a usage reading is unavailable, say so; never borrow another account's percentage. Reserve time for fixes and this checkpoint.

Experimental work stays off main until intentionally reviewed for main. Currently successful app-changing main builds still publish the existing Canary feed. Until that policy is deliberately changed, an experimental branch produces Actions artifacts only, and installing one uses the existing Canary package/data. A later main APK can have a higher version code yet older feature content; do not automatically tell users to install it over experimental work.
