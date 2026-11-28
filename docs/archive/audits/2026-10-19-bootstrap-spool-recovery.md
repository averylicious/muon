# Recover abandoned derived bootstrap scratch after process death

Cumulative #619 56ff40f5c03a7bcc78f6f03c2cc6907a1fcf2026; #253 derived-file lifecycle follow-up. GPT-6 / Codex desktop, exact variant/effort not reported; implementation/source self-check, no Claude or independent review.

## Observed

An authorized ordinary .1274 synthetic50,000-distinct-artist/album load reached the test's conservative220MiB Java-heap-PSS guard. The helper deliberately force-stopped this metadata-only app; spontaneous app crash/OOM is NOT established. Sparse samples maxJavaHeapPss237416KiB, total338088KiB, RSS455912KiB, only two samples. These are overlapping resident categories, not retained-heap/peak or timing measurements. Device heap-growth limit384MiB; do not lower data budgets merely by treating the guard as that platform limit.

The stopped snapshot had two new16KiB download-bootstrap-UUID-records.db files. Each has only the exact derived statuses schema (ordinal/id/state/bytes): zero phone rows and one card row exactly matching the unchanged saved-manager projection. Existing ownership journals, audio, covers and saved records did not change. Source DownloadBootstrapSnapshot deletes scratch on orderly close, but process death bypasses it and there was no startup stale-scratch cleanup. Repeated interrupted startups could accumulate derived copies. Further controlled retained-memory analysis is separate; no full wide-library pass claimed.

## Fix / boundaries

The next bootstrap worker performs one bounded scratch scan per private root/process before opening its spool. Inspect at most128 entries. Match only the reserved UUIDv4 records/changed SQLite scratch names and supported satellites; never recurse. Require an exact canonical private parent and a regular no-follow entry with one link. Unsupported link attributes/failing cleanup leave that file alone and never block the real index scan. The actual manifest uses one app process; normal and diagnostic packages have separate private roots. A future multi-process design must revise this process-local ownership scheme.

Every live snapshot reserves its prefix in a shared registry before creating files; per-entry active checks and unlink share a short lock, including a new snapshot registered during iteration. Close unregisters after ordinary database close/delete attempts. The scan's I/O is on the existing bootstrap worker, not the constructor/main-thread path. Persistent saved/index/migration files have different names and cannot match. Unknown names, directories, symlinks and hardlinks are preserved. This is optional scratch reclamation, never source-audio/native-row deletion authority or a migration recovery receipt. The128-entry bound can leave additional leftovers for later process starts.

Two focused existing-fixture tests exercise stale namespace/satellite removal, registered live-prefix protection, unrelated/original/symlink/hardlink preservation and actual empty-index publication, then bounded inspection with140 leftovers plus an unchanged real saved request. Existing full status paging/event/cursor-close-failure cases stay. CI is first Android compile/tests. Selected device acceptance must establish that only the known derived leftovers are reclaimed and all persistent records/audio/journals remain. App stack OPEN; no Stable/activation/experimental merge or release.
