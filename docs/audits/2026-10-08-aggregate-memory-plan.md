# #253 aggregate memory redesign: finite engineering plan — 2026-10-08

The user explicitly **requires broader saved-library paging and Media3/cache retention memory redesign before Stable**. It is not a post-release deferral or just UAT. Other release-trust deferrals and separate SD-recovery #179 remain unchanged. Source acceptance PRs stay open; this document does not land app changes or authorize Stable.

Inspected application baseline: #390 d0df73a64b3758375424293fca9638dad2b5dee4, including #389 and inherited acceptance stack, main5a123109. Later focused implementations below were reviewed against their own heads. Initial mapping by Claude Opus5.5, Claude Code, High; GPT-6 (Codex desktop, exact variant/effort not exposed) verified relevant sources, corrected unsupported proposals and maintains this plan. Unverified hypotheses are identified. No phone OOM, heap reduction or jank improvement was measured.

## Preservation and decisions

- Every previously visible saved copy remains accessible, with unknown/oversized tags and Unverified status preserved. Originals and uncertain/shared bytes are never discarded to meet a memory budget. Legacy rows that cannot form an existing bounded SavedRef remain hidden owners, not permission to delete them.
- Destructive decisions use complete fail-closed ownership evidence, never displayed pages. Existing sole-owner/request/token/epoch/removal-ack barriers remain.
- Preserve exact title/handle ordering and full display metadata. The mapping proposed clipping title sort keys to128 characters and queue labels to512: **not approved; rejected as a default implementation shortcut**. Merely documenting a behavior change is not authorization.
- User queue decision: full complete-copy queue in display order within a defined budget; above it explain refusal and offer a smaller selection. No silent prefix/window; shuffle/repeat remain unchanged. Initial implementation offers Play this copy or Cancel; larger selection UI can be a later bounded slice.
- Bounded pages do not imply bounded cache keys, saved-record count, manager tasks, ownership maps or full playback timelines. A finite gate must address each separately; no exact whole-process heap guarantee is claimed.

## Verified holders and current progress

| Holder / source | Current engineering status | Remaining cost |
| --- | --- | --- |
| Operations in OfflineStore: removal, naming, move admission/completion; SavedEntries.IndexCensus | #389 compact exact ID/key/state censuses, full hidden-owner participation, constant-time ownership lookup; native SQLite/cache failure tests | Census/name maps remain O(number and size of names); one full native row still allocated per read |
| RetainedDownloadIndex.inspect | #390 stores IDs only, then rereads/preserves one raw row at a time; refusal on missing/changed state; actual-manager tests | Pending IDs remain O(N); manager startup still retains stopped Download objects |
| OfflineStore.moveBatch / DownloadMoveReceipts | #392 stops beyond free receipt capacity; #397 adds4MiB retained-request payload and768KiB single-command admission before output | Logical cap is not exact heap; shared Binder contention and partial failed target output remain |
| TauonApi.json / TauonJson | #391 limits array slots and total lexical values before DOM, preserving existing resource-refusal/load behavior | Original bounded bytes/String and token allocations, DOM inside structural budget, and retained live library still coexist |
| ConnectScreen playable-copy count | #393 streams/counts without song decode, ownership maps, display list or sorting | Native row allocation and cache key/span snapshots remain; actual saved screen still full-list |
| SavedCopies.playable and MuonApp.playSaved | Verified #394/.750 removes full complete-entry filter, prevalidates count/text, full queue or explicit smaller selection | Initial saved display list remains O(N); bounded preparation synchronous on tap pending paging; actual heap overhead unmeasured |
| LibraryModel.saved / OfflineStore.savedEntries / sortSaved | Full decoded list plus sort still retained | **#398 derived catalog/streaming foundation only; runtime paging NOT wired** |
| Player/session/controllers | Pinned-source verified full list conversions, window/period and source per item | Chunked IPC does not bound resident queue; live-library queue costs still need disposition/guards |
| Cache keys/content metadata/spans and DownloadMarks bootstrap | Some streaming raw-record projections exist | **Cardinality/aggregate budget work remains**; no automatic eviction of protected saved data |

Existing live-load limits remain:2,048 playlists,50,000 occurrences,16MiB encoded metadata;16MiB response bytes/depth64; per-track/saved-display metadata bounds and locator bounds. Read source for actual constants. These guards do not complete #253.

## Next bounded slices and acceptance

### 1. Exact disk-backed saved catalog, then bounded page hydration

A complete in-memory catalog of clipped titles is insufficient. The #398 foundation provides a **derived Muon-owned disk catalog** through supported Media3 cursor reads, one projected record at a time, without querying/rewriting Media3's private schema. It stores exact UTF-16 BLOB order keys and canonical locators in its own noBackupFilesDir SQLite database with atomic generations/bounded page reads. Runtime UI integration remains: this unused data layer alone does not reduce runtime memory. Verify latest #398 native-index/cache/rollback/order tests and carry forward private-storage/schema/version/original-preservation requirements.

Keep exact ordering: unknown titles last, full lowercase title and full canonical handle tie-breaker as sortSaved currently uses. Native SQLite text collation is not automatically Kotlin UTF-16 String ordering; verify supplementary Unicode and use an equivalent sortable representation/comparator. No clipped sort keys. All copies remain reachable through count/page queries; no permanent row ceiling that hides data.

