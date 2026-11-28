# #253 removal delivery — 2026-10-09

One bounded #253 slice on `codex/removal-delivery-oct9` in `~/.codex/worktrees/muon-removal-delivery-oct9`. It includes main `c1708a58a4f32461a5528bdac2c993daea91f3ec` and open app parent #414 `039362ee274eb78d4cc037021fdc44d6e7fe41d5`. Source and tests only, **uncommitted for Codex review**.

No local Gradle or APK build was run; CI will be the first compile, and **every test below is pending**. No device, experimental checkout, push, PR or merge. This grants no Stable or merge permission.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort explicitly selected; implementation. Not independently reviewed.

## Defect

`OfflineStore.removeSavedNow` and `removeAllNow` started one untagged removal per checked row and counted each as sent. The services' `DownloadCommandBudget` admits only `DOWNLOAD_REMOVAL_COUNT` (4) removals per manager until it is idle. A Remove all of more than four rows was therefore partly refused at delivery, with one toast per refusal and no truthful producer result. Single removal also reported nothing if the service refused it.

## Pinned Media3 1.11.0 checked

From `/tmp/muon-oct8-exoplayer-1.11.0-sources.jar`, the published `media3-exoplayer` 1.11.0 sources jar, the same one identified in the save-delivery checkpoint (SHA-256 `2d583de9…a60ed6`; not re-hashed in this session):

- `DownloadService.onStartCommand` `ACTION_REMOVE_DOWNLOAD` calls `downloadManager.removeDownload(id)` synchronously.
- `DownloadManager.removeDownload` increments `pendingMessages` before posting. `onMessageProcessed` notifies `Listener.onIdle` on the application thread once nothing is pending and no task is active.
- The listener set is a `CopyOnWriteArraySet`, so removing a listener inside `onIdle` is safe.
- `ACTION_INIT` changes no command state.

## Change

**New `DownloadRemovalDelivery.kt`** holds one process-owned batch per `Store`.

- **Bounds:** at most 128 checked removals (`DOWNLOAD_COMMAND_COUNT`) and 4 MiB of logical ID text (`RemovalSelection`, `removalCost`).
- **Windows:**
  - The batch sends at most four removals at a time, all to one shelf.
  - The first window goes from the producer thread. Each later window is sent from the main thread only after the manager that ran the previous window reports idle, which is when its removal budget resets.
- **Tokens:** each send carries a fresh token bound to the exact `Shelf` and ID. It must be claimed once, before admission.
  - Unknown, retired, replayed, wrong-shelf or wrong-ID copies become INIT and change nothing.
  - So do copies carrying a move or save token, and copies whose action isn't a removal.
- **Acknowledgement:** a removal is acknowledged as accepted only after the real Media3 `onStartCommand` returned with the removal admitted. Accepted means queued, not yet deleted.
- **Unconfirmed:** if the service throws after forwarding, the removal is counted **unconfirmed**, never unchanged.
- **Refusals before Media3:** a budget refusal, move exclusion or wrong-card binding leaves the row unchanged.
  - A refused row is retried in a later window, up to three sends in total, but only while other rows in the batch make progress.
  - A window in which nothing was accepted ends the batch. A lasting refusal therefore never spins, and the rest is reported unchanged.
- **Timeout:** each window has a 60-second uptime deadline, with one runnable.
  - Sent-but-unanswered rows are reported unconfirmed (conservative). Their retired tokens can no longer authorize anything.
  - Rows never sent are reported unchanged.
- **Other unchanged outcomes:** a send that Android refuses (null or throws) and a row on an unavailable card are not sent and reported unchanged.
- **Messages:** `removalDeliveryMessage` reports accepted, unchanged, unconfirmed and left-for-later counts. A single removal is silent on success; otherwise it says unchanged or unconfirmed, with retry or check advice.

**`OfflineStore`:**

- **`removeSavedNow`:** every ownership check is unchanged (move receipt invalidation, move exclusion, availability, the exact-locator single-name probe and sole ownership).
  - It then begins a one-row batch instead of an untagged send.
  - It returns the new `SavedRemoval.Waiting` if a batch is already pending, and `Sent` now means "handed over for acknowledgement".
