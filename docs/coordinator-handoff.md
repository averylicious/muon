# Coordinator handoff

Updated 2026-10-22. This short resume entry points to maintained [issue state](audits/issues/README.md); history is evidence, not permission.

## Current boundary

Resumed acceptance checks completed. **All device work stopped/restored**, no active storage worker/helper or code edits. Coordinator GPT-6 / Codex desktop (exact variant/effort not reported); allocated Claude Opus5.5 High finished focused read-only closure review, idle. Main `ec02b64f41996ec4a456c764ed94360675570df1`; experiment untouched.

Current app candidate DRAFT/OPEN [#636](https://github.com/averylicious/muon/pull/636), app/build source `af70c50c226cfafb5320052bf71950a40eef60a2`; subsequent checkpoint changes are documentation only. Ordinary .1323/Diagnostic .1324 actual reports/APK checks passed; current-head .1325 CI also passed. Selected minified opt-in/original/optimization/Move/Delete and oversized-queue refusal passed. Resumed Diagnostic tiny-artwork/library replacement and unoptimized legacy Delete/Move passed. No app-stack merge/Stable publication.

Latest execution checkpoint: [October22 release acceptance and restoration](handoffs/2026-10-22-release-acceptance.md). Next is the checkpoint’s finite user/RC acceptance (existing-data release upgrade, interactions, Bluetooth and spoken/focus TalkBack); no confirmed new #253 implementation defect. This documentation report is isolated from the unchanged app PR. Rooted POCO shell works on the clean-flashed A17 ROM; run-as still fails context lookup. Old Diagnostic card data preserved separately, regular Muon unchanged. Historical pre-flash private indexes no longer exist; do not assume older snapshots are a live baseline.

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
