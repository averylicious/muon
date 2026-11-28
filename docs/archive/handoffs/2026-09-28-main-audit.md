# Main audit checkpoint — 2026-09-28

## Current boundary

- **#180 merged** after source review and successful exact-head CI. Reviewed head `329ff0107e2a3719fbb2b10ac86e65b99083ff44`; main merge `bdb2da0065e316533912269bf3a5addf7afaf617`.
- Fixes: complete backup/transfer exclusions, subnet-safe discovery candidates, and full builds when main has no reachable Canary baseline. Includes the repository audit map and corrected standing-merge instructions.
- [PR run 290](https://github.com/averylicious/muon/actions/runs/36323073501) passed: signed debug/release APKs, both JVM test variants and lint, publication/CI tests, and APK signature/identity checks. Local Python checks: 27 passed. These are automated/source checks, not phone QA.
- Test artifact: [app-debug-329ff0107e2a3719fbb2b10ac86e65b99083ff44](https://github.com/averylicious/muon/actions/runs/36323073501/artifacts/10933850595), version `0.1.0-canary.290`, run/version code 290. It contains audited main, not the alpha UI experiment, and may be older than the installed Canary.
- [Main run 298](https://github.com/averylicious/muon/actions/runs/36345725688) is the publication run for the merge. Check its final result and release before recommending a main Canary update.

## Ownership and recovery

- GPT-6 Astra (Codex) owns the main audit. Claude Opus 5.5 independently reviewed #180's code fixes at Medium and analyzed #179 at High. Both review assignments completed without edits.
- The user's original checkout is now on `claude/m3-expressive-alpha` (observed `4bfc5cf` on resume). Its own experiment document describes artifact-only builds that update the existing Canary package. Do not check that checkout out to main or merge its experimental code while doing the audit.
- The old `/tmp` worktree disappeared during interruption. Its committed branch was intact, and the audit was recovered into an isolated persistent worktree. No uncommitted implementation was lost. Temporary Claude output is not required: the storage evidence is now in [the report](../audits/2026-09-28-storage.md) and #179.
- No Claude CLI was running at the resume check. A newer Claude project conversation exists for experimental work; refresh live activity/ownership before resuming any session. Do not concurrently drive the same session or mistake the older audit review session for the active experiment.
- Astra's account quota is not exposed by this session's tools. Previous Claude CLI rate readings belong to the previous window and are not current measurements. Refresh available readings at the next assignment and reserve handoff capacity.

## Next audit work

1. Continue [#181](https://github.com/averylicious/muon/issues/181) using [the map](../audits/README.md). #180 is only the first pass; whole-repository coverage remains incomplete.
2. Prioritize [#179](https://github.com/averylicious/muon/issues/179): the detailed review found a possible cache-loss chain during SD-card removal. Establish a data-preserving lifecycle and a disposable regression before claiming safe hot-swap. An availability guard alone is incomplete.
3. Other open questions remain: fresh-install Benchmark discovery, publication-failure recovery, signing/dependency trust, offline concurrency, playback/library lifecycle and measured performance. See the baseline report and #83.

## Authorization and QA

The user's standing authorization covers blocker-free merges at verified heads for both agents. Audit any relevant main component; workstreams are organizational aids. Stable release/tag, signing or experimental-feed changes need an explicit request. Phone access remains unrequested. Main still publishes its usual Canary feed, so distinguish those builds from the user's experimental branch before suggesting installation.

Manual LAN/backup checks for #180 and all new storage scenarios remain pending user testing. No device loss, measured performance improvement, or whole-app security certification is claimed. Preserve evidence and update this pointer/STATE.md at the next boundary.
