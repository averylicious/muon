# Continuing authorized engineering

User-approved standing clarification, 2026-10-09: remove the old one-slice-per-turn / “bite-sized” execution cap. Keep changes reviewable and validate each boundary, but continue across successive tasks while authorized actionable work and sufficient capacity remain. This supersedes older runbooks, prompts, checkpoints and memory notes that recommend ending after one reviewable slice.

The work loop is: implement → verify → fix failures → save durable evidence → choose the next actionable task → repeat. A completed commit/PR, green CI, saved handoff or elapsed hour is not by itself a reason to end the turn. Checkpoints support continued work and recovery; they do not require a coordinator rotation.

Stop and leave a complete handoff when:

- Authorized engineering is complete and only acceptance testing remains.
- A genuine blocker prevents all further useful authorized work. Record a local blocker and continue another actionable task when possible.
- Remaining capacity warrants finishing and wrapping up, with enough reserve for current validation, likely fixes, commit/push and portable handoff.
- The user asks to pause or stop.

Check the actual available usage at meaningful boundaries and before substantial new work. Report the account/window and used versus remaining percentage. Missing five-hour or another agent's readings are unavailable, not zero or a reused old value. A context window and usage allowance are different. Do not promise an exact task cost or deliberately exhaust capacity with unrecorded work; no billing/reset action is implied. If an allocated coding agent is unavailable, continue solo where feasible and authorized.

Preserve existing safeguards: one writer per isolated branch/worktree/session; exact-head CI and proportional source review; required main protection; standing merge/acceptance rules; experimental owner independence; no Stable release/tag or automatic agent dispatch; no secrets. Phone access still needs explicit current-task authorization. Durable user permission takes precedence over a dated checkpoint; record renewed device scope without treating it as permanent permission.

Leave concise evidence after each verified boundary in the PR/shared issue and keep one current continuation pointer. Prefer links over repeated transcripts. Before a genuine stop, record exact heads/checks/artifacts, active/idle ownership, dirty or parked work, pending acceptance, blockers and the next actionable task. Do not say a quota was exhausted when the stop was discretionary.
