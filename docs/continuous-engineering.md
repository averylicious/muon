# Continuing authorized engineering

User-approved standing clarification, 2026-10-09: remove the old one-slice-per-turn / “bite-sized” execution cap. Keep changes reviewable and validate each boundary, but continue across successive tasks while authorized actionable work and sufficient capacity remain. This supersedes older runbooks, prompts, checkpoints and memory notes that recommend ending after one reviewable slice.

The work loop is: implement → verify → fix failures → save durable evidence → choose the next actionable task → repeat. A completed commit/PR, green CI, saved handoff or elapsed hour is not by itself a reason to end the turn. Checkpoints support continued work and recovery; they do not require a coordinator rotation.

Stop and leave a complete handoff when:

- Authorized engineering is complete and only acceptance testing remains.
- A genuine blocker prevents all further useful authorized work. Record a local blocker and continue another actionable task when possible.
- Remaining capacity warrants finishing and wrapping up, with enough reserve for current validation, likely fixes, commit/push and portable handoff.
- The user asks to pause or stop.

Check the actual available usage at meaningful boundaries and before substantial new work. Report the account/window and used versus remaining percentage. Missing five-hour or another agent's readings are unavailable, not zero or a reused old value. A context window and usage allowance are different. Do not promise an exact task cost or deliberately exhaust capacity with unrecorded work; no billing/reset action is implied. If an allocated coding agent is unavailable, continue solo where feasible and authorized.

A turn limit on an allocated coding client does not bound the cost of reloading its existing history. Before reusing a long-lived session near quota, check recent input/cache usage as well as the remaining percentage. On2026-10-28 a short read-only Claude review reloaded about820,000 cache-creation input tokens and hit its five-hour limit before completing; it is not a completed independent review. If a history rebuild is excessive, keep that session idle until its context is managed through normal client compaction or the user allocates a fresh audit session. Preserve the original history and existing session-ownership boundary; do not silently use another session or repeatedly spend quota on the same reload. Repository issue state and exact source/check receipts provide the portable context.

Preserve existing safeguards: one writer per isolated branch/worktree/session; exact-head CI and proportional source review; required main protection; standing merge/acceptance rules; experimental owner independence; no Stable release/tag or automatic agent dispatch; no secrets. Phone access still needs explicit current-task authorization. Durable user permission takes precedence over a dated checkpoint; record renewed device scope without treating it as permanent permission.

Maintain concise evidence in the [current issue-state files](audits/issues/README.md) and keep one current continuation pointer. Edit those snapshots in place. Comment on the existing PR/issue for decisions, blockers, material verification or merge/release changes; a routine verified boundary does not require another checkpoint comment. Prefer links over repeated transcripts. Before a genuine stop, record exact heads/checks/artifacts, active/idle ownership, dirty or parked work, pending acceptance, blockers and the next actionable task. Do not say a quota was exhausted when the stop was discretionary.

## Questions that may be missed

User clarification 2026-10-27: Desktop notifications can be missed. Keep unanswered user questions in the maintained current checkpoint, with the exact decision needed, why it matters and whether it blocks any work. Record answers and remove resolved questions from the pending list; a missed notification or elapsed time is never approval. Continue useful independent authorized work while waiting. At every final handoff—including a user-requested early stop/reboot or the requested 20%-remaining usage boundary—explicitly list any unanswered questions again, separate from routine future QA and already answered decisions. If none remain, say so briefly. Do not rely on the notification sound or the previous chat alone.
