# Agent workflow and starter prompts

[AGENTS.md](../AGENTS.md) is the shared instruction file; ask any client that does not load it automatically to read it. The [PR template](../.github/pull_request_template.md) records authorship, validation and test builds.

## One change through the cycle

1. **Implementation agent:** scoped change on a branch, PR, latest-head checks, then attribution, tested commit, signed Canary artifact and manual checks on the PR.
2. **You:** download the artifact ZIP, install `app-debug.apk` over Muon Canary, and report results on the PR. Testing another PR replaces that install; Stable is separate.
3. **Merge:** under the [standing permissions](STATE.md#standing-permissions) the track owner merges blocker-free PRs at their verified head unless you ask to leave one open. Astra reviews when you request it.
4. **GitHub Actions:** a successful APK-producing `main` build publishes the next Canary prerelease for Obtainium. Stable is a separate explicit request.

Nothing delegates or approves automatically. Parallel tasks each get their own branch and worktree.

## Implementation starter prompt

```text
Work in the Muon repository. Read AGENTS.md and docs/STATE.md first.

Task: <feature or bug, expected behaviour, scope limits>

Make a focused change on a dedicated branch and worktree and open a PR into
<main or gen-4-progressive-blur>, following docs/parallel-tracks.md. Add
meaningful tests and use the GitHub Actions build. Record your actual model,
tool/client, effort and role in the PR (not reported if unknown).

Give me the PR URL, successful run, signed Canary artifact, commit/version
and short manual QA steps. No ADB or device debugging. Merge under
STATE.md's standing permissions unless I ask to leave this PR open. No
auto-merge, release tags or Stable. If blocked, say exactly what remains
unverified.
```

## Astra review starter prompt

```text
Read AGENTS.md and review Muon PR #<number>.
My manual QA: <results, or not tested yet>.

Inspect the diff and surrounding code, regressions, test coverage and
latest-head CI. Update attribution, separating your fixes from review. No
device debugging.

This cycle: <review only and leave open / review and merge once blocking
findings are resolved and the final head passes>.

If merging, verify the main build and Canary publication and give me the
release link. Flag behaviour changes that need retesting. No Stable.
```

## Build notes

- See [CI details](ci.md). Test APKs are 14-day **Actions artifacts**, not GitHub Releases, so Obtainium does not see them. Artifact names carry the full SHA; each ZIP has `BUILD.txt` and `SHA256SUMS`.
- Branch and main builds share the Canary signing identity. If Android rejects an older APK, rerun that branch rather than uninstalling.
- Docs-only changes report a lightweight check with no APK: write "No APK generated; device QA not needed".
- Another Astra taking over starts from the [resume order](STATE.md#resume-order).
