# October 10 ongoing #253 partition integration checkpoint

This is a durable checkpoint during continuing engineering, not an instruction to stop. Follow `docs/continuous-engineering.md`. GPT-6 / Codex desktop owns the isolated main-audit worktrees below; exact variant/effort not reported. Claude is unavailable and has not been resumed. No device commands this continuation. User experiment remains separate and clean at its last observation `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c`; verify live state on takeover. Base main included in app heads: `6cf983183f83e6a2d498aa5744c7896e3802bdd8`.

## Source stack and evidence

All application PRs remain OPEN for the inherited acceptance gate. The main-target heads are cumulative, retaining earlier #449–#465 and independent #451 as integrated in #458; do not merge a successor around pending acceptance merely because its own change is prepared/disabled.

| PR | Exact source head | Latest-head verification | New engineering boundary |
|---|---|---|---|
| #466 | e266349962dca911d12573524556350fadf00172 | Android 37950608035 / Canary .899; 1180 tests per variant, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; downloaded report/APK verified | Actual OfflineStore startup retains bounded exact participants and failed factory slots before native construction; no failed-root retry |
| #467 | f9cb16f862661ec0d787e093149a6b90c71e4c08 | Android 37951454941 / .900; 1187 tests each, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; report/APK verified | Actual read-only legacy DB/UID/directory lifetime and migration through the same gate, without a legacy SimpleCache |
| #468 | 56d2f2b319413bae41c109c2dc539aba9c35f61c | Android 37952824209 / .902; 1190 tests each, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; report/APK verified | Exact persisted completion reconciliation after a missed callback; known pre-forward receipt refusal preserves reservations |
| #469 | 70950ad4ff30122f90a87ae5d775322d21b3a8fc | Android 37953743046 / .903; 1195 tests each, no failures/errors/skips; lint 0 errors, 65 Debug / 64 Release warnings; Branch direction passed; report/APK verified | Actual partition manager/service binding and gated Add/acknowledgement; scalar existing-ID check avoids old payload hydration |
| #470 | 7a3b7d91cff864f829969d5a9fc7b77338affdc5 | Android 37955060466 / .904; 1199 tests each, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; report/APK verified | Complete root/session/manager composition, Fresh/Resume identity, retained startup without legacy cache creation |
| #471 | 32868c288b2b938e46ab9d4b41ff5d289dae5c01 | Android 37957801294 / .907; 1204 tests each, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; report/APK verified | Actual unknown native opening/release retains pool residency and global admission, including no returned Cache/lease |
| #472 | 031b8f43f81c779490d9464d64802631cd1a25fa | Android 37958309502 / .910; 1210 tests each, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; report/APK verified | Actual Shelf and phone/card service binding/delivery; scalar row status; exact unused receipt disposal; private restart foreground protocol preserved |
| #473 | 02dd24ab67a7b57232c59dbd7c5c1ac478cbf97c | Android 37958939025 / .911; 1217 tests each, no failures/errors/skips; lint 0 errors, 65 warnings each; Branch direction passed; report/APK verified | Strict noninitializing journal resume and pre-constructor file identity, bounded schema read, truthful unknown-retirement progress |

| #474 | 0c99943295a926fca4e5fed94fc472780d3e808d | Android 37960595163 / .912; 1221 tests each, no failures/errors/skips; lint 0 errors, 66 warnings each; Branch direction passed; report/APK verified | Actual owned single-slot migration worker, explicit consent, cooperative cancellation and pending-time deadline |
| #475 | e2060d224e1d914c799cd2d0aa979c91d7b2ff0b | Android 37961545017 running; Branch direction passed; inspect final head/checks/reports on takeover | Actual OfflineStore reads exact persistent choice BEFORE provider/native construction; staged partition refuses without legacy fallback |

PR descriptions/comments contain artifact URLs/names, checksums, actual identity verification and test limitations. Branch builds update the same Canary app, are not Obtainium releases, and do not include the separate Expressive experiment. Keep the latest desired track's build/version when testing; do not uninstall to bypass downgrades.

Corrected failures: #468 older 88ed266 run was cancelled after a native-capacity fixture correction. #471 a1cc4de failed one existing diagnostic-message assertion among 1204 Debug tests; repaired 32868c2 preserves original message/cause while retaining typed ownership. #472 aef44c7 failed compile because Media3's RESTART constant is private; pinned DownloadService source was checked and 031b8f4 preserves the verified wire action's implicit foreground obligation. Older intermediate/cancelled runs are not passes. Any new failure still requires repair and final-head evidence.

## What is enabled and what remains

Actual default startup ownership changes are #466 and the staged-choice guard #475; production move-reader/copy fixes inherited from #464/#465 also remain in this candidate. The partition root, journal/session/manager and Shelf/service path are PREPARED and tested with actual native/manager/service controls, but **OfflineStore.create still selects legacy**. No opt-in setting/default switch is enabled. Original full legacy native-index retention therefore remains in default mode. #253 is still a required engineering gate.

Remaining finite integration work:

1. Bind selected partition startup off-main before any legacy SimpleCache construction and expose truthful pending/failed availability without live helper/manager swap. #475 implements the immutable persisted-choice guard; it deliberately refuses staged partition startup until production integration is ready.
2. One app-wide storage gate covering played-copy workers/cache limit/eviction, cover ownership, all save/removal/move commands and actual reader/worker shutdown.
3. Safe partition removal and move/copy policy before manager/index mutations; originals and unsupported/unknown ownership must stay intact. Prepared partition service currently refuses unsupported mutations rather than claiming equivalence.
4. User opt-in/progress/cancel orchestration (owned single-slot worker #474 is prepared, not yet exposed) and interrupted startup/migration recovery. Strict journal resume and Ready routing are prepared; restart grants no adoption, automatic replay or source cleanup.
5. Final compile/controlled integration verification and renewed acceptance for behavior actually enabled. #401's remaining saved-card delay/performance evidence is separate; no general performance claim from these JVM controls.

Accepted deferrals remain: full #179 SD recovery is not this Stable gate; broader dependency verification/runner/SDK/cache provenance is post-release maintenance with documented risks. Hardware Bluetooth/headset and full TalkBack spoken/focus UAT remain user acceptance. No Stable tag/release is authorized by this checkpoint.

## Recovery / ownership

Owned worktrees: `/home/avery/.codex/worktrees/muon-storage-startup-owner-oct9`, `muon-readonly-legacy-owner-oct9`, `muon-partition-completion-reconcile-oct9`, `muon-partition-manager-owner-oct9`, `muon-owned-partition-shelf-oct9`, `muon-partition-opening-quarantine-oct10`, `muon-partition-shelf-service-oct10`, `muon-partition-resume-journals-oct10`, `muon-partition-migration-worker-oct10`, `muon-storage-backend-selection-oct10`. Source changes are committed/pushed at the heads above. Current source implementation is active; no Claude or phone process was dispatched. A successor must verify the current coordinator has yielded before overlapping writes and verify exact live heads/status rather than treating this running snapshot as a final handoff.

Read the linked per-boundary documents for native/actual-service controls and remaining contracts. Preserve unknown file/native owners and their shared/exclusive admission; no production reset or cleanup authority comes from fixture-only reflection teardown. Codex PR attachment UI reached its 100-item limit; portable repository/PR links remain available. Do not delete unrelated attachments/PRs to work around it.
