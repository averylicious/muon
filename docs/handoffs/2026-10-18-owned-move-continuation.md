# October 18 resumed owned-Move engineering

## Ownership and scope

Solo GPT-6 / Codex desktop; exact variant and effort not reported. No Claude or subagents. The user refreshed limits and renewed rooted POCO testing this cycle. Prior completed proofs are in [startup verification](2026-10-18-startup-verification.md); they do not establish runtime acceptance for the new source below.

Main was refreshed at `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`. The original experiment checkout remains clean at `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c`; remote experiment is independently owned and untouched. Application PRs stay open for cumulative acceptance. No release/tag/Stable promotion, activation or experiment integration.

## Current source

[#609](https://github.com/averylicious/muon/pull/609), `ad617a15146f2c449499b75f10838576d408456d`, branch `codex/owned-move-read-checkpoint-oct18`, persistent worktree `~/.codex/worktrees/muon-owned-move-read-checkpoint-oct18`. Based on verified #608 `439c9cdab163636ced9d0fe0396f6a0e07c57c60`.

The preliminary session availability check duplicated the full destination journal sweep that every concrete owned-shelf Move checkpoint already performs before physical I/O. Only this preliminary owned-Move observation now checks fresh root/volume/session state; the enclosing checkpoint still performs the full sweep. Generic sessions default to full availability. State/actual exclusive lease are rechecked after caller cancellation. Native/open/close/non-Move checks and full binding/target-before-every-source-file-removal stay unchanged. No journal result is cached.

Production caller audit and invariants: `docs/audits/2026-10-18-owned-move-checkpoint.md` on the source branch. Three focused regressions cover actual target-journal replacement and aliased parent immediately after known native closure, and unchanged generic-session availability refusal; existing mutation/cancellation/close/restart tests remain enabled. Necessary distinct tests are user-approved; consolidation follows the audit, no CI coverage reduction.

## Verification

Local source/caller review, `git diff --check` and branch preflight passed. No local Android compile/tests. [Android run 39121004089](https://github.com/averylicious/muon/actions/runs/39121004089) is the first compile/test and is pending. Branch direction passed. No current-head APK/report/device pass claimed yet. Verify current head and actual downloaded reports/APK before installation. Never dispatch another build of this branch while the ordinary run is active: workflow concurrency cancels it.

## Phone safety and next check

USB POCO `3b34b8e6` was reidentified as surya, Android 17/API 37, root UID 0. Both Muon apps are stopped, music volume 11 separately read back; no old driver active. No new install/mutation this cycle yet. Prior verified ordinary .1235 / diagnostic .1238 remain installed. The complete disposable diagnostic copy and older partial are on CARD. Original music and parked pre-ROM inventories are preserved.

After current-head ordinary CI succeeds, request a separate diagnostic build explicitly. Verify reports and actual package/certificate/debuggable/export/BUILD/hash before installing. Capture stopped diagnostic baseline, perform one new UI Card-to-Phone Move with disposable data, monitor fixed bounded counters, then compare all request fields/full audio/metadata/new allocation/key/cover/source retirement and unchanged old journals/partial/protected originals. Stop/snapshot only after known idle; restore original music volume and read it back even on host failure. One phone driver at a time; do not interrupt active storage work.

#401 startup/Move latency and large-library native/managed memory/performance characterization remain engineering. Device/hardware/spoken/subjective UAT is separate. No meaningful speedup or Stable readiness follows from source changes or green CI alone.

## Verification corrections and completed memory boundary

Initial run 39120112852 was superseded/cancelled. Run 39120604782 compiled and ran 1,742 Debug tests with one fixture failure: a nonexistent journal filename. The current head fixes that name to `new-save-journal-v1.db`; run 39121004089 remains pending. Production code is unchanged by those test corrections. Generic defaults/full enclosing checks remain. No current-head APK installed.

[Library memory/refusal report](../audits/2026-10-18-library-memory-characterization.md): ordinary #608 .1235 completed controlled synthetic 1,000/10,000/25,000/50,000-song loads and explicit 50,001-entry refusal preserving 50,000. Highest sampled final total PSS about 298 MiB; not a whole-process or instantaneous peak guarantee. Short metadata/no artwork/no playback only; remaining memory dimensions are explicit. Immediate full saved-record/native/audio/cover and original connection-file comparisons passed; both parked-original inventories match. Own phone-loopback listener/files and temporary preference backup removed; both apps stopped and media 11 restored/read back. Diagnostic full copy/old partial still CARD. No active driver.
