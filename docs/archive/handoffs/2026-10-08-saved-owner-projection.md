# #253 saved projection ownership boundary — 2026-10-08

Based on verified #399 `2764f987329f7775e5bfef07c791986091f39334`, including main `2ee2da7091e14f9d98cbb8cfd2bae77ad3c22b59`. Dedicated `codex/saved-owner-projection-oct8` / `muon-saved-owner-projection-oct8`. GPT-6 / Codex desktop implementation and author self-review; exact variant/effort not exposed. Claude unavailable, idle, not resumed. No independent review claimed. App PR stays OPEN for UAT; no Stable authorization.

## Engineering

Production OfflineStore.projectSavedEntries replaces the inventory-wide Java ID/key counts and removable-ID set with an operation-scoped private no-backup SQLite census. Full all-state/hidden index rows contribute; exact UTF-16 BLOB names distinguish even unpaired surrogates and embedded NULs. ID/key counts saturate at2. One raw request and its name encodings are projected at a time. Successful build precedes display emission; query errors propagate. The census transaction remains uncommitted, serializes users and rolls back on close/failure; every invocation builds afresh. Original Media3 schema/requests/audio are never written. SQLite page cache requested at256KiB, not an exact process heap bound. Existing destructive admission still uses its own fresh complete safeguards, not the display page or this display-only census.

Native SQLite controls cover equivalence to existing ownership across hidden aliases/all states, duplicate IDs/exact names, failed/cancelled reads and empty scratch lifetime,2501-row streaming, main-thread/closed refusal, actual native index/cache projection and original preservation. Actual production LibraryModel tests also exercise the new census through OfflineStore. CI is the first Android compile/test/lint; final evidence must be recorded after it runs.

## Remaining

#253 still requires manager STOPPED task lifetime, destructive-operation full name maps, native cache key/span/content metadata and played claims/origin cardinality, and remaining live/service queue admission. This projection change does not complete those. Full row windows/one oversized legacy name, native page overhead, full scans/IO time remain; no measured heap/performance claim. #230 engineering complete/UAT pending; #179 recovery and release-trust reviews separately deferred.

#399/.770 passed798tests each variant; authorized POCO31-copy page/refresh/queue checks passed with unchanged15-row index and31audio payload hashes. SD playback eventually worked after about36seconds initial buffering: latency cause unestablished, not a startup/performance pass. Original volume restored; no card/lock/security changes. Multi-page/oversize UI, spoken TalkBack and broad user acceptance remain pending. Current slice has not yet run on the phone at this source checkpoint.
