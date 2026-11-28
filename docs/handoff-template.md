# Muon coordinator checkpoint — YYYY-MM-DD

Replace placeholders; unknown is preferable to a guess. Link this file from the coordinator runbook and issue #40.

## Scope and ownership
- Current user-authorized work and explicit exclusions:
- Outgoing coordinator/model/client; incoming coordinator if assigned:
- Claude model/client, active assignment or verified idle, owned files/worktree:
- Other active workers/commands; dirty files and recovery instructions:
- No further dispatch by outgoing coordinator after: <time/event>

## Verified state
- Inspection time, remote, main SHA, inspected branches/worktrees:
- PR links and full head SHAs; prerequisite relationships, sibling integrations:
- Which records are historical or superseded:
- Actual model/role attribution and independent review status:

## Validation and user QA
- Latest-head CI links/results; pending/failed/unrun checks:
- Recommended test build: commit, run number, Canary version, artifact name/link:
- Included and excluded feature branches; artifact expiry/downgrade caveats:
- User's reported QA, separately from remaining checks and unmeasured claims:

## Next bounded slice
- Issue, outcome, base SHA, owner/files, dependencies:
- Acceptance checks, excluded scope, stop point:
- Decisions/blockers needing coordination:
- Usage source/time, window, used versus remaining; unknown values:

## Resume
- First read-only verification steps:
- Claude connection hint if useful (no credentials; optional across machines):
- If interrupted, which commands may have succeeded and must be checked before retry:

Apply the current user instructions and STATE.md standing merge authorization. Keep QA-blocked and forward-sync PRs open for their owners/gates; do not infer device access or Stable-release permission from a checkpoint.
