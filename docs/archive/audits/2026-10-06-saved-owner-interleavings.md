# Saved-owner census and mutation boundary — 2026-10-06

Base main `c638cd539ee43d85112c6c0fadfafbb0d002abc4`. This extends the real disposable controls in [saved ownership](2026-10-06-saved-ownership-controls.md) for #213. GPT-6 / Codex desktop (exact variant/effort not exposed), implementation and self-review; no independent review. No app API, schema, migration, dependency change or user-data mutation is introduced. CI is the first Kotlin compile/run; exact outcomes belong on the PR.

## Three deterministic interleavings

`SavedOwnershipControlTest` uses actual `DefaultDownloadIndex` writes, `SimpleCache` spans and `DefaultDownloaderFactory` removal, as the earlier controls do. There are no threads, sleeps, network calls or user-store access: the writes deliberately occur between planning and execution. This characterizes a possible ordering rather than proving a concurrent production race on a phone.

1. **A sole owner becomes aliased after the census.** A fresh census would refuse, but the captured plan still passes its request-ID/key check. Actual downloader removal deletes the shared bytes; the later completed row survives and claims bytes that no longer exist.
2. **The same ID/key can describe a replacement record.** A real index replacement changes the row's owner metadata while preserving both fields. The old plan deletes the replacement row and its cache bytes. Request/key equality is not a record revision or identity proof.
3. **A plan can be applied to another shelf.** Matching ID/key/tag fields on a different index/cache do not authorize transfer. The prototype plan has no cache-owner/generation binding, so execution deletes the other shelf's bytes and leaves the original shelf untouched.

These are shortcomings of a deliberately test-local prototype. They are not a claim that production uses that prototype or that #213 is fixed. Existing opaque keys, equal tags, a path, a cache UID and a mounted flag do not authenticate the audio or returning volume.

## Required contract before an application removal caller

- Admission must bind the request to a specific owned cache/index and live generation, an exact record revision, and a complete declared co-owner registry. Unknown, unreadable, replaced or unavailable owners refuse without deleting or adopting uncertain bytes.
- Planning and mutation cannot be separated by unaccounted owner changes. Every relevant add/update/remove, prospective key reservation, alias registration and late asynchronous callback must participate in the same mutation/admission contract. A UI-thread check alone does not serialize Media3's internal handler or downloader workers.
- The removal decision must be invalidated by any relevant intervening change, including another claimed alias, replacement record or shelf/volume generation. Rechecking immediately before I/O still leaves a gap unless writer admission is actually coordinated.
- Cache/file deletion and persistent-index mutation are not one SQLite transaction. A database lock, row compare or counter does not automatically make the library's file effects atomic or crash-recoverable. Establish supported writer/reader drain, completion acknowledgment, restart persistence and interrupted-operation handling before claiming preservation; retain #179/#230 dependencies.
- Cover deletion has its own owner set: legacy shared covers remain uncertain. A proven exclusive audio row does not assign that cover to the row.

## Next bounded source question

Map each production owner mutation and its execution context on the **current #302-plus-main application source**, including the manager's add/merge/removal completion and played-copy/move work. Determine whether one supported admission mechanism can account for them and what operations must conservatively remain unavailable. Record unsupported boundaries before choosing a durable schema or adding production scaffolding. Do not directly promote `Plan.Exact`, add a blanket global lock or edit Media3's internal SQLite tables.

A useful production #213 boundary still includes separately reachable Unverified saved copies, validated cache-only routing and queue provenance, live-stream selection when identity is uncertain, exact action/cover ownership and collision-free prospective saves. The user's selected policy is unchanged. This report neither merges #302 nor clears its acceptance gates.
