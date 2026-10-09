# #253 production startup ownership

Successor of OPEN #465 at `d0f4d8817d8c989bbb68963298a9208c7360f4f8`, with main's checkpoint `6cf983183f83e6a2d498aa5744c7896e3802bdd8` included. Worktree/branch: `muon-storage-startup-owner-oct9` / `codex/storage-startup-owner-oct9`. Claude unavailable; implementation/self-check GPT-6, Codex desktop, exact variant/effort not reported. No independent review or device testing this cycle.

## Actual production change

`OfflineStore.get/create/shelf` now construct under one `StorageStartup<Store>` before opening native caches. Returned database, played-order database owner, phone/card caches, manager workers, managers, artwork worker and mark ledger are strongly retained in at most sixteen slots. A successful Store is returned unchanged; a failed root startup is sticky for the process. Repeated UI/service calls cannot keep creating new cache/worker sets after a partial constructor failure. Reentrant startup and registration after the opening ends refuse. The owner does not close or discard uncertain participants merely because creation threw. A failed optional card startup remains unavailable rather than being retried on the phone cache.

Native cache initialization remains asynchronous as before: this change adds no new blocking initialization wait on the application's main thread. Existing service classes keep their one manager binding. Fixtures that inject an existing Store retain their current seam.

## Verification and limits

New tests cover stable identity, partial failure, bounded registration before construction, reentrancy and stale opening handles. A native SQLite/real SimpleCache control preserves the exact original bytes, cache lock and workers after a later injected failure, and proves retry does not construct another cache. Android compile/unit/lint and exact-head artifacts are pending until recorded on the PR.

This is production lifetime groundwork, not partition cutover or a complete #253 memory bound. Default remains legacy; its full native index remains allocated. Required engineering still includes actual shelf backend selection before SimpleCache, shared app-wide barriers, service command/task routing, played/cover/move/removal integration, opt-in migration and restart recovery. The owner provides no shutdown/reset permission: future teardown must establish actual manager/worker/file quiescence first. Interrupted card recovery remains the separately deferred #179 work. Acceptance stack stays OPEN pending user testing; no Stable publication or experimental edits.
