# Remaining main audit work — 2026-10-03

Baseline `8dea8cabf7089614acfaa6e07e7d4e6336f6b80d`; live PR/issue status checked October 3. GPT-6 / Codex desktop, effort not reported. This is a planning map, not new product authorization, a release request or a security certificate.

## Closest planning estimate

**About 75% of the first source-review pass, with roughly ±10 percentage points of uncertainty.** This replaces the older roughly 70% planning estimate. It is judgment across inspected workstreams, not measured file/line/path coverage. Recent work narrowed cache preservation, request/controller/library/queue behavior and CI/wrapper trust. It does not imply 75% of fixes are merged, 75% of remaining effort is predictable, or Stable readiness. Difficult ownership and recovery questions dominate the remaining effort; newly found issues can change the estimate.

An overall audit-and-release percentage would be misleading until significant findings have dispositions and user QA is complete. The checklist below is the operational measure.

## Source/fix work still outstanding

| Area | Established evidence / fixes | Remaining concrete outcome |
| --- | --- | --- |
| SD lifecycle/data preservation (#179) | Real cache/index/path/metadata controls; S1 availability candidate in #289; service reuse and volume-identity source evidence | Generation ownership, writer quiescence, capture completeness/durability, safe service dispatch, pristine-card adoption, idempotent migration/recovery, then disposable card-loss/reattachment QA. #179 had been closed; reopened this cycle because production finding remains unresolved. |
| Retained audio identity (#213) | Disposable numeric-ID-reuse characterization; artwork identity fix exists separately | Preserve old downloaded/played bytes while refusing an ambiguous reused Tauon ID; settle matching/retention semantics and implement/test them. No silent delete/rekey/migration. |
| Partial move targets (#230) | Late-publication guard in #289; non-deleting byte comparison; partial-span fixtures | Preservation-first cleanup/retry ownership for failed or stale copies. Running copy cancellation/orphan cleanup not implemented by #289. |
| Aggregate resources (#253) | Response/per-record candidates; library/queue representations inventoried; R9 source questions narrowed | Compatible peak-parse/retained-library/queue/offline metadata policy, representative fixtures and measured effects. Do not confuse serialized bytes with heap or IPC behavior. |
| Dependency/build trust | Pinned graph/advisory scan, qualified tool/crypto callers, wrapper checksum/order, tag/publication/protection guards | Untraced transitive/tool advisory paths, artifact verification/locking with a reviewed trust basis, Gradle/action cache provenance and remaining actions/SDK/JDK sources. No current compromise claimed. |
| Playback/platform/library finish | Request lifetimes, controller gates, queue duplicates/Undo, library loading/navigation and sheet guards reviewed | Audio-focus/lifecycle and hardware-controller behavior; fresh-install discovery attribution; broad accessibility/lifecycle/viewport timing and unresolved integration regressions. Existing queue/position process-death limitation stays documented rather than silently becoming a feature promise. |
| Performance measurement (#83 and resource work) | Baseline Profile exists; source-backed workload/copy questions and fixtures | Controlled release-like before/after timing, representative heap/GC traces and bulk-operation latency. No optimization benefit established by source audit or CI alone. |

## Implemented fixes awaiting integration/acceptance

At the initial live refresh, before this cycle’s new test/report PRs, **28 PRs were open**. Many overlap; this does not mean 28 independent implementations remain. Exact Git ancestry checked from shared local objects:

- **#289 storage candidate:** includes exact current #264, #281, #234 heads. POCO .494 has mounted-card disposable move/removal evidence; unresolved card-loss and user-acceptance gates remain.
- **#290 network/queue/library candidate:** includes exact current #279, #273, #267, #258, #257, #252, #250, #245, #242, #229, #227, #224, #219, #217, #209, #206, #205 heads. Pixel .496 has limited Undo/cancellation/scroll evidence. Hardware/network/lifecycle checks and user acceptance remain.
- **Not included by exact current-head ancestry in either candidate:** #248 metadata codec, #240 bootstrap state, #237 copy scheduler, #221 bounded notification artwork, #212 played-copy byte budget, #210 artwork identity. They require fresh integration/source review/current-head checks and their documented phone gates. #290's finite metadata-client deadlines do not implement #221's image byte/decode limits.

Do not close component PRs merely because a combined candidate compiles. After user acceptance and current-head/destination checks, the combined PR can land, then explicitly close or mark integrated the covered components/issues with receipt links. Preserve own/independent review attribution.

## Device/release gates

- #289/#290 remain open under the user's acceptance gate; coordinator ADB observations are not that acceptance.
- Pixel lockscreen explicitly deferred; do not change settings to force controls. Remaining headset/Bluetooth/notification/controller compatibility is hardware QA.
- Genuine offline/card unavailable/replacement/mid-IO/removal races need an isolated disposable setup. Existing user card downloads are not a failure-injection fixture; raw external-file backup does not restore the protected index.
- Queue reorder/removal/reconnect Undo, rapid/mid-animation sheet cancellation, network interruption, rotation/short viewport and remaining artwork/played-cache candidates need focused checks.
- Stable promotion requires an explicit release request and a disposition for significant findings. This pass audits **main**, not the maturing Expressive branch; bringing main fixes forward and auditing that combined experimental source are separate outcomes.

## Resume order

1. #293/#294 have landed their verified cache/manager/progressive controls (443/444 tests per variant, zero failures/errors/skips). Continue #179’s owned writer drain and generation design; no production recovery is implemented.
2. Continue #179 ownership design or #213 preservation policy in bounded steps; #253 compatibility/source evidence can progress independently.
3. After user acceptance, land refreshed combined candidates and reconcile component trackers, then integrate the six separate candidate heads deliberately. Never mix the experiment into main.
4. Complete remaining dependency/lifecycle/measurement questions; consolidate a release gate list rather than advancing a percentage from PR count or test totals.

Current work/CI/ownership belongs in the coordinator checkpoint and live #181/#40 receipts. A later head or newly discovered path is not silently covered by this dated estimate.
