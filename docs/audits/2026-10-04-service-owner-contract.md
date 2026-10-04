# #179 service-owner stop and rebind contract — 2026-10-04

Inspected main `b1b572abc8d83789798006483be7509ed484013d`, branch `codex/service-owner-contract`. Compared with open #302 at `51493a7a083ebdbb82d88e65b0b064e9759a0aed`.

**Attribution:**
- **Author:** Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading and design.
- **Review:** GPT-6 / Codex desktop (effort not reported) independently reviewed draft `9c8e449` and found blocking factual and design errors (listed at the end). This revision is the author's correction against that review; the coordinator wrote none of the text.

Documentation only: no app or test change, no compile, no device. #179 remains open.

**Usage:** the author's client didn't show a percentage. The coordinator's runtime reported 17% of the five-hour and 9% of the weekly allowance used.

**Question:** using only public or protected Media3 and Android APIs, where can a service owner's command admission, creation admission, old helper callbacks and already-posted service callbacks be frozen, cancelled or retired? Is an in-process card replacement (rebind) supportable with the existing `DownloadService`?

**Short answer:**
- **Commands:** can be refused at delivery in the subclass's `onStartCommand`. A comparison there with an app-owned binding receipt or a captured generation tells stale app commands apart.
- **Creation through the current superclass:** a command gate or a pause after creation can't refuse it safely. Skipping `super` means owning the whole service lifecycle and foreground behaviour.
- **Old helpers:** they stay registered on their manager. Only `DownloadManager.release()` retires their callbacks, under ordering conditions that are not yet established.
- **Rebind:** a coordinated in-process rebind through the documented public clear may be supportable in principle. The present owners and proofs don't establish it.
- **Current binding:** main and #302 bind the card shelf once per process. That is **current binding behaviour, not an implemented quarantine**, and a fresh process doesn't make adoption safe.
- **Next slice:** a narrow delivery admission on the card service, with a process-scoped binding receipt.

## Pinned sources

