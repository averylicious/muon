# Muon current state

Updated 2026-11-28. Coordinator and resume entry point. GitHub is authoritative for heads, checks and merge state; verify live before acting. [Issue state](audits/issues/README.md) · [Release plan](release-plan.md) · [Parallel tracks](parallel-tracks.md).

## Where things are

- **Stable [v0.3.0](https://github.com/averylicious/muon/releases/tag/v0.3.0)** is Latest: tag `89b933a7` (#801), Gen 3 M3 Expressive with stable Material 3 `1.5.0`. Its release-please changelog and `0.3.0` version files landed in #779.
- **`main`** is at `9ff9ae2c` (#779). The latest published Canary is [.1774](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.1774), from `9e9c58d7` (#803).
- **`gen-4-progressive-blur`** (Gen 4 - Progressive Blur, owned by the user and their Claude session) was cut from `v0.3.0` and moved to Compose BOM `2026.11.01` / Compose `1.13.0` and Android SDK `37.2` (#802). Plan: #796; Lyrics in Focus: #797; open PR #809.
- `claude/m3-expressive-alpha` is retired and deleted; its frozen-RC policy (#679) and post-RC staging draft (#701) are closed.
- **Branch protection:** on 2026-11-28 the API reported no protection or rulesets on `main`. Until it is restored, the [preflight and merge gates](parallel-tracks.md#preflight-and-merge-gates) are contributor-enforced.

## Open work

- **#646** Saved-copy Delete refusal after reset: stays open and unresolved by user decision; current-copy deletion passing is not a fix.
- **#401** Saved SD-copy playback start and Move latency: deferred.
- **#671** Intermittent stream buffering (POCO): not recurred; open, non-blocking.
- **#83** / draft PR **#683** Baseline Profile benefit measurement.
- **#796**, **#797** Gen 4 work on the experiment track.
- **#806** Docs streamlining (this cycle).
- Pending device checks and build prerequisites: [phone QA queue](handoffs/phone-qa-queue.md).

## Standing permissions

- **Blocker-free self-merge, 2026-09-27.** The user's words: "You are authorized to merge PRs without any blockers without asking for permission in future sessions". Merge at the CI-verified head with the expected-head SHA, after recording a self-review on the PR.
- **Default kept, 2026-11-28.** Agents keep self-merging CI-verified, blocker-free PRs **unless the user explicitly asks to leave a PR open**. This retires the 2026-09-30 rule that held every phone-QA PR open.
- **Parallel tracks, 2026-09-28.** Main and the experiment proceed independently with repository safeguards; forward-sync PRs into `gen-4-progressive-blur` land under the same rule with a regular merge commit.
- **Always per request:** Stable releases, `vMAJOR.MINOR.PATCH` tags, auto-merge, anything with a blocker, and device access (ADB, scrcpy, device settings) need the user's go-ahead in the current conversation. Main and experimental Canary share one package; authorized agent device tests use the Diagnostic app so the user's everyday Canary survives.

## Resume order

1. Read this page, [AGENTS.md](../AGENTS.md), [parallel tracks](parallel-tracks.md) and only the relevant [issue-state file](audits/issues/README.md).
2. Verify live `main`, `gen-4-progressive-blur` and PR heads, required checks, worktree ownership and the user's current authorization. Preserve dirty work.
3. One writer per branch/worktree/session; never resume the user's Claude session. Record the actual model and effort you run with.
4. Keep exact-head CI, automated device results and user acceptance distinct.
5. Before yielding, edit the relevant issue file in place and update this page with active/idle ownership, exact commits, outstanding checks, unanswered questions and the next slice. Use [the handoff template](handoffs/TEMPLATE.md) for a longer checkpoint.

## History

Earlier state is evidence, not current permission: [status archive](handoffs/2026-10-21-status-archive/STATE.md), [coordinator chronology](handoffs/2026-10-21-status-archive/coordinator-handoff.md), [v0.2.0 release](handoffs/2026-10-23-stable-020-release.md), [frozen M3E RC](handoffs/2026-10-24-frozen-m3e-rc.md), [M3E RC2](handoffs/2026-11-10-m3e-rc2.md). Do not replay old gates that were later accepted, completed or deferred.
