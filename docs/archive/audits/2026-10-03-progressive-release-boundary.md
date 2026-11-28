# #179 S3 progressive download release boundary — 2026-10-03

Baseline `6b93a0381083ea1959a7b4e437b40d32f532cf66` on branch `codex/progressive-release-boundary`. It includes main `8dea8cab` and merged PR #293 ([download release boundary](2026-10-03-download-release-boundary.md)). Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Claude authored the investigation/control. GPT-6 / Codex desktop (effort not reported) independently checked the pinned source path and test, and fixed fixture drain ordering and failure cleanup. Those coordinator-authored edits are self-reviewed. Test-only: **no production fix**. No device, network or user data. GitHub Actions is the first compile/run. #179 remains open.

**Question:** with Muon's real download path (`DownloadManager` → `DefaultDownloaderFactory` → `ProgressiveDownloader` → `CacheWriter` → `CacheDataSource` → `TeeDataSource`/`CacheDataSink` → `SimpleCache`), can bytes reach the cache after `DownloadManager.release()` has returned?

## Pinned sources (Media3 1.11.0)

Read with Python's `zipfile`, without modifying the jars. Line numbers count `\n`-separated lines.

| Jar | sha256 |
| --- | --- |
| `media3-exoplayer` sources (`muon-volume-catalog/build/sources/exoplayer.jar`) | `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |
| `media3-datasource` sources (`muon-volume-catalog/build/sources/datasource.jar`) | `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| `media3-common` sources (`muon-resource-inventory/build/media3-sources/common.jar`) | `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e` |

- **`DefaultDownloaderFactory.createDownloader`** (72-92): a URI with no recognised extension gives `ProgressiveDownloader`.
- **`ProgressiveDownloader`:**
  - `download` (146-196) runs `cacheWriter.cache()` in a `RunnableFutureTask` on the supplied executor and waits with `get()`. Its `finally` calls `blockUntilFinished()`.
  - `cancel` (198-205) sets `isCanceled` and calls `downloadRunnable.cancel(true)`.
- **`RunnableFutureTask`** (common):
  - `get()` blocks interruptibly (67-70), but `blockUntilFinished()` is uninterruptible (59-61).
  - `cancel(true)` calls `cancelWork()` (`cacheWriter.cancel()`) and interrupts the work thread (84-102).
  - `run` opens `finished` only after `doWork` ends (117-139).

  So the manager's task thread waits until the cache write has finished, whatever the interrupts.
- **`CacheWriter`:** `cancel` only sets a flag (91-93), and `throwIfCanceled` (231-235) is checked **before** each read in `readBlockToCache`. A read already in progress isn't stopped by it; on the exception, the data source is closed (`closeQuietly`).
- **`TeeDataSource`:** `read` (69-82) writes every byte it gets from upstream to the sink. `close` (96-105) closes the sink after the upstream.
- **`CacheDataSink`:** `open` starts a cache file (185-201, `startFile` at 246). `close` → `closeCurrentOutputStream` (263-283) **commits** what was written (`commitFile` 278) unless the flush fails.
- **`DownloadManager.release`** (504-530, internal 940-955) cancels tasks and returns without joining them (PR #293).

**Source-derived expectation:** if a read is blocked when `release()` runs and later returns bytes, those bytes are written to the sink and committed by the close that follows the cancellation check, **after** `release()` returned.

## Control (`ProgressiveReleaseBoundaryTest`; pending first CI)

**`releaseReturnsWhileTheRealDownloaderIsBlockedAndItsBytesCommitAfterwards`.** Real throughout, except the upstream:
- `SimpleCache`, `DefaultDownloadIndex` and native SQLite in disposable folders;
- `DefaultDownloaderFactory` with a test-owned single-thread executor;
- `DownloadManager` with `Requirements(0)` and `resumeDownloads()`.

A thin `Downloader` wrapper delegates to the real one and only records the task thread.

The **synthetic upstream** `DataSource` opens with a known length of 1,000 bytes. Its first read signals, then blocks, **ignoring interrupts** (10-second deadline), until the test lets it go. It then returns all the bytes; after that, end of input.

**Expected:**

| When | Expectation |
| --- | --- |
| After `release()` returns | the read is still blocked; the task thread is alive; no span is committed; the index says `STATE_QUEUED` |
| After the read is released and both threads are drained | **1,000 bytes committed**, byte-exact; the index still says `STATE_QUEUED` |

Content length and the final span list are printed (`MUON_RELEASE_BOUNDARY`) as observations, not asserted. **CI is the first run.** If it shows something different, record what it shows rather than changing the expectation to fit.

## Cleanup and CI risks

- **Teardown order:** `@After` always lets the read go, calls `release()` (only once the read was reached, which proves the manager's thread runs), then drains: executor `shutdown` plus `awaitTermination`, and the task thread `join`, each bounded to 5 seconds. Both drains are attempted before asserting. The cache/database are released only if the executor has terminated and the task thread is no longer alive.
- **Assumption, same as #293:** Robolectric's paused looper mode runs the manager's `HandlerThread` on a real thread. If it doesn't, the test fails at the 5-second wait, and teardown skips `release()` to avoid hanging.
- **Bounded waits:** they use `nanoTime` and latch or join timeouts; there are no sleeps.
- **If a drain timed out,** this is a fixture failure; teardown refuses to release a cache/database still used by a worker. The disposable harness can retain resources on that exceptional path rather than close them unsafely. No production resources are involved.

## What this does and doesn't show

- **Would show (once CI confirms):** with the real Media3 progressive download components, a write can commit to the cache after `DownloadManager.release()` has returned. So a release-only stop is not a quiescence barrier; the download executor and task threads must be drained first.
- **Not shown:**
  - that Muon's OkHttp upstream ignores interrupts, or how long a real read stays blocked;
  - any card removal, file loss or data loss;
  - the service-owned managers (S4), the `mover`/`copier` executors, or playback.

  The interrupt-ignoring read is synthetic. #179's drain and quiescence design remains unimplemented.

## Final-head CI observations

[#294](https://github.com/averylicious/muon/pull/294) head `e6176b2d315313a6062976c034ef3d02c59e0fd9` passed [run502](https://github.com/averylicious/muon/actions/runs/37117235898): 444 tests per variant, zero failures/errors/skips. Downloaded XML confirms this control executed in both, with after-drain span (0,1000), content length1000 and index QUEUED. Both byte-exact assertions passed. Thus the stated expectation was observed for the synthetic blocked upstream. It does not establish real OkHttp interruption behavior or device/data loss. Merged at `ef8aba57770b9fd5286f8c024d85caf2c1ee4ec0`.
