# #179 public-span snapshot feasibility — 2026-10-02

Inspected main `b1df6cc3d5286f0a685379d51d98e08347899713`. GPT-6 / Codex desktop, effort not reported; author investigation/tests/self-check, no independent review. Test-only S3 step, not a production recovery/migration fix. No device, mount event, user cache or music accessed.

The existing five `RemovableCacheCharacterizationTest` cases establish the modeled mapping-loss sequence. This adds one distinct feasibility control using actual pinned Media3 SimpleCache/native SQLite and disposable files: capture public `CacheSpan.key/position/length/file` and known content length while healthy; model disappearance/release index loss; restore files; import copies into a NEW cache directory using public write APIs; release/reopen only that new directory and assert exact bytes. The original orphan file is retained and the old directory is deliberately never reopened. No private table edits, reflection, filename-ID parsing, dependency fork or cache-format change.

Pinned datasource source reviewed: SimpleCache `getCachedSpans`, `startReadWrite`, `startFile`, `commitFile`, `applyContentMetadataMutations`, and CacheSpan immutable fields. The source checksum/reference is in [volume evidence](2026-10-02-volume-catalog-evidence.md). The fixture's mapped release-while-absent behavior is already reproduced in [the original report](2026-09-29-cache-characterization.md); directory rename is not Android mount/eject fidelity.

## What this would establish if CI passes

A healthy public snapshot plus still-intact named files can recreate this one exact key/byte range in a distinct cache using supported public writes, without reopening the destructive old cache. This refines feasibility; it does NOT prove automatic import is safe or complete. CI is the first compile/execution and results must be recorded at the final head, not inferred from these expectations.

## Unresolved before production recovery

- Snapshot lives only in test memory; no durable catalog, complete-key capture, atomic publication/crash recovery or mutation veto is implemented.
- A path may change on span touch/rename, a file can disappear or change after capture, and abrupt loss can precede enrollment. Missing snapshot/file means unknown, not empty or permission to delete.
- The fixture imports one known contiguous span and known metadata field. Multiple/overlapping/missing spans, redirects/custom metadata, corrupted or hostile records, downloads/index attribution and complete audio validity remain untested.
- UUID/cache UID/generation/containment and ambiguous old shared `card` rows still require S2 ownership rules. No assignment of all legacy rows to the attached volume.
- Copying into a new namespace needs additional storage, a quiescent original owner, generation-bound services/callbacks, interrupted-import handling and validated source files. None is provided here.
- Existing absent-card cache operations/release remain unsafe; this control deliberately models that loss only on throwaway files. Never apply the fixture's teardown sequence to user media.
- #213 retained numeric identity and #230 partial target ownership are separate. A byte-preserving import does not authenticate a track against a rebuilt Tauon library.

## Stop / next boundary

Keep #179 open. Review a durable, generation-scoped snapshot/export design and its supported metadata/recovery limits before production catalog/migration work; no private Media3 index manipulation or automatic deletion. Reuse the existing fixture for additional controlled failure cases rather than a real SD card with sole copies. No phone QA is required for this test-only branch; any recovery app change needs separate review/QA.
