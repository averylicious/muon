# Muon contributor instructions

These instructions apply to all coding agents working in this repository. Read them before making changes. The user's explicit instructions for the current task take precedence.

## Project boundaries

- Muon is a Kotlin / Jetpack Compose / Media3 Android streaming client for Tauon. Keep the architecture small and dependencies minimal; follow the existing code before adding abstractions.
- Tauon's unauthenticated HTTP API is trusted-LAN-only. Do not expose it publicly, open tunnels, or change the user's firewall/network configuration as part of feature work.
- Preserve package IDs, signing identities, and the Stable/Canary update paths unless explicitly asked to change them. See `docs/ci.md` and `docs/obtainium.md`.
- Always use the **1Password MCP server** when working with 1Password developer Environments. If unavailable, report that limitation instead of silently switching to another access method. Never print or commit secrets or upload signing material as an artifact.

## Default role: implement and hand off

1. Confirm the requested scope from the task and inspect the relevant code. Preserve unrelated local changes. Do not expand a small fix into a general refactor or repository-wide audit.
2. Work on a dedicated branch and its own persistent worktree. Audit fixes normally use `codex/<short-topic>` targeting `main`; experimental changes target `claude/m3-expressive-alpha`. Follow [the parallel-track protocol](docs/parallel-tracks.md) for destination checks and forward integration. Never reuse another session's checkout. Do not push feature changes directly to `main`.
3. Add meaningful tests where behavior warrants them, and update relevant documentation. Prefer GitHub Actions for Android builds because the user's desktop is slow at building APKs. Run lightweight checks locally when useful.
4. Push the branch and use the existing **Android APKs** workflow. For app/build changes it builds both signed variants, runs unit tests and lint, and checks publication safeguards and APK identities. Documentation-only branches run lightweight checks without APKs; report that outcome instead of promising an artifact. Manual workflow dispatch always requests the full build. Same-repository branch pushes trigger it; fork PRs do not currently receive this build. Do not broaden secret access to make an untrusted fork build.
5. Inspect the run for the **latest PR head commit**. Fix failures within scope, push again, and refresh the build links. If a check is blocked or unavailable, report it accurately; never call an unrun check a pass.
6. Complete the PR description using `.github/pull_request_template.md`, including model attribution, verification results, a test-build link, and a short manual QA checklist. Give the user the PR and test-build links in the handoff.
7. **Leave the PR open,** unless the user's standing authorization in [`docs/STATE.md`](docs/STATE.md) covers it. Since 2026-09-27 that lets an agent merge its own blocker-free PR at the CI-verified head, recorded on the PR as a self-review. Forward-sync PRs are the exception: leave them for the experimental owner to land at a clean boundary, unless the user delegates that integration. Never enable auto-merge, publish a Stable release or create release tags without the user's explicit request. Astra reviews or audits when the user asks. Do not automatically start a review task or another agent.

## Build and verification

- Do not assume a local Android build. Agent environments on the user's desktop may lack the Gradle cache or Android SDK platform, and the desktop is slow at building APKs. The **Android APKs** workflow is normally the first real compile; say so rather than implying code was compiled or run locally.
- Pinned versions live in the build files (`build.gradle.kts`, `app/build.gradle.kts` and the Compose BOM they name). Read them there; do not copy version numbers into documentation, where they go stale.
- When unsure of a library API or its behaviour, check it against the pinned version's published sources (for AndroidX, the `-sources.jar` on Google's Maven), not memory or the latest docs. Record in the PR or handoff what was checked and where, and what remains unverified.
- JVM unit tests cover pure logic. There are no Compose UI tests, and device testing is the user's. Say plainly which behaviour relies on the CI compile, source reading and manual QA.

## Manual QA and test builds