Hydrate a fixed number of bounded pages (initial target4×50 entries) with a generation-tagged LRU. Read current records by locator; vanished/rebound/unavailable rows become explicit refresh-needed placeholders, never a different copy. Cancel stale loads on refresh/server/store changes and preserve last good catalog on failed rebuild. Page flags enable UI only; removal rechecks ownership as now. Avoid performing sorting or record/tag decoding on main.

Tests: native-index/cache equivalence to production savedInventory/sortSaved across states, duplicates, unknown/oversized tags, phone/card/played copies; full Unicode/title tie order; no partial catalog publication; old generations/rebound records; every copy paginates exactly once. Then model-generation/LRU tests and manual saved-list/scroll/refresh/TalkBack acceptance. Data layer and UI wiring may be separate PRs, but unused infrastructure alone does not establish reduced runtime memory.

### 2. Queue preparation from catalog and service admission

Retain the user's full-queue/refusal policy and unchanged metadata. Stream selected catalog refs/entries into the existing bounded queue plan, off main, with cancellation/generation checks so an old pending saved selection cannot replace newer live playback. Offer an explicit smaller selection above budget; current single-copy option is the minimal first implementation.

Verify session-side input/ref retention as well as the UI path, mixed queues and repeated Add operations. Tests must preserve full within-budget order/duplicates/selected index, old queue on refusal/cancellation, and no hidden fallback. UI fallback and hardware playback are pending UAT.

**Do not automatically replace per-ID admission reads with a full-index census.** Current lookups hold one raw row at a time. A full census for one queued copy can allocate more maps and scan more rows. Compare measured work/retention; if batching helps, retain only required bounded locators while still refusing missing/rebound entries. This optimization is not an independent release requirement absent a demonstrated remaining budget issue.

### 3. Manager retained tasks and active-operation budgets

Pinned DownloadManager.InternalHandler.initialize loads unfinished **and STOPPED** rows as full Download objects. Internal/main lists share record references, not separately deep-copied request bytes, but keep those bytes alive. The startup-ID reduction does not solve that lifetime retention.

An initialization-only manager view is a candidate, **not yet approved by source/runtime proof**. Keep a separate complete persistent catalog view; no hiding/deleting retained records from Muon's own reads or source ownership checks. Verify manager add/remove/restart/release/global-command semantics and actual service delivery against published pinned sources before changing it.

Source verification found important corrections to the first mapping: addDownload/removeDownload look up absent records with loadFromIndex=true; setStopReason(id) uses **false** and its index fallback applies to terminal rows; removeAllDownloads combines its in-memory tasks with only COMPLETED/FAILED index rows before setStatesToRemoving. Omitting STOPPED rows can therefore break bulk semantics. Do not assume every command simply reloads all omitted records. Test supported ordinary removal, retries and all admission paths, failure latching and cold restart with many retained records, with every original field/byte preserved.

If a safe manager view cannot be established, design an explicit alternative boundary. **Measuring/disclosing it is not an accepted deferral** under the current user decision. Separately budget fresh process tasks/service commands before constructing huge retained queues; refusal must preserve saved rows and explain retry.

### 4. Full ownership/name/cache/native cardinality

Preserve full hidden-owner evidence while reducing resident key/count maps: consider bounded disk-backed census/name snapshots or streamed checks of only candidate keys, with generation/exclusion validation. Never infer sole ownership from a page or skip a giant/unreadable row. Test hidden aliases, all-state rows, mid-read failure and exact request safety.

Review SimpleCache content-index lifetime, played-record metadata and span/key snapshots using pinned sources; hoist repeated getKeys copies within one operation where safe. A new indefinitely retained origin map could worsen memory or go stale: use bounded/derived lookup and preserve protected-played claims. No migration/eviction of originals merely to fit RAM. Native CursorWindow failure containment #395/.751 passed actual native SQLite fixtures at a deliberately small window (770tests per variant); originals preserved, no phone failure claimed.

### 5. Explicit legacy command and live-queue budgets

Move legacy requests now have a4MiB logical batch-retention budget, plus a768KiB command admission limit with4KiB envelope (#397): logical preflight avoids unbounded Parcel allocation, then actual request Parcel size is checked before target output. Oversized entries are kept with an explanation; retryable remainder remains untouched. Final-head #397/.755 actual reports/APK verified777 tests per variant, including tagged Intent envelopes/native-index/cache preservation. This is conservative admission, not guaranteed Binder delivery under concurrent load. Other live/service queues still need review; no metadata rewriting.

Review live queue duplication/retention against its existing aggregate load budget and establish explicit admission limits where needed. All remaining logical budgets must have real boundary/failure tests; device measurements qualify them but do not replace source preservation rules.

## Pinned-source evidence and completion rule

Google Maven media3-exoplayer source SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`: DownloadManager.initialize/add/remove/global commands, DefaultDownloadIndex/DownloadCursor, ExoPlayerImpl.createMediaSources. media3-session SHA256 `9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715`: MediaControllerImplBase.setMediaItems/setMediaItemsInternal and MediaSessionStub list retrieval. Reverify actual build pins on resume; these receipts identify inspected artifacts, not a generic supplier-authentication conclusion.

#253 stays open until runtime paging/queue/manager/cache and remaining budget outcomes are implemented and verified, then UAT and landing. A map or passing preparatory tests is not completion. Any new unavoidable limitation needs an explicit user disposition; do not reuse the release-trust deferral for application memory engineering.
