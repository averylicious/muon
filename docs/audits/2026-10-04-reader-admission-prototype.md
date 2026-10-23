# #179 reader admission prototype — 2026-10-04

Base `0e3712b85c9bb99515085f3457de2f31756281be` on branch `codex/reader-admission-prototype` (pending #310 ancestry). Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author prototype and source check, **not independent review**.

**Test-only, not a production gate or fix.** No production code, API change, device, network or user data. Not compiled locally: CI is the first compile and run. #179 remains open.

## Source facts (pinned Media3 1.11.0, local `build/sources`)

Hashes: datasource `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`, exoplayer `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. Line numbers count `\n`-separated lines.

- **Close after a failed open:** `DataSource.open` says callers must still call `close()` if it throws (`DataSource.java` 48-50). `close` must be called even if `open` threw (103-104).
- **One instance is reopened:** `ProgressiveMediaPeriod.ExtractingLoadable.load` (1296-1356) opens the same `StatsDataSource`-wrapped source on each loop iteration (1299-1303), so **a lease taken at construction is not enough**.
- **Close failures can be swallowed:** that same caller closes with `DataSourceUtil.closeQuietly` (1356). A failed close may never reach a caller as an exception, so uncertainty must be recorded where the close happens.
- **Muon's `OfflineDataSource`:**
  - `open` (`OfflineDataSource.kt` 22-27) routes and creates a new `Shelf.source` `CacheDataSource` on **every** open, and stores it as `active` before calling `open` on it;
  - `close` (36-38) clears `active` in a `finally`;
  - clearing `active` is not a completion receipt ([source close control](2026-10-04-source-close-control.md)).

## The test-local prototype (in `SourceCloseControlTest`)

`ReaderAdmission` is per-open admission for **this fixture's one fixed shelf**, not a production store or card identity. `AdmittedSource` wraps an actual `OfflineDataSource`. Lifecycle:
- **Open:** `open` acquires a lease **before** delegating, so before routing or creating any source. When admission is closed or the generation uncertain, it throws `ReaderAdmissionClosed` and does no route, source or cache work.
- **Failed open:** the lease stays held through a failed open until the caller's `close`.
- **Close:** a successful close releases the lease only **after** the delegate's `close` returns. A failed close quarantines the generation as **uncertain**: no release, and drain is never granted afterwards. A later no-op close doesn't clear it.
- **Reuse:** each reopen of the same instance acquires a new lease; a reopen after closure or uncertainty is refused.
- **Drained** means admission closed, no lease held, and no uncertainty. A timed-out `awaitDrained` returns false and grants nothing. Closing admission wakes waiters, so a waiter with no leases left doesn't sleep until its timeout.

The three existing controls keep their meaning. The only addition to them is a route-call counter in `offlineSource()`.

## Cases (verified Android539)

1. **`closedAdmissionRefusesAPreviouslyMadeSourceBeforeAnyRouteOrCacheWork`:** a source made before closure is refused on open, with no route call and no cache source created.
2. **`anActiveCachedOpenBlocksDrainUntilItsCloseReturns`:** a real cached read is open. Closed admission alone isn't drained; the gate drains only after the close, once the real file has closed.
3. **`aFailedOpenAfterTheFileOpenedStaysCountedUntilTheCallerCloses`:** the injected open failure keeps one lease until the caller's close, which closes the real file.
4. **`aHeldCloseKeepsDrainFalseUntilTheCloseReturns`:** one worker owns open, read and close. The test thread closes admission while the close is held: not drained. A purposeful 50 ms `awaitDrained` while the close is still held returns false, so a timeout grants no drain. Once the close returns, it drains, with a bounded wait.
5. **`aCloseFailureLeavesTheGenerationUncertainEvenAfterALaterNoOpClose`:**
   - the close failure is injected after a successful real close, and makes the generation uncertain;
   - a reopen is refused even before admission closes, with no extra route;
   - a no-op close plus admission closure still doesn't drain.
6. **`aReusedSourceReleasesEachLeaseAndCannotReopenAfterAdmissionCloses`:** open, close, then reopen the same instance: a new lease and a new underlying source. After closure, a reopen is refused and creates no new source.

**Cleanup:** the existing teardown applies. It opens the gates, drains the worker, then closes every tracked `OfflineDataSource`, tolerating only the injected after-close failure. It releases the cache and database only if every opened real `FileDataSource` recorded a completed close.

**Teardown never takes permission from the prototype:** in case 5 the prototype stays uncertain, and disposal relies only on the wrapper independently observing the real file's successful close. That is a fixture proof, not a gate permission.

## Limits and next production prerequisites

- **Synthetic and single-owner:** the faults are synthetic, there is one owner per source, and the shelf is fixed.
- **Not covered:** real file, cache or network failure behaviour, playback-thread ownership, and Loader cancellation/interrupt timing. Only the entry points above are covered.
- **Not gated:** downloader, service, mover, copier and callback admission; production cache release; a durable catalog; card identity or recovery.

Before production use, a design needs to settle:
- where the lease lives per shelf generation (owned by the store, bound to card identity);
- how every reader entry point acquires it, including `OfflineDataSource`, played-copy reads and any direct `Shelf.source` users;
- what uncertainty means to the user, and how recovery leaves it;
- how it composes with the downloader drain prototype and `Loader` release receipts.

## Verification receipt

[#311](https://github.com/averylicious/muon/pull/311) final head `e187f941e03479b6a86a6a968eac9e6f59d87081` passed [Android539](https://github.com/averylicious/muon/actions/runs/37149037473) and Branch direction. Downloaded XML: 461 tests per variant, zero failures/errors/skips, all nine source-close/prototype controls executed in both. Debug artifact11283615652 BUILD.txt matched the exact head, run539 and Canary.539. Coordinator independently reviewed Claude's source and requested waiter notification and an actual held-close timeout control. Merged as `12ab1e44f4e4de9d91eff383bdd8f117f29818e0`; main [Android540](https://github.com/averylicious/muon/actions/runs/37149793941) and [.540 publication](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.540) verified, including downloaded BUILD.txt. No production reader barrier or device assurance follows from these test-only receipts.
