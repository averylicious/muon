# #179 card-service command admission — 2026-10-04

Inspected main `9cecdd109ab1f5bb3b4dc95113ed764f345f1032`, branch `codex/card-service-command-admission`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Implementation and tests by the author, **not independent review**; pending coordinator review. Not compiled or run locally: Actions is the first compile and test run. #179 remains open. This applies the identity-only slice of the [service-owner contract](2026-10-04-service-owner-contract.md).

**Bug:** `MuonCardDownloadService.getDownloadManager` returns `(card ?: phone).manager`. Media3 calls it once, when it builds the class's helper, and every later instance reuses that helper. A card service first created with no card therefore keeps the **phone** manager. Card commands delivered to it then pause, resume, add, remove or re-stop the phone's downloads.

## Change

- **Binding receipt:**
  - `getDownloadManager` records the manager it returns in an app-owned, process-scoped field before returning it.
  - `onCreate` copies that field onto the instance **after** `super.onCreate()`. A recreated instance therefore gets the binding even though Media3 doesn't call the getter again.
  - A live older instance keeps its own copy.
- **Admission:** `onStartCommand` admits the seven changing public actions only when the instance's manager **is** the store's card manager and is **not** the phone manager:
  - ADD, REMOVE, REMOVE_ALL;
  - RESUME, PAUSE;
  - SET_STOP_REASON, SET_REQUIREMENTS.
- **Refused commands:**
  - `super` receives a **copy** of the intent with `ACTION_INIT` and the same extras, so a foreground start still reaches the foreground notification.
  - The delivered intent isn't changed.
  - No log is written.
- **Unchanged:** a null intent, INIT, Media3's internal restart and unknown actions pass through as before.
- **Not added:** no cache construction or release, helper clear, rebind, availability policy or generation extras. No private Media3 state is read in production.

## Pinned source evidence

Media3 1.11.0 (`app/build.gradle.kts` 82-84). The local `exoplayer.jar` sources have SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`; they were read with Python's `zipfile`, never executed.

**`DownloadService.java`:**
- **Helper map:** 188-194.
- **`onCreate`:** looks up the helper (596). Only when it is missing does it call `getDownloadManager` (604), resume that manager (605), build the helper with it (606-608) and store it (609). Either way it attaches (611-612).
- **Getter contract:** once per subclass per process (727-734).
- **`onStartCommand`:**
  - It is public and not final.
  - Foreground handling: `KEY_FOREGROUND` (624-625), with the notification shown at 686-689.
  - The manager comes from the current helper (631).
  - INIT does nothing (633-636). The changing actions dispatch at 637-680.
  - The idle check is at 692-694.
- **Public constants:** `ACTION_INIT` (68), the seven actions (85-153) and `KEY_FOREGROUND` (173-178) are public. The restart action is private (70-72).
- **Intent builders:** 296-419.

**`DownloadManager.java`:**
- **Synchronous state:** `setDownloadsPaused` sets its field synchronously (534-550), and `setRequirements` (350-358) is synchronous too.
- **Async commands:** add, remove and stop-reason commands are queued to the internal thread.
- **`isIdle`:** (294-296) counts pending messages.
- **Defaults:** a new manager starts paused (244), and `DEFAULT_REQUIREMENTS` is NETWORK (158).

## Tests (`CardServiceCharacterizationTest`; CI pending)

They use the existing disposable fixture: real services and helpers, native SQLite and temporary caches. There is no network, no user files, no phone and no download I/O. Reflection is limited to the fixture's existing store injection and helper inspection.
- **Inverted** `aCardCommandDeliveredWithNoCardSelectedLeavesThePhoneManagerAlone`, previously `...RunsAgainstThePhoneManager`. A queued card PAUSE delivered to the phone fallback no longer pauses the phone, and the delivered intent keeps its action.
- **`noChangingCardCommandAltersAFallbackPhoneManager`:** the phone index is seeded with a stopped download (stop reason 7) and given inert downloaders. All seven changing commands are delivered to the card service bound to the phone. After the manager settles, the phone is still unpaused (and stays paused for RESUME) and its requirements are still the default. The stopped row stays, with its stop reason, and nothing was added.
- **`cardCommandsReachTheCardManagerFromTheFirstAndARecreatedInstance`:** ADD and PAUSE are admitted on the first instance. After destroy, a recreated instance reuses the helper and RESUME is still admitted. The phone is untouched.
- **`aCardAddedAfterThePhoneFallbackDoesNotOpenTheRetainedHelper`:** a store card assigned after the fallback doesn't admit commands that would reach the phone through the retained helper.
- **`anOlderInstanceKeepsItsOwnBindingAfterATestOnlyClear`:** this is artificial. Robolectric allows two live instances, Android doesn't, and production never clears the map. An old instance still bound to the original card refuses PAUSE once the store has a replacement card, and the new instance admits it. A shared receipt without the per-instance copy would have paused the original card.
- **`aRefusedForegroundCommandStillShowsTheForegroundNotification`:** a refused `KEY_FOREGROUND` command still produces the card service's foreground notification (ID 3), and the delivered intent is unchanged.

**Not checked locally:** Robolectric 4.16.1's `ShadowService.lastForegroundNotification`/`lastForegroundNotificationId` were not checked against its published sources, because they weren't available locally. The CI compile is the check.

## Limits

- **Not fixed, by design:** the fallback still resumes the phone manager when the card service is first created without a card (`onCreate` 605, before any command). Media3's internal restarts from the retained helper still run, and a phone download can still restart the card class and show a second progress notification.
- **INIT still reads the manager:** a refused command runs no command-specific mutator, but INIT still reads the helper's manager for the idle and notification checks. A foreground start still has its normal deadline.
- **Reachability on main:**
  - The store's card is fixed for the process, and the senders only address the card service when a card shelf exists.
  - The misroute therefore needs a card command delivered to a class bound to the phone. The tests construct that, and a pending start reaching a later process could cause it, but this is unverified platform behaviour.
  - This is a defensive fix, not a reproduced device failure.
- **Not in this change:**
  - availability (#302);
  - generation stamps;
  - pause or restart retirement;
  - the downloader drain;
  - readers, the mover and the copier;
  - catalog or identity work.
- **Not a stop or quarantine:** this is neither a generation stop nor a quarantine, and it doesn't make the card safe. No cache release or adoption is permitted.
- **Receipt is inferred from source:** the binding receipt relies on the pinned source, not on observing the helper. Re-verify 596-611 on a Media3 upgrade, and Muon must never call `getDownloadManager` itself.

## User QA (pending user testing)

- **Card in:** download, remove, Remove all and move between phone and card still work, with the normal progress notification.
- **No card:** phone downloads and their notification are unaffected, and there are no crashes.
- **Not allowed:** failure injection on existing card downloads or user originals.

**Checks run locally:** `git diff --check` and the CI prose check. `test_ci*.py` doesn't cover app code. No Gradle, Kotlin compile or Robolectric run was done locally.
