# #179 service-owner stop and rebind contract — 2026-10-04

Inspected main `b1b572abc8d83789798006483be7509ed484013d`, branch `codex/service-owner-contract`. Compared with open #302 at `51493a7a083ebdbb82d88e65b0b064e9759a0aed`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading and design by the author, **not independent review**. Documentation only: no app or test change, no compile, no device. Quota telemetry isn't available in this session, so usage isn't reported. #179 remains open.

**Question:** using only public or protected Media3 and Android APIs, where can a service owner's command admission, creation admission, old helper callbacks and already-posted service callbacks be frozen, cancelled or retired? Is an in-process card replacement (rebind) supportable with the existing `DownloadService`?

**Short answer:**
- Commands can be **refused at delivery**, in the subclass's `onStartCommand`.
- Creation can't be refused: the only lever is which manager `getDownloadManager` returns, once per class per process.
- The old helper can't be removed from its manager. It is retired only by releasing that manager, which then forbids any later access through the helper that still holds it.
- An in-process rebind therefore needs the global helper clear, an unbounded main-thread release and a downloader drain. It is not a small change; it needs an architecture change.
- Main and #302 already bind the card shelf **once per process**, so the coherent contract is: no in-process rebind, and adoption happens only at process start. The next production slice is delivery admission on the card service.

## Pinned sources

