# #179 S3 download release boundary — 2026-10-03

Inspected main `8dea8cabf7089614acfaa6e07e7d4e6336f6b80d`, branch `codex/download-release-boundary`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Claude authored the investigation and test. GPT-6 / Codex desktop (effort not reported) independently checked the test and pinned source contract, then integrated #292 for combined CI. This source review does not establish runtime behavior. Test-only: **no production fix**. No device, network or user data; GitHub Actions is the first compile. #179 remains open, and the existing app candidates are still awaiting user QA.

Follows the [writer-boundaries report](2026-10-03-cache-writer-boundaries.md) (PR #292), whose limit 2 said `DownloadManager.release` doesn't join running downloads.

**Question:** when `DownloadManager.release()` returns, has the download thread finished?

## Pinned source (Media3 1.11.0, `media3-exoplayer` sources)

`muon-volume-catalog/build/sources/exoplayer.jar`, sha256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`, read with Python's `zipfile` without modifying it. Line numbers count `\n`-separated lines.

- **Paused at start:** the `DownloadManager` constructor starts with `downloadsPaused = true` and default network requirements (237-274, line 244; `DEFAULT_REQUIREMENTS` at 158).
- **What `release()` waits for:** it sends `MSG_RELEASE` and waits only for the internal handler's `released` flag (504-530). Its javadoc says it waits until downloads are persisted to the index.
- **What the internal release does:** `InternalHandler.release` (940-955) calls `task.cancel(true)` for each active task, then `setDownloadingStatesToQueued`, quits its thread and notifies. **It doesn't join task threads.**
- **What cancelling a task does:** `Task.cancel(true)` (1316-1329) clears its handler reference, calls `downloader.cancel()` and `interrupt()`. Its comment says download threads stay alive while cancellation completes, and that the time this takes "depends on the implementation of the downloader".
- **`Task.run`** (1333-1369) calls `downloader.download(this)`. Once it returns, the task reports back only if its handler reference is still set, which after release it isn't.
- **The `Downloader` contract** (`Downloader.java` 47-74): once cancelled, `download` is "expected" to return "reasonably quickly", with "no guarantees about how the method will return". The caller should interrupt the thread after `cancel()`.

**Source-backed:** `release()` doesn't wait for download threads to end. Whether a thread is still running afterwards depends on the downloader.

## Control (`DownloadReleaseBoundaryTest`; CI pending, first compile)

**`releaseReturnsAfterRequestingCancellationWithoutWaitingForTheDownloadThread`.** The setup is real throughout, except for the downloader:
- a real `DownloadManager`;
- a `DefaultDownloadIndex` on native SQLite;
- the public `DownloaderFactory`;
- `Requirements(0)`, so no network is involved;
- `resumeDownloads()`;
- Robolectric's paused looper (the manager's internal thread and task threads are real threads).

The **downloader is test-controlled**. It signals when `download()` is entered, records `cancel()`, and stays in `download()` through cancel and interrupts until the test opens a separate latch. It has a 10-second deadline, so a failed test can't hang.

**Expected:**
- **After `release()` returns:**
  - `cancel()` was called;
  - the index row is `STATE_QUEUED`, because release persisted it;
  - **the task thread is still alive**, and `download()` hasn't returned.
- **After the test opens the latch:** the thread ends within a bounded join, and `download()` has returned.

**Cleanup:** `@After` always opens the latch, releases the manager (it's idempotent), joins the worker and asserts it ended, all before closing the database, even if an assertion failed. There are no sleeps or busy-waits; the only waits are latch and join timeouts.

**Harness assumption (unverified):** the test assumes Robolectric's paused looper mode runs the manager's background `HandlerThread` on a real thread. Robolectric 4.16.1's sources weren't inspected in this slice.
- **If it doesn't,** the test fails at the 5-second `started` wait. Teardown then skips `release()`, which would otherwise wait forever on that thread, so the run stays bounded.
- **The trade-off:** that failure path leaves the manager's thread unreleased.

## What this does and doesn't show

- **Source-backed; runtime control pending CI:** with a downloader whose cancellation is asynchronous or blocked in I/O, `release()` can return while the download thread is still inside `download()`. So a quiescence step that only calls `release()` doesn't prove that thread has stopped writing.
- **Not shown:**
  - that Muon's real `ProgressiveDownloader`/`CacheWriter` keeps writing after release, or for how long;
  - any cache or file change after release, or any data loss;
  - device behaviour, or service-owned managers (S4).

  A real `CacheWriter` checks for cancellation and is interrupted, so it may stop quickly. That wasn't measured.
- **Not addressed:** pausing, the `mover` and `copier` executors, playback reads, and main-thread completions. These stay as listed in the writer-boundaries report.

## Next step (not started)

Characterize the real `DefaultDownloaderFactory`/`ProgressiveDownloader` with a deterministic in-process blocking upstream `DataSource`, not HTTP. It would show whether a cache write can follow `release()`. #179's drain and quiescence design remains unimplemented.