- The user performs phone QA. Do not use ADB, scrcpy, instrumentation on their phone, or change device settings unless the user explicitly asks for device testing in the current task. Historical device-debugging permission is not standing permission.
- Provide the successful Actions run URL and the `app-debug-<full-commit-SHA>` artifact link/name. Record the commit, Canary version, and workflow run number. The artifact ZIP contains `app-debug.apk`, `SHA256SUMS`, and `BUILD.txt`.
- This signed debug APK updates **Muon Canary** and preserves its data; it is not a separate per-PR app. Branch artifacts expire after 14 days and do not appear in Obtainium's GitHub Releases feed. The release-variant artifact is also available, but use Canary for routine manual QA.
- Android prevents ordinary version-code downgrades. When testing several branches, use a fresh workflow run of the desired head if its APK is older than the installed build. Do not uninstall the user's app or change signing to bypass this.
- Keep manual QA marked **pending user testing** until the user supplies results. Automated checks do not establish playback, visual quality, or real-device behavior. For documentation-only PRs, say that device QA is not needed. When CI selects documentation-only checks, provide the successful run and say no APK was generated. Do not add skip-CI commit markers; the workflow should report its check.

## Attribution on every PR

- Identify each contributing agent's actual model and role: implementation, review, or integration. Include the tool/client and effort setting when known. Do not infer an unknown model from its display name; write `not reported` where necessary.
- Name the reviewer only after a review has occurred. Before that, use `Pending user-requested Astra review`. An author's self-check is not independent review.
- Keep attribution current when another agent fixes or reviews the PR, including Astra's own PRs. A later Astra review must distinguish its review from its own authorship or fixes.

## Astra review and promotion cycle

When the user requests review, inspect the PR diff and relevant surrounding code, assess behavior and regressions, and check the latest head's CI and the user's QA results. Report specific findings and unresolved limitations. Keep review proportional to the change.

Review alone does not authorize merging. When the user requests review **and merge**, resolve blocking findings, ensure required checks pass on the final head, and merge that reviewed commit. If fixes change behavior after manual QA, identify what needs retesting. Update the PR's attribution and validation evidence.

A successful `main` build automatically publishes a private Canary prerelease. Verify that publication and provide its link; report build/publication failures without claiming an update is ready. Stable `vMAJOR.MINOR.PATCH` tags require an explicit stable-release request. Do not create per-PR prereleases under the existing Canary feed.

See `docs/agent-workflow.md` for starter prompts and the human handoff sequence.

## Resuming or coordinating across agents

- For the main-branch audit authorized on 2026-09-27, start with [the audit map and latest report](docs/audits/README.md). Astra may review/fix any component while Claude and the user experiment off main. Workstreams organize resumable evidence, not rigid frontend/backend restrictions. Follow [the parallel-track protocol](docs/parallel-tracks.md) and preserve each branch's release behavior; apply the standing merge authorization above.

- A user-selected successor Astra may take over backend work and coordination. Read [the coordinator runbook](docs/coordinator-handoff.md) and its linked latest checkpoint before broad exploration; verify live PR heads, checks, ownership and user authorization. A checkpoint records evidence, not new permission to merge or access devices.
- Keep one writer per branch/worktree/session. Astra audits main; the user and their Claude session own the experiment. The same file may change independently on both tracks, with reconciliation in a separate integration PR. Do not resume the user's Claude session; an explicitly allocated, idle audit session is separate. Routine work does not require a live agent-to-agent handshake. Experimental owners land forward-sync PRs at their own boundary.
- Work in small, independently reviewable slices. Before starting another, leave durable commit/PR/check/QA evidence for the previous slice. Reserve capacity for fixes and handoff; do not deliberately run into a hard limit with unrecorded edits.
- Put portable state in repository documents and PR/issue comments. Local chat history, temporary files, tool memories and local session IDs are optional conveniences, never prerequisites for another contributor. Follow the existing attribution, secret-access and fork-build boundaries.

- Coordinator rotation is reciprocal: a returning Astra follows the same live-state takeover checks as a new contributor. Before yielding, update the runbook checkpoint with active/idle ownership, exact commits, outstanding checks and the next bounded slice; never rely on the previous chat alone.
