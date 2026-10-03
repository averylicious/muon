# #179 retained helper restart control — 2026-10-04

Inspected main `95637089df2b4f52da6197d0804adc7d9073d0a2` (after #313), branch `codex/helper-restart-control`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author tests and source check, **not independent review**. Test-only: no production change, device, network, card or user data. Not compiled locally: Actions is the first compile and run. #179 remains open.

**Question:** after the card service is destroyed and the static helper map is cleared, is the old helper still registered on the old manager, and can it still ask Android to restart the service class? #305 covered lookup only; this covers the retained callback.

## Pinned source

Media3 1.11.0 `exoplayer.jar` from local `build/sources`, sha256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. Line numbers count `\n`-separated lines.

**`DownloadService.java`:**
- **Helper creation:** `onCreate` (586-613) makes a helper only when none is mapped for the class.
- **Registration:** the helper's constructor registers itself as a listener on its manager (`downloadManager.addListener(this)`, 971).
- **Detach only:** `onDestroy` (710-716) only detaches the service (`detachService` 990-993 sets `downloadService = null`). It doesn't remove the listener.
- **Map only:** `clearDownloadManagerHelpers` (581) clears the map only.
- **Restart path:** `DownloadManagerHelper.onDownloadChanged` (1046-1059) calls `restartService()` whenever `serviceMayNeedRestart()` is true and the state needs a started service.
  - `serviceMayNeedRestart()` (1117-1119) is true when no service is attached or it has stopped.
  - The qualifying states (861-865) are DOWNLOADING, REMOVING and RESTARTING.
- **Restart method:** `restartService` (1121-1144) uses `Util.startForegroundService` with the private `ACTION_RESTART` (71-72) when foreground is allowed. For `MuonCardDownloadService` it is, because its notification ID is 3 (`MuonDownloadService.kt`).
- **Not removed elsewhere:** nothing in the inspected service path calls `removeListener` (`DownloadManager.java` 327) on that helper. The manager keeps listeners in a private `CopyOnWriteArraySet` (187).

## Controls (added to `CardServiceCharacterizationTest`; CI pending)

Both reuse the fixture's idle disposable real managers, caches, native SQLite and services, plus its existing field reflection. New reflection reads only the manager's private `listeners` set. Nothing modifies private state beyond the supported `clearDownloadManagerHelpers`.

1. **`destroyedAndClearedHelperStaysOnItsManagerAndCanRequestARestart`**
   - **Setup:** a real card service binds the original manager, is destroyed, and the helper map is cleared.
   - **Expected:**
     - the same helper is still in the original manager's listener set;
     - after draining the started-service queue, a **COMPLETED** callback on the retained helper starts nothing (the negative control);
     - a **DOWNLOADING** callback then records exactly one start of `MuonCardDownloadService` with the RESTART action.
2. **`anOldHelperStillRestartsTheClassAfterAReplacementServiceBinds`**
   - **Setup:** as above, then a replacement card shelf and a fresh service, which selects the replacement manager through a different helper.
   - **Expected:**
     - the old helper stays on the old manager only, not the replacement's;
     - its DOWNLOADING callback still records a RESTART for the same service class, which is now bound to the replacement manager.

## Limitations

- **The callbacks are synthetic.** The test calls the real retained helper's `onDownloadChanged` directly with a constructed `Download`. No task, download, network, mounted card or real manager event runs. It doesn't claim the OS ran a foreground service, or what the restarted service would do.
- **Coordinator pinned-source check:** published [shadows-framework4.16.1 sources](https://repo.maven.apache.org/maven2/org/robolectric/shadows-framework/4.16.1/shadows-framework-4.16.1-sources.jar), SHA256 `977c225559953d772cff539dff0ccf4bb757f297e6a18620cd609ba077108409`. ShadowContextImpl335–342 maps startForegroundService to startService; ShadowContextWrapper95–96 consumes the instrumentation started-service queue. This resolves the source-level API uncertainty, but actual execution remains pending first CI. No downloaded software/JAR was executed locally.

**What this shows:** a retained callback, plus a restart request after detach and map clearing. Destroying the service and clearing the map is **not** listener removal, manager release, or a drain of workers, callbacks or readers.

**Not claimed:** a production barrier, generation, catalog, card safety, or any performance property.

**Cleanup:** the fixture's teardown destroys every service, restores the Store, releases managers before caches, clears helpers and closes the database. Each test drains the started-service queue before its controls and asserts it's empty afterwards.

## Coordinator review

GPT-6 / Codex desktop, effort not reported independently reviewed the actual helper/listener registration, synthetic terminal negative control, active-state restart intent and replacement-service binding against the pinned public sources. Fixture reflection reads the listener set; no task is admitted or private collection mutated. Manager release removes pending application-handler callbacks in the pinned source but is not a worker/cache drain; these controls do not exercise that release boundary. Own report addition is self-reviewed. Exact-head CI and merge receipt follow on the PR.
