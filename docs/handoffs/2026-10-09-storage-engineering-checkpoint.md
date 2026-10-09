# October9: storage engineering and measured saved startup

This is the current portable continuation for Stable/main auditing. It supersedes prior source/check/ownership snapshots without discarding their historical evidence. User-approved [continuous engineering](../continuous-engineering.md) removes the one-slice-per-turn cap: implement, verify, fix, checkpoint, then continue while useful authorized engineering and prudent capacity remain. A green run/PR/checkpoint alone is not a stopping condition.

## Tracks and permission

Main inspected at602933e5ad1d8ecc65048e8a776488df6acec013 (policy PR#448 merged). Strict required Build, test and sign / Branch direction, up-to-date branches/admin enforcement and no force/deletion were live-verified this continuation. Refresh protection/main/PR heads before merging; these are snapshots. User experiment stays independent at claude/m3-expressive-alpha; no session takeover or forward integration performed.

Application acceptance stack remains OPEN under the phone-QA gate. Prepared partition components remain disabled in production; no Stable release/tag/version decision, auto-merge or bypass. Documentation-only checkpoint can self-review/merge only at its current protected green head. No signing material/private phone databases/traces/song metadata uploaded.

Current task explicitly authorizes rooted USB POCO audit verification. Device verified surya/M2007J20CG. Permission is dated, not future standing authorization. No new Pixel testing, eject, root-manager/SELinux/security changes or original-data deletion.

## Exact source and CI evidence

All rows are OPEN application PRs. Reports and signed APK identities/checksums were actually inspected for VERIFIED rows; test counts are per variant with zero failures/errors/skips. Lint has zero errors (warnings64 through#451;65 thereafter).

| PR / branch | Exact source head | Android run / Canary | Actual tests / state |
| --- | --- | --- | --- |
| [#449](https://github.com/averylicious/muon/pull/449), codex/partition-command-owner-oct9 |08fe3c0197474d019ef483286045d3e11eb028de|[37922459522/.861](https://github.com/averylicious/muon/actions/runs/37922459522)|1076, VERIFIED |
| [#450](https://github.com/averylicious/muon/pull/450), codex/partition-completed-audio-oct9 |c876e5c56c49aa8a58c835a3fa89458d42bff166|[37924743469/.864](https://github.com/averylicious/muon/actions/runs/37924743469)|1085, VERIFIED |
| [#451](https://github.com/averylicious/muon/pull/451), codex/saved-read-ahead-oct9, independent sibling now integrated in#458 |15acb730c0c673f166f21614bbf3ff82c95cd21e|[37925246744/.865](https://github.com/averylicious/muon/actions/runs/37925246744)|1084, VERIFIED |
| [#452](https://github.com/averylicious/muon/pull/452), codex/partition-completion-index-oct9 |e52119a8a2453c4bd82eb655dae180560cfebc55|[37926224655/.867](https://github.com/averylicious/muon/actions/runs/37926224655)|1092, VERIFIED |
| [#453](https://github.com/averylicious/muon/pull/453), codex/partition-migration-control-oct9 |1eb353119f11ab21cc65494d80129a68b3ee643c|[37927537834/.869](https://github.com/averylicious/muon/actions/runs/37927537834)|1098, VERIFIED |
| [#454](https://github.com/averylicious/muon/pull/454), codex/published-native-pin-oct9 |6e98e9f421a1c936742fafa83bba109fa6ab7559|[37928043477/.870](https://github.com/averylicious/muon/actions/runs/37928043477)|1099, VERIFIED |
| [#455](https://github.com/averylicious/muon/pull/455), codex/mixed-saved-routing-oct9 |3b2196897e07fba84b08fb0fd27040bc85a206fc|[37928687940/.871](https://github.com/averylicious/muon/actions/runs/37928687940)|1105, VERIFIED |
| [#456](https://github.com/averylicious/muon/pull/456), codex/migration-io-ownership-oct9 |4dfd799c0af5156e78d423d521f2dd0c7b08a254|[37930723498/.874](https://github.com/averylicious/muon/actions/runs/37930723498)|1111, VERIFIED |
| [#457](https://github.com/averylicious/muon/pull/457), codex/saved-storage-barrier-oct9 |4dee9fea16884d4db5de3630105aae08e40e6afd|[37931647688/.876](https://github.com/averylicious/muon/actions/runs/37931647688)|1125, VERIFIED |
| [#458](https://github.com/averylicious/muon/pull/458), codex/partition-storage-session-oct9; cumulative candidate including#451+#457 |bbc2a0bff0a0dfbfe4168593753589f7e2d047e4|[37932197614/.877](https://github.com/averylicious/muon/actions/runs/37932197614)|1138, VERIFIED |
| [#459](https://github.com/averylicious/muon/pull/459), codex/legacy-resource-projection-oct9 |c04d8616ee4acc06a5109e71086b8703f80e7d67|[37934713600/.881](https://github.com/averylicious/muon/actions/runs/37934713600)|1148, VERIFIED; initial compile failure corrected |
| [#460](https://github.com/averylicious/muon/pull/460), codex/readonly-legacy-audio-oct9 |f8df6882c70dfc4617ac4759f9150bc2aa512001|[37935448536/.883](https://github.com/averylicious/muon/actions/runs/37935448536)|1155, VERIFIED; initial compile failure corrected |
| [#461](https://github.com/averylicious/muon/pull/461), codex/migration-projection-budget-oct9 |85a3e330ddac0cac20a69d7229246b90c7b1312a|[37935631801/.884](https://github.com/averylicious/muon/actions/runs/37935631801)|1157, VERIFIED |
| [#463](https://github.com/averylicious/muon/pull/463), codex/migration-uncertain-status-oct9 |2250aba07ed1d002323af69df9a526e6ea34c36e|[37936539404/.887](https://github.com/averylicious/muon/actions/runs/37936539404)|1159, VERIFIED |
| [#464](https://github.com/averylicious/muon/pull/464), codex/move-verification-close-oct9; cumulative candidate |fb37dd454a507f7a862d05dff36f3d93edf0e7aa|[37938165437](https://github.com/averylicious/muon/actions/runs/37938165437)|Final-head receipt pending; diagnostic [37938383591](https://github.com/averylicious/muon/actions/runs/37938383591) requested, not installed/tested |



Source details: [command owner](2026-10-09-partition-command-owner.md), [completed reads](2026-10-09-completed-save-read.md), [bounded index projection](2026-10-09-completed-index-projection.md), [migration controls](2026-10-09-migration-control.md), [cached native UID](2026-10-09-published-native-pin.md), [mixed routing](2026-10-09-mixed-saved-routing.md), [unknown migration file ownership](2026-10-09-migration-io-ownership.md), [shared barrier](2026-10-09-saved-storage-barrier.md), [composed session](2026-10-09-partition-storage-session.md), [read-only legacy projection](2026-10-09-legacy-resource-projection.md), [legacy playback/inventory](2026-10-09-readonly-legacy-audio.md) [projection cancellation budget](2026-10-09-migration-projection-budget.md), [truthful migration uncertainty](2026-10-09-migration-uncertain-status.md) and [production move-reader close](2026-10-09-move-verification-close.md). They describe prepared support, not an enabled app default.

Latest VERIFIED artifact: [app-debug-2250aba07ed1d002323af69df9a526e6ea34c36e](https://github.com/averylicious/muon/actions/runs/37936539404/artifacts/11618004282), Canary0.1.0-canary.887. SHA2563771419b5447549e0a8eef9444a761cbbb7f2ab5e97c4b69b25cee2826028c8c. Actual reports1159tests each/lint0errors65warnings; downloaded APK signer/package/non-debuggable/three-private-service identities verified. PR bodies carry earlier artifact links/hashes. Artifact expires after14days; it updates Canary and is not Obtainium/Stable. Prefer final cumulative candidate only after its own receipt succeeds.

## POCO diagnostic result: #401

[Portable measured receipt](https://github.com/averylicious/muon/issues/401#issuecomment-6080578934), [PR receipt](https://github.com/averylicious/muon/pull/451#issuecomment-6080579324), [source/timing handoff](2026-10-09-saved-read-ahead.md). Diagnostic #451/.868, run37926244336, artifact11613789708, actual1084tests per variant and signed diagnostic identity/checksum verified. SHA256ac8e1d04035cb0c11f63304042d21c2cf53a122785abf4d31b1d2020dc521e44.

Source-correlated Ogg last-page tiny reads repeatedly traversed card/storage/canonical-path checks. Saved-only64KiB read-ahead keeps real checks on each refill and live streams unchanged. First unprofiled baseline selection READY39115ms; first selection after fix881ms; second fixture516ms; warmed first fixture240ms. Later selections are warm, not repeated cold launches. The sampled44726ms baseline includes profiler overhead and is excluded from equal timing comparison. This is a two-copy diagnostic fixture result, not general large-library/card/performance proof.

After stopping the diagnostic app, two disposable audio payload SHA256s and both saved rows (every column) matched pre-test snapshots. No raw DB/trace/song metadata committed or uploaded. Diagnostic stopped; immediately prior media volume restored7. Normal Canary/Stable and user's originals unchanged. No active monitor/sampler/logcat left. Refresh current device/volume/PID before further testing, do not reuse an old process or restore an earlier unrelated volume19.

## Finite remaining Stable gate

| Area | Engineering disposition | Acceptance / next action |
| --- | --- | --- |
| #253 partitioned storage | REQUIRED, NOT complete. Prepared session is composed/tested but OfflineStore still starts legacy SimpleCache shelves. | Wire real app-level owner and all service/command/cover/move/played/removal/shutdown participants; exact pre-manager destructive refusal remains mandatory. Preserve request/record/cover identity. |
| #253 opt-in and restart | REQUIRED engineering | Explicit off-main opt-in/background/progress/cancel controls; bounded truthful recovery at Reserved/Opening/Open/Closed and Copying/Verified/Ready/Uncertain. No partial-target adoption or source deletion authority from Ready. |
| #253 legacy transition | REQUIRED integration; #459/#460 prepare read-only per-key projection and scalar inventory/playback without SimpleCache, #461 covers projection cancellation/deadline | Actually select/integrate the bounded read-only route in production; preserve unsupported resources with explicit feedback. Unknown format must refuse without initialization/repair/truncation. A wrapper does not undo full-index allocation. |
| #401 saved-card startup | Measured source bottleneck and narrow production playback fix on OPEN#451/in#458 | Normal cumulative-build UAT and larger fixture performance remain; controlled two-copy diagnostic result is not release-wide proof. |
| #230 legacy move scope | NEW production verification-close finding on OPEN#464; CI/acceptance pending | sameBytes previously swallowed close errors. Bounded exact readers and actual-file close latch now prevent source-removal authority when close is unknown, including mid-fragment failure. Verify final head and normal device moves; new partition backend still needs its own removal integration. |
| #213 retained identity | Approved policy/fixes on OPEN acceptance stack | Verify live stream remains distinct from unverified saved bytes; saved copy remains separately available. |
| Hardware/library/accessibility | Primarily acceptance, keep relevant app PRs OPEN | Bluetooth/headset/media controls, queue duplicates/Undo, scroll restoration/sheet cancellation, full spoken/focus TalkBack and normal-use acceptance. Prior POCO lockscreen automated checks do not cover every hardware route. |
| Full #179 SD lifecycle | User-deferred, unresolved post-release follow-up | Graceful eject may OS-terminate app; not recovery proof. No emulator/recovery completion claimed. |
| Dependency verification/tool/cache provenance | User-deferred with documented risks for this release | Planned maintenance after main ships and mature M3 integration; existing inventory/protections do not authenticate every dependency/tool. |
| Stable release | Not authorized | Explicit user version/tag/release request after final engineering and acceptance disposition. |

## Ownership and continuing work

Root GPT-6/Codex desktop implements/integrates and self-checks; exact variant/effort not exposed, not independent review. Each branch has its own persistent worktree. Intermediate acceptance-integration branch codex/storage-acceptance-integration-oct9 at54c0655a3de31c46ddf1efe5152ce8e68321656e is clean and incorporated in#458; no separate PR or required unpublished work there. Docs writer uses codex/storage-engineering-checkpoint-oct9; don't reuse another session's checkout.

Allocated audit Claude was verified idle and explicitly resumed with actual claude-opus-5-5, High effort. Dashboard immediately beforehand reported0%five-hour/92%weekly USED; the CLI nevertheless returned session-limit error (reset message1:20am Asia/Manila) before any file edits. Exact limiting window is unverified; do not call the fresh five-hour quota exhausted. Audit session is idle; root continued solo. No other Claude session or user experiment was resumed. Local IDs/logs are optional conveniences, not recovery dependencies.

At meaningful boundaries record actual available Codex quota; only weekly is exposed on this account, five-hour unavailable. Reserve capacity for final CI corrections/report verification and portable evidence; don't stop solely because this checkpoint exists. First inspect/fix pending final heads above. Continue with the real production owner/service integration, opt-in/restart controls and legacy transition, preserving data and application UAT gate. A blocker on one item does not prevent other useful authorized engineering.


Continuation quota snapshot: Codex84%weekly USED (16%remaining), five-hour unavailable. Tool attachment attempts for#459–#464 (including docs#462) returned the app's100-attachment limit; PRs exist on GitHub with durable URLs and were not closed or merged to make UI room. No source/check record depends on those UI attachments. All app PRs stay OPEN. Root continues solo; pending CI gets corrected before any engineering completion claim.


Production move follow-up: #464 is authored in local worktree muon-legacy-reader-retention-oct9 but its branch is codex/move-verification-close-oct9 (renamed before first push; no separate dirty reader-retention branch). Source review withdrew a suspected CacheDataSource reference-loss issue after checking the final cacheReadDataSource field, then confirmed the separate swallowed-mid-fragment-close problem. Do not rederive either from memory alone. New diagnostic run targets the same fb37dd4 head for disposable fixture moves only; normal Canary/Stable data must stay untouched. Refresh device identity/root/media volume and verify the known two-copy fixture before testing. No eject/SELinux/root-manager changes.
