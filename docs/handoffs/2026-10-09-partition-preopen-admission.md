# October9: read-only pre-open native partition validation (#253)

Root implementation/source self-check: GPT-6 / Codex desktop, exact variant/effort not exposed. `codex/partition-preopen-admission-oct9` includes #436 at e676b162b7d371a33278fe010233c73e7e3d8942 and #435/#434/#433/earlier OPEN acceptance. Main included e164a340b2a0367c8277f7d927ced8b6a7401bfa. No phone/experiment/session commands. Leave app PR OPEN for UAT. Final exact head/check/artifact receipts belong on the PR/coordinator checkpoint.

## Why this cannot be a post-open guard

Pinned published SimpleCache initializes/scans its folder; CachedContentIndex.DatabaseStorage.load and CacheFileMetadataIndex.initialize recreate/drop a version-mismatched table. With a missing content mapping, startup may delete unrecognized span files. Opening an unknown/full legacy index first also defeats the resident-memory bound. A UID marker alone is insufficient.

`PartitionNativeAdmission.inspect` is an UNWIRED strictly read-only check of a CLOSED known private bytes directory and an ALREADY-OPEN owned SQLiteDatabase. It uses supported VersionTable reads plus explicitly source-verified native schema/filename observations. It performs no SQL/index/schema writes, repairs, native constructor, database creation, file mutation or fallback. Never use it to adopt a legacy/untrusted cache. Unknown formats refuse. Library upgrades must source-review this format gate; do not relax it blindly to make an upgrade pass.

Checks: exact canonical directory/no active native lock; expected UID marker; both exact pinned table versions/schemas and only known schema objects; at most one exact owner key and EMPTY native metadata (bounded CASE/scalar BLOB-byte-length projections (TEXT length stops at NUL)); bounded positive native file rows and byte-total overflow; valid canonical id/position/timestamp v3 span names matching the one content ID; no overlapping extents; at most ten known native subfolders/256 files, no aliases/symlinks/unindexed/duplicate entries; actual file lengths/count matching the file index. DirectoryStream iteration is bounded and never loads an unbounded file array. Cancellation/availability checkpoints are supplied by the future owner.

Source evidence: Google's published datasource sources JAR SHA256 a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a; SimpleCache, CachedContentIndex and CacheFileMetadataIndex initialization/table/schema/load/file naming contracts inspected. The code intentionally reads the pinned format; it does not manage native schemas. Gradle remains dependency version authority.

## Twelve meaningful native controls

Actual native close/index/span admission and reopen; empty native index; missing/wrong UID with original files retained; mismatched native version/missing content table without recreation; foreign key/multiple resources/5MiB native metadata refused without loading it; >3MiB NUL-suffixed key/filename refused before CursorWindow loading; tighter span budget; stale file-length/missing index rows; foreign files/symlinks/unindexed span preservation; unknown trigger/new filename format refusal; overlapping indexed files preserving both originals; active-native/cancelled preflight refusal.

Actions is first Android compile/test/lint; expected combined1005tests per variant, actual reports must establish it. No phone/heap/startup-speed/power-loss claim.

## Required production owner integration still remains

This is an observation, not ownership/writer/availability exclusion. The future factory must validate current volume, private ready record and separate metadata UID/key, hold a single owner across preflight and native open, and count EVERY migration/read/write native instance against fixed residency. After open it must still admit the native single-resource cache and keep actual reader/writer/listener pins through clean closure. The SQLite provider itself must already be the owned existing DB: creating a missing DB is not validation. No production factory/routing is enabled here.

Downloader/played/removal routing, request/index/cover handover, true source barriers, space/cancel/deadline worker, restart recovery and opt-in controls remain REQUIRED engineering. Original/uncertain/overbudget legacy copies stay readable, and supported legacy import still has a full-index transition peak. Ready is not deletion authority. Later renewed POCO rooted-ADB testing belongs after a production-routed opt-in candidate exists; #401 startup cause and inherited UAT remain open.

Claude Opus5.5/ClaudeCode/High finished normally/idle at78%five-hour/92%weekly USED. Root owns this solo follow-up; refresh quota/heads/ownership at resumption, never depend on local session logs.
