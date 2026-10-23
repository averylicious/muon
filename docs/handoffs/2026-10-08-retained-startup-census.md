# #253 retained-startup census — 2026-10-08

One bounded #253 slice on `codex/retained-startup-census-oct8`, from `ab58f47553c9d162c36153fa2b08939114b32eda` (#389 compact censuses, including #387 and main `5a123109`). Source and tests only, reviewed by the coordinator before committing; latest-head CI is recorded on the PR. No local Gradle/Android build: GitHub Actions will be the first compile and test run. No device, network, signing or settings change.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort explicitly selected; implementation. GPT-6 (Codex desktop, exact variant/effort not exposed) reviewed the focused startup implementation and tests; this is not an independent review of the inherited acceptance stack.

## Finding

`RetainedDownloadIndex.inspect` collected every unfinished retained row as a full `Download` (request, address, stored tags) in an `ArrayList` before writing its stopped state. That list held every unfinished row's raw record at once.

## Change (`RetainedDownloadIndex.kt` only)

- **Pending-ID snapshot.** The same state-filtered scan (QUEUED, DOWNLOADING, REMOVING, RESTARTING) now keeps only each row's request ID. The cursor is closed by `use` before any read or write.
- **One row at a time.** For each ID, the guard calls `actual.getDownload(id)` just before the preserving `putDownload`. It writes that row's own current request, timestamps, content length and progress with STOPPED/`RETAINED_STOP_REASON`. That re-read record is the only full record held.
- **Unexpected change refuses startup.** If the re-read row is missing or no longer in an unfinished state, the guard writes nothing for it and throws `IOException("A retained download changed during startup")`. "No longer unfinished" means completed, stopped, failed or anything else. A missing row is never recreated and a newer completed or other record is never overwritten.
  - The failure is latched exactly like a failed scan.
  - Media3's initialization catches it and loads no tasks.
  - Later `getDownloads` / `setDownloadingStatesToQueued` calls on this instance rethrow without rescanning, so current-process commands are never stopped.
  - A new process retries.
- **Why refuse rather than skip.** In supported startup no other writer should run during `inspect`: Media3's worker is inside it, and the guard is `@Synchronized`. A changed row therefore means an unsupported concurrent writer, and continuing would authorize the manager's initial task snapshot on a premise that has just been contradicted.
- A failed single-row read (`IOException`) is latched the same way.
- **Unchanged:**
  - the main-thread refusal (no SQLite writes on main);
  - the IOException latch;
  - completed rows, which are never scanned or written;
  - cached bytes, which are never touched;
  - normal delegation after a successful inspection;
  - no schema, custom SQL or dependency.
- **Partial stopping, as before:** rows already written before a failure stay STOPPED with every record field preserved. The rest keep their original state, and this process loads no tasks.

Verified against the pinned Media3 published `media3-exoplayer` source artifact from Google Maven on 2026-10-08 (SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`): `DownloadManager.InternalHandler.initialize` invokes `setDownloadingStatesToQueued` before reading unfinished/stopped rows, catches `IOException`, clears its initial downloads and closes the cursor before `syncTasks`. Initialization and subsequent commands use that handler's serialized message queue. Tests exercise the actual manager, rather than only this decorator. This does not constrain unsupported writers outside that queue.

## Tests (`RetainedDownloadStartupTest`, actual `DownloadManager`, native SQLite, disposable SimpleCache)

All existing startup tests are unchanged. An `Observed` delegating index records the guard's state-filtered scan, each single-row read and write, and anything done while that scan was open. Only the scan that runs before the manager's own `setDownloadingStatesToQueued` is counted.

1. **Five unfinished rows** (QUEUED ×2, DOWNLOADING, REMOVING, RESTARTING):
   - One row has a valid encoded song record; four have different 256 KiB raw tags. There is also one completed row with large tags.
   - Before the manager's startup step there is exactly one scan, then `read:id`/`put:id` pairs covering every unfinished ID once. Nothing is read or written while the scan is open, and the scan cursor is closed.
   - Request (including tags), timestamps, content length and progress are unchanged, with STOPPED/213. The partial audio is kept.
   - The completed row's request and update time are untouched.
   - `resumeDownloads` creates no downloader, and every loaded manager download is STOPPED.
   - The valid record still displays and is marked stopped after restart.
2. **Per-ID read failure after a successful scan**, on the second of two REMOVING rows; one of them shares a key with a completed row:
   - No downloader is created and the manager loaded no downloads.
   - The failed row stays REMOVING with its request; the other is either untouched or STOPPED/213, depending on scan order.
   - The shared and own bytes are kept, and the scan cursor is closed.
   - A newer `addDownload` (stop reason 7) works.
   - The guard rethrows the same latched failure without a second scan, and the newer row keeps stop reason 7.
3. **A row removed the moment it is read again:** it is not recreated. The startup refuses: no task, latched, one closed scan, bytes kept, and the other row is untouched or stopped.
4. **A row turned COMPLETED (new update time) the moment it is read again:** the newer record is not overwritten. The rest is as in case 3.

The existing tests still cover the fresh current-process add and removal (`finishedRowsAreUntouchedAndFreshCurrentProcessCommandsStillWork`), the main-thread refusal, the unreadable census and the aliased removal. Not yet run: CI is the first execution.

## Limitations (not claimed)

- **What this does and doesn't fix:** it eliminates holding every unfinished row's raw record at once. It does not bound:
  - per-row native cursor windows or per-row `Download` allocation. Media3's cursor still decodes each row in full to give its ID, since there is no ID-only public projection;
  - the number of pending IDs, which grows with unfinished rows;
  - the `Download` records the Media3 manager itself keeps for every loaded STOPPED task;
  - process memory.

  No heap, jank or timing measurement was made.
- **No new guarantees:** no transaction, hashing or new concurrency guarantee. The re-read narrows, but does not close, the window against an unsupported writer between the read and the write.
- **More conservative:** a changed row now blocks this process's initial task load, where before the old snapshot would have been written. That trades availability (until the next restart) for not overwriting newer records.
- **Not touched in this slice:** the other census/move code, `STATE.md` and the coordinator documents. This does not complete #253. On 2026-10-08 the user explicitly required broader saved-library paging and Media3/cache memory redesign before Stable; it is engineering work, not deferred to post-release or only UAT.
