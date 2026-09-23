# Coordinator state after the Collection redesign integration

2026-09-23. Current checkpoint for [the coordinator runbook](../coordinator-handoff.md). It follows
[the integration checkpoint](2026-09-23-collection-integration.md) and records the merged state that
checkpoint was written before. Dated evidence, not live status or authorization: verify GitHub first.

## Merged state

- `main` is `c17e9d393b60debbc41ec412af42a70602432d20` (merge of PR #95, integrating the reviewed
  Collection redesign frontend through PR #94). It also contains PR #67 (CI optimization) and PR #92
  (release naming), merged earlier at `9efc66b34a6d83999bc6bed41144116b944f8bf5`.
- Integration run 122 (`35827752057`) passed with its artifact checksum and `BUILD.txt` verified by
  Astra. PR #94's corrected head passed run 121.
- The included frontend PRs were marked merged by GitHub through the integration. PRs #55 and #57
  were closed as delivered through copied commits; #41 is closed.
- **Pending at writing:** main run 123 (`35828481130`) was still building. Its success and the
  resulting private Canary publication must be verified before anyone calls that Canary ready. No
  Stable tag exists or is requested.

## Still open

- Backend: #56 (album metadata), #58, #61, #62. #56 conflicts textually with #79's title change in
  `TauonApi.tracks()`; the rebase is trivial.
- Documentation: #52 (Collection redesign mockups) and #66 (the earlier runbook PR). The runbook PR
  that carries this checkpoint supersedes #66 once merged; #66 is left for the coordinator to close.
- Issue #93: persist shuffle and repeat preferences across process death, force stop and reboot — a
  separate Astra backend slice before Stable.

## Review and QA

- Independent reviews: Astra reviewed Claude's #91 and #94 and their fixes. PR #79's filename
  fallback was independently reviewed by Claude Sonnet 5 at `49352b3` and its integration re-checked
  in #94 by Claude Opus 5.5, with no blockers.
- **Phone QA of the new gestures is pending** (presentation host, mini-player following, top-bar
  dismissal, sheet separation and insets). Policy tests are not device evidence. #43's motion,
  including a spring settle, still needs verification or tuning, so #43 stays open.

## Authorization and ownership

- The user authorized sound, reviewed merges this cycle. A new contributor confirms the current
  authorization before merging; the AGENTS.md default for implementation remains leave-open. No phone
  access, Stable publication, signing or network changes are authorized.
- Coordinator: Astra. Frontend: Claude. Claude's session is idle after this documentation slice.

## Agreed sequence and deferred work

Player → artwork and collection browsing → queue and actions → Search and Connect → stabilization.
Milestone 1 is the Collection redesign. Blur is deferred to future work; Baseline Profiles belong in a
later point release.

## Usage snapshots

Claude Opus 5.5 (`claude-opus-5-5`, medium effort) wrote this; its latest reading, as given to it, was
about 83% of the five-hour window and 42% weekly. Astra's current account exposes a weekly reading
only; its last recorded 18% snapshot is stale. Treat both as snapshots, not current capacity.
