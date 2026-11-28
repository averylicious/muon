# Played-copy and maintenance lifetime — 2026-10-11

## Scope and ownership

Solo GPT-6 / Codex desktop implementation and self-check; exact variant/effort not reported. No Claude or phone access this cycle. Branch `codex/played-copy-lifetime-oct11`, persistent worktree `~/.codex/worktrees/muon-played-copy-lifetime-oct11`, targeting main. Includes main `2cfb4a2c0570bb18bf206123eafa0f7c517c53c5` and cumulative OPEN #483 `5bb54a26d4d08b002e3d58500b530ba976511279` (which includes #482 and prior acceptance stack). Do not reuse or modify the experiment checkout.

## Change

Actual `OfflineStore.copyPlayed`, startup trim, resize and clear closures now pass their store's shared storage barrier into `PlayedCopyWork`. Submission alone does not acquire a permit: the real closure owns it through its actual copy/source close and synchronous cache cleanup. Maintenance runs under its own permit after the previous copy completes; exclusive migration admission refuses these optional operations before mutation. Cancellation requests do not release a running copy's permit.

An actual `PlayedCopyCloseUncertain` retains its exact writer and shared permit, invalidates storage admission and discards queued copy/maintenance closures. The existing production catch also retains the writer on the shelf and quarantines the native played evictor. An arbitrary executor that accepts a runnable and then throws retains the bounded submitted operations and invalidates their gates, so a late drain cannot mutate or retry. Only explicitly known rejection-before-submission drops unstarted jobs without poisoning storage; production uses the existing single-thread executor and declares that contract.

Eight new controls cover actual native CacheDataSource close/oversize cleanup and unknown-close preservation, held permits, exclusive refusal, known failure recovery, queued maintenance quarantine, ambiguous submission and known rejection. Controlled JVM/native SQLite tests are not device/performance evidence. CI is first compile; exact-head build/report/APK receipts are recorded on the PR after completion.

## Limits and next work

Partition mode remains disabled. This closes the played copy worker's lifetime gap, not every cache participant. Actual asynchronous DownloadManager tasks, native cache teardown, startup ownership scans, destructive policies and partition startup/opt-in/restart recovery still need integration. #482 playback projection has a finite 1MiB input ceiling; larger valid metadata remains intact but unsupported. App PRs stay OPEN under inherited acceptance; no Stable release/tag or source deletion.

Continue authorized useful engineering after this verified boundary while quota permits. Keep durable exact-head/check/artifact evidence on the PR and main checkpoint. POCO becomes useful for end-to-end opt-in/restart acceptance once production controls exist; it is not needed for these controlled worker tests.
