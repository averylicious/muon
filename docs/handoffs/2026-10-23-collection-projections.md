# Collection projection follow-up — October23

Current RC #636/.1334 remains frozen at `1d73ecda6be93bcb9c17c3f1e48f1047d52c7fec`, user UAT pending. No phone access; no app install, Stable publication or experimental change is authorized by this checkpoint.

## Source and ownership

Root owns isolated `codex/collection-projections-oct23` / `muon-collection-projections-oct23`, based on post-RC CI head `ea6c9ea4d1999b5d91f0b0fec6a592a37e154683`. Implementation source `4b84fd8b530a811fd25d44b9ffd52eae2ec29985` includes Claude Opus5.5 High work, root fixes/tests and independent source review. Claude stopped successfully/idle at96% five-hour/6% weekly used. Root allowance refreshed on the same account to2% weekly used (no five-hour reading), so earlier93% reserve is stale. Verify current usage rather than reusing these readings.

[#639 maintained state](../audits/issues/639.md) records scope, review and pinned API evidence. First Android compile/full tests/lint/artifact verification is pending at push time; the future draft PR body must hold its immutable final head/run/artifact receipt. Do not claim working-device performance from source or tests. This follow-up must stay draft/open behind current #636 acceptance/merge and #641 integration; do not move main while user UAT is unresolved.

## Other work

CI #641 ordinary final-head .1336 passed120 CI/9 release-policy checks, both1,801-case suites/no skips and lint0errors/66warnings each, plus downloaded exact-signer/package/export/BUILD/hash verification. [Run](https://github.com/averylicious/muon/actions/runs/39820149164). Manual timing-mode [.1337](https://github.com/averylicious/muon/actions/runs/39822672644) is running at the same head to verify distinct artifact naming; it is not installed/published.

Network #638 has its own writer/worktree `codex/discovery-network-owner-oct23` / `muon-discovery-network-owner-oct23`, same base. Independent lifecycle review found an IPv6-only NSD regression; its implementing subagent is fixing that before root HTTP/source review and CI. Never share its checkout or overwrite moving edits. Root GitHub audit #181 body records live ownership; no new findings automatically become current RC gates.

## Next action

Push/verify the dedicated #639 draft at its final head; fix failures without suppressing checks. Preserve sorting/ties/debounce/completed-query token, current-source actions and saved scroll state. Later manual checks: large-library order/query switching, immediate loading/disabled actions, server replacement with equal metadata, return-from-artist/album search scroll, locale behavior. Devices stay paused until reauthorized. Keep attribution separate for Claude implementation, root fixes and independent review. No test matrix reduction/dependency or package/signing change.
