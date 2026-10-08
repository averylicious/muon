# Bounded holders and opt-in migration checkpoint — 2026-10-09

## Ownership and authorization

Main-only documentation checkpoint, based on main `c1708a58a4f32461a5528bdac2c993daea91f3ec`. GPT-6 / Codex desktop implemented and author-self-checked the solo slices; exact variant/effort not exposed. Allocated audit Claude session `8eec8ee5-3d58-4ac2-b74f-fa29e2fa8a91` was explicitly verified `claude-opus-5-5`, High and implemented #417. Root source review of Claude's removal slice is distinct from root's own author checks. No Codex subagent spawned. Local session ID is optional; repository/PR receipts are portable authority.

Claude reached its actual session limit first during subsequent diagnostic preparation: last event97% five-hour/85% weekly USED, then rate_limit and CLI exit1; no implementation was produced on that second assignment. Claude is idle, not resumed until quotas permit. Codex latest read38% weekly USED, ordinary usage allowed; no five-hour reading exposed. Refresh these readings rather than reuse them as current.

No ADB, phone settings, app installs or experiment mutations this cycle. The user explicitly withheld device QA. Experiment remote moved independently to `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; do not assume its owner idle or reuse that checkout/session. Main audit and experiment integrate through separate owner-landed forward syncs. App stack stays OPEN for acceptance, no app merge/Stable tag/release/auto-merge/bypass. Main's published app .734 excludes this stack; this checkpoint contains no app code.

The user approved **preparing opt-in partitioned storage migration**, with old copies readable and removal only after their replacements are byte-verified; temporary free space is required. No production migration, source deletion or acceptance is implied by that approval. [Finite source-backed migration plan](../audits/2026-10-09-partitioned-cache-migration.md) records its preservation constraints and remaining stages.

## Exact app boundaries

#416 and #417 were sibling continuations of the preceding acceptance boundary; #419 combines them with #418. #420 through #429 are cumulative descendants, not separately squashable siblings. Preserve all ancestry and rebase/integrate at an explicit accepted boundary. Every listed app PR targets main and is OPEN; verify again before any eventual merge.

| PR | Latest head | Scope | Android CI |
| --- | --- | --- | --- |
| [#416](https://github.com/averylicious/muon/pull/416) | 499a907e867c0a6a560d5db8fb8590aa50491c1e | final aggregate playback and explicit smaller selection | [.798](https://github.com/averylicious/muon/actions/runs/37846002610) success |
| [#417](https://github.com/averylicious/muon/pull/417) | 1b3b503777690128acc598ff164a90677be67e05 | bounded removal acknowledgement/delivery | [.799](https://github.com/averylicious/muon/actions/runs/37846582896) success |
| [#418](https://github.com/averylicious/muon/pull/418) | 83d0156f4c9a9a9a5b2416a83e761faa38212454 | disk protected played claims | [.804](https://github.com/averylicious/muon/actions/runs/37848053519) success |
| [#419](https://github.com/averylicious/muon/pull/419) | c6bce2cf25974f1beb3de66171b86a4b3b5a708f | disk complete destructive ownership | [.805](https://github.com/averylicious/muon/actions/runs/37848059798) success |
| [#420](https://github.com/averylicious/muon/pull/420) | 38d15909efa827314f2628b9502ac2159de90e71 | opt-in diagnostic startup timings (no speed fix) | [.806](https://github.com/averylicious/muon/actions/runs/37848148432) success |
| [#421](https://github.com/averylicious/muon/pull/421) | 3b1b1d65bd7f8d6710dc53a596374b4a4f8c9919 | disk saved-origin grouping/selection | [.807](https://github.com/averylicious/muon/actions/runs/37848890574) success |
| [#422](https://github.com/averylicious/muon/pull/422) | 8f2afc66aada429075a7a6e228d592e1bbc6db5d | disk played-span eviction order | [.808](https://github.com/averylicious/muon/actions/runs/37849432555) success |
| [#423](https://github.com/averylicious/muon/pull/423) | 8ce7cb40f9765aa3cf9ef06b3b8588b5cfe922fd | scalar saved coverage without span copy | [.809](https://github.com/averylicious/muon/actions/runs/37849838248) success |
| [#424](https://github.com/averylicious/muon/pull/424) | 044e5b4c79d2cfbbb32c36a939bc3509b842e61c | disk streamed played inventory/count/clear | [.810](https://github.com/averylicious/muon/actions/runs/37850313200) success |
| [#425](https://github.com/averylicious/muon/pull/425) | b6a78da313ce69cb0db4727fb44369904b43cefa | disk per-ID byte tally | [.811](https://github.com/averylicious/muon/actions/runs/37850785211) success |
| [#426](https://github.com/averylicious/muon/pull/426) | 663bdff922f15a5dbe471bc6db697ea43ed11adf | disk status bootstrap/live tombstones | [.812](https://github.com/averylicious/muon/actions/runs/37851280432) success |
| [#427](https://github.com/averylicious/muon/pull/427) | 8891c278ffdd2b3c7e36a0bdd7ec07e354411146 | bounded page badges/scalar completed count | [.813](https://github.com/averylicious/muon/actions/runs/37852068689) success |
| [#428](https://github.com/averylicious/muon/pull/428) | ee8b7b401803d6f669ba6af0eeaf9caf688359af | unwired bounded migration copy/verification | [.814](https://github.com/averylicious/muon/actions/runs/37852602616) in_progress |
| [#429](https://github.com/averylicious/muon/pull/429) | bfe50bb572be3da548b0cf64ecfa80423a767d87 | unwired fixed native cache lease pool | [.815](https://github.com/averylicious/muon/actions/runs/37853043385) in_progress |

Required Branch direction checks also pass on the finished heads; PR-description edits can rerun those checks without changing code. Final migration CI is still pending in this draft: never call it a pass or use that draft as release acceptance.

## Actual artifact/report evidence

Actual downloaded signed APK and XML reports were verified for .798 (881tests/48lint warnings each variant), .805 (898/50), .806 (902/50), .808 (909/51), .812 (920/52), and .813 (923/53); all have0failures/errors/skips and0lint errors. BUILD/full commit/run, SHA256SUMS, existing signer/package IDs, non-debuggable ordinary Canary and three private services were checked. Other finished slices have exact-head successful workflows; their individual ZIPs were not separately downloaded. Their code is covered by later cumulative native/test reports, not presumed individual byte verification.

Current verified application candidate [#427](https://github.com/averylicious/muon/pull/427), head `8891c278ffdd2b3c7e36a0bdd7ec07e354411146`, [Android .813/37852068689](https://github.com/averylicious/muon/actions/runs/37852068689), [artifact11583086262](https://github.com/averylicious/muon/actions/runs/37852068689/artifacts/11583086262), `app-debug-8891c278ffdd2b3c7e36a0bdd7ec07e354411146`, APK SHA256 `ea72b66f58f77e2feeb152aa5793a2dc5a928cfd456d45dcb6e1334a165a89a8`. Version0.1.0-canary.813. Branch artifacts update the same Canary, expire after14days and are not Obtainium releases. No local Android build or runtime/heap/performance measurement is claimed.

#416 fixed an initial snackbar declaration compile issue; #418's earlier native fixtures used an invalid table name/failed-download failure reason, corrected before the final heads. Failed/superseded runs are not successful receipts. Successful compilation/test/lint does not establish physical-device behavior, bitmap quality, TalkBack speech or hardware controls.

## Engineering implemented on the open stack

Final streaming/playback mutations preflight aggregate size before queue/mode change, preserve the prior queue on refusal, and offer explicit Play this song instead of a silent prefix above the supported budget. Removal preparation/delivery is bounded and acknowledged at real service admission, with truthful refused/unconfirmed/completed distinctions, epoch/token/full-request checks and no destructive fallback.

Complete hidden-owner/played-protection evidence is streamed onto private disk rather than truncated. Played span ordering/inventory and saved-origin selection avoid separate lifetime/full-census JVM collections. Scalar saved coverage avoids copying every native span for display. Byte totals and mark counts use one scalar with exact per-ID disk rows; row badges are hydrated only with bounded loaded pages. Bootstrap uses one raw index row, fixed16-status publication windows and separate live-event tombstones, rejecting incomplete evidence and reporting Loading/Unavailable totals. Disk failures preserve source audio/index records; derived scratch/state is never source-deletion authority.

These reductions do NOT bound Media3's resident CachedContentIndex, native span sets or startup file-index map. Requested SQLite page caches are not exact process heap limits. Current move/native naming paths still copy native key/span sets, with preservation semantics that must remain during the new routing integration.

#420 provides timings only for explicit diagnostic builds, with constant phase/outcome and monotonic duration fields and no song/ID/address/error text. It does not attribute or fix the previously observed36–39second SD startup delay (#401). Ordinary signed Canary remains non-debuggable and does not emit these timings.

## Approved migration preparation

#428's disabled helper inspects one resource through scalar ranges, refuses over-budget metadata/fragmentation before target writes, preserves every retained partial range (even beyond declared length) and every unknown metadata field, requires strict synced writes and full byte comparison plus extent/metadata rechecks. It never deletes a source. Evidence is current preparation only, not a durable receipt or writer-exclusion proof. The caller must hold that barrier; failed/canceled/uncertain replacement data is retained for explicit recovery.

#429's disabled lease foundation counts opening/closing reservations against fixed native residency, never retires pinned readers/writer holes/listeners, refuses saturated admission without an accumulating queue and stops all new admission after an uncertain native release. No production callers exist for these helpers, so they are not a runtime memory redesign. Legacy supported-API import still opens the full old native index; transitional peak and over-budget retained legacy copies remain explicit limitations.

## Finite remaining gate and next work

| Gate | Current status | Required remaining work |
| --- | --- | --- |
| #253 application-owned holders/queues | Bounded reductions on OPEN app stack; source/native tests passing through #427 | UAT of counts/badges, bounded refusal/selection, removal/move/Undo/restart, UI latency; source review of final native preparation CI |
| #253 native cache | REQUIRED engineering, migration approved; preparation/lease foundations unwired | Exact persistent disk locator; complete pinned Cache adapter and per-resource metadata/span admission; lease lifecycle integration; migration journal/request/cover routing and restart/cancellation/source-removal revalidation; opt-in UI; native and later device verification |
| #401 saved-card startup | Cause/fix unresolved; diagnostic timing instrumented | Authorized phone/card vs phone storage comparison and parent/candidate timings; no device access this cycle |
| #230 preservation/moves | Defined engineering complete on OPEN stack | User acceptance of failure/retry/source preservation and moves; later native migration must not weaken those contracts |
| Hardware/queue/library/accessibility | App compatibility fixes remain OPEN for UAT | Bluetooth/headset, notification controls, queue duplicates/Undo, library scroll and sheet cancellation, TalkBack speech/focus; previous source/ADB checks are limited evidence |
| Full #179 SD recovery | User-deferred, unresolved | Separate follow-up after current release, emulator/device when convenient; existing narrower card checks do not prove live-process recovery |
| Dependency/tool/cache provenance | Both broader reviews explicitly user-deferred for this release | Planned post-release maintenance after current main ships and mature M3 integration; CI protections/inventory are not publisher authentication |
| Stable publication | Not authorized | Final accepted app integration, required latest-head checks, explicit Stable version/tag/release request |

Next bounded engineering is the migration's exact persistent disk locator and complete supported Cache routing contract; preserve every original/hidden owner and avoid automatically adopting/deleting ambiguous partitions. Integrate the lease and copy helpers only with tested availability/writer barriers. The native-cache gate remains engineering, not UAT-only and not covered by trust/SD deferrals.

Before resuming, read AGENTS/STATE/coordinator/audit map, refresh main/app/experiment heads and required checks, verify owners and quota, and use a fresh own worktree from the complete acceptance successor. Keep pending phone QA pending. Own new worktrees are committed; final migration runs/checkpoint refresh are the active remaining work in this draft.
