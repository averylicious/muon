# Original issue descriptions before the concise-state workflow

Archived 2026-10-21 before editing the GitHub bodies for #181, #253 and #401. This preserves their original problem statements and dated evidence; it is **not** current status, ownership, scope or new permission. Use the [maintained issue-state index](issues/README.md) and [release plan](../release-plan.md). Historical comments remain unchanged.

## Original #181 — Main-branch audit: correctness, security, privacy and release trust

## Scope and continuation

The user authorized a new independent Astra audit on 2026-09-27: main-branch correctness, security, privacy, CI, dependencies, backend and general bugs. Claude/user experimental Material 3 alpha work may proceed on a separate branch. Both agents can merge blocker-free PRs at CI-verified heads; no Stable release, feed/signing change or phone access is implied.

This is broader than historical #26 (closed at the user's request); retain that report as history. This issue tracks the new audit, not a claim that every component has been checked.

- Baseline: `f0909f0c5174174a999240e8cebd7900702d2cc0`.
- First fixes/map: #180 (`329ff0107e2a3719fbb2b10ac86e65b99083ff44`).
- [Repository map and workstreams](https://github.com/averylicious/muon/blob/main/docs/audits/README.md).
- [Evidence and findings](https://github.com/averylicious/muon/blob/main/docs/audits/2026-09-27-main.md).

## Coverage queue

- [ ] CI/release trust, publication recovery and resolved dependency integrity. First CI selector fix is #180; complete coverage remains open.
- [ ] Network/controller boundaries, cancellation and fresh-install discovery (the Benchmark failure has not been reproduced here).
- [ ] Offline consistency, storage exhaustion, move/delete races and SD lifecycle (#179).
- [ ] Playback/queue/shuffle/gain/lifecycle reliability, preserving documented process-death limitations until deliberately implemented.
- [ ] Library/UI async correctness, resource limits, navigation, gesture and accessibility regressions; coordinate overlapping experimental files.
- [ ] Measurement/release readiness and remaining user QA; Baseline Profile measurements stay in #83.

These organize checkpoints; agents may follow findings across components. For each continuation record current head/branch, reviewed files, demonstrated findings versus hypotheses, exact tests/CI, manual QA gaps, active agent/file ownership and the next investigation. Do not spend an entire window exploring without leaving evidence. Never call a passing build a security certification.

At creation: GPT-6 Astra (Codex) owns the main audit branch; Claude Opus 5.5 reviewed the first three fixes at Medium and reported no experimental branch active. Refresh live status rather than treating this snapshot as an ownership lock forever. Usage readings are session-specific; Astra's quota is unavailable through the current tools.



## Original #253 — Audit follow-up: bound aggregate library and metadata resources

## Existing audit question, now a portable follow-up
This tracks Q2 from #203, not a new demonstrated OOM or measured jank. Inspected main `17309b11dd339dd833a8bf135b1b30b2d0f273a7`; #245 and #252 are separate pending-QA fixes for main-thread projection and recursive depth. Neither establishes aggregate heap bounds.

Source paths: TauonApi.json buffers a wire response, creates UTF-8 text and a DOM before projecting; LibraryModel.connect retains every successful playlist list plus the previous library during refresh; allTracks flatten/distinct and MuonApp grouping/artwork identities add snapshots/indexes. Each MediaItem carries display text and encodeSong bytes into session/DownloadService payloads. A finite per-body limit does not bound total playlist records, single-field size, DOM expansion, refresh overlap or a single IPC item. No reproduction of a phone OOM/TransactionTooLargeException has occurred.

### Bounded next slices
1. Inventory actual retained representations and highest-risk individual payloads. Add disposable fixtures for wide arrays, many playlists and unusually long tags; don't run an uncontrolled OOM on the user's device or infer phone heap from desktop timings.
2. Propose explicit per-field/per-item and aggregate-load budgets, with compatibility rationale for large legitimate libraries, duplicate/shared-playlist accounting, partial-load/old-library preservation and a clear recoverable error. Avoid silent truncation or deleting cached media. A Binder buffer is shared, so one-item size checks alone are not a guarantee.
3. Implement one budget boundary at a time with regression tests proving rejection/preservation and retry. Source-check actual pinned Media3 IPC list transport; don't assume it chunks an oversized single item.
4. With separately authorized device QA/measurement, check legitimate large libraries, refresh responsiveness/memory and controlled malformed cases. No measured performance benefit should be claimed from CI.

Keep this separate from retained-ID identity #213, SD lifecycle #179 and transient worker ownership #225. Existing #39 auto-connect trust remains a product decision; no policy change/feature/refactor is authorized by this issue alone.


## Original #401 — Investigate long saved SD-copy playback start on POCO

## Observed behavior
During authorized POCO X3 NFC USB/root checks of saved paging #399 (Canary .770, `2764f987329f7775e5bfef07c791986091f39334`), selecting one completed SD-card copy builds the full31-item saved queue but initially stays BUFFERING for roughly36–39seconds before playback starts. This repeated after stopping/restarting the app: system-session samples at2.13 through36.71seconds stayed BUFFERING/position0; at38.76seconds PLAYING. A third cold start also eventually played. No session error observed. The same selected song was used; this is not proof every card copy or every device is affected.

## Scope / evidence
- Android16 custom ROM, app installed as normal signed Canary update. Library had15completed SD request rows plus16played copies.
- All15index rows matched every column after the first check, and all31audio-span payload hashes matched. No card unmount/mount, deletion/move/download or security-setting changes.
- Tests muted, playback paused/stopped and original media volume restored. Device/private records/screenshots kept local, not uploaded.
- A root Java-trace request produced no usable trace here; no security-policy changes attempted.
- No parent-build device comparison, extractor/read/admission timing or root cause yet. Do not assume paging caused this. Cache-only route and startup must be instrumented/verified before claiming a fix.

## Next bounded investigation
Use an explicit temporary diagnostic build if needed: time queue preparation, session admission and first cache open/read separately; compare another complete card copy and a phone played copy; inspect the pinned extractor/load behavior; compare a refreshed parent build without uninstalling or downgrading data. Preserve originals/volume and leave app acceptance PRs open. Recheck device authorization at the next cycle.

## Attribution
GPT-6 / Codex desktop, implementation-author device checks, exact variant/effort not exposed. Claude unavailable/not resumed; no independent review. #253 remains required before Stable, and full #179 SD eject/restart recovery remains separately user-deferred. This is a distinct observed playback-start issue, not a claim that OS eject recovery has been fixed.


