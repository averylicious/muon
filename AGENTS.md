# Muon contributor instructions

For every coding agent in this repository. The user's explicit instructions for the current task take precedence. Current state, open work and **standing permissions** live in [`docs/STATE.md`](docs/STATE.md).

## Project boundaries

- Muon is a Kotlin / Jetpack Compose / Media3 Android client for Tauon. Keep the architecture small and dependencies minimal; follow existing code before adding abstractions.
- Tauon's unauthenticated HTTP API is **trusted-LAN-only**. Never expose Tauon publicly, open tunnels, or change the user's firewall/network configuration. This scope covers Tauon only: opt-in, off-by-default outbound lookups to external metadata or lyrics providers (such as synced lyrics, #797) are allowed behind a clear user toggle that discloses what metadata is sent.
- Hostile-media container pre-parsers and Media3-internal allocation wrappers are out of scope under that model. Add format-specific parser guards only for bugs reproduced on real user files; prefer Media3's public APIs (extractor filtering, track-selection parameters).
- Preserve package IDs, signing identities and the Stable/Canary update paths unless explicitly asked ([CI](docs/ci.md), [Obtainium](docs/obtainium.md)).
- Use the **1Password MCP server** for 1Password developer Environments; if it is unavailable, say so rather than switching methods. Never print or commit secrets or upload signing material.
- Design generations are named `Gen N` / `Gen N - <descriptor>`. `docs/design/current/` holds only real device screenshots; proposals go in `docs/design/gen-N*/`. Rules and the canonical table: [release naming](docs/release-naming.md).

## Git and GitHub access

Prefer the **GitHub CLI (`gh`)** for PRs, issues, checks and API calls, and **HTTPS git with the gh credential helper** over SSH, so no SSH-agent approval prompt appears on the user's desktop:

```sh
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' fetch https://github.com/averylicious/muon.git +refs/heads/main:refs/remotes/origin/main
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push https://github.com/averylicious/muon.git HEAD:refs/heads/<branch>
```

## Workflow: implement and hand off

1. Confirm scope and inspect the code. Preserve unrelated changes; do not grow a small fix into a refactor.
2. Work on a dedicated branch in its own worktree. Main fixes use `codex/<topic>` or `claude/<topic>` targeting `main`; experimental work targets `gen-4-progressive-blur`. Follow [parallel tracks](docs/parallel-tracks.md) for destination checks and forward syncs. Never push feature changes directly to `main`.
3. Add meaningful tests where behaviour warrants them and update relevant docs.
4. Push and use the **Android APKs** workflow. App/build changes build both signed variants and run unit tests, lint and publication/identity checks. Docs-only branches run lightweight checks with no APK. Manual dispatch always forces a full build. Fork PRs get no signed build; do not broaden secret access for them.
5. Inspect the run for the **latest PR head**. Fix in-scope failures and push again. Never call an unrun or blocked check a pass.
6. Fill in [the PR template](.github/pull_request_template.md): attribution, verification, test build, manual QA checklist. Give the user the PR and test-build links.
7. Merging follows the standing permissions in [`docs/STATE.md`](docs/STATE.md#standing-permissions). Never enable auto-merge, publish Stable or create release tags without the user's explicit request. Do not start review tasks or other agents automatically.

## Worktrees and sessions

- Use the harness's native worktrees: in **T3 Code**, `t3_worktree_status` then `t3_worktree_handoff` or `delegate_task` with `isolation: "worktree"`; in a native CLI/desktop app, its managed directory (e.g. `~/.codex/worktrees/<topic>`). Without either, use `git worktree` in a dedicated directory outside other checkouts (not loose in `~/Projects/`, not in an ignored folder `git clean -x` could remove) and remove it after merge.
- One writer per branch/worktree/session. Inspect `git worktree list` first. Never switch, clean, reset, stash, remove or reuse another session's checkout; a clean checkout is not proof its owner is idle. The original `~/Projects/muon` checkout belongs to the experiment.
- Never resume the user's Claude session. A separate audit session is usable only when the user explicitly assigns it and it is idle.

## Build and verification

- Assume no local Android build: CI is normally the first compile. Say so rather than implying code was compiled or run.
- Pinned versions live in `build.gradle.kts`, `app/build.gradle.kts` and the Compose BOM they name; do not copy them into docs.
- Check library APIs against the pinned version's published sources (AndroidX `-sources.jar` on Google's Maven), not memory. Record what was checked and what remains unverified.
- JVM unit tests cover pure logic; there are no Compose UI tests. Say which behaviour relies on CI compile, source reading and manual QA.

## Manual QA and test builds

- The user does phone QA. ADB, scrcpy, instrumentation or device-setting changes need **explicit authorization in the current task**; earlier permission is never standing.
- Report the Actions run, `app-debug-<full-SHA>` artifact, commit, Canary version and run number. The APK updates **Muon Canary** in place and expires after 14 days; for an older head, rerun its build rather than uninstalling or changing signing.
- Keep manual QA **pending user testing** until the user reports results. For docs-only PRs, link the successful lightweight run, say no APK was generated and that device QA is not needed. No skip-CI markers.

## Attribution on every PR

Name each contributing agent's actual model, role (implementation, review, integration), tool/client and effort if known; write `not reported` rather than guess. Use `Pending user-requested Astra review` until a review has happened; an author's self-check is not independent review. Keep attribution current when others fix or review.

## Review and promotion

Review the diff, surrounding code, latest-head CI and the user's QA results, and report specific findings; say what needs retesting if fixes change behaviour. After a `main` merge, verify the Canary prerelease and give its link. Stable tags need an explicit request. While the repository is public, releases and Actions logs are public.

## Continuing work

- Loop: implement → verify → fix → record durable evidence → next authorized task. A merged PR, green CI or saved checkpoint does not by itself end the turn; there is no one-slice-per-turn cap.
- Stop with a complete handoff when authorized work is done and only acceptance remains, a genuine blocker stops all useful work, remaining capacity is only enough for validation and handoff, or the user asks.
- Check actual usage at meaningful boundaries; a missing reading is unavailable, not zero. Reloading a long session's history can cost more than the task itself.
- Keep unanswered user questions in the current checkpoint (decision needed, why, what it blocks) and list them again at every final handoff. A missed notification or elapsed time is never approval.

## Portable issue state

- Resume from [the issue-state index](docs/audits/issues/README.md), the relevant issue file and [STATE.md](docs/STATE.md), then verify live heads and checks; GitHub is the truth. Do not load whole issue timelines.
- Keep one maintained Markdown file per active issue under `docs/audits/issues/`, edited in place and linked from a short issue/PR body. Comment on GitHub only for decisions, blockers, actionable review, material verification or merge/release changes. Preserve historical user decisions.
- Chat history and local session IDs are conveniences, never prerequisites. Starter prompts: [agent workflow](docs/agent-workflow.md).
