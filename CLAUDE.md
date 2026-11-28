# Muon: instructions for Claude Code

@AGENTS.md

## Start here, every session

1. Read [`docs/STATE.md`](docs/STATE.md): what is on `main`, the latest Canary, open PRs and issues, and the **standing permissions**. Merging follows that section; device access (ADB) and releases need the user's go-ahead in the current conversation.
2. Read [parallel tracks](docs/parallel-tracks.md) before any branch, sync or merge, then the relevant [issue-state file](docs/audits/issues/README.md). Verify live heads and checks; GitHub is the truth.

## Claude Code notes

- Git and `gh` access, worktrees, attribution and QA rules are in AGENTS.md. Use HTTPS with the gh credential helper, not SSH.
- **Waiting on CI:** poll `gh api "repos/averylicious/muon/actions/runs?head_sha=<sha>"` in a background task, not a foreground sleep.
- **Phones:** the Pixel's wireless ADB port changes between sessions; check `adb devices` instead of reusing a serial, and only with current-task authorization.
