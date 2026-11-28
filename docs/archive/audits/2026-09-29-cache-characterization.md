# Removable-cache characterization — 2026-09-29

Follow-up to [the source review](2026-09-28-storage.md), tracked in [#179](https://github.com/averylicious/muon/issues/179). App source inspected at main `831f5465b630343a4decdc1b4cbf46570801d753`. This slice adds tests and documentation only; **it does not fix the card lifecycle or authorize hot-swapping**.

## Executable evidence

`app/src/test/java/dev/avery/muon/RemovableCacheCharacterizationTest.kt` exercises the actual resolved Media3 `SimpleCache`, `NoOpCacheEvictor` and `StandaloneDatabaseProvider`. Robolectric supplies Android APIs and native SQLite on the JVM. The explicitly selected Android API 34 test runtime works with CI's Java 17; it does not change Muon's minimum/target SDK. Robolectric is test-only and is not packaged in an APK. No Compose UI test framework or app resources are needed here.

Every test creates disposable cache data. A directory rename hides the original path while leaving its bytes intact. The database remains accessible in Robolectric's application storage, modeling Muon's phone-side database separately from card files. This is **not** a simulation of Android's mount broadcasts, real-volume permissions or in-flight I/O on removal.

| Case | What the test checks |
| --- | --- |
| Mounted release and reopen | Same cache UID, addressable content and exact byte preservation. |
| Disappear and return without cache operations | The unchanged files remain addressable after a normal release and reopen. |
| Release while absent | Intact parked bytes survive release, but their phone-side content mapping is lost; initialization deletes the orphan span after the directory returns. |
| Missing-span lookup, return, metadata store, reopen | Lookup queues removal; restoring files does not restore their mapping. A later ordinary metadata operation persists the removal and reopening deletes the orphan. |
| Phone cache alongside the disappearing card | Distinct cache UIDs in the shared database isolate the unaffected phone cache. Its bytes remain addressable across reopen. |

The loss cases intentionally **characterize unsafe upstream behavior**. Green tests confirm that behavior, not safe app storage. Keep them as dependency evidence; the eventual application lifecycle fix needs separate preservation tests that exercise its production integration. Normal cases and post-return byte assertions prevent an empty/invalid fixture from masquerading as the loss path.

Exact-head CI execution/results are recorded on the linked PR and [#179](https://github.com/averylicious/muon/issues/179). Until those results are verified, test expectations are not a successful reproduction claim. The existing Android APKs workflow runs the class in both debug and release unit-test tasks, alongside builds, lint and APK checks. A focused invocation is:

```sh
./gradlew :app:testDebugUnitTest --tests dev.avery.muon.RemovableCacheCharacterizationTest
```

## Source validation

The app currently resolves Media3 from the build files. For this inspection, its datasource/database source JARs were fetched from Google's Maven at the pinned version and the following paths inspected:

- `androidx/media3/datasource/cache/SimpleCache.java`: `getSpan`, `removeStaleSpans`, `release`, `applyContentMetadataMutations`, `initialize` and `loadDirectory`.
- `androidx/media3/datasource/cache/CachedContentIndex.java`: `maybeRemove`, `store` and database storage updates. Empty, unlocked entries may be removed even when content-length metadata exists.
- `androidx/media3/datasource/cache/SimpleCacheSpan.java`: the content-ID lookup used to recognize stored files.
- `androidx/media3/database/StandaloneDatabaseProvider.java`: the database uses application storage, separately from cache directories.

Robolectric's pinned published POM and annotations sources were also checked, including `Config.sdk`/`Config.NONE` and `SQLiteMode.NATIVE`. Versions belong in Gradle, not duplicated as upgrade instructions here.

## Next implementation boundary

Do not mistake a check of `MEDIA_MOUNTED` for race-free preservation. The retained cache can already have observed a missing file before a broadcast or another availability check. Releasing it can persist the damage. Moving only the database to the card is not yet a proven remedy: in-memory changes may be persisted after the same card returns.

The next design must protect the content-ID mapping and account for cache ownership, active reads/writes, download services and completion callbacks. Define same-card versus different-card identity and migration from the shared `card` download index before reattachment. Keep the phone cache usable. See [the existing requirements](2026-09-28-storage.md#requirements-for-the-next-implementation).

A focused availability/routing containment change can be reviewed separately, but it must leave #179 open and identify the remaining mid-operation/persistence risk. Before claiming a fix, add tests proving byte preservation through the production lifecycle boundary, then obtain separately authorized disposable-card QA. No user file loss, phone behavior, graceful eject, surprise removal, download/move concurrency or different-card reattachment has been verified by this JVM harness.
