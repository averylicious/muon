# Retained audio identity investigation

Main baseline `792df8c2d9ad09938630e9b516b9b90bbf536ca7`, issue #213. GPT-6 / Codex desktop; effort not reported. No device access or retained-data deletion.

The existing route is extracted to `routeOfflineRequest` for disposable actual Media3 cache/index tests. `hasPlayedCopy` retains the length/span check. Explicit download precedence, offline-only played copies, key formats, shelf order and saved records remain unchanged. `playedCopy` retains initialization failure as a miss.

`RetainedIdentityCharacterizationTest` seeds identifiable bytes A into real SimpleCache/native SQLite DefaultDownloadIndex, with a paused DownloadManager supplying Shelf's index. A live song B reusing origin/ID still routes through OfflineDataSource to A's retained bytes. Its media extras describe B, its progressive custom cache key is null, and the index describes A. Matching downloads, offline played copies, online-original preference and cross-origin separation are covered. The mismatch assertions characterize the existing bug; passing is not an identity fix, phone proof, or real Tauon rebuild. Exact-head CI evidence belongs on the PR.

UI marks and DownloadAll/add/remove use origin/ID too; DownloadArt's retained cover uses that same ID. A routing-only fix leaves incorrect badges/actions. Played copies and remembered ReplayGain also need identity review. No migration is implemented.

## Upstream evidence

Installed Tauon 12.1.0-1.1 `t_modules/t_webserve.py`, get_track lines 589-621, SHA-256 acfd2181989c7e634d01347e608409f40a6a854b9acf0a63c215e878906bead4. This API1 record returns numeric index, full path and metadata, not a content hash, stable UUID, file size or modification time. t_main.py allocates master_count and resets it on library clear. The separate web-client get_track_id hashes index/title/artist and is not this API1 field or a content fingerprint. This does not rule out stronger identity on every Tauon route/version.

Pinned published Media3 source checked: ProgressiveMediaSource.createPeriod forwards localConfiguration.customCacheKey; ProgressiveMediaPeriod.ExtractingLoadable.buildDataSpec forwards it to DataSpec. Metadata extras do not reach this route. Download, DefaultDownloadIndex, DownloadManager and CacheDataSource supply the fixture contract. Sources: Google Maven media3-exoplayer/1.11.0 and media3-datasource/1.11.0 -sources.jar.

## Preservation-first follow-up

1. Specify one identity contract across media items, downloads, played copies, marks/actions, covers and gain; keep queue occurrence IDs separate. Metadata mismatch can prevent silent substitution but does not prove corrupt/different bytes or authorize deletion.
2. Preserve A's saved-record offline access when live B reuses the ID. Make replacement/removal explicit; a live-stream bypass is only containment without coherent actions.
3. Version the retained record, possibly digesting path/metadata rather than storing raw server paths. A path is a discriminator, not a fingerprint. Legacy records lack paths; do not rewrite/delete them speculatively.
4. Test legacy restart, edited tags, identical tags/different paths, unknown metadata, explicit/played copies, moves, removal and failed replacement. Future fix tests must assert the new behavior rather than relabeling characterization as a fix.
5. Obtain manual QA before a behavior-changing merge. #179 removable-storage ownership/preservation remains separate.

#213 stays open. No key rewrite, deletion or phone testing occurred.
