# #179 queued service command control — 2026-10-04

Inspected main `5387988d60d495fac19bf1e2624e7b36d4f43691`, branch `codex/service-command-control`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author tests and source check, **not independent review**. Test-only: no app change, device, network, card or user data. Not compiled locally: Actions is the first compile and run, and CI is pending. #179 remains open.

**Question:** can a legitimate service command, queued while the old card shelf is selected, execute against a replacement manager after the old service is destroyed, the helper map is cleared and a new service binds?

## Source evidence

**Media3 1.11.0** `exoplayer.jar` (local `build/sources`, sha256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`); line numbers count `\n`-separated lines.

**In `DownloadService.java`:**
- **Commands carry only an action:** the public builders `buildResumeDownloadsIntent`/`buildPauseDownloadsIntent` (362-377) make `new Intent(context, clazz).setAction(action)` (`getIntent` 872-875) plus a foreground flag. No manager, shelf or generation goes with the command.
- **Sending:** `sendResumeDownloads`/`sendPauseDownloads` (492-509) call `context.startService` when not foreground (`startService` 877-883).
- **The manager is resolved at delivery:** `onStartCommand` (616-696) reads the manager from the service's **current** helper (631), then dispatches `ACTION_RESUME_DOWNLOADS`/`ACTION_PAUSE_DOWNLOADS` to `resumeDownloads()`/`pauseDownloads()` (658-663).
- **A new service resumes its manager:** `onCreate` calls `downloadManager.resumeDownloads()` when it makes a new helper (604-605).

**In `DownloadManager.java`:** `getDownloadsPaused()` (423) returns the application-thread state that `setDownloadsPaused` updates synchronously (534-538).

**Robolectric 4.16.1** published sources (`robolectric-sources.jar`, sha256 `977c225559953d772cff539dff0ccf4bb757f297e6a18620cd609ba077108409`): `ShadowInstrumentation.startService` (629-630) only records the intent in its started-services queue. It doesn't deliver it.

## Controls (added to `CardServiceCharacterizationTest`; CI pending)

Both use the fixture's idle disposable real managers, caches, native SQLite and services, the public intent senders, and the real `onStartCommand` called directly. There are no download tasks, network, timers, or private manager changes.

1. **`aCommandQueuedForTheOldCardRunsAgainstTheReplacementManager`**
   - **Setup:** the card service binds the original manager, which is then paused. A non-foreground **RESUME** is sent and captured from the queue.
   - **Expected while queued:** the original stays paused, so nothing delivered the command.
   - **Then:** the old service is destroyed, the helpers cleared, the replacement shelf selected and a new service created. That service's `onCreate` resumes the replacement, so the replacement is paused again through the public API.
   - **Expected after delivery:** delivering the captured intent to the new service's `onStartCommand` **resumes the replacement**, and the original stays paused.
2. **`aCardCommandDeliveredWithNoCardSelectedRunsAgainstThePhoneManager`**
   - **Setup:** a non-foreground **PAUSE** is queued while the card manager is bound; the card manager stays resumed, so the command is only queued.
   - **Then:** destroy, clear, and set no card. A new card service selects the phone manager through the existing fallback (`MuonDownloadService.kt` 43).
   - **Expected after delivery:** the card-class command **pauses the phone manager**, and the card manager is untouched.

## Limits

- **Manual delivery only:** delivery is the real `onStartCommand` called directly with the captured intent. It doesn't show Android's queue scheduling, a delivery after process death, or any timing.
- **State changes only:** pause and resume change manager state only. No real download, remove or data operation runs.
- **No fix or safety claim:** this is not a production fix, generation gate, catalog or card-safety property, and nothing here permits cache release or adoption.

**Cleanup:** each test drains the started-service queue before capturing and asserts it's empty at the end. The fixture's teardown destroys every service, restores the Store, releases managers before caches, clears helpers and closes the database.

**Next stop-contract prerequisite:** application-side checks can't stop commands already queued. The service owner (S4) needs either to attach and check a generation on each delivered command, or to refuse or stop delivery for an old generation, before any rebind is treated as safe.
