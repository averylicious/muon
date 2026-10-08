# #253 manager startup view and unsupported bulk download commands — 2026-10-08

One bounded #253 slice in `~/.codex/worktrees/muon-manager-retention-oct8`, based on #400 plus main `9eb3947` (merge `be28308`). Source and tests reviewed by Codex; application acceptance remains open. No local build or test run: Actions will be the first compile and run, and **every test below is pending**. No device, experiment, push or merge. No Stable release or merge permission is implied.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort explicitly selected; implementation. GPT-6 / Codex desktop independently reviewed the implementation and added a combined actual-service/omitted-manager test; exact variant/effort not exposed.

## Source basis

Pinned `media3-exoplayer` 1.11.0, as pinned in `app/build.gradle.kts`. Codex compared `DownloadManager`, `DefaultDownloadIndex`, `DownloadService`, `WritableDownloadIndex` and `DownloadNotificationHelper` byte for byte against the published sources jar, SHA-256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`.

- **`DownloadManager.InternalHandler.initialize`** loads every QUEUED, STOPPED, DOWNLOADING, REMOVING and RESTARTING row as a full `Download`. It keeps them in its internal list for the process, and a copy of that list goes to the main thread on every update.
- **Startup is only stopped rows.** `RetainedDownloadIndex` has already stopped every unfinished row (reason 213) inside that same initialization, so what the manager loads is entirely stopped retained rows.
- **Omitted rows (in the index but never loaded by the manager):**

  | Command | Behaviour for an omitted row |
  | --- | --- |
  | `addDownload`, `removeDownload` | Read it from the index (`loadFromIndex = true`): same result as today. |
  | `setStopReason(id)` | Does not read it; its index fallback only touches COMPLETED/FAILED rows. |
  | global `setStopReason` | Acts only on in-memory rows plus COMPLETED/FAILED index rows. |
  | `removeAllDownloads` | Removes in-memory rows plus COMPLETED/FAILED rows, then `setStatesToRemoving()` marks **every** row REMOVING. The omitted row is left REMOVING with no task. |

- **Unaffected:** resume, pause and requirements only sync in-memory rows. A STOPPED row never starts.
- **`DownloadNotificationHelper`** ignores STOPPED rows; `DownloadService` reads only QUEUED rows when scheduling.

## Change

- **New `ManagerStartupIndex`** wraps `RetainedDownloadIndex` only in `OfflineStore.shelf`.
  - For the **exact** startup query (that state list in that order), it asks the index for the same states without STOPPED.
  - Every other query is passed through unchanged: all states, single states, reordered, with duplicates or with other states. An unknown startup shape therefore keeps today's retention rather than omitting anything.
  - No row is written, changed or deleted, and there is no shared mutable flag.
  - `addDownload`, `removeDownload`, `getDownload` and all writes delegate unchanged.
- **`OfflineStore.admitCommand`** turns every `ACTION_REMOVE_ALL_DOWNLOADS` and `ACTION_SET_STOP_REASON` into INIT, with the notice "Muon doesn't support that download command, so nothing was changed."
  - This runs **before** the move-token branch, so a token can't bypass it and no receipt is touched.
  - It runs regardless of moves, and does not count as an admitted mutation (no epoch change).
  - Both services already route their commands through `admitCommand`.
- **Why these two aren't supported:**
  - Muon never sends them; the services are private.
  - With retained rows outside the manager, Media3 would apply them to only part of the index. Remove all would leave rows REMOVING with no task, hiding them from Saved until the next restart.
  - Even without the view, these commands are unsafe here. Remove all deletes by cache key, including bytes another row shares (#213). Clearing a stop reason would resume a retained partial download from its old address.
  - The user-facing Remove all is unchanged: `removeAllNow` sends a checked per-row removal for each sole owner.

## Tests (all pending CI)

**`RetainedDownloadStartupTest`** (actual `DownloadManager`, native SQLite, disposable SimpleCache, production composition `ManagerStartupIndex(RetainedDownloadIndex(...))`):

1. **64 rows across two restarts.** 32 are already retained; 32 are unfinished (QUEUED, DOWNLOADING, REMOVING, RESTARTING), all with 64 KiB tags and partial audio.
   - `currentDownloads` stays empty and no downloader is created, even after resume.
   - Every request, timestamp, length, progress and byte is unchanged, and every row is STOPPED/213.
   - The completed row is untouched.
   - Reads through `manager.downloadIndex` return all 65 rows, all 64 STOPPED rows, and the completed one.
2. **Query shape.** Only the exact startup query leaves STOPPED out. Reordered, duplicated, extra-state, partial, single-state and all-state queries are complete.
3. **Differential removal**, with and without the view, using a real progressive downloader. An omitted row is removed with its own bytes, and both produce the same listener events (REMOVING change, then removal).
4. **Differential add.** `addDownload` of the stored request with stop reason 7 merges the full stored record (32 KiB data) identically with and without the view.
5. **Pinned direct hazards**, called directly on the manager only and never by production:
   - `setStopReason(id, NONE)` leaves an omitted row unchanged.
   - `removeAllDownloads()` marks it REMOVING with no task while its bytes stay.

   These fail if a Media3 upgrade changes either behaviour.

**`MoveCommandAdmissionTest`** (real `MuonDownloadService` / `MuonCardDownloadService` and Media3 service):

6. Remove all, global and per-ID set stop reason, and both again with a move token are all refused. The completed row, its stop reason and its bytes are unchanged, and no receipt is pending. Delivered, any of them would visibly change that row.
7. The card service refuses them for its own card manager.
8. User Remove all still sends exactly one checked per-row removal. Delivered to the real service, it removes the row and its bytes.

9. A combined actual-service test uses the production manager composition with a previously stopped row: unsupported globals and per-ID resume leave it intact, then a supported per-ID removal deletes only its disposable bytes/record.

**Existing test adjusted:** `MoveDeliveryOwnershipTest.aCommandBetweenVerificationAndRemovalInvalidatesItsEpoch` now uses an ordinary admitted per-ID removal as the intervening command, because a stop-reason command no longer reaches Media3. All other retention, latch, window, admission and move suites are unchanged.

## What stays unbounded (#253 remains open)

- **The manager's in-memory rows for work this process starts or touches:** new saves, move hand-overs, removals and any omitted row it adds or removes. Their number is limited only by the commands sent, and each update copies that list to the main thread. A separate active-operation budget is still needed (plan §3).
- `RetainedDownloadIndex`'s startup pending-ID list, per-row cursor allocation, and remaining plan items: other queue/cache-key/native-window cardinality; runtime saved paging is already implemented in #399, pending acceptance.
- No heap, jank or device measurement was made, and none is claimed.

Claude completed and left an idle session at99% five-hour used/77% weekly used (runtime event,2026-10-08), with its handoff saved. No further Claude task was assigned; quota not proven exhausted. Final-head CI/build evidence is recorded on the PR and shared checkpoint rather than this preliminary test list.
