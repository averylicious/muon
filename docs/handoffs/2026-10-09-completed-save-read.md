# October 9: completed new-save reads (#253)

Cumulative successor of OPEN #449 at `08fe3c0197474d019ef483286045d3e11eb028de`, including main `602933e5ad1d8ecc65048e8a776488df6acec013` and approved continuous-engineering policy. Parent actual run861 /37922459522 passed 1,076 tests per variant, zero failures/errors/skips, lint0errors/64warnings, actual signed normal APK checks; PR449 records the artifact/checksum and corrected initial compile failure. This checkpoint supports continued engineering, not a turn stop.

## Implemented

Prepared `PartitionCompletedAudio` composes a clean owned new-save journal allocation/native UID with an exact bounded COMPLETED manager-row snapshot. Closed alone, a completed row without closed ownership, interrupted writers, stale allocations, alias/ranged/oversized requests and mismatched content length cannot publish/read. The snapshot retains scalar identity and a fixed request digest, not request metadata/audio payloads. A fresh owner can read a clean completed copy without recovering old command tokens. This does not authenticate a remote song.

`openClosedSaveRead` uses the read-only native lifecycle: it preserves Closed rather than recording a new writer Opening/Open. The actual native layout/UID/sidecar admission remains required. Inspection/first open require exact full coverage and content length. Source readers have no upstream or write sink, capture/recheck exact row+route around I/O, revalidate the acquired native cache even on pool reuse, hold pins through EOF until actual close and fail sticky on ownership/availability loss. Paged journal inventory opens no caches; shutdown stops admission without force-closing active readers. Migration Ready and old verified routes retain their separate authority.

Nine tests exercise actual native caches/journals/SQLite/index/read sources, including real DownloadManager save→seal→COMPLETED→new-owner read, bounded paged enumeration, mismatched length/alias, active writer, index change during read, stale allocation/cached old UID, sticky volume loss and drain-after-stop. CI is first compile/test/lint; final-head actual receipts belong on PR/issues. Test fixtures use disposable bytes; no production heap/performance claim.

## Remaining engineering

Not selected by app services yet. The mandatory completion lookup still needs a bounded production index projection BEFORE arbitrary persisted request materialization, under the actual app-wide source/writer/removal/move/eviction/availability barrier. Compose legacy, migration Ready and completed-new-save routing without wrong fallback. Then bind service commands/manager callbacks/cover acknowledgements, define explicit interrupted-allocation recovery, implement bounded space/deadline/cancel/progress migration and opt-in controls, and investigate the legacy full-index transition peak. This component does not clear #253 or release Stable. #230 defined legacy engineering is separate from its pending UAT; full #179/provenance remain user-deferred.

## Device diagnosis / ownership

Root GPT-6 / Codex desktop sole writer; exact variant/effort not exposed; author source self-check is not independent review. Allocated audit Claude idle, dated quota not assumed reset. Experiment untouched. Current user explicitly renewed rooted USB POCO verification. Separate diagnostic run862 /37922519306 at verified parent c93e6451f755629c1cc99ef7c227596970d8dc4c passed1,068tests per variant and actual artifact signature/pkg/debuggable checks; installed as a data-preserving update to Muon diag only. Canary/Stable untouched. Existing disposable fixture has two complete card downloads/two audio spans. Before snapshots of DB family and audio payload hashes retained locally only, never public artifacts; #401 measurements/preservation outcome recorded separately once complete. Original media volume19 recorded and temporarily silenced for diagnosis, must restore after stopping diagnostic playback.

Continue actionable authorized engineering after verified receipts while actual quota allows. Keep cumulative app PRs OPEN pending inherited acceptance; no Stable/tag/auto-merge/bypass or shared experimental worktree mutation.
