# Actual native download lifetime — 2026-10-11

## Ownership

Solo GPT-6 / Codex desktop implementation/self-check; exact variant/effort not reported. No Claude or phone commands this cycle. Dedicated `codex/native-download-lifetime-oct11` worktree `~/.codex/worktrees/muon-native-download-lifetime-oct11`, targeting main. Includes main `2cfb4a2c0570bb18bf206123eafa0f7c517c53c5` and cumulative OPEN #484 `fbf4604b75cc5b4988457ac6dcc9132b42ff44aa`. #482 and #483 have verified final-head CI/APK receipts on their PRs. #484 verification pending at preparation.

## Engineering

`OfflineStore.shelf` now constructs `LegacyDownloadFactory` for actual production legacy manager tasks. A downloader acquires the store's shared permit on its real manager task thread, not at command submission or a completion notification. It uses Media3's supported direct progressive executor, eliminating the extra two-thread native-download executor and any separately submitted I/O lifetime. Normal progressive/ranged audio behavior is retained; adaptive formats are not supported by Muon's dependencies.

The native file/upstream/sink participants share `ClosureTracker`. Known closes release the permit; a close failure, swallowed close, suppressed reentry or failed component construction retains the exact native task/tracker/factory participants in the quarantined permit. New admission, retries and removals then refuse. Production also quarantines the played evictor on native uncertainty so active native cache callbacks cannot evict those resources. Cancellation waits for the established native work to finish closing; pre-start cancellation does not call the pinned native cancel path before its worker exists.

The tracker now retains exact factories during construction and actual returned participants afterwards (at most three). Reentrant close cannot become proof of closure when a callback suppresses its inner exception. Prepared partition downloads use the same strengthened tracker; partition mode remains disabled.

Eleven added controlled native tests cover real cache-hit/download/byte-range fallback, failed reads, unknown upstream/sink closure, failed construction, cancellation, exclusive refusal and tracker reentry/retention. Published pinned Media3 1.11.0 `ProgressiveDownloader`, `DefaultDownloaderFactory`, `CacheWriter`, `CacheDataSource` and `CacheDataSink` sources were read from Google's Maven. No local Android build; CI is first compile. Exact-head CI/report/APK receipts will be on the PR.

## Remaining limits

This wrapper is not pre-manager destructive admission: rejecting a manager remove task cannot by itself preserve its row. Existing destructive command policy remains, and comprehensive migration routing still needs integration. Legacy native cache/provider ownership, startup scans/index/census/move/removal lifetimes, production partition startup, opt-in/progress/cancel/restart recovery remain engineering. A retained participant object does not prove its opaque native internals closed; uncertainty deliberately blocks every live migration and requires process restart, never a retry close.

Application PRs remain OPEN under inherited acceptance. No Stable tag/release, original-data edits, experimental checkout changes or device testing. POCO becomes useful for end-to-end opt-in/restart checks after production controls are implemented; current tests do not require it.
