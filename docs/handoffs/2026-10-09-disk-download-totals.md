# #253 download byte accounting on disk — 2026-10-09

Continuation of #424 on the unmerged main-audit acceptance stack. GPT-6 / Codex desktop implementation/self-check, exact variant/effort not exposed; Claude exhausted/idle. No phone or experimental access.

`OfflineStore.create` now uses private derived SQLite per-ID finished-byte accounting instead of an indefinitely retained HashMap of all completed IDs/sizes. Each update binds one exact UTF-16 name and size, replacing/deleting transactionally; the aggregate remains one wrapping Long exactly like the prior JVM sum (native SQLite SUM overflows differently). The private table resets from actual status/bootstrap events each process; it is never saved-index/audio authority. No native cache schema or original row/byte changes.

Database/transaction failures latch the tally as unknown without throwing through the manager's main callback. Settings shows Size unavailable and omits the unknown download segment from its storage bar instead of presenting a stale size as exact. Saved originals and operations continue; a new process rebuilds accounting. The requested 256 KiB SQLite page cache is not an exact native/process heap bound. Status callbacks now include private SQLite IO; phone latency remains unmeasured.

Native tests compare the exact prior map sum through 1,200 mixed events, removals/replacements/unknown IDs, long/NUL/unpaired names and Long overflow; verify runtime facade/reset and fail-closed unknown accounting with originals intact. Existing pure accounting tests remain. CI first compile/full test/lint; exact final receipts on PR/coordinator checkpoint. UAT pending for Settings finished counts/byte labels, download completion/removal, moves between shelves and restart.

Still required #253: compact all-status bootstrap publication, global Compose download marks, native SimpleCache content/metadata/span retention, naming/all-key and destructive extent snapshots. This removes the byte-accounting map only; it does not complete the memory gate. #401 cause remains unknown pending authorized device comparison. App PRs stay open; no Stable/tag/app merge.
