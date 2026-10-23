# Current audit issue state

Start here when resuming the main audit. These are **maintained current snapshots**, edited in place, not append-only journals. Read the [release gates](../../release-plan.md) and the relevant issue file; open older evidence only for the specific question being investigated.

| Workstream | Current state | GitHub owner record |
| --- | --- | --- |
| Aggregate memory and partition storage | [#253](253.md) | [Issue #253](https://github.com/averylicious/muon/issues/253) |
| Saved-card startup and Move latency — explicitly deferred post-release | [#401](401.md) | [Issue #401](https://github.com/averylicious/muon/issues/401) |
| Saved-copy Delete refusal and reset compatibility | [#646](646.md) | [Issue #646](https://github.com/averylicious/muon/issues/646) |
| First-song normalization and missing-tags reminder | [#645](645.md) | [Issue #645](https://github.com/averylicious/muon/issues/645) |
| Overall release sequence and acceptance gates | [Release plan](../../release-plan.md) | [Audit #181](https://github.com/averylicious/muon/issues/181) |

GitHub remains authoritative for current heads/checks/merge state. A snapshot must identify its date, inspected head and verification limits; it does not grant device access or override user decisions. Latest execution state belongs in the concise [coordinator entry point](../../coordinator-handoff.md), with history linked rather than repeated. Files on an open documentation PR are proposals/working state until merged; verify the linked branch/PR before using a main-branch copy.

## Reporting contract for all agents

- Keep one existing issue as the workstream's owner record. Keep its body short: problem, current status, link to its maintained Markdown state and relevant PR. Reuse an existing PR for an unchanged scope; do not create tracking duplicates.
- Use one current Markdown file per **active issue**, following [the template](TEMPLATE.md). Edit the current sections in place. Do not append each session's transcript or a new dated block forever. Aim for roughly one page; extract lengthy supporting analysis into a linked evidence document.
- Record the exact candidate/source commit, implementation versus merge state, completed evidence, unresolved findings, remaining release gates and next action. Separate CI/source, selected automated device checks and user acceptance. Keep active/uncertain operations visible until reconciled.
- Comment on GitHub only for a needed decision/question, a blocker, actionable review feedback, a material verification result, or a merge/release change. Link the state file and short receipt. Routine quota readings, unchanged polling, every successful intermediate CI and repeated checkpoints do not need new issue/PR comments.
- Amend a short maintained summary comment only when necessary if the issue body cannot be edited. Do not delete/rewrite historical user comments, decisions, review conversations or original evidence to reduce noise. Do not copy private runtime dumps, addresses, credentials or user metadata into public Markdown.
- Keep crash/quota recovery checkpoints when useful, but write the detailed handoff once, link it from the state file and the coordinator entry point, and avoid copying the same checkpoint into STATE, the runbook, the audit README and multiple issue comments.
- On resume, read this index, the relevant current file and its latest checkpoint first. Inspect live branch/head/check state. Query individual older comments or evidence only when needed; do not stream the complete issue timeline by default.
- Once an issue closes, leave a short final status and links to the landed fix and acceptance evidence. Reopen/update that file for a real regression; do not label an unresolved deferral as fixed.
