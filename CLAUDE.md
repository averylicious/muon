# Muon: instructions for Claude Code

@AGENTS.md

## Start here, every session (local or cloud)

1. Read [the parallel-track protocol](docs/parallel-tracks.md), then [`docs/STATE.md`](docs/STATE.md): what is on `main`, the latest Canary, open PRs, what the user has authorized this cycle, and the next slice.
2. Start with [maintained issue state](docs/audits/issues/README.md), the relevant current issue file and linked latest checkpoint; then verify live PR/main heads and checks. GitHub is the truth. Do not load the full historical issue timeline or append a checkpoint comment for every session.
3. Permissions: **merging a blocker-free PR** is a standing authorization from the user (2026-09-27, quoted in `docs/STATE.md`). **Device access (ADB) and releases** are per cycle: they need the user's go-ahead in the current conversation. A note here or on #40 records evidence, not new permission.

## Practical notes for this repo

- **No local Android build:** CI (the Android APKs workflow) is the first compile. Check AndroidX, Media3 and Material 3 APIs against the pinned versions' `-sources.jar` on Google's Maven, not from memory.
- **Pushing:** the SSH agent may be unavailable. Push over HTTPS with the gh credential helper:
  `git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push https://github.com/averylicious/muon.git HEAD:refs/heads/<branch>`.
- **Waiting on CI:** poll `gh api "repos/averylicious/muon/actions/runs?head_sha=<sha>"` in a background loop, rather than sleeping in the foreground.
- **Merges:** after both latest-head checks pass and the destination is still included in that head (see `docs/parallel-tracks.md`), when authorized, merge with `gh api -X PUT repos/averylicious/muon/pulls/<n>/merge -f merge_method=merge -f sha=<CI-verified head>`, and record the review on the PR first.
- **Worktrees and sessions:** use one persistent worktree per branch (prefer a directory outside another checkout, such as `~/.codex/worktrees/<topic>`; request host access if the execution sandbox requires it). The original checkout belongs to the experiment. Never resume a different session without the user assigning it; Astra's audit Claude session and the user's experimental session are separate.
- **Parallel tracks:** experimental changes target `claude/m3-expressive-alpha`; main fixes start from main. Read `docs/parallel-tracks.md` before a sync, refresh destinations and run `tools/check_branch_policy.py`. The experimental owner lands forward-sync PRs at a clean boundary. Do not merge the experiment wholesale into main.
- **Current state:** edit the relevant `docs/audits/issues/` snapshot in place, keep `docs/STATE.md` and the coordinator entry point short, and link detailed historical evidence. Existing issue/PR bodies should link current state. Comment only for needed decisions, blockers, actionable review, material verification or merge/release changes; do not post routine checkpoint transcripts on #40.
- **Phones:** the Pixel's wireless ADB port changes between sessions, so check `adb devices` rather than reusing an old serial. Device rules are in AGENTS.md → Manual QA and test builds; only explicit current-task authorization permits phone testing. Current execution/cleanup state is in the linked checkpoint, not a standing permission.
