# October 9: new-save ownership follow-on checkpoint

Read the [preceding native-cache checkpoint](2026-10-09-native-cache-checkpoint.md) for #433–#439 exact heads and actual report/APK receipts, and the [production integration map](../audits/2026-10-09-partitioned-production-plan.md) for remaining engineering. This follow-on does not enable migration, clear #253 or reduce the Stable gate to UAT.

## Verified boundary and ownership

- Included main: `dddef2d180f480059fbcdd9df0aefd415835c3d3` (docs-only #440). Exact .844 and main .845 passed lightweight checks, generated zero artifacts and skipped publication.
- Complete app successor: [#441](https://github.com/averylicious/muon/pull/441), branch `codex/partition-save-ownership-oct9`, head `6947c7a1a2dd6b65b489d2ab6af0d876f6e0bf35`. It includes #439 and current main. The only integration conflict was the main checkpoint's richer downloader handoff receipt; retained that verified document.
- Android [.846/37908570128](https://github.com/averylicious/muon/actions/runs/37908570128), Branch direction and dependency inventory passed. Actual downloaded reports confirm 1,047 tests per variant, zero failures/errors/skips, zero lint errors and 64 warnings each. The six native journal and six owned-cache tests passed in both variants. BUILD.txt confirms `0.1.0-canary.846`; [artifact 11605198923](https://github.com/averylicious/muon/actions/runs/37908570128/artifacts/11605198923), `app-debug-6947c7a1a2dd6b65b489d2ab6af0d876f6e0bf35`, has APK SHA256 `83fe70226b5b03643e7c46c8b0739f0221072913be5ca17e4c10b738faebd40b`. Existing signer, Canary package, non-debuggable identity and three private services verified. This is a 14-day Actions artifact updating existing Canary/data, not an Obtainium release or separate per-PR app.
- Model attribution: GPT-6 / Codex desktop implementation and author source self-check, exact variant/effort not exposed. Inherited Claude Opus5.5 / Claude Code / High #434 implementation/root review retained on its PR. Root's own new code is not independently reviewed.
- All application PRs remain OPEN pending acceptance. Original experimental checkout/session untouched; no phone/ADB commands, Stable release/tag, auto-merge or bypass. Main docs do not install the open app stack or publish a newer app; main's latest app remains .734.
- All eight implementation worktrees are idle at this verified boundary. The contributors this cycle were root and the explicitly allocated audit Claude session; both are idle. Every implementation worktree is committed/pushed; no uncommitted source depends on temporary logs. The main-only follow-on docs worktree is being finalized; verify its final PR/merge receipt before takeover. Local paths/session history are optional conveniences, not prerequisites.

## Prepared change and remaining work

The separate exact new-save journal never fabricates migration Ready or download completion. Opening/Open surviving a crash cannot authorize restart adoption. Only verified native index/sidecar/database close persists Closed before returning residency. Busy capacity does not poison a clean route. New-save/migration collisions, stale tickets and missing/unknown/malformed journals refuse without replacing old bytes. [Source handoff](2026-10-09-new-save-ownership.md) records tests and limits.

This is still UNWIRED. Production new-save allocation and command orchestration must tie the reservation/journal to exact request, saved identity, completion record and covers. Saved/download/played routing, actual reader/writer/removal/source/eviction/availability barriers, controlled failure/restart recovery, bounded space/cancel/deadline/progress worker and opt-in controls remain REQUIRED engineering. A refused Downloader.remove alone cannot preserve a manager record. Every uncertain/original copy remains preserved; legacy full-index import still has an unresolved transition peak.

Next bounded slice: introduce the production backend boundary around the concrete OfflineStore Shelf/Store cache and saved-source assumptions, first with a behavior-preserving legacy implementation and focused manager/removal tests. Then route new-save operations through the owned factory/journal/reader/downloader under exact command ownership; do not flip routing while its record/cover and recovery semantics are incomplete. Reconcile current main and run exact-head checks before any app landing; do not independently squash overlapping ancestor PRs.

## Finite Stable gate

| Gate | Current state | Remaining |
| --- | --- | --- |
| #253 app-owned queues/holders | Implemented on OPEN acceptance stack | User acceptance of counts/refusal/selection/delivery/move/Undo/restart and responsiveness |
| #253 native storage | Tested disabled foundations, including separate new-save lifecycle | Production orchestration/backend routing, real barriers, migration worker/recovery/opt-in and controlled preservation tests |
| #401 saved-card startup | Unresolved cause/fix | Separately authorized diagnostic parent/candidate/card/internal comparison; no speed claim |
| #230 move/preservation | Defined existing-backend engineering complete on OPEN stack | UAT; new backend must preserve exact command/complete-copy/source-removal/record/cover rules |
| Hardware/library/accessibility | OPEN acceptance | Bluetooth/headset, notifications, duplicates/Undo, scroll/sheet cancellation and TalkBack spoken/focus checks |
| Full #179 SD recovery | User deferred, unresolved | Separate post-release device/emulator work |
| Dependency/tool/cache provenance | Both user deferred for this release | Planned post-release maintenance, not authenticated by inventories |
| Stable publication | Not authorized | Accepted final integrated app/checks and explicit release request |

No rooted POCO is needed for disabled helpers. Tell the user when actual production routing/opt-in controls are ready for disposable private-index/card/restart/cancel/original-byte checks; ask for renewed rooted-ADB access then. Historical permission is not reused. #401 diagnostic measurements can be separately authorized. User hardware/accessibility UAT remains separate from source/CI evidence.

## Quota and takeover

Claude finished its earlier assignment normally and remains idle, last observed 78% five-hour / 92% weekly USED. Root continued solo; final October 9 account reading was 58% weekly USED, no five-hour field exposed. These are dated observations, not guarantees of current quota. Refresh live heads, checks, main/experiment ownership and quota before continuing. No hard cutoff observed. Every implementation boundary is committed/pushed; final exact-head receipts and idle status belong on the PR and #40/#181/#253.
