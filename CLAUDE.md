# Muon: instructions for Claude Code

@AGENTS.md

## Start here, every session (local or cloud)

1. Read [`docs/STATE.md`](docs/STATE.md): what is on `main`, the latest Canary, open PRs, what the user has authorized this cycle, and the next slice.
2. Verify it live before acting: `gh pr list`, the latest `main` run, and the latest checkpoint comment on issue #40. `STATE.md` is updated at every merge, but GitHub is the truth.
3. Permissions: **merging a blocker-free PR** is a standing authorization from the user (2026-09-27, quoted in `docs/STATE.md`). **Device access (ADB) and releases** are per cycle: they need the user's go-ahead in the current conversation. A note here or on #40 records evidence, not new permission.

## Practical notes for this repo

- **No local Android build:** CI (the Android APKs workflow) is the first compile. Check AndroidX, Media3 and Material 3 APIs against the pinned versions' `-sources.jar` on Google's Maven, not from memory.
- **Pushing:** the SSH agent may be unavailable. Push over HTTPS with the gh credential helper:
  `git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push https://github.com/averylicious/muon.git HEAD:refs/heads/<branch>`.
- **Waiting on CI:** poll `gh api "repos/averylicious/muon/actions/runs?head_sha=<sha>"` in a background loop, rather than sleeping in the foreground.
- **Merges:** when authorized, merge with `gh api -X PUT repos/averylicious/muon/pulls/<n>/merge -f merge_method=merge -f sha=<CI-verified head>`, and record the review on the PR first.
- **Worktrees:** use one per branch under `~/.codex/worktrees/`, so parallel sessions never share a checkout.
- **After each merge:** update `docs/STATE.md` in the next PR (or a docs-only PR), and post a checkpoint on #40 at the end of a batch.
- **Phones:** the Pixel's wireless ADB port changes between sessions, so check `adb devices` rather than reusing an old serial. Device rules are in `docs/STATE.md` → Authorization.
