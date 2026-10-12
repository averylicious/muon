# Move command admission (#230) — 2026-10-08

Branch `codex/move-command-admission-oct8`, base `56ec5ef` (acceptance `381503983c` integrated with main `f78874a`). Claude Opus 5.5 (`claude-opus-5-5`) in Claude Code, High effort explicitly selected (the runtime does not report effort). Role: implementation and author self-check, not independent review. Coordinator source review and follow-ups: GPT-6 / Codex desktop, exact variant/effort not reported. Added main-looper assertions, pending-hand-over admission and a real pending-removal control. Coordinator owns commit, push, PR and CI; exact receipts on the PR supersede this source snapshot. Not compiled locally; GitHub Actions is the first compile and run.

## Safety contract (what the code does)

1. **Admission.** `OfflineStore.move` starts a move only if both managers are *quiet* and no other move holds the new `MoveExclusion`. If so, it takes the exclusion on the main thread. Otherwise nothing is moved, and the user is asked to try again. *Quiet* means all of:
   - `isInitialized` and `isIdle` (no command waiting on the manager's handler, no task running);
   - no entry in `currentDownloads` that is QUEUED, DOWNLOADING, REMOVING or RESTARTING.

   `isIdle` alone is not enough: a paused manager, or one whose requirements are unmet, reports idle while it still holds resumable queued work.
2. **Delivery gate.** While the exclusion is held, both `MuonDownloadService` and `MuonCardDownloadService` pass every intent through `OfflineStore.admitCommand` before `super.onStartCommand`.
   - Refused: ADD, REMOVE, REMOVE_ALL, RESUME, SET_STOP_REASON, SET_REQUIREMENTS.
   - Each refused intent becomes `ACTION_INIT`, keeping its extras so a foreground start still shows its notification. The refusal is shown to the user. Nothing is queued to run later.
   - PAUSE is let through: it only stops work.
   - The card service's existing fallback refusal is applied first and is unchanged. The gate covers intents queued before the move and delivered after it was admitted.
3. **Sender refusals.** While the exclusion is held:
   - `add` queues nothing;
   - `removeSavedNow` returns the new `Busy` and revokes that song's hand-over (`moveOwnership.remove`), so the move will not add it (#234);
   - `removeAll` revokes every hand-over and sends nothing;
   - `completeMovedCopyNow` keeps both copies.

   Each refusal is said. Nothing is deferred.
4. **Release, then hand over.** The mover no longer posts a hand-over per song. After its last copy, its `finally` posts one main-thread step: release the exclusion, then hand over the copies through the existing `deliverMovedCopy` / `moveOwnership.publish` / receipts. Nothing can run between those two steps.
   - This runs on every exit from the batch, including failure, and `moveOwnership.finish` follows in a nested `finally`.
   - A send failure now invalidates that song's receipt and is reported, instead of being rethrown on main.
   - If submitting the batch to the mover throws, the exclusion is released immediately.

## Source evidence (pinned Media3 1.11.0, `/tmp/muon-DownloadService.java`, `/tmp/muon-DownloadManager.java`, exoplayer jar SHA-256 `2d583de9…ed6`)

- **`onStartCommand` dispatches straight to the manager:** `addDownload`, `removeDownload`, `removeAllDownloads`, `resumeDownloads`, `setStopReason`, `setRequirements`. Converting the action before `super.onStartCommand` is therefore the last point before Media3 sees a command.
- **Command methods count synchronously:** `removeDownload` and the other command methods increment `pendingMessages` on the calling (application) thread. `isIdle()` is `activeTaskCount == 0 && pendingMessages == 0`, and `onMessageProcessed` updates both on the application looper. Reading them in `move()` on the main thread is consistent.
- **Before initialization, a manager is not idle:** the constructor sets `pendingMessages = 1`.
- **New managers start paused:** the constructor sets `downloadsPaused = true`. `DownloadService.onCreate` calls `resumeDownloads()` before any `onStartCommand`, and only when it first creates the class's helper. Admission requires no resumable queued work, and RESUME and ADD are refused while the move holds the exclusion, so a first service creation during a move has nothing to start. A test covers that creation.
- **`getCurrentDownloads()`** returns the manager's in-memory non-terminal downloads, the ones loaded at initialization and changed since.
- **Downloader-level refusal is too late:** a refusing `Downloader.remove` still loses the row (`docs/audits/2026-10-07-move-admission.md`, #380), which is why this gate sits before Media3.

## What it covers, and what it does not

**Covers:**
- Commands reaching Media3 through Muon's two download services, and through Muon's own senders: `add`, single removal, Remove all, the move hand-over and move-completion source removal.
- Two moves at once.
- A queued intent delivered during a move.
- First service creation during a move.
- Pending or running manager work, and resumable queued or removing work, at move time.

**Does not cover:**
- **Work Media3 starts on its own after admission.** Requirements changes on an unpaused manager, or a task already in flight, are excluded only by the admission snapshot. Admission requires none to be pending, but nothing stops Media3 from changing that state internally.
- **Direct manager calls that bypass the services:** none exist in production code today.
- **Other writers:** played-copy writing (it uses only played keys, never a moved key) and SimpleCache's own internals.
- **Card loss or hot swap during a move:** #179 stays deferred, and the existing per-block availability checks remain.
- **Restart:** the exclusion is process-local, and a process killed mid-move keeps every byte, as before.
- **Not cleanup or receipts:** this is not a cleanup or ownership receipt for failed partial output. Partial bytes stay unindexed for a later move's preflight, unchanged.
- **Not a transaction:** with the manager or with the cache.

So #230 is **not** fixed by this slice.

**Behaviour change:** while a move is copying, saving, removing and Remove all are refused with a notice, and the user retries afterwards. A removal refused during a move also cancels that song's move.

## Changed paths

- `app/src/main/java/dev/avery/muon/DownloadMoveOwnership.kt`: `MoveExclusion`.
- `app/src/main/java/dev/avery/muon/OfflineStore.kt`:
  - admission (`quiet`) and the exclusion in `move`;
  - the `moveBatch` / `handOver` split;
  - `admitCommand`;
  - sender refusals and `SavedRemoval.Busy`.
- `app/src/main/java/dev/avery/muon/MuonDownloadService.kt`: both services pass intents through `admitCommand`.
- `app/src/test/java/dev/avery/muon/MoveCommandAdmissionTest.kt` (new): actual delivery to `MuonDownloadService.onStartCommand` into a real manager over native SQLite and a disposable cache, whose fixture downloader really deletes the cached bytes on removal. Tests:
  - a removal delivered during a move (with the service first created during the move) keeps both the row and the bytes; the same intent after the move removes both (positive control);
  - a delivered pending command refuses a move, and the move is admitted once the manager settles;
  - a second move is refused while the first is in flight;
  - a paused manager reporting idle with queued work still refuses a move.
- `app/src/test/java/dev/avery/muon/DownloadMoveCharacterizationTest.kt`:
  - its managers are now settled before each test;
  - the two #234 removal tests now expect refusal during the move (still no Add), then the original Sent/one-removal behaviour after it;
  - a failed copy is shown to release the exclusion;
  - a second move during the first adds nothing more.
  - These still capture intents without delivering them; they are not end-to-end service tests.

## Remaining checks

- CI compile, and these tests on the first Actions run. Nothing here was compiled or run.
- Device QA: move with saves or removals attempted during it (pending user testing).
- Next #230 boundaries (unchanged):
  - a writer and sink completion receipt;
  - target generation and ownership;
  - restart preservation;
  - any cleanup of failed output, which stays refused.

## Coordinator review follow-ups

Both admission paths now assert the main looper, where manager state/dispatch are serialized. Move admission also refuses while the process-local receipt registry has an unacknowledged hand-over, covering a queued target Add not yet reflected in manager pending counts. Actual service pending-removal and idle-manager/pending-hand-over controls were added. The latter captures, rather than delivers, the Add and explicitly tests that distinction. Explicit saved removal invalidates the pending receipt so the busy marker is not permanent. This does not authenticate sink close or complete source-removal acknowledgment; those remain separate #230 work. No full #230 closure.
