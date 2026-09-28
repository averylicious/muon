# Parallel tracks and landscape fix — 2026-09-28

## Recovered boundary

- Main audit #181 continues; [the prior checkpoint](2026-09-28-main-audit.md) and [SD-cache report](../audits/2026-09-28-storage.md) remain the audit baseline. #179 is not fixed or device-reproduced.
- #183's landscape background fix was independently source-reviewed by GPT-6 Astra (Codex; effort not reported), including row/scaffold/panel layering. Reviewed head `bf0c796303515b63c800b4afa97d4fff89ebff6a`; [run 303](https://github.com/averylicious/muon/actions/runs/36347730475) passed both builds/tests/lint and APK checks. Merged as `2211d275523856949efdf201610e51d4553ffe56` under standing authorization. [Review record](https://github.com/averylicious/muon/pull/183#issuecomment-5862771138).
- #183 was authored by Claude Opus 5.5. Its identical experimental change had Claude-reported dark-theme Pixel QA; main-build light/Pure-black checks remain pending. No new phone access occurred. [Main run 304](https://github.com/averylicious/muon/actions/runs/36373841578) was in progress when this checkpoint was first written; verify its final result/release.

## Current safeguards slice

- Branch `codex/parallel-track-safeguards`, based on the #183 merge, implements [parallel tracks](../parallel-tracks.md), updated agent entry points and a read-only Branch direction PR check with real Git-graph regression tests. No app, dependency, signing or release-feed change is part of this slice.
- Intended main protection: strict/up-to-date required `Build, test and sign` and `Branch direction` checks, PRs with zero required approving reviewers (preserving autonomous self-reviewed merges), no force-push/deletion. It was **not enabled at this checkpoint's initial write**. Verify the PR/check comments and live GitHub settings before claiming enforcement; do not silently bypass failures.
- Forward-sync work must be a new isolated branch/PR targeting the experiment. The experimental owner lands it at a clean boundary; do not merge it underneath their active session. An initial sync remains to be prepared after this safeguards PR lands. Carry main fixes and the protocol forward while retaining the experiment's libraries, motion and blur.
- Starting experiment head: `cd4bdfc6e9a4bd98608147004c715e0caf4e7c10`. Refresh it before integration. The original checkout is user-owned and was not edited or switched by Astra.

## Ownership and resume

Astra owns the main audit/safeguards worktree. The user and their independent Claude session own the experiment. The user has now explicitly allocated a different Claude session for audit help; its local ID is optional and not required for handoff. Verify idle status before using it; never guess or resume the user-led session. No continuous handshake is required between tracks.

Astra account usage telemetry is unavailable in these tools. Historical percentages are not live readings. The user reported the last window cutoff; the unfinished files survived in the persistent audit worktree. Reserve enough capacity for commits, exact-head checks and a checkpoint; leave pending work open with evidence rather than forcing a merge.

Before the next slice, inspect this branch/PR, live heads, checks, protection and #181. Finish the safeguards and record integration evidence, then return to #179's data-preserving lifecycle investigation. Manual phone QA remains user-owned. Main Canary and experimental artifacts update the same installed Canary; keep experimental builds for experimental UI testing. No Stable release is authorized.
