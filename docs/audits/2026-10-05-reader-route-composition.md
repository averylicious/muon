# #179 reader route composition control — 2026-10-05

Inspected main `fa6d47fba0ee131f1860fb087154877401c855fc`, branch `codex/reader-route-composition`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author test and source check, **not independent review**. **Test-only:** no production file, build or dependency change, device, network or user data. Not compiled locally: Actions is the first compile and run. #179 remains open.

**Gap:** the [#311 prototype](2026-10-04-reader-admission-prototype.md) leases one fixed shelf. Production routing reads more than the shelf it finally selects:
- `OfflineStore.route` (178-184) calls `routeOfflineRequest(spec, store.phone, store.shelves, offline)`.
- `routeOfflineRequest` (`OfflineRoute.kt` 12-21) asks `shelves.firstOrNull { it.completed(id) }` in order, phone then card, before selecting.
- `Shelf.completed` (`OfflineStore.kt` 40-41) reads `manager.downloadIndex.getDownload(id)` inside `runCatching`, so an exception becomes a **miss**.
- `OfflineDataSource.open` (`OfflineDataSource.kt` 22-27) routes, then creates and opens the selected shelf's source.

A card hit therefore queries the **phone** generation's index too. Admission that throws from inside an index call would be silently turned into "not downloaded" rather than a refusal.

## Fixture (`ReaderRouteCompositionTest`, new; CI pending)

**Actual objects:**
- two disposable `Shelf`s, phone and card, each with a real `SimpleCache`;
- a native-SQLite `DefaultDownloadIndex` per shelf, in separate tables;
- an idle `DownloadManager` that never receives a download;
- the actual `routeOfflineRequest` and `OfflineDataSource`.

**Seeds:** the card's completed record is written to its real index table **before** its manager starts. Its bytes go into the card cache. The phone index has no record.

**Seams:**
- **Index recorder:** each manager receives a `WritableDownloadIndex` that delegates every call to the real index. On `getDownload` it records the index name and whether **that generation's** lease was held at the moment of the query. This observes protection at the actual call, rather than copying the routing logic.
- **Cache reads:** the cache-read factory is a real `FileDataSource` wrapped to count opens and closes and to inject the synthetic faults: open-after-delegate, held close, close-after-delegate.
- **No network:** the upstream fails if it is ever reached, and teardown asserts it never was.

**Test-local lease bundle** (`Generation` plus `RouteAdmittedSource`; not production):
- **Before routing:** each open leases **every** candidate generation in snapshot order (phone, card), before any route, index query or source.
- **Refusal:** if a later generation refuses, earlier leases are released and `GenerationClosed` is thrown with no route call.
- **Failed opens:** leases are held through a failed open until the caller's `close`.
- **Close:** a successful delegate close releases all of them. A failed close marks **every** generation that open leased as uncertain; it is never released, and a later no-op close doesn't clear it.
- **Drained** means closed, unleased and certain. A timed-out wait grants nothing.

## Cases

1. **`aCardHitConsultsThePhoneIndexAndTheCardIndexUnderBothLeases`:** the recorded queries are exactly the phone index, then the card index, each while its own lease was held. The bytes come from the card cache's real file. Both leases are released after close.
2. **`aClosedCardGenerationRefusesBeforeAnyRouteQueryOrSourceAndRollsBackThePhoneLease`:** no route, query or source happens. The phone lease taken first is rolled back, and both generations drain.
3. **`anActiveCardReadBlocksBothGenerationsFromDrainingUntilItsCloseReturns`:** the phone is blocked too, because it was consulted. Both drain only after the real file closes.
4. **`aFailedCardOpenKeepsBothLeasesUntilTheCallerCloses`:** the injected failure happens after the real open. The caller's close reaches the real file.
5. **`aHeldCardCloseKeepsBothGenerationsUndrainedUntilItReturns`:** one worker owns the source. 50 ms bounded waits return false while the close is held; both drain within 5 s after it returns.
6. **`aCardCloseFailureLeavesBothGenerationsUncertainEvenAfterALaterNoOpClose`:** a reopen is refused with no new route or query. The real file did close, which is teardown's proof only, not a gate permission.
7. **`aReusedSourceTakesNewLeasesOnEachOpenAndIsRefusedOnceAGenerationCloses`:** each reopen makes new leases, a new route, new queries and a new card source. After the card generation closes, a reopen is refused with no new route or source, and the phone lease is rolled back.

**Cleanup:**
1. Teardown opens the hold and drains the worker.
2. It closes every tracked `OfflineDataSource`, tolerating only the injected after-close failure.
3. Only if every real `FileDataSource` that opened recorded a completed close does it release both managers, then both caches, then close the database.

The gates' own drain is never used as permission to dispose.

## Conservative cost (why this stays test-local)

- **Over-refusal:** leasing every snapshot candidate refuses requests that one generation alone could serve. For example, a phone hit or stream fallback is refused whenever the card generation is closing or uncertain. A failed close on the card also marks the phone uncertain.
- **Narrower alternatives** (not proven here):
  - leasing per consulted index as routing proceeds;
  - releasing unselected generations after selection;
  - routing over a frozen catalog snapshot.
- **Not a production proposal:** this test doesn't propose adopting the bundle into production.

## Still unprotected (not covered by this or any reader lease)

- **Downloads:** the downloader tasks and the manager threads.
- **Services:** the services and their helpers, including restarts and posted attach callbacks.
- **Callbacks:** the `watch` listener's `record`/`removed` and cross-shelf removes, the startup index scan's post, and the artwork executor.
- **Copier:** played-copy writes, including `downloaded()`'s **card-index** read.
- **Mover:** direct `CacheDataSource` reads and writes across both generations, its Add posts, and its progress.
- **Index and library readers:** `downloadedSongs`/`downloadedLibrary`/`downloadsOn`/`downloadedOn`.
- **Played-copy routing:** offline played-copy routing (`hasPlayedCopy` on the phone cache), not exercised here because `offline` is false.
- **Catalog and card identity:** durable catalog and card identity, and any cache release or adoption.
- **Production caller:** this test reproduces `OfflineStore.route`'s shelf order rather than calling it.

The faults are synthetic. Real file, cache and network failures, Loader timing and multiple owners aren't shown.

**Checks run locally:** `git diff --check` and the CI prose check. No Gradle, Kotlin or Robolectric run.
