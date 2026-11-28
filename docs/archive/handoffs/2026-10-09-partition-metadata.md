# October 9: preserve partition metadata outside native byte indexes (#253)

Root implementation: GPT-6 / Codex desktop, exact variant and effort not exposed. The source branch `codex/partition-metadata-oct9` is a complete successor to #434 at e128cc06da1f5cc3da40a5e7ab8a74b84313c610, including #433 and the earlier acceptance stack. Main destination included: e164a340b2a0367c8277f7d927ced8b6a7401bfa. Experiment/session untouched, no phone commands. Leave the application PR OPEN for UAT. Actual final head/run/artifact receipts belong on its PR and the coordinator checkpoint after CI.

## Confirmed source issue and disabled solution

Pinned Media3 datasource sources from Google's Maven (SHA256 a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a) show that `CachedContentIndex.maybeRemove` discards content with no spans/locks, including metadata-only records. `SimpleCache.initialize` scans its own byte directory and can delete unknown files: the separate SQLite store MUST live outside that scanned directory. No dependency version changed; Gradle remains the authority.

`PartitionContentMetadata` owns an independent exact UID/key-bound SQLite schema, never Media3's schema. One partition holds at most 256 fields and 4MiB combined exact UTF-16 names/binary values. Payload uses 64KiB rows, avoiding a single multi-megabyte CursorWindow row. Reads return fresh bounded projections; writes snapshot bounded caller input and atomically replace only this derived payload. Unknown/corrupt/missing payload and owner mismatch refuse, never reset or invent empty metadata. Fresh creation is explicit and refuses an existing directory; ordinary reopen cannot recreate a missing database. Even empty metadata has an encoded payload. FULL SQLite synchronization and transactions are used, without claiming physical power-loss proof.

The optional store is owned by `PartitionResourceCache`. Native metadata must be empty at admission; legacy native metadata is never silently adopted. Reads and logical content-length admission use the separate store, unknown fields survive clean native close/reopen and last-span removal, and explicit resource removal clears the own metadata. A missing/corrupt projection stops new native write admission. Native byte directories and separate metadata directories are sibling layout components in tests. This is still UNWIRED: no production path, opt-in control or migration is enabled.

## Meaningful regressions (first compile/test/lint is Actions)

Fifteen native SQLite/actual SimpleCache regressions cover: >3MiB binary field and exact NUL/unpaired-surrogate names; owner mismatch/fresh-directory refusal; interrupted chunk transaction rollback; metadata byte/field budgets; missing database/payload refusal; foreign schema/changed owner preservation; noncontiguous/oversized chunks; duplicate fields/trailing/odd encoding rejection; metadata-only journal publication only after actual clean native close/reopen; real CacheDataSource/CacheWriter byte/content-length lifecycle with empty native metadata; last-span metadata preservation and explicit removal; logical length rejecting a staged overrun; damaged metadata refusing native write admission; inconsistent legacy length refusing publication while original bytes/metadata remain; and refusal to adopt legacy metadata or another key's store.

There is no local Android build or physical-device/heap/speed claim. If Actions fails, fix and record the latest head rather than calling this preparation verified. Expected count is 982 tests per variant; inspect the actual downloaded reports.

## Remaining engineering and phone boundary

- Production partition owner/factory, exact volume/native UID validation and routing over a fixed native-instance pool.
- Cached-file reader leases and write/listener lifetime integration; the Cache API does not report cached-read closure. Never release an in-use native instance.
- Real source availability/writer/reader/removal/eviction barrier and bounded cancel/deadline worker for migration; journal Ready alone never authorizes source deletion. Request/cover handover is still separate.
- Optional source cleanup only with another byte-verified replacement, complete destination/locator durability and explicit source-removal rules; partial/uncertain/overbudget legacy copies stay readable, without claiming bounded legacy startup.
- Opt-in space/cancel/progress/restart UI and full production integration/preservation tests. Inconsistent legacy declared lengths remain refused and retained, not silently altered.
- #401 saved-card startup measurements remain unresolved. User deferred provenance/dependency verification and broader SD recovery separately; neither is falsely marked proved here.

Ask for renewed POCO rooted-ADB access when a production-routed opt-in build is available, for private-index/card identity, restart/cancel and original-byte preservation checks on disposable copied data. No reason to disturb the user's card to validate a disabled helper. Ordinary playback/download/queue/library/accessibility UAT is still pending for inherited app fixes.

## Agent/quota state

Claude Opus 5.5 / Claude Code / High implemented #434, root reviewed and fixed it. The allocated audit session finished normally/idle at 78% five-hour and 92% weekly USED; no hard limit during this assignment. No further Claude task is running. Root owns this follow-up solo; local session paths are optional conveniences, not takeover requirements. Check live head/checks/ownership and account quota before continuing.
