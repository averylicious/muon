# Coordinator handoff

Latest main-audit checkpoint: [Generation ownership and QA refresh](handoffs/2026-10-04-generation-owners.md). Test-only controls #309–#311 are merged, and main .540 publication is verified. #302 is reopened and refreshed with #312; fresh head/checks/build receipts belong in the checkpoint/PR. Every app candidate remains open for user acceptance. The [generation owner map](audits/2026-10-04-generation-ownership-map.md) names remaining production #179 contracts; it is not a working recovery gate. No device work or experimental changes.

This runbook lets another user-selected Astra contributor resume Muon without the original conversation. Read [AGENTS.md](../AGENTS.md) first, then **[STATE.md](STATE.md)**. The earlier controller-policy checkpoint is [2026-09-30, network and entry points](handoffs/2026-09-30-controller-policy.md), with the [repository map and continuation plan](audits/README.md). Earlier handoffs and audit baselines are historical evidence. Refresh PR heads, checks and the latest shared issue comments before assuming any checkpoint is current.

Since 2026-09-27, the user authorizes Astra's main-branch audit alongside Claude/user experimental work off main. Both agents may independently merge blocker-free PRs at their CI-verified heads under STATE.md's standing authorization. The audit can follow findings across the repository. Follow [parallel tracks](parallel-tracks.md): one writer per branch/worktree/session, independent changes on each track, and separate forward-sync PRs. The historical frontend/backend split does not restrict the audit.

## Start with a small refresh

1. Read the latest checkpoint and current user request. Identify whether this cycle permits implementation, review, merge, direct agent communication or device testing. Do not inherit permission from quoted historical prompts.
2. Inspect `git status --short`, `git worktree list`, the remote and current branch. Preserve dirty work. Inspect existing PRs and their full head SHAs, dependencies, CI and latest user QA before creating another branch or duplicating work.
3. Read the current issue #40 checkpoint and relevant PR discussion. Design decisions and mockups for the Collection redesign (formerly UI 2.0; see [release naming](release-naming.md)) are now merged from PR #52 under `docs/design/2.0/`; its dated preparation notes are historical, so use the latest checkpoint for live execution state. Read only the design and source needed for the chosen slice.
4. Establish the active coordinator and implementation owners. Check whether another agent is still working before editing its files or resuming its conversation. Record the next slice and ownership in the relevant issue/PR so another contributor can see it.
5. Select one useful, bounded outcome with an explicit stop point. Build on the actual prerequisite branch if it is unmerged; record the stack and resulting diff. A PR targeting main can still contain prerequisite changes.

GitHub and committed documents are the shared record across machines. Do not require access to the previous contributor's Codex history, memory, `/tmp` output, signing keys or Claude login. A fresh clone with authorized repository access is enough to inspect the work. Fork builds do not receive signing secrets under the current workflow; use the established authorized same-repository workflow without expanding secret access.

## Ownership and collaboration

[Parallel tracks](parallel-tracks.md) is the current operating protocol. Astra owns the main audit; the user and their Claude session own the experiment. Each can fix any relevant component on their assigned track. Different tracks may touch the same file without a live handshake, but never share a checkout or rewrite the other's branch.

The experimental owner lands main-to-experiment sync PRs at a clean boundary. Astra can prepare those PRs and record required integration on #181 without resuming the user's Claude conversation. Generally useful experimental fixes are ported to a fresh main-based branch and reviewed against main's dependencies; the experimental branch itself never merges to main.

### An explicitly allocated audit Claude session

The user may provide a distinct Claude session for Astra's audit work. Before resuming it, check that exact session is idle, its checkout and assignment are independent, and no other coordinator is driving it. Do not use `--continue` or a historical ID to guess which session the user meant. Do not reset account/model settings or disable permission safeguards.

Give one bounded assignment with the issue, base/head, isolated worktree, allowed changes or read-only scope, validation and stop point. Review its actual output and diff; an exit code alone is not completion evidence. If session ownership is unclear or the session is active, continue independent audit work and record the blocker rather than interrupting it. Local IDs and logs remain optional recovery hints, not a portable dependency.

## Bite-sized execution and quota boundaries

- Keep each active audit assignment bounded, with one writer per branch/worktree/session. Prefer a single slice near limits. The user-led experiment proceeds independently; it does not wait for audit quota to reset.
- A slice ends with a focused commit/PR, a source review or recorded findings, CI evidence for that exact head, and a manual QA checklist. Documentation-only heads run lightweight checks and produce no APK (see [CI](ci.md)); report that rather than an artifact. An open PR is a valid stopping point; a green build is not merge authorization.
- Check each contributor's available usage at boundaries. Codex account limits are shared across tasks on that account; a different contributor uses their own account's allowance. A context window is separate from usage quota. Do not claim access to another contributor's quota if only they can see it.
- Record source, time/window and whether a percentage means used or remaining. Treat missing data as unknown. If tool/dashboard readings disagree, retain the discrepancy and the user's corrected reading instead of inventing precision. No need to poll during every edit.
- Reserve enough capacity for review fixes, commit/push and the checkpoint. Exact capacity per feature cannot be promised. Stop adding scope when a safe finish is uncertain. Never redeem reset credits or change billing/model settings without the required user authorization.
- Keep a short durable checkpoint after each slice, before broad exploration or compaction. Link existing evidence instead of pasting whole transcripts. Do not claim that an agent hit a hard limit merely because the cycle stopped conservatively.

