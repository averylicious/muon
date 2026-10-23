# Public readiness and move characterization checkpoint — 2026-09-30

## Ownership and authorization

Astra main audit continues in its isolated worktree; user/Claude own experiment. Public-readiness #232 merged at `b77619afd6f312584727aac809e4020475304b8b` with required exact-head docs checks. MIT/disclosure/third-party notices and [the scoped leak review](../audits/2026-09-30-public-readiness.md) are now main. Secret scanning and push protection enabled/read back; no confirmed credential leak found, with documented coverage limits. No device or audit Claude session used.

User temporarily made repo public after private Actions minutes ran out. Run386 fully succeeded and published main Canary .386, proving uploads/job start worked on that run. Its APK excludes open audit fixes and experimental UI. No Stable publication or tag, artifact deletion or history rewrite authorized/performed. Phone-QA app PRs remain open.

## Current boundary

`codex/move-removal-characterization`, based on main b77619: five disposable tests call production move/remove and real Media3 cache/index while capturing service intents. [Report](../audits/2026-09-30-move-removal.md). No production fix or behavior change. Until exact-head CI executes, expectations are unverified; do not call them reproduced. CI/run/head updates belong on its PR and #181.

Earlier aggregate checkpoint remains [#231](https://github.com/averylicious/muon/pull/231) at `808a814df9f4b9acc21adb34828c8666c194a9de`; its build385 rerun and branch check were requested after external recovery. Main changed via #232, so #231 requires refreshed destination integration and new final-head checks before merge. Its earlier billing-block report is historical; #232/report is newer evidence. All earlier app PR heads/gates are there; do not infer they are merged or combined.

## Next steps

1. Verify this characterization's CI compile/cases/full lint/identities and artifact. Fix fixture errors within this slice and record actual evidence. Test-only PR does not itself fix #230; keep issue open. Phone QA not needed for this test-only slice.
2. Refresh required checks on current heads; resolve shared-source overlaps before combining app fixes; leave app PRs needing device QA open. Resume #230 move/remove ownership design or #225 optional-copy scheduler characterization in another focused branch; no broad storage migration.

Save final SHA/checks/clean worktree and outgoing ownership on #181 before yielding. Repository documents/PRs are portable recovery state; temporary scanner/log files and local chat history are optional.
