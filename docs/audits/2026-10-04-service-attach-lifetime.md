# #179 service attach-callback lifetime — 2026-10-04

Inspected main `8ed4e4ca9932fbb01115ec136a9c5643bf9749c2`, branch `codex/service-attach-lifetime`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Test fixture and source reading by the author, **not independent review**. Test-only: no app change, device or user data. Not compiled or run locally: Actions is the first compile and run. #179 remains open.

**Question:** the helper's attach callback is posted to the front of the main queue through an anonymous handler. `onDestroy` clears only the notification updater's handler. If that queued callback runs after the instance is destroyed, does it still read the manager and restart foreground updates? This is the **posted** callback, not the listener callbacks that the existing `CardServiceCharacterizationTest` controls invoke directly.

## Pinned sources

- **Media3 1.11.0** `exoplayer.jar` sources, SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6` (`app/build.gradle.kts` 82-84). Read with Python's `zipfile`.
- **Robolectric 4.16.1** `shadows-framework` sources, SHA256 `977c225559953d772cff539dff0ccf4bb757f297e6a18620cd609ba077108409`.

**`DownloadService.java`:**
- **`attachService`** (975-988): when the manager is initialized, it posts `notifyDownloads(downloadManager.getCurrentDownloads())` with `postAtFrontOfQueue` on a new handler (984-986).
- **`notifyDownloads`** (800-809): calls `startPeriodicUpdates` when any download needs a started service. It doesn't check `isDestroyed`.
- **Notification updater:** `startPeriodicUpdates` (900-903) leads to `update()` (923-945). `update()` reads the helper's manager (924-926) and calls the foreground start (929-934) or `notify` (939-940). It then re-posts itself every interval (942-945).
- **`onDestroy`** (709-716): detaches the service and calls `stopPeriodicUpdates` (905-908), which clears only the updater's handler. The service's own helper field isn't cleared, so `update()` still reaches the manager.

**Robolectric:**
- **`ServiceController.create()`/`destroy()`** run the lifecycle method, then `idleIfPaused` (`ServiceController.java` 63-74). `buildService(...).get()` only attaches the context (19-54).
- **`ShadowService.startForeground`** (58-81) records the notification and posts it through `NotificationManager`. Its `onDestroy` shadow (32-38) only runs through `super`, which `DownloadService.onDestroy` doesn't call.

## Fixture (`ServiceAttachLifetimeTest`, new; CI pending)

**Setup:**
- A disposable native-SQLite index and temporary cache, plus a real `DownloadManager` with no requirements and one download. The actual `MuonDownloadService` is used through the existing store injection.
- A downloader that **stays in `download()` until cancelled** keeps that download in DOWNLOADING. There is no audio, network or cache write.
- The main looper is paused (Robolectric's default).

**Tests:**
1. **Live control: `aLiveServiceRunsTheAttachCallbackAndStopsUpdatingWhenDestroyed`.**
   - `create()` drains the queue, so the callback runs while the instance lives. The foreground notification appears (ID 2, title "Downloading", built from the manager's current downloads).
   - Advancing past the 1 s interval replaces the notification.
   - After `destroy()`, advancing 2.2 s leaves it unchanged: updates stopped.
2. **Destroy first: `anAttachCallbackQueuedBeforeDestroyStillStartsUpdatesOnTheDestroyedInstance`.**
   - `onCreate()` and `onDestroy()` are called directly, before the main looper is drained. Nothing has been shown yet, because the callback is still queued.
   - After draining, the callback starts the foreground on the **destroyed** instance, with the same manager-derived title. Advancing past the interval replaces the notification again, so periodic updates resumed and no public API stops them.

**Teardown:** restores the store, releases the manager (which cancels the held task), waits up to 10 s for the worker to exit, then releases the cache, clears the test-only helper map and closes the database. The main looper is never drained after the release, so no queued update reads a released manager. Robolectric discards the remaining messages when the test ends.

## What this shows, and what it doesn't

- **Shown, if CI passes:** a queued attach callback that runs after `onDestroy` reads the manager and restarts periodic foreground updates on the destroyed instance. Those updates keep reading the manager every interval and can't be stopped through `DownloadService`'s public or protected API. That is a source-supported lifetime gap in the callback itself.
- **Not shown: that Android orders it this way.** The second test's ordering is **artificial**. On a device, onCreate and onDestroy arrive as separate main-thread messages, and a front-of-queue post normally runs before any message already queued. Whether a destroy can ever precede it (for example, inside the same message as create) isn't established. No device behaviour or data loss is claimed.
- **Not a production fix:** no production change is proposed or made. Pending #321 (card command admission) doesn't touch this path.

## Implications for #179's owner drain

- **A destroy receipt isn't a retirement receipt.** Before any manager release, a stop coordinator must have evidence that no attach callback is still queued, or that one already ran and its updates were stopped.
- **A barrier only orders.** A main-thread barrier after detach orders an earlier front-of-queue callback ahead of itself. But if the callback ran after destroy, the barrier can't undo the restarted updates. Any later update would then read a released manager.
- **The only boundaries that remove the risk:**
  - not releasing the manager in-process, as on main today;
  - or an architecture that owns the notification and lifecycle itself, rather than `DownloadService`'s private updater.
- **No permission granted:** nothing here permits a cache release, rebind or adoption.

**Next bounded step:** source-read Android's `ActivityThread` service-message handling, or run disposable device instrumentation, to learn whether a destroy can precede the queued attach callback. That is only needed if a future design proposes an in-process release; otherwise record this as a constraint on such designs.

**Checks run locally:** `git diff --check` and the CI prose check. No Gradle, Kotlin or Robolectric run.
