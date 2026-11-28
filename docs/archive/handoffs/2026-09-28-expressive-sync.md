# Main-to-Expressive integration — 2026-09-28

## Scope and ownership

The user explicitly asked Astra to sync main into `claude/m3-expressive-alpha`, then corrected the quota warning because the five-hour window reset. This delegates this integration to Astra; future routine forward-sync PRs still belong to the experimental owner under [parallel tracks](../parallel-tracks.md). The experiment never merges wholesale into main.

Astra owns only the isolated `sync/main-into-expressive-audit` branch/worktree. The user-led experimental checkout and Claude session remain separate. After the remote merge, the experimental owner must inspect local changes, fetch and update their own branch before new work. Do not reset or discard local work. A separate audit Claude session was used for #184's read-only review and has finished; no Claude process is needed for this sync.

## Exact inputs and review

- Main: `831f5465b630343a4decdc1b4cbf46570801d753` (#184 merged). #184's final head `deedcf333b379a3d85b844d3acbdeddef796e695` passed [Android APKs run 306](https://github.com/averylicious/muon/actions/runs/36374465258) and [final Branch direction check](https://github.com/averylicious/muon/actions/runs/36375501717). Author/self-review: GPT-6 Astra; independent read-only original-head review: Claude Opus 5.5, High requested. Main protection is live: both checks from GitHub Actions, strict/up-to-date, admins enforced, PRs with zero required approving reviews, no force-push/deletion.
- Experimental destination: `117015a73efe166af9460cf22160fcbe8d5a8bc0`, refreshed after it advanced from `cd4bdfc6e9a4bd98608147004c715e0caf4e7c10`. New experimental player morph/UI work is preserved. It is not independently re-audited by this integration.
- Main's #180 changes carried forward: complete backup/device-transfer exclusions, narrow-subnet discovery fixes and conservative CI scope selection. #184 adds branch/session safeguards and handoff documents.
- Main's #183 landscape fix is already present on alpha. The only conflict was that row's modifier/comment; alpha's background plus blur was retained. `MuonApp.kt`, the experimental build files and all experimental UI files remain identical to the refreshed destination. No dependency downgrade, signing/package/feed change or storage migration.
- Astra source-reviewed the integration diff against both parents. Combined-head CI is required; the source main runs do not validate the alpha dependency combination. The PR and latest #181 comment record the final SHA, checks, artifacts and merge status; do not infer success from this initial checkpoint.

## Builds and pending QA

[#183 main run 304](https://github.com/averylicious/muon/actions/runs/36373841578) passed and [Canary .304](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.304) published. [#184 main run 310](https://github.com/averylicious/muon/actions/runs/36405297163) was running when this checkpoint was written; refresh its result/publication. Main APKs exclude the experiment.

This sync's build comes from Actions, updates existing Muon Canary and preserves the alpha features. It is not an Obtainium release. Device QA remains pending user testing: verify experimental motion/blur/morph/settings, landscape background in light/dark/Pure black, LAN discovery/manual address, playback and existing offline content. Do not delete app data for this check. #179's SD-card lifecycle risk is still open and unfixed; no hot-swap safety claim is made. No device access or Stable release occurred.

## Resume and next work

Read the PR and [#181](https://github.com/averylicious/muon/issues/181) for final evidence. If unmerged, refresh both input heads, merge any advances into this isolated branch, review and require both exact-head checks before merging with a merge commit. Never squash this sync or force-push shared history. If already merged, do not create a duplicate sync.

The local experimental branch's erroneous upstream was corrected from `origin/main` to `origin/claude/m3-expressive-alpha`; verify `git branch -vv` on every machine. Audit and sync worktrees now live outside the user's checkout, under `~/.codex/worktrees/`; their exact host paths are optional recovery hints.

Return the main audit to #179 after this integration boundary; see [the audit map](../audits/README.md). Astra's current quota telemetry is unavailable. The user's reset supersedes the earlier 4% warning; old Claude rate-limit snapshots are likewise not current quota measurements. Keep durable evidence before another slice/cutoff.
