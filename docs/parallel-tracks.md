# Parallel tracks

Authorized by the user on 2026-09-28. Main and the experiment proceed independently, without routine live coordination. Current heads and permissions: [STATE.md](STATE.md).

| Track | Destination | Default owner | Purpose |
| --- | --- | --- | --- |
| Main audit | `main` | Astra | Correctness, security, privacy, reliability and CI fixes in any component |
| Gen 4 - Progressive Blur | `gen-4-progressive-blur` | User and their Claude session | Compose `1.13` / SDK `37.2` and progressive/backdrop blur; branch APKs only |
| Forward integration | `gen-4-progressive-blur` via a `sync/main-into-gen-4-<topic>` PR | One named integrator | Bring reviewed main fixes into Gen 4 |

## Working without shared checkouts

- Each agent uses its own branch and persistent worktree ([AGENTS.md](../AGENTS.md#worktrees-and-sessions)). One writer per branch/worktree/session; tracks may change the same file independently and reconcile in integration.
- Claim the issue and branch in its PR or issue before overlapping work; check existing PRs first. A successor confirms the previous writer has stopped (a user-directed takeover is enough) and preserves dirty work.
- Record cross-track dependencies on the relevant issue or PR. Contact the other track only for an unresolved behavioural decision or ownership collision.

## Moving fixes between tracks

- **Main → Gen 4:** from the current Gen 4 head, make a `sync/main-into-gen-4-<topic>` branch, merge current `main`, resolve conflicts, review the combined diff and run CI there. Never open a direct `main` → experiment PR: the main APK does not test the combined app. Once CI (and any requested QA) passes, the integrator lands it with a **regular merge commit** (`--merge`); never squash, rebase or force-push shared history.
- **Gen 4 → main:** never merge the experiment wholesale, even without conflicts. Port a focused fix and its tests onto a fresh branch from `main`, cite the source commit/PR, and check it against main's pinned dependencies. Never carry a dependency bundle along.
- Sync after a main fix the experiment needs, before changing a shared state/API/storage contract, or before calling an experimental regression unrelated to main. Batch small fixes at the experimental owner's next clean boundary.

## Preflight and merge gates

From your own worktree, refresh both histories (HTTPS helper: [AGENTS.md](../AGENTS.md#git-and-github-access)) and run the read-only preflight. For a Gen 4 PR, pass `--base gen-4-progressive-blur`.

```sh
git fetch origin main:refs/remotes/origin/main gen-4-progressive-blur:refs/remotes/origin/gen-4-progressive-blur
python3 tools/check_branch_policy.py --base main --head "$(git rev-parse HEAD)" --source "$(git branch --show-current)"
```

The **Branch policy / Branch direction** PR check rejects `gen-4-progressive-blur` or `sync/main-into-*` heads targeting `main`, requires the head to contain its current destination, requires forward-sync heads to contain current `main`, and fails when history cannot be verified. It has a read-only token and no signing secrets. Never move a ref backwards or disable the check to make it pass; change the policy only through a reviewed PR.

Before merging, refresh the head, destination and CI. **Build, test and sign** and **Branch direction** must both pass on the final head. If the destination moved, merge it in, inspect the result and rerun CI. Record the head and destination on the PR and merge with the expected SHA:

```sh
gh api -X PUT repos/averylicious/muon/pulls/<n>/merge -f merge_method=merge -f sha=<CI-verified head>
```

The check is a snapshot, not a lock, and does not catch hand-copied changes or prove semantic compatibility. Review build/dependency, settings/schema migrations, storage, playback contracts and CI/signing in every integration; never resolve a conflict by taking a whole file from one side. Server-side protection on `main` must be verified live ([STATE.md](STATE.md)).

## Evidence and builds

Each PR records track, source and destination SHAs, cross-track needs, model roles, checks, artifacts and remaining QA. Main builds publish the Canary feed; experimental APKs are Actions artifacts that update the same Canary package, so a newer main APK can replace experimental features. Name the track and version before recommending a download. This protocol implies no Stable release, signing change, feed change or device access.
