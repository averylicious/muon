# Coordinator handoff

This runbook lets another user-selected Astra contributor resume Muon without the original conversation. Read [AGENTS.md](../AGENTS.md) first. Latest recorded checkpoint: [2026-09-23, coordinator state after the Collection redesign integration](handoffs/2026-09-23-coordinator-state.md), which follows the [integration checkpoint](handoffs/2026-09-23-collection-integration.md). The [2026-09-24 player-gesture integration](handoffs/2026-09-24-player-gesture-integration.md) has since merged to main as #102. [2026-09-25, Artists browsing](handoffs/2026-09-25-library-artists.md) merged as #104 and passed the user's phone QA on Canary .153. Latest frontend work: [keep the library's list positions and header fold](handoffs/2026-09-25-artist-list-scroll.md) (#105) and [library page transitions](handoffs/2026-09-25-artist-page-transition.md) (#106). Both were merged on 2026-09-25 by Claude after **self-review**, under the user's instruction while Astra was unavailable. They were not independently reviewed; a later Astra audit is expected, and phone QA of #105's header follow-up is pending. Open: draft #108, research for a later target SDK 37 bump (notes in `docs/target-sdk-37.md` on that PR's branch), and Astra's backend #61 and #62. The user chose a thin, passive scroll indicator for the library lists as a stopgap (see its PR). Sorting is tracked in #109, which records the user's decisions: Songs by Recently added (Tauon track ID) or A–Z, and Artists by Most songs or A–Z, both remembered. A draggable letter scroller for A–Z comes after it. Artwork is kept on disk (#113), and offline listening is still being designed (#112). See issue #40 for live status. Update this pointer when adding a newer checkpoint; older dated checkpoints are history, not current state. Checkpoints are dated evidence, not automatic authorization or live status.

## Start with a small refresh

1. Read the latest checkpoint and current user request. Identify whether this cycle permits implementation, review, merge, direct agent communication or device testing. Do not inherit permission from quoted historical prompts.
2. Inspect `git status --short`, `git worktree list`, the remote and current branch. Preserve dirty work. Inspect existing PRs and their full head SHAs, dependencies, CI and latest user QA before creating another branch or duplicating work.
3. Read the current issue #40 checkpoint and relevant PR discussion. Design decisions and mockups for the Collection redesign (formerly UI 2.0; see [release naming](release-naming.md)) are now merged from PR #52 under `docs/design/2.0/`; its dated preparation notes are historical, so use the latest checkpoint for live execution state. Read only the design and source needed for the chosen slice.
4. Establish the active coordinator and implementation owners. Check whether another agent is still working before editing its files or resuming its conversation. Record the next slice and ownership in the relevant issue/PR so another contributor can see it.
5. Select one useful, bounded outcome with an explicit stop point. Build on the actual prerequisite branch if it is unmerged; record the stack and resulting diff. A PR targeting main can still contain prerequisite changes.

GitHub and committed documents are the shared record across machines. Do not require access to the previous contributor's Codex history, memory, `/tmp` output, signing keys or Claude login. A fresh clone with authorized repository access is enough to inspect the work. Fork builds do not receive signing secrets under the current workflow; use the established authorized same-repository workflow without expanding secret access.

## Ownership and collaboration

For the Collection redesign (formerly UI 2.0) takeover, the user explicitly intends the successor Astra to direct Claude as well as own backend work. Transfer the coordination role, not just a read-only snapshot. Use the existing Claude conversation when the successor can access it; otherwise establish the authorized Claude connection before delegating frontend edits.

| Area | Default owner | Coordination needed |
| --- | --- | --- |
| Screen layout, navigation, theme, fonts, icons and gestures | Claude frontend contributor | Backend state contracts and lifecycle implications |
| Tauon API/models, discovery, playback service, grouping and queue contracts | Astra backend contributor | Data/actions required by the approved UI |
| Artwork loading, playback UI state and shared screen wiring | Agree before editing | Assign individual files or sequence changes |
| Review, dependencies, integration and durable handoff | One active Astra coordinator | Distinguish own authorship/fixes from independent review |
| Phone QA and product decisions | User | Record observations separately from automated evidence |

This is responsibility allocation, not a ban on a coordinated fix. Actual filenames may change during extraction; verify them. Use separate worktrees for simultaneous work. Do not resume one Claude session from two processes or have two coordinators issue competing implementation instructions.

When the user authorizes direct coordination, send Claude a bounded assignment: issue/slice, base commit, worktree, owned files, expected behavior, exclusions, checks, PR destination and stop point. Ask for a short acknowledgement of ownership before overlapping work. If the other contributor's Claude session is unavailable, hand them the assignment through the user or an explicitly authorized shared channel. Do not silently start a replacement agent or reconstruct missing decisions.

