# Independent audit and experiment tracks

The user authorized these tracks on 2026-09-28. They replace routine live frontend/backend coordination for the current main audit. Read this with [AGENTS.md](../AGENTS.md), [STATE.md](STATE.md) and the [latest checkpoint](coordinator-handoff.md). Earlier handoffs describe history, not current ownership.

## Release generations and frozen candidates

Follow the [release sequence and finite current gate list](release-plan.md). The user-approved sequence is current audit → frozen RC → explicitly approved Stable → checked forward integration into M3E → further experimentation and maintenance → next frozen candidate and delta-focused audit. The 2026-10-24 promotion exception below is limited to the frozen accepted RC; later experimental work stays isolated. RC preparation does not authorize a final Stable tag. Routine main-track audit automation should use the separate Diagnostic app when authorized so it does not replace the user's everyday experimental Canary. Release-configuration acceptance is still required.

## Work without sharing sessions or checkouts

| Track | Destination | Default owner | Purpose |
| --- | --- | --- | --- |
| Main audit | `main` | Astra | Correctness, security, privacy, reliability and CI fixes in any component |
| Expressive experiment | `claude/m3-expressive-alpha` | User and their Claude session | Experimental libraries and design; branch APKs only |
| Forward integration | Experimental branch, through a `sync/main-into-expressive-<topic>` PR | One named integrator; experimental owner lands it | Bring reviewed main fixes into the experiment |

