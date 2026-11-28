# Download upstream and drain ownership — 2026-10-03

Inspected main `1c865dc22d624eaab8e0157c9f56916b3df4cea0`. GPT-6 / Codex desktop, effort not reported; source investigation/self-review, not independent review. This follows #179’s writer/release controls and supports S3 design. No production patch, device use, socket test or claim of data loss.

## Actual Muon wiring

`OfflineStore.shelf` (83–85) creates a CacheDataSource factory using `OkHttpDataSource.Factory(Transport.client)` and a DefaultDownloaderFactory supplied an inline `Executors.newFixedThreadPool(2)`. The Shelf retains cache/manager/service, not that executor. The object owns separate single-thread copier and mover pools; artwork and bootstrap workers have additional ownership. There is no retained shelf executor shutdown/await handle in this inspected implementation.

This is an ownership gap for a proposed safe replacement barrier, not proof that normal operation leaks a fixed number of threads or that a download is currently corrupting files. A future generation must own the relevant writer lifecycle explicitly; blindly calling manager.release and cache.release is not evidence that the download executor and task threads have drained. The manager’s Task and ProgressiveDownloader’s executor runnable are distinct threads in this wiring.

`Transport.client` sets a five-second connect timeout and fifteen-second read timeout. A read timeout is not a whole-operation deadline or a completion receipt. This report does not propose imposing a short total timeout on legitimate audio downloads.

## Pinned OkHttp data-source bridge

Read the published [Media3 datasource-okhttp source JAR](https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource-okhttp/1.11.0/media3-datasource-okhttp-1.11.0-sources.jar), SHA256 `e90957224aa28b7602604a0a5543f36e81570ca952185a4ae5538aa430768cdc`. Build selects that Media3 version; line numbers count literal newlines. This verifies the bridge source, not resolved compiled-artifact integrity or the underlying OkHttp/Okio socket implementation.

In `OkHttpDataSource.java`:

- `open` creates a local Call and obtains response/body stream through `executeCall` (270–275).
- `executeCall` enqueues the Call and waits for the response future. Interruption of that wait explicitly calls `call.cancel()` (429–451). Do not falsely report that this bridge never cancels calls.
- Body `readInternal` directly reads the response stream (511–530, call at 523). There is no corresponding explicit Call.cancel in this method; whether interruption terminates that socket/body read depends on the underlying implementation and timing, which this slice did not inspect or measure.
- `close` and `closeConnectionQuietly` close the response body (362–370,534–539). This is separate from proving that the writer has completed before a resource is replaced.

Therefore the synthetic interruption-ignoring upstream in [#294](https://github.com/averylicious/muon/pull/294) tests the progressive components’ shutdown contract. It must not be described as verified behavior of Muon’s actual OkHttp body read. Source-supported missing join/ownership is enough to require a drain design; actual network timing remains unverified.

## Next bounded outcome

Specify an owned generation’s download executor/task, mover/copier and callback lifetimes before implementing replacement. Retain the old resources while draining or report a recoverable unavailable state; do not purge/release/reopen the card based on the manager return alone. Service helper ownership (S4), capture completeness, durable adoption and recovery remain separate unresolved requirements. Any actual network cancellation test must use controlled disposable data and add evidence rather than rewrite the synthetic control’s claims.
