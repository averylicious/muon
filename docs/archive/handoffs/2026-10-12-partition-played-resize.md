# Bounded played-cache resize — October 12

Solo #253 main-audit successor to completed deletion. No Claude or phones. Partition mode remains disabled. Full Delete/Move for legacy originals is required before activation by the user's October 12 decision.

Actual partition natives now opt into Media3's supported cache-span access timestamps through a touch-only NoOp evictor. No resident eviction collection is introduced. Worker resize streams total played usage, scans fixed 16-row save-journal pages and probes at most one bounded resource at a time to select the least-recently-used eligible **whole copy** (newest span access). Removal rechecks the same Closed UID/allocation and observed access timestamp under exclusive access. An intervening real read invalidates the candidate. Idle pools are retired only when known idle; active/EOF readers, caller/index claims, original/Ready migration copies and uncertain owners remain protected.

Startup maintenance and Settings limit reduction use this path after the played-copy actor drains. Results count actual removals and report protected/busy/unknown remaining bytes. Closed tombstones remain and repeated shrink is idempotent. A full optional played-copy admission still declines; automatic make-room for a new network copy remains engineering. Settings does not promise that unfinished behavior.

Bookkeeping is bounded, but repeated census/selection for each removed copy can be quadratic in library size. This is a correctness/memory boundary, not measured performance. Cache timestamps are eviction ordering only, not identity or deletion authority; pinned SimpleCache may log failed timestamp persistence. Every removal still validates actual target ownership and reader exclusion.

Pinned Google Maven Media3 1.11.0 datasource sources inspected: CacheEvictor.java requiresCacheSpanTouches; SimpleCache.java 227, 359, 637–661 records supported access timestamps when enabled. No private native schema is mutated by the eviction policy.

Verification: git diff --check; first Android compile/test/lint via Actions pending. Actual native SQLite/Robolectric tests cover last-read ordering, restart after eviction, protected original bytes, reader at EOF and 19-copy paging/caller claims. Final exact-head CI/report/APK receipt belongs on the PR/#253/current checkpoint. Application acceptance remains pending, PR OPEN. Continue authorized engineering across verified boundaries while capacity permits.