- Each agent uses its own branch **and persistent worktree**, preferably outside another agent's checkout (for example `~/.codex/worktrees/<topic>`). A nested ignored worktree is still vulnerable to a parent `git clean -x`; do not treat `.gitignore` as filesystem isolation. Inspect `git status --short` and `git worktree list` first. Do not switch, clean, reset, stash, remove or reuse another session's checkout. A clean checkout is not evidence its owner is idle. The original Muon checkout currently belongs to the experiment.
- One writer per branch/worktree/session. Different tracks may change the same file independently; integration is where those changes are reconciled. This permits work without asking the other session before each edit.
- Astra must not resume the user's experimental Claude session. The user may assign a **different audit Claude session**; verify that exact session is idle and assign it a separate worktree before use. A session ID from old logs is not authorization to take it over. Without an allocated idle session, continue independently and leave PR/issue evidence.
- Within a track, claim the issue/slice and branch in its PR or issue before overlapping work. Check existing PRs before starting another. A successor verifies the previous writer has stopped; a user-directed takeover after a cutoff is sufficient, but dirty work must be preserved.
- Record findings and cross-track dependencies on [#181](https://github.com/averylicious/muon/issues/181) or the relevant PR. Contact the other agent only for an unresolved behavioral decision or ownership collision, not for ordinary independent fixes.

## Moving fixes between tracks

**Main to experiment:** after a useful main fix lands, the experimental owner or a named integrator makes a separate integration branch from the current experimental head. Merge current main into that branch, resolve conflicts, review the combined diff against the experiment and run CI there. Open a PR into `claude/m3-expressive-alpha`. Do not open a direct main-to-experiment PR: the tested main APK does not test the combined experimental app.

Astra may prepare that PR autonomously. The experimental owner lands it at their next clean boundary and updates their checkout, so a background audit process never moves the branch underneath their editing session. Leave it open for them unless the user explicitly delegates that integration. Use merge commits for these syncs, retaining main's ancestry; do not squash, rebase or force-push shared track history.

**Routine experiment to main:** outside the explicit frozen promotion below, do not merge the experiment wholesale, even if Git reports no conflicts. For a generally useful fix, make a fresh branch from current main and port only that focused change and its tests. Cite the experimental source commit/PR and check it against main's pinned dependencies. #183 is an example of this pattern. Cherry-picking a reviewed isolated fix can help, but never take an alpha/dependency bundle along with it.

Sync after a main fix that the experiment needs, before editing a shared state/API/storage contract, or before treating an experimental regression as unrelated to main. Batch independent small main fixes at the experimental owner's next boundary instead of interrupting every commit. Prefer a small integration PR over a large overdue merge.

## Frozen RC and bounded promotion

The user accepted focused M3E UAT on 2026-10-24 and chose to daily-drive a frozen RC while later audits produce separate candidates. Frozen source: `6b1a767c180a8b9e531bf1468c9e72e348635b74` (#678), whose app/build/workflow/tools match accepted Diagnostic .1415 source `209247be9634cd60145525ed15a98af46b5f47fc`; differences are maintained documentation only. No final Stable publication is requested.

A separately reviewed policy change allows **only** `codex/promote-m3e-rc-oct24` to introduce that accepted history to main. The promotion must include current main and the exact frozen commit, keep its tree outside `docs/` and the four named policy/test files unchanged, keep those four files identical to main, and import no later experiment-only commits. Start from the frozen source, merge reviewed main policy, and run full checks; do not merge the moving experiment tip. Main advancing with other code requires a new reviewed/accepted candidate instead of silently changing the freeze.

After this promotion, main descendants may contain the now-accepted alpha history. The guard still rejects direct experiment/forward-sync sources, renamed heads importing experiment-only commits absent from main, and main polluted without the accepted anchor. Stable tags containing alpha history must include the accepted anchor and already belong to main; pre-alpha historical main tags remain valid. This is a fetched-graph safeguard, not authentication of cherry-picked code or release authorization. Do not squash/rebase promotion history.

Keep every frozen candidate identified by source, exact signed artifact/hash and acceptance. Later audits use isolated branches and Diagnostic builds; do not replace the user's installed RC without an installation request. Normal Canary still follows main; a manually installed signed release-variant RC uses the existing Stable package and does not follow Canary publications. It is an unreleased test APK, not a new Stable release, even though its package is the Stable one. Branch release artifacts retain the workflow's development version name; use BUILD.txt/run/code to identify the RC. Do not introduce an RC tag/feed/package without explicit approval. Keep #671 and existing deferrals open with their current dispositions. Only a confirmed serious defect can invalidate the freeze as a release gate.

## Preflight and merge gates

From the agent's own worktree, fetch complete current main and experimental histories. The local preflight is read-only and requires full SHAs and refreshed `origin` refs:

```sh
git fetch origin main:refs/remotes/origin/main claude/m3-expressive-alpha:refs/remotes/origin/claude/m3-expressive-alpha
python3 tools/check_branch_policy.py --base main --head "$(git rev-parse HEAD)" --source "$(git branch --show-current)"
```

For an experimental PR, pass `--base claude/m3-expressive-alpha`. Use the authorized HTTPS fetch/push helper in [CLAUDE.md](../CLAUDE.md) if SSH is unavailable. Never rewrite a tracked ref backwards to make the check pass.

The lightweight **Branch policy / Branch direction** PR check:

- rejects an experimental or forward-sync source targeting main;
- before approved promotion, rejects alpha ancestry (including renamed branches) except the bounded frozen promotion above; afterwards rejects additional experiment-only ancestry absent from main;
- requires the tested head to contain the current destination;
- additionally requires forward-sync heads to include current main;
- fails when required history cannot be verified.

The check has a read-only token, no signing secrets and no Android build. It does not extend signed APK access to fork PRs. Missing anchor history needs investigation; do not silently replace the anchor or disable the check. Any new promotion/anchor change requires another reviewed policy PR; a moving experiment tip is never an implicit approval.

Before merging, refresh the PR head, destination and CI. Both **Build, test and sign** and **Branch direction** must pass on the final head. If the destination changed, merge it into the isolated work branch, inspect the resolution and rerun CI. Record the exact head and destination on the PR and merge using the expected head SHA. Do not enable auto-merge or bypass failed/pending checks to save quota.

The CI check is a snapshot, not a lock. GitHub branch protection with strict/up-to-date required checks is the server-side gate for main; inspect the live protection settings and latest checkpoint rather than assuming documentation enabled it. Main protection was enabled and read back on 2026-09-28: both checks are required from GitHub Actions, strict/up-to-date mode and admin enforcement are on, PRs require zero approving reviewers, and force-push/deletion are disabled. Verify these settings live before relying on them. Without protection, the preflight and merge procedure remain contributor-enforced. An experimental branch that the user updates directly is likewise not protected by PR-only checks.

This guard prevents common accidental branch mixing. It does not detect a hand-copied/squashed alpha change, stop a trusted writer modifying the policy, or prove semantic compatibility. Review build/dependency changes, settings/schema migrations, storage ownership, playback contracts and CI/signing behavior in every relevant integration. Do not resolve a conflict by taking an entire file from either branch without checking both changes.

## Evidence and stopping safely

Each PR records destination/track, source and destination SHAs, any cross-track source/forward-sync need, actual model roles, checks, artifacts and remaining QA. A handoff records dirty work, running tasks and next owner. Preserve it before another implementation boundary or a quota cutoff; use [the template](handoffs/TEMPLATE.md). A checkpoint does not end a continuing turn; follow [continuous engineering](continuous-engineering.md) and continue actionable authorized work while capacity allows.

Main still publishes the normal Canary feed with the repository's current visibility. Experimental APKs come from Actions and update the same Canary package/data. Installing a newer main APK can replace experimental features; identify the track and version before recommending a download. No Stable tag/release, signing change, phone access or feed change is implied by this protocol.
