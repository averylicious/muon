# HTTP body read: interruption, cancellation and completion — 2026-10-05

Inspected main `bd2918623ea63ae108dc658807548184446df3cd`, branch `codex/http-body-drain-source`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading by the author, **not independent review**. Documentation only: no app or test change, no network, no device. This narrows the unknown left by the [download upstream ownership](2026-10-03-download-upstream-ownership.md) report. #179 remains open, and no new defect is claimed.

## Sources (published, read-only; line numbers count `\n`)

**Fetched by the coordinator:**

| Source | SHA256 |
| --- | --- |
| `com.squareup.okhttp3:okhttp:4.12.0` sources | `d91a769a4140e542cddbac4e67fcf279299614e8bfd53bd23b85e60c2861341c` |
| `com.squareup.okio:okio-jvm:3.6.0` sources | `870a42a7b468af8fd24d67b7b62e5861aab398c56f15a80b10642e7001564e2f` |

The okhttp 4.12.0 POM declares okio **3.6.0**. The okio version that CI actually resolves is **not verified** here.

**Read earlier:**

| Source | SHA256 |
| --- | --- |
| Media3 1.11.0 `datasource-okhttp` | `e90957224aa28b7602604a0a5543f36e81570ca952185a4ae5538aa430768cdc` |
| Media3 1.11.0 `datasource` | `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| Media3 1.11.0 `common` | `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e` |

Also read: `ProgressiveDownloader.java` and `DownloadManager.java` (1.11.0).

**Muon's wiring:**
- `Transport.client` sets a 5 s connect timeout and a 15 s read timeout, with no call timeout (`TauonApi.kt` 11-13).
- Downloads use `OkHttpDataSource.Factory(Transport.client)` behind `CacheDataSource`, with `DefaultDownloaderFactory` and an unretained 2-thread pool (`OfflineStore.kt` 83-85).

## Verified source behaviour

- **The interrupt check comes only before each read:**
  - Okio's `InputStreamSource.read` calls `timeout.throwIfReached()` and then the blocking `input.read` (`JvmOkio.kt` 86-109).
  - `throwIfReached` throws `InterruptedIOException("interrupted")` if `Thread.isInterrupted`, without clearing the flag (`Timeout.kt` 96-105).
  - Nothing in this code checks interruption again while `input.read` is blocked.
- **Buffered bytes are served without that check:**
  - The socket source is a `RealBufferedSource` (`RealConnection.kt` 307 and 421).
  - The HTTP/1 body source reads it through `AbstractSource.read` (`Http1ExchangeCodec.kt` 337-345).
  - **Inferred from the buffering structure:** bytes already buffered can be returned without calling the socket source, so an interrupt is seen only at the next underlying socket read.
- **The read timeout is a deadline:**
  - HTTP/1 sets `socket.soTimeout` and the source timeout to the read timeout (`RealConnection.kt` 615-616).
  - `Socket.source()` wraps reads in `SocketAsyncTimeout` (`JvmOkio.kt` 136-140). Its watchdog `timedOut()` **closes the socket** (153-155), armed by `enter()`/`scheduleTimeout` from the timeout or deadline (`AsyncTimeout.kt` 51-57, 229-248).
  - For Muon this bounds a silent body read at about 15 s, but it isn't a whole-download deadline.
- **Cancellation closes the socket:**
  - `RealCall.cancel` → `exchange?.cancel()` (`RealCall.kt` 135-140)
  - → `codec.cancel()` (`Exchange.kt` 156-157)
  - → `connection.cancel()` (`Http1ExchangeCodec.kt` 103-104)
  - → `rawSocket.closeQuietly()` (`RealConnection.kt` 639-641).
  - **Not verified here:** that a blocked read then throws.
- **Closing the body is bounded but separate:**
  - `FixedLengthSource.close` discards any remainder with a 100 ms deadline (`Http1ExchangeCodec.kt` 391-398; `ExchangeCodec.kt` 78; `Util.kt` 334-364). If that fails, it marks the connection `noNewExchanges`.
  - The deadline also arms the watchdog.
  - Closing the body returns the exchange; it is not a receipt that a cache writer has finished.
- **Media3 bridge** (prior report, `OkHttpDataSource.java`):
  - **Waiting for headers:** an interrupt calls `call.cancel()` (429-451).
  - **Body reads:** `readInternal` reads the stream directly, with no cancel (511-530).
  - **Close:** `close` closes the response body (362-370, 534-539).
- **Download stack:**
  - **`DownloadManager.Task.cancel`** calls `downloader.cancel()` and interrupts the Task thread.
  - **`ProgressiveDownloader.cancel`** sets its flag and calls `RunnableFutureTask.cancel(true)` (199-205), which in turn:
    - calls `CacheWriter.cancel()`, a volatile flag (`CacheWriter.java` 91-92);
    - **interrupts the pool worker** (`RunnableFutureTask.java` 84-101).
  - **CacheWriter** checks the flag only **between** reads (188-189). On an exception it closes the data source quietly (198-200).
  - **Waits for the worker:** `ProgressiveDownloader.download`'s `finally` calls `blockUntilFinished()` (188-191), which is uninterruptible (`RunnableFutureTask.java` 59-60).
  - So the **Task thread does not return before the pool worker's `doWork` ends**.

## What this means (verified versus hypothesis)

- **Interrupted between reads:** the next `CacheWriter` flag check, or the next Okio socket read, throws `InterruptedIOException`. The writer then closes the data source, which closes the body (a bounded 100 ms discard). This is source-supported.
- **Interrupted during a blocked socket read:** nothing in the inspected code turns that interrupt into an exception mid-read. The read ends on data, end of stream, the 15 s read timeout (the watchdog closes the socket), or a socket closed by someone else. No body-read `Call.cancel` was found in the Media3 bridge.
  - **Hypothesis:** whether Android's socket `read` itself responds to `Thread.interrupt` is platform behaviour, not established here.
  - So **a worst-case cancelled-but-still-reading worker can last about the read timeout** after the server stops sending. Only an end-to-end test would show the real value.
- **Completion still has to be observed.** Interruption, cancellation, the body close and the read timeout are all requests or bounds. None of them is a receipt that the worker finished, the file closed or the cache writer released its span.
  - **Implied receipt:** given `blockUntilFinished`, the **Task thread exiting** implies the pool worker finished.
  - **Still missing:** `DownloadManager.release` doesn't join Task threads (#179), so that receipt still has to be taken (for example, by joining the Task thread, as in the #322 fixture).

## Limits

- **Not analysed:**
  - **TLS:** `SSLSocket` reads, where `rawSocket` close is the cancel path; behaviour under a blocked TLS read is unverified.
  - **HTTP/2:** stream timeouts and `RST_STREAM`.
  - **Other platform paths:** Android Conscrypt and Android's socket implementation.
  - **Several owners or calls at once.**
- **Muon's setup:** Tauon is plain HTTP on the LAN, so HTTP/1 is the expected path. Negotiation wasn't verified.
- **Versions:** source matches the published artifacts, not CI's actually resolved okio version.
- **Not measured:** real timing.

## Smallest safe follow-up (proposal only)

A disposable JVM test with a loopback `ServerSocket` (a local socket, not the network) that serves a fixed-length body, sends part of it and then stalls. It would run Muon's `OkHttpDataSource` → `CacheDataSource` → `ProgressiveDownloader` on a temporary cache with a **short test read timeout**, then measure:
- **interrupt between reads:** the test thread interrupts the worker;
- **interrupt during a blocked read:** the time from `cancel()` to the Task thread exiting, with and without the server closing its side;
- **file close:** that the cache's real file closes before teardown, and that the Task thread joins within a bound.

Keep it CI-only. Assert ordering and bounds, not absolute timings.

**Checks run locally:** `git diff --check` and the CI prose check.
