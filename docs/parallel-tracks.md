# Independent audit and experiment tracks

The user authorized these tracks on 2026-09-28. They replace routine live frontend/backend coordination for the current main audit. Read this with [AGENTS.md](../AGENTS.md), [STATE.md](STATE.md) and the [latest checkpoint](coordinator-handoff.md). Earlier handoffs describe history, not current ownership.

## Work without sharing sessions or checkouts

| Track | Destination | Default owner | Purpose |
| --- | --- | --- | --- |
| Main audit | `main` | Astra | Correctness, security, privacy, reliability and CI fixes in any component |
| Expressive experiment | `claude/m3-expressive-alpha` | User and their Claude session | Experimental libraries and design; branch APKs only |
| Forward integration | Experimental branch, through a `sync/main-into-expressive-<topic>` PR | One named integrator; experimental owner lands it | Bring reviewed main fixes into the experiment |

- Each agent uses its own branch **and persistent worktree**. Inspect `git status --short` and `git worktree list` first. Do not switch, clean, reset, stash, remove or reuse another session's checkout. A clean checkout is not evidence its owner is idle. The original Muon checkout currently belongs to the experiment.
- One writer per branch/worktree/session. Different tracks may change the same file independently; integration is where those changes are reconciled. This permits work without asking the other session before each edit.
- Astra must not resume the user's experimental Claude session. The user may assign a **different audit Claude session**; verify that exact session is idle and assign it a separate worktree before use. A session ID from old logs is not authorization to take it over. Without an allocated idle session, continue independently and leave PR/issue evidence.
- Within a track, claim the issue/slice and branch in its PR or issue before overlapping work. Check existing PRs before starting another. A successor verifies the previous writer has stopped; a user-directed takeover after a cutoff is sufficient, but dirty work must be preserved.
- Record findings and cross-track dependencies on [#181](https://github.com/averylicious/muon/issues/181) or the relevant PR. Contact the other agent only for an unresolved behavioral decision or ownership collision, not for ordinary independent fixes.

## Moving fixes between tracks

**Main to experiment:** after a useful main fix lands, the experimental owner or a named integrator makes a separate integration branch from the current experimental head. Merge current main into that branch, resolve conflicts, review the combined diff against the experiment and run CI there. Open a PR into `claude/m3-expressive-alpha`. Do not open a direct main-to-experiment PR: the tested main APK does not test the combined experimental app.

Astra may prepare that PR autonomously. The experimental owner lands it at their next clean boundary and updates their checkout, so a background audit process never moves the branch underneath their editing session. Leave it open for them unless the user explicitly delegates that integration. Use merge commits for these syncs, retaining main's ancestry; do not squash, rebase or force-push shared track history.

**Experiment to main:** do not merge the experiment wholesale, even if Git reports no conflicts. For a generally useful fix, make a fresh branch from current main and port only that focused change and its tests. Cite the experimental source commit/PR and check it against main's pinned dependencies. #183 is an example of this pattern. Cherry-picking a reviewed isolated fix can help, but never take an alpha/dependency bundle along with it.

Sync after a main fix that the experiment needs, before editing a shared state/API/storage contract, or before treating an experimental regression as unrelated to main. Batch independent small main fixes at the experimental owner's next boundary instead of interrupting every commit. Prefer a small integration PR over a large overdue merge.

## Preflight and merge gates

From the agent's own worktree, fetch complete current main and experimental histories. The local preflight is read-only and requires full SHAs and refreshed `origin` refs:

```sh
git fetch origin main:refs/remotes/origin/main claude/m3-expressive-alpha:refs/remotes/origin/claude/m3-expressive-alpha
python3 tools/check_branch_policy.py --base main --head "$(git rev-parse HEAD)" --source "$(git branch --show-current)"
```

For an experimental PR, pass `--base claude/m3-expressive-alpha`. Use the authorized HTTPS fetch/push helper in [CLAUDE.md](../CLAUDE.md) if SSH is unavailable. Never rewrite a tracked ref backwards to make the check pass.

The lightweight **Branch policy / Branch direction** PR check:

- rejects an experimental or forward-sync source targeting main;
- rejects any main-targeted head descended from the first alpha-upgrade commit `454350a058fc4e272731afd0fff23bd9cd22e0f3`, even under a renamed branch;
- requires the tested head to contain the current destination;
- additionally requires forward-sync heads to include current main;
- fails when required history cannot be verified.

The check has a read-only token, no signing secrets and no Android build. It does not extend signed APK access to fork PRs. Missing anchor history needs investigation; do not silently replace the anchor or disable the check. If the experiment is retired, update this policy through a reviewed PR.

Before merging, refresh the PR head, destination and CI. Both **Build, test and sign** and **Branch direction** must pass on the final head. If the destination changed, merge it into the isolated work branch, inspect the resolution and rerun CI. Record the exact head and destination on the PR and merge using the expected head SHA. Do not enable auto-merge or bypass failed/pending checks to save quota.

The CI check is a snapshot, not a lock. GitHub branch protection with strict/up-to-date required checks is the server-side gate for main; inspect the live protection settings and latest checkpoint rather than assuming documentation enabled it. Without protection, the preflight and merge procedure remain contributor-enforced. An experimental branch that the user updates directly is likewise not protected by PR-only checks.

This guard prevents common accidental branch mixing. It does not detect a hand-copied/squashed alpha change, stop a trusted writer modifying the policy, or prove semantic compatibility. Review build/dependency changes, settings/schema migrations, storage ownership, playback contracts and CI/signing behavior in every relevant integration. Do not resolve a conflict by taking an entire file from either branch without checking both changes.

## Evidence and stopping safely

Each PR records destination/track, source and destination SHAs, any cross-track source/forward-sync need, actual model roles, checks, artifacts and remaining QA. A handoff records dirty work, running tasks and next owner. Preserve it before another slice or a quota cutoff; use [the template](handoffs/TEMPLATE.md).

Main still publishes the normal private Canary feed. Experimental APKs come from Actions and update the same Canary package/data. Installing a newer main APK can replace experimental features; identify the track and version before recommending a download. No Stable tag/release, signing change, phone access or feed change is implied by this protocol.