- **Version:** `app/build.gradle.kts` 82-84 pins Media3 1.11.0.
- **`exoplayer.jar` sources:** the local copy in the service-command-control worktree (`build/sources`), SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. That matches the hash recorded in earlier reports for the published 1.11.0 sources. Its manifest carries no version, so the match to 1.11.0 rests on that recorded hash.
- **`datasource.jar` sources:** the local copy in the source-close-control worktree, SHA256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`.
- Both were read with Python's `zipfile` only; nothing was executed. Line numbers count `\n`-separated lines.

## Confirmed source evidence

### `DownloadService.java`

- **Static helper map** (188-194): one helper per concrete class, documented as making `getDownloadManager` a once-per-subclass call.
- **`getDownloadManager` contract** (727-734): called once per process per subclass, on the first `onCreate`. Later instances reuse the manager returned then.
- **`onCreate`** (585-613):
  - It looks up the helper for the class (596).
  - **Only when none exists** does it call `getDownloadManager()` (604) and `resumeDownloads()` (605), create the helper and store it in the map (609).
  - It always attaches the service to the helper (611-612).
- **The helper's constructor** (960-973) registers the helper as a manager listener (971). The helper class is `private static final` (949), so the app has no reference to pass to `removeListener`.
- **`attachService`** (975-988): when the manager is initialized, it posts `notifyDownloads` **at the front of the main queue** through a new handler (984-986). This is neither the manager's `applicationHandler` nor the notification updater's handler.
- **`onStartCommand`** (615-696) is public and not final:
  - It sets `startedInForeground` from `KEY_FOREGROUND` or the private `ACTION_RESTART` (624-625).
  - It reads the manager from the **current helper** (631) and dispatches public actions to it (632-684).
  - It shows the foreground notification when started in the foreground (686-689), calls `onIdle` when the manager is idle (692-694), and returns `START_STICKY` (695).
- **Actions and keys:**
  - `ACTION_INIT` (68), the six mutating actions (85-153) and `KEY_FOREGROUND` (173-178) are public.
  - `ACTION_RESTART` is private (70-72).
- **`onDestroy`** (709-716): detaches the service from the helper (`detachService` 990-993) and stops periodic notification updates (905-908 removes that handler's callbacks). It neither removes the listener nor clears the map.
- **`clearDownloadManagerHelpers`** (574-583) clears the map only. Its documentation names multi-user download directories as the use, "before restarting the service".
- **Restart paths:**
  - `onDownloadChanged` (1045-1059) and `onWaitingForRequirementsChanged` (1083-1100) call `restartService` (1121-1144) whenever `serviceMayNeedRestart()` (1117-1119) is true.
  - `restartService` sends the private `ACTION_RESTART` through `startForegroundService` when foreground is allowed, which it is for both Muon classes.
  - The restart intent carries no extras.
- **Scheduler:** Muon returns `null` from `getScheduler`, and `onCreate` asks for one only below API 31 (598-603). There is no scheduler restart path.

### `DownloadManager.java`

- **Starts paused:** a new manager is paused (244). Only `resumeDownloads`, for example from the service's `onCreate`, lets downloads run.
- **Pausing doesn't stop removals:** `syncRemovingDownload` (1041-1069) starts remove tasks whatever the paused state. `canDownloadsRun` (1201-1202) gates downloads only.
- **Listener calls:**
  - Listeners live in a `CopyOnWriteArraySet` (187), with public `addListener` and `removeListener` (317-329).
  - They are called synchronously from public setters such as `setDownloadsPaused` (534-550), and from main-handler messages (596-655).
- **`release()`** (500-532) is documented as "must not be accessed after".
  - It **blocks the calling thread** until the internal thread acknowledges (511-517); there is no timeout.
  - It then removes the `applicationHandler` callbacks (522) and stops the requirements watcher (523).
  - The internal release (940-955) cancels tasks with `cancel(true)` and quits the thread. `Task.cancel` (1316-1329) interrupts the task but doesn't join it.

### `SimpleCache.java` (datasource)

A process-static set of locked folders (67) makes a second `SimpleCache` on the same folder throw (217-218) until `release()` unlocks it (262-273, 818-823).

### Muon callers

- **Service bindings** (`MuonDownloadService.kt`, identical on main and #302):
  - The phone class returns `phone.manager` (24).
  - The card class returns `(card ?: phone).manager` (43).
  - Neither overrides `onCreate`, `onStartCommand` or `onDestroy`.
- **Card shelf created once per process:**
  - On main, `OfflineStore.create` (90-135) sets `card` **once** (130-133). It is never reassigned, and `clearDownloadManagerHelpers` and `release` are never called.
  - #302 is the same (its `OfflineStore` 150-156). It adds `cardPresent(folder)` as the shelf's availability, and that availability belongs to that folder only.
- **Command senders on main:**
  - `add` (234, target 159-160)
  - leftover removes in `watch` (147-148)
  - `remove` (287)
  - `resume` (295)
  - move Add (326)
  - `removeAll` (346)
- **What #302 gates:** it checks availability at those senders (309, 369, 382, 437, 508, and `leftoverCopies` at 177). It states that "A card service already running, or restarted by the system, is not covered" (#302 `resume`).
- **Test file:** #302 predates the newer main tests in `CardServiceCharacterizationTest`, so the two branches differ there. That is not a deletion to preserve.

## Where each owner can actually be stopped

| Owner | Supported lever | Freezes? | Evidence |
| --- | --- | --- | --- |
| **Commands delivered to a service** (queued in-process; after process death, a pending start the platform redelivers) | Override `onStartCommand` and check in-process state at delivery. For a refused mutating public action, call `super.onStartCommand` with a copy of the intent whose action is `ACTION_INIT`; the copy keeps the extras, `KEY_FOREGROUND` included. Pass every other action (INIT, the private RESTART, unknown) through unchanged. | **Refuses at delivery.** Delivery can't be cancelled, and the platform keeps scheduling it. | Confirmed source. Redelivery after process death is documented Android `START_STICKY` behaviour, **not reproduced** here. |
| **Intent epoch extras** | Any in-app sender can set extras. The restart intent has none (1124). | No: an extra is a hint, not authentication or full coverage. | Confirmed source |
| **Creation** (first `onCreate` of a class in the process) | Only *which* manager `getDownloadManager` returns. `super.onCreate` always resumes it when it makes the helper. | **No.** Skipping `super.onCreate` leaves the helper field null, so later `super` calls fail on their non-null checks. Avoiding that means overriding every lifecycle method and relying on private behaviour, which is **unsupported**. Pausing right after `super.onCreate` is a race: the internal thread may start tasks between the two messages. | Confirmed source; the race is inferred from the message order (534-550, 959-1028). |
| **Old helper callbacks and restarts** | Only `DownloadManager.release()`, which removes pending main callbacks and stops the watcher. | Yes, but only by release, after which **nothing** may touch that manager. The helper still holds it, so any later service instance of that class would. The map must be cleared in the same main-thread turn, with no live instance of the class. | Confirmed source; the ordering requirement is a recommendation. |
| **Already-posted service callbacks** | The attach post runs at the front of the queue right after `onCreate`, before the app can act. `onDestroy` removes the notification updater's callbacks. | Effectively retired by `onDestroy`, plus detaching the service (helper calls check `downloadService != null`). | Confirmed source |
| **Service lifetime** | `Context.stopService`, observed through an `onDestroy` override that calls `super`. | **No stable stop.** A pending start or a helper restart can create a new instance at any time. Without a clear, it re-attaches to the same helper and manager. | Confirmed source. Platform timing needs a device. |
| **The manager's own tasks** (downloads in progress, removals) | `pauseDownloads` stops downloads only. `release` cancels without joining. | **No** from the service layer. This needs the #296 downloader admission and drain. | Confirmed source; #293/#294/#296 |

## The approaches the task asked about

1. **Stamping in a wrapper or `onStartCommand` alone:** covers delivered commands, the only service-entry lever. It doesn't cover the creation resume, helper restarts, manager tasks or other owners. Stamps aren't authentication. **Useful, but incomplete.**
2. **Skipping `super.onCreate`:** depends on private null-handling and breaks `onStartCommand`, `onDestroy` and notifications. **Rejected.**
3. **`onDestroy` plus a clear:**
   - The clear is global, so it also forgets the phone helper.
   - The phone helper stays registered on the phone manager, and no public API can remove it. The next phone-service creation then adds a second helper and resumes the phone manager again.
   - Each clear leaves one more permanent restart listener behind.
   - The old card helper keeps its unreleased manager and can restart the class.
   - **Not a stop.**
4. **Release order** (stop, `onDestroy` receipt, then in one main-thread turn release and clear):
   - Release blocks the main thread with no timeout. The internal thread may be stuck in index or file I/O on a removed card, which is an ANR risk that needs a device.
   - Release doesn't join workers, so the #296 drain must come first.
   - A same-folder cache must also be released before it can be reconstructed.
   - **Supportable only as part of a full coordinator.**
5. **Process-lifetime no-rebind quarantine:** this is effectively **already the architecture** on main and #302. A card is bound at Store creation and never replaced.
   - **What it doesn't gate:** as a quarantine of an *ejected* card it reaches only the senders (#302 S1). It doesn't reach delivered commands (open), the first-creation resume, helper restarts, the manager's own removal and download tasks, or readers, the mover and the copier.
   - **So it isn't a complete quarantine:** it must not be described as card safety, and it never authorizes cache release.

**In-process replacement or rebind is not supportable with the existing `DownloadService` without an architecture change.** The candidate changes are:
- an app-owned service facade that owns its managers' listeners, notification and lifecycle instead of the Media3 static helper;
- per-generation concrete service classes, which need manifest entries and come from a finite pool;
- or keeping the current binding and allowing adoption **only during Store construction in a fresh process**, after the S2 identity and catalog decisions.

The last needs no new service machinery. It is the recommended contract direction, but the identity and catalog decisions are unresolved, and so is any user-visible path to a fresh process.

## Other constraints that shape the contract

- **The intent queue and manager ownership:**
  - An intent carries only the class, action and extras. The manager is resolved at delivery from the class's helper (631).
  - With no in-process rebind, a class's manager is fixed for the process.
  - The card class falls back to the **phone** manager when `card` is null at its first creation. That can happen after a sticky restart or redelivery in a new process without the card, or when the card shelf failed to construct.
  - A card-class REMOVE, for example the leftover remove after a card-to-phone move, would then remove the phone's copy. This is source-inferred; neither the platform redelivery nor this misroute has been reproduced.
- **Process death** ends every in-process owner and is the only release on main. On the next start, `OfflineStore.create` runs before any service binds, because both `getDownloadManager` overrides call `OfflineStore.get`.
- **The shared database:** both indexes and both caches share one `StandaloneDatabaseProvider`. No per-generation close is possible or proposed.
- **Foreground notification contract:**
  - The private RESTART and any `KEY_FOREGROUND` start are foreground starts. The service must reach `super`'s `showNotificationIfNotAlready` (686-689) or it fails the platform's foreground-start contract.
  - A delivery gate must therefore pass RESTART through and keep `KEY_FOREGROUND` on any intent it neutralizes.
  - Muon's own senders all pass `foreground=false`.
  - Under the fallback binding, a phone download can restart the card class, giving a second progress notification (ID 3) for phone downloads. This already happens today, isn't fixed by a delivery gate, and isn't destructive.
- **Timeouts and failures:** `onTimeout` calls `stopSelf` (703-707). A neutralized command becomes INIT, which stops the service once the manager is idle (692-694, 838-859). There is no timeout primitive for release.

## Recommendation: one production slice

**PR: card download service delivery admission.**

**Changes, `MuonCardDownloadService` only:**
1. Record the manager its own `getDownloadManager` returned, in an app-owned field on the class. Media3 calls it once per class per process, and Muon never clears the map.
2. Override `onStartCommand`. Admit the six mutating public actions only when that bound manager **is** `store.card?.manager` and, with #302, `card.available()`.
3. Otherwise pass `super` a copy of the intent whose action is `ACTION_INIT` (extras kept) and log a warning. Pass every other action through unchanged.

**What it doesn't do:**
- no clear, release or pause/resume injection;
- no epoch extras or new classes;
- no change to the phone class;
- no private reflection.

**Prerequisites:**
- **Availability clause:** #302 accepted and merged, since it supplies `Shelf.available()`. #302 doesn't touch `MuonDownloadService.kt`, so the two compose without conflict.
- **If #302 is still pending:** the identity-only clause (bound manager is the card manager) is independently useful on main. It closes the phone-fallback misroute. The availability clause would follow #302.

**Acceptance checks** (Robolectric fixture already in `CardServiceCharacterizationTest`; CI as the first compile):
- With the card class bound to the card manager and the card available, ADD, REMOVE, RESUME and PAUSE still act on the card manager.
- With the card class bound to the phone, every mutating action leaves the phone manager unchanged. This **inverts** `aCardCommandDeliveredWithNoCardSelectedRunsAgainstThePhoneManager` (203).
- With #302 and a present-but-unavailable card, mutating actions leave the card manager unchanged.
- A neutralized intent that had `KEY_FOREGROUND=true` still shows the foreground notification. INIT is unchanged.
- The service stops once the manager is idle.
- No helper-map or manager-lifecycle calls are added.

**Failure behaviour (data-preserving):**
- A refused command never reaches a manager, so nothing is removed or rewritten.
- A refused removal leaves the card copy listed.
- A refused move Add leaves the source copy, because only a completed Add removes the source. It may leave unindexed bytes on the card, which wastes space but loses nothing.
- There is no user notice from the service. The senders' #302 notices remain the visible path.

**Stop boundary:** this is command admission at delivery only. It is **not** a generation stop or a quarantine of every owner, and it doesn't make the card safe.
- **Still open:** the creation resume, helper restarts, the card manager's own tasks, readers, the mover, the copier and the startup index scan.
- **Not permitted:** no cache release, recreation, purge or adoption.

**QA, pending user testing:**
- Normal card download, remove and move with the card in.
- With the card out, no crash and phone downloads unaffected.
- No failure injection on existing card downloads.

### Alternatives, by concrete constraint

- **Full stop coordinator now:** needs the #296 drain, a main-thread release with no timeout, a global clear that leaks a phone helper, and S2 identity. Too big, with unresolved device risk.
- **Inert manager for the card class without a card:** removes the duplicate notification and the misroute. It needs a custom `WritableDownloadIndex` and a throwing downloader factory, and still needs the delivery gate. It is more scaffolding for the same command coverage.
- **Pause on unavailability:** removals keep running when paused (1041-1069). It needs an availability event Muon doesn't have, and it races the creation resume.
- **Killing the process to rebind:** abrupt, interrupts playback, and has no supported orderly path. Rejected.

## Unverified, and device requirements

- **Platform behaviour:** `START_STICKY` redelivery of pending starts after process death, and whether `stopService` drops pending starts, come from documentation or are unknown here. Needs AOSP reading or a device.
- **Release on a removed card:** the main-thread block in `release()` and its ANR risk need disposable-card device QA.
- **`SimpleCache` on remount:** how construction treats an existing card folder, and whether card contents changed while ejected, are S2 and catalog questions, not service ones.
- **No new test:** the open questions are platform scheduling and device I/O, which a Robolectric control can't settle, and the existing controls already cover the source-level behaviour.

**Next step:** the coordinator reviews this contract. If accepted, implement the delivery-admission PR above, availability clause included, once #302 merges. If #302 is still pending, ship the identity-only version on main. Either way the phone-fallback test expectation is inverted in the same PR, and phone QA stays the user's.
