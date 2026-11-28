# Open issue state

One maintained snapshot per **open** issue, edited in place. GitHub is authoritative for heads, checks and merge state; verify live before acting. Start from [current state](../STATE.md) and the [release plan](../release-plan.md).

| Issue | Workstream | Snapshot |
| --- | --- | --- |
| [#646](https://github.com/averylicious/muon/issues/646) | Saved-copy Delete refusal and reset compatibility (open by user decision) | [646.md](646.md) |
| [#401](https://github.com/averylicious/muon/issues/401) | Saved-card startup and Move latency (deferred) | [401.md](401.md) |
| [#671](https://github.com/averylicious/muon/issues/671) | Intermittent stream buffering (not recurred, non-blocking) | [671.md](671.md) |
| [#83](https://github.com/averylicious/muon/issues/83) | Measured Baseline Profile benefit (draft #683) | [83.md](83.md) |

Closed issue snapshots and the old audit index: [docs/archive/audits/issues/](../archive/audits/issues/README.md). Pending device checks: [phone QA queue](../phone-qa-queue.md).

## Reporting contract

- The GitHub issue is the owner record; keep its body short and link its snapshot here. Do not open tracking duplicates.
- Add a snapshot only for an open issue, from [the template](TEMPLATE.md). Aim for one page: exact inspected commits, implementation versus merge state, findings with their evidence limits, remaining gates and the next action.
- Keep CI/source, automated device checks and user acceptance separate. A snapshot records evidence; it grants no device access and overrides no user decision.
- Edit in place; no per-session log. Put a longer checkpoint in a [handoff](../handoff-template.md) and link it once.
- Comment on GitHub only for a decision, blocker, actionable review, material verification or a merge/release change.
- Never copy private device data, addresses or credentials into public Markdown.
- When an issue closes, write a final status with links to the fix and acceptance evidence, move the file to `docs/archive/audits/issues/` and drop its row above.
