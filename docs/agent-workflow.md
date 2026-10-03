# Agent workflow and starter prompts

The repository's [AGENTS.md](../AGENTS.md) is the shared instruction file. Agents should read it before work; for a client that does not load it automatically, explicitly ask it to do so. The [PR template](../.github/pull_request_template.md) makes authorship, validation, and test builds visible to the next reviewer.

## One change through the cycle

1. **Implementation agent:** make a scoped change on a branch, open a PR, and wait for GitHub's checks. Add the actual model/role, latest tested commit, signed Canary test artifact, and focused manual checks to the PR.
2. **You:** download the artifact ZIP from the linked Actions run, extract it, and install `app-debug.apk` over Muon Canary. Try the manual checks and report observations on the PR or in the review task. Testing another PR replaces that Canary installation; Stable remains separate.
3. **Astra, when you request it:** review the code, tests, CI evidence, and your feedback. An explicit review-only/leave-open request is respected. Otherwise STATE.md's standing authorization lets the assigned track owner merge blocker-free PRs at their verified heads; independent review is attributed separately from author self-review. Behavior changes during review may need another manual test.
4. **GitHub Actions:** the successful `main` build publishes the next Canary prerelease with the repository's current visibility. Astra verifies it and gives you the release link. Obtainium can then fetch that Canary update. Stable promotion is a separate explicit request.

There is no automatic delegation or approval loop. You choose when to bring a PR back for Astra review. For multiple simultaneous implementation tasks, give each its own branch and checkout/worktree so they do not edit the same files in the same working tree.

## Implementation starter prompt

Copy this into the other coding agent, replacing the task description:

```text
Work in the Muon repository. Read AGENTS.md before editing and follow its
implementation/PR handoff workflow.

Task: <feature request or bug, expected behavior, and scope limits>

Implement a small, focused change on a dedicated branch and open a PR into
<main or claude/m3-expressive-alpha, according to the assigned track>. Read
docs/parallel-tracks.md and use a worktree/session separate from the other
track. Add meaningful tests where needed and use the existing GitHub Actions
build. Include your actual model, tool/client, effort if known, and role in
the PR description; label unknown details as not reported.

Wait for the latest PR head's checks, then provide the PR URL, successful
Actions run, signed Canary APK artifact, commit/version, and short manual
QA steps. I will test on my phone. Do not use ADB or perform live device
debugging. Apply STATE.md's standing blocker-free merge authorization unless
I request leaving this PR open. Experimental owners land forward-sync PRs
at their own boundary. Do not enable auto-merge, create release tags or
publish Stable.

If blocked, explain the exact blocker and what remains unverified rather
than claiming the build or feature passed.
```

## Astra review starter prompt

```text
Read AGENTS.md and review Muon PR #<number>.
My manual QA observations: <results, or not tested yet>.

Inspect the diff and relevant surrounding code, assess regressions and test
coverage, and verify CI against the current PR head. Update model/role
attribution and validation evidence, distinguishing your own fixes from
review. Do not perform live phone debugging.

For this cycle: <review only and leave open / review and merge once blocking
findings are resolved and the final head passes its checks>.

If merging, verify the subsequent main build and Canary publication, and
give me its release link. Flag behavior changes that need manual retesting.
Do not publish Stable.
```

## Build details worth remembering

- The read-only Branch policy PR check guards track direction and base freshness. The Android APKs workflow is unchanged: same-repository branch pushes run scope checks; app/build changes build both signed variants and run tests/lint. Documentation-only branches use lightweight checks without APKs. See [CI details](ci.md).
- Pre-merge test APKs are **Actions artifacts**, available to authorized repository users for 14 days. They are not GitHub Releases and Obtainium does not see them in the release feed. The existing Canary feed stays reserved for successful `main` builds.
- Artifact names include the complete commit SHA. Each ZIP contains `BUILD.txt` with the commit, run number, and version, plus `SHA256SUMS`.
- Use the debug/Canary artifact for experiments. Both branch and main builds use the original Canary signing identity. A newer workflow run supplies a higher version code; if Android rejects an older test APK, rebuild that desired branch instead of uninstalling and losing settings.
- Every PR records actual contributing models, including Astra-authored changes. Reviewer attribution remains pending until review happens. Attribution is an audit trail, not a substitute for checks or human QA.
- Documentation-only changes still report a workflow check, but skip Android setup, signing, APKs and publication. Provide the successful run with "no APK generated; device QA not needed". Manual workflow dispatch forces a full build when needed. No new tests are needed merely to mirror prose.

## Another Astra contributor taking over

Use the [coordinator handoff runbook](coordinator-handoff.md) for the copyable
resume prompt, ownership rules, interruption recovery and latest dated checkpoint.
This supports a contributor on another machine/account as well as a new local
task. The [parallel-track protocol](parallel-tracks.md) lets main and the experiment
proceed without routine Claude coordination. An explicitly allocated audit
Claude session is separate from the user's experimental session; never
resume the latter. The workflow does not automatically launch other agents.
