# #179 HTTP downloader invocation boundary — 2026-10-05

Base main `46f13c8fce6b428085b85436938326f46033bbd2`, branch `codex/http-downloader-boundary`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Test and report by the author, **not independent review**. **Test-only:** no app, build or dependency change, no device, no external network. Not compiled locally: Android CI is the first Kotlin and Robolectric compile and run, and it is pending. #179 remains open.

**Question:** a real downloader invocation (`download()` returning) is a different event from `cancel()` or `release()` returning. Does the actual pinned stack close its HTTP source and cache sink **before** `download()` returns, when it is cancelled during the header wait, or when a held partial body ends?

## Sources

Media3 1.11.0 and OkHttp 4.12.0 (`app/build.gradle.kts` 82-87). Published Google Maven sources fetched by the coordinator into `build/audit-sources/` (ignored), SHA256:

| Jar | SHA256 |
| --- | --- |
| `media3-exoplayer` | `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |
| `media3-datasource` | `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| `media3-datasource-okhttp` | `e90957224aa28b7602604a0a5543f36e81570ca952185a4ae5538aa430768cdc` |
| `media3-common` | `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e` |

The extracted `.java` copies were read. Symbols relied on:
- **`ProgressiveDownloader`:**
  - constructor with `Executor`;
  - `download`: a `RunnableFutureTask` runs `cacheWriter.cache()`; `get()`; then `blockUntilFinished()` in `finally`;
  - `cancel`: sets `isCanceled`, then `RunnableFutureTask.cancel(true)`.
- **`RunnableFutureTask`:**
  - `cancel` calls `cancelWork` and interrupts the running worker;
  - `get` blocks interruptibly and throws `CancellationException` if canceled;
  - `blockUntilFinished` is uninterruptible.
- **`CacheWriter`:**
  - an unbounded open when the length is unknown;
  - `throwIfCanceled` between reads;
  - `closeQuietly(dataSource)` on any failure.
- **`CacheDataSource.createDataSourceForDownloading`** and `closeCurrentSource`.
- **`TeeDataSource`:** opens upstream first, then the sink; closes upstream, then the sink.
- **`OkHttpDataSource.executeCall`:** an interrupted wait calls `call.cancel()`.

## Fixture (`HttpDownloaderBoundaryTest`, new; CI pending)

**The stack, all actual:** `ProgressiveDownloader` → `CacheWriter` → `CacheDataSource` → `CacheDataSink`, `SimpleCache` and native SQLite → `OkHttpDataSource`. It runs on a test-owned single-thread executor, over a peer bound to `127.0.0.1` on an ephemeral port that parses at most 64 request lines.

**What is test-owned:**
- **Wrappers:** the upstream and sink are wrapped **only to record calls**. They delegate every operation, so the socket behaviour is the real JVM's.
- **Caller:** a test-owned thread stands in for `DownloadManager`'s task thread. No manager, generation or card is involved.

**Setup:** the native cache is initialized before the peer starts its 5 s accept window. The `SimpleCache` reference is kept before `checkInitialization`, so a failed initialization still leaves it for teardown to release.

**Teardown:**
1. Stop the peer, so any read it holds ends.
2. Cancel every downloader and join every caller.
3. Only then shut down the executor. It must stay usable until every `download()` has returned, because a refused submission would leave `blockUntilFinished` waiting forever.
4. Drain the OkHttp dispatchers.
5. Release the cache and database only if every caller and the executor finished, and every opened source and sink recorded a returned close.

## Cases

1. **`cancelDuringTheHeaderWaitCancelsTheRealCallAndClosesTheSourceBeforeDownloadReturns`:** the peer has the request and withholds every header, and `downloader.cancel()` is called.
   - **Cancellation:** the real `Call` is canceled (`EventListener.canceled`, `isCanceled`). The open fails with `TYPE_OPEN` caused by a plain `InterruptedIOException`.
   - **Close and return:** the source's close returned, no sink was opened, and the source close is recorded **before** `download()` returned, which reports `CancellationException`.
   - **Worker:** the executor terminates.
   - **Peer:** it never sent a response, so nothing but cancellation ended the wait.
2. **`aHeldPartialBodyTimesOutAndClosesSourceAndSinkBeforeDownloadReturns`:** no cancel. One of two body bytes arrives, and only the configured 1 s JVM read timeout can end the held read.
   - **Failure:** `download()` throws `TYPE_READ` caused by `SocketTimeoutException`.
   - **Close order:** the source and sink closes both returned before `download()` did, and the executor terminates.
   - **Peer and cache:** the peer still holds the last byte. Exactly one byte (`42`) is committed in a single span at position 0, and `isCached(0, 2)` is false.
3. **`cancelAndInterruptAfterTheFirstByteStillReturnOnlyAfterSourceAndSinkClose`:** the first byte has been received, and the wrapper's next read has been entered. The test calls `downloader.cancel()` and then interrupts the caller, as `DownloadManager`'s task cancel does.
   - **Read timeout:** a generous 5 s, waiting up to 15 s for the caller. That makes it unlikely that scheduling delay lets the timeout win before the cancel, but the latches don't bound scheduling, so the ordering isn't guaranteed. If the timeout did win first, the caller would report the read error and the case would fail visibly rather than pass on a false premise.
   - **Caller:** it sees `CancellationException` or its own `InterruptedException`.
   - **How the read ended:** by interruption or by the configured timeout, never by the peer. The test **prints which**, and doesn't assert it.
   - **Close order:** the source and sink closes return before `download()` does, and the executor terminates. The peer still holds its byte, and nothing is cached as complete.

## What this shows, and what it doesn't

- **Shown, if CI passes:** for these paths, `download()` returning **does** follow the closes of the actual source and sink. `blockUntilFinished` makes the caller wait for the worker even when the caller is interrupted. Returning from `cancel()` alone is only a request.
- **Not shown:**
  - **The read state in case 3:** entering the wrapper's read doesn't show the socket read is natively blocked, and which way the read ended is timing-dependent.
  - **Platforms:** Android or Conscrypt sockets, TLS, HTTP/2, or any device timing.
  - **Releasing a manager:** `DownloadManager.release` still doesn't join its task threads, so a stop coordinator must take this receipt itself (for example, by joining the task thread).
  - **Recovery permission:** nothing here permits manager, generation or physical card recovery.

**Checks run locally:** `git diff --check` and the CI prose check. No Gradle or Robolectric run. No phone QA is needed for this test-only change.
