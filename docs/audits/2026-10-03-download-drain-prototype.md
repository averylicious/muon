# #179 S3 download drain prototype — 2026-10-03

Baseline main `1f78d6db6a6712666fa216964d35d87f524c9e58`, branch `codex/download-drain-prototype`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author prototype and self-check, **not independent review**.

**Test-only prototype, not a production fix.** No production code, dependency or refactor change, and no device, network or user data. #179 remains open. Storage and network/queue/library candidates #289/#290 still await user acceptance.

**Question:** can a small, supported contract close **downloader write admission** and confirm that every admitted invocation has finished before a cache capture, using Muon's real Media3 download path?

## Pinned sources (Media3 1.11.0, per `app/build.gradle.kts` 82-84)

Read with Python's `zipfile`, without modifying the jars. Line numbers count `\n`-separated lines.

| Jar | sha256 |
| --- | --- |
| `media3-exoplayer` sources (`muon-volume-catalog/build/sources/exoplayer.jar`) | `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |
| `media3-datasource` sources (`muon-volume-catalog/build/sources/datasource.jar`) | `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| `media3-common` sources (`muon-resource-inventory/build/media3-sources/common.jar`) | `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e` |

**Source-confirmed:**
- **The manager stops, but doesn't wait for, its download threads:**
  - `DownloadManager.release` (504-530; internal 940-955) cancels tasks without joining them.
  - `Task.run` (1333-1369) calls `downloader.download`/`remove`. It retries `IOException` with `Thread.sleep` (1345-1356), and records any other exception as `finalException` (1362-1363).
- **The downloader waits for its own cache write:** `ProgressiveDownloader.download` (146-196) submits a `RunnableFutureTask` with `executor.execute` (172), and its `finally` calls `blockUntilFinished()` (188-195), which is uninterruptible (`RunnableFutureTask` 59-61). The runnable opens `finished` only after `doWork`, the cache write including its commit, ends (117-139).

  So when `download()` returns, that downloader's cache write has finished. The merged #294 control (run 502) observed the late commit, span (0, 1000), before the drain ended.
- **Executor trap (source-confirmed; timing hypothetical):**
  - If the executor has been shut down, `executor.execute` (172) throws before the runnable starts, and the `finally` calls `blockUntilFinished()` on a runnable that never ran.
  - `finished` opens without running only when `cancel()` happened while no work thread existed (`RunnableFutureTask.cancel` 84-102).
  - So shutting the download executor down while a `ProgressiveDownloader` may still submit can block that thread indefinitely unless it was cancelled first.
  - Whether that interleaving happens in practice wasn't tested; the prototype keeps the executor open until the gate has drained.
- **A refusal is visible to a live manager:** in `onTaskStopped` (1091-1132), a task the manager hasn't cancelled is handled as follows:
  - a refused **download** becomes `STATE_FAILED` (`onDownloadTaskStopped` 1134-1157);
  - a refused **remove** still **deletes the index row** (`onRemoveTaskStopped` 1159-1172), even though no bytes were removed.

  So a downloader-level gate **must not be the only command boundary while a manager is live.** Manager commands must stop first: release or pause, with cancellation. This is source reading only; this prototype doesn't exercise it.
- **Muon's wiring:** `OfflineStore.shelf` passes an inline `Executors.newFixedThreadPool(2)` (`OfflineStore.kt` 85) and keeps no handle to it ([upstream ownership report](2026-10-03-download-upstream-ownership.md)).

## Control (`DownloadDrainPrototypeTest`; CI pending, first compile)

**`closedAdmissionDrainsTheAdmittedRealDownloadBeforeCaptureAndRefusesLateWork`.**

**Setup:** the real `DownloadManager`, `DefaultDownloaderFactory` (with a test-owned executor), `ProgressiveDownloader`, `CacheWriter`, `SimpleCache` and native SQLite, in disposable folders. A **test-local `AdmissionGate`** wraps the real factory:
- `close()` refuses later invocations atomically. A refusal throws `AdmissionClosed`, a `CancellationException`, before the real downloader is created or the cache is touched.
- Each `download`/`remove` is counted from admission until the real downloader returns.
- `awaitDrained` returns true only when nothing admitted is still running; a timeout returns false and grants nothing.

The upstream is the synthetic interrupt-ignoring read from #294.

**Sequence and expectations:**
1. **Admit:** a real download is admitted, and its read blocks.
2. **Close, then release:** close admission, then `manager.release()`, which cancels the admitted task.
3. **Not drained yet:** the gate isn't drained and the read is still blocked. A 100 ms bounded wait returns false, and **no capture is taken**. That result is fixed because the test holds the read.
4. **Drain, then capture:** let the read return, wait (bounded) until drained, then capture. The expected capture is span (0, 1000), the exact bytes, and content length 1000, consistent with run 502.
5. **Late work refused:** a `remove()` on the old admitted downloader, and a `download()` and `remove()` on a new one, are all refused (3 refusals). The real factory was used only once, and a recapture equals the first capture.

**Cleanup order:** let the read go, release the manager (only once its thread has shown it runs), wait for the gate to drain, then shut the executor down and await it, and join the task thread. Only when all of these have succeeded are the cache and database released. A failed drain leaves them open rather than closing resources under a worker. There are no sleeps; waits are latches, joins and lock conditions with deadlines.

## Smallest production adoption boundary (proposed, not implemented)

Per shelf generation:
1. **Own the gate and executor:** an admission gate wrapping that shelf's `DefaultDownloaderFactory`, plus the shelf's download **executor, retained** rather than created inline.
2. **Stop in this order:**
   - stop manager commands (release, or pause with cancellation) so refusals aren't recorded as failures or removals;
   - close the gate;
   - wait for it to drain, bounded;
   - only then shut the executor down and await it.
3. **On timeout:** the generation stays **unavailable**: no capture, release or reuse.

This covers downloader cache writes only.

## Not covered or unverified

**Not gated by this prototype:**
- manager index commands, and the race between a live manager and a refusal (above);
- DownloadService helper reuse (S4);
- playback reads and cache touches;
- the `mover`/`copier` executors and `watch`/main-thread completions;
- callback generations;
- absent-cache release;
- an atomic or durable catalog, and migration.

**Unverified:**
- real OkHttp body-read timing (synthetic upstream);
- Robolectric running the manager's `HandlerThread` (same assumption as #293/#294);
- the executor-rejection interleaving.

**No claims about** missing cards, data loss, heap or performance. CI is the first compile and run.
