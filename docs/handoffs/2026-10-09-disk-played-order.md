# #253 played eviction order on disk — 2026-10-09

Continuation of #421 and the unmerged main-audit acceptance stack. GPT-6 / Codex desktop implemented/self-checked this slice; exact variant/effort not exposed. Allocated Claude is idle after an actual five-hour limit error. No phone QA/ADB this cycle.

## Runtime boundary

`OfflineStore.create` supplies `DiskPlayedSpanOrder` to `PlayedSongEvictor`. The extra whole-cache TreeSet of CacheSpan/file references is replaced by a private no-backup SQLite order keyed by exact UTF-16 key and position, ordered by last touch, full key and position exactly like the old comparator. The in-memory order is retained only for existing small fixture constructors. The derived table resets on the cache's first startup callback; old process rows never authorize eviction.

Native Cache callbacks keep the derived order current, under the cache lock. The oldest eligible key is selected one row at a time; the cursor closes before resource removal triggers mutation callbacks. Download ownership still refuses eviction until its complete census succeeds and for every claimed played key. Only whole played resources are evicted, never downloads or the key currently being written. Database/schema/read/write/closed failures disable eviction rather than fall back to a full map or delete uncertain bytes; the cache can remain over budget.

Pinned published Media3 datasource 1.11.0 sources were checked for CacheSpan comparison and SimpleCache callback/lock behavior. Source SHA256: a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a. No private native index schema was read or changed.

## Verification / limitations

Native SQLite tests compare all eligible order decisions with the prior TreeSet including long/NUL/supplementary/unpaired names, timestamps/touches and exclusions; reset stale derived rows; and exercise actual SimpleCache protected hidden claims, whole-song/multi-span eviction, active writer protection and failed-order preservation of original bytes. CI is the first compile/full test/lint run. Final head/check/artifact receipts are recorded on the PR and coordinator checkpoint. UAT remains pending: cache limit, recent offline playback, explicit downloads, clear/refresh and restarting.

SQLite is requested to use a 256 KiB page cache; this is not an exact native/process heap bound. Cache callbacks now perform private SQLite IO and phone latency remains unmeasured. Native SimpleCache still retains its own full content/metadata/span state; its getters/resource removal still produce snapshots. This slice removes Muon's duplicate ordering holder and does not complete #253 or establish a fix for startup #401. Originals are never evicted just to meet a RAM target. No app merge/Stable release/tag occurred.
