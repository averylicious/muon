# #253 saved-origin census on disk — 2026-10-09

Continuation of #420 and its startup diagnostics; all inherited acceptance PRs remain open. GPT-6 / Codex desktop implemented and self-checked this slice (exact variant/effort not exposed). Claude hit its five-hour limit during the preceding diagnostic preparation and is idle. No device QA or ADB this cycle.

## Implemented boundary

`SavedCatalog` stores each projected entry's exact UTF-16 origin in its own private, derived database. `preferredOrigin` selects the most-common non-null origin, with the exact existing unknown-title/full lowercase title/full handle display order on ties. Incomplete and unknown-tag entries still participate, as before. `SavedPaging.reload` no longer builds a whole-library origin HashMap/candidate set or repeatedly hydrates pages to break origin ties.

Private schema v1 -> v2 adds a nullable BLOB column and index; it preserves the prior committed page/generation. A complete atomic rebuild fills origins. It does not query or migrate Media3's private schema or change downloads/audio. Failure/cancellation retains the last complete catalog, with generation checking in the same transaction as origin lookup. Native SQLite is requested to use a 256 KiB page cache and file-backed temporary storage; this is not an exact total heap/SQLite allocation guarantee.

Native tests cover count/tie equivalence including incomplete/unknown entries, long/NUL/unpaired-surrogate origins without clipping, stale/reentrant/another-connection checks, failed rebuild rollback, and migration from the actual previous private schema. Existing paging integration tests remain.

## Verification and next work

CI is the first compile/full JVM and lint run; final head, check and artifact receipts belong in the PR and coordinator checkpoint. User acceptance remains pending. Check offline reload/refresh, library ordering and saved fallback connection choice after upgrading an existing install. All copies must remain accessible.

Still required under #253: native SimpleCache content/metadata/span retention and key snapshots, played eviction ordering, and other source holders identified in the aggregate-memory plan. Startup diagnostics are instrumentation, not a demonstrated fix for #401. No whole-process heap or phone latency improvement is claimed. No app PR was merged or Stable published.