- **Version:** `app/build.gradle.kts` 82-84 pins Media3 1.11.0.
- **`exoplayer.jar` sources:** the local copy in the service-command-control worktree (`build/sources`), SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. That matches the hash recorded in earlier reports for the published 1.11.0 sources. The jar's manifest carries no version, so the version match rests on that recorded hash.
- **`datasource.jar` sources:** the local copy in the source-close-control worktree, SHA256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`.
- Both were read with Python's `zipfile` only; nothing was executed. Line numbers count `\n`-separated lines.
- **Not verified:** Android platform behaviour beyond these sources. No AOSP or framework documentation was checked in this pass.

## Confirmed source evidence

### `DownloadService.java`

- **Static helper map** (188-194): one helper per concrete class. Its comment says this makes `getDownloadManager` a once-per-subclass call.
- **`getDownloadManager` contract** (727-734): called on the first `onCreate` of a subclass. Later instances reuse the manager returned then, **without calling it again**.
- **`onCreate`** (585-613):
  - It looks up the helper for the class (596).
  - **Only when none exists** does it call `getDownloadManager()` (604), call `resumeDownloads()` on that same manager (605), build the helper with it (606-608) and put it in the map (609).
  - Otherwise it reuses the mapped helper. Either way it attaches the service (611-612).
- **The helper's constructor** (960-973) registers the helper as a manager listener (971). The helper class is `private static final` (949), so the app has no reference to pass to `removeListener`.
- **`attachService`** (975-988): when the manager is initialized, it posts `notifyDownloads` **at the front of the main queue** through a new anonymous handler (984-986).
  - That handler is distinct from the manager's `applicationHandler` and from the notification updater's handler.
  - The runnable captures the service instance and the helper's manager.
- **`notifyDownloads`** (800-809): if any current download needs a started service, it calls `startPeriodicUpdates()`. It doesn't check `isDestroyed`.
- **Notification updater:**
  - `startPeriodicUpdates` (900-903) sets the flag and calls `update()`.
  - `update()` (923-945) reads the helper's manager (924-926) and shows the notification as foreground (929-934) or through `notify` (939-940). While periodic updates are on, it re-posts itself (942-945).
- **`onStartCommand`** (615-696) is public and not final:
  - A null intent is treated as INIT, with the comment that a null action means the service was restarted (627-630).
  - `startedInForeground` comes from `KEY_FOREGROUND` or the private `ACTION_RESTART` (624-625).
  - It reads the manager from the **current helper** (631) and dispatches the public actions (632-684).
  - It shows the foreground notification when started in the foreground (686-689), calls `onIdle` when `isIdle()` (692-694), and returns `START_STICKY` (695).
- **Actions and keys:**
  - `ACTION_INIT` (68), the mutating actions (85-153) and `KEY_FOREGROUND` (173-178) are public.
  - `ACTION_RESTART` is private (70-72).
- **`onDestroy`** (709-716): sets `isDestroyed`, detaches the service from the helper (712, `detachService` 990-993) and calls `stopPeriodicUpdates()` (713-714). That clears **only** the updater handler's callbacks (905-908).
  - It does **not** remove an attach runnable still queued on the anonymous handler.
  - It doesn't remove the helper's listener or the map entry.
- **`clearDownloadManagerHelpers`** (574-583): the public, documented mechanism for apps using different download directories for multiple users, called "before restarting the service". It clears the map only.
- **Restart paths:**
  - `onDownloadChanged` (1045-1059) and `onWaitingForRequirementsChanged` (1083-1100) call `restartService` (1121-1144) whenever `serviceMayNeedRestart()` (1117-1119) is true.
  - The restart is the private `ACTION_RESTART` with no extras (1124-1125).
- **Scheduler:** Muon returns `null` from `getScheduler`, and `onCreate` asks for one only below API 31 (598-603).

### `DownloadManager.java`

- **Starts paused:** a new manager is paused (244).
- **Pausing gates downloads, not removals:** `canDownloadsRun` (1201-1202) gates downloads, while `syncRemovingDownload` (1041-1069) starts remove tasks whatever the paused state.
- **Listener calls:** listeners are called synchronously from public setters, for example `setDownloadsPaused` 534-550, and from main-handler messages (596-655). `addListener` and `removeListener` (317-329) are public, but the helper is unreachable.
- **`release()`** (500-532): documented as "must not be accessed after".
  - It waits for the internal thread's acknowledgement (511-517).
  - It then removes the `applicationHandler` callbacks only (522) and stops the requirements watcher (523).
  - The internal release (940-955) cancels tasks with `cancel(true)` and quits the thread. `Task.cancel` (1316-1329) interrupts the task without joining it.
  - **Release does not drain workers.**

### `SimpleCache.java` (datasource)

- **Folder lock:** a process-static set of locked folders (67) makes a second `SimpleCache` on the same folder throw (217-218) until `release()` unlocks it (262-273, 818-823).
- **Construction can delete:**
  - **`initialize`** (520-567) scans the folder.
  - **`loadDirectory`** (585-627) deletes empty subdirectories (592-596). It also deletes files that don't parse as spans against the content index (617-624).
  - **Other paths:** `removeEmpty` (567) and stale-span removal (724) also run.

### Muon callers

- **Service bindings** (`MuonDownloadService.kt`, identical on main and #302):
  - The phone class returns `phone.manager` (24).
  - The card class returns `(card ?: phone).manager` (43).
  - Neither overrides `onCreate`, `onStartCommand` or `onDestroy`. No Muon code calls `getDownloadManager` itself.
- **Card shelf created once per process:**
  - On main, `OfflineStore.create` (90-135) sets `card` **once** (130-133). It is never reassigned, and `clearDownloadManagerHelpers` and `release` are never called in production.
  - #302 is the same (its `OfflineStore` 150-156), adding `cardPresent(folder)` as that folder's availability.
- **Command senders on main:**
  - `add` (234, target 159-160)
  - leftover removes in `watch` (147-148)
  - `remove` (287)
  - `resume` (295)
  - move Add (326)
  - `removeAll` (346)
- **What #302 gates:** it checks availability at those senders (309, 369, 382, 437, 508; `leftoverCopies` 177). It states that a running or system-restarted card service is not covered.
- **Test file:** #302 predates the newer main tests in `CardServiceCharacterizationTest`.

## Where each owner can be stopped

| Owner | Supported lever | What it achieves | Evidence |
| --- | --- | --- | --- |
| **Commands delivered to a service** | Override `onStartCommand` and compare in-process state at delivery: an app-owned binding receipt (below) or a captured generation. For a refused mutating public action, call `super` with a copy of the intent whose action is `ACTION_INIT` (extras kept). Pass every other action through. | **Refuses that command's mutator at delivery.** Delivery itself isn't cancelled. `super` still runs INIT, which reads the helper's manager for the idle check (692) and, when started in the foreground, for the notification (686-689, 924-926). | Confirmed source |
| **Generation (epoch) extras** | The app stamps its own sends with a generation, and the gate compares it with the generation captured for the current binding. | **Distinguishes stale app commands** from current ones. It is **not authentication**: any in-process sender can set it. It doesn't cover untagged internal RESTART or INIT intents, the creation resume or helper callbacks. | Confirmed source (the restart has no extras, 1124-1125) |
| **Creation** through the current superclass | Which manager `getDownloadManager` returns. `super.onCreate` resumes it when making a helper (604-605). | **A command gate or a pause after creation can't safely refuse it.** The resume (605) happens before any `onStartCommand`, and a later pause races tasks the internal thread may already have started (534-550, 959-1028). Not calling `super` is possible in principle, but then the subclass must own **all** lifecycle, helper and foreground behaviour itself, since `super`'s other methods need the helper (631, 712, 924). That is a custom service, not a gate. | Confirmed source; the race is inferred from message order |
| **Old helper callbacks and restarts** | `DownloadManager.release()` only: it removes pending `applicationHandler` messages and stops the watcher. | Retires the manager's own callbacks to the helper. Afterwards the manager must not be touched, yet the helper still holds it. No service instance of that class may then reach it, either through a map entry or a queued attach runnable (next row). | Confirmed source; the ordering requirement is a recommendation |
| **Already-posted attach runnable** | None public. It is on an anonymous handler (984-986), and `onDestroy` doesn't remove it (709-716, 905-908). | **Not retired by destroy.** If it ran after `onDestroy`, `notifyDownloads` (800-809) could restart periodic updates on the destroyed instance (900-903, 923-945): a notification reactivation that no public API stops, which would read the manager. | Qualified source risk; no runtime or device defect is proven |
| **Main-thread barrier after detach** | After the `onDestroy` receipt, post an ordinary main-thread runnable and wait for it before any release. | By `MessageQueue` ordering, a front-of-queue attach runnable posted earlier on the main looper should run before a barrier posted later. That is queue ordering, not a synchronous receipt, and it carries no general Android timing guarantee. It does **not** detect or undo a reactivation that already happened, so a release still needs evidence that none occurred. | Recommendation; bounded completion and reactivation aren't established |
| **Service lifetime** | `Context.stopService`, observed through an `onDestroy` override that calls `super`. | No stable stop. A later start, from an app sender or a helper restart, creates a new instance, which re-attaches the same mapped helper. | Confirmed source; platform timing unverified |
| **The manager's own tasks** | `pauseDownloads` stops downloads, not removals. `release` cancels without joining. | Needs the ordered stop below, not a service lever. | Confirmed source; #296 |

## Required stop order for any future coordinator

This follows #296 and keeps manager release **before** downloader refusal. Otherwise a live remove task could clear an index row when the gate refuses it.

1. Freeze app and service operations: senders, delivery admission and owner leases. A coordinator may acquire owners before this.
2. Release the manager, or request cancellation, with the download executor **still usable**.
3. Close downloader admission.
4. Drain tasks and cache users, with a bound.
5. Only then release the cache.

`manager.release()` doesn't drain workers, and it has no timeout: it waits on the internal thread. No bounded release is proposed here. Any unsafe order is a defect, not permission. This pass doesn't establish the service-specific steps either: the receipt that no instance can reach a released manager, and the attach-runnable condition above.

## The approaches the task asked about

1. **Delivery gate or stamping alone:** covers delivered app commands only. It doesn't cover the creation resume, internal restarts, helper or attach callbacks, the manager's tasks or other owners. **Useful, but incomplete.**
2. **Skipping `super.onCreate`:** this isn't an Android prohibition. It means replacing `DownloadService`'s lifecycle, helper and foreground behaviour, so it is a custom-service architecture, not a small change.
3. **`onDestroy` plus a clear:**
   - The clear is the documented public mechanism, but it is global. It also forgets the phone helper.
   - The phone helper stays registered on the phone manager, so the next phone-service creation adds a second helper and resumes the phone manager again.
   - The old card helper keeps its unreleased manager and can still restart the class.
   - **Not a stop by itself.**
4. **Release ordering:** supportable only inside the full ordered stop above, with the service conditions settled. Two device questions remain open:
   - the main-thread wait in `release()` while the internal thread is stuck in I/O on a removed card;
   - same-folder cache release before reconstruction.
5. **Once-per-process binding (no rebind):** this is **current binding behaviour on main and #302, not an implemented quarantine.** For an ejected card, the senders (#302 S1) are the only gate. It doesn't reach:
   - delivered commands;
   - the first-creation resume;
   - restarts;
   - the attach runnable;
   - the manager's tasks;
   - readers, the mover or the copier.

**Rebind conclusion:** an in-process rebind may be supportable in principle through the documented clear plus the full ordered stop. The present owners, receipts and proofs don't establish it.
- **Recommended:** an architecture change, but it isn't proven to be the only route.
- **Options:**
  - an app-owned service facade;
  - per-generation concrete service classes;
  - keeping the current binding while the catalog and identity work proceeds.
- **A fresh process doesn't make adoption safe:** `SimpleCache` construction on different or remounted media may delete files and folders (592-596, 617-624, 567). Adoption needs the S2 identity and catalog decisions first, wherever it happens.

## Other constraints

- **Intent queue and manager ownership:** an intent carries only the class, action and extras. The manager is resolved at delivery from the class's mapped helper (631). Under the current binding, a class's manager is fixed until process death or a clear.
- **Phone fallback:** the card class falls back to the **phone** manager when `card` is null at its first creation. The resume at 605 then applies to the phone manager **before** any gate can run.
- **Restarts and pending starts:**
  - `START_STICKY` (695) is not a redelivery mode: the delivered intent isn't kept for redelivery. Per the source comment (627-630), a restart may arrive with a null intent, which becomes INIT and carries no mutator.
  - Whether an **undelivered** pending start can reach a later process is not verified here.
  - Any card-command misroute to the phone after process death is therefore source-inferred and conditional on that, not established.
- **Process death** ends every in-process owner. On the next start, `OfflineStore.get` runs before either service binds. That ordering doesn't make the cache construction it triggers non-destructive.
- **Shared database:** both indexes and both caches share one `StandaloneDatabaseProvider`. No per-generation close is proposed.
- **Foreground:** a start that is foreground, through the private RESTART or `KEY_FOREGROUND`, still has to show the foreground notification in time.
  - Turning a command into INIT keeps `KEY_FOREGROUND` but **doesn't waive that deadline**. `super` meets it only by reaching 686-689 and completing `update()`.
  - Muon's own senders pass `foreground=false`.
  - Under the fallback binding, a phone download can restart the card class, showing a second progress notification (ID 3). This already happens today and isn't fixed by a delivery gate.
- **Timeouts:** `onTimeout` calls `stopSelf` (703-707). INIT stops the service once the manager is idle (692-694, 838-859).

## Recommendation: one production slice

**PR: card-service delivery admission with a process-scoped binding receipt.**

**Binding receipt** (the review found the draft's per-instance field broke every recreated service):
1. In `MuonCardDownloadService.getDownloadManager`, store the manager about to be returned in an **app-owned, process-scoped** field (a companion or top-level value), then return it.
   - The source supports treating this as the helper binding. Media3 calls this override only at 604. It then builds the helper with that same value (606-608) and keeps it in a map that, like the receipt, lasts until process death or a clear (188-194, 574-583).
   - **Reused helpers:** a later instance doesn't call the override again, but the process-scoped receipt survives.
   - **A clear:** the next creation calls the override again and overwrites the receipt.
   - **A failed creation:** if `onCreate` throws after 604, there is no helper. The next creation overwrites the receipt.
2. In `onCreate`, after `super.onCreate()` returns, **snapshot** the receipt onto the instance.
   - **Why it matches:** Android allows one live instance per service component, so the snapshot equals the receipt in effect when this instance attached.
   - **Clears:** if the map is cleared while this instance lives, the instance keeps its old helper and snapshot. The new receipt only applies to the next instance.
   - **Limit:** the snapshot is inferred from pinned source. It doesn't observe the helper. A Media3 upgrade must re-verify 604-609, and Muon must never call `getDownloadManager` itself.
3. Override `onStartCommand`.
   - **Admit** the six mutating public actions only when the snapshot **is** `OfflineStore.current()?.card?.manager`, and, with #302, `card.available()`.
   - **Otherwise** pass `super` a copy of the intent with action `ACTION_INIT` (extras kept) and log a warning.
   - **All other actions** pass through unchanged.
4. **Process death:** it resets the receipt, the map and the store together.

**Optional generation stamp:** a per-process app generation added to Muon's own sends and compared at the gate would also refuse stale app commands. It covers no internal intents, creation or callbacks, and it isn't authentication. The receipt alone covers the phone-fallback case, so the stamp is optional.

**Prerequisites:**
- **Availability clause:** #302 accepted and merged. It doesn't touch `MuonDownloadService.kt`.
- **While #302 is pending:** the narrower **identity-only** fix (bound manager is the card manager, no availability clause) can go on main. It stops a card-class command acting on the phone manager.
- **Every application PR:** stays open for the user's QA.

**Acceptance checks** (existing `CardServiceCharacterizationTest` fixture; CI is the first compile):
- **Card bound and available:** ADD, REMOVE, RESUME and PAUSE act on the card manager. This holds on the first instance **and on a recreated instance that reuses the helper**.
- **Card class bound to the phone:** every mutating action leaves the phone manager's state unchanged. This inverts `aCardCommandDeliveredWithNoCardSelectedRunsAgainstThePhoneManager` (203).
- **With #302:** a present-but-unavailable card leaves the card manager's state unchanged.
- **After a test-only clear and a new instance:** the snapshot follows the new helper's manager.
- **Foreground:** a neutralized intent with `KEY_FOREGROUND=true` still reaches the foreground notification. INIT and the restart are unchanged.
- **Unchanged:** the service still stops when idle. No private reflection, helper-map or manager-lifecycle calls are added in production.

**Failure behaviour:**
- **No mutator:** a refused command runs no command-specific mutator. The INIT path still reads the manager for the idle and notification checks.
- **Refused removal:** the card copy stays.
- **Refused move Add:** the source copy stays, because only a completed Add removes it. The card may keep unindexed bytes, wasting space without losing anything.
- **Notices:** the senders' #302 notices stay the user-visible path.

**Stop boundary:** this is delivery admission for app commands only. It is **not** a generation stop, a quarantine, card safety or adoption permission.
- **Not covered:** the creation resume (including the fallback resume of the phone at 605), internal restarts, the attach runnable, the manager's tasks, readers, the mover, the copier and the startup scan.
- **Not permitted:** no cache release, recreation, purge or adoption.

**QA, pending user testing:**
- **Card in:** normal card download, remove and move.
- **Card out:** no crash, and phone downloads unaffected.
- **Not allowed:** failure injection on existing card downloads.

### Alternatives, by concrete constraint

- **Full stop coordinator now:** needs the ordered stop above, with #296 admission and drain, the attach-runnable and no-instance receipts, the release wait on a removed card, and S2 identity. None of these are established yet.
- **Inert manager for the card class without a card:** avoids the phone fallback resume and notification. It needs a custom `WritableDownloadIndex` and downloader factory, and still needs the gate.
- **Pause on unavailability:** removals keep running when paused. It needs an availability event Muon doesn't have, and it races the creation resume.
- **Killing the process to rebind:** abrupt, with no orderly supported path, and it doesn't make construction non-destructive. Rejected.

## Unverified, and device requirements

- **Platform behaviour:** whether pending starts persist across process death, and whether `stopService` drops pending starts. No primary source was checked.
- **Attach runnable:** whether it can run after `onDestroy` in practice, and how long the barrier takes to complete.
- **Release on a removed card:** the `release()` wait during card I/O, and its main-thread effects. Needs a disposable card on a device.
- **Remounted media:** `SimpleCache` construction effects on remounted or different media, beyond the deletion paths read here.
- **No new test:** the remaining questions are platform timing and device I/O. The receipt's behaviour across recreation belongs in the production PR's acceptance tests.

## Coordinator review of draft `9c8e449`, corrected here

1. **Binding receipt:** the draft's binding was set only in `getDownloadManager`, so it failed every recreated instance. It is replaced by a process-scoped receipt plus a snapshot taken after `super.onCreate`.
2. **Generation extras:** they do distinguish stale app commands. They are not authentication, and they don't cover internal intents.
3. **Attach runnable:** `onDestroy` doesn't remove it. Front-of-queue posting is ordering, not a receipt, and the barrier and reactivation risk are now qualified.
4. **Stop order:** corrected to the #296 order, with manager release before downloader refusal. The draft's claim that the drain must come first is withdrawn, and release isn't said to drain or have a timeout.
5. **`START_STICKY`:** it isn't intent redelivery. Pending-start persistence is unverified, and the misroute after process death is conditional.
6. **Overbroad claims:** creation refusal and replacement are now worded as limits of the current superclass and present proofs. The draft treated them as impossible.
7. **Process start:** once-per-process binding is current behaviour, not a quarantine. A fresh process doesn't make adoption safe, because construction can delete files.
8. **INIT path:** neutralized INIT still reads the manager and doesn't waive the foreground deadline. The fallback resume happens before the gate.
9. **Usage:** recorded from the coordinator's runtime. The unrelated connector note is dropped.

**Next step:** the coordinator reviews this revision. If it is accepted, implement the delivery-admission PR: identity-only on main while #302 is pending, with the availability clause after #302 merges. Phone QA stays the user's.