- **`removeAllNow`** returns `RemoveAllPlan(sent, kept, later, busy)`.
  - It keeps the whole-census, hidden-row-inclusive sole-owner selection. The selection is bounded by `RemovalSelection`.
  - Sole owners beyond the bound are counted as `later` (unchanged, named in the final message), never silently dropped.
  - `removeAll` refuses while a batch is pending, as do `removeAllNow` and single removal.
- **`deliverCommand`** routes removal-tokened intents to `deliverRemoval`. That function claims the token, then runs the existing `admitCommand` unchanged: budget, move exclusion, epoch increment and receipt invalidation.
- **Move separation:** a move's source removal keeps its own receipt and `MOVE_COMMAND_TOKEN` path, independent of this batch. A move is now also refused while a removal batch is pending.
- **`refusedRemovalCommand`:** the card service's earlier refusal branch for a wrong or unavailable binding releases only the exact unclaimed token for the intended card shelf.

**`MuonDownloadService.kt`:** the card service's refusal branch also calls `refusedRemovalCommand`.

Nothing deletes or rewrites saved bytes or records to meet a budget. No schema, dependency, package, signing or release change.

## Tests (pending CI)

**New `DownloadRemovalDeliveryTest`**, using the real `MuonDownloadService` / `MuonCardDownloadService`, Media3 `DownloadService` and `DownloadManager`, native SQLite, disposable caches, and a fixture downloader that deletes the real bytes:

1. **Seven sole owners** (five on the phone, two on the card). Windows of four, then one, then two, each to one shelf, each after idle. Every row and its bytes are removed, and the final notice is "Removing 7 saved copies."
2. **Removal budget 0:** one window of four refused, no further window, all six originals and their bytes kept, and the "unchanged" notice. Retry with the normal budget removes all six.
3. **Removal budget 2:** partly refused windows are retried after idle; three windows remove all five.
4. **Service throws after the real `manager.removeDownload`:** reported unconfirmed, not unchanged; the row is actually gone.
5. **Exact claim:** a copy for the wrong shelf, wrong ID, unknown token, an added move token or a non-removal action is INIT and doesn't consume the real command. The real delivery removes only the phone row; the card row of the same name is kept. A replay after a new same-name row is INIT.
6. **Timeout:** reported unconfirmed. The late copy is INIT against the retry's batch, and the retry removes the row.
7. **Card service bound to the phone manager** (created with no card): the card removal is refused and reported unchanged, with the card row and its bytes kept.
8. **Pending batch:** a second removal returns `Waiting`, Remove all returns busy, and a move is refused; nothing changes.
9. **Android refuses the start:** reported unchanged, the batch is released and the row is kept.

**Existing tests adjusted:**
- `DownloadMoveCharacterizationTest` and `MoveCommandAdmissionTest` now compare `RemoveAllPlan` instead of `Pair`.
- `aSoleOwnedRowWithALargeRecordIsStillRemovedOneByOneAndByRemoveAll` now asserts that Remove all waits while the single removal is unacknowledged. It then lets the deadline pass (reported unconfirmed) before Remove all sends.

All other move, admission, card, census and save-receipt suites are unchanged.

## Limitations and next work

- **Remove all is capped per pass:** a library with more than 128 sole owners, or more than 4 MiB of IDs, needs several Remove all passes, and the message says so. UAT on a large disposable library is pending user testing.
- **Census memory is unchanged:** the full destructive census (`IndexCensus` in `removeAllNow`, every row's ID, key and state, held while selecting) is still the separate holder recorded for later. This slice doesn't bound it.
- **Not an exact bound on Android's backlog:** accepted means Media3 queued the removal, not that bytes are gone. The bounds are on the app's pending batch, not an exact Binder/start backlog or heap.
- **No automatic retry after uncertainty:** after a timeout or an exception the user is asked to check Saved copies.
- **Deadline sleeps with the device:** the deadline uses uptime and can pause during deep sleep.
- **Another pending batch blocks single removals:** a single Remove tapped while a Remove all batch is sending its later windows is refused with `Waiting` until the batch ends.
