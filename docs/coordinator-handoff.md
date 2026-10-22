# Coordinator handoff

Updated 2026-10-22. This short resume entry points to maintained [issue state](audits/issues/README.md); history is evidence, not permission.

## Current boundary

Pixel8 user acceptance of .1325 passed the tried Bluetooth controls and found queue Undo/shuffled-order and TalkBack labeling/focus defects. Storage setup wording also needs phone-only/empty-library clarity. These are concrete UAT corrections within the frozen scope, not a reopened general audit.

Implementation is in the isolated #636 candidate worktree; no phone or Claude use for these changes. Main now includes documentation report #637 at `6eaeb766604d22e5e4f70b7fbacbb82ad924b648`. The app candidate remains DRAFT/OPEN pending final-head CI and focused user retesting. See the [current UAT correction checkpoint](handoffs/2026-10-22-uat-corrections.md); the [completed storage/compatibility evidence](handoffs/2026-10-22-release-acceptance.md) remains valid for its exact prior sources.

## Resume order

1. Read [current state](STATE.md), [fixed release gates](release-plan.md) and only the relevant [#253](audits/issues/253.md) / [#401](audits/issues/401.md) snapshot. Open the latest execution checkpoint and specific supporting evidence as needed; do not stream the entire issue timeline.
2. Verify live main/experimental/PR heads and required checks, worktree dirt/ownership, active processes and actual current user authorization. Preserve dirty work and uncertain storage operations. A clean checkout alone does not prove its writer is idle.
3. Use one writer per isolated branch/worktree/session. Do not resume the user's experimental Claude session. An allocated audit session must be explicitly assigned and idle; select the requested actual model/effort and record attribution. Local session IDs are optional conveniences, not required recovery state.
4. Check available usage at meaningful boundaries; missing readings are unavailable, not zero. Keep account/usage/private device details local. Reserve capacity for validation, fixes and durable handoff while continuing authorized useful work.
5. Keep exact-head CI/report/APK, selected automated device results and user acceptance distinct. Do not install over an active/uncertain Move. Diagnostic results do not establish actual release-configuration acceptance.
6. Update the relevant current issue file in place. Leave a concise checkpoint at recovery boundaries, link it once here, and use GitHub comments only for needed decisions/blockers/review/material results. Follow the [reporting contract](audits/issues/README.md).

## Integration and release

Follow [parallel tracks](parallel-tracks.md). Current protected-main checks and expected-head merge rules still apply. App PRs retain pending acceptance. Main Canary publication is separate from an explicitly requested Stable release. Experimental owners normally land forward-sync PRs at a clean boundary; the current alpha ancestry guard remains in force pending a separately reviewed future promotion.

[Historical coordinator chronology](handoffs/2026-10-21-status-archive/coordinator-handoff.md) preserves old prompts, role instructions and dated receipts for targeted lookup. Do not copy that chronology back into this entry point.