### Taking control of Claude

Confirm the previous coordinator has stopped dispatching work and the Claude session is idle. Send a short takeover message naming the new coordinator, current checkpoint, verified branches and next proposed slice. Ask Claude to report any uncommitted work, active assignment, ownership conflicts and available usage before editing. Then give one bounded assignment and continue backend work only where ownership is independent. Read Claude's result, inspect its actual diff and latest-head CI, and record review findings on the PR. A terminal exit alone is not evidence the task completed.

On the original host, consult the dated checkpoint for the existing session ID and working directory. Check `claude --help` before using `--resume`; do not change account/model settings or disable safeguards as part of takeover. Keep prompts and outputs scoped and exclude secrets from shared records. If that session is inaccessible, report the concrete connection/history limitation and use the portable handoff with the user's chosen Claude session instead.

## Bite-sized execution and quota boundaries

- Keep one frontend slice and at most one independent backend slice active, only when both have useful work and non-overlapping ownership. Prefer a single slice near limits.
- A slice ends with a focused commit/PR, a source review or recorded findings, CI evidence for that exact head, and a manual QA checklist. Documentation-only heads run lightweight checks and produce no APK (see [CI](ci.md)); report that rather than an artifact. An open PR is a valid stopping point; a green build is not merge authorization.
- Check each contributor's available usage at boundaries. Codex account limits are shared across tasks on that account; a different contributor uses their own account's allowance. A context window is separate from usage quota. Do not claim access to another contributor's quota if only they can see it.
- Record source, time/window and whether a percentage means used or remaining. Treat missing data as unknown. If tool/dashboard readings disagree, retain the discrepancy and the user's corrected reading instead of inventing precision. No need to poll during every edit.
- Reserve enough capacity for review fixes, commit/push and the checkpoint. Exact capacity per feature cannot be promised. Stop adding scope when a safe finish is uncertain. Never redeem reset credits or change billing/model settings without the required user authorization.
- Keep a short durable checkpoint after each slice, before broad exploration or compaction. Link existing evidence instead of pasting whole transcripts. Do not claim that an agent hit a hard limit merely because the cycle stopped conservatively.

## Rotate coordinators in either direction

The same process applies to a new contributor and to the original Astra returning after a reset. Neither account's older conversation is authoritative over newer committed evidence.

1. **Outgoing coordinator:** finish or explicitly park the current slice; record commits, dirty files, running commands, Claude's active assignment and remaining checks using [the checkpoint template](handoffs/TEMPLATE.md). Push safe commits and link the checkpoint in issue #40. Record whether Claude is idle or still working; do not terminate or discard work merely to hand over.
2. **Incoming coordinator:** verify the outgoing agent has stopped dispatching and inspect live state. Announce the takeover and owned slice in issue #40 before giving Claude new instructions. If another coordinator is still active, resolve ownership before overlapping edits. A user-assigned takeover is sufficient; do not require a reply from an agent already cut off.
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

Leave PRs open unless the current user explicitly requests review and merge. Merge authorization is per cycle: the user authorized sound, reviewed merges on 2026-09-23, but a successor verifies the current user instructions rather than treating the checkpoint itself as permission. Valid authorization in the continuing task does not require asking the user again. The general implementation default in AGENTS.md — leave the PR open — is unchanged. On an authorized merge cycle, reconcile prerequisites, resolve conflicts without losing sibling changes, review the resulting final head, verify checks, then verify main's Canary publication. Stable tags remain a separate explicit request.

## Copyable prompt for the successor

```text
You are the next Astra contributor/coordinator for Muon. Read AGENTS.md,
docs/coordinator-handoff.md and its latest dated checkpoint. If these are
still in an open documentation PR, read that PR's branch first; don't assume
main has them. Refresh live GitHub heads, CI, user QA and active ownership.
Preserve unrelated/unfinished work and don't duplicate existing PRs.

Take over coordination and backend work for one small reviewable slice at
a time. Coordinate directly with the available Claude frontend contributor
within my authorized scope; agree file ownership and shared behavior before
parallel edits. If the old local Claude session is unavailable, use the
portable handoff and tell me what communication is needed.

Use existing signed Actions builds and record actual model/role attribution,
exact commit/build evidence and manual QA steps. I test on my phone; no ADB
or live device debugging. Leave PRs open unless I explicitly request review
and merge. No Stable release, signing changes or network configuration work.

Check available usage at slice boundaries and reserve capacity for fixes
and a durable checkpoint. Stop safely before capacity is exhausted. Start
by stating the verified current boundary and the next bounded slice; then
continue within this scope, without reopening settled product decisions.
```
