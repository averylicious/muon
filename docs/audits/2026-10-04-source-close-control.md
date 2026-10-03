# #179 source close control — 2026-10-04

Base `ce08eeb4d05547650155f3278972f9e582217fb6` on branch `codex/source-close-control` (includes pending test-only #309). Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author tests and source check, **not independent review**. Test-only characterization, **not a production gate or fix**. No device, network, user data or production change. Not compiled locally: CI is the first compile and run. #179 remains open.

## Pinned sources (Media3 1.11.0, local `build/sources`)

Read with Python's `zipfile`. Line numbers count `\n`-separated lines.

| Jar | sha256 |
| --- | --- |
| `datasource.jar` | `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a` |
| `exoplayer.jar` | `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` |

- **The test's seam exists:** `CacheDataSource.Factory.setCacheReadDataSourceFactory` (117). Each `CacheDataSource` creates its own cache-read source (316).
- **`open`** (582-622) sets `currentDataSource` before calling the delegate's `open` (799, 802). On failure it only marks a cache error and rethrows; it **doesn't close** the delegate.
- **`close`** (682-693) clears `actualUri` (684) **before** `closeCurrentSource` (688).
- **`closeCurrentSource`** (850-864) clears `currentDataSource` and releases any hole span in a `finally`, even if the delegate's `close` throws. `close` rethrows that failure.
- **Muon:**
  - `OfflineDataSource.open` (`OfflineDataSource.kt` 22-27) stores `active` before opening, so a failed open still leaves a source to close;
  - `close` (36-38) clears `active` in a `finally`;
  - `Shelf.source` (`OfflineStore.kt` 37-38) is a shared, mutable `CacheDataSource.Factory`.

## Controls (`SourceCloseControlTest`; exact-head CI verified)

Every control runs the actual `OfflineDataSource` → `routeOfflineRequest` → `Shelf.source` `CacheDataSource` → **real `FileDataSource`**, over disposable fully cached bytes with a completed native-SQLite index row. The manager is idle with no downloads. The only seams are on the test's own `Shelf.source`:
- a wrapper around the real `FileDataSource`, adding a synthetic fault or hold;
- an upstream that fails, and is counted, if ever reached. It replaces OkHttp before any source exists, and teardown asserts it was never opened.

1. **`openFailureAfterTheRealFileOpenedLeavesCloseToTheCaller`**
   - **Fault:** the wrapper throws `IOException` **after** the real `FileDataSource.open` succeeded.
   - **Expected:**
     - the failed open didn't close the delegate;
     - the caller's `OfflineDataSource.close()` closes it exactly once and clears `active`;
     - a second close does nothing.
   - **Not claimed:** that an open failure drains itself.
2. **`aHeldCloseHasNoCompletionReceiptAndUriIsAlreadyCleared`**
   - **Setup:** one test worker owns construction, open, read and close; the test thread only watches latches, atomic counters and a future. The wrapper's close blocks on a test-owned gate.
   - **Expected while held:** no close-completion receipt, the delegate is not closed, and a `getUri()` taken from inside the close hook, on the same worker, is already `null`. **So `uri == null` is not a drain receipt.**
   - **Expected after the gate opens:** the worker returns the exact bytes, and the delegate closes once.
3. **`closeFailureAfterTheRealCloseStillClearsTheActiveSource`**
   - **Fault:** the wrapper closes the real delegate, then throws `IOException`.
   - **Expected:** `OfflineDataSource` propagates the failure but clears `active`, and a later close doesn't retry that source.
   - **Caveat:** this injected throw comes after a successful cleanup. It doesn't show that real close failures are safe or drained.

**Cleanup** (revised after coordinator review of `03f9e52`):
1. **Gates and worker:** teardown opens every test gate, shuts the worker down and waits for it, bounded to 5 seconds.
2. **Close every source:** only after the worker has drained, so no source is called from two threads, teardown closes every `OfflineDataSource` a test created. It tracks them in a thread-safe list, so this covers a source left open by a failed main-thread assertion.
   - The worker's own lifecycle closes its source in a `finally`, whether or not open or read succeeded.
   - **Expected failure:** only the deliberately injected `InjectedAfterClose`, thrown after a successful real close, is tolerated.
   - **Any other close failure** fails the fixture.
3. **Release the dependencies:** the manager, cache and database are released only if:
   - the worker drained;
   - no unexpected cleanup failure occurred;
   - every real `FileDataSource` that opened recorded a completed close.

   Otherwise they're kept and the fixture fails.
4. **Order:** all cleanup steps run before any teardown assertion.

**Other bounds:**
- `readAll` is bounded to the 4-byte payload: a source that returns zero, or more bytes than the fixture holds, fails instead of hanging.
- The held close waits only on a test-owned gate. No timeout or interrupt releases it.

## #309 pinned-source check (separate, narrow)

The local `exoplayer.jar` (hash above) **confirms** the coordinator's #309 citations in `Loader.java`:

| Citation | Lines | What it says |
| --- | --- | --- |
| Default `Loader(String)` | 236-240 | `ReleasableExecutor.from(Util.newSingleThreadExecutor(...), ExecutorService::shutdown)` |
| `release` | 328-335 | cancels the current task, queues `ReleaseTask`, then `downloadExecutor.release()` |
| `LoadTask.cancel` | 421-438 | `cancelLoad`, interrupts the executor thread; when released, finishes and calls `onLoadCanceled(..., true)` synchronously |
| `ReleaseTask.run` | 574-576 | runs the release callback |
| `ReleasableExecutor` | 33-54 | release may run with commands still pending; `from` wraps and calls the release callback |

**Also confirmed:**
- `LoadTask.run` checks `canceled` before calling `load()` (443-450), which supports #309's queued-cancellation control.
- `Loader(ReleasableExecutor)` is public (250).
- `Callback.onLoadStarted` is a default method (107), so the test's callback compiles without it.

## Limits

- **The faults are synthetic.** Real file, cache or OkHttp failure modes, and how long real closes take, aren't characterized.
- **Single owner only:** the controls cover normal and single-owner lifecycles, not concurrent API calls, playback-thread ownership, or a real blocked read.
- **No design here:** no production leases, generation binding, card-loss recovery or drain design. #179 remains open.

## Execution receipt

PR #310 head `0e3712b85c9bb99515085f3457de2f31756281be` passed [Android537](https://github.com/averylicious/muon/actions/runs/37148303361). Downloaded XML verifies455 tests per variant, zero failures/errors/skips, all three controls executed. Debug artifact11282554749 BUILD.txt confirms exact SHA/run537/Canary.537. Main merge `ae25fe2149040879f10f95760135a90249db298d`. GPT-6/Codex desktop (effort not reported) independently reviewed Claude's test code, requested the cleanup correction, integrated main and verified CI; its own documentation is self-review. No phone acceptance is claimed.