## Rotate coordinators in either direction

The same process applies to a new contributor and to the original Astra returning after a reset. Neither account's older conversation is authoritative over newer committed evidence.

1. **Outgoing coordinator:** finish or explicitly park the current slice; record commits, dirty files, running commands, Claude's active assignment and remaining checks using [the checkpoint template](handoffs/TEMPLATE.md). Push safe commits and link the checkpoint in issue #40. Record whether Claude is idle or still working; do not terminate or discard work merely to hand over.
2. **Incoming coordinator:** verify the outgoing agent has stopped dispatching and inspect live state. Record the takeover and owned audit slice on #181 (and #40 for a batch checkpoint) before assigning audit work. If another coordinator is still active, resolve ownership before overlapping edits. A user-assigned takeover is sufficient; do not require a reply from an agent already cut off.
3. **At every completed slice:** update the checkpoint and PR evidence. At a cutoff, mark the outcome unknown until inspected. Keep one current pointer here; retain dated checkpoints as history. A checkpoint may be updated within its documentation PR; record its revision commit in the shared issue rather than embedding a self-referential SHA.
4. **On return:** repeat the incoming procedure even if you created the original plan. Inspect intervening diffs, user decisions and Claude's current assignment. Do not restart a completed slice, restore an old branch, or silently override the new contributor's work.

No account credentials, raw session transcripts or signing material belong in the handoff. Local session IDs are optional recovery hints; the receiving contributor supplies their own authorized tooling. Record each model's actual contribution on its PR, including a returning coordinator's fixes or review.

## Recover after interruption

Read the latest checkpoint, then inspect the actual branch, dirty diff, processes and PR before doing anything destructive. Never reset/discard an interrupted agent's work to obtain a clean checkout. If it was not committed, identify the changed files and owner, preserve the diff, and finish or isolate only the understood slice. If a command may have succeeded before interruption, verify GitHub before retrying a push, PR or comment.

Recheck CI on the current full SHA; old green results do not validate a changed head. A failed or unfinished run remains failed/pending in the handoff. Record a remaining blocker and leave a draft/open PR as appropriate rather than declaring completion. Verify tool result error flags, not just process exit or a nominal success label. Keep secrets and raw authentication/thinking output out of logs and repository documents.

No phone access is implied. User QA can confirm overall experience without proving every listed edge case. A newer workflow run may have a higher Android version code while containing older app code: inspect branch contents before recommending installation. Never uninstall or change signing to bypass a downgrade.

## Checkpoint contents

Use a dated file in `docs/handoffs/` and link it from here and issue #40. Keep it concise:

- Current requested scope, implemented work, proposals and deferred work, clearly separated.
- Coordinator/agent ownership; active work or explicit idle state; dirty work and recovery steps if any.
- PR URLs, full SHAs, prerequisite relationships and relevant review comments.
- Latest-head CI/run, and for full builds the artifact/version; which feature branches the recommended APK includes and excludes.
- User QA as reported, remaining device checks, source findings and unmeasured performance claims.
- Next one or two slices, blockers and shared behavior decisions still needed.
- Usage snapshots only if useful, with source/window and no assumption they remain current.

Use the current user's instructions and STATE.md's standing merge authorization. Since 2026-09-27, either agent may merge a blocker-free PR at the CI-verified head and record its self-review; distinguish independent review when another agent actually performed it. Reconcile prerequisites, resolve conflicts without losing sibling changes, and verify main's Canary publication. Leave unresolved or unverified work open with a clear checkpoint. Stable releases/tags remain a separate explicit request.

## Copyable prompt for the successor

```text
You are the next Astra contributor/coordinator for Muon. Read AGENTS.md,
docs/coordinator-handoff.md and its latest dated checkpoint. If these are
still in an open documentation PR, read that PR's branch first; don't assume
main has them. Refresh live GitHub heads, CI, user QA and active ownership.
Preserve unrelated/unfinished work and don't duplicate existing PRs.

Continue the main audit using docs/audits/README.md and its latest report.
Follow findings across components as needed and leave reviewable checkpoints.
Read docs/parallel-tracks.md. Preserve the separate user-led experimental
checkout and session; do not resume that Claude session. A distinct audit
Claude session may be used only when explicitly allocated and idle. Bring
main fixes forward through a separate sync PR for the experimental owner
to land. Use PR/issue evidence instead of routine live coordination.

Use existing signed Actions builds and record actual model/role attribution,
exact commit/build evidence and manual QA steps. I test on my phone; no ADB
or live device debugging. Apply STATE.md's standing authorization for
blocker-free, CI-verified merges and accurately record self/independent review.
No Stable release, signing changes or network configuration work.

Check available usage at slice boundaries and reserve capacity for fixes
and a durable checkpoint. Stop safely before capacity is exhausted. Start
by stating the verified current boundary and the next bounded slice; then
continue within this scope, without reopening settled product decisions.
```
