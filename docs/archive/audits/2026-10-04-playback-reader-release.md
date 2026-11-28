# #179 playback-reader release acknowledgment — 2026-10-04

Main `c8d4edebeaeb6b9b675618530db3693e6477cea2`; acceptance head `78b1e13b4a726f2c1ce7af93166d1936c8ffea60`. GPT-6 / Codex desktop, effort not reported: source investigation/self-review. Extends [service/reader stop contract](2026-10-04-service-drain-contract.md). No production change, test execution, device access or live stuck-reader/data-loss reproduction.

Pinned Media3 exoplayer1.11.0 published sources SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`, read directly from source JAR. Lines count newline characters.

## Actual acknowledgment boundary

| Path | What the source establishes |
| --- | --- |
| `ExoPlayerImplInternal.release`658–665 | Waits with a release timeout for the playback-thread processed condition. |
| `releaseInternal`2053–2068 | Calls reset/release-source/renderers and opens that processed condition in finally. It does not wait directly for each progressive loading worker. |
| `ProgressiveMediaSource.releasePeriod`461–462 | Calls ProgressiveMediaPeriod.release. |
| `ProgressiveMediaPeriod.release`263–275 | Requests loader release with itself as callback, removes period handler callbacks and marks released; it does not synchronously join the loader. |
| `Loader.release`328–335 | Cancels the current task, queues a ReleaseTask on the loading executor, then releases that executor. |
| Default `Loader`236–240; `ReleasableExecutor`33–54 | Wraps a single-thread executor with ExecutorService.shutdown. The release contract explicitly permits pending/executing commands; its wrapper invokes the release callback without awaiting termination. |
| `LoadTask.cancel`421–438 | Calls loadable.cancelLoad and interrupts its loading thread; cancellation may still be ongoing after release. Clears its callback reference rather than waiting. |
| `ProgressiveMediaPeriod.ExtractingLoadable.cancelLoad`1291–1293 and load finally1354–1356 | Cancellation sets a flag. DataSource close is in the loading call's finally, after control returns from extraction/read. |
| `ReleaseTask.run`574–576; period.onLoaderReleased278–282 | Queued completion callback runs on the loading executor and then releases sample queues/extractor. It is a different event from playback-thread release acknowledgment. |

`ProgressiveMediaPeriod`240–243 selects that default Loader when no executor is supplied. Inspected Muon PlaybackService configures DefaultMediaSourceFactory/OfflineStore source and does not supply a replacement loading executor. This is source configuration, not an instrumented runtime executor dump.

**Confirmed API distinction:** return from player release, period release or executor shutdown is not a cache-reader drain receipt. A cancel flag/interrupt does not by itself establish that an existing read and its finally/close have exited. The source permits overlap; it does not show that a real Muon file read remains blocked or loses data on a phone. A public loader release callback is later than current work in the default serial executor, but Muon has no generation-bound route exposing that receipt to its cache owner.

## Safe next bounded control

Use the real Loader with a test-owned serial ReleasableExecutor and a deterministic synthetic Loadable held through interruption. Establish release returns while that invocation is held; its ReleaseCallback must not fire until the task exits. Include cancellation-before-start and cleanup with bounded waits. Then test actual OfflineDataSource/CacheDataSource close ownership separately before production wiring. Do not call a synthetic Loadable a real blocked cache/file/network reproduction.

A production cache owner needs generation leases covering source construction/open through completed close (including failed open/cancel), plus the separately characterized downloader/service/mover/copier/callback drains. Timeout grants no capture, release, reuse or recovery permission. Do not release an absent-card cache or treat this report as a durable catalog/adoption fix. #179 remains open.
