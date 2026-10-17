# Own-cover Move preservation preparation — 2026-10-12

Full legacy-original Delete/Move remains required before activation. Partition mode remains disabled. This main-track cumulative successor to #520 adds internal entry-owned cover copy, not Move source retirement or user-visible controls.

## Ownership and verification

GPT-6 / Codex desktop implementation and source self-check; exact variant/effort not reported. No independent review, Claude or phone this cycle. One isolated writer on codex/partition-move-cover-copy-oct12, parent bcbb266d406affd44659597ba1cadba32320b18d, includes main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. Refresh live head/PR/checks before takeover. Local diff check; Actions is first Android compile and results are pending at source checkpoint.

## Prepared behavior

- Confirm original own cover and optional existing fresh target by bounded streaming SHA256, exact lengths/EOF and actual file identity/mtime. No per-track legacy covers are adopted or removed. Differing target bytes refuse overwrite; confirmed no-own-cover gets an explicit absent receipt.
- Same actual exclusive storage gate, bounded distinct request IDs, canonical/non-symlink regular paths and known file keys. Caller cancels queued artwork first. Fresh copy writes only its unique temporary file, flushes/syncs and closes actual streams, rechecks bytes/source/target and publishes with atomic rename. Directory force/actual close and final byte confirmation precede receipt.
- Known failed write with confirmed closes removes only its own exact temporary inode. Unknown close/rename retains actual streams/channel/path disposition and invalidates admission; no retry/cleanup or source deletion. Directory durability unavailable refuses while retaining original/target bytes; Android support awaits composed candidate QA.
- SavedCoverIo now owns an optional actual durability channel and disposition; existing fetch semantics remain.
- Tests cover source/older-cover preservation, queued-target cancellation, idempotent exact target, mismatch/no-source refusal, foreign gate/symlink/oversized refusal, exact failed reader/writer close retention/no retry and known write-failure cleanup. These are JVM file/logic checks, not Android visual/storage-removal verification.

## Next engineering

Persist the cover receipt with the destination-bound Move journal and reverify actual destination audio/completion/root and cover before target-aware source retirement/recovery. Existing v3 Move intent has no cover proof and generic cleanup continues to refuse it. Full nonterminal/unverified-original disposition, obsolete old native metadata retention, production Fresh/Resume opt-in/recovery and final composed lifecycle coverage remain. Keep app PRs open for inherited acceptance; do not force selector/preferences or request phone tests of preparation alone. No Stable release/tag.
